package com.fnfmod.song;

import com.fnfmod.FnfMod;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;

/** Psych-compatible metadata for one isolated mod directory. */
public record ModPackInfo(Path root, String name, String description, Path icon) {

    public static ModPackInfo read(Path directory) {
        Path normalized = directory.toAbsolutePath().normalize();
        String fallback = normalized.getFileName() == null ? normalized.toString()
                : normalized.getFileName().toString();
        if (fallback.equalsIgnoreCase("mods") && normalized.getParent() != null
                && normalized.getParent().getFileName() != null) {
            fallback = normalized.getParent().getFileName().toString();
        }
        String name = fallback;
        String description = "No description provided.";
        Path json = normalized.resolve("pack.json");
        if (Files.isRegularFile(json)) {
            try {
                JsonObject object = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
                if (object.has("pack") && object.get("pack").isJsonObject()) {
                    object = object.getAsJsonObject("pack");
                }
                name = text(object, "name", text(object, "title", fallback));
                description = text(object, "description", description);
            } catch (Exception error) {
                FnfMod.LOGGER.warn("Could not parse Psych pack metadata {}: {}", json, error.toString());
            }
        }
        Path icon = normalized.resolve("pack.png");
        return new ModPackInfo(normalized, name.isBlank() ? fallback : name,
                description.isBlank() ? "No description provided." : description,
                Files.isRegularFile(icon) ? icon : null);
    }

    private static String text(JsonObject object, String key, String fallback) {
        try {
            JsonElement value = object.get(key);
            return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
