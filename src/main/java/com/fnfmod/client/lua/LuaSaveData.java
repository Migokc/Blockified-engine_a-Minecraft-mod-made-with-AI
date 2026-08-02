package com.fnfmod.client.lua;

import com.fnfmod.FnfMod;
import com.fnfmod.song.SongLibrary;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Psych Engine save-data and achievement persistence.
 *
 * <p>Psych stores these in FlxSave slots; Blockified writes one JSON file per
 * slot under {@code config/fnfmod/saves/}. Values stay in memory until the script
 * calls {@code flushSaveData}, matching FlxSave's explicit flush.
 *
 * <p>Slot and folder names are reduced to a safe file name, so a script cannot
 * reach outside the saves folder through its save name.
 */
public final class LuaSaveData {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String ACHIEVEMENTS = "achievements";

    /** Loaded slots, keyed by "folder/save". Cleared when the game closes. */
    private static final Map<String, JsonObject> SLOTS = new HashMap<>();

    private LuaSaveData() {}

    public static Path savesDir() {
        return SongLibrary.root().resolve("saves");
    }

    // ------------------------------------------------------------------ slots

    /** Psych's initSaveData: opens a slot, reading it from disk if it exists. */
    public static void init(String save, String folder) {
        slot(folder, save);
    }

    /** Psych's flushSaveData: writes the slot to disk. */
    public static void flush(String save, String folder) {
        write(key(folder, save), slot(folder, save));
    }

    /** Psych's eraseSaveData: clears the slot and removes its file. */
    public static void erase(String save, String folder) {
        String key = key(folder, save);
        SLOTS.put(key, new JsonObject());
        try {
            Files.deleteIfExists(file(key));
        } catch (Exception error) {
            FnfMod.LOGGER.warn("Could not erase Lua save {}: {}", key, error.toString());
        }
    }

    /** Reads one field, or null when the slot has no such field. */
    public static Object get(String save, String folder, String field) {
        JsonElement value = slot(folder, save).get(field);
        return value == null ? null : unwrap(value);
    }

    public static void set(String save, String folder, String field, Object value) {
        slot(folder, save).add(field, wrap(value));
    }

    // ---------------------------------------------------------- achievements

    /**
     * Achievements live in their own slot so they survive independently of a
     * mod's own save data, as they do in Psych.
     */
    public static boolean unlocked(String name) {
        JsonObject entry = achievement(name, false);
        return entry != null && entry.has("unlocked") && entry.get("unlocked").getAsBoolean();
    }

    public static boolean exists(String name) {
        return achievement(name, false) != null;
    }

    public static void unlock(String name) {
        achievement(name, true).addProperty("unlocked", true);
        flushAchievements();
    }

    public static double score(String name) {
        JsonObject entry = achievement(name, false);
        return entry == null || !entry.has("score") ? 0 : entry.get("score").getAsDouble();
    }

    /** Returns the stored score. {@code saveIfNotUnlocked} matches Psych's flag. */
    public static double setScore(String name, double value, boolean saveIfNotUnlocked) {
        if (!saveIfNotUnlocked && !unlocked(name)) return score(name);
        achievement(name, true).addProperty("score", value);
        flushAchievements();
        return value;
    }

    public static double addScore(String name, double value, boolean saveIfNotUnlocked) {
        return setScore(name, score(name) + value, saveIfNotUnlocked);
    }

    private static JsonObject achievement(String name, boolean create) {
        JsonObject slot = slot(ACHIEVEMENTS, ACHIEVEMENTS);
        JsonElement existing = slot.get(name);
        if (existing != null && existing.isJsonObject()) return existing.getAsJsonObject();
        if (!create) return null;
        JsonObject entry = new JsonObject();
        slot.add(name, entry);
        return entry;
    }

    private static void flushAchievements() {
        write(key(ACHIEVEMENTS, ACHIEVEMENTS), slot(ACHIEVEMENTS, ACHIEVEMENTS));
    }

    // ---------------------------------------------------------------- storage

    private static JsonObject slot(String folder, String save) {
        return SLOTS.computeIfAbsent(key(folder, save), LuaSaveData::read);
    }

    private static JsonObject read(String key) {
        Path file = file(key);
        if (!Files.isRegularFile(file)) return new JsonObject();
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file));
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (Exception error) {
            FnfMod.LOGGER.warn("Could not read Lua save {}: {}", key, error.toString());
            return new JsonObject();
        }
    }

    private static void write(String key, JsonObject data) {
        try {
            Path file = file(key);
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(data));
        } catch (Exception error) {
            FnfMod.LOGGER.warn("Could not write Lua save {}: {}", key, error.toString());
        }
    }

    private static Path file(String key) {
        return savesDir().resolve(key + ".json");
    }

    private static String key(String folder, String save) {
        String safeFolder = safe(folder, "psychenginemods");
        String safeSave = safe(save, "save");
        return safeFolder + "/" + safeSave;
    }

    /** Collapses anything that is not a plain name character, blocking path escapes. */
    private static String safe(String raw, String fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        String cleaned = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        return cleaned.isBlank() || cleaned.replace(".", "").isBlank() ? fallback : cleaned;
    }

    private static Object unwrap(JsonElement value) {
        if (!value.isJsonPrimitive()) return value.toString();
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isBoolean()) return primitive.getAsBoolean();
        if (primitive.isNumber()) return primitive.getAsDouble();
        return primitive.getAsString();
    }

    private static JsonElement wrap(Object value) {
        if (value instanceof Boolean b) return new JsonPrimitive(b);
        if (value instanceof Number n) return new JsonPrimitive(n);
        return new JsonPrimitive(String.valueOf(value));
    }
}
