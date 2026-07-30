package com.fnfmod.client.camera;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Psych/Flixel camera flash and fade overlays.
 *
 * <p>A flash starts opaque and clears; a fade runs the other way and holds the
 * colour unless it was started with {@code fadeOut}. Each camera keeps its own
 * overlay so a HUD flash does not disturb one running on the game camera.
 *
 * <p>State is static because there is one active song at a time, matching how
 * {@link GameplayCamera} holds its zoom and shake.
 */
public final class CameraOverlay {

    public enum Target { GAME, HUD, OTHER }

    private record Effect(int rgb, long start, long durationMs, float from, float to) {

        float alphaAt(long now) {
            if (durationMs <= 0) return to;
            double progress = Math.min(1, (now - start) / (double) durationMs);
            return (float) (from + (to - from) * progress);
        }

        boolean finished(long now) {
            return now - start >= durationMs;
        }
    }

    private static final Map<Target, Effect> EFFECTS = new EnumMap<>(Target.class);

    private CameraOverlay() {}

    /** Psych's cameraFlash: the colour appears at once and fades away. */
    public static void flash(String camera, int rgb, double durationSeconds, boolean forceReset) {
        start(target(camera), rgb, durationSeconds, 1f, 0f, forceReset);
    }

    /**
     * Psych's cameraFade. The default fades the screen into the colour and holds
     * it; {@code fadeOut} instead clears an already-covered screen.
     */
    public static void fade(String camera, int rgb, double durationSeconds,
                            boolean forceReset, boolean fadeOut) {
        float from = fadeOut ? 1f : 0f;
        float to = fadeOut ? 0f : 1f;
        start(target(camera), rgb, durationSeconds, from, to, forceReset);
    }

    private static void start(Target target, int rgb, double durationSeconds,
                              float from, float to, boolean forceReset) {
        // Flixel ignores a new effect while one is running unless force is set.
        if (!forceReset && EFFECTS.containsKey(target)
                && !EFFECTS.get(target).finished(System.currentTimeMillis())) {
            return;
        }
        EFFECTS.put(target, new Effect(rgb & 0xFFFFFF, System.currentTimeMillis(),
                Math.max(0, (long) (durationSeconds * 1000)), from, to));
    }

    /**
     * Packed ARGB to draw over this camera, or 0 when nothing is active. A held
     * fade keeps returning its colour, so the screen stays covered.
     */
    public static int colorFor(Target target) {
        Effect effect = EFFECTS.get(target);
        if (effect == null) return 0;
        long now = System.currentTimeMillis();
        float alpha = effect.alphaAt(now);
        if (effect.finished(now) && effect.to <= 0) {
            EFFECTS.remove(target);
            return 0;
        }
        int alphaByte = Math.max(0, Math.min(255, Math.round(alpha * 255)));
        return alphaByte == 0 ? 0 : alphaByte << 24 | effect.rgb;
    }

    public static void reset() {
        EFFECTS.clear();
    }

    private static Target target(String camera) {
        String name = camera == null ? "" : camera.toLowerCase(Locale.ROOT).replace("cam", "");
        return switch (name) {
            case "hud" -> Target.HUD;
            case "other" -> Target.OTHER;
            default -> Target.GAME;
        };
    }
}
