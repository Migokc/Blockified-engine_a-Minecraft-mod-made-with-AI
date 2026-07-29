package com.fnfmod.client.render;

/**
 * Shared camHUD layer anchors. Lua setObjectOrder values are compared against
 * these anchors, so scripts can deliberately render between native systems.
 */
public final class HudLayerOrder {
    public static final int RECEPTORS = 100;
    public static final int NOTES = 200;
    public static final int HIT_EFFECTS = 300;
    public static final int HUD = 400;
    /** Psych adds new Lua objects at the top of their camera by default. */
    public static final int LUA_DEFAULT = 1000;

    private HudLayerOrder() {}
}
