package com.fnfmod.client.render;

import com.fnfmod.FnfMod;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;

/** Immutable, validated view of a note skin's optional skin.json. */
public record NoteSkinConfig(Part note, Part receptor, Part sustain, Part splash, Part holdCover) {
    public record Part(float scale, float alpha, float x, float y) {
        private static final Part DEFAULT = new Part(1f, 1f, 0f, 0f);
    }

    public static final NoteSkinConfig DEFAULT = new NoteSkinConfig(
            Part.DEFAULT, Part.DEFAULT, Part.DEFAULT, Part.DEFAULT, Part.DEFAULT);

    public static NoteSkinConfig load(Path skinDirectory) {
        Path file = skinDirectory.resolve("skin.json");
        if (!Files.isRegularFile(file)) return DEFAULT;
        try {
            JsonObject json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            return new NoteSkinConfig(
                    part(json, "noteScale", "noteAlpha", "noteX", "noteY"),
                    part(json, "receptorScale", "receptorAlpha", "receptorX", "receptorY"),
                    part(json, "holdWidthScale", "sustainAlpha", "sustainX", "sustainY"),
                    part(json, "splashScale", "splashAlpha", "splashX", "splashY"),
                    part(json, "holdCoverScale", "holdCoverAlpha", "holdCoverX", "holdCoverY")
            );
        } catch (Exception error) {
            FnfMod.LOGGER.warn("Bad skin.json in {}: {}", skinDirectory, error.toString());
            return DEFAULT;
        }
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
}
