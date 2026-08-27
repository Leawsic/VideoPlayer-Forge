package com.github.squi2rel.vp;

import com.github.squi2rel.vp.video.ClientVideoScreen;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.lwjgl.opengl.GL11;

import java.util.HashMap;

import static com.github.squi2rel.vp.VideoPlayerClient.screens;

public class ScreenRenderer {
    private static final HashMap<Integer, RenderType> quadsCache = new HashMap<>();

    private static int triangleId;
    private static final RenderType VIDEO_TRIANGLES = RenderType.create(
            "video_triangles",
            DefaultVertexFormat.POSITION_COLOR_TEX,
            VertexFormat.Mode.TRIANGLE_STRIP,
            4096,
            true,
            true,
            RenderType.CompositeState.builder()
                    .setShaderState(new RenderStateShard.ShaderStateShard(GameRenderer::getPositionColorTexShader))
                    .setTextureState(new RenderStateShard.EmptyTextureStateShard(() -> RenderSystem.setShaderTexture(0, triangleId), () -> {}))
                    .setCullState(new RenderStateShard.CullStateShard(false))
                    .createCompositeState(true)
    );

    private static final Quaternionf rotation = new Quaternionf();
    public static float cameraX, cameraY, cameraZ;
    public static boolean skybox;

    public static void render(PoseStack matrices, Camera camera) {
        skybox = false;
        matrices.pushPose();
        Vec3 pos = camera.getPosition();
        cameraX = (float) pos.x;
        cameraY = (float) pos.y;
        cameraZ = (float) pos.z;
        rotation.set(0, 0, 0, 1);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LESS);
        RenderSystem.disableCull();
        MultiBufferSource.BufferSource immediate = Minecraft.getInstance().renderBuffers().bufferSource();
        quadsCache.clear();
        int old = RenderSystem.getShaderTexture(0);
        for (ClientVideoScreen screen : screens) {
            try {
                screen.draw(matrices, immediate);
            } catch (Exception e) {
                VideoPlayerMain.LOGGER.error("Exception while rendering", e);
            }
        }
        immediate.endBatch();
        RenderSystem.setShaderTexture(0, old);
        RenderSystem.enableCull();
        RenderSystem.disableDepthTest();
        matrices.popPose();
    }

    public static RenderType getLayer(int textureId) {
        return quadsCache.computeIfAbsent(textureId, v -> RenderType.create(
                "video_quad_" + textureId,
                DefaultVertexFormat.POSITION_TEX_COLOR,
                VertexFormat.Mode.QUADS,
                32,
                true,
                true,
                RenderType.CompositeState.builder()
                        .setShaderState(new RenderStateShard.ShaderStateShard(GameRenderer::getPositionTexColorShader))
                        .setTextureState(new RenderStateShard.EmptyTextureStateShard(() -> RenderSystem.setShaderTexture(0, textureId), () -> {}))
                        .setCullState(new RenderStateShard.CullStateShard(false))
                        .createCompositeState(true)
        ));
    }

    public static void drawTriangles(int textureId, Runnable r) {
        triangleId = textureId;
        VIDEO_TRIANGLES.setupRenderState();
        r.run();
        VIDEO_TRIANGLES.clearRenderState();
    }

    public static void rotateMatrix(PoseStack matrices) {
        matrices.mulPose(rotation);
    }
}
