package com.fnfmod.client.gameplay;

import net.minecraft.client.Minecraft;

/**
 * Lets a song change the player's base field of view for the duration of a play
 * and puts it back afterwards.
 *
 * <p>The gameplay camera's Camera Zoom is a multiplier on top of this base FOV,
 * so scripts that want a different zoom range set the base here first. The real
 * value is captured the first time a song changes it, so the restore returns the
 * exact FOV the player had, whatever a chart or Lua did in between. Restoring
 * when nothing changed it is a no-op, so it is always safe to call on the way out.
 */
public final class FieldOfViewControl {

    /** Minecraft's own FOV slider bounds; requests are clamped to these. */
    public static final int MIN = 30;
    public static final int MAX = 110;

    private static Integer original;

    private FieldOfViewControl() {}

    /** Sets the base FOV for this play, clamped to Minecraft's range. */
    public static void apply(int fov) {
        var options = Minecraft.getInstance().options;
        if (original == null) {
            original = options.fov().get();
        }
        options.fov().set(Math.max(MIN, Math.min(MAX, fov)));
    }

    /** The base FOV currently in effect, song-set or not. */
    public static int current() {
        return Minecraft.getInstance().options.fov().get();
    }

    /** Restores the value from before the song touched it. No-op if untouched. */
    public static void restore() {
        if (original == null) return;
        Minecraft.getInstance().options.fov().set(original);
        original = null;
    }
}
