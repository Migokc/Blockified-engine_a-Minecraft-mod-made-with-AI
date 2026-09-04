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

    static void render(PoseStack poseStack, MultiBufferSource.BufferSource shared,
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
        Font.DisplayMode mode = text.seeThrough() ? Font.DisplayMode.SEE_THROUGH
                : text.surfaceAttached() ? Font.DisplayMode.POLYGON_OFFSET
                : Font.DisplayMode.NORMAL;

        double letterSpacing = text.letterSpacing();
        // One line's step down the block, so lineSpacing widens or tightens the gap.
        float advance = (float) Math.max(1.0, LINE_HEIGHT + text.lineSpacing());
        float blockWidth = 1;
        for (FormattedCharSequence line : lines) {
            blockWidth = Math.max(blockWidth, lineWidth(font, line, letterSpacing));
        }

        // Bake every world text block once. Besides being much cheaper for outlined text, the
        // two-sided textured quad gives labels a stable mirrored back face. Attached button text
        // uses polygon offset, so it shares the exact button pivot without z-fighting.
        if (drawBaked(poseStack, shared, text, font, lines, advance,
                blockWidth, stroke, letterSpacing, color, borderColor, shadow, alpha, mode, light)) {
            return;
        }

        // A private immediate source, like BBS's own provider, rather than the shared
        // level buffer source that did not paint here.
        MultiBufferSource.BufferSource buffers = MultiBufferSource.immediate(BUILDER);
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();

        Matrix4f base = poseStack.last().pose();
        float top = -lines.size() * advance * 0.5f;

        // Pass 1: the border, flushed first so the fill is always drawn on top of it
        // (a single batch lets the translucency sort put the outline over the text).
        if (stroke > 0) {
            // Fallback used only if baking is unavailable.
            float y = top;
            for (FormattedCharSequence line : lines) {
                float x = alignedX(text.alignment(), lineWidth(font, line, letterSpacing), blockWidth);
                Matrix4f m = text.italic() ? shear(base, y) : base;
                if (shadow) {
                    // A shadow is one offset copy, so its alpha is already the requested one.
                    drawLine(font, line, letterSpacing, x + stroke, y + stroke, borderColor, m,
                            buffers, mode, light);
                } else {
                    for (int dx = -stroke; dx <= stroke; dx++) {
                        for (int dy = -stroke; dy <= stroke; dy++) {
                            if (dx == 0 && dy == 0) continue;
                            drawLine(font, line, letterSpacing, x + dx, y + dy, borderColor, m,
                                    buffers, mode, light);
                        }
                    }
                }
                y += advance;
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
            float x = alignedX(text.alignment(), lineWidth(font, line, letterSpacing), blockWidth);
            Matrix4f m = text.italic() ? shear(base, y) : base;
            drawLine(font, line, letterSpacing, x, y, color, m, buffers, fillMode, light);
            y += advance;
        }
        buffers.endBatch();
        RenderSystem.enableCull();
    }

    /**
     * Bakes the block into a texture and draws it as a single quad in the text's own plane, so a
     * translucent block shows exactly its alpha. Returns false when baking is unavailable.
     */
    private static boolean drawBaked(PoseStack poseStack, MultiBufferSource.BufferSource buffers,
                                     LuaWorldObject.Text text, Font font,
                                     List<FormattedCharSequence> lines, float advance,
                                     float blockWidth, int stroke, double letterSpacing,
                                     int color, int borderColor, boolean shadow, int alpha,
                                     Font.DisplayMode mode, int light) {
        int margin = stroke + LuaTextBaker.PADDING;
        int width = (int) Math.ceil(blockWidth) + margin * 2;
        int height = (int) Math.ceil((lines.size() - 1) * advance) + LINE_HEIGHT + margin * 2;
        int opaqueFill = (color & 0x00FFFFFF) | 0xFF000000;
        int opaqueBorder = (borderColor & 0x00FFFFFF) | 0xFF000000;
        String signature = String.join("|", "world", text.text(), String.valueOf(text.textSize()),
                String.valueOf(text.width()), text.alignment(), String.valueOf(text.italic()),
                String.valueOf(stroke), text.borderStyle(), Integer.toHexString(opaqueFill),
                Integer.toHexString(opaqueBorder), String.valueOf(text.lineSpacing()),
                String.valueOf(letterSpacing), String.valueOf(lines.size()));

        float half = blockWidth * 0.5f;
        LuaTextBaker.Key key = new LuaTextBaker.Key(signature, width, height);
        var baked = LuaTextBaker.bakeLayers(key, gui -> {
            float y = margin;
            for (FormattedCharSequence line : lines) {
                float x = alignedX(text.alignment(), lineWidth(font, line, letterSpacing), blockWidth)
                        + half + margin;
                paintLine(gui, font, line, letterSpacing, x, y, opaqueBorder, stroke, shadow,
                        text.italic());
                y += advance;
            }
        }, opaqueBorder, gui -> {
            float y = margin;
            for (FormattedCharSequence line : lines) {
                float x = alignedX(text.alignment(), lineWidth(font, line, letterSpacing), blockWidth)
                        + half + margin;
                paintGlyphs(gui, font, line, letterSpacing, x, y, opaqueFill, text.italic());
                y += advance;
            }
        }, opaqueFill);
        if (baked == null) return false;

        boolean seeThrough = mode == Font.DisplayMode.SEE_THROUGH;
        boolean polygonOffset = mode == Font.DisplayMode.POLYGON_OFFSET;
        LuaTextBaker.drawWorld(poseStack.last(), buffers, baked, -half - margin,
                -lines.size() * advance * 0.5f - margin, width, height, alpha / 255f,
                seeThrough, polygonOffset, light);
        return true;
    }

    /** The outline stamps, painted opaque inside the baked block. */
    private static void paintLine(net.minecraft.client.gui.GuiGraphics gui, Font font,
                                  FormattedCharSequence line, double letterSpacing,
                                  float x, float y, int borderColor, int stroke, boolean shadow,
                                  boolean italic) {
        if (shadow) {
            paintGlyphs(gui, font, line, letterSpacing, x + stroke, y + stroke, borderColor, italic);
            return;
        }
        for (int dx = -stroke; dx <= stroke; dx++) {
            for (int dy = -stroke; dy <= stroke; dy++) {
                if (dx == 0 && dy == 0) continue;
                paintGlyphs(gui, font, line, letterSpacing, x + dx, y + dy, borderColor, italic);
            }
        }
    }

    /** One line of glyphs, honouring tracking and the italic shear. */
    private static void paintGlyphs(net.minecraft.client.gui.GuiGraphics gui, Font font,
                                    FormattedCharSequence line, double letterSpacing,
                                    float x, float y, int color, boolean italic) {
        if (italic) {
            gui.pose().pushPose();
            gui.pose().translate(0, y, 0);
            gui.pose().mulPose(new org.joml.Matrix4f().set(new float[]{
                    1, 0, 0, 0,
                    -0.2f, 1, 0, 0,
                    0, 0, 1, 0,
                    0, 0, 0, 1}));
            gui.pose().translate(0, -y, 0);
        }
        if (letterSpacing == 0) {
            gui.drawString(font, line, (int) Math.round(x), (int) Math.round(y), color, false);
        } else {
            double penX = x;
            for (int point : codePoints(line)) {
                String glyph = glyph(point);
                gui.drawString(font, glyph, (int) Math.round(penX), (int) Math.round(y), color, false);
                penX += font.width(glyph) + letterSpacing;
            }
        }
        if (italic) gui.pose().popPose();
    }

    /** Width of one line including the gaps letterSpacing inserts between glyphs. */
    private static float lineWidth(Font font, FormattedCharSequence line, double letterSpacing) {
        if (letterSpacing == 0) return font.width(line);
        List<Integer> points = codePoints(line);
        if (points.isEmpty()) return 0;
        double total = -letterSpacing;
        for (int point : points) total += font.width(glyph(point)) + letterSpacing;
        return (float) Math.max(0, total);
    }

    private static List<Integer> codePoints(FormattedCharSequence line) {
        List<Integer> points = new java.util.ArrayList<>();
        line.accept((index, style, codePoint) -> {
            points.add(codePoint);
            return true;
        });
        return points;
    }

    private static String glyph(int codePoint) {
        return new String(Character.toChars(codePoint));
    }

    /** Draws a line glyph by glyph so each gap carries the tracking value. */
    private static void drawLine(Font font, FormattedCharSequence line, double letterSpacing,
                                 float x, float y, int color, Matrix4f matrix,
                                 MultiBufferSource buffers, Font.DisplayMode mode, int light) {
        if (letterSpacing == 0) {
            font.drawInBatch(line, x, y, color, false, matrix, buffers, mode, 0, light);
            return;
        }
        double penX = x;
        for (int point : codePoints(line)) {
            String glyph = glyph(point);
            font.drawInBatch(glyph, (float) penX, y, color, false, matrix, buffers, mode, 0, light);
            penX += font.width(glyph) + letterSpacing;
        }
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
