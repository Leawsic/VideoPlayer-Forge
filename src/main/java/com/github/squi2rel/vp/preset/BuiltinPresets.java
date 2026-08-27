package com.github.squi2rel.vp.preset;

import java.util.LinkedHashMap;
import java.util.Map;

public final class BuiltinPresets {
    private BuiltinPresets() {}

    public static final Map<String, ScreenPreset> ALL = new LinkedHashMap<>();

    static {
        ScreenPreset small = new ScreenPreset();
        small.name = "small";
        small.width = 2f;
        small.height = 1.125f;
        ALL.put("small", small);

        ScreenPreset cinemaLarge = new ScreenPreset();
        cinemaLarge.name = "cinema-large";
        cinemaLarge.width = 8f;
        cinemaLarge.height = 4.5f;
        ALL.put("cinema-large", cinemaLarge);

        ScreenPreset wall = new ScreenPreset();
        wall.name = "wall";
        wall.width = 4f;
        wall.height = 2.25f;
        ALL.put("wall", wall);

        ScreenPreset floating = new ScreenPreset();
        floating.name = "floating";
        floating.width = 4f;
        floating.height = 2.25f;
        floating.floating = true;
        ALL.put("floating", floating);
    }

    public static ScreenPreset get(String name) {
        return ALL.get(name);
    }
}