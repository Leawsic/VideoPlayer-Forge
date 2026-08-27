package com.github.squi2rel.vp.network;

import com.github.squi2rel.vp.DataHolder;
import com.github.squi2rel.vp.ServerConfig;
import com.github.squi2rel.vp.provider.PlayerProviderSource;
import com.github.squi2rel.vp.provider.VideoInfo;
import com.github.squi2rel.vp.provider.VideoProviders;
import com.github.squi2rel.vp.video.IVideoListener;
import com.github.squi2rel.vp.video.VideoArea;
import com.github.squi2rel.vp.video.VideoScreen;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;

import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static com.github.squi2rel.vp.VideoPlayerMain.LOGGER;
import static com.github.squi2rel.vp.network.ByteBufUtils.readString;
import static com.github.squi2rel.vp.network.ByteBufUtils.writeString;
import static com.github.squi2rel.vp.network.PacketID.*;
import static com.github.squi2rel.vp.video.VideoScreen.MAX_NAME_LENGTH;

public class ServerPacketHandler {
    public static void handle(ServerPlayer player, ByteBuf buf) {
        short type = buf.readUnsignedByte();
        LOGGER.info("server type: {}", type);
        switch (type) {
            case CONFIG -> {
                ByteBufUtils.readString(buf, 16);
                DataHolder.lock();
                DataHolder.allPlayers.add(player.getUUID());
                DataHolder.unlock();
            }
            case REQUEST -> {
                if (DataHolder.isBlacklisted(player.getUUID())) {
                    player.sendSystemMessage(Component.literal("你已被加入视频播放黑名单").withStyle(ChatFormatting.RED));
                    return;
                }
                VideoArea area = getArea(player, readName(buf));
                if (area == null) return;
                VideoScreen screen = area.getScreen(readName(buf));
                if (screen == null) return;
                String url = ByteBufUtils.readString(buf, 256);
                if (fetchSource(player, url, screen::addInfo)) return;
            }
            case SYNC -> {
                VideoArea area = getArea(player, readName(buf));
                if (area == null) return;
                VideoScreen screen = area.getScreen(readName(buf));
                if (screen == null || screen.currentPlaying() == null) return;
                sendTo(player, sync(screen, screen.getProgress()));
            }
            case CREATE_AREA -> {
                // TODO check permission
                VideoArea area = VideoArea.from(ByteBufUtils.readVec3(buf), ByteBufUtils.readVec3(buf), readName(buf), player.level().dimension().location().toString());
                area.initServer();
                DataHolder.lock();
                DataHolder.areas.computeIfAbsent(area.dim, k -> new HashMap<>()).put(area.name, area);
                DataHolder.unlock();
                player.sendSystemMessage(Component.literal("已成功在世界 " + player.level().dimension().location() + " 创建名为 " + area.name + " 的观影区!").withStyle(ChatFormatting.GREEN));
            }
            case REMOVE_AREA -> {
                // TODO check permission
                VideoArea area = getArea(player, readName(buf));
                if (area == null) return;
                DataHolder.lock();
                DataHolder.areas.get(area.dim).remove(area.name).remove();
                if (area.hasPlayer()) {
                    byte[] data = removeArea(area);
                    PlayerList pm = Objects.requireNonNull(player.getServer()).getPlayerList();
                    area.forEachPlayer(p -> sendTo(pm.getPlayer(p), data));
                }
                DataHolder.unlock();
                player.sendSystemMessage(Component.literal("已成功在世界 " + player.level().dimension().location() + " 移除名为 " + area.name + " 的观影区!").withStyle(ChatFormatting.GREEN));
            }
            case CREATE_SCREEN -> {
                // TODO check permission
                VideoArea area = getArea(player, readName(buf));
                if (area == null) return;
                VideoScreen screen = VideoScreen.read(buf, area);
                readUV(buf, screen);
                readScale(buf, screen);
                screen.readMeta(buf);
                screen.initServer();
                DataHolder.lock();
                area.addScreen(screen);
                if (area.hasPlayer()) {
                    byte[] data = createScreen(List.of(screen));
                    PlayerList pm = Objects.requireNonNull(player.getServer()).getPlayerList();
                    area.forEachPlayer(p -> sendTo(pm.getPlayer(p), data));
                }
                DataHolder.unlock();
                player.sendSystemMessage(Component.literal("已成功在观影区 " + area.name + " 创建名为 " + screen.name + " 的屏幕!").withStyle(ChatFormatting.GREEN));
            }
            case REMOVE_SCREEN -> {
                // TODO check permission
                VideoArea area = getArea(player, readName(buf));
                if (area == null) return;
                DataHolder.lock();
                VideoScreen screen = area.removeScreen(readName(buf));
                if (screen != null && area.hasPlayer()) {
                    byte[] data = removeScreen(screen);
                    PlayerList pm = Objects.requireNonNull(player.getServer()).getPlayerList();
                    area.forEachPlayer(p -> sendTo(pm.getPlayer(p), data));
                    player.sendSystemMessage(Component.literal("已成功在观影区 " + area.name + " 移除名为 " + screen.name + " 的屏幕!").withStyle(ChatFormatting.GREEN));
                }
                DataHolder.unlock();
            }
            case SKIP -> {
                VideoArea area = getArea(player, readName(buf));
                if (area == null) return;
                VideoScreen screen = area.getScreen(readName(buf));
                if (screen == null) return;
                boolean force = buf.readBoolean();
                if (force) {
                    // TODO check permission
                    screen.skip();
                    return;
                }
                screen.voteSkip(player.getUUID());
                Component s = Component.literal("玩家 %s 已投票跳过 %s 上的视频 还需 %d 个玩家".formatted(
                        player.getName().getString(), screen.name, screen.skipped() == 0 ? 0 : (int) (area.players() * screen.skipPercent - screen.skipped() + 1)
                ));
                player.sendSystemMessage(Component.literal("已投票跳过此视频").withStyle(ChatFormatting.GOLD));
                PlayerList pm = Objects.requireNonNull(player.getServer()).getPlayerList();
                area.forEachPlayer(p -> Objects.requireNonNull(pm.getPlayer(p)).sendSystemMessage(s));
            }
            case STOP -> {
                VideoArea area = getArea(player, readName(buf));
                if (area == null) return;
                VideoScreen screen = area.getScreen(readName(buf));
                if (screen != null) screen.stop();
            }
            case PAUSE -> {
                VideoArea area = getArea(player, readName(buf));
                if (area == null) return;
                VideoScreen screen = area.getScreen(readName(buf));
                if (screen != null) screen.pause();
            }
            case RESUME -> {
                VideoArea area = getArea(player, readName(buf));
                if (area == null) return;
                VideoScreen screen = area.getScreen(readName(buf));
                if (screen != null) screen.resume();
            }
            case SEEK -> {
                VideoArea area = getArea(player, readName(buf));
                if (area == null) return;
                VideoScreen screen = area.getScreen(readName(buf));
                long progress = buf.readLong();
                if (screen != null) screen.seek(progress);
            }
            case SKIP_PERCENT -> {
                // TODO check permission
                VideoArea area = getArea(player, readName(buf));
                if (area == null) return;
                VideoScreen screen = area.getScreen(readName(buf));
                if (screen == null) return;
                screen.setSkipPercent(buf.readFloat());
                player.sendSystemMessage(Component.literal("屏幕 " + screen.name + " 的投票跳过比例已设置为 " + screen.skipPercent).withStyle(ChatFormatting.GREEN));
            }
            case IDLE_PLAY -> {
                // TODO
                VideoArea area = getArea(player, readName(buf));
                if (area == null) return;
                VideoScreen screen = area.getScreen(readName(buf));
                if (screen == null) return;
                readString(buf, 1024);
            }
            case SET_UV -> {
                // TODO check permission
                VideoArea area = getArea(player, readName(buf));
                if (area == null) return;
                VideoScreen screen = area.getScreen(readName(buf));
                if (screen == null) return;
                readUV(buf, screen);
                if (area.hasPlayer()) {
                    byte[] data = setUV(screen, screen.u1, screen.v1, screen.u2, screen.v2);
                    PlayerList pm = Objects.requireNonNull(player.getServer()).getPlayerList();
                    area.forEachPlayer(p -> sendTo(pm.getPlayer(p), data));
                }
            }
            case OPEN_MENU -> {
                // TODO
                VideoArea area = getArea(player, readName(buf));
                if (area == null) return;
                VideoScreen screen = area.getScreen(readName(buf));
                if (screen == null) return;
            }
            case SET_META -> {
                short id = buf.readUnsignedByte();
                if (id > Action.VALUES.length) {
                    player.connection.disconnect(Component.literal("Unknown action type: " + id));
                    return;
                }
                Action action = Action.VALUES[id];
                VideoArea area = getArea(player, readName(buf));
                if (area == null) return;
                VideoScreen screen = area.getScreen(readName(buf));
                if (screen == null) return;
                int value = buf.readInt();
                if (!action.verify(value)) {
                    player.connection.disconnect(Component.literal("Invalid value: " + value));
                    return;
                }
                action.apply(screen, value);
                if (area.hasPlayer()) {
                    byte[] data = setMeta(screen, id, value);
                    PlayerList pm = Objects.requireNonNull(player.getServer()).getPlayerList();
                    area.forEachPlayer(p -> sendTo(pm.getPlayer(p), data));
                }
            }
            case SET_CUSTOM_META -> {
                VideoArea area = getArea(player, readName(buf));
                if (area == null) return;
                VideoScreen screen = area.getScreen(readName(buf));
                if (screen == null) return;
                String key = readName(buf);
                int value = buf.readInt();
                boolean remove = buf.readBoolean();
                if (remove) {
                    screen.meta.remove(key);
                } else {
                    screen.meta.put(key, value);
                }
                if (area.hasPlayer()) {
                    byte[] data = setCustomMeta(screen, key, value, remove);
                    PlayerList pm = Objects.requireNonNull(player.getServer()).getPlayerList();
                    area.forEachPlayer(p -> sendTo(pm.getPlayer(p), data));
                }
            }
            case SET_SCALE -> {
                VideoArea area = getArea(player, readName(buf));
                if (area == null) return;
                VideoScreen screen = area.getScreen(readName(buf));
                if (screen == null) return;
                boolean fill = buf.readBoolean();
                float scaleX = buf.readFloat();
                float scaleY = buf.readFloat();
                if (scaleX < 0.0625f || scaleX > 16f || scaleY < 0.0625f || scaleY > 16f) {
                    throw new IllegalArgumentException("Invalid scale value: " + scaleX + " " + scaleY);
                }
                screen.fill = fill;
                screen.scaleX = scaleX;
                screen.scaleY = scaleY;
                if (area.hasPlayer()) {
                    byte[] data = setScale(screen, fill, scaleX, scaleY);
                    PlayerList pm = Objects.requireNonNull(player.getServer()).getPlayerList();
                    area.forEachPlayer(p -> sendTo(pm.getPlayer(p), data));
                }
            }
            case AUTO_SYNC -> {
                VideoArea area = getArea(player, readName(buf));
                if (area == null) return;
                VideoScreen screen = area.getScreen(readName(buf));
                if (screen == null) return;
                VideoInfo info = screen.currentPlaying();
                if (info == null || !info.seekable()) return;
                long clientTime = buf.readLong();
                IVideoListener listener = screen.getListener();
                if (listener == null) return;
                long progress = listener.getProgress();
                if (progress <= 0) return;
                sendTo(player, autoSync(screen, clientTime, progress));
            }
            default -> player.connection.disconnect(Component.literal("Unknown packet type: " + type));
        }
        if (buf.readableBytes() > 0) {
            player.connection.disconnect(Component.literal("Illegal packet! Remaining: " + buf.readableBytes()));
        }
    }

