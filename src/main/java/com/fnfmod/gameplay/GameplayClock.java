package com.fnfmod.gameplay;

/**
 * A monotonic millisecond clock for gameplay animations that stops while the
 * song is paused.
 *
 * <p>Tweens, timers, camera moves, and sound fades measure progress as
 * {@code now - start}. Reading {@link System#currentTimeMillis()} for that keeps
 * counting through a pause, so the moment play resumes a tween jumps to — or past
 * — its end. Reading {@link #now()} instead subtracts the time spent paused, so a
 * tween picks up exactly where it left off.
 *
 * <p>State is static because one song plays at a time. {@link #setRunning} is
 * driven every frame from the gameplay loop, so it also freezes for a
 * pausing Lua substate and the Minecraft pause menu, not only the song menu.
 */
public final class GameplayClock {

    private static long pausedAt = -1;   // wall-clock time the pause began, or -1 while running
    private static long pausedTotal;     // accumulated paused milliseconds

    private GameplayClock() {}

    /** Milliseconds elapsed for gameplay, excluding time spent paused. */
    public static long now() {
        long reference = pausedAt >= 0 ? pausedAt : System.currentTimeMillis();
        return reference - pausedTotal;
    }

    /** Called each frame with whether the song is currently advancing. */
    public static void setRunning(boolean running) {
        if (running) resume();
        else pause();
    }

    private static void pause() {
        if (pausedAt < 0) pausedAt = System.currentTimeMillis();
    }

    private static void resume() {
        if (pausedAt >= 0) {
            pausedTotal += System.currentTimeMillis() - pausedAt;
            pausedAt = -1;
        }
    }

    /** Clears all paused time; call when a song starts so the clock stays near wall time. */
    public static void reset() {
        pausedAt = -1;
        pausedTotal = 0;
    }
}
