package com.github.squi2rel.vp.preset;

import java.util.HashMap;
import java.util.Map;

public class ScreenPreset {
    public String name;
    public float width = 4f;
    public float height = 2.25f;
    public boolean floating;
    public float u1, v1, u2 = 1, v2 = 1;
    public boolean fill;
    public float scaleX = 1, scaleY = 1;
    public String source = "";
    public Map<String, Integer> meta = new HashMap<>();
}