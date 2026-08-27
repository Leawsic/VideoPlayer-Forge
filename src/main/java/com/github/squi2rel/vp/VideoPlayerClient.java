package com.github.squi2rel.vp;

import com.github.squi2rel.vp.network.PacketID;
import com.github.squi2rel.vp.network.VideoPayload;
import com.github.squi2rel.vp.preset.PresetManager;
import com.github.squi2rel.vp.preset.ScreenPreset;
import com.github.squi2rel.vp.provider.VideoInfo;
import com.github.squi2rel.vp.video.*;
import com.google.gson.Gson;
import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.world.BossEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.commons.lang3.StringUtils;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static com.github.squi2rel.vp.VideoPlayerMain.LOGGER;
import static com.github.squi2rel.vp.VideoPlayerMain.error;

@SuppressWarnings({"DataFlowIssue"})
public class VideoPlayerClient {
    public static final Path configPath = FMLPaths.CONFIGDIR.get().resolve("videoplayer-client.json");
    public static final Minecraft client = Minecraft.getInstance();
    public static Config config;
    private static final Gson gson = new Gson();

    public static final HashMap<String, ClientVideoArea> areas = new HashMap<>();
    public static final ArrayList<ClientVideoScreen> screens = new ArrayList<>();
    private static final TouchHandler touchHandler = new TouchHandler();
    private static ClientVideoScreen currentLooking, currentScreen;
    private static boolean isInArea = false;
    private static LerpingBossEvent bossBar = null;
    private static boolean bossBarAdded = false;
    private static boolean keyPressed = false;

    public static boolean connected = false;
    public static String remoteControlName = "minecraft:iron_ingot";
    public static float remoteControlId = -1;
    public static float remoteControlRange = 64;
    public static float noControlRange = 16;
    public static boolean remoteControl = false;

    public static Runnable disconnectHandler = () -> {};

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_AREAS = (context, builder) -> {
        for (ClientVideoArea a : areas.values()) {
            if (a.name.startsWith(builder.getRemaining())) {
                builder.suggest("\"" + a.name.replace("\\", "\\\\") + "\"");
            }
        }
        return builder.buildFuture();
    };

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_SCREENS = (context, builder) -> {
        ClientVideoArea area = areas.get(context.getArgument("area", String.class));
        if (area == null) return Suggestions.empty();
        for (VideoScreen screen : area.screens) {
            if (!((ClientVideoScreen) screen).interactable) continue;
            if (screen.name.startsWith(builder.getRemaining())) {
                builder.suggest("\"" + screen.name.replace("\\", "\\\\") + "\"");
            }
        }
        return builder.buildFuture();
    };

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_REAL_SCREENS = (context, builder) -> {
        ClientVideoArea area = areas.get(context.getArgument("area", String.class));
        if (area == null) return Suggestions.empty();
        for (VideoScreen screen : area.screens) {
            if (!screen.source.isEmpty() || !((ClientVideoScreen) screen).interactable) continue;
            if (screen.name.startsWith(builder.getRemaining())) {
                builder.suggest("\"" + screen.name.replace("\\", "\\\\") + "\"");
            }
        }
        return builder.buildFuture();
    };

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_PRESETS = (context, builder) -> {
        PresetManager.builtins().keySet().forEach(name -> suggestPreset(builder, name));
        PresetManager.customs().keySet().forEach(name -> suggestPreset(builder, name));
        return builder.buildFuture();
    };

