package com.github.squi2rel.vp;

import com.github.squi2rel.vp.network.ServerPacketHandler;
import com.github.squi2rel.vp.video.VideoArea;
import com.github.squi2rel.vp.video.VideoScreen;
import com.google.gson.Gson;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;

public class DataHolder {
    public static ServerConfig config = new ServerConfig();
    public static ArrayList<UUID> allPlayers = new ArrayList<>();
    public static HashMap<UUID, String> playerDim = new HashMap<>();

    public static final Path configPath = FMLPaths.CONFIGDIR.get().resolve("videoplayer.json");
    public static HashMap<String, HashMap<String, VideoArea>> areas = new HashMap<>();

    private static final Gson gson = new Gson();
    private static final ReentrantLock lock = new ReentrantLock();

    public static MinecraftServer server;

    public static void update() {
        if (server == null) return;
        PlayerList pm = server.getPlayerList();
        lock();
        for (UUID uuid : allPlayers) {
            ServerPlayer player = pm.getPlayer(uuid);
            if (player == null) continue;
            if (isBlacklisted(uuid)) continue;
            String dim = player.serverLevel().dimension().location().toString();
            HashMap<String, VideoArea> all = areas.get(dim);
            if (all == null || all.isEmpty()) continue;
            for (VideoArea area : all.values()) {
                if (area.inBounds(player.position())) {
                    if (area.addPlayer(player.getUUID())) {
                        ServerPacketHandler.sendTo(player, ServerPacketHandler.createArea(area));
                        if (area.screens.isEmpty()) {
                            ServerPacketHandler.sendTo(player, ServerPacketHandler.loadArea(area));
                            continue;
                        }
                        ServerPacketHandler.sendTo(player, ServerPacketHandler.createScreen(area.screens));
                        ServerPacketHandler.sendTo(player, ServerPacketHandler.loadArea(area));
                        ServerPacketHandler.sendTo(player, ServerPacketHandler.updatePlaylist(area.screens));
                        player.displayClientMessage(Component.literal("进入观影区 " + area.name).withStyle(ChatFormatting.DARK_AQUA), true);
                    }
                } else {
                    if (area.removePlayer(player.getUUID())) {
                        ServerPacketHandler.sendTo(player, ServerPacketHandler.unloadArea(area));
                        ServerPacketHandler.sendTo(player, ServerPacketHandler.removeArea(area));
                        player.displayClientMessage(Component.literal("离开观影区 " + area.name).withStyle(ChatFormatting.DARK_AQUA), true);
                    }
                }
            }
        }
        for (Map.Entry<UUID, String> entry : playerDim.entrySet()) {
            ServerPlayer player = pm.getPlayer(entry.getKey());
            if (player == null) continue;
            String dim = player.serverLevel().dimension().location().toString();
            if (!dim.equals(entry.getValue())) {
                HashMap<String, VideoArea> map = areas.get(entry.getValue());
                if (map == null) continue;
                for (VideoArea area : map.values()) {
                    if (area.removePlayer(player.getUUID())) {
                        ServerPacketHandler.sendTo(player, ServerPacketHandler.unloadArea(area));
                        ServerPacketHandler.sendTo(player, ServerPacketHandler.removeArea(area));
                    }
                }
            }
        }
        for (ServerPlayer player : pm.getPlayers()) {
            playerDim.put(player.getUUID(), player.serverLevel().dimension().location().toString());
        }
        unlock();
    }

    public static void lock() {
        lock.lock();
    }

    public static void unload(MinecraftServer s) {
        PlayerList pm = s.getPlayerList();
        lock();
        for (HashMap<String, VideoArea> map : areas.values()) {
            for (VideoArea area : map.values()) {
                if (!area.hasPlayer()) continue;
                byte[] data = ServerPacketHandler.removeArea(area);
                area.forEachPlayer(u -> ServerPacketHandler.sendTo(pm.getPlayer(u), data));
            }
        }
        unlock();
    }

    public static void playerJoin(ServerPlayer player) {
        ServerPacketHandler.sendTo(player, ServerPacketHandler.config(VideoPlayerMain.version, config));
    }

    public static void playerLeave(UUID uuid) {
        lock();
        allPlayers.remove(uuid);
        playerDim.remove(uuid);
        unlock();
        CompletableFuture.runAsync(() -> {
            lock.lock();
            for (HashMap<String, VideoArea> value : areas.values()) {
                for (VideoArea area : value.values()) {
                    area.removePlayer(uuid);
                }
            }
            lock.unlock();
        });
    }

    public static void unlock() {
        lock.unlock();
    }

    public static boolean isBlacklisted(UUID uuid) {
        ServerPlayer player = server.getPlayerList().getPlayer(uuid);
        if (player == null) return false;
        return config.blacklist.contains(player.getName().getString());
    }

    public static void stop(MinecraftServer server) {
        save();
        unload(server);
    }

    public static void save() {
        lock();
        ArrayList<VideoArea> all = new ArrayList<>();
        for (HashMap<String, VideoArea> child : areas.values()) {
            all.addAll(child.values());
        }
        config.areas = all;
        writeString(configPath, gson.toJson(config));
        unlock();
    }

    public static void load(MinecraftServer server) {
        DataHolder.server = server;
        lock();
        try {
            config = gson.fromJson(readString(configPath), ServerConfig.class);
        } catch (Exception e) {
            config = new ServerConfig();
            save();
        }
        for (VideoArea area : config.areas) {
            for (VideoScreen screen : area.screens) {
                if (screen.meta == null) screen.meta = new HashMap<>();
            }
            area.initServer();
            area.afterLoad();
            areas.computeIfAbsent(area.dim, k -> new HashMap<>()).put(area.name, area);
        }
        config.areas = null;
        unlock();
    }

    public static String readString(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void writeString(Path path, String str) {
        try {
            Files.writeString(path, str);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
