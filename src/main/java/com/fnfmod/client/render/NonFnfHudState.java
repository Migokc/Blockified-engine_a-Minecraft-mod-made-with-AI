package com.fnfmod.client.render;

import com.fnfmod.client.lua.PsychColor;

/**
 * Lua-controllable state for the non-FNF HUD styles (default / abbreviated /
 * numbers / vanilla): the XP-style bar and the score / miss text.
 *
 * <p>Mirrors the {@code healthBar.*} and {@code scoreTxt.*} property paths of
 * {@link PsychHudState} so the same scripts work regardless of HUD style.
 * Position and colour overrides are {@code null} until a script sets them, and
 * fall back to the live layout default — so a window resize still repositions an
 * untouched element instead of leaving it stranded at an old coordinate.
 */
public final class NonFnfHudState {

    public Double barX, barY;
    public double barAlpha = 1;
    public double barScale = 1;
    public boolean barVisible = true;

    public Double scoreX, scoreY;
    public double scoreAlpha = 1;
    public double scoreScale = 1;
    public boolean scoreVisible = true;
    public Integer scoreColor;
    public String scoreText;

    public double barX(double def) { return barX == null ? def : barX; }
    public double barY(double def) { return barY == null ? def : barY; }
    public double scoreX(double def) { return scoreX == null ? def : scoreX; }
    public double scoreY(double def) { return scoreY == null ? def : scoreY; }
    public int scoreColor(int def) { return scoreColor == null ? def : scoreColor; }
    public String scoreText(String generated) { return scoreText == null ? generated : scoreText; }

    public Object property(String path, double defBarX, double defBarY,
                           double defScoreX, double defScoreY, int defScoreColor, String generated) {
        return switch (path) {
            case "healthBar.x" -> barX(defBarX);
            case "healthBar.y" -> barY(defBarY);
            case "healthBar.alpha" -> barAlpha;
            case "healthBar.scale", "healthBar.scale.x", "healthBar.scale.y" -> barScale;
            case "healthBar.visible" -> barVisible;
            case "scoreTxt.x" -> scoreX(defScoreX);
            case "scoreTxt.y" -> scoreY(defScoreY);
            case "scoreTxt.alpha" -> scoreAlpha;
            case "scoreTxt.scale", "scoreTxt.scale.x", "scoreTxt.scale.y" -> scoreScale;
            case "scoreTxt.visible" -> scoreVisible;
            case "scoreTxt.color" -> scoreColor(defScoreColor);
            case "scoreTxt.text" -> scoreText(generated);
            default -> null;
        };
    }

    public boolean setProperty(String path, Object value) {
        switch (path) {
            case "healthBar.x" -> barX = number(value);
            case "healthBar.y" -> barY = number(value);
            case "healthBar.alpha" -> barAlpha = clampAlpha(number(value));
            case "healthBar.scale", "healthBar.scale.x", "healthBar.scale.y" -> barScale = Math.max(0, number(value));
            case "healthBar.visible" -> barVisible = bool(value);
            case "scoreTxt.x" -> scoreX = number(value);
            case "scoreTxt.y" -> scoreY = number(value);
            case "scoreTxt.alpha" -> scoreAlpha = clampAlpha(number(value));
            case "scoreTxt.scale", "scoreTxt.scale.x", "scoreTxt.scale.y" -> scoreScale = Math.max(0, number(value));
            case "scoreTxt.visible" -> scoreVisible = bool(value);
            case "scoreTxt.color" -> scoreColor = color(value);
            case "scoreTxt.text" -> scoreText = String.valueOf(value);
            default -> { return false; }
        }
        return true;
    }

    private static double clampAlpha(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private static int color(Object value) {
        return value instanceof Number number ? number.intValue() : PsychColor.parse(String.valueOf(value));
    }

    private static double number(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        try { return Double.parseDouble(String.valueOf(value)); }
        catch (Exception ignored) { return 0; }
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean bool) return bool;
        if (value instanceof Number number) return number.doubleValue() != 0;
        return Boolean.parseBoolean(String.valueOf(value));
    }
}
