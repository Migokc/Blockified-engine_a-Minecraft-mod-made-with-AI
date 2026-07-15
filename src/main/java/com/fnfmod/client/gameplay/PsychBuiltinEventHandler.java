package com.fnfmod.client.gameplay;

import com.fnfmod.chart.ChartEventTypes;
import com.fnfmod.chart.SongChart;
import com.fnfmod.gameplay.PlaybackMode;

import java.util.Locale;

/** Psych 1.0-compatible built-in event parsing, separate from screen mechanics. */
public final class PsychBuiltinEventHandler {
    public interface Host {
        PlaybackMode playbackMode();
        void eventHey(String target, double durationSeconds);
        void eventSetGirlfriendSpeed(int speed);
        void eventAddCameraZoom(float gameAmount, float hudAmount);
        void eventPlayAnimation(String target, String animation);
        void eventCameraFollow(Double x, Double y);
        void eventAltIdle(String target, String suffix);
        void eventScreenShake(double gameDuration, double gameIntensity,
                              double hudDuration, double hudIntensity);
        void eventChangeCharacter(String target, String characterId);
        void eventChangeScrollSpeed(double multiplier, double durationSeconds);
        void eventSetProperty(String property, Object value);
        void eventPlaySound(String sound, float volume);
    }

    public boolean execute(SongChart.Event event, Host host) {
        if (event == null || host == null) return false;
        String name = text(event.name);
        String value1 = text(event.value1);
        String value2 = text(event.value2);

        if (ChartEventTypes.is(name, ChartEventTypes.HEY)) {
            if (host.playbackMode() != PlaybackMode.FNF) return true;
            double duration = positive(value2, 0.6);
            String target = value1.toLowerCase(Locale.ROOT);
            if (target.equals("bf") || target.equals("boyfriend") || target.equals("0")) {
                host.eventHey("boyfriend", duration);
            } else if (target.equals("gf") || target.equals("girlfriend") || target.equals("1")) {
                host.eventHey("gf", duration);
            } else {
                host.eventHey("gf", duration);
                host.eventHey("boyfriend", duration);
            }
        } else if (ChartEventTypes.is(name, ChartEventTypes.SET_GF_SPEED)) {
            if (host.playbackMode() == PlaybackMode.FNF) {
                host.eventSetGirlfriendSpeed(Math.max(1, integer(value1, 1)));
            }
        } else if (ChartEventTypes.is(name, ChartEventTypes.ADD_CAMERA_ZOOM)) {
            host.eventAddCameraZoom((float) number(value1, 0.015), (float) number(value2, 0.03));
        } else if (ChartEventTypes.is(name, ChartEventTypes.PLAY_ANIMATION)) {
            host.eventPlayAnimation(animationTarget(value2), value1);
        } else if (ChartEventTypes.is(name, ChartEventTypes.CAMERA_FOLLOW_POS)) {
            host.eventCameraFollow(optionalNumber(value1), optionalNumber(value2));
        } else if (ChartEventTypes.is(name, ChartEventTypes.ALT_IDLE_ANIMATION)) {
            host.eventAltIdle(characterTarget(value1), value2);
        } else if (ChartEventTypes.is(name, ChartEventTypes.SCREEN_SHAKE)) {
            double[] game = pair(value1);
            double[] hud = pair(value2);
            host.eventScreenShake(game[0], game[1], hud[0], hud[1]);
        } else if (ChartEventTypes.is(name, ChartEventTypes.CHANGE_CHARACTER)) {
            host.eventChangeCharacter(characterTarget(value1), value2);
        } else if (ChartEventTypes.is(name, ChartEventTypes.CHANGE_SCROLL_SPEED)) {
            host.eventChangeScrollSpeed(number(value1, 1), Math.max(0, number(value2, 0)));
        } else if (ChartEventTypes.is(name, ChartEventTypes.SET_PROPERTY)) {
            host.eventSetProperty(value1, parsedValue(value2));
        } else if (ChartEventTypes.is(name, ChartEventTypes.PLAY_SOUND)) {
            host.eventPlaySound(value1, (float) number(value2, 1));
        } else {
            return false;
        }
        return true;
    }

    private static String animationTarget(String raw) {
        String value = raw.toLowerCase(Locale.ROOT);
        if (value.equals("bf") || value.equals("boyfriend") || value.equals("1")) return "boyfriend";
        if (value.equals("gf") || value.equals("girlfriend") || value.equals("2")) return "gf";
        return "dad";
    }

    private static String characterTarget(String raw) {
        String value = raw.toLowerCase(Locale.ROOT);
        if (value.equals("gf") || value.equals("girlfriend") || value.equals("2")) return "gf";
        if (value.equals("dad") || value.equals("opponent") || value.equals("1")) return "dad";
        return "boyfriend";
    }

    private static double[] pair(String raw) {
        String[] parts = raw.split(",", -1);
        return new double[]{parts.length > 0 ? number(parts[0], 0) : 0,
                parts.length > 1 ? number(parts[1], 0) : 0};
    }

    private static Object parsedValue(String raw) {
        if (raw.equalsIgnoreCase("true")) return true;
        if (raw.equalsIgnoreCase("false")) return false;
        try { return Double.parseDouble(raw); }
        catch (NumberFormatException ignored) { return raw; }
    }

    private static String text(String value) { return value == null ? "" : value.trim(); }
    private static int integer(String raw, int fallback) {
        try { return Integer.parseInt(raw); }
        catch (NumberFormatException ignored) { return fallback; }
    }
    private static double number(String raw, double fallback) {
        try {
            double value = Double.parseDouble(raw);
            return Double.isFinite(value) ? value : fallback;
        } catch (NumberFormatException ignored) { return fallback; }
    }
    private static double positive(String raw, double fallback) {
        double value = number(raw, fallback);
        return value > 0 ? value : fallback;
    }
    private static Double optionalNumber(String raw) {
        if (raw.isBlank()) return null;
        try {
            double value = Double.parseDouble(raw);
            return Double.isFinite(value) ? value : null;
        } catch (NumberFormatException ignored) { return null; }
    }
}
