package com.fnfmod.client.render;

import com.fnfmod.FnfMod;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.GsonBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Immutable, validated view of a note skin's optional skin.json. */
public record NoteSkinConfig(boolean rgb, Part note, Part receptor, Part sustain,
                             Part splash, Part holdCover) {
    public record Part(float scale, float alpha, float x, float y) {
        private static final Part DEFAULT = new Part(1f, 1f, 0f, 0f);
    }

    public static final NoteSkinConfig DEFAULT = new NoteSkinConfig(true,
            Part.DEFAULT, Part.DEFAULT, Part.DEFAULT, Part.DEFAULT, Part.DEFAULT);

    public static NoteSkinConfig load(Path skinDirectory) {
        return loadFile(skinDirectory.resolve("skin.json"));
    }

    /** Loads a note-skin config from a specific json file (flat skins name it &lt;skin&gt;.json). */
    public static NoteSkinConfig loadFile(Path file) {
        if (file == null || !Files.isRegularFile(file)) return DEFAULT;
        try {
            JsonObject json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            Part holdCover = part(json, "holdCoverScale", "holdCoverAlpha", "holdCoverX", "holdCoverY");
            // This exact transform was the old workaround for Blockified's incorrect
            // built-in hold-cover placement. It is now the neutral renderer baseline.
            if (near(holdCover.scale(), 1.7f) && near(holdCover.alpha(), 1f)
                    && near(holdCover.x(), 0f) && near(holdCover.y(), 35f)) {
                holdCover = Part.DEFAULT;
            }
            return new NoteSkinConfig(
                    bool(json, "rgb", true),
                    part(json, "noteScale", "noteAlpha", "noteX", "noteY"),
                    part(json, "receptorScale", "receptorAlpha", "receptorX", "receptorY"),
                    part(json, "holdWidthScale", "sustainAlpha", "sustainX", "sustainY"),
                    part(json, "splashScale", "splashAlpha", "splashX", "splashY"),
                    holdCover
            );
        } catch (Exception error) {
            FnfMod.LOGGER.warn("Bad note-skin json {}: {}", file, error.toString());
            return DEFAULT;
        }
    }

    /** Saves only Blockified's transform keys while preserving unrelated skin metadata. */
    public static void saveFile(Path file, NoteSkinConfig config) throws java.io.IOException {
        if (file == null || config == null) throw new java.io.IOException("No note-skin config target");
        JsonObject json = new JsonObject();
        if (Files.isRegularFile(file)) {
            try {
                var parsed = JsonParser.parseString(Files.readString(file));
                if (parsed.isJsonObject()) json = parsed.getAsJsonObject();
            } catch (Exception ignored) {}
        }
        json.addProperty("rgb", config.rgb());
        put(json, "note", config.note());
        put(json, "receptor", config.receptor());
        put(json, "sustain", config.sustain());
        put(json, "splash", config.splash());
        put(json, "holdCover", config.holdCover());
        Path parent = file.toAbsolutePath().normalize().getParent();
        if (parent == null) throw new java.io.IOException("Invalid note-skin config path");
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, file.getFileName().toString(), ".tmp");
        Files.writeString(temporary,
                new GsonBuilder().setPrettyPrinting().create().toJson(json) + System.lineSeparator());
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void put(JsonObject json, String prefix, Part part) {
        String scale = prefix.equals("sustain") ? "holdWidthScale" : prefix + "Scale";
        String alpha = prefix.equals("sustain") ? "sustainAlpha" : prefix + "Alpha";
        String x = prefix.equals("sustain") ? "sustainX" : prefix + "X";
        String y = prefix.equals("sustain") ? "sustainY" : prefix + "Y";
        json.addProperty(scale, part.scale());
        json.addProperty(alpha, part.alpha());
        json.addProperty(x, part.x());
        json.addProperty(y, part.y());
    }

    private static Part part(JsonObject json, String scale, String alpha, String x, String y) {
        return new Part(scale(json, scale), alpha(json, alpha), position(json, x), position(json, y));
    }

    private static float scale(JsonObject json, String key) {
        try {
            if (json.has(key) && json.get(key).isJsonPrimitive()) {
                float value = json.get(key).getAsFloat();
                if (value > 0.05f && value < 20f) return value;
            }
        } catch (Exception ignored) {}
        return 1f;
    }

    private static float alpha(JsonObject json, String key) {
        try {
            if (json.has(key) && json.get(key).isJsonPrimitive()) {
                return Math.max(0f, Math.min(1f, json.get(key).getAsFloat()));
            }
        } catch (Exception ignored) {}
        return 1f;
    }

    private static float position(JsonObject json, String key) {
        try {
            if (json.has(key) && json.get(key).isJsonPrimitive()) {
                float value = json.get(key).getAsFloat();
                if (Float.isFinite(value)) return Math.max(-4096f, Math.min(4096f, value));
            }
        } catch (Exception ignored) {}
        return 0f;
    }

    private static boolean bool(JsonObject json, String key, boolean fallback) {
        try {
            if (json.has(key) && json.get(key).isJsonPrimitive()) return json.get(key).getAsBoolean();
        } catch (Exception ignored) {}
        return fallback;
    }

    private static boolean near(float a, float b) {
        return Math.abs(a - b) < 0.0001f;
    }
}
