package com.fnfmod.client;

import com.fnfmod.FnfMod;
import com.fnfmod.song.SongLibrary;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Client-side gameplay options, stored in config/fnfmod/options.json. */
public class ClientOptions {
    public static final String SONG_ICON = "__song__";
    public static final String NOTE_SKIN_DEFAULT = "default";
    public static final String NOTE_SKIN_NONE = "none";
    public static final String NOTE_ASSET_SOURCE_AUTO = "auto";
    public static final String NOTE_ASSET_SOURCE_CURRENT = "current";
    public static final String NOTE_ASSET_SOURCE_GLOBAL = "global";
    public static final String SKIN_SOURCE_FORM = "form";
    public static final String SKIN_SOURCE_PLAYER = "player";
    public static final String SKIN_SOURCE_FILE = "file";
    public static final String SKIN_SOURCE_ACCOUNT = "account";
    /** Keep Blockified/Minecraft GUI coordinates at a Psych-style 1280x720 minimum canvas. */
    public boolean forcePsychResolution = false;
    public boolean downscroll = false;
    /** Your strumline centered, opponent notes split to the screen edges. */
    public boolean middlescroll = false;
    public boolean ghostTapping = true;
    /** Bot auto-plays your notes. Scores are shown but never saved. */
    public boolean botplay = false;
    public double offsetMs = 0;
    public double scrollSpeedMult = 1.0;
    /** true = constant scroll speed (overrides chart), false = multiplier of chart speed. */
    public boolean constantScrollSpeed = false;
    /** Rating popup position, as a fraction of the screen (-1 = use default). */
    public double ratingX = -1;
    public double ratingY = -1;
    /** Local player: none = disabled; default = song-defined set; otherwise a named set. */
    public String animationSet = "none";
    /** Solo opponent/bot selection. Null migrates old configs to animationSet. */
    public String opponentAnimationSet;
    /** Give the locally controlled performer your current skin on compatible BBS player forms. */
    public boolean playerUsePlayerSkin = false;
    /** Give the solo bot your current Minecraft skin when its BBS form supports player skins. */
    public boolean botUsePlayerSkin = false;
    /** Compatible BBS player-form texture source: form, player, or file. */
    public String playerSkinSource;
    public String botSkinSource;
    /** Absolute PNG selected in the native file picker. Empty unless the file source is used. */
    public String playerSkinFile = "";
    public String botSkinFile = "";
    /** Minecraft account name used by the account skin source. */
    public String playerSkinAccount = "";
    public String botSkinAccount = "";
    /** Minecraft model geometry for a selected file (false = wide/Steve, true = slim/Alex). */
    public boolean playerSkinSlim = false;
    public boolean botSkinSlim = false;
    /** Solo mode side: 0 = player, 1 = opponent, 2 = both. */
    public int playAs = 0;
    /** default = chart arrowSkin/splashSkin; none = procedural; otherwise skins/&lt;name&gt;. */
    public String noteSkin = NOTE_SKIN_DEFAULT;
    /** Source tab used for same-named note assets: auto (legacy), current, or global. */
    public String noteSkinSource = NOTE_ASSET_SOURCE_AUTO;
    /** Health icons (__song__ = current song, "" = none). */
    public String playerIcon = SONG_ICON;
    public String botIcon = SONG_ICON;
    /** HUD style: default, abbreviated, numbers, vanilla, fnf. */
    public String hudStyle = "default";
    /** Splash pair name in config/fnfmod/splashes/ ("" = off). Ignored when the note skin ships its own. */
    public String splashSkin = "";
    public String splashSkinSource = NOTE_ASSET_SOURCE_AUTO;
    /** default = chart/Psych hold cover; none = off; otherwise a pack in splashes/holdSplashes/. */
    public String holdSplashSkin = NOTE_SKIN_DEFAULT;
    public String holdSplashSkinSource = NOTE_ASSET_SOURCE_AUTO;
    /** Hitsound file name in config/fnfmod/hitsounds/ ("" = off). */
    public String hitsound = "";
    public double hitsoundVolume = 1.0;

