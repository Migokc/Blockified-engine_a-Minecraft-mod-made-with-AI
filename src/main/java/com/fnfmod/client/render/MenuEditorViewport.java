package com.fnfmod.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;

/**
 * BBS-film-editor-style viewport: the completed 3D world is copied before GUI
 * rendering, then composited into the editor's own aspect-correct rectangle.
 */
public final class MenuEditorViewport {
    private static RenderTarget capture;

    private MenuEditorViewport() {}

    /** Captures the current world framebuffer after menu objects and gizmos rendered. */
    public static void captureWorld() {
        Minecraft minecraft = Minecraft.getInstance();
        RenderTarget source = minecraft.getMainRenderTarget();
        if (source == null || source.width <= 0 || source.height <= 0) return;
        ensure(source.width, source.height);
        if (capture == null) return;

        GlStateManager._glBindFramebuffer(36008, source.frameBufferId);  // GL_READ_FRAMEBUFFER
        GlStateManager._glBindFramebuffer(36009, capture.frameBufferId); // GL_DRAW_FRAMEBUFFER
        GlStateManager._glBlitFrameBuffer(0, 0, source.width, source.height,
                0, 0, capture.width, capture.height, 16384, 9728);
        source.bindWrite(false);
        RenderSystem.viewport(0, 0, source.width, source.height);
    }

    /** Draws a centered crop so the world and 2D 1280x720 canvas share one viewport. */
    public static void draw(GuiGraphics gui, int x, int y, int width, int height) {
        if (capture == null || width <= 0 || height <= 0) return;
        gui.flush();
        double targetAspect = width / (double) height;
        // Psych presentation renders a virtual 16:9 projection across the raw
        // target; drawing the full texture here reverses that temporary stretch.
        double sourceAspect = PsychResolutionController.enabled()
                ? targetAspect : capture.width / (double) capture.height;
        float u0 = 0, u1 = 1, v0 = 0, v1 = 1;
        if (sourceAspect > targetAspect) {
            double visible = targetAspect / sourceAspect;
            u0 = (float) ((1.0 - visible) * 0.5);
            u1 = 1.0F - u0;
        } else if (sourceAspect < targetAspect) {
            double visible = sourceAspect / targetAspect;
            v0 = (float) ((1.0 - visible) * 0.5);
            v1 = 1.0F - v0;
        }

        float[] previousColor = RenderSystem.getShaderColor().clone();
        int previousTexture = RenderSystem.getShaderTexture(0);
        try {
            RenderSystem.disableDepthTest();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShaderColor(1, 1, 1, 1);
            RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
            RenderSystem.setShaderTexture(0, capture.getColorTextureId());
            Matrix4f matrix = gui.pose().last().pose();
            BufferBuilder builder = Tesselator.getInstance()
                    .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            // Render-target textures are bottom-up.
            builder.addVertex(matrix, x, y + height, 0).setUv(u0, v0).setColor(-1);
            builder.addVertex(matrix, x + width, y + height, 0).setUv(u1, v0).setColor(-1);
            builder.addVertex(matrix, x + width, y, 0).setUv(u1, v1).setColor(-1);
            builder.addVertex(matrix, x, y, 0).setUv(u0, v1).setColor(-1);
            BufferUploader.drawWithShader(builder.buildOrThrow());
        } finally {
            RenderSystem.setShaderTexture(0, previousTexture);
            RenderSystem.setShaderColor(previousColor[0], previousColor[1],
                    previousColor[2], previousColor[3]);
            RenderSystem.enableDepthTest();
        }
    }

    public static void release() {
        if (capture != null) {
            capture.destroyBuffers();
            capture = null;
        }
    }

    private static void ensure(int width, int height) {
        if (capture != null && capture.width == width && capture.height == height) return;
        release();
        capture = new TextureTarget(width, height, false, Minecraft.ON_OSX);
        capture.setClearColor(0, 0, 0, 1);
    }
}
