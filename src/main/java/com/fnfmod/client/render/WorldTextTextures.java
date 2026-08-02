package com.fnfmod.client.render;

import com.fnfmod.FnfMod;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix4f;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bakes Lua world text into a texture so it can be drawn as a world sprite.
 *
 * <p>Font glyphs do not paint through their own render types during a level-render
 * stage, but {@code font.drawInBatch} works fine into an off-screen render target
 * (an ordinary GUI-style 2D render). So the text is rendered once into a texture
 * here — with the font's own glyphs, so custom fonts survive — and the caller then
 * draws that texture through the depth-tested (and see-through-capable) sprite path.
 */
public final class WorldTextTextures {

    public record Baked(ResourceLocation id, DynamicTexture texture, int width, int height) {}

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private WorldTextTextures() {}

    /** Renders text into a fresh texture, or null if it could not be baked. */
    public static Baked bake(Font font, String text, int textSize, int rgb, int wrapPx) {
        float scale = Math.max(1f, textSize / (float) font.lineHeight);
        String source = text.isEmpty() ? " " : text;
        List<FormattedCharSequence> lines = font.split(Component.literal(source),
                wrapPx <= 0 ? Integer.MAX_VALUE : wrapPx);
        if (lines.isEmpty()) return null;
        int lineH = font.lineHeight + 1;
        int textW = 1;
        for (FormattedCharSequence line : lines) textW = Math.max(textW, font.width(line));
        int textH = Math.max(1, lines.size() * lineH);
        int w = Math.min(2048, Math.max(1, Math.round(textW * scale) + 2));
        int h = Math.min(2048, Math.max(1, Math.round(textH * scale) + 2));
        int color = rgb & 0x00FFFFFF | 0xFF000000; // opaque; object alpha is applied by the sprite

        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        TextureTarget target = null;
        try {
            target = new TextureTarget(w, h, false, Minecraft.ON_OSX);
            target.setClearColor(0f, 0f, 0f, 0f);
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);

            Matrix4f projection = new Matrix4f().setOrtho(0f, w, h, 0f, 1000f, 21000f);
            RenderSystem.backupProjectionMatrix();
            RenderSystem.setProjectionMatrix(projection, VertexSorting.ORTHOGRAPHIC_Z);
            PoseStack pose = new PoseStack();
            pose.translate(0f, 0f, -11000f);
            pose.scale(scale, scale, 1f);

            ByteBufferBuilder builder = new ByteBufferBuilder(4096);
            MultiBufferSource.BufferSource buffers = MultiBufferSource.immediate(builder);
            float y = 0;
            for (FormattedCharSequence line : lines) {
                font.drawInBatch(line, 0, y, color, false, pose.last().pose(), buffers,
                        Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
                y += lineH;
            }
            buffers.endBatch();
            builder.close();
            RenderSystem.restoreProjectionMatrix();

            NativeImage image = new NativeImage(w, h, false);
            RenderSystem.bindTexture(target.getColorTextureId());
            image.downloadTexture(0, false);
            image.flipY();

            DynamicTexture dynamic = new DynamicTexture(image);
            ResourceLocation id = FnfMod.id("world_text/" + COUNTER.incrementAndGet());
            Minecraft.getInstance().getTextureManager().register(id, dynamic);
            return new Baked(id, dynamic, w, h);
        } catch (Throwable error) {
            FnfMod.LOGGER.warn("Failed to bake world text: {}", error.toString());
            return null;
        } finally {
            if (target != null) target.destroyBuffers();
            main.bindWrite(true);
        }
    }

    public static void release(ResourceLocation id, DynamicTexture texture) {
        if (id != null) {
            try { Minecraft.getInstance().getTextureManager().release(id); } catch (Throwable ignored) {}
        }
        if (texture != null) {
            try { texture.close(); } catch (Throwable ignored) {}
        }
    }
}
