package com.fnfmod.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** Shared visual language for Blockified's built-in tools and settings screens. */
public final class BlockifiedScreenStyle {
    public static final int BACKDROP = 0xF2050208;
    public static final int PANEL = 0xF0120D18;
    public static final int PANEL_INNER = 0xC01B1025;
    public static final int PANEL_SOFT = 0x90251431;

    /** Blockified purple and variants derived from #B82BFF. */
    public static final int ACCENT = 0xFFB82BFF;
    public static final int ACCENT_LIGHT = 0xFFD681FF;
    public static final int ACCENT_DARK = 0xFF65188C;
    public static final int ACCENT_DEEP = 0xFF330C47;
    public static final int ACCENT_STRONG = 0xAAB82BFF;
    public static final int ACCENT_MEDIUM = 0x66B82BFF;
    public static final int ACCENT_SOFT = 0x55B82BFF;
    public static final int ACCENT_FAINT = 0x33B82BFF;
    public static final int TEXT = 0xFFFFFFFF;
    public static final int TEXT_MUTED = 0xFFA99AB2;
    public static final int TEXT_SECTION = 0xFFD9C1E6;

    private BlockifiedScreenStyle() {}

    public static void backdrop(GuiGraphics gui, int width, int height) {
        gui.fill(0, 0, width, height, BACKDROP);
        gui.fill(0, 0, width, 2, 0x44B82BFF);
    }

    public static void panel(GuiGraphics gui, int x, int y, int width, int height) {
        gui.fill(x - 3, y - 3, x + width + 3, y + height + 3, 0xA0000000);
        gui.fill(x, y, x + width, y + height, PANEL);
        gui.fill(x, y, x + 3, y + height, ACCENT);
    }

    /** A world-visible variant used by overlays such as the song browser. */
    public static void translucentPanel(GuiGraphics gui, int x, int y, int width, int height) {
        gui.fill(x - 2, y - 2, x + width + 2, y + height + 2, 0x44000000);
        gui.fill(x, y, x + width, y + height, 0x88120D18);
        gui.fill(x, y, x + 3, y + height, 0xCCB82BFF);
        gui.renderOutline(x, y, width, height, 0x55542B68);
    }

    public static void inner(GuiGraphics gui, int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) return;
        gui.fill(x, y, x + width, y + height, PANEL_INNER);
        gui.renderOutline(x, y, width, height, 0x444A285B);
    }

    public static void header(GuiGraphics gui, Font font, int x, int y,
                              String eyebrow, String title, String description) {
        gui.drawString(font, eyebrow, x, y, ACCENT, false);
        gui.drawString(font, title, x, y + 16, TEXT, false);
        if (description != null && !description.isBlank()) {
            gui.drawString(font, description, x, y + 30, TEXT_MUTED, false);
        }
    }

    public static void section(GuiGraphics gui, Font font, String title, int x, int y) {
        gui.fill(x, y + 11, x + 3, y + 20, ACCENT);
        gui.drawString(font, title, x + 8, y + 10, TEXT_SECTION, false);
    }
}