    // The Chart tab's options moved to per-song files (config/fnfmod/editor/<song>.json), since
    // a value tuned for one chart should not follow the user into every other one. These fields
    // remain only as the starting values a song inherits the first time it is opened.
    /** Legacy seed: chart editor playback hitsounds, per chart side. */
    public boolean editorHitsoundPlayer = false;
    public boolean editorHitsoundOpponent = false;
    /** Legacy seed: editor-only metronome; never used by gameplay. */
    public boolean editorMetronome = false;
    public double editorMetronomeVolume = 0.75;
    public boolean editorWaveforms = true;
    public boolean editorOnsetMarkers = true;
    /** Legacy seed: added to the chart's song offset only inside the chart editor. */
    public double editorChartingOffsetMs = 0.0;
    /** Chart-editor instrumental waveform RGB. */
    public int editorInstWaveformColor = 0x0000FF;

    /** Show the world XYZ axis gizmo (bottom-right) while playtesting from the editor. */
    public boolean editorShowAxisGizmo = false;
    /** Show seek, pause, and timeline controls during chart-editor playtests. */
    public boolean editorPlaytestPlaybackControls = false;

    /**
     * Route note input through the frame-rate-independent sampler. Off keeps the
     * per-frame GLFW handler. The high-rate backend is added in a later step; until
     * then this only changes which path GLFW input flows through.
     */
    public boolean preciseInput = false;

    /**
     * Pre-song warning flags: "on" (both sources), "off" (neither),
     * "blockified" (engine-detected only), "song" (author warnings.txt only).
     */
    public String songWarnings = "on";

    /** Legacy boolean form of {@link #songWarnings}, read once to migrate old saves. */
    @Deprecated
    public Boolean showSongWarnings;

    /** Engine-detected warnings — render distance and future hardcoded checks. */
    public boolean warnBlockified() {
        String mode = songWarnings == null ? "on" : songWarnings;
        return mode.equals("on") || mode.equals("blockified");
    }

    /** Author warnings from a song's or pack's warnings.txt. */
    public boolean warnSong() {
        String mode = songWarnings == null ? "on" : songWarnings;
        return mode.equals("on") || mode.equals("song");
    }

    public int[] noteColorBase = defaultBase();
    /** Psych RGB template green channel; white by default. */
    public int[] noteColorHighlight = defaultHighlight();
    public int[] noteColorOutline = defaultOutline();

    public static int[] defaultBase() {
        return new int[]{0xC24B99, 0x00FFFF, 0x12FA05, 0xF9393F};
    }

    public static int[] defaultOutline() {
        return new int[]{0x3C1F56, 0x1542B7, 0x0A4447, 0x651038};
    }

    public static int[] defaultHighlight() {
        return new int[]{0xFFFFFF, 0xFFFFFF, 0xFFFFFF, 0xFFFFFF};
    }

    private static ClientOptions instance;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Per-mod-world overrides overlay the user's settings while inside a bundled mod
    // world. The overridden fields' original (user) values are kept so they are never
    // saved back to options.json and are restored on leaving the world.
    private static Map<String, JsonElement> overriddenOriginals = new LinkedHashMap<>();
    private static Set<String> overriddenKeys = Set.of();

    public static ClientOptions get() {
        if (instance == null) load();
        return instance;
    }

    public static void load() {
        // A fresh read from disk is the authoritative user base; drop any world
        // override bookkeeping so re-applying overrides starts clean.
        overriddenKeys = Set.of();
        overriddenOriginals = new LinkedHashMap<>();
        Path file = SongLibrary.root().resolve("options.json");
        instance = null;
        try {
            if (Files.isRegularFile(file)) {
                instance = GSON.fromJson(Files.readString(file), ClientOptions.class);
            }
        } catch (Exception e) {
            FnfMod.LOGGER.warn("Could not read options.json: {}", e.toString());
        }
        if (instance == null) instance = new ClientOptions();
        normalize(instance);
    }

