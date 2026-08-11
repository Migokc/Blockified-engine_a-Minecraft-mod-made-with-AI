package com.fnfmod.client.anim;

import com.fnfmod.FnfMod;
import com.fnfmod.character.CharacterDefinitionPaths;
import com.fnfmod.song.SongLibrary;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.world.entity.player.Player;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * BBS FS character integration with selectable forms and FNF action-state maps.
 *
 * BBS FS runs as a Fabric mod through Sinytra Connector. Blockified deliberately
 * talks to it through {@link BbsFsAnimationBridge}; no Yarn/Fabric classes leak
 * into the native NeoForge source tree.
 *
 * New definitions are named JSON files such as animations/bf.json and optional
 * animations/bf-opp.json. The old folder layout remains readable. The mapping
 * values now name BBS animation-state IDs instead of playerAnimator files:
 * {
 *   "bbsForm": "My BF form",
 *   "icon": "bf",
 *   "cameraOffset": [0.0, 0.0],
 *   "animations": {
 *     "idle": "idle",
 *     "left": { "anim": "singLEFT", "cameraOffset": [-1.0, 0.0] },
 *     "down": "singDOWN",
 *     "up": "singUP",
 *     "right": "singRIGHT",
 *     "miss": "miss",
 *     "hey": "hey",
 *     "attack": { "state": "sword-swing", "cameraOffset": [1.0, -0.5] }
 *   }
 * }
 *
 * Animation keys are not limited to Blockified's conventional action slots. Any
 * user-defined key can be invoked by Play Animation or characterPlayAnim; its
 * value selects the real BBS form state and may carry a camera offset.
 *
 * Settings expose only definitions from config/fnfmod/animations. Definitions
 * inside config/fnfmod/mods/<mod>/animations are private to that mod and are
 * selected by its chart character IDs and Change Character events.
 */
public final class CharacterAnimations {

    public static final String[] ACTIONS = {"idle", "idle2", "left", "down", "up", "right", "miss", "hey"};
    public static final String NONE_SET = CharacterDefinitionPaths.NONE;
    public static final String DEFAULT_SET = CharacterDefinitionPaths.DEFAULT;
    private static final String ASSET_PATH_FILE = "assets_path.txt";

    public static final class AnimEntry {
        public String state = "";
        public float camX, camY;
    }

    private static final class AnimSet {
        final Map<String, AnimEntry> actions = new HashMap<>();
        float baseCamX, baseCamY;
        boolean hasBaseCamera;
        String icon = "";
        String vocalsFile = "";
        int color = -1;
        String bbsForm = "";
        float rotation;
        boolean hasRotation;
        float opponentBaseCamX, opponentBaseCamY;
        boolean hasOpponentBaseCamera;
        String opponentIcon = "";
        String opponentVocalsFile = "";
        int opponentColor = -1;
        String opponentBbsForm = "";
        float opponentRotation;
        boolean hasOpponentRotation;
        // Bundled BBS form JSON (self-contained model + texture) if the folder ships one.
        java.nio.file.Path formFile;
        java.nio.file.Path opponentFormFile;
        // When set, the idle is not re-triggered every beat; the BBS animation's
        // own loop carries it, so the idle plays continuously instead of bopping.
        boolean loopIdle, hasLoopIdle;
        boolean opponentLoopIdle, hasOpponentLoopIdle;

        AnimEntry resolve(String role, String action) {
            String normalized = actionKey(action);
            AnimEntry entry = actions.get(role + "." + normalized);
            return entry == null ? actions.get(normalized) : entry;
        }

        String form(String role) {
            return "opponent".equals(role) && !opponentBbsForm.isBlank()
                    ? opponentBbsForm : bbsForm;
        }

        java.nio.file.Path bundledForm(String role) {
            return "opponent".equals(role) && opponentFormFile != null ? opponentFormFile : formFile;
        }

        boolean loopIdle(String role) {
            return "opponent".equals(role) && hasOpponentLoopIdle ? opponentLoopIdle : loopIdle;
        }

