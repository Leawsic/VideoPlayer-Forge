package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.ScreenRenderer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import static com.github.squi2rel.vp.VideoPlayerClient.config;

public class Degree360Player extends VideoPlayer implements MetaListener {
    private static final Quaternionf tmp = new Quaternionf();
    protected float[] vertices;
    protected int vertexCount;
    protected VertexBuffer buffer;
    protected boolean dirty = true;
    protected int old;
    protected float x, y, z;
    protected int rot;
    protected boolean skybox = false;

    public Degree360Player(ClientVideoScreen screen, Vector3f p1, Vector3f p2, Vector3f p3, Vector3f p4) {
        super(screen, p1, p2, p3, p4);
    }

    @Override
    public synchronized void init() {
        super.init();
        buffer = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
    }

    @Override
    public void draw(PoseStack matrices, MultiBufferSource.BufferSource immediate, ClientVideoScreen s) {
        super.draw(matrices, immediate, s);
        if (vertices == null) return;
        matrices.pushPose();
        ScreenRenderer.rotateMatrix(matrices);
        if (skybox) {
            ScreenRenderer.skybox = true;
        } else {
            matrices.translate(x - ScreenRenderer.cameraX, y - ScreenRenderer.cameraY, z - ScreenRenderer.cameraZ);
        }
        if (rot != 0) matrices.mulPose(tmp.rotationY((float) Math.toRadians(rot)));
        int gray = (int) (config.brightness / 100.0 * 255);
        if (old != gray) {
            dirty = true;
            old = gray;
        }
        buffer.bind();
        if (dirty) {
            BufferBuilder bufferBuilder = Tesselator.getInstance().getBuilder();
            bufferBuilder.begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR_TEX);
            for (int i = 0; i < vertexCount; i++) {
                int idx = i * 5;
                bufferBuilder.vertex(vertices[idx], vertices[idx + 1], vertices[idx + 2]).color(gray, gray, gray, 255).uv(vertices[idx + 3], vertices[idx + 4]).endVertex();
            }
            buffer.upload(bufferBuilder.end());
            dirty = false;
        }
        ScreenRenderer.drawTriangles(getTextureId(), () -> buffer.drawWithShader(matrices.last().pose(), RenderSystem.getProjectionMatrix(), RenderSystem.getShader()));
        matrices.popPose();
    }

    @Override
    public void onMetaChanged() {
        super.onMetaChanged();
        x = p1.x + Float.intBitsToFloat(screen.meta.getOrDefault("x", 0));
        y = p1.y + Float.intBitsToFloat(screen.meta.getOrDefault("y", 0));
        z = p1.z + Float.intBitsToFloat(screen.meta.getOrDefault("z", 0));
        vertices = genVertices(
                screen.meta.getOrDefault("radius", 10),
                screen.meta.getOrDefault("lat", 32),
                screen.meta.getOrDefault("lon", 32),
                screen.u1, screen.u2, screen.v1, screen.v2
        );
        skybox = screen.meta.getOrDefault("skybox", 0) != 0;
        rot = screen.meta.getOrDefault("rot", 0);
        vertexCount = vertices.length / 5;
        dirty = true;
    }

    @Override
    public synchronized void cleanup() {
        super.cleanup();
        buffer.close();
    }

    protected static float[] genVertices(float radius, int latSegments, int lonSegments, float us, float ue, float vs, float ve) {
        int vertexCount = latSegments * (lonSegments + 1) * 2;
        float[] data = new float[vertexCount * 5];

        int idx = 0;
        for (int lat = 0; lat < latSegments; lat++) {
            double theta1 = Math.PI * lat / latSegments;
            double theta2 = Math.PI * (lat + 1) / latSegments;
            for (int lon = 0; lon <= lonSegments; lon++) {
                double phi = 2 * Math.PI * lon / lonSegments;
                float y1 = (float) (radius * Math.cos(theta1));
                float y2 = (float) (radius * Math.cos(theta2));
                float r1 = (float) (radius * Math.sin(theta1));
                float r2 = (float) (radius * Math.sin(theta2));
                float x1 = (float) (r1 * Math.cos(phi));
                float x2 = (float) (r2 * Math.cos(phi));
                float z1 = (float) (r1 * Math.sin(phi));
                float z2 = (float) (r2 * Math.sin(phi));
                float u = Mth.lerp((float) lon / lonSegments, us, ue);
                float v1 = Mth.lerp((float) lat / latSegments, vs, ve);
                float v2 = Mth.lerp((float) (lat + 1) / latSegments, vs, ve);
                data[idx++] = x1;
                data[idx++] = y1;
                data[idx++] = z1;
                data[idx++] = u;
                data[idx++] = v1;
                data[idx++] = x2;
                data[idx++] = y2;
                data[idx++] = z2;
                data[idx++] = u;
                data[idx++] = v2;
            }
        }

        return data;
    }
}
