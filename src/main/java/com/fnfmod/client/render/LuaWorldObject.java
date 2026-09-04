package com.fnfmod.client.render;

import net.minecraft.client.gui.Font;
import net.minecraft.resources.ResourceLocation;

/** Immutable render snapshots shared by Lua world-object renderers. */
public sealed interface LuaWorldObject permits LuaWorldObject.Sprite, LuaWorldObject.Text {
    /**
     * Material path used for a world object.  Keeping flat and emissive separate is
     * important under Iris: shader packs commonly reinterpret Minecraft's emissive
     * entity pass, so it is not a reliable implementation of "unlit".
     */
    enum RenderMode {
        LIT("lit"), FLAT("flat"), EMISSIVE("emissive");

        private final String luaName;

        RenderMode(String luaName) { this.luaName = luaName; }

        public String luaName() { return luaName; }
        public boolean usesWorldLight() { return this == LIT; }

        public static RenderMode resolve(String value, boolean lighting) {
            if (value != null) {
                return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
                    case "lit", "world", "entity", "shaded" -> LIT;
                    case "emissive", "glow", "glowing" -> EMISSIVE;
                    case "flat", "unlit", "fullbright", "full-bright" -> FLAT;
                    default -> lighting ? LIT : FLAT;
                };
            }
            return lighting ? LIT : FLAT;
        }
    }

    double x();
    double y();
    double z();
    double scaleX();
    double scaleY();
    double alpha();
    double angle();
    double rotationX();
    double rotationY();
    int color();
    boolean billboard();
    boolean lighting();
    RenderMode renderMode();
    boolean seeThrough();

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
            double rotationX,
            double rotationY,
            int color,
            boolean billboard,
            boolean lighting,
            RenderMode renderMode,
            boolean seeThrough
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
            double rotationX,
            double rotationY,
            int color,
            boolean billboard,
            boolean lighting,
            RenderMode renderMode,
            boolean seeThrough,
            double borderSize,
            int borderColor,
            String borderStyle,
            String alignment,
            boolean italic,
            double lineSpacing,
            double letterSpacing,
            boolean surfaceAttached,
            /** Uses the same front-face basis as a sprite/button plane. */
            boolean spritePlaneOrientation
    ) implements LuaWorldObject {}
}