    /** Applies the on-load migrations and defaults; shared by load() and override merges. */
    private static void normalize(ClientOptions o) {
        // Migrate the old on/off boolean to the new four-way mode.
        if (o.showSongWarnings != null) {
            o.songWarnings = o.showSongWarnings ? "on" : "off";
            o.showSongWarnings = null;
        }
        if (o.songWarnings == null || o.songWarnings.isBlank()) o.songWarnings = "on";
        if (o.animationSet == null || o.animationSet.isBlank()) o.animationSet = "none";
        if (o.opponentAnimationSet == null || o.opponentAnimationSet.isBlank()) {
            o.opponentAnimationSet = o.animationSet;
        }
        // Old configs only had a boolean Form/Player choice. A missing source is
        // migrated without changing the user's existing selection.
        o.playerSkinSource = normalizeSkinSource(o.playerSkinSource, o.playerUsePlayerSkin);
        o.botSkinSource = normalizeSkinSource(o.botSkinSource, o.botUsePlayerSkin);
        o.playerUsePlayerSkin = SKIN_SOURCE_PLAYER.equals(o.playerSkinSource);
        o.botUsePlayerSkin = SKIN_SOURCE_PLAYER.equals(o.botSkinSource);
        if (o.playerSkinFile == null) o.playerSkinFile = "";
        if (o.botSkinFile == null) o.botSkinFile = "";
        if (o.playerSkinAccount == null) o.playerSkinAccount = "";
        if (o.botSkinAccount == null) o.botSkinAccount = "";
        if (o.noteSkin == null || o.noteSkin.isBlank()) o.noteSkin = NOTE_SKIN_DEFAULT;
        if (o.holdSplashSkin == null || o.holdSplashSkin.isBlank()) o.holdSplashSkin = NOTE_SKIN_DEFAULT;
        o.noteSkinSource = normalizeNoteAssetSource(o.noteSkinSource);
        o.splashSkinSource = normalizeNoteAssetSource(o.splashSkinSource);
        o.holdSplashSkinSource = normalizeNoteAssetSource(o.holdSplashSkinSource);
        if (o.noteColorBase == null || o.noteColorBase.length != 4) o.noteColorBase = defaultBase();
        if (o.noteColorHighlight == null || o.noteColorHighlight.length != 4) {
            o.noteColorHighlight = defaultHighlight();
        }
        if (o.noteColorOutline == null || o.noteColorOutline.length != 4) o.noteColorOutline = defaultOutline();
    }