        AnimSet copy() {
            AnimSet copy = new AnimSet();
            copy.actions.putAll(actions);
            copy.baseCamX = baseCamX;
            copy.baseCamY = baseCamY;
            copy.hasBaseCamera = hasBaseCamera;
            copy.icon = icon;
            copy.vocalsFile = vocalsFile;
            copy.color = color;
            copy.bbsForm = bbsForm;
            copy.rotation = rotation;
            copy.hasRotation = hasRotation;
            copy.opponentBaseCamX = opponentBaseCamX;
            copy.opponentBaseCamY = opponentBaseCamY;
            copy.hasOpponentBaseCamera = hasOpponentBaseCamera;
            copy.opponentIcon = opponentIcon;
            copy.opponentVocalsFile = opponentVocalsFile;
            copy.opponentColor = opponentColor;
            copy.opponentBbsForm = opponentBbsForm;
            copy.opponentRotation = opponentRotation;
            copy.hasOpponentRotation = hasOpponentRotation;
            copy.formFile = formFile;
            copy.opponentFormFile = opponentFormFile;
            copy.loopIdle = loopIdle;
            copy.hasLoopIdle = hasLoopIdle;
            copy.opponentLoopIdle = opponentLoopIdle;
            copy.hasOpponentLoopIdle = hasOpponentLoopIdle;
            return copy;
        }

        void overlay(AnimSet higherPriority) {
            if (higherPriority == null) return;
            actions.putAll(higherPriority.actions);
            if (higherPriority.formFile != null) formFile = higherPriority.formFile;
            if (higherPriority.opponentFormFile != null) opponentFormFile = higherPriority.opponentFormFile;
            if (higherPriority.hasLoopIdle) {
                loopIdle = higherPriority.loopIdle;
                hasLoopIdle = true;
            }
            if (higherPriority.hasOpponentLoopIdle) {
                opponentLoopIdle = higherPriority.opponentLoopIdle;
                hasOpponentLoopIdle = true;
            }
            if (higherPriority.hasBaseCamera) {
                baseCamX = higherPriority.baseCamX;
                baseCamY = higherPriority.baseCamY;
                hasBaseCamera = true;
            }
            if (!higherPriority.icon.isBlank()) icon = higherPriority.icon;
            if (!higherPriority.vocalsFile.isBlank()) vocalsFile = higherPriority.vocalsFile;
            if (higherPriority.color >= 0) color = higherPriority.color;
            if (!higherPriority.bbsForm.isBlank()) bbsForm = higherPriority.bbsForm;
            if (higherPriority.hasRotation) {
                rotation = higherPriority.rotation;
                hasRotation = true;
            }
            if (higherPriority.hasOpponentBaseCamera) {
                opponentBaseCamX = higherPriority.opponentBaseCamX;
                opponentBaseCamY = higherPriority.opponentBaseCamY;
                hasOpponentBaseCamera = true;
            }
            if (!higherPriority.opponentIcon.isBlank()) opponentIcon = higherPriority.opponentIcon;
            if (!higherPriority.opponentVocalsFile.isBlank()) opponentVocalsFile = higherPriority.opponentVocalsFile;
            if (higherPriority.opponentColor >= 0) opponentColor = higherPriority.opponentColor;
            if (!higherPriority.opponentBbsForm.isBlank()) opponentBbsForm = higherPriority.opponentBbsForm;
            if (higherPriority.hasOpponentRotation) {
                opponentRotation = higherPriority.opponentRotation;
                hasOpponentRotation = true;
            }
        }
    }

    private static final Map<String, AnimSet> GLOBAL_SETS = new LinkedHashMap<>();
    private static final Map<String, AnimSet> SONG_SETS = new LinkedHashMap<>();
    private static Path activeSongFolder;
    private static String activePlayerCharacter = "bf";
    private static String activeOpponentCharacter = "dad";
    private static boolean available;

    private CharacterAnimations() {}

