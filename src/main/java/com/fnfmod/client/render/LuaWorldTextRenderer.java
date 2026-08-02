package com.fnfmod.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix4f;

import java.util.List;
import java.util.Locale;

/**
 * Draws world-camera Lua text with the vanilla {@link Font}.
 *
 * <p>Modelled on BBS FS's {@code LabelFormRenderer.render3D}: it draws the text with
 * {@code Font.drawInBatch} into a <em>private</em> immediate buffer source and flushes
 * that on the spot, wrapped in explicit depth-test / cull state. Drawing into
 * Minecraft's shared buffer source ({@code renderBuffers().bufferSource()}) during the
 * level-render stage silently painted nothing — a fresh provider, like BBS uses for its
 * 3D label form, paints correctly. Custom fonts survive because the glyphs come from the
 * font's own set, and see-through text uses the SEE_THROUGH display mode.
 */
final class LuaWorldTextRenderer {
    private static final int LINE_HEIGHT = 9;
    /** Reused private vertex sink, flushed after every text object (render thread only). */
    private static final ByteBufferBuilder BUILDER = new ByteBufferBuilder(2048);

    private LuaWorldTextRenderer() {}

    static void render(PoseStack poseStack, MultiBufferSource.BufferSource sharedIgnored,
                       LuaWorldObject.Text text, float textScale, int light) {
        if (text.text() == null || text.text().isEmpty()) return;
        Font font = text.font();

        int wrapWidth = text.width() <= 0 ? Integer.MAX_VALUE
                : Math.max(1, (int) Math.floor(text.width() / textScale));
        List<FormattedCharSequence> lines = font.split(Component.literal(text.text()), wrapWidth);
        if (lines.isEmpty()) return;

        int alpha = Math.max(0, Math.min(255, (int) Math.round(text.alpha() * 255)));
        int color = text.color() & 0x00FFFFFF | alpha << 24;
        int borderColor = text.borderColor() & 0x00FFFFFF | alpha << 24;
        int stroke = (int) Math.round(Math.abs(text.borderSize()));
        boolean shadow = "shadow".equalsIgnoreCase(text.borderStyle());
        Font.DisplayMode mode = text.seeThrough()
                ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL;

        int blockWidth = 1;
        for (FormattedCharSequence line : lines) blockWidth = Math.max(blockWidth, font.width(line));

        // A private immediate source, like BBS's own provider, rather than the shared
        // level buffer source that did not paint here.
        MultiBufferSource.BufferSource buffers = MultiBufferSource.immediate(BUILDER);
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();

        Matrix4f base = poseStack.last().pose();
        float top = -lines.size() * LINE_HEIGHT * 0.5f;

        // Pass 1: the border, flushed first so the fill is always drawn on top of it
        // (a single batch lets the translucency sort put the outline over the text).
        if (stroke > 0) {
            float y = top;
            for (FormattedCharSequence line : lines) {
                float x = alignedX(text.alignment(), font.width(line), blockWidth);
                Matrix4f m = text.italic() ? shear(base, y) : base;
                if (shadow) {
                    font.drawInBatch(line, x + stroke, y + stroke, borderColor, false, m,
                            buffers, mode, 0, light);
                } else {
                    for (int dx = -stroke; dx <= stroke; dx++) {
                        for (int dy = -stroke; dy <= stroke; dy++) {
                            if (dx == 0 && dy == 0) continue;
                            font.drawInBatch(line, x + dx, y + dy, borderColor, false, m,
                                    buffers, mode, 0, light);
                        }
                    }
                }
                y += LINE_HEIGHT;
            }
            buffers.endBatch();
        }

        // Pass 2: the fill. POLYGON_OFFSET nudges it toward the viewer so it wins the
        // depth test against the border at the same plane — no z-fighting. (See-through
        // text has no depth test, so it keeps its own mode.)
        Font.DisplayMode fillMode = stroke > 0 && mode == Font.DisplayMode.NORMAL
                ? Font.DisplayMode.POLYGON_OFFSET : mode;
        float y = top;
        for (FormattedCharSequence line : lines) {
            float x = alignedX(text.alignment(), font.width(line), blockWidth);
            Matrix4f m = text.italic() ? shear(base, y) : base;
            font.drawInBatch(line, x, y, color, false, m, buffers, fillMode, 0, light);
            y += LINE_HEIGHT;
        }
        buffers.endBatch();
        RenderSystem.enableCull();
    }

    /** left / center / right alignment inside the widest line, centred on the anchor. */
    private static float alignedX(String alignment, float lineWidth, float blockWidth) {
        String a = alignment == null ? "" : alignment.toLowerCase(Locale.ROOT);
        return switch (a) {
            case "left" -> -blockWidth * 0.5f;
            case "right" -> blockWidth * 0.5f - lineWidth;
            default -> -lineWidth * 0.5f;   // center
        };
    }

    /** Horizontal shear for italic, matching the 2D text renderer's -0.2 slope. */
    private static Matrix4f shear(Matrix4f base, float y) {
        Matrix4f m = new Matrix4f(base);
        Matrix4f s = new Matrix4f().set(new float[]{
                1, 0, 0, 0,
                -0.2f, 1, 0, 0,
                0, 0, 1, 0,
                0, 0, 0, 1});
        m.translate(0, y, 0);
        m.mul(s);
        m.translate(0, -y, 0);
        return m;
    }
}
