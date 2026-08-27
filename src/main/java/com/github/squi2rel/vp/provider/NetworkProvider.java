package com.github.squi2rel.vp.provider;

import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

public class NetworkProvider implements IVideoProvider {
    @Override
    public @Nullable CompletableFuture<VideoInfo> from(String str, IProviderSource source) {
        char first = str.charAt(0);
        if (first == '/' || first == '\\' || first == '.' || str.charAt(1) == ':') return null;
        // The server no longer runs VLC, so it cannot probe the stream. Hand the raw
        // MRL to clients directly; each client's VLC determines playability itself.
        // seekable is unknown here, so it is left false (no server-side seek/sync).
        return CompletableFuture.completedFuture(
                new VideoInfo(source.name(), getName(str), str, "", -1, false, -1, NO_PARAMS));
    }

    private static String getName(String mrl) {
        String path = mrl.toLowerCase();
        String name = "Unknown Stream";
        if (path.startsWith("http") && path.contains(".m3u8")) {
            name = "HLS Stream";
        } else if (path.startsWith("rtsp://") || path.startsWith("rtspt://")) {
            name = "RTSP Stream";
        } else if (path.startsWith("http")) {
            name = "HTTP Stream";
        } else if (path.startsWith("rtp://")) {
            name = "RTP Stream";
        } else if (path.startsWith("mms://")) {
            name = "MMS Stream";
        }
        return name;
    }
}
