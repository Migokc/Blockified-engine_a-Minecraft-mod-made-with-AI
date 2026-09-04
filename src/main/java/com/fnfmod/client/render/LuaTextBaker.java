package com.fnfmod.client.render;

import com.fnfmod.FnfMod;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.GlStateBackup;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bakes a Lua text block into its own texture, so a translucent text draws as one flat image.
 *
 * <p>An outline is the same glyphs stamped at several offsets. Drawn straight into the scene
 * those copies overlap, and translucent copies pile up where they do, so any alpha but 0 came
 * out looking solid. Painting the block opaque into a private target and then drawing that
 * target once at the requested alpha sidesteps the problem entirely: the overlap happens while
 * everything is opaque, and the scene only ever sees a single textured quad.</p>
 */
public final class LuaTextBaker {

    /** Everything that changes the painted pixels; a change here means a re-bake. */
    public record Key(String signature, int width, int height) {}

    /** Stable copy of an off-screen bake; never remains attached to a framebuffer. */
    public record Baked(ResourceLocation id, DynamicTexture texture) {}

    /**
     * Slack around the painted block. The italic shear and rounding can carry a glyph a couple
     * of pixels past the measured box, and anything outside the texture is simply not there,
     * so the block would show a clipped edge.
     */
    public static final int PADDING = 16;

    private static final Map<Key, Baked> CACHE = new LinkedHashMap<>();
    private static final AtomicInteger NEXT_ID = new AtomicInteger();
    /** Text blocks are small, but each owns a GPU texture, so the cache stays short. */
    private static final int MAX_CACHED = 48;
    private static final int MAX_SIDE = 2048;

    private LuaTextBaker() {}

    /**
     * Returns the texture holding this text, painting it first if the cache has no copy. The
     * painter draws at full opacity, with the block's top-left at 0,0. Returns null when the
     * size is unusable or the paint failed, and the caller should draw the text directly.
     */
    public static Baked bake(Key key, Consumer<GuiGraphics> painter) {
        if (!valid(key)) return null;
        Baked cached = CACHE.get(key);
        if (cached != null) return cached;
        Baked baked = paint(key, painter);
        if (baked == null) return null;
        CACHE.put(key, baked);
        trim();
        return baked;
    }

    /**
     * Bakes an underlay and a foreground independently, then combines their alpha masks. Pixels
     * covered by the foreground replace the underlay instead of blending with it. This matters
     * for outlined text: antialiased glyph edges are translucent, so ordinary source-over
     * painting lets the dark outline show through and changes the apparent text colour.
     */
    public static Baked bakeLayers(Key key, Consumer<GuiGraphics> underPainter, int underArgb,
                                   Consumer<GuiGraphics> foregroundPainter, int foregroundArgb) {
        if (!valid(key)) return null;
        Baked cached = CACHE.get(key);
        if (cached != null) return cached;

        NativeImage under = paintImage(key, underPainter);
        if (under == null) return null;
        NativeImage foreground = paintImage(key, foregroundPainter);
        if (foreground == null) {
            under.close();
            return null;
        }

        try {
            int underRgb = argbToAbgrRgb(underArgb);
            int foregroundRgb = argbToAbgrRgb(foregroundArgb);
            for (int y = 0; y < key.height(); y++) {
                for (int x = 0; x < key.width(); x++) {
                    int foregroundAlpha = foreground.getPixelRGBA(x, y) >>> 24;
                    if (foregroundAlpha > 0) {
                        // Replace, do not blend: the outline must never tint the glyph itself.
                        under.setPixelRGBA(x, y, foregroundAlpha << 24 | foregroundRgb);
                    } else {
                        int underAlpha = under.getPixelRGBA(x, y) >>> 24;
                        if (underAlpha > 0) {
                            // Keep antialiasing, but undo framebuffer-premultiplied RGB.
                            under.setPixelRGBA(x, y, underAlpha << 24 | underRgb);
                        }
                    }
                }
            }
        } finally {
            foreground.close();
        }

        Baked baked = register(under);
        if (baked == null) return null;
        CACHE.put(key, baked);
        trim();
        return baked;
    }

    private static boolean valid(Key key) {
        return key.width() > 0 && key.height() > 0
                && key.width() <= MAX_SIDE && key.height() <= MAX_SIDE;
    }

    /** Converts GuiGraphics' ARGB colour to NativeImage's packed ABGR RGB channels. */
    private static int argbToAbgrRgb(int argb) {
        int red = argb >> 16 & 0xFF;
        int green = argb >> 8 & 0xFF;
        int blue = argb & 0xFF;
        return blue << 16 | green << 8 | red;
    }

    private static void trim() {
        while (CACHE.size() > MAX_CACHED) {
            var oldest = CACHE.entrySet().iterator();
            if (!oldest.hasNext()) return;
            var entry = oldest.next();
            oldest.remove();
            release(entry.getValue());
        }
    }

