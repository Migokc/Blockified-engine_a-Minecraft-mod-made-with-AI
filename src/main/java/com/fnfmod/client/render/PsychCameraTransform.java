package com.fnfmod.client.render;

/** Exact virtual-canvas transform for one Psych camGame object. */
public final class PsychCameraTransform {
    public record Result(double x, double y, double scaleX, double scaleY) {}

    private PsychCameraTransform() {}

    /**
     * Interpolates between screen-stable (scrollFactor 0) and full camGame
     * movement (scrollFactor 1). Keeping position and scale on the same axis
     * removes the drift caused by the previous per-object zoom approximation.
     */
    public static Result apply(double objectX, double objectY,
                               double scrollFactorX, double scrollFactorY,
                               double cameraX, double cameraY, double zoom,
                               double shakeX, double shakeY) {
        double influenceX = clamp01(Math.abs(scrollFactorX));
        double influenceY = clamp01(Math.abs(scrollFactorY));
        double fullX = PsychCanvas.WIDTH * 0.5 + (objectX - cameraX) * zoom + shakeX;
        double fullY = PsychCanvas.HEIGHT * 0.5 + (objectY - cameraY) * zoom + shakeY;
        double drawX = objectX + (fullX - objectX) * scrollFactorX;
        double drawY = objectY + (fullY - objectY) * scrollFactorY;
        return new Result(drawX, drawY,
                1 + (zoom - 1) * influenceX,
                1 + (zoom - 1) * influenceY);
    }

    private static double clamp01(double value) {
        return Math.max(0, Math.min(1, value));
    }
}
