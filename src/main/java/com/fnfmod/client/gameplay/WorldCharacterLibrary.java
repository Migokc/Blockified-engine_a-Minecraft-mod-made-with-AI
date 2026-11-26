package com.fnfmod.client.gameplay;

import com.fnfmod.client.ClientOptions;
import com.fnfmod.song.SongLibrary;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/** Global picker/runtime lookup for Psych-style 2D character definitions. */
public final class WorldCharacterLibrary {
    public static final String PREFIX = "character:";
    private static final String[] CHARACTER_DIRS = {
            "characters", "shared/characters", "data/characters",
            "assets/characters", "assets/shared/characters", "assets/data/characters"
    };

    public record Entry(String id, Path json) {}

    private WorldCharacterLibrary() {}

    /**
     * Lists characters in Mods-settings priority order. The first same-named
     * definition wins, matching the rest of Blockified's external asset lookup.
     */
    public static List<Entry> list() {
        Map<String, Entry> found = new LinkedHashMap<>();
        for (Path root : SongLibrary.orderedPickerContentRoots(
                SongLibrary.ExternalContent.CHARACTERS)) {
            for (String relative : CHARACTER_DIRS) scan(root.resolve(relative), found);
        }
        return List.copyOf(found.values());
    }

    public static Path resolve(String id) {
        String wanted = key(id);
        if (wanted.isEmpty()) return null;
        for (Entry entry : list()) if (key(entry.id()).equals(wanted)) return entry.json();
        return null;
    }

    public static boolean isSelection(String value) {
        return value != null && value.regionMatches(true, 0, PREFIX, 0, PREFIX.length());
    }

    public static String selection(String id) {
        return PREFIX + (id == null ? "" : id.trim());
    }

    /** Runtime/network discriminator. It is deliberately never persisted in options.json. */
    public static String runtimeSelection(String id, String type) {
        return ClientOptions.PERFORMER_TYPE_2D.equals(ClientOptions.normalizePerformerType(type))
                ? selection(id) : id == null ? "" : id.trim();
    }

    public static String idFromSelection(String value) {
        return isSelection(value) ? value.substring(PREFIX.length()).trim() : "";
    }

    public static Path resolveSelection(String value) {
        return resolve(idFromSelection(value));
    }

    /** Psych/V-Slice camera-position pixels converted to Blockified world-camera units. */
    public static float[] cameraOffset(String selection) {
        return cameraOffset(selection, false);
    }

    public static float[] cameraOffset(String selection, boolean flipped) {
        Path json = isSelection(selection) ? resolveSelection(selection) : resolve(selection);
        return cameraOffset(json, flipped);
    }

    public static float[] cameraOffset(Path json) {
        return cameraOffset(json, false);
    }

    public static float[] cameraOffset(Path json, boolean flipped) {
        if (json == null) return new float[]{0, 0};
        try {
            JsonObject data = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
            String key = data.has("camera_position") ? "camera_position"
                    : data.has("cameraOffsets") ? "cameraOffsets" : "";
            if (key.isEmpty() || !data.get(key).isJsonArray()) return new float[]{0, 0};
            JsonArray pair = data.getAsJsonArray(key);
            if (pair.isEmpty()) return new float[]{0, 0};
            // Psych adds camera_position after finding the character midpoint.
            // Unlike the rendered sprite, this offset is not multiplied by the
            // character's JSON scale.
            float worldScale = (float) WorldCharacter.WORLD_RENDER_SCALE / 64f;
            float x = pair.get(0).getAsFloat() * worldScale;
            float y = pair.size() > 1 ? -pair.get(1).getAsFloat() * worldScale : 0;
            if (flipped) x = -x;
            return new float[]{x, y};
        } catch (Exception ignored) {
            return new float[]{0, 0};
        }
    }

    private static void scan(Path directory, Map<String, Entry> found) {
        if (!Files.isDirectory(directory)) return;
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                String name = file.getFileName().toString();
                if (!name.toLowerCase(Locale.ROOT).endsWith(".json")) continue;
                String id = name.substring(0, name.length() - 5);
                found.putIfAbsent(key(id), new Entry(id, file.toAbsolutePath().normalize()));
            }
        } catch (Exception ignored) {}
    }

    private static String key(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
