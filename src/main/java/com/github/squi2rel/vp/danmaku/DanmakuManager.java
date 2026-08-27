package com.github.squi2rel.vp.danmaku;

import com.github.squi2rel.vp.VideoPlayerClient;
import com.github.squi2rel.vp.video.ClientVideoScreen;
import com.github.squi2rel.vp.video.IVideoPlayer;
import com.github.squi2rel.vp.provider.VideoInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DanmakuManager {
    private static final String VIEW_URL = "https://api.bilibili.com/x/web-interface/view?bvid=%s";
    private static final String SEGMENT_URL = "https://api.bilibili.com/x/v2/dm/web/seg.so?type=1&oid=%s&segment_index=%s";
    private static final Pattern BVID = Pattern.compile("(BV[0-9A-Za-z]{10})(?:[^#]*?[?&]p=(\\d+))?");
    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final Map<ClientVideoScreen, Track> TRACKS = new HashMap<>();
    private static final int SEGMENT_MILLIS = 6 * 60 * 1000;
    private static final int MAX_VISIBLE = 24;

    private DanmakuManager() {}

    public static void play(ClientVideoScreen screen, VideoInfo info) {
        if (!VideoPlayerClient.config.danmaku) return;
        Matcher matcher = BVID.matcher(info.rawPath());
        if (!matcher.find()) {
            TRACKS.remove(screen);
            return;
        }
        String key = matcher.group();
        Track previous = TRACKS.get(screen);
        if (previous != null && previous.key.equals(key)) return;
        Track track = new Track(key, matcher.group(1), matcher.group(2) == null ? 1 : Integer.parseInt(matcher.group(2)));
        TRACKS.put(screen, track);
        CompletableFuture.runAsync(() -> resolveCid(track));
    }

    public static void stop(ClientVideoScreen screen) {
        TRACKS.remove(screen);
    }

    public static void draw(ClientVideoScreen screen, MultiBufferSource.BufferSource buffers) {
        if (!VideoPlayerClient.config.danmaku) return;
        Track track = TRACKS.get(screen);
        if (track == null || track.cid == 0 || screen.player == null || screen.player.isPaused()) return;

        long progress = screen.getPlaybackProgress();
        int currentSegment = (int) (progress / SEGMENT_MILLIS) + 1;
        loadSegment(track, currentSegment);
        loadSegment(track, currentSegment + 1);

        List<Danmaku> visible = new ArrayList<>();
        List<Danmaku> items;
        synchronized (track) {
            items = new ArrayList<>(track.items);
        }
        for (Danmaku danmaku : items) {
            long age = progress - danmaku.time;
            if (age >= 0 && age <= 9000) visible.add(danmaku);
            if (visible.size() >= MAX_VISIBLE) break;
        }
        if (visible.isEmpty()) return;

        IVideoPlayer player = screen.player;
        int videoWidth = player.getWidth();
        int videoHeight = player.getHeight();
        if (videoWidth <= 0 || videoHeight <= 0) return;

        Vector3f origin = new Vector3f(screen.p1).sub(new Vector3f(
                com.github.squi2rel.vp.ScreenRenderer.cameraX,
                com.github.squi2rel.vp.ScreenRenderer.cameraY,
                com.github.squi2rel.vp.ScreenRenderer.cameraZ));
        Vector3f right = new Vector3f(screen.p4).sub(screen.p1).div(videoWidth);
        Vector3f down = new Vector3f(screen.p2).sub(screen.p1).div(videoHeight);
        Matrix4f matrix = new Matrix4f()
                .m00(right.x).m01(right.y).m02(right.z)
                .m10(down.x).m11(down.y).m12(down.z)
                .m20(0).m21(0).m22(1)
                .m30(origin.x).m31(origin.y).m32(origin.z);
        Font font = Minecraft.getInstance().font;
        for (int index = 0; index < visible.size(); index++) {
            Danmaku danmaku = visible.get(index);
            long age = progress - danmaku.time;
            float x = videoWidth - (videoWidth + font.width(danmaku.text)) * age / 9000f;
            float y = 8 + (index % 12) * 18;
            font.drawInBatch(danmaku.text, x, y, 0xFF000000 | danmaku.color, true, matrix, buffers,
                    Font.DisplayMode.POLYGON_OFFSET, 0, 0xF000F0);
        }
    }

    private static void resolveCid(Track track) {
        try {
            String body = request(String.format(VIEW_URL, track.bvid), HttpResponse.BodyHandlers.ofString()).body();
            var root = com.google.gson.JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("data");
            if (track.page == 1) {
                track.cid = root.get("cid").getAsLong();
            } else {
                track.cid = root.getAsJsonArray("pages").get(track.page - 1).getAsJsonObject().get("cid").getAsLong();
            }
        } catch (Exception ignored) {
        }
    }

    private static void loadSegment(Track track, int index) {
        synchronized (track) {
            if (track.cid == 0 || !track.loaded.add(index)) return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                byte[] bytes = request(String.format(SEGMENT_URL, track.cid, index), HttpResponse.BodyHandlers.ofByteArray()).body();
                List<Danmaku> parsed = parseReply(bytes);
                synchronized (track) {
                    track.items.addAll(parsed);
                    track.items.sort(Comparator.comparingLong(item -> item.time));
                }
            } catch (Exception ignored) {
                synchronized (track) {
                    track.loaded.remove(index);
                }
            }
        });
    }

    private static <T> HttpResponse<T> request(String url, HttpResponse.BodyHandler<T> handler) throws IOException, InterruptedException {
        return CLIENT.send(HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", "https://www.bilibili.com")
                .GET().build(), handler);
    }

    private static List<Danmaku> parseReply(byte[] bytes) throws IOException {
        List<Danmaku> result = new ArrayList<>();
        ByteArrayInputStream input = new ByteArrayInputStream(bytes);
        while (input.available() > 0) {
            long tag = readVarint(input);
            int field = (int) (tag >>> 3);
            int wire = (int) (tag & 7);
            if (field == 1 && wire == 2) result.add(parseElement(readBytes(input)));
            else skip(input, wire);
        }
        result.removeIf(item -> item == null || item.mode != 1 || item.text.isBlank() || item.text.length() > 80);
        return result;
    }

    private static Danmaku parseElement(byte[] bytes) throws IOException {
        ByteArrayInputStream input = new ByteArrayInputStream(bytes);
        long time = -1;
        int mode = 0;
        int color = 0xFFFFFF;
        String text = "";
        while (input.available() > 0) {
            long tag = readVarint(input);
            int field = (int) (tag >>> 3);
            int wire = (int) (tag & 7);
            if (wire == 0) {
                long value = readVarint(input);
                if (field == 2) time = value;
                else if (field == 3) mode = (int) value;
                else if (field == 5) color = (int) value;
            } else if (wire == 2) {
                byte[] value = readBytes(input);
                if (field == 7) text = new String(value, java.nio.charset.StandardCharsets.UTF_8);
            } else {
                skip(input, wire);
            }
        }
        return time < 0 ? null : new Danmaku(time, mode, color, text);
    }

    private static long readVarint(ByteArrayInputStream input) throws IOException {
        long value = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            int next = input.read();
            if (next < 0) throw new IOException("Unexpected protobuf EOF");
            value |= (long) (next & 127) << shift;
            if ((next & 128) == 0) return value;
        }
        throw new IOException("Invalid protobuf varint");
    }

    private static byte[] readBytes(ByteArrayInputStream input) throws IOException {
        long length = readVarint(input);
        if (length < 0 || length > input.available()) throw new IOException("Invalid protobuf length");
        return input.readNBytes((int) length);
    }

    private static void skip(ByteArrayInputStream input, int wire) throws IOException {
        switch (wire) {
            case 0 -> readVarint(input);
            case 1 -> input.skipNBytes(8);
            case 2 -> input.skipNBytes(readVarint(input));
            case 5 -> input.skipNBytes(4);
            default -> throw new IOException("Unsupported protobuf wire type");
        }
    }

    private record Danmaku(long time, int mode, int color, String text) {}

    private static final class Track {
        private final String key;
        private final String bvid;
        private final int page;
        private final Set<Integer> loaded = new HashSet<>();
        private final List<Danmaku> items = new ArrayList<>();
        private volatile long cid;

        private Track(String key, String bvid, int page) {
            this.key = key;
            this.bvid = bvid;
            this.page = page;
        }
    }
}
