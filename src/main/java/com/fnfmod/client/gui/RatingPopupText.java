package com.fnfmod.client.gui;

import com.fnfmod.client.render.LuaTextBaker;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** Shared gameplay/settings preview renderer for Minecraft-font rating popups. */
final class RatingPopupText {
    static final float DEFAULT_RATING_SCALE = 6f;
    static final float DEFAULT_COMBO_SCALE = 5.5f;
    private static final int OUTLINE_FONT_PIXELS = 1;

    private RatingPopupText() {}

    static float digitWidth(Font font, float comboSize) {
        return font.width("0") * DEFAULT_COMBO_SCALE * comboSize;
    }

    static float firstDigitOffset(Font font, String rating, float ratingSize, float comboSize) {
        return font.width(rating) * DEFAULT_RATING_SCALE * ratingSize * 0.15f
                + digitWidth(font, comboSize) * 0.5f;
    }

    static float digitStep(Font font, float comboSize) {
        return digitWidth(font, comboSize) + 9f * comboSize;
    }

    static void draw(GuiGraphics gui, Font font, String text, float x, float y,
                     float scale, int color, float alpha) {
        if (alpha <= 0f || text == null || text.isEmpty() || !Float.isFinite(scale) || scale <= 0f) return;
        int fill = 0xFF000000 | color & 0xFFFFFF;
        int dark = 0xFF000000
                | ((color >> 16 & 255) * 3 / 10) << 16
                | ((color >> 8 & 255) * 3 / 10) << 8
                | (color & 255) * 3 / 10;
        // Bake the Minecraft glyph and its outline at native font resolution, then
        // scale the finished texture with nearest filtering. The outline remains one
        // font pixel wide at every popup size, rather than one Psych-canvas pixel.
        int margin = OUTLINE_FONT_PIXELS + LuaTextBaker.PADDING;
        int contentWidth = Math.max(1, font.width(text));
        int contentHeight = Math.max(1, font.lineHeight);
        int width = contentWidth + margin * 2;
        int height = contentHeight + margin * 2;
        var key = new LuaTextBaker.Key("rating-native-v2|" + text + "|"
                + Integer.toHexString(fill) + "|" + Integer.toHexString(dark), width, height);
        gui.flush();
        var baked = LuaTextBaker.bakeLayers(key, target -> {
            for (int dx = -OUTLINE_FONT_PIXELS; dx <= OUTLINE_FONT_PIXELS; dx++) {
                for (int dy = -OUTLINE_FONT_PIXELS; dy <= OUTLINE_FONT_PIXELS; dy++) {
                    if (dx == 0 && dy == 0
                            || dx * dx + dy * dy > OUTLINE_FONT_PIXELS * OUTLINE_FONT_PIXELS) continue;
                    target.drawString(font, text, margin + dx, margin + dy, dark, false);
                }
            }
        }, dark, target -> target.drawString(font, text, margin, margin, fill, false), fill);
        if (baked != null) {
            LuaTextBaker.draw(gui.pose().last().pose(), baked,
                    x - (contentWidth * 0.5f + margin) * scale,
                    y - (contentHeight * 0.5f + margin) * scale,
                    width * scale, height * scale, alpha);
            return;
        }

        // Keep the rating visible even if the off-screen bake is unavailable.
        gui.pose().pushPose();
        gui.pose().translate(x, y, 0);
        gui.pose().scale(scale, scale, 1f);
        int left = -contentWidth / 2;
        int top = -contentHeight / 2;
        int opacity = Math.clamp(Math.round(alpha * 255f), 0, 255);
        for (int dx = -OUTLINE_FONT_PIXELS; dx <= OUTLINE_FONT_PIXELS; dx++) {
            for (int dy = -OUTLINE_FONT_PIXELS; dy <= OUTLINE_FONT_PIXELS; dy++) {
                if (dx == 0 && dy == 0
                        || dx * dx + dy * dy > OUTLINE_FONT_PIXELS * OUTLINE_FONT_PIXELS) continue;
                gui.drawString(font, text, left + dx, top + dy,
                        opacity << 24 | dark & 0xFFFFFF, false);
            }
        }
        gui.drawString(font, text, left, top, opacity << 24 | fill & 0xFFFFFF, false);
        gui.pose().popPose();
    }
}