    private static String normalizeSkinSource(String source, boolean oldUsePlayerSkin) {
        if (source == null || source.isBlank()) {
            return oldUsePlayerSkin ? SKIN_SOURCE_PLAYER : SKIN_SOURCE_FORM;
        }
        String normalized = source.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case SKIN_SOURCE_PLAYER, SKIN_SOURCE_FILE, SKIN_SOURCE_ACCOUNT -> normalized;
            default -> SKIN_SOURCE_FORM;
        };
    }

    private static String normalizeNoteAssetSource(String source) {
        if (source == null) return NOTE_ASSET_SOURCE_AUTO;
        return switch (source.trim().toLowerCase(java.util.Locale.ROOT)) {
            case NOTE_ASSET_SOURCE_CURRENT -> NOTE_ASSET_SOURCE_CURRENT;
            case NOTE_ASSET_SOURCE_GLOBAL -> NOTE_ASSET_SOURCE_GLOBAL;
            default -> NOTE_ASSET_SOURCE_AUTO;
        };
    }

    /**
     * Overlays a bundled mod world's {@code blockified-options.json} onto the user's
     * settings. Any key present there shadows the user's value and is locked in-game;
     * keys not present keep the user's value. Pass {@code null} (a normal world / menu)
     * to restore the plain user settings. Overrides are never persisted.
     */
    public static synchronized void applyWorldOverrides(Path worldRoot) {
        restoreOverrides();
        if (worldRoot == null) return;
        Path file = worldRoot.resolve("blockified-options.json");
        if (!Files.isRegularFile(file)) return;
        JsonObject override;
        try {
            override = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        } catch (Exception e) {
            FnfMod.LOGGER.warn("Bad world blockified-options.json {}: {}", file, e.toString());
            return;
        }
        // Before opponentAnimationSet existed, animationSet controlled both solo
        // sides. Keep old mod-world configs equivalent while allowing new ones to
        // override either selector independently.
        if (override.has("animationSet") && !override.has("opponentAnimationSet")) {
            override.add("opponentAnimationSet", override.get("animationSet").deepCopy());
        }
        // Preserve old mod-world configs that only set the former two-way boolean.
        if (override.has("playerUsePlayerSkin") && !override.has("playerSkinSource")) {
            override.addProperty("playerSkinSource", override.get("playerUsePlayerSkin").getAsBoolean()
                    ? SKIN_SOURCE_PLAYER : SKIN_SOURCE_FORM);
        }
        if (override.has("botUsePlayerSkin") && !override.has("botSkinSource")) {
            override.addProperty("botSkinSource", override.get("botUsePlayerSkin").getAsBoolean()
                    ? SKIN_SOURCE_PLAYER : SKIN_SOURCE_FORM);
        }
        JsonObject baseJson = GSON.toJsonTree(get()).getAsJsonObject();
        Map<String, JsonElement> originals = new LinkedHashMap<>();
        Set<String> keys = new LinkedHashSet<>();
        for (Map.Entry<String, JsonElement> entry : override.entrySet()) {
            if (!baseJson.has(entry.getKey())) continue; // unknown setting; ignore
            originals.put(entry.getKey(), baseJson.get(entry.getKey()));
            baseJson.add(entry.getKey(), entry.getValue());
            keys.add(entry.getKey());
        }
        if (keys.isEmpty()) return;
        ClientOptions merged = GSON.fromJson(baseJson, ClientOptions.class);
        normalize(merged);
        instance = merged;
        overriddenOriginals = originals;
        overriddenKeys = keys;
        FnfMod.LOGGER.info("Applied {} world setting override(s): {}", keys.size(), keys);
    }

    private static void restoreOverrides() {
        if (overriddenKeys.isEmpty()) return;
        JsonObject json = GSON.toJsonTree(get()).getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : overriddenOriginals.entrySet()) {
            json.add(entry.getKey(), entry.getValue());
        }
        ClientOptions restored = GSON.fromJson(json, ClientOptions.class);
        normalize(restored);
        instance = restored;
        overriddenOriginals = new LinkedHashMap<>();
        overriddenKeys = Set.of();
    }

    /** True while the given settings field is forced by the current mod world (locked in-game). */
    public static boolean isLocked(String field) {
        return overriddenKeys.contains(field);
    }

    public static boolean hasWorldOverrides() {
        return !overriddenKeys.isEmpty();
    }

    /** HUD style with "vanilla" downgraded to "default" in creative (can't take real damage). */
    public static String effectiveHudStyle() {
        String s = get().hudStyle;
        if ("vanilla".equals(s)) {
            var p = net.minecraft.client.Minecraft.getInstance().player;
            if (p != null && p.getAbilities().instabuild) return "default";
        }
        return s;
    }

    public static void save() {
        Path file = SongLibrary.root().resolve("options.json");
        try {
            Files.createDirectories(file.getParent());
            JsonObject json = GSON.toJsonTree(get()).getAsJsonObject();
            // A mod world's forced values must never leak into the user's own options.
            for (Map.Entry<String, JsonElement> entry : overriddenOriginals.entrySet()) {
                json.add(entry.getKey(), entry.getValue());
            }
            Files.writeString(file, GSON.toJson(json));
        } catch (Exception e) {
            FnfMod.LOGGER.warn("Could not save options.json: {}", e.toString());
        }
    }
}
