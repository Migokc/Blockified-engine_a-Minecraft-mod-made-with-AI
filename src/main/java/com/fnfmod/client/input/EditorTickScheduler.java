package com.fnfmod.client.input;

import java.util.concurrent.locks.LockSupport;
import java.util.function.DoubleSupplier;

/**
 * Fires the chart editor's playback ticks against the audio clock instead of the
 * render frame, so a tick lands on its note as it scrolls rather than up to a
 * frame late.
 *
 * <p>A background thread samples the precise playback position at roughly a
 * kilohertz and plays each note's tick the instant the position reaches it. The
 * tick itself must be thread-safe to play, so it goes through {@code HitsoundPlayer}'s
 * audio thread; the editor keeps its own frame-based fallback for the no-hitsound
 * case, which uses the main-thread sound engine.
 */
public final class EditorTickScheduler {

    /** Plays one tick for a note on the given side, off the render thread. */
    @FunctionalInterface
    public interface TickSink {
        void play(boolean playerSide);
    }

    private static final long POLL_INTERVAL_NANOS = 500_000L;

    private final DoubleSupplier viewPositionMs;
    private final TickSink sink;

    // Sorted note view-times and sides; swapped as whole arrays for lock-free reads.
    private volatile double[] times = new double[0];
    private volatile boolean[] sides = new boolean[0];
    private volatile int index;
    private volatile boolean running;
    private volatile boolean active;
    private Thread thread;

    public EditorTickScheduler(DoubleSupplier viewPositionMs, TickSink sink) {
        this.viewPositionMs = viewPositionMs;
        this.sink = sink;
    }

    /** Replaces the note list. Times must be sorted ascending (chart order). */
    public synchronized void setNotes(double[] noteTimes, boolean[] noteSides) {
        this.times = noteTimes;
        this.sides = noteSides;
    }

    /** Points the cursor at the first note at or after the given view position. */
    public void seek(double positionMs) {
        double[] t = times;
        int i = 0;
        while (i < t.length && t[i] < positionMs) i++;
        index = i;
    }

    /** Enables sampling. Starts the thread on first use. */
    public void setActive(boolean value) {
        if (value && thread == null) start();
        if (value && !active) LockSupport.unpark(thread);
        active = value;
    }

    private synchronized void start() {
        if (thread != null) return;
        running = true;
        thread = new Thread(this::loop, "fnfmod-editor-ticks");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        active = false;
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    private void loop() {
        while (running) {
            if (active) fireDue();
            LockSupport.parkNanos(POLL_INTERVAL_NANOS);
        }
    }

    private void fireDue() {
        double pos = viewPositionMs.getAsDouble();
        double[] t = times;
        boolean[] s = sides;
        int i = index;
        while (i < t.length && t[i] <= pos) {
            try {
                sink.play(i < s.length && s[i]);
            } catch (Throwable ignored) {
                // A tick problem must not stop scheduling.
            }
            i++;
        }
        index = i;
    }
}