    private static Baked paint(Key key, Consumer<GuiGraphics> painter) {
        NativeImage image = paintImage(key, painter);
        return image == null ? null : register(image);
    }

    private static Baked register(NativeImage image) {
        Minecraft minecraft = Minecraft.getInstance();
        DynamicTexture texture = null;
        ResourceLocation id = null;
        try {
            texture = new DynamicTexture(image);
            id = FnfMod.id("lua_text_bake/" + NEXT_ID.incrementAndGet());
            minecraft.getTextureManager().register(id, texture);
            return new Baked(id, texture);
        } catch (Throwable error) {
            FnfMod.LOGGER.warn("Could not register baked Lua text: {}", error.toString());
            if (id != null) {
                try { minecraft.getTextureManager().release(id); }
                catch (Throwable ignored) {}
            }
            if (texture != null) {
                try { texture.close(); } catch (Throwable ignored) {}
            } else {
                try { image.close(); } catch (Throwable ignored) {}
            }
            return null;
        }
    }

    private static NativeImage paintImage(Key key, Consumer<GuiGraphics> painter) {
        Minecraft minecraft = Minecraft.getInstance();
        RenderTarget target = null;
        NativeImage copiedImage = null;
        // Everything below leaves global render state behind if it escapes, so the whole
        // paint is guarded and the previous target/matrices are always put back.
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        boolean matricesPushed = false;
        // The world pass does not always draw into the main render target, so remember the one
        // that is actually bound (and its viewport) and put exactly that back afterwards.
        // Rebinding the main target instead sent the rest of the pass somewhere else, which is
        // why the block came out wearing the sky.
        // READ and DRAW are normally the same, but Fabulous/Iris may split them while the level
        // is being composited. Restoring one GL_FRAMEBUFFER binding for both corrupts the rest of
        // that composite (most visibly the sky around the sun and moon).
        int previousDrawBuffer = org.lwjgl.opengl.GL11.glGetInteger(
                org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousReadBuffer = org.lwjgl.opengl.GL11.glGetInteger(
                org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER_BINDING);
        int[] previousViewport = new int[4];
        org.lwjgl.opengl.GL11.glGetIntegerv(org.lwjgl.opengl.GL11.GL_VIEWPORT, previousViewport);
        GlStateBackup previousGlState = new GlStateBackup();
        RenderSystem.backupGlState(previousGlState);
        float[] previousShaderColor = RenderSystem.getShaderColor().clone();
        float previousFogStart = RenderSystem.getShaderFogStart();
        int previousTexture = RenderSystem.getShaderTexture(0);
        try {
            // TextureTarget.clear and GuiGraphics obey inherited scissor and colour-mask state.
            // World rendering may leave either restricted, clipping the upper glyph pixels or
            // preventing one of the texture channels from being written.
            RenderSystem.disableScissor();
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            target = new TextureTarget(key.width(), key.height(), false, Minecraft.ON_OSX);
            target.setClearColor(0f, 0f, 0f, 0f);
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);

            RenderSystem.backupProjectionMatrix();
            RenderSystem.setProjectionMatrix(
                    new Matrix4f().setOrtho(0f, key.width(), key.height(), 0f, 1000f, 21000f),
                    VertexSorting.ORTHOGRAPHIC_Z);
            modelView.pushMatrix();
            modelView.identity().translate(0f, 0f, -11000f);
            matricesPushed = true;
            RenderSystem.applyModelViewMatrix();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableDepthTest();
            // The block is painted opaque; a tint left over from an earlier draw would bake
            // itself into the texture and then be multiplied a second time on the way out.
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            // Vanilla/BBS text uses rendertype_text. Its fog distance comes from the raw glyph
            // vertex coordinates, so off-screen pixel positions were treated as world distance:
            // larger text/outline regions crossed the current render-distance fog threshold and
            // had the sky colour permanently baked into them. Disable fog only for this flush.
            RenderSystem.setShaderFogStart(Float.MAX_VALUE);

            MultiBufferSource.BufferSource buffers =
                    MultiBufferSource.immediate(new ByteBufferBuilder(786432));
            GuiGraphics graphics = new GuiGraphics(minecraft, buffers);
            painter.accept(graphics);
            graphics.flush();

            // Never keep the framebuffer attachment as the displayed texture. Later world-pass
            // framebuffer/texture changes could otherwise replace the cached glyph pixels.
            // Read the completed bake once into an independent texture instead. Keep its native
            // bottom-up row order because draw() already flips V for render-target output.
            copiedImage = new NativeImage(key.width(), key.height(), false);
            RenderSystem.bindTexture(target.getColorTextureId());
            copiedImage.downloadTexture(0, false);
            NativeImage result = copiedImage;
            copiedImage = null;
            return result;
        } catch (Throwable error) {
            FnfMod.LOGGER.warn("Could not bake Lua text: {}", error.toString());
            if (copiedImage != null) {
                try { copiedImage.close(); } catch (Throwable ignored) {}
            }
            return null;
        } finally {
            if (matricesPushed) {
                modelView.popMatrix();
                RenderSystem.applyModelViewMatrix();
                RenderSystem.restoreProjectionMatrix();
            }
            if (target != null) target.destroyBuffers();
            com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(
                    org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER, previousDrawBuffer);
            com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(
                    org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, previousReadBuffer);
            com.mojang.blaze3d.platform.GlStateManager._viewport(previousViewport[0],
                    previousViewport[1], previousViewport[2], previousViewport[3]);
            RenderSystem.setShaderTexture(0, previousTexture);
            RenderSystem.setShaderFogStart(previousFogStart);
            RenderSystem.setShaderColor(previousShaderColor[0], previousShaderColor[1],
                    previousShaderColor[2], previousShaderColor[3]);
            RenderSystem.restoreGlState(previousGlState);
        }
    }