    private static boolean fetchSource(ServerPlayer player, String url, Consumer<VideoInfo> cb) {
        CompletableFuture<VideoInfo> video = VideoProviders.from(url, new PlayerProviderSource(player));
        if (video == null) {
            player.sendSystemMessage(Component.literal("无法解析视频源"));
            return true;
        }
        CompletableFuture.supplyAsync(() -> {
            try {
                LOGGER.info("start fetch");
                return video.get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).thenAccept(v -> {
            try {
                if (v == null) {
                    player.sendSystemMessage(Component.literal("无法解析视频源"));
                    return;
                }
                cb.accept(v);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        return false;
    }

    private static VideoArea getArea(ServerPlayer player, String name) {
        String dim = player.serverLevel().dimension().location().toString();
        DataHolder.lock();
        HashMap<String, VideoArea> map = DataHolder.areas.get(dim);
        VideoArea area = map == null ? null : map.get(name);
        DataHolder.unlock();
        // TODO check bypass permission
        return area != null && area.containsPlayer(player.getUUID()) ? area : null;
    }

    private static String readName(ByteBuf buf) {
        return ByteBufUtils.readString(buf, MAX_NAME_LENGTH);
    }

    private static ByteBuf create(int id) {
        ByteBuf buf = PooledByteBufAllocator.DEFAULT.buffer();
        buf.writeByte((byte) id);
        return buf;
    }

    private static byte[] toByteArray(ByteBuf buf) {
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        buf.release();
        return bytes;
    }

    public static void readUV(ByteBuf buf, VideoScreen screen) {
        screen.u1 = buf.readFloat();
        screen.v1 = buf.readFloat();
        screen.u2 = buf.readFloat();
        screen.v2 = buf.readFloat();
    }

    public static void writeUV(ByteBuf buf, VideoScreen screen) {
        buf.writeFloat(screen.u1);
        buf.writeFloat(screen.v1);
        buf.writeFloat(screen.u2);
        buf.writeFloat(screen.v2);
    }

    public static void readScale(ByteBuf buf, VideoScreen screen) {
        screen.fill = buf.readBoolean();
        screen.scaleX = buf.readFloat();
        screen.scaleY = buf.readFloat();
    }

    public static void writeScale(ByteBuf buf, VideoScreen screen) {
        buf.writeBoolean(screen.fill);
        buf.writeFloat(screen.scaleX);
        buf.writeFloat(screen.scaleY);
    }

    public static void sendTo(ServerPlayer player, byte[] bytes) {
        if (player == null) return;
        VideoPayload.sendToPlayer(player, bytes);
    }

    public static byte[] config(String version, ServerConfig config) {
        ByteBuf buf = create(CONFIG);
        writeString(buf, version);
        writeString(buf, config.remoteControlName);
        buf.writeFloat(config.remoteControlId);
        buf.writeFloat(config.remoteControlRange);
        buf.writeFloat(config.noControlRange);
        return toByteArray(buf);
    }

    public static byte[] request(VideoScreen screen, VideoInfo info) {
        ByteBuf buf = create(REQUEST);
        writeString(buf, screen.area.name);
        writeString(buf, screen.name);
        VideoInfo.write(buf, info);
        return toByteArray(buf);
    }

    public static byte[] sync(VideoScreen screen, long time) {
        ByteBuf buf = create(SYNC);
        writeString(buf, screen.area.name);
        writeString(buf, screen.name);
        buf.writeLong(time);
        return toByteArray(buf);
    }

    public static byte[] createArea(VideoArea area) {
        ByteBuf buf = create(CREATE_AREA);
        writeString(buf, area.name);
        VideoArea.write(buf, area);
        return toByteArray(buf);
    }

    public static byte[] removeArea(VideoArea area) {
        ByteBuf buf = create(REMOVE_AREA);
        writeString(buf, area.name);
        return toByteArray(buf);
    }

    public static byte[] createScreen(List<VideoScreen> screens) {
        ByteBuf buf = create(CREATE_SCREEN);
        writeString(buf, screens.get(0).area.name);
        buf.writeByte(screens.size());
        for (VideoScreen screen : screens) {
            VideoScreen.write(buf, screen);
            writeUV(buf, screen);
            writeScale(buf, screen);
            screen.writeMeta(buf);
        }
        return toByteArray(buf);
    }

    public static byte[] removeScreen(VideoScreen screen) {
        ByteBuf buf = create(REMOVE_SCREEN);
        writeString(buf, screen.area.name);
        writeString(buf, screen.name);
        return toByteArray(buf);
    }

    public static byte[] loadArea(VideoArea area) {
        ByteBuf buf = create(LOAD_AREA);
        writeString(buf, area.name);
        for (VideoScreen screen : area.screens) {
            VideoInfo info = screen.currentPlaying();
            if (info == null) continue;
            writeString(buf, screen.name);
            buf.writeBoolean(screen.isPaused());
            VideoInfo.write(buf, info);
            buf.writeLong(screen.getProgress());
        }
        return toByteArray(buf);
    }

    public static byte[] unloadArea(VideoArea area) {
        ByteBuf buf = create(UNLOAD_AREA);
        writeString(buf, area.name);
        return toByteArray(buf);
    }

    public static byte[] updatePlaylist(List<VideoScreen> screens) {
        ByteBuf buf = create(UPDATE_PLAYLIST);
        writeString(buf, screens.get(0).area.name);
        buf.writeByte(screens.size());
        for (VideoScreen screen : screens) {
            writeString(buf, screen.name);
            buf.writeByte(screen.infos.size());
            for (VideoInfo info : screen.infos) {
                writeString(buf, info.playerName());
                writeString(buf, info.name());
            }
        }
        return toByteArray(buf);
    }

    public static byte[] skip(VideoScreen screen) {
        ByteBuf buf = create(SKIP);
        writeString(buf, screen.area.name);
        writeString(buf, screen.name);
        return toByteArray(buf);
    }

    public static byte[] stop(VideoScreen screen) {
        ByteBuf buf = create(STOP);
        writeString(buf, screen.area.name);
        writeString(buf, screen.name);
        return toByteArray(buf);
    }

    public static byte[] pause(VideoScreen screen) {
        ByteBuf buf = create(PAUSE);
        writeString(buf, screen.area.name);
        writeString(buf, screen.name);
        return toByteArray(buf);
    }

    public static byte[] resume(VideoScreen screen) {
        ByteBuf buf = create(RESUME);
        writeString(buf, screen.area.name);
        writeString(buf, screen.name);
        return toByteArray(buf);
    }

    public static byte[] seek(VideoScreen screen, long progress) {
        ByteBuf buf = create(SEEK);
        writeString(buf, screen.area.name);
        writeString(buf, screen.name);
        buf.writeLong(progress);
        return toByteArray(buf);
    }

    public static byte[] execute(String command) {
        ByteBuf buf = create(EXECUTE);
        writeString(buf, command);
        return toByteArray(buf);
    }

    public static byte[] setUV(VideoScreen screen, float u1, float v1, float u2, float v2) {
        ByteBuf buf = create(SET_UV);
        writeString(buf, screen.area.name);
        writeString(buf, screen.name);
        buf.writeFloat(u1);
        buf.writeFloat(v1);
        buf.writeFloat(u2);
        buf.writeFloat(v2);
        return toByteArray(buf);
    }

    public static byte[] setMeta(VideoScreen screen, int actionId, int value) {
        ByteBuf buf = create(SET_META);
        buf.writeByte(actionId);
        writeString(buf, screen.area.name);
        writeString(buf, screen.name);
        buf.writeInt(value);
        return toByteArray(buf);
    }

    public static byte[] setCustomMeta(VideoScreen screen, String key, int value, boolean remove) {
        ByteBuf buf = create(SET_CUSTOM_META);
        writeString(buf, screen.area.name);
        writeString(buf, screen.name);
        ByteBufUtils.writeString(buf, key);
        buf.writeInt(value);
        buf.writeBoolean(remove);
        return toByteArray(buf);
    }

    public static byte[] setScale(VideoScreen screen, boolean fill, float scaleX, float scaleY) {
        ByteBuf buf = create(SET_SCALE);
        writeString(buf, screen.area.name);
        writeString(buf, screen.name);
        buf.writeBoolean(fill);
        buf.writeFloat(scaleX);
        buf.writeFloat(scaleY);
        return toByteArray(buf);
    }

    public static byte[] autoSync(VideoScreen screen, long clientTime, long progress) {
        ByteBuf buf = create(AUTO_SYNC);
        writeString(buf, screen.area.name);
        writeString(buf, screen.name);
        buf.writeLong(clientTime);
        buf.writeLong(progress);
        return toByteArray(buf);
    }
}
