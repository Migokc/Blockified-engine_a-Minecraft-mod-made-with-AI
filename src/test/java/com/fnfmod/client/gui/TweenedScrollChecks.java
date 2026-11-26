package com.fnfmod.client.gui;

import com.fnfmod.client.math.Easing;

import java.util.concurrent.atomic.AtomicLong;

/** Exercises picker scrolling without a Minecraft window. */
public final class TweenedScrollChecks {
    private static void near(double actual, double expected, String message) {
        if (Math.abs(actual - expected) > 0.0001) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }

    public static void main(String[] args) {
        AtomicLong time = new AtomicLong();
        TweenedScroll scroll = new TweenedScroll(200_000_000L, time::get);
        scroll.reset(30, 100);
        scroll.animateTo(90, 100);
        time.set(100_000_000L);
        scroll.update();
        double halfway = 30 + 60 * Easing.apply("expoOut", 0.5);
        near(scroll.position(), halfway, "expoOut at half duration");

        scroll.animateTo(20, 100);
        near(scroll.position(), halfway, "retarget starts from visible position");
        time.set(300_000_000L);
        scroll.update();
        near(scroll.position(), 20, "retarget finishes at the new position");

        scroll.reset(150, 100);
        near(scroll.position(), 100, "reset clamps to maximum");
        scroll.animateTo(-50, 100);
        scroll.clampTo(0);
        near(scroll.position(), 0, "content shrinking to one page clears offset");
        near(scroll.target(), 0, "content shrinking clears target");

        TweenedScroll otherPanel = new TweenedScroll(200_000_000L, time::get);
        otherPanel.reset(40, 100);
        scroll.reset(10, 100);
        scroll.animateTo(80, 100);
        near(otherPanel.position(), 40, "independent panels keep separate positions");
        near(otherPanel.target(), 40, "independent panels keep separate targets");
        System.out.println("Tweened settings/picker scroll checks passed.");
    }
}
