package com.fnfmod.client.world;

import com.fnfmod.FnfMod;
import com.fnfmod.client.ClientOptions;
import com.fnfmod.world.ModWorldOptions;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Reads and writes a bundled mod world's {@code blockified-options.json}. The same
 * file carries the world controls ({@code allowCheats}, {@code saveOnExit},
 * {@code hideSettingsButton}, {@code allowExternalContent}) read by
 * {@link ModWorldOptions} and the gameplay setting overrides consumed by
 * {@link ClientOptions#applyWorldOverrides}.
 */
public final class WorldSettingsIO {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Gameplay/visual settings a world may force onto the player, mirroring
     * {@link ClientOptions} field names. Personal calibration (offsetMs), the player's
     * own skin choice, editor preferences, and mod/directory settings are deliberately
     * excluded — those stay under the player's control.
     */
    public static final List<String> GAMEPLAY_KEYS = List.of(
            "downscroll", "middlescroll", "ghostTapping", "botplay",
            "scrollSpeedMult", "constantScrollSpeed", "hudStyle",
            "noteSkin", "splashSkin", "hitsound", "hitsoundVolume",
            "noteColorsEnabled", "noteColorBase", "noteColorOutline",
            "playAs", "animationSet", "opponentAnimationSet",
            "playerIcon", "botIcon", "ratingX", "ratingY");

    private WorldSettingsIO() {}

    /** Current world settings JSON (empty object when none saved yet). */
    public static JsonObject read(Path worldRoot) {
        if (worldRoot == null) return new JsonObject();
        Path file = worldRoot.resolve(ModWorldOptions.FILE_NAME);
        if (!Files.isRegularFile(file)) return new JsonObject();
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file));
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (Exception error) {
            FnfMod.LOGGER.warn("Could not read world settings {}: {}", file, error.toString());
            return new JsonObject();
        }
    }

    /** Writes the world settings JSON, creating the world folder if needed. */
    public static boolean write(Path worldRoot, JsonObject json) {
        if (worldRoot == null || json == null) return false;
        try {
            Files.createDirectories(worldRoot);
            Files.writeString(worldRoot.resolve(ModWorldOptions.FILE_NAME), GSON.toJson(json));
            return true;
        } catch (Exception error) {
            FnfMod.LOGGER.warn("Could not write world settings for {}: {}", worldRoot, error.toString());
            return false;
        }
    }

    /** Whether any gameplay override key is present (settings are being forced). */
    public static boolean hasForcedGameplay(JsonObject json) {
        for (String key : GAMEPLAY_KEYS) {
            if (json.has(key)) return true;
        }
        return false;
    }

    /** Copies the player's current gameplay/visual settings into the world so they are forced. */
    public static void copyPlayerGameplay(JsonObject json) {
        JsonObject current = GSON.toJsonTree(ClientOptions.get()).getAsJsonObject();
        for (String key : GAMEPLAY_KEYS) {
            if (current.has(key)) json.add(key, current.get(key));
        }
    }

    /** Removes every forced gameplay override, restoring player control of them. */
    public static void clearForcedGameplay(JsonObject json) {
        for (String key : GAMEPLAY_KEYS) json.remove(key);
    }
}
