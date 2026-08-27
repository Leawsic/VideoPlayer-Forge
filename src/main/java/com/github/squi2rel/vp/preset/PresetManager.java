package com.github.squi2rel.vp.preset;

import com.github.squi2rel.vp.VideoPlayerMain;
import com.google.gson.Gson;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

public final class PresetManager {
    private static final Gson GSON = new Gson();
    private static final Path PRESETS_DIR = FMLPaths.CONFIGDIR.get().resolve("videoplayer-presets");
    private static final Map<String, ScreenPreset> CUSTOM = new LinkedHashMap<>();

    private PresetManager() {}

    public static void init() {
        try {
            Files.createDirectories(PRESETS_DIR);
        } catch (IOException e) {
            VideoPlayerMain.LOGGER.error("Failed to create preset directory", e);
            return;
        }
        load();
    }

    public static void load() {
        CUSTOM.clear();
        if (!Files.isDirectory(PRESETS_DIR)) return;
        try (Stream<Path> files = Files.list(PRESETS_DIR)) {
            files.filter(path -> path.getFileName().toString().endsWith(".json")).forEach(path -> {
                try {
                    ScreenPreset preset = GSON.fromJson(Files.readString(path), ScreenPreset.class);
                    if (preset != null && preset.name != null && !preset.name.isBlank()
                            && BuiltinPresets.get(preset.name) == null) {
                        CUSTOM.put(preset.name, preset);
                    }
                } catch (Exception e) {
                    VideoPlayerMain.LOGGER.error("Failed to load preset {}", path, e);
                }
            });
        } catch (IOException e) {
            VideoPlayerMain.LOGGER.error("Failed to list presets", e);
        }
    }

    public static ScreenPreset find(String name) {
        ScreenPreset builtin = BuiltinPresets.get(name);
        return builtin != null ? builtin : CUSTOM.get(name);
    }

    public static Map<String, ScreenPreset> builtins() {
        return BuiltinPresets.ALL;
    }

    public static Map<String, ScreenPreset> customs() {
        return CUSTOM;
    }

    public static boolean isBuiltin(String name) {
        return BuiltinPresets.get(name) != null;
    }

    public static boolean save(String name, float width, float height) {
        if (isBuiltin(name) || name == null || name.isBlank() || name.contains("/") || name.contains("\\") || name.contains("..")) return false;
        ScreenPreset preset = new ScreenPreset();
        preset.name = name;
        preset.width = width;
        preset.height = height;
        CUSTOM.put(name, preset);
        try {
            Files.createDirectories(PRESETS_DIR);
            Files.writeString(PRESETS_DIR.resolve(name + ".json"), GSON.toJson(preset));
            return true;
        } catch (IOException e) {
            VideoPlayerMain.LOGGER.error("Failed to save preset {}", name, e);
            return false;
        }
    }

    public static boolean remove(String name) {
        if (isBuiltin(name) || CUSTOM.remove(name) == null) return false;
        try {
            Files.deleteIfExists(PRESETS_DIR.resolve(name + ".json"));
            return true;
        } catch (IOException e) {
            VideoPlayerMain.LOGGER.error("Failed to remove preset {}", name, e);
            return false;
        }
    }
}