    public static void init() {
        available = BbsFsAnimationBridge.init();
        reload();
    }

    public static synchronized void reload() {
        GLOBAL_SETS.clear();
        GLOBAL_SETS.put(DEFAULT_SET, loadGlobalSet(DEFAULT_SET));
        for (String name : CharacterDefinitionPaths.selectableGlobalNames()) {
            GLOBAL_SETS.put(key(name), loadGlobalSet(name));
        }

        reloadSongSets();
        int total = GLOBAL_SETS.values().stream().mapToInt(set -> set.actions.size()).sum()
                + SONG_SETS.values().stream().mapToInt(set -> set.actions.size()).sum();
        FnfMod.LOGGER.info("Loaded {} global and {} song BBS character set(s) ({} mapped states)",
                GLOBAL_SETS.size(), SONG_SETS.size(), total);
    }

    public static synchronized void useSongFolder(Path songFolder) {
        useSongFolder(songFolder, "bf", "dad");
    }

    public static synchronized void useSongFolder(Path songFolder, String playerCharacter,
                                                  String opponentCharacter) {
        activeSongFolder = songFolder == null ? null : songFolder.toAbsolutePath().normalize();
        activePlayerCharacter = key(playerCharacter);
        activeOpponentCharacter = key(opponentCharacter);
        reloadSongSets();
    }

    public static synchronized List<String> listSets() {
        List<String> names = new ArrayList<>();
        names.add(NONE_SET);
        names.add(DEFAULT_SET);
        for (String name : CharacterDefinitionPaths.selectableGlobalNames()) {
            if (GLOBAL_SETS.containsKey(key(name))) names.add(name);
        }
        return names;
    }

    /** Internal name used by mod events; never appears in settings. */
    public static String modSet(String name) {
        return CharacterDefinitionPaths.modScoped(name);
    }

    /** Mod-local by default; an explicit global: prefix selects user config. */
    public static String runtimeSet(String name) {
        String clean = name == null ? "" : name.trim();
        if (clean.regionMatches(true, 0, "global:", 0, 7)) {
            return CharacterDefinitionPaths.normalizeName(clean.substring(7));
        }
        return modSet(clean);
    }

    /** BBS form names exposed to the character editor. */
    public static synchronized List<String> listBbsForms() {
        return BbsFsAnimationBridge.listForms();
    }

    /** Plays unsaved editor data directly without requiring a character.json reload. */
    public static synchronized boolean preview(Player player, String form, String state) {
        return available && BbsFsAnimationBridge.play(player, form, state);
    }

    /**
     * Editor preview that prefers the definition's bundled form when present.
     * This lets a loaded self-contained character work even when its form is not
     * installed in BBS's normal config folder.
     */
    public static synchronized boolean preview(Player player, String form,
                                               Path definitionFile, String state) {
        if (!available) return false;
        Path bundled = bundledFormFor(definitionFile);
        if (bundled != null) BbsFsAnimationBridge.registerAssetPack(bundled.getParent());
        return BbsFsAnimationBridge.play(player, form, bundled, state);
    }

    /** Loads/applies an editor character's bundled form before a state is previewed. */
    public static synchronized boolean preparePreview(Player player, String form, Path definitionFile) {
        if (!available) return false;
        Path bundled = bundledFormFor(definitionFile);
        if (bundled != null) BbsFsAnimationBridge.registerAssetPack(bundled.getParent());
        return BbsFsAnimationBridge.prepare(player, form, bundled);
    }

    /** Restores the form worn before a character preview or song. */
    public static void stopPreview() {
        BbsFsAnimationBridge.restoreAll();
    }

    public static synchronized boolean hasAction(String setName, String action) {
        return hasAction(setName, "player", action);
    }

    public static synchronized boolean hasAction(String setName, String role, String action) {
        AnimSet set = resolveSet(setName);
        return set != null && set.resolve(role, action) != null;
    }

    public static synchronized boolean isDisabled(String setName) {
        return NONE_SET.equals(key(setName));
    }

