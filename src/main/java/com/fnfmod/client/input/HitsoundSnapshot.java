package com.fnfmod.client.input;

import com.fnfmod.client.audio.HitsoundPlayer;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Lets the high-rate input backend decide, off the game thread, whether a press
 * lands on a note — so it can play the hitsound the instant the press is
 * detected instead of waiting for the next frame.
 *
 * <p>The game thread publishes a small per-lane list of the notes currently
 * hittable, each carrying the exact sound to play. The backend reads it
 * lock-free, and when a press falls inside the window it {@linkplain
 * #claim claims} the note and plays its sound. The claim set is the handshake
 * back to the game thread: when it later credits that note it sees the claim and
 * skips the sound, so the note is never sounded twice.
 */
public final class HitsoundSnapshot {

    /** A note that can be hit right now, plus the recipe for its hitsound. */
    public record Hittable(double timeMs, Path customSound, boolean playDefault, float volume) {}

    private final AtomicReferenceArray<Hittable[]> lanes = new AtomicReferenceArray<>(4);
    private final Set<Long> claimed = ConcurrentHashMap.newKeySet();
    private volatile double window;

    /** The widest hit distance (ms) a press may land from a note and still count. */
    public void setWindow(double windowMs) {
        this.window = windowMs;
    }

    /** Publishes the hittable notes for a lane. Called each frame on the game thread. */
    public void publish(int lane, Hittable[] hittable) {
        if (lane >= 0 && lane < 4) lanes.set(lane, hittable);
    }

    /** Drops claims for notes older than the window, so the set cannot grow forever. */
    public void prune(double minTimeMs) {
        claimed.removeIf(key -> timeOf(key) < minTimeMs);
    }

    public void reset() {
        for (int lane = 0; lane < 4; lane++) lanes.set(lane, null);
        claimed.clear();
    }

    /**
     * If a press at {@code positionMs} lands on an unclaimed hittable note in this
     * lane, claims it and plays its hitsound, then returns true. Runs on the
     * backend thread. The closest in-window note is chosen so a single press does
     * not sound two stacked notes.
     */
    public boolean claim(int lane, double positionMs) {
        Hittable[] notes = lane >= 0 && lane < 4 ? lanes.get(lane) : null;
        if (notes == null) return false;
        double max = window;
        Hittable best = null;
        double bestDist = max;
        for (Hittable note : notes) {
            double dist = Math.abs(note.timeMs - positionMs);
            if (dist <= max && dist < bestDist) {
                best = note;
                bestDist = dist;
            }
        }
        if (best == null) return false;
        if (!claimed.add(key(lane, best.timeMs))) return false; // another press already sounded it
        play(best);
        return true;
    }

    /** True if the backend already sounded this note, so the game thread should not. */
    public boolean wasClaimed(int lane, double timeMs) {
        return claimed.contains(key(lane, timeMs));
    }

    public void release(int lane, double timeMs) {
        claimed.remove(key(lane, timeMs));
    }

    /** Plays a note's hitsound. Shared so the backend and game thread never differ. */
    public static void play(Hittable note) {
        if (note.customSound != null) HitsoundPlayer.play(note.customSound, note.volume);
        else if (note.playDefault) HitsoundPlayer.play();
    }

    // Claims are keyed by lane + rounded note time; a song never exceeds 40 bits of ms.
    private static long key(int lane, double timeMs) {
        return ((long) lane << 40) | (Math.round(timeMs) & 0xFF_FFFF_FFFFL);
    }

    private static long timeOf(long key) {
        return key & 0xFF_FFFF_FFFFL;
    }
}
