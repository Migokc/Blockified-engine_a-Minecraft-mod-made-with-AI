package com.fnfmod.client.render;

import net.minecraft.client.gui.GuiGraphics;

/** Fixed Psych Engine 1280x720 canvas shared by Lua and the FNF scene. */
public final class PsychCanvas {
    public static final int WIDTH = 1280;
    public static final int HEIGHT = 720;

    private PsychCanvas() {}

    public static void push(GuiGraphics gui, float zoom) {
        push(gui, zoom, WIDTH * 0.5, HEIGHT * 0.5);
    }

    /** Game-camera transform: center the viewport on a Psych world coordinate. */
    public static void push(GuiGraphics gui, float zoom, double targetX, double targetY) {
        float scale = Math.min(gui.guiWidth() / (float) WIDTH, gui.guiHeight() / (float) HEIGHT);
        float x = (gui.guiWidth() - WIDTH * scale) * 0.5f;
        float y = (gui.guiHeight() - HEIGHT * scale) * 0.5f;
        gui.pose().pushPose();
        gui.pose().translate(x, y, 0);
        gui.pose().scale(scale, scale, 1);
        gui.pose().translate(WIDTH * 0.5f, HEIGHT * 0.5f, 0);
        gui.pose().scale(zoom, zoom, 1);
        gui.pose().translate(-targetX, -targetY, 0);
    }

    public static void pop(GuiGraphics gui) {
        gui.pose().popPose();
    }
}