    public static synchronized String icon(String setName) {
        return icon(setName, "player");
    }

    public static synchronized String icon(String setName, String role) {
        AnimSet set = resolveSet(setName);
        if (set == null) return "";
        return "opponent".equals(role) && !set.opponentIcon.isBlank()
                ? set.opponentIcon : set.icon;
    }

    public static synchronized int color(String setName, String role) {
        AnimSet set = resolveSet(setName);
        if (set == null) return -1;
        return "opponent".equals(role) && set.opponentColor >= 0 ? set.opponentColor : set.color;
    }

    public static synchronized String vocalsFile(String setName, String role) {
        AnimSet set = resolveSet(setName);
        if (set == null) return "";
        return "opponent".equals(role) && !set.opponentVocalsFile.isBlank()
                ? set.opponentVocalsFile : set.vocalsFile;
    }

    private static void reloadSongSets() {
        SONG_SETS.clear();
        if (activeSongFolder == null || !Files.isDirectory(activeSongFolder)) return;

        AnimSet combined = GLOBAL_SETS.getOrDefault(DEFAULT_SET, new AnimSet()).copy();
        overlayFile(combined, CharacterDefinitionPaths.modCharacterJson(
                activeSongFolder, DEFAULT_SET, false), false);
        overlayFile(combined, CharacterDefinitionPaths.modCharacterJsonExact(
                activeSongFolder, DEFAULT_SET, true), true);
        overlayFile(combined, CharacterDefinitionPaths.modCharacterJson(
                activeSongFolder, activePlayerCharacter, false), false);
        overlayFile(combined, CharacterDefinitionPaths.modCharacterJson(
                activeSongFolder, activeOpponentCharacter, false), true);
        overlayFile(combined, CharacterDefinitionPaths.modCharacterJsonExact(
                activeSongFolder, activeOpponentCharacter, true), true);
        SONG_SETS.put(DEFAULT_SET, combined);

        loadSongNamedSets(activeSongFolder.resolve("animations"));
    }

    private static void loadSongNamedSets(Path parent) {
        if (!Files.isDirectory(parent)) return;
        try (Stream<Path> entries = Files.list(parent)) {
            for (Path entry : entries.sorted().toList()) {
                String filename = entry.getFileName().toString();
                String lower = filename.toLowerCase(java.util.Locale.ROOT);
                String name;
                if (Files.isRegularFile(entry) && lower.endsWith(".json")
                        && !lower.endsWith("-opp.json") && !lower.equals("character.json")
                        && !lower.equals("character-opp.json") && !lower.equals("mapping.json")) {
                    name = key(filename.substring(0, filename.length() - 5));
                } else if (Files.isDirectory(entry) && Files.isRegularFile(entry.resolve("character.json"))) {
                    name = key(filename);
                } else continue;
                if (DEFAULT_SET.equals(name)) continue;
                AnimSet local = loadModSet(name);
                SONG_SETS.put(name, local);
            }
        } catch (Exception error) {
            FnfMod.LOGGER.warn("Failed to scan song BBS character sets in {}: {}", parent, error.toString());
        }
    }

    /** The animation names a definition maps (role-agnostic), for the editor's anim list. */
    public static synchronized List<String> actionNames(String setName, String role) {
        AnimSet set = resolveSet(setName);
        if (set == null) return List.of();
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        for (String actionKey : set.actions.keySet()) {
            names.add(actionKey.startsWith("opponent.") ? actionKey.substring("opponent.".length()) : actionKey);
        }
        return new ArrayList<>(names);
    }

    private static AnimSet resolveSet(String setName) {
        boolean modScoped = CharacterDefinitionPaths.isModScoped(setName);
        String selected = CharacterDefinitionPaths.unscopedName(setName);
        if (NONE_SET.equals(selected)) return null;
        if (modScoped || DEFAULT_SET.equals(selected)) {
            AnimSet song = SONG_SETS.get(selected);
            if (song != null) return song;
            return GLOBAL_SETS.get(DEFAULT_SET);
        }
        AnimSet global = GLOBAL_SETS.get(selected);
        if (global != null) return global;
        return SONG_SETS.getOrDefault(DEFAULT_SET, GLOBAL_SETS.get(DEFAULT_SET));
    }

