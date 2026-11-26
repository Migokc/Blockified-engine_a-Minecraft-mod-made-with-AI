package com.fnfmod.client.gui;

import com.fnfmod.client.math.Easing;

import java.util.function.LongSupplier;

/** A bounded, retargetable scroll offset; screens still own their layout and input. */
public final class TweenedScroll {
    private final long durationNanos;
    private final LongSupplier clock;
    private double position;
    private double target;
    private double from;
    private long started;
    private boolean active;

    public TweenedScroll(long durationNanos) {
        this(durationNanos, System::nanoTime);
    }

    TweenedScroll(long durationNanos, LongSupplier clock) {
        if (durationNanos <= 0) throw new IllegalArgumentException("durationNanos must be positive");
        this.durationNanos = durationNanos;
        this.clock = clock;
    }

    public double position() { return position; }
    public double target() { return target; }

    public void reset(double value, double max) {
        position = target = from = bounded(value, max);
        active = false;
    }

    public void animateTo(double value, double max) {
        double next = bounded(value, max);
        if (Math.abs(next - target) < 0.01) return;
        update();
        from = position;
        target = next;
        started = clock.getAsLong();
        active = Math.abs(target - from) > 0.01;
        if (!active) position = target;
    }

    public void update() {
        if (!active) return;
        double progress = (clock.getAsLong() - started) / (double) durationNanos;
        if (progress >= 1) {
            position = target;
            active = false;
        } else {
            position = from + (target - from) * Easing.apply("expoOut", progress);
        }
    }

    /** Keep an active tween inside the viewport when content or GUI scale changes. */
    public void clampTo(double max) {
        position = bounded(position, max);
        target = bounded(target, max);
        from = bounded(from, max);
        if (Math.abs(target - position) < 0.01) {
            position = target;
            active = false;
        }
    }

    private static double bounded(double value, double max) {
        if (!Double.isFinite(value) || !Double.isFinite(max)) return 0;
        return Math.max(0, Math.min(value, Math.max(0, max)));
    }
}