    public static void init() {
        VlcDecoder.load();
        loadConfig();
        PresetManager.init();
        disconnectHandler = () -> client.execute(() -> {
            connected = false;
            for (ClientVideoArea area : areas.values()) {
                area.remove();
            }
            areas.clear();
            for (ClientVideoScreen screen : screens) {
                screen.cleanup();
            }
            screens.clear();
            currentLooking = null;
        });
        VideoPayload.clientHandler = buf -> {
            try {
                ClientPacketHandler.handle(buf);
            } catch (Exception e) {
                LOGGER.error("Exception while handling packet", e);
            }
        };
        MinecraftForge.EVENT_BUS.register(VideoPlayerClient.class);
        bossBar = new LerpingBossEvent(UUID.randomUUID(), Component.literal(""), 0, BossEvent.BossBarColor.WHITE, BossEvent.BossBarOverlay.PROGRESS, false, false, false);
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        if (error != null && event.getPlayer() != null) {
            event.getPlayer().displayClientMessage(Component.literal("VideoPlayer错误: libVLC库加载失败\n" + error + "\n查看日志获取更多信息").withStyle(ChatFormatting.RED), false);
        }
        if (config.alwaysConnected) ClientPacketHandler.config(VideoPlayerMain.version);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        disconnectHandler.run();
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        update();
        ScreenRenderer.render(event.getPoseStack(), event.getCamera());
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (client.player == null || client.level == null || client.screen != null || currentLooking == null) return;
        boolean pressed = client.options.keyUse.isDown();
        if (pressed && !keyPressed) {
            keyPressed = true;
            if (remoteControl || client.player.getItemInHand(InteractionHand.MAIN_HAND).isEmpty() && client.player.getItemInHand(InteractionHand.OFF_HAND).isEmpty()) {
                ClientPacketHandler.openMenu(currentLooking);
            }
        } else if (!pressed) {
            keyPressed = false;
        }
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("vlc")
                .then(Commands.literal("play")
                        .then(Commands.argument("url", StringArgumentType.greedyString())
                                .executes(s -> {
                                    if (checkInvalid(s, true)) return 0;
                                    ClientPacketHandler.request(currentScreen.getScreen(), s.getArgument("url", String.class));
                                    return 1;
                                })))
                .then(Commands.literal("playthat")
                        .then(Commands.argument("area", StringArgumentType.string()).suggests(SUGGEST_AREAS)
                                .then(Commands.argument("screen", StringArgumentType.string()).suggests(SUGGEST_REAL_SCREENS)
                                        .then(Commands.argument("url", StringArgumentType.greedyString())
                                                .executes(s -> {
                                                    ClientVideoScreen screen = getScreen(s);
                                                    if (screen == null) return 0;
                                                    ClientPacketHandler.request(screen.getScreen(), s.getArgument("url", String.class));
                                                    return 1;
                                                })))))
                .then(Commands.literal("skip")
                        .then(Commands.argument("force", BoolArgumentType.bool())
                                .executes(s -> {
                                    if (checkInvalid(s, true)) return 0;
                                    ClientPacketHandler.skip(currentScreen.getScreen(), s.getArgument("force", Boolean.class));
                                    return 1;
                                }))
                        .then(Commands.argument("area", StringArgumentType.string()).suggests(SUGGEST_AREAS)
                                .then(Commands.argument("screen", StringArgumentType.string()).suggests(SUGGEST_REAL_SCREENS)
                                        .then(Commands.argument("force", BoolArgumentType.bool())
                                                .executes(s -> {
                                                    ClientVideoScreen screen = getScreen(s);
                                                    if (screen == null) return 0;
                                                    ClientPacketHandler.skip(screen.getScreen(), s.getArgument("force", Boolean.class));
                                                    return 1;
                                                })
                                        )))
                        .executes(s -> {
                            if (checkInvalid(s, true)) return 0;
                            ClientPacketHandler.skip(currentScreen.getScreen(), false);
                            return 1;
                        })
                )
                .then(Commands.literal("volume")
                        .then(Commands.argument("volume", IntegerArgumentType.integer(0, 100))
                                .executes(s -> {
                                    int v = s.getArgument("volume", Integer.class);
                                    config.volume = v;
                                    saveConfig();
                                    s.getSource().sendSuccess(() -> Component.literal("音量已设置为 " + v + "%").withStyle(ChatFormatting.GREEN), false);
                                    ClientVideoScreen first = screens.stream().filter(cs -> cs.player instanceof VideoPlayer).findAny().orElse(null);
                                    if (first == null) return 1;
                                    first.player.setVolume(v);
                                    return 1;
                                })))
                .then(Commands.literal("createAreaHere")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(s -> createAreaHere(s, 16f))
                                .then(Commands.argument("radius", FloatArgumentType.floatArg(1f))
                                        .executes(s -> createAreaHere(s, s.getArgument("radius", Float.class))))))
                .then(Commands.literal("createArea")
                        .then(Commands.argument("x1", FloatArgumentType.floatArg())
                        .then(Commands.argument("y1", FloatArgumentType.floatArg())
                        .then(Commands.argument("z1", FloatArgumentType.floatArg())
                        .then(Commands.argument("x2", FloatArgumentType.floatArg())
                        .then(Commands.argument("y2", FloatArgumentType.floatArg())
                        .then(Commands.argument("z2", FloatArgumentType.floatArg())
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(s -> {
                                    if (checkInvalid(s, false)) return 0;
                                    ClientPacketHandler.createArea(
                                            new Vector3f(
                                                s.getArgument("x1", Float.class),
                                                s.getArgument("y1", Float.class),
                                                s.getArgument("z1", Float.class)
                                            ),
                                            new Vector3f(
                                                s.getArgument("x2", Float.class),
                                                s.getArgument("y2", Float.class),
                                                s.getArgument("z2", Float.class)
                                            ),
                                            s.getArgument("name", String.class)
                                    );
                                    return 1;
                                })))))))))
                .then(Commands.literal("createScreenHere")
                        .then(Commands.argument("area", StringArgumentType.string()).suggests(SUGGEST_AREAS)
                                .then(Commands.argument("name", StringArgumentType.string())
                                        .executes(s -> createScreenHere(s, null))
                                        .then(Commands.argument("preset", StringArgumentType.string()).suggests(SUGGEST_PRESETS)
                                                .executes(s -> createScreenHere(s, s.getArgument("preset", String.class)))))))
                .then(Commands.literal("removeArea")
                        .then(Commands.argument("name", StringArgumentType.string()).suggests(SUGGEST_AREAS)
                                .executes(s -> {
                                    if (checkInvalid(s, false)) return 0;
                                    String name = s.getArgument("name", String.class);
                                    ClientPacketHandler.removeArea(name);
                                    return 1;
                                })))
                .then(Commands.literal("createScreen")
                        .then(Commands.argument("area", StringArgumentType.string()).suggests(SUGGEST_AREAS)
                        .then(Commands.argument("name", StringArgumentType.string())
                        .then(Commands.argument("x1", FloatArgumentType.floatArg())
                        .then(Commands.argument("y1", FloatArgumentType.floatArg())
                        .then(Commands.argument("z1", FloatArgumentType.floatArg())
                        .then(Commands.argument("x2", FloatArgumentType.floatArg())
                        .then(Commands.argument("y2", FloatArgumentType.floatArg())
                        .then(Commands.argument("z2", FloatArgumentType.floatArg())
                        .then(Commands.argument("x3", FloatArgumentType.floatArg())
                        .then(Commands.argument("y3", FloatArgumentType.floatArg())
                        .then(Commands.argument("z3", FloatArgumentType.floatArg())
                        .then(Commands.argument("x4", FloatArgumentType.floatArg())
                        .then(Commands.argument("y4", FloatArgumentType.floatArg())
                        .then(Commands.argument("z4", FloatArgumentType.floatArg())
                        .then(Commands.argument("source", StringArgumentType.string()).suggests(SUGGEST_REAL_SCREENS)
                                .executes(s -> {
                                    ClientVideoArea area = getArea(s);
                                    if (area == null) return 0;
                                    ClientPacketHandler.createScreen(new VideoScreen(
                                            area,
                                            s.getArgument("name", String.class),
                                            new Vector3f(
                                                    s.getArgument("x1", Float.class),
                                                    s.getArgument("y1", Float.class),
                                                    s.getArgument("z1", Float.class)
                                            ),
                                            new Vector3f(
                                                    s.getArgument("x2", Float.class),
                                                    s.getArgument("y2", Float.class),
                                                    s.getArgument("z2", Float.class)
                                            ),
                                            new Vector3f(
                                                    s.getArgument("x3", Float.class),
                                                    s.getArgument("y3", Float.class),
                                                    s.getArgument("z3", Float.class)
                                            ),
                                            new Vector3f(
                                                    s.getArgument("x4", Float.class),
                                                    s.getArgument("y4", Float.class),
                                                    s.getArgument("z4", Float.class)
                                            ),
                                            s.getArgument("source", String.class)
                                    ));
                                    return 1;
                                })))))))))))))))))
                .then(Commands.literal("preset")
                        .then(Commands.literal("list").executes(s -> listPresets(s)))
                        .then(Commands.literal("apply")
                                .then(Commands.argument("preset", StringArgumentType.string()).suggests(SUGGEST_PRESETS)
                                        .then(Commands.argument("area", StringArgumentType.string()).suggests(SUGGEST_AREAS)
                                                .then(Commands.argument("screen", StringArgumentType.string())
                                                        .executes(s -> createScreenHere(s, s.getArgument("preset", String.class)))))))
                        .then(Commands.literal("save")
                                .then(Commands.argument("name", StringArgumentType.string())
                                        .then(Commands.argument("width", FloatArgumentType.floatArg(0.1f))
                                                .then(Commands.argument("height", FloatArgumentType.floatArg(0.1f))
                                                        .executes(s -> savePreset(s,
                                                                s.getArgument("name", String.class),
                                                                s.getArgument("width", Float.class),
                                                                s.getArgument("height", Float.class)))))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("name", StringArgumentType.string()).suggests(SUGGEST_PRESETS)
                                        .executes(s -> removePreset(s, s.getArgument("name", String.class))))))
                .then(Commands.literal("removeScreen")
                        .then(Commands.argument("area", StringArgumentType.string()).suggests(SUGGEST_AREAS)
                                .then(Commands.argument("name", StringArgumentType.string()).suggests(SUGGEST_SCREENS)
                                        .executes(s -> {
                                            ClientVideoArea area = getArea(s);
                                            if (area == null) return 0;
                                            String screenName = s.getArgument("name", String.class);
                                            VideoScreen screen = area.getScreen(screenName);
                                            if (screen == null) {
                                                s.getSource().sendSuccess(() -> Component.literal("没有名为 " + screenName + " 的屏幕"), false);
                                                return 0;
                                            }
                                            ClientPacketHandler.removeScreen(screen);
                                            return 1;
                                        }))))
                .then(Commands.literal("skipPercent")
                        .then(Commands.argument("percent", FloatArgumentType.floatArg(0, 1.01f))
                                .executes(s -> {
                                    if (checkInvalid(s, true)) return 0;
                                    ClientPacketHandler.skipPercent(currentScreen, s.getArgument("percent", Float.class));
                                    return 1;
                                })))
                .then(Commands.literal("list")
                        .executes(s -> {
                            if (checkInvalid(s, true)) return 0;
                            String str = currentScreen.getScreen().infos.stream()
                                    .map(i -> String.format("%s 请求玩家: %s", i.name(), i.playerName()))
                                    .collect(Collectors.joining("\n"));
                            s.getSource().sendSuccess(() -> Component.literal("观影区 %s 屏幕 %s\n%s".formatted(
                                    currentScreen.area.name, currentScreen.name, str.isEmpty() ? "队列无视频" : str
                            )).withStyle(ChatFormatting.GOLD), false);
                            return 1;
                        }))
                .then(Commands.literal("sync")
                        .executes(s -> {
                            if (checkInvalid(s, true)) return 0;
                            ClientPacketHandler.sync(currentScreen);
                            return 1;
                        }))
                .then(Commands.literal("idleplay")
                        .then(Commands.argument("url", StringArgumentType.greedyString())
                                .executes(s -> {
                                    if (checkInvalid(s, true)) return 0;
                                    ClientPacketHandler.idlePlay(currentScreen, s.getArgument("url", String.class));
                                    return 1;
                                })))
                .then(Commands.literal("brightness")
                        .then(Commands.argument("brightness", IntegerArgumentType.integer(0, 100))
                                .executes(s -> {
                                    config.brightness = s.getArgument("brightness", Integer.class);
                                    s.getSource().sendSuccess(() -> Component.literal("亮度已设置为 " + config.brightness + "%").withStyle(ChatFormatting.GREEN), false);
                                    saveConfig();
                                    return 1;
                                })))
                .then(Commands.literal("slice")
                        .then(Commands.argument("u1", FloatArgumentType.floatArg())
                        .then(Commands.argument("v1", FloatArgumentType.floatArg())
                        .then(Commands.argument("u2", FloatArgumentType.floatArg())
                        .then(Commands.argument("v2", FloatArgumentType.floatArg())
                                .executes(s -> {
                                    if (checkInvalidLooking(s)) return 0;
                                    float u1 = s.getArgument("u1", Float.class);
                                    float v1 = s.getArgument("v1", Float.class);
                                    float u2 = s.getArgument("u2", Float.class);
                                    float v2 = s.getArgument("v2", Float.class);
                                    ClientPacketHandler.setUV(currentLooking, u1, v1, u2, v2);
                                    return 1;
                                }))))))
                .then(Commands.literal("stop")
                        .executes(s -> {
                            if (checkInvalid(s, true)) return 0;
                            currentScreen.player.stop();
                            return 1;
                        }))
                .then(Commands.literal("setmeta")
                        .then(Commands.argument("area", StringArgumentType.string()).suggests(SUGGEST_AREAS)
                                .then(Commands.argument("screen", StringArgumentType.string()).suggests(SUGGEST_SCREENS)
                                        .then(Commands.literal("mute")
                                                .then(Commands.argument("mute", BoolArgumentType.bool())
                                                        .executes(s -> {
                                                            ClientVideoScreen screen = getScreen(s);
                                                            if (screen == null) return 0;
                                                            ClientPacketHandler.setMeta(screen, PacketID.Action.MUTE.ordinal(), s.getArgument("mute", Boolean.class) ? 1 : 0);
                                                            return 1;
                                                        })))
                                        .then(Commands.literal("interactable")
                                                .then(Commands.argument("interactable", BoolArgumentType.bool())
                                                        .executes(s -> {
                                                            ClientVideoScreen screen = getScreen(s);
                                                            if (screen == null) return 0;
                                                            ClientPacketHandler.setMeta(screen, PacketID.Action.INTERACTABLE.ordinal(), s.getArgument("interactable", Boolean.class) ? 1 : 0);
                                                            return 1;
                                                        })))
                                        .then(Commands.literal("aspect")
                                                .then(Commands.argument("aspect", FloatArgumentType.floatArg(0.0625f, 16f))
                                                        .executes(s -> {
                                                            ClientVideoScreen screen = getScreen(s);
                                                            if (screen == null) return 0;
                                                            float aspect = s.getArgument("aspect", Float.class);
                                                            ClientPacketHandler.setMeta(screen, PacketID.Action.ASPECT.ordinal(), Float.floatToIntBits(aspect));
                                                            return 1;
                                                        })))
                                        .then(Commands.literal("fov")
                                                .then(Commands.argument("fov", IntegerArgumentType.integer(1, 179))
                                                        .executes(s -> {
                                                            ClientVideoScreen screen = getScreen(s);
                                                            if (screen == null) return 0;
                                                            ClientPacketHandler.setMeta(screen, PacketID.Action.FOV.ordinal(), s.getArgument("fov", Integer.class));
                                                            return 1;
                                                        })))
                                        .then(Commands.literal("autoSync")
                                                .then(Commands.argument("autoSync", BoolArgumentType.bool())
                                                        .executes(s -> {
                                                            ClientVideoScreen screen = getScreen(s);
                                                            if (screen == null) return 0;
                                                            ClientPacketHandler.setMeta(screen, PacketID.Action.AUTO_SYNC.ordinal(), s.getArgument("autoSync", Boolean.class) ? 1 : 0);
                                                            return 1;
                                                        })))
                                        .then(Commands.literal("custom")
                                                .then(Commands.literal("set")
                                                        .then(Commands.argument("key", StringArgumentType.string())
                                                                .then(Commands.argument("value", IntegerArgumentType.integer())
                                                                        .executes(s -> {
                                                                            ClientVideoScreen screen = getScreen(s);
                                                                            if (screen == null) return 0;
                                                                            ClientPacketHandler.setCustomMeta(screen, s.getArgument("key", String.class), s.getArgument("value", Integer.class), false);
                                                                            return 1;
                                                                        }))))
                                                .then(Commands.literal("get")
                                                        .then(Commands.argument("key", StringArgumentType.string())
                                                                .executes(s -> {
                                                                    ClientVideoScreen screen = getScreen(s);
                                                                    if (screen == null) return 0;
                                                                    String key = s.getArgument("key", String.class);
                                                                    s.getSource().sendSuccess(() -> Component.literal(key + "=" + screen.meta.getOrDefault(key, null)), false);
                                                                    return 1;
                                                                })))
                                                .then(Commands.literal("remove")
                                                        .then(Commands.argument("key", StringArgumentType.string())
                                                                .executes(s -> {
                                                                    ClientVideoScreen screen = getScreen(s);
                                                                    if (screen == null) return 0;
                                                                    ClientPacketHandler.setCustomMeta(screen, s.getArgument("key", String.class), -1, true);
                                                                    return 1;
                                                                })))
                                                .then(Commands.literal("list")
                                                        .executes(s -> {
                                                            ClientVideoScreen screen = getScreen(s);
                                                            if (screen == null) return 0;
                                                            s.getSource().sendSuccess(() -> Component.literal(screen.meta.toString()), false);
                                                            return 1;
                                                        })))
                                )))
                .then(Commands.literal("scale")
                        .then(Commands.literal("stretch")
                                .executes(s -> {
                                    if (checkInvalidLooking(s)) return 0;
                                    ClientPacketHandler.setScale(currentLooking, true, 1, 1);
                                    return 1;
                                }))
                        .then(Commands.literal("auto")
                                .executes(s -> {
                                    if (checkInvalidLooking(s)) return 0;
                                    ClientPacketHandler.setScale(currentLooking, false, 1, 1);
                                    return 1;
                                }))
                        .then(Commands.literal("set")
                                .then(Commands.argument("scaleX", FloatArgumentType.floatArg(0.0625f, 16f))
                                        .then(Commands.argument("scaleY", FloatArgumentType.floatArg(0.0625f, 16f))
                                                .executes(s -> {
                                                    if (checkInvalidLooking(s)) return 0;
                                                    ClientPacketHandler.setScale(currentLooking, false, s.getArgument("scaleX", Float.class), s.getArgument("scaleY", Float.class));
                                                    return 1;
                                                })))))
        );
    }

    private static ClientVideoArea getArea(CommandContext<CommandSourceStack> s) {
        if (checkInvalid(s, false)) return null;
        String name = s.getArgument("area", String.class);
        ClientVideoArea area = areas.get(name);
        if (area == null) {
            s.getSource().sendSuccess(() -> Component.literal("没有名为 " + name + " 的观影区").withStyle(ChatFormatting.RED), false);
            return null;
        }
        return area;
    }

    private static int createAreaHere(CommandContext<CommandSourceStack> context, float radius) {
        if (checkInvalid(context, false) || client.player == null) return 0;
        String name = context.getArgument("name", String.class);
        Vector3f pos = client.player.position().toVector3f();
        ClientPacketHandler.createArea(
                new Vector3f(pos.x - radius, pos.y - radius, pos.z - radius),
                new Vector3f(pos.x + radius, pos.y + radius, pos.z + radius), name);
        return 1;
    }

    private static int createScreenHere(CommandContext<CommandSourceStack> context, String presetName) {
        if (checkInvalid(context, false) || client.player == null) return 0;
        String areaName = context.getArgument("area", String.class);
        ClientVideoArea area = areas.get(areaName);
        if (area == null) {
            context.getSource().sendSuccess(() -> Component.literal("没有名为 " + areaName + " 的观影区").withStyle(ChatFormatting.RED), false);
            return 0;
        }
        String name = context.getNodes().stream().anyMatch(node -> node.getNode().getName().equals("screen"))
                ? context.getArgument("screen", String.class)
                : context.getArgument("name", String.class);
        ScreenPreset preset = presetName == null ? new ScreenPreset() : PresetManager.find(presetName);
        if (preset == null) {
            context.getSource().sendSuccess(() -> Component.literal("没有名为 " + presetName + " 的预设").withStyle(ChatFormatting.RED), false);
            return 0;
        }
        Vec3 center;
        Vec3 right;
        if (!preset.floating && client.hitResult instanceof BlockHitResult hit
                && hit.getType() == HitResult.Type.BLOCK
                && hit.getDirection().getAxis().isHorizontal()) {
            Direction direction = hit.getDirection();
            center = hit.getLocation().add(Vec3.atLowerCornerOf(direction.getNormal()).scale(0.01));
            right = Vec3.atLowerCornerOf(direction.getClockWise().getNormal());
        } else {
            Direction direction = client.player.getDirection();
            center = client.player.getEyePosition().add(Vec3.atLowerCornerOf(direction.getNormal()).scale(2));
            right = Vec3.atLowerCornerOf(direction.getClockWise().getNormal());
        }
        float width = preset.width;
        float height = preset.height;
        Vec3 up = new Vec3(0, 1, 0);
        Vector3f p1 = center.add(up.scale(height / 2)).add(right.scale(width / 2)).toVector3f();
        Vector3f p2 = center.add(up.scale(-height / 2)).add(right.scale(width / 2)).toVector3f();
        Vector3f p3 = center.add(up.scale(-height / 2)).add(right.scale(-width / 2)).toVector3f();
        Vector3f p4 = center.add(up.scale(height / 2)).add(right.scale(-width / 2)).toVector3f();
        VideoScreen screen = new VideoScreen(area, name, p1, p2, p3, p4, preset.source);
        screen.u1 = preset.u1;
        screen.v1 = preset.v1;
        screen.u2 = preset.u2;
        screen.v2 = preset.v2;
        screen.fill = preset.fill;
        screen.scaleX = preset.scaleX;
        screen.scaleY = preset.scaleY;
        screen.meta = new HashMap<>(preset.meta);
        ClientPacketHandler.createScreen(screen);
        return 1;
    }

    private static void suggestPreset(com.mojang.brigadier.suggestion.SuggestionsBuilder builder, String name) {
        if (name.startsWith(builder.getRemaining())) builder.suggest(name);
    }

    private static int listPresets(CommandContext<CommandSourceStack> context) {
        String names = String.join(", ", PresetManager.builtins().keySet())
                + (PresetManager.customs().isEmpty() ? "" : ", " + String.join(", ", PresetManager.customs().keySet()));
        context.getSource().sendSuccess(() -> Component.literal("可用屏幕预设: " + names).withStyle(ChatFormatting.GOLD), false);
        return 1;
    }

    private static int savePreset(CommandContext<CommandSourceStack> context, String name, float width, float height) {
        boolean saved = PresetManager.save(name, width, height);
        context.getSource().sendSuccess(() -> Component.literal(saved ? "已保存预设 " + name : "无法保存预设 " + name)
                .withStyle(saved ? ChatFormatting.GREEN : ChatFormatting.RED), false);
        return saved ? 1 : 0;
    }

    private static int removePreset(CommandContext<CommandSourceStack> context, String name) {
        boolean removed = PresetManager.remove(name);
        context.getSource().sendSuccess(() -> Component.literal(removed ? "已删除预设 " + name : "没有可删除的自定义预设 " + name)
                .withStyle(removed ? ChatFormatting.GREEN : ChatFormatting.RED), false);
        return removed ? 1 : 0;
    }

    private static ClientVideoScreen getScreen(CommandContext<CommandSourceStack> s) {
        if (checkInvalid(s, false)) return null;
        ClientVideoArea area = getArea(s);
        if (area == null) return null;
        String name = s.getArgument("screen", String.class);
        ClientVideoScreen screen = area.getScreen(name);
        if (screen == null) {
            s.getSource().sendSuccess(() -> Component.literal("屏幕未找到").withStyle(ChatFormatting.RED), false);
            return null;
        }
        return screen;
    }

    private static boolean checkInvalid(CommandContext<CommandSourceStack> s, boolean checkScreen) {
        if (!connected && !config.alwaysConnected) {
            s.getSource().sendSuccess(() -> Component.literal("未连接到服务器").withStyle(ChatFormatting.RED), false);
            return true;
        }
        if (checkScreen && currentScreen == null) {
            if (isInArea) {
                s.getSource().sendSuccess(() -> Component.literal("当前观影区没有主屏幕").withStyle(ChatFormatting.RED), false);
            } else {
                s.getSource().sendSuccess(() -> Component.literal("当前没有在观影区内").withStyle(ChatFormatting.RED), false);
            }
            return true;
        }
        return false;
    }

    private static boolean checkInvalidLooking(CommandContext<CommandSourceStack> s) {
        if (!connected && !config.alwaysConnected) {
            s.getSource().sendSuccess(() -> Component.literal("未连接到服务器").withStyle(ChatFormatting.RED), false);
            return true;
        }
        if (currentLooking == null) {
            s.getSource().sendSuccess(() -> Component.literal("当前没有看向屏幕").withStyle(ChatFormatting.RED), false);
            return true;
        }
        return false;
    }

    private static void updateBossBar() {
        if (currentLooking != null) {
            ClientPacketListener handler = client.getConnection();
            if (handler == null) return;
            if (!bossBarAdded) {
                handler.handleBossUpdate(ClientboundBossEventPacket.createAddPacket(bossBar));
                bossBarAdded = true;
            }
            ClientVideoScreen screen = currentLooking.getScreen();
            VideoInfo info = screen.infos.peek();
            if (info != null && screen.player != null) {
                String name = info.name();
                long progress = System.currentTimeMillis() - screen.getStartTime();
                long totalProgress = screen.player.getTotalProgress();
                String time;
                if (totalProgress > 0) {
                    boolean showHour = progress >= 3600000 || totalProgress >= 3600000;
                    time = formatDuration(progress, showHour) + "/" + formatDuration(totalProgress, showHour);
                    bossBar.setProgress((float) progress / totalProgress);
                } else {
                    time = formatDuration(progress, progress >= 3600000) + "/LIVE";
                    bossBar.setProgress(0);
                }
                bossBar.setName(Component.literal(name + " " + time));
            } else {
                bossBar.setName(Component.literal("无"));
                bossBar.setProgress(1);
            }
            handler.handleBossUpdate(ClientboundBossEventPacket.createUpdateNamePacket(bossBar));
            handler.handleBossUpdate(ClientboundBossEventPacket.createUpdateProgressPacket(bossBar));
        } else if (bossBarAdded) {
            ClientPacketListener handler = client.getConnection();
            if (handler != null) {
                handler.handleBossUpdate(ClientboundBossEventPacket.createRemovePacket(bossBar.getId()));
            }
            bossBarAdded = false;
        }
    }

    private static void checkInteract() {
        if (client == null || client.player == null) return;

        isInArea = false;
        currentLooking = null;
        currentScreen = null;
        if (screens.isEmpty()) {
            touchHandler.handle(null);
            return;
        }

        float delta = client.getFrameTime();
        Vec3 eyePos = client.player.getEyePosition(delta);
        Vec3 lookVec = client.player.getViewVector(delta);

        Vector3f lineStart = new Vector3f(eyePos.toVector3f());

        remoteControl = false;
        for (ItemStack item : client.player.getHandSlots()) {
            if (!BuiltInRegistries.ITEM.getKey(item.getItem()).toString().equals(remoteControlName)) continue;
            CompoundTag data = item.getTag();
            if (data == null) continue;
            int id = data.getInt("CustomModelData");
            if (id != remoteControlId) continue;
            remoteControl = true;
        }
        Vector3f lineEnd = eyePos.add(lookVec.scale(remoteControl ? remoteControlRange : noControlRange)).toVector3f();

        ArrayList<Intersection.Result> list = new ArrayList<>();
        for (ClientVideoScreen s : screens) {
            if (!s.interactable) continue;
            ClientVideoScreen screen = s.getTrackingScreen();
            if (screen == null)  continue;
            Intersection.Result result = Intersection.intersect(lineStart, lineEnd, screen);
            if (result.intersects) list.add(result);
        }
        Intersection.Result target = list.isEmpty() ? null : Collections.min(list, Comparator.comparing(s -> s.distance));
        currentLooking = target == null || target.screen == null ? null : target.screen;
        touchHandler.handle(target);

        if (currentLooking != null) {
            currentScreen = currentLooking;
            return;
        }

        currentScreen = null;
        for (ClientVideoArea area : areas.values()) {
            if (!area.loaded) continue;
            isInArea = true;
            for (VideoScreen screen : area.screens) {
                ClientVideoScreen s = (ClientVideoScreen) screen;
                if (s.interactable) {
                    currentScreen = s;
                    break;
                }
            }
        }
    }

    public static boolean checkVersion(String v) {
        String[] p1 = StringUtils.split(v, '.');
        String[] p2 = StringUtils.split(VideoPlayerMain.version, '.');
        if (p1.length < 2 || p2.length < 2) return false;
        return p1[0].equals(p2[0]) && p1[1].equals(p2[1]);
    }

    public static void update() {
        for (ClientVideoScreen screen : screens) {
            if (screen.isPostUpdate()) continue;
            screen.swapTexture();
            screen.update();
        }
        checkInteract();
        updateBossBar();
    }

    private static String formatDuration(long millis, boolean showHour) {
        long all = millis / 1000;
        long hours = all / 3600;
        long minutes = (all % 3600) / 60;
        long seconds = all % 60;

        if (showHour) {
            return String.format("%02d:%02d:%02d", hours, minutes, seconds);
        } else {
            return String.format("%02d:%02d", minutes, seconds);
        }
    }

    private static void saveConfig() {
        try {
            Files.writeString(configPath, gson.toJson(config));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void loadConfig() {
        try {
            config = gson.fromJson(Files.readString(configPath), Config.class);
        } catch (Exception e) {
            config = new Config();
            try {
                saveConfig();
            } catch (Exception e1) {
                e1.addSuppressed(e);
                throw new RuntimeException(e);
            }
        }
    }
}
