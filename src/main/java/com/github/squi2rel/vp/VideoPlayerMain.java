package com.github.squi2rel.vp;

import com.github.squi2rel.vp.network.ServerPacketHandler;
import com.github.squi2rel.vp.network.VideoPayload;
import com.github.squi2rel.vp.provider.VideoProviders;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import net.minecraft.commands.Commands;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static com.github.squi2rel.vp.DataHolder.areas;
import static com.github.squi2rel.vp.DataHolder.config;
import static com.github.squi2rel.vp.DataHolder.lock;
import static com.github.squi2rel.vp.DataHolder.unlock;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;
import static net.minecraft.network.chat.Component.literal;

@Mod(VideoPlayerMain.MOD_ID)
public class VideoPlayerMain {
    public static final String MOD_ID = "videoplayer";
    public static final String version = ModList.get().getModContainerById(MOD_ID)
            .map(c -> c.getModInfo().getVersion().toString())
            .orElse("0.0.0");
    public static Throwable error = null;

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, VideoPlayerMain::newDaemon);

    public VideoPlayerMain() {
        VideoProviders.register();
        MinecraftForge.EVENT_BUS.register(this);
        var modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(this::commonSetup);
        modBus.addListener(this::clientSetup);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(VideoPayload::register);
    }

    private void clientSetup(final FMLClientSetupEvent event) {
        event.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> VideoPlayerClient::init));
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        DataHolder.load(event.getServer());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        DataHolder.stop(event.getServer());
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            DataHolder.update();
        }
    }

    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            DataHolder.playerJoin(player);
        }
    }

    @SubscribeEvent
    public void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        DataHolder.playerLeave(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("").then(Commands.argument("command", StringArgumentType.greedyString()).executes(s -> {
            if (!s.getSource().isPlayer()) return 0;
            ServerPacketHandler.sendTo(s.getSource().getPlayer(), ServerPacketHandler.execute(s.getArgument("command", String.class)));
            return 1;
        })));

        event.getDispatcher().register(literal("vlcadmin")
                .requires(s -> s.hasPermission(2))
                .then(literal("area")
                        .then(literal("list")
                                .executes(s -> {
                                    String dim = s.getSource().getLevel().dimension().location().toString();
                                    lock();
                                    var map = areas.get(dim);
                                    unlock();
                                    if (map == null || map.isEmpty()) {
                                        s.getSource().sendSuccess(() -> literal("当前维度 " + dim + " 无观影区"), false);
                                        return 1;
                                    }
                                    StringBuilder sb = new StringBuilder("维度 " + dim + " 观影区列表 (" + map.size() + "):\n");
                                    for (var area : map.values()) {
                                        sb.append("  ").append(area.name)
                                                .append(" [").append((int) area.min.x).append(",").append((int) area.min.y).append(",").append((int) area.min.z)
                                                .append(" -> ").append((int) area.max.x).append(",").append((int) area.max.y).append(",").append((int) area.max.z)
                                                .append("] 屏幕数: ").append(area.screens.size())
                                                .append(" 玩家数: ").append(area.players()).append("\n");
                                    }
                                    s.getSource().sendSuccess(() -> literal(sb.toString()), false);
                                    return 1;
                                }))
                        .then(literal("info")
                                .then(argument("name", StringArgumentType.string())
                                        .executes(s -> {
                                            String name = s.getArgument("name", String.class);
                                            String dim = s.getSource().getLevel().dimension().location().toString();
                                            lock();
                                            var map = areas.get(dim);
                                            var area = map != null ? map.get(name) : null;
                                            unlock();
                                            if (area == null) {
                                                s.getSource().sendSuccess(() -> literal("未找到观影区: " + name), false);
                                                return 1;
                                            }
                                            StringBuilder sb = new StringBuilder("观影区 " + name + ":\n");
                                            sb.append("  维度: ").append(dim).append("\n");
                                            sb.append("  范围: [").append((int) area.min.x).append(",").append((int) area.min.y).append(",").append((int) area.min.z)
                                                    .append(" -> ").append((int) area.max.x).append(",").append((int) area.max.y).append(",").append((int) area.max.z).append("]\n");
                                            sb.append("  屏幕数: ").append(area.screens.size()).append("\n");
                                            sb.append("  玩家数: ").append(area.players()).append("\n");
                                            if (!area.screens.isEmpty()) {
                                                sb.append("  屏幕列表:\n");
                                                for (var screen : area.screens) {
                                                    sb.append("    ").append(screen.name).append(" (source: ").append(screen.source).append(")\n");
                                                }
                                            }
                                            s.getSource().sendSuccess(() -> literal(sb.toString()), false);
                                            return 1;
                                        })))
                        .then(literal("remove")
                                .then(argument("name", StringArgumentType.string())
                                        .executes(s -> {
                                            String name = s.getArgument("name", String.class);
                                            String dim = s.getSource().getLevel().dimension().location().toString();
                                            lock();
                                            var map = areas.get(dim);
                                            var area = map != null ? map.remove(name) : null;
                                            unlock();
                                            if (area == null) {
                                                s.getSource().sendSuccess(() -> literal("未找到观影区: " + name), false);
                                                return 1;
                                            }
                                            if (area.hasPlayer()) {
                                                var pm = s.getSource().getServer().getPlayerList();
                                                var data = ServerPacketHandler.removeArea(area);
                                                area.forEachPlayer(p -> ServerPacketHandler.sendTo(pm.getPlayer(p), data));
                                            }
                                            area.remove();
                                            DataHolder.save();
                                            s.getSource().sendSuccess(() -> literal("已删除观影区: " + name).withStyle(net.minecraft.ChatFormatting.GREEN), false);
                                            return 1;
                                        }))))
                .then(literal("screen")
                        .then(literal("list")
                                .then(argument("area", StringArgumentType.string())
                                        .executes(s -> {
                                            String areaName = s.getArgument("area", String.class);
                                            String dim = s.getSource().getLevel().dimension().location().toString();
                                            lock();
                                            var map = areas.get(dim);
                                            var area = map != null ? map.get(areaName) : null;
                                            unlock();
                                            if (area == null) {
                                                s.getSource().sendSuccess(() -> literal("未找到观影区: " + areaName), false);
                                                return 1;
                                            }
                                            StringBuilder sb = new StringBuilder("观影区 " + areaName + " 屏幕列表 (" + area.screens.size() + "):\n");
                                            for (var screen : area.screens) {
                                                sb.append("  ").append(screen.name)
                                                        .append(" source: ").append(screen.source)
                                                        .append(" pos: [").append((int) screen.p1.x).append(",").append((int) screen.p1.y).append(",").append((int) screen.p1.z).append("]\n");
                                            }
                                            s.getSource().sendSuccess(() -> literal(sb.toString()), false);
                                            return 1;
                                        }))))
                .then(literal("blacklist")
                        .then(literal("list")
                                .executes(s -> {
                                    lock();
                                    var list = new java.util.ArrayList<>(config.blacklist);
                                    unlock();
                                    if (list.isEmpty()) {
                                        s.getSource().sendSuccess(() -> literal("黑名单为空"), false);
                                        return 1;
                                    }
                                    s.getSource().sendSuccess(() -> literal("黑名单 (" + list.size() + "):\n" + String.join("\n", list)), false);
                                    return 1;
                                }))
                        .then(literal("add")
                                .then(argument("player", StringArgumentType.greedyString())
                                        .executes(s -> {
                                            String name = s.getArgument("player", String.class);
                                            lock();
                                            config.blacklist.add(name);
                                            DataHolder.save();
                                            unlock();
                                            s.getSource().sendSuccess(() -> literal("已加入黑名单: " + name).withStyle(net.minecraft.ChatFormatting.GREEN), false);
                                            return 1;
                                        })))
                        .then(literal("remove")
                                .then(argument("player", StringArgumentType.greedyString())
                                        .executes(s -> {
                                            String name = s.getArgument("player", String.class);
                                            lock();
                                            boolean removed = config.blacklist.remove(name);
                                            DataHolder.save();
                                            unlock();
                                            if (removed) {
                                                s.getSource().sendSuccess(() -> literal("已移出黑名单: " + name).withStyle(net.minecraft.ChatFormatting.GREEN), false);
                                            } else {
                                                s.getSource().sendSuccess(() -> literal("不在黑名单中: " + name).withStyle(net.minecraft.ChatFormatting.YELLOW), false);
                                            }
                                            return 1;
                                        })))
                        .then(literal("clear")
                                .executes(s -> {
                                    lock();
                                    config.blacklist.clear();
                                    DataHolder.save();
                                    unlock();
                                    s.getSource().sendSuccess(() -> literal("黑名单已清空").withStyle(net.minecraft.ChatFormatting.GREEN), false);
                                    return 1;
                                }))));
    }

    private static Thread newDaemon(Runnable task) {
        Thread t = new Thread(task);
        t.setDaemon(true);
        return t;
    }
}
