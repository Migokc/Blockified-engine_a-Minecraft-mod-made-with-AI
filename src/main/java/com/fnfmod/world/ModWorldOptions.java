package com.fnfmod.world;

import com.fnfmod.FnfMod;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;

/** JSON-only runtime controls reserved for verified bundled mod worlds. */
public final class ModWorldOptions {

    /** File in a mod world's folder that carries every world-scoped control and override. */
    public static final String FILE_NAME = "blockified-options.json";
    /** Folder in a mod world that holds bundled BBS assets (models/textures) for sharing. */
    public static final String BUNDLED_ASSETS_DIR = "bbs-assets";

    private record State(boolean active, Boolean allowCheats, boolean saveOnExit,
                         boolean hideSettingsButton, boolean allowExternalContent,
                         boolean autoOpenMenu, String autoMenuProfile,
                         boolean forcePsychResolution) {}

    private static volatile State state = defaults();

    private ModWorldOptions() {}

    /** Reads special world controls after ModContentScope has verified the world owner. */
    public static synchronized void loadActiveWorld() {
        state = defaults();
        Path worldRoot = activeWorldRoot();
        if (worldRoot == null) return;

        Boolean allowCheats = null;
        boolean saveOnExit = true;
        boolean hideButton = false;
        boolean allowExternal = false;
        boolean autoOpenMenu = false;
        String autoMenuProfile = "";
        boolean forcePsychResolution = false;
        Path file = worldRoot.resolve(FILE_NAME);
        if (Files.isRegularFile(file)) {
            try {
                JsonObject json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                allowCheats = booleanValue(json.get("allowCheats"), "allowCheats", file, null);
                Boolean configuredSave = booleanValue(json.get("saveOnExit"), "saveOnExit", file, true);
                saveOnExit = configuredSave == null || configuredSave;
                hideButton = Boolean.TRUE.equals(booleanValue(json.get("hideSettingsButton"),
                        "hideSettingsButton", file, false));
                allowExternal = Boolean.TRUE.equals(booleanValue(json.get("allowExternalContent"),
                        "allowExternalContent", file, false));
                autoOpenMenu = Boolean.TRUE.equals(booleanValue(json.get("autoOpenMenu"),
                        "autoOpenMenu", file, false));
                autoMenuProfile = stringValue(json.get("autoMenuProfile"));
                forcePsychResolution = Boolean.TRUE.equals(booleanValue(json.get("forcePsychResolution"),
                        "forcePsychResolution", file, false));
            } catch (Exception error) {
                FnfMod.LOGGER.warn("Bad mod-world controls in {}: {}", file, error.toString());
            }
        }
        state = new State(true, allowCheats, saveOnExit, hideButton, allowExternal,
                autoOpenMenu, autoMenuProfile, forcePsychResolution);
        FnfMod.LOGGER.info("Mod world controls: allowCheats={}, saveOnExit={}, hideButton={}, "
                        + "allowExternal={}, autoMenu={}, profile={}, psychResolution={}",
                allowCheats == null ? "level.dat" : allowCheats, saveOnExit, hideButton,
                allowExternal, autoOpenMenu, autoMenuProfile, forcePsychResolution);
    }

    /** The active bundled mod world's folder, or null when not in a verified mod world. */
    public static Path activeWorldRoot() {
        return ModContentScope.activeMod()
                .filter(ignored -> ModContentScope.isModWorld())
                .map(ModContentScope.ActiveMod::worldRoot)
                .orElse(null);
    }

    /** The active mod world's bundled BBS asset folder (may not exist yet), or null. */
    public static Path bundledAssetsRoot() {
        Path worldRoot = activeWorldRoot();
        return worldRoot == null ? null : worldRoot.resolve(BUNDLED_ASSETS_DIR);
    }

    private static Boolean booleanValue(JsonElement value, String key, Path file, Boolean fallback) {
        if (value == null || value.isJsonNull()) return fallback;
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) return value.getAsBoolean();
        FnfMod.LOGGER.warn("Ignoring non-boolean {} in {}", key, file);
        return fallback;
    }

    private static String stringValue(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return "";
        String text = value.getAsString().trim().toLowerCase(java.util.Locale.ROOT);
        return text.length() > 128 ? text.substring(0, 128) : text;
    }

    private static State defaults() {
        return new State(false, null, true, false, false, false, "", false);
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

    /** Hides the in-menu World Settings button for this world (still editable by editing the JSON). */
    public static boolean hideSettingsButton() {
        return state.hideSettingsButton();
    }

    /**
     * When true, a bundled mod world may also use external directories and other installed
     * packs, not only its owning pack. Default false keeps a shared world self-contained.
     */
    public static boolean allowExternalContent() {
        State current = state;
        return current.active() && current.allowExternalContent();
    }

    /** Opens the configured pack menu as soon as a player enters this bundled world. */
    public static boolean autoOpenMenu() {
        State current = state;
        return current.active() && current.autoOpenMenu() && !current.autoMenuProfile().isBlank();
    }

    public static String autoMenuProfile() {
        return state.autoMenuProfile();
    }

    /** World-scoped override for Blockified's fixed 1280x720 logical canvas. */
    public static boolean forcePsychResolution() {
        State current = state;
        return current.active() && current.forcePsychResolution();
    }

    public static synchronized void clear() {
        state = defaults();
    }
}
