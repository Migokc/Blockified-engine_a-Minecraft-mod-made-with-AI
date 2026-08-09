package com.fnfmod.world;

import com.fnfmod.FnfMod;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;

/** JSON-only runtime controls reserved for verified bundled mod worlds. */
public final class ModWorldOptions {

    private record State(boolean active, Boolean allowCheats, boolean saveOnExit) {}

    private static volatile State state = new State(false, null, true);

    private ModWorldOptions() {}

    /** Reads special world controls after ModContentScope has verified the world owner. */
    public static synchronized void loadActiveWorld() {
        state = new State(false, null, true);
        Path worldRoot = ModContentScope.activeMod()
                .filter(ignored -> ModContentScope.isModWorld())
                .map(ModContentScope.ActiveMod::worldRoot)
                .orElse(null);
        if (worldRoot == null) return;

        Boolean allowCheats = null;
        boolean saveOnExit = true;
        Path file = worldRoot.resolve("blockified-options.json");
        if (Files.isRegularFile(file)) {
            try {
                JsonObject json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                allowCheats = booleanValue(json.get("allowCheats"), "allowCheats", file, null);
                Boolean configuredSave = booleanValue(json.get("saveOnExit"), "saveOnExit", file, true);
                saveOnExit = configuredSave == null || configuredSave;
            } catch (Exception error) {
                FnfMod.LOGGER.warn("Bad mod-world controls in {}: {}", file, error.toString());
            }
        }
        state = new State(true, allowCheats, saveOnExit);
        FnfMod.LOGGER.info("Mod world controls: allowCheats={}, saveOnExit={}",
                allowCheats == null ? "level.dat" : allowCheats, saveOnExit);
    }

    private static Boolean booleanValue(JsonElement value, String key, Path file, Boolean fallback) {
        if (value == null || value.isJsonNull()) return fallback;
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) return value.getAsBoolean();
        FnfMod.LOGGER.warn("Ignoring non-boolean {} in {}", key, file);
        return fallback;
    }

    public static boolean hasCheatOverride() {
        State current = state;
        return current.active() && current.allowCheats() != null;
    }

    public static boolean allowCheats() {
        State current = state;
        return current.active() && Boolean.TRUE.equals(current.allowCheats());
    }

    /** True only for a verified mod world that explicitly disabled persistence. */
    public static boolean preventSaving() {
        State current = state;
        return current.active() && !current.saveOnExit();
    }

    public static synchronized void clear() {
        state = new State(false, null, true);
    }
}