    private static String key(String name) {
        return CharacterDefinitionPaths.normalizeName(name);
    }

    private static AnimSet loadGlobalSet(String name) {
        AnimSet set = loadFiles(CharacterDefinitionPaths.globalCharacterJson(name, false),
                CharacterDefinitionPaths.globalCharacterJsonExact(name, true));
        if (DEFAULT_SET.equals(key(name))) {
            JsonObject legacy = readObject(SongLibrary.animationsDir().resolve("mapping.json"));
            if (legacy != null) applyLegacyMapping(set, legacy);
        }
        return set;
    }

    private static AnimSet loadModSet(String name) {
        return loadFiles(CharacterDefinitionPaths.modCharacterJson(activeSongFolder, name, false),
                CharacterDefinitionPaths.modCharacterJsonExact(activeSongFolder, name, true));
    }

    private static AnimSet loadFiles(Path player, Path opponent) {
        AnimSet set = new AnimSet();
        overlayFile(set, player, false);
        overlayFile(set, opponent, true);
        return set;
    }

    private static void overlayFile(AnimSet set, Path file, boolean opponent) {
        if (file == null) return;
        applyCharacterDefinition(set, readObject(file), opponent);
        Path bundled = bundledFormFor(file);
        if (bundled != null) {
            if (opponent) set.opponentFormFile = bundled;
            else set.formFile = bundled;
            // BBS checks registered packs in insertion order. Keep the existing
            // per-definition folder first, then use the shared tree as fallback.
            BbsFsAnimationBridge.registerAssetPack(bundled.getParent());
            Path sharedAssets = sharedAssetFolder(file);
            if (sharedAssets != null) BbsFsAnimationBridge.registerAssetPack(sharedAssets);
        }
    }

    /**
     * Reads an optional {@code assets_path.txt} stored inside an old-style
     * {@code animations/<name>/} definition. Its first non-comment line is a
     * Lua-style path relative to the owning mod root (for example
     * {@code animations/shared_assets}). Flat definitions may use the unambiguous
     * sibling form {@code <name>.assets_path.txt}.
     */
    private static Path sharedAssetFolder(Path defFile) {
        Path pointer = assetPathFileFor(defFile);
        if (pointer == null || !Files.isRegularFile(pointer)) return null;

        try {
            String value = Files.readAllLines(pointer).stream()
                    .map(line -> line.replace("\uFEFF", "").trim())
                    .filter(line -> !line.isEmpty())
                    .filter(line -> !line.startsWith("#") && !line.startsWith("//") && !line.startsWith("--"))
                    .findFirst()
                    .orElse("");
            if (value.isEmpty()) {
                FnfMod.LOGGER.warn("Animation asset path file {} is empty", pointer);
                return null;
            }

            Path relative = Path.of(value.replace('\\', '/'));
            if (relative.isAbsolute()) {
                FnfMod.LOGGER.warn("Animation asset path in {} must be relative to its mod root: {}", pointer, value);
                return null;
            }

            Path owner = animationOwnerRoot(defFile);
            if (owner == null) {
                FnfMod.LOGGER.warn("Could not determine the mod root for animation asset path {}", pointer);
                return null;
            }
            Path realOwner = owner.toRealPath();
            Path resolved = owner.resolve(relative).normalize();
            if (!Files.isDirectory(resolved)) {
                FnfMod.LOGGER.warn("Animation asset folder from {} does not exist: {}", pointer, resolved);
                return null;
            }
            Path realResolved = resolved.toRealPath();
            if (!realResolved.startsWith(realOwner)) {
                FnfMod.LOGGER.warn("Animation asset path in {} leaves its mod root: {}", pointer, value);
                return null;
            }
            return realResolved;
        } catch (Exception error) {
            FnfMod.LOGGER.warn("Bad animation asset path file {}: {}", pointer, error.toString());
            return null;
        }
    }

