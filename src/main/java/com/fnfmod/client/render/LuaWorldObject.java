package com.fnfmod.client.render;

import net.minecraft.client.gui.Font;
import net.minecraft.resources.ResourceLocation;

/** Immutable render snapshots shared by Lua world-object renderers. */
public sealed interface LuaWorldObject permits LuaWorldObject.Sprite, LuaWorldObject.Text {
    double x();
    double y();
    double z();
    double scaleX();
    double scaleY();
    double alpha();
    double angle();
    int color();
    boolean billboard();
    boolean lighting();

    /** Immutable atlas data for the currently displayed animation frame. */
    record Frame(int x, int y, int width, int height, int frameX, int frameY,
                 boolean rotated) {}

    record Sprite(
            ResourceLocation texture,
            int textureWidth,
            int textureHeight,
            Frame frame,
            double animationOffsetX,
            double animationOffsetY,
            double x,
            double y,
            double z,
            double width,
            double height,
            double graphicWidth,
            double graphicHeight,
            double scaleX,
            double scaleY,
            double alpha,
            double angle,
            int color,
            boolean billboard,
            boolean lighting
    ) implements LuaWorldObject {}

    record Text(
            Font font,
            String text,
            double x,
            double y,
            double z,
            double width,
            int textSize,
            double scaleX,
            double scaleY,
            double alpha,
            double angle,
            int color,
            boolean billboard,
            boolean lighting
    ) implements LuaWorldObject {}
}
