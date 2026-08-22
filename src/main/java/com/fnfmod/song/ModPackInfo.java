package com.fnfmod.song;

import com.fnfmod.FnfMod;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Psych or V-Slice/Polymod metadata for one isolated mod directory. */
public record ModPackInfo(Path root, String name, String description, Path icon,
                          String version, String license, List<Contributor> contributors) {
    public record Contributor(String name, String role, String url) {}

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
        String version = "";
        String license = "";
        List<Contributor> contributors = new ArrayList<>();
        Path json = normalized.resolve("pack.json");
        if (Files.isRegularFile(json)) {
            try {
                JsonObject object = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
                if (object.has("pack") && object.get("pack").isJsonObject()) {
                    object = object.getAsJsonObject("pack");
                }
                name = text(object, "name", text(object, "title", fallback));
                description = text(object, "description", description);
                version = text(object, "version", "");
            } catch (Exception error) {
                FnfMod.LOGGER.warn("Could not parse Psych pack metadata {}: {}", json, error.toString());
            }
        }
        // Friday Night Funkin' V-Slice packs use Polymod metadata instead of
        // Psych's pack.json. Prefer its fields when present, while retaining
        // pack.json as a compatible fallback for hybrid packs.
        Path polymod = normalized.resolve("_polymod_meta.json");
        if (Files.isRegularFile(polymod)) {
            try {
                JsonObject object = JsonParser.parseString(Files.readString(polymod)).getAsJsonObject();
                name = text(object, "title", text(object, "name", name));
                description = text(object, "description", description);
                version = text(object, "mod_version", text(object, "version", version));
                license = text(object, "license", license);
                if (object.has("contributors") && object.get("contributors").isJsonArray()) {
                    object.getAsJsonArray("contributors").forEach(value -> {
                        if (!value.isJsonObject()) return;
                        JsonObject contributor = value.getAsJsonObject();
                        String contributorName = text(contributor, "name", "");
                        if (!contributorName.isBlank()) contributors.add(new Contributor(
                                contributorName, text(contributor, "role", "Contributor"),
                                text(contributor, "url", "")));
                    });
                }
            } catch (Exception error) {
                FnfMod.LOGGER.warn("Could not parse V-Slice Polymod metadata {}: {}",
                        polymod, error.toString());
            }
        }
        Path icon = Files.isRegularFile(normalized.resolve("_polymod_icon.png"))
                ? normalized.resolve("_polymod_icon.png") : normalized.resolve("pack.png");
        return new ModPackInfo(normalized, name.isBlank() ? fallback : name,
                description.isBlank() ? "No description provided." : description,
                Files.isRegularFile(icon) ? icon : null, version, license,
                List.copyOf(contributors));
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