    private static Path assetPathFileFor(Path defFile) {
        if (defFile == null || defFile.getParent() == null) return null;
        String fileName = defFile.getFileName().toString();
        if (fileName.equalsIgnoreCase("character.json") || fileName.equalsIgnoreCase("character-opp.json")) {
            return defFile.getParent().resolve(ASSET_PATH_FILE);
        }
        String base = fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".json")
                ? fileName.substring(0, fileName.length() - 5) : fileName;
        if (base.toLowerCase(java.util.Locale.ROOT).endsWith("-opp")) {
            base = base.substring(0, base.length() - 4);
        }
        return defFile.getParent().resolve(base + ".assets_path.txt");
    }

    private static Path animationOwnerRoot(Path defFile) {
        Path normalized = defFile.toAbsolutePath().normalize();
        if (activeSongFolder != null) {
            Path modRoot = activeSongFolder.toAbsolutePath().normalize();
            if (normalized.startsWith(modRoot.resolve("animations"))) return modRoot;
        }
        Path globalAnimations = SongLibrary.animationsDir().toAbsolutePath().normalize();
        return normalized.startsWith(globalAnimations) ? globalAnimations.getParent() : null;
    }

    /** A sibling {@code <name>.form.json} or folder {@code form.json} bundles the model + texture. */
    private static Path bundledFormFor(Path defFile) {
        if (defFile == null) return null;
        Path parent = defFile.getParent();
        if (parent == null) return null;
        String name = defFile.getFileName().toString();
        String base = name.toLowerCase(java.util.Locale.ROOT).endsWith(".json")
                ? name.substring(0, name.length() - 5) : name;
        Path sibling = parent.resolve(base + ".form.json");
        if (Files.isRegularFile(sibling)) return sibling;
        Path folderForm = parent.resolve("form.json");
        return Files.isRegularFile(folderForm) ? folderForm : null;
    }

    private static JsonObject readObject(Path file) {
        if (!Files.isRegularFile(file)) return null;
        try {
            return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        } catch (Exception error) {
            FnfMod.LOGGER.warn("Bad {}: {}", file, error.toString());
            return null;
        }
    }

    private static void applyLegacyMapping(AnimSet set, JsonObject mapping) {
        for (String role : new String[]{"player", "opponent"}) {
            JsonObject roleMap = mapping.has(role) && mapping.get(role).isJsonObject()
                    ? mapping.getAsJsonObject(role) : null;
            if (roleMap == null) continue;
            for (String action : ACTIONS) {
                if (!roleMap.has(action)) continue;
                AnimEntry entry = new AnimEntry();
                entry.state = roleMap.get(action).getAsString();
                if (!entry.state.isBlank()) set.actions.put(role + "." + actionKey(action), entry);
            }
        }
    }

    private static void applyCharacterDefinition(AnimSet set, JsonObject json, boolean opponent) {
        if (json == null) return;

        if (json.has("cameraOffset")) {
            float[] offset = readVec2(json.get("cameraOffset"));
            if (opponent) {
                set.opponentBaseCamX = offset[0];
                set.opponentBaseCamY = offset[1];
                set.hasOpponentBaseCamera = true;
            } else {
                set.baseCamX = offset[0];
                set.baseCamY = offset[1];
                set.hasBaseCamera = true;
            }
        }

        String icon = readString(json, "icon");
        if (opponent) set.opponentIcon = icon;
        else set.icon = icon;

        String vocalsFile = readFirstString(json, "vocals_file", "vocalsFile", "vocal_file", "vocalFile",
                "vocals_prefix", "vocalsPrefix", "vocal_prefix", "vocalPrefix");
        if (opponent) set.opponentVocalsFile = vocalsFile;
        else set.vocalsFile = vocalsFile;

        int color = readColor(json);
        if (opponent) set.opponentColor = color;
        else set.color = color;

        String form = readString(json, "bbsForm");
        if (form.isBlank()) form = readString(json, "form");
        if (opponent) set.opponentBbsForm = form;
        else set.bbsForm = form;

        if (json.has("rotation")) {
            float rotation = readFloat(json.get("rotation"));
            if (opponent) {
                set.opponentRotation = rotation;
                set.hasOpponentRotation = true;
            } else {
                set.rotation = rotation;
                set.hasRotation = true;
            }
        }

        // loopIdle (alias loopAnimation): let the BBS animation loop instead of
        // re-triggering idle every beat.
        JsonElement loop = json.has("loopIdle") ? json.get("loopIdle")
                : json.get("loopAnimation");
        if (loop != null && loop.isJsonPrimitive()) {
            boolean value = readBool(loop, false);
            if (opponent) {
                set.opponentLoopIdle = value;
                set.hasOpponentLoopIdle = true;
            } else {
                set.loopIdle = value;
                set.hasLoopIdle = true;
            }
        }

        JsonObject animations = json.has("animations") && json.get("animations").isJsonObject()
                ? json.getAsJsonObject("animations") : null;
        if (animations != null) {
            for (Map.Entry<String, JsonElement> animation : animations.entrySet()) {
                String action = animation.getKey().trim();
                AnimEntry entry = readEntry(animation.getValue(), action);
                if (entry != null) {
                    set.actions.put((opponent ? "opponent." : "") + actionKey(action), entry);
                }
            }
        }

        // A form-only definition follows BBS/FNF conventional state names. Keep
        // idle2 explicit because enabling it changes alternating-idle behavior.
        if (animations == null && !form.isBlank()) {
            for (String action : new String[]{"idle", "left", "down", "up", "right", "miss", "hey"}) {
                AnimEntry entry = new AnimEntry();
                entry.state = action;
                set.actions.put((opponent ? "opponent." : "") + actionKey(action), entry);
            }
        }
    }

    private static AnimEntry readEntry(JsonElement value, String action) {
        if (value == null) return null;
        AnimEntry entry = new AnimEntry();

        try {
            if (value.isJsonPrimitive()) {
                entry.state = value.getAsString().trim();
            } else if (value.isJsonObject()) {
                JsonObject object = value.getAsJsonObject();
                entry.state = readString(object, "state");
                if (entry.state.isBlank()) entry.state = readString(object, "anim");
                if (entry.state.isBlank()) entry.state = action;
                if (object.has("cameraOffset")) {
                    float[] offset = readVec2(object.get("cameraOffset"));
                    entry.camX = offset[0];
                    entry.camY = offset[1];
                }
            }
        } catch (Exception ignored) {}

        return entry.state.isBlank() ? null : entry;
    }

    private static String actionKey(String action) {
        return action == null ? "" : action.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String readString(JsonObject object, String key) {
        try {
            return object.has(key) ? object.get(key).getAsString().trim() : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String readFirstString(JsonObject object, String... keys) {
        for (String key : keys) {
            String value = readString(object, key);
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private static boolean readBool(JsonElement value, boolean fallback) {
        try {
            if (value.getAsJsonPrimitive().isBoolean()) return value.getAsBoolean();
            String text = value.getAsString().trim().toLowerCase(java.util.Locale.ROOT);
            return text.equals("true") || text.equals("1") || text.equals("yes") || text.equals("on");
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static float[] readVec2(JsonElement value) {
        try {
            if (value.isJsonArray()) {
                JsonArray array = value.getAsJsonArray();
                return new float[]{array.get(0).getAsFloat(), array.size() > 1 ? array.get(1).getAsFloat() : 0};
            }
        } catch (Exception ignored) {}
        return new float[]{0, 0};
    }

    private static float readFloat(JsonElement value) {
        try {
            float result = value.getAsFloat();
            return Float.isFinite(result) ? result : 0;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static int readColor(JsonObject json) {
        for (String key : new String[]{"healthbar_colors", "health_bar_colors", "healthBarColors"}) {
            try {
                if (!json.has(key) || !json.get(key).isJsonArray()) continue;
                JsonArray array = json.getAsJsonArray(key);
                if (array.size() < 3) continue;
                int r = Math.max(0, Math.min(255, array.get(0).getAsInt()));
                int g = Math.max(0, Math.min(255, array.get(1).getAsInt()));
                int b = Math.max(0, Math.min(255, array.get(2).getAsInt()));
                return (r << 16) | (g << 8) | b;
            } catch (Exception ignored) {}
        }
        return -1;
    }

    public static synchronized float[] baseCameraOffset(String setName) {
        return baseCameraOffset(setName, "player");
    }

    public static synchronized float[] baseCameraOffset(String setName, String role) {
        AnimSet set = resolveSet(setName);
        if (set == null) return new float[]{0, 0};
        if ("opponent".equals(role) && set.hasOpponentBaseCamera) {
            return new float[]{set.opponentBaseCamX, set.opponentBaseCamY};
        }
        return new float[]{set.baseCamX, set.baseCamY};
    }

    public static synchronized float rotation(String setName, String role) {
        AnimSet set = resolveSet(setName);
        if (set == null) return 0;
        if ("opponent".equals(role) && set.hasOpponentRotation) return set.opponentRotation;
        return set.rotation;
    }

    /** When true, the idle is not re-triggered each beat; the BBS loop carries it. */
    public static synchronized boolean loopIdle(String setName, String role) {
        AnimSet set = resolveSet(setName);
        return set != null && set.loopIdle(role);
    }

    public static synchronized float[] play(Player player, String setName, String role, String action) {
        if (!available || player == null) return null;
        AnimSet set = resolveSet(setName);
        if (set == null) return null;

        AnimEntry entry = set.resolve(role, action);
        if (entry == null && !DEFAULT_SET.equals(key(setName))) {
            AnimSet fallback = resolveSet(DEFAULT_SET);
            if (fallback != null) entry = fallback.resolve(role, action);
        }
        if (entry == null || entry.state.isBlank()) return null;

        return BbsFsAnimationBridge.play(player, set.form(role), set.bundledForm(role), entry.state)
                ? new float[]{entry.camX, entry.camY} : null;
    }

    /** Applies the character's BBS form up front, before the first animation plays. */
    public static synchronized boolean prepare(Player player, String setName, String role) {
        if (!available || player == null) return false;
        AnimSet set = resolveSet(setName);
        if (set == null) return false;
        return BbsFsAnimationBridge.prepare(player, set.form(role), set.bundledForm(role));
    }

    public static void stop(Player player) {
        BbsFsAnimationBridge.restoreAll();
    }

    /** Full-bright control for a performer: value 0 = unlit/flat, 1 = normal world light. */
    public static void setLighting(Player player, float value) {
        BbsFsAnimationBridge.setLighting(player, value);
    }

    /** Whether a performer is currently forced to a non-default (e.g. full-bright) lighting. */
    public static boolean isLightingForced(Player player) {
        return BbsFsAnimationBridge.isLightingForced(player);
    }

    /** True when BBS is currently rendering this player through a form. */
    public static boolean hasActiveBbsForm(Player player) {
        return BbsFsAnimationBridge.hasActiveForm(player);
    }

    /** Releases one client-side performer while leaving the active cast intact. */
    public static void release(Player player) {
        BbsFsAnimationBridge.restore(player);
    }

    /**
     * Editor helper: bundles the given BBS form (its model + texture) into a
     * character folder so it becomes self-contained and shareable. baseName is
     * the character definition's file name without extension.
     */
    public static synchronized boolean bundleForm(String formName, Path targetFolder, String baseName) {
        boolean ok = BbsFsAnimationBridge.exportForm(formName, targetFolder,
                baseName == null || baseName.isBlank() ? "character" : baseName);
        if (ok) {
            BbsFsAnimationBridge.clearBundledForms();
            reload();
        }
        return ok;
    }
}
