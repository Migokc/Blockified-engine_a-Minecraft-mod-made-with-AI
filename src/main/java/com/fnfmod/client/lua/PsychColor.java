package com.fnfmod.client.lua;

import java.util.Locale;
import java.util.Map;

/** Psych/Flixel-compatible color parsing shared by Lua graphics and native HUD properties. */
public final class PsychColor {
    private static final Map<String, Integer> NAMED = Map.ofEntries(
            Map.entry("white", 0xFFFFFFFF), Map.entry("black", 0xFF000000),
            Map.entry("red", 0xFFFF0000), Map.entry("green", 0xFF00FF00),
            Map.entry("blue", 0xFF0000FF), Map.entry("cyan", 0xFF00FFFF),
            Map.entry("magenta", 0xFFFF00FF), Map.entry("yellow", 0xFFFFFF00),
            Map.entry("gray", 0xFF808080), Map.entry("grey", 0xFF808080),
            Map.entry("orange", 0xFFFFA500), Map.entry("purple", 0xFF800080),
            Map.entry("pink", 0xFFFFC0CB), Map.entry("brown", 0xFFA52A2A),
            Map.entry("lime", 0xFF00FF00), Map.entry("navy", 0xFF000080),
            Map.entry("teal", 0xFF008080), Map.entry("transparent", 0x00000000)
    );

    private PsychColor() {}

    public static int parse(String raw) {
        String value = raw == null ? "FFFFFF" : raw.trim().toLowerCase(Locale.ROOT);
        Integer named = NAMED.get(value.replace(" ", ""));
        if (named != null) return named;
        if (value.startsWith("#")) value = value.substring(1);
        if (value.startsWith("0x")) value = value.substring(2);
        value = value.replace("_", "");
        try {
            long parsed = Long.parseUnsignedLong(value, 16);
            return (int) (value.length() <= 6 ? parsed | 0xFF000000L : parsed);
        } catch (Exception ignored) {
            return 0xFFFFFFFF;
        }
    }
}
