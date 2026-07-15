package com.fnfmod.client.render;

import com.fnfmod.client.lua.PsychColor;

/** Mutable script-facing state for Blockified's Psych-style native HUD objects. */
public final class PsychHudState {
    public double barX;
    public double barY;
    public double barWidth = 600;
    public double barHeight = 8;
    public double barAlpha = 1;
    public boolean barVisible = true;
    public int opponentColor = -1;
    public int playerColor = -1;

    public double backgroundX;
    public double backgroundY;
    public double backgroundWidth = 604;
    public double backgroundHeight = 12;
    public double backgroundAlpha = 1;
    public boolean backgroundVisible = true;

    public double scoreX;
    public double scoreY;
    public double scoreWidth;
    public double scoreHeight = 20;
    public double scoreAlpha = 1;
    public double scoreAngle;
    public double scoreScaleX = 1;
    public double scoreScaleY = 1;
    public int scoreColor = 0xFFFFFFFF;
    public boolean scoreVisible = true;
    public String scoreTextOverride;

    public PsychHudState(int canvasWidth, int canvasHeight, boolean downscroll) {
        barX = canvasWidth * 0.5 - barWidth * 0.5;
        barY = downscroll ? 28 : canvasHeight - 40;
        backgroundX = barX - 2;
        backgroundY = barY - 2;
        scoreX = 0;
        scoreY = barY + barHeight + 22;
        scoreWidth = canvasWidth;
    }

    public Object property(String path, double healthPercent, String generatedScore) {
        return switch (path) {
            case "healthBar.x" -> barX;
            case "healthBar.y" -> barY;
            case "healthBar.width" -> barWidth;
            case "healthBar.height" -> barHeight;
            case "healthBar.alpha" -> barAlpha;
            case "healthBar.visible" -> barVisible;
            case "healthBar.percent" -> healthPercent;
            case "healthBar.leftBar.color" -> opponentColor;
            case "healthBar.rightBar.color" -> playerColor;
            case "healthBarBG.x" -> backgroundX;
            case "healthBarBG.y" -> backgroundY;
            case "healthBarBG.width" -> backgroundWidth;
            case "healthBarBG.height" -> backgroundHeight;
            case "healthBarBG.alpha" -> backgroundAlpha;
            case "healthBarBG.visible" -> backgroundVisible;
            case "scoreTxt.x" -> scoreX;
            case "scoreTxt.y" -> scoreY;
            case "scoreTxt.width" -> scoreWidth;
            case "scoreTxt.height" -> scoreHeight;
            case "scoreTxt.alpha" -> scoreAlpha;
            case "scoreTxt.angle" -> scoreAngle;
            case "scoreTxt.scale.x" -> scoreScaleX;
            case "scoreTxt.scale.y" -> scoreScaleY;
            case "scoreTxt.color" -> scoreColor;
            case "scoreTxt.visible" -> scoreVisible;
            case "scoreTxt.text" -> scoreTextOverride == null ? generatedScore : scoreTextOverride;
            default -> null;
        };
    }

    public boolean setProperty(String path, Object value) {
        double number = number(value, 0);
        switch (path) {
            case "healthBar.x" -> moveBarX(number);
            case "healthBar.y" -> moveBarY(number);
            case "healthBar.width" -> barWidth = Math.max(1, number);
            case "healthBar.height" -> barHeight = Math.max(1, number);
            case "healthBar.alpha" -> barAlpha = clampAlpha(number);
            case "healthBar.visible" -> barVisible = bool(value);
            case "healthBar.leftBar.color" -> opponentColor = color(value);
            case "healthBar.rightBar.color" -> playerColor = color(value);
            case "healthBarBG.x" -> backgroundX = number;
            case "healthBarBG.y" -> backgroundY = number;
            case "healthBarBG.width" -> backgroundWidth = Math.max(1, number);
            case "healthBarBG.height" -> backgroundHeight = Math.max(1, number);
            case "healthBarBG.alpha" -> backgroundAlpha = clampAlpha(number);
            case "healthBarBG.visible" -> backgroundVisible = bool(value);
            case "scoreTxt.x" -> scoreX = number;
            case "scoreTxt.y" -> scoreY = number;
            case "scoreTxt.width" -> scoreWidth = Math.max(1, number);
            case "scoreTxt.height" -> scoreHeight = Math.max(1, number);
            case "scoreTxt.alpha" -> scoreAlpha = clampAlpha(number);
            case "scoreTxt.angle" -> scoreAngle = number;
            case "scoreTxt.scale.x" -> scoreScaleX = number;
            case "scoreTxt.scale.y" -> scoreScaleY = number;
            case "scoreTxt.color" -> scoreColor = color(value);
            case "scoreTxt.visible" -> scoreVisible = bool(value);
            case "scoreTxt.text" -> scoreTextOverride = String.valueOf(value);
            default -> { return false; }
        }
        return true;
    }

    private void moveBarX(double value) {
        double delta = value - barX;
        barX = value;
        backgroundX += delta;
    }

    private void moveBarY(double value) {
        double delta = value - barY;
        barY = value;
        backgroundY += delta;
        scoreY += delta;
    }

    private static double clampAlpha(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private static int color(Object value) {
        return value instanceof Number number ? number.intValue() : PsychColor.parse(String.valueOf(value));
    }

    private static double number(Object value, double fallback) {
        if (value instanceof Number number) return number.doubleValue();
        try { return Double.parseDouble(String.valueOf(value)); }
        catch (Exception ignored) { return fallback; }
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean bool) return bool;
        if (value instanceof Number number) return number.doubleValue() != 0;
        return Boolean.parseBoolean(String.valueOf(value));
    }
}
