package com.fnfmod.client.render;

import net.minecraft.client.gui.GuiGraphics;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Converts logical GUI bounds through the active pose before enabling a scissor. */
public final class PoseScissor {
    private PoseScissor() {}

    public static void enable(GuiGraphics gui, float left, float top, float right, float bottom) {
        Matrix4f pose = gui.pose().last().pose();
        Vector3f a = transform(pose, left, top);
        Vector3f b = transform(pose, right, top);
        Vector3f c = transform(pose, left, bottom);
        Vector3f d = transform(pose, right, bottom);
        float minX = Math.min(Math.min(a.x, b.x), Math.min(c.x, d.x));
        float maxX = Math.max(Math.max(a.x, b.x), Math.max(c.x, d.x));
        float minY = Math.min(Math.min(a.y, b.y), Math.min(c.y, d.y));
        float maxY = Math.max(Math.max(a.y, b.y), Math.max(c.y, d.y));
        gui.enableScissor((int) Math.floor(minX), (int) Math.floor(minY),
                (int) Math.ceil(maxX), (int) Math.ceil(maxY));
    }

    private static Vector3f transform(Matrix4f pose, float x, float y) {
        return pose.transformPosition(new Vector3f(x, y, 0));
    }
}
