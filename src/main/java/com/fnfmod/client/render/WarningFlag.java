package com.fnfmod.client.render;

import com.fnfmod.client.math.Easing;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Small advisory flag that slides up from the bottom-left, holds, and slides
 * back down. Non-blocking: the song plays while it shows. Several queued
 * warnings play one after another, so each gets its own moment on screen.
 *
 * <p>Presentation only. What to warn about lives in
 * {@link com.fnfmod.client.gameplay.SongWarnings}; this just displays the text
 * it produces, so a new warning needs no change here.
 */
public final class WarningFlag {

    private static final long SLIDE_MS = 260;
    private static final long HOLD_MS = 4200;
    private static final int MARGIN = 6;
    private static final int PADDING = 6;
    private static final int MAX_TEXT_WIDTH = 190;
    /** Panel brightness relative to the accent; low so light text stays readable. */
    private static final double PANEL_TINT = 0.14;
    /** Border brightness relative to the accent; brighter than the panel. */
    private static final double BORDER_TINT = 0.45;
    private static final int PANEL_ALPHA = 0xE6;

    private record Flag(String text, int accent) {}

    private static final Deque<Flag> QUEUE = new ArrayDeque<>();
    private static Flag current;
    private static long currentStart;

    private WarningFlag() {}

    /** Queues one warning with its own stripe colour. */
    public static void show(String message, int accent) {
        if (message != null && !message.isBlank()) QUEUE.add(new Flag(message, accent));
    }

    /** Clears everything on the way out of gameplay. */
    public static void clear() {
        QUEUE.clear();
        current = null;
    }

    /**
     * Advances and draws the active flag. Call once per frame in screen space.
     * The screen height positions the flag against the bottom edge.
     */
    public static void render(GuiGraphics gui, int screenHeight) {
        long now = System.currentTimeMillis();
        if (current == null) {
            current = QUEUE.poll();
            if (current == null) return;
            currentStart = now;
        }

        long elapsed = now - currentStart;
        long total = SLIDE_MS + HOLD_MS + SLIDE_MS;
        if (elapsed >= total) {
            current = null;
            return; // next frame pulls the following flag, leaving a brief gap
        }

        Font font = Minecraft.getInstance().font;
        var lines = font.split(Component.literal(current.text), MAX_TEXT_WIDTH);
        int textWidth = 0;
        for (var line : lines) textWidth = Math.max(textWidth, font.width(line));

        int panelWidth = textWidth + PADDING * 2 + 4; // +4 for the accent stripe
        int panelHeight = lines.size() * 10 + PADDING * 2 - 1;

        // Slide progress: up during the first slide, down during the last.
        double shown;
        if (elapsed < SLIDE_MS) {
            shown = Easing.apply("cubeOut", elapsed / (double) SLIDE_MS);
        } else if (elapsed < SLIDE_MS + HOLD_MS) {
            shown = 1;
        } else {
            shown = 1 - Easing.apply("cubeIn", (elapsed - SLIDE_MS - HOLD_MS) / (double) SLIDE_MS);
        }

        int hiddenY = screenHeight;                       // fully off the bottom
        int restY = screenHeight - panelHeight - MARGIN;  // resting position
        int x = MARGIN;
        int y = (int) Math.round(hiddenY + (restY - hiddenY) * shown);

        gui.fill(x, y, x + panelWidth, y + panelHeight, tint(current.accent, PANEL_TINT, PANEL_ALPHA));
        gui.fill(x, y, x + 3, y + panelHeight, current.accent);         // full-strength stripe
        gui.renderOutline(x, y, panelWidth, panelHeight, tint(current.accent, BORDER_TINT, 0xFF));

        int textX = x + 4 + PADDING;
        int textY = y + PADDING;
        for (int i = 0; i < lines.size(); i++) {
            gui.drawString(font, lines.get(i), textX, textY + i * 10, 0xFFEFEFEF, false);
        }
    }

    /** A darker shade of the accent for the panel and border, at the given alpha. */
    private static int tint(int accent, double brightness, int alpha) {
        int red = (int) Math.round(((accent >> 16) & 0xFF) * brightness);
        int green = (int) Math.round(((accent >> 8) & 0xFF) * brightness);
        int blue = (int) Math.round((accent & 0xFF) * brightness);
        return (alpha & 0xFF) << 24 | red << 16 | green << 8 | blue;
    }
}
