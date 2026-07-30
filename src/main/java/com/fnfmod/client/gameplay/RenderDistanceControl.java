package com.fnfmod.client.gameplay;

import net.minecraft.client.Minecraft;

/**
 * Lets a song change Minecraft's render distance for the duration of a play and
 * puts it back afterwards.
 *
 * <p>The original value is captured the first time a song changes it, so the
 * restore returns the exact number the player had, whatever a chart or Lua did
 * in between. Restoring when nothing changed it is a no-op, so it is always safe
 * to call on the way out of gameplay.
 */
public final class RenderDistanceControl {

    /** Minecraft's own render-distance bounds; requests are clamped to these. */
    public static final int MIN = 2;
    public static final int MAX = 32;

    private static Integer original;

    private RenderDistanceControl() {}

    /**
     * Sets the render distance for this play, clamped to Minecraft's range. The
     * player's real value is remembered on the first change so it can be restored.
     */
    public static void apply(int chunks) {
        var options = Minecraft.getInstance().options;
        if (original == null) {
            original = options.renderDistance().get();
        }
        int clamped = Math.max(MIN, Math.min(MAX, chunks));
        if (options.renderDistance().get() != clamped) {
            options.renderDistance().set(clamped);
            reloadChunks();
        }
    }

    /** The render distance currently in effect, song-set or not. */
    public static int current() {
        return Minecraft.getInstance().options.renderDistance().get();
    }

    /** Restores the value from before the song touched it. No-op if untouched. */
    public static void restore() {
        if (original == null) return;
        var options = Minecraft.getInstance().options;
        if (!options.renderDistance().get().equals(original)) {
            options.renderDistance().set(original);
            reloadChunks();
        }
        original = null;
    }

    /**
     * Applies a render-distance change so new chunks actually appear.
     *
     * <p>Two things are needed, and the option's own callback does neither. The
     * server — including the integrated server in singleplayer — only streams
     * chunk data out to the view distance the client last told it, so it has to
     * be notified or there is simply no data past the old distance to draw. Then
     * the renderer has to rebuild its chunk set for the new distance, which is
     * what the F3+A shortcut does; a plain redraw keeps the old loaded area.
     *
     * <p>This is why the change previously only appeared after visiting the video
     * settings: leaving that screen re-broadcasts the client options.
     */
    private static void reloadChunks() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        // Tell the server the new view distance so it sends the extra chunks.
        minecraft.options.broadcastOptions();
        // Rebuild the client's render chunks for the new distance, like F3+A.
        minecraft.levelRenderer.allChanged();
    }
}
