package com.github.squi2rel.vp;

import com.github.squi2rel.vp.video.VideoArea;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public class ServerConfig {
    public ArrayList<VideoArea> areas = new ArrayList<>();
    public String remoteControlName = "minecraft:iron_ingot";
    public float remoteControlId = -1;
    public float remoteControlRange = 64;
    public float noControlRange = 16;
    public Set<String> blacklist = new HashSet<>();
}