    /**
     * Draws a baked block as one quad. {@code matrix} places it, so the same call serves the
     * 2D canvas and world space. Bake pixels retain render-target bottom-up order, hence flipped V.
     */
    public static void draw(Matrix4f matrix, Baked target, float x, float y,
                            float width, float height, float alpha) {
        if (target == null || alpha <= 0) return;
        GlStateBackup previousGlState = new GlStateBackup();
        RenderSystem.backupGlState(previousGlState);
        float[] previousShaderColor = RenderSystem.getShaderColor().clone();
        int previousTexture = RenderSystem.getShaderTexture(0);
        try {
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableCull();
            RenderSystem.colorMask(true, true, true, true);
            // PositionTexColor multiplies every vertex by this global value. The level pass uses
            // it for sky/world tinting, so cached text must explicitly draw neutral white.
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
            RenderSystem.setShaderTexture(0, target.id());
            BufferBuilder builder = Tesselator.getInstance()
                    .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            builder.addVertex(matrix, x, y + height, 0f).setUv(0f, 0f).setColor(1f, 1f, 1f, alpha);
            builder.addVertex(matrix, x + width, y + height, 0f).setUv(1f, 0f).setColor(1f, 1f, 1f, alpha);
            builder.addVertex(matrix, x + width, y, 0f).setUv(1f, 1f).setColor(1f, 1f, 1f, alpha);
            builder.addVertex(matrix, x, y, 0f).setUv(0f, 1f).setColor(1f, 1f, 1f, alpha);
            BufferUploader.drawWithShader(builder.buildOrThrow());
        } finally {
            RenderSystem.setShaderTexture(0, previousTexture);
            RenderSystem.setShaderColor(previousShaderColor[0], previousShaderColor[1],
                    previousShaderColor[2], previousShaderColor[3]);
            RenderSystem.restoreGlState(previousGlState);
        }
    }

    /**
     * Draws baked text through Minecraft's entity pipeline. Unlike the immediate shader path used
     * by HUD canvases, this selects the level pass' correct translucent output target under both
     * Fabulous and normal graphics, preventing sky/composite data from replacing the text colour.
     */
    public static void drawWorld(PoseStack.Pose pose, MultiBufferSource.BufferSource buffers,
                                 Baked target, float x, float y, float width, float height,
                                 float alpha, boolean seeThrough, boolean polygonOffset, int light) {
        if (target == null || alpha <= 0) return;
        // Match Minecraft/BBS label rendering: the text shader uses the lightmap but has no
        // entity normal/directional-light calculation. An entity translucent shader makes an
        // otherwise white label turn grey according to the plane's facing direction.
        RenderType renderType = FlatWorldRenderTypes.get(target.id(), seeThrough, polygonOffset);
        VertexConsumer vertices = buffers.getBuffer(renderType);
        int colorAlpha = Math.max(0, Math.min(255, Math.round(alpha * 255f)));

        // Bake pixels retain render-target bottom-up order, hence flipped V.
        worldVertex(vertices, pose, x, y + height, 0f, 0f, colorAlpha, light);
        worldVertex(vertices, pose, x + width, y + height, 1f, 0f, colorAlpha, light);
        worldVertex(vertices, pose, x + width, y, 1f, 1f, colorAlpha, light);
        worldVertex(vertices, pose, x, y, 0f, 1f, colorAlpha, light);
        buffers.endBatch(renderType);
    }

    private static void worldVertex(VertexConsumer vertices, PoseStack.Pose pose, float x, float y,
                                    float u, float v, int alpha, int light) {
        vertices.addVertex(pose, x, y, 0f)
                .setColor(255, 255, 255, alpha)
                .setUv(u, v)
                .setLight(light);
    }

    /** Drops every baked block, e.g. when a song ends and its fonts go away. */
    public static void clear() {
        for (Baked target : CACHE.values()) release(target);
        CACHE.clear();
    }

    private static void release(Baked baked) {
        if (baked == null) return;
        try { Minecraft.getInstance().getTextureManager().release(baked.id()); }
        catch (Throwable ignored) {}
        try { baked.texture().close(); } catch (Throwable ignored) {}
    }
}
