package com.fnfmod.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/** Draws world-camera Lua text after its world transform has been applied. */
final class LuaWorldTextRenderer {
    private static final int LINE_HEIGHT = 9;

    private LuaWorldTextRenderer() {}

    static void render(PoseStack poseStack, MultiBufferSource.BufferSource buffers,
                       LuaWorldObject.Text text, float textScale, int light) {
        if (text.text().isEmpty()) return;
        Font font = text.font();
        int wrapWidth = text.width() <= 0 ? Integer.MAX_VALUE
                : Math.max(1, (int) Math.floor(text.width() / textScale));
        List<FormattedCharSequence> lines = font.split(Component.literal(text.text()), wrapWidth);
        if (lines.isEmpty()) return;

        int alpha = Math.max(0, Math.min(255, (int) Math.round(text.alpha() * 255)));
        int color = text.color() & 0x00FFFFFF | alpha << 24;
        // Text labels stay readable regardless of the world light at their spot
        // (a dark/underwater position would otherwise render the glyphs black and
        // make the text look like it never appeared). NORMAL beats POLYGON_OFFSET
        // here: the offset mode can push thin glyph quads behind nearby geometry.
        int drawLight = LightTexture.FULL_BRIGHT;
        float y = -lines.size() * LINE_HEIGHT * 0.5f;
        for (FormattedCharSequence line : lines) {
            float x = -font.width(line) * 0.5f;
            font.drawInBatch(line, x, y, color, false, poseStack.last().pose(), buffers,
                    Font.DisplayMode.NORMAL, 0, drawLight);
            y += LINE_HEIGHT;
        }
    }
}
