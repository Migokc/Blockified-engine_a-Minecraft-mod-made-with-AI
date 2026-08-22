package com.fnfmod.client.lua;

import com.fnfmod.FnfMod;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.fnfmod.chart.SongChart;
import com.fnfmod.client.ClientOptions;
import com.fnfmod.client.ClientSession;
import com.fnfmod.client.FnfKeys;
import com.fnfmod.client.camera.CameraOverlay;
import com.fnfmod.client.camera.GameplayCamera;
import com.fnfmod.client.audio.PsychSoundPlayer;
import com.fnfmod.client.gameplay.PsychAssetResolver;
import com.fnfmod.client.gameplay.RenderDistanceControl;
import com.fnfmod.client.gui.GameplayScreen;
import com.fnfmod.gameplay.GameplayClock;
import com.fnfmod.gameplay.PlaybackMode;
import com.fnfmod.gameplay.PlaybackPolicy;
import com.fnfmod.client.render.LuaWorldObject;
import com.fnfmod.client.render.LuaWorldObjectRenderer;
import com.fnfmod.client.render.ObjectBorderRegistry;
import com.fnfmod.client.render.MissingAssetTexture;
import com.fnfmod.client.render.WorldTextTextures;
import com.fnfmod.client.render.WorldSpriteEntityVisuals;
import com.fnfmod.client.render.HudLayerOrder;
import com.fnfmod.client.render.SparrowAtlas;
import com.fnfmod.client.render.AnimateAtlas;
import com.fnfmod.client.render.SpriteAtlasCache;
import com.fnfmod.client.render.SpriteImageCache;
import com.fnfmod.client.render.PsychCanvas;
import com.fnfmod.client.render.PsychCameraTransform;
import com.fnfmod.song.SongEntry;
import com.fnfmod.song.SongLibrary;
import com.fnfmod.entity.WorldSpriteEntity;
import com.fnfmod.gameplay.PerformerShadows;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.Entity;
import net.minecraft.resources.ResourceLocation;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.DebugLib;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

/** Sandboxed Psych Engine 1.0.x Lua compatibility runtime for gameplay. */
public final class PsychLuaRuntime implements AutoCloseable {
    /** Psych Engine's logical game canvas, independent of Minecraft GUI scale. */
    public static final int VIRTUAL_WIDTH = PsychCanvas.WIDTH;
    public static final int VIRTUAL_HEIGHT = PsychCanvas.HEIGHT;
    public static final int FUNCTION_CONTINUE = 0;
    public static final int FUNCTION_STOP = 1;
    public static final int FUNCTION_STOP_LUA = 2;
    public static final int FUNCTION_STOP_HSCRIPT = 3;
    public static final int FUNCTION_STOP_ALL = 4;

    /** Per-callback runaway-script guard. Normal gameplay callbacks use far less. */
    private static final long MAX_CALL_INSTRUCTIONS = 5_000_000L;
    private static final long CALL_WALL_CEILING_NANOS = 1_000_000_000L;

    private record Script(Path path, Globals globals, BudgetDebugLib budget) {}

    private static final class LuaAnimation {
        String name;
        List<SparrowAtlas.Frame> frames = List.of();
        double frameRate = 24;
        boolean looped = true;
        /**
         * Sheet this animation's frames belong to. loadMultipleFrames lets one
         * sprite mix animations from several atlases, so the frame coordinates
         * are only meaningful against the sheet they were read from.
         */
        ResourceLocation texture;
        int textureWidth, textureHeight;
        SparrowAtlas sheet;
    }

    private static final class LuaObject {
        String tag;
        String image = "";
        String text = "";
        String fontName = "";
        String camera = "game";
        double x, y, z, width = 100, height = 100, graphicWidth = 100, graphicHeight = 100;
        double scaleX = 1, scaleY = 1;
        double scrollFactorX = 1, scrollFactorY = 1;
        double alpha = 1, angle, rotationX, rotationY;
        int color = 0xFFFFFFFF;
        int textSize = 16;
        boolean visible = true, added, textObject, sizeExplicit;
        boolean antialiasing = true;
        /** An image was requested but could not be resolved or decoded. */
        boolean missingAsset;
        /** The image exists outside the current playback/Mods permission boundary. */
        boolean imageBlockedByPolicy;
        /** World-camera behavior. Billboard matches vanilla name tags; lighting matches world entities. */
        boolean worldBillboard = true, worldLighting = true;
        /** World-camera see-through: when true the object ignores depth and draws over geometry. */
        boolean worldSeeThrough;
        /** Physics belongs to the client-side host entity when this is a world sprite. */
        boolean worldGravity, worldCollision, worldShadow;
        /** Projected shadows from IRLights; independent from Minecraft's blob shadow. */
        boolean worldIrlightsShadows = true;
        /** Cached bake of world text into a texture (drawn as a sprite), invalidated on change. */
        String bakedKey;
        ResourceLocation bakedTexture;
        net.minecraft.client.renderer.texture.DynamicTexture bakedDynamic;
        int bakedWidth, bakedHeight;
        int order = HudLayerOrder.LUA_DEFAULT;
        boolean orderExplicit;
        ResourceLocation texture;
        DynamicTexture dynamicTexture;
        SpriteImageCache.Handle imageHandle;
        Path imageSource;
        int textureWidth, textureHeight;
        SparrowAtlas atlas;
        Path atlasPng;
        Path atlasXml;
        /** Extra sheets merged in by loadMultipleFrames, owned by this object. */
        final List<SparrowAtlas> extraAtlases = new ArrayList<>();
        /** Psych text styling. */
        double borderSize;
        int borderColor = 0xFF000000;
        String borderStyle = "outline";
        /** Non-text silhouette outline, measured in fixed 1280x720 Psych pixels. */
        double objectBorderSize;
        int objectBorderColor = 0xFF000000;
        String alignment = "left";
        boolean italic;
        /**
         * Psych's FlxText autoSize. False (the default for text made with a width)
         * wraps at the field width; true lets the field grow and never wraps.
         */
        boolean autoSize;
        double heightLimit;
        /** Psych blend mode name; only "add" changes rendering, the rest draw normally. */
        String blend = "";
        final Map<String, LuaAnimation> animations = new LinkedHashMap<>();
        final Map<String, double[]> animationOffsets = new LinkedHashMap<>();
        String currentAnimation;
        int animationFrame;
        double animationElapsed;
        boolean animationReverse, animationFinished;
    }

    /** Immutable world-object snapshot exposed specifically to the free-cam editor. */
    public record EditableWorldObject(
            String tag, String kind, String image, String text,
            double x, double y, double z, double width, double height,
            double scaleX, double scaleY, double alpha,
            double rotationX, double rotationY, double rotationZ,
            int color, int textSize,
            boolean visible, boolean billboard, boolean lighting, boolean seeThrough, boolean antialiasing,
            double borderSize, int borderColor, String borderStyle,
            String alignment, boolean italic,
            String animation, List<String> animations, int fps, boolean loop) {}

    private record Timer(String tag, long intervalMs, int totalLoops, long nextAt, int completed) {
        Timer advance(long next) { return new Timer(tag, intervalMs, totalLoops, next, completed + 1); }
    }

    /** One numeric property a tween drives. */
    private record TweenTarget(String path, double from, double to) {}

    /**
     * A tween over any number of numeric properties, plus an optional packed-color
     * property. Colors interpolate per channel rather than across the packed int,
     * which would run through unrelated hues.
     */
    private record Tween(String tag, List<TweenTarget> targets, long start, long durationMs,
                         String ease, String colorPath, int colorFrom, int colorTo,
                         long startDelayMs) {}

    private final GameplayScreen host;
    private final SongChart chart;
    private final String songId;
    private final Path songFolder;
    private final Path modRoot;
    private final EnumSet<SongLibrary.ExternalContent> externalContent;
    private final PlaybackPolicy playbackPolicy;
    private final PsychAssetResolver assets;
    private final PsychSoundPlayer soundPlayer;
    private final List<Script> scripts = new ArrayList<>();
    /**
     * Stable snapshot of {@link #scripts}, rebuilt only when a script is added or
     * removed. {@link #call} runs every frame (onUpdate/onUpdatePost and note
     * hits); copying the list each time it fired was needless per-frame garbage.
     */
    private Script[] scriptSnapshot;
    /** Psych-style debugPrint trace lines shown top-left, newest at the bottom, fading out. */
    private record DebugLine(String text, int color, long bornMs) {}
    private final List<DebugLine> debugLines = new ArrayList<>();
    private static final long DEBUG_LINE_LIFETIME_MS = 6000;
    private static final long DEBUG_LINE_FADE_MS = 1000;
    private static final int DEBUG_LINE_MAX = 20;
    private final Map<String, LuaObject> objects = new LinkedHashMap<>();
    /** Budget paired with each isolated Globals, including while its file is loading. */
    private final Map<Globals, BudgetDebugLib> scriptBudgets = new IdentityHashMap<>();
    private static final class WorldEntityBinding {
        final WorldSpriteEntity entity;
        Direction facing = Direction.NORTH;
        double lastX = Double.NaN, lastY = Double.NaN, lastZ = Double.NaN;
        WorldEntityBinding(WorldSpriteEntity entity) { this.entity = entity; }
    }

    /** Looping audio requested during hidden editor reconstruction, started only when it is revealed. */
    private record DeferredSound(String name, float volume, String tag, boolean loop) {}
    private final Map<String, WorldEntityBinding> worldSpriteEntities = new LinkedHashMap<>();
    private final Map<String, LuaValue> sharedVars = new HashMap<>();
    /**
     * Psych custom substate. Members are Lua object tags drawn above everything
     * else while the substate is open; a substate opened with pauseGame holds
     * gameplay the way Psych's own substates do.
     */
    private String substateName;
    private boolean substatePauses;
    private final List<String> substateMembers = new ArrayList<>();
    private final Map<String, Timer> timers = new LinkedHashMap<>();
    private final Map<String, Tween> tweens = new LinkedHashMap<>();
    private final Map<String, DeferredSound> reconstructionSounds = new LinkedHashMap<>();
    private final Set<String> warned = new LinkedHashSet<>();
    private final LuaFontLoader fontLoader;
    private final Random random = new Random();
    private final Map<Integer, Boolean> previousKeys = new HashMap<>();
    private final Set<Integer> queriedKeys = new LinkedHashSet<>();
    private final Map<String, Boolean> previousControls = new HashMap<>();
    private final Set<String> queriedControls = new LinkedHashSet<>();
    private int lastStep = Integer.MIN_VALUE;
    private int lastBeat = Integer.MIN_VALUE;
    private int lastSection = Integer.MIN_VALUE;
    private int lastMeterBeat = Integer.MIN_VALUE;
    private int lastMeasure = Integer.MIN_VALUE;
    private boolean songStarted;
    /** 0=loading files, 1=onCreate, 2=onCreatePost, 3=running. */
    private int createPhase;
    private boolean closed;

    public static PsychLuaRuntime load(GameplayScreen host, SongChart chart) {
        return load(host, chart, null, null, null,
                new PlaybackPolicy(ClientSession.playbackMode, ClientSession.songAssets,
                        ClientSession.luaAllowed));
    }

    public static PsychLuaRuntime load(GameplayScreen host, SongChart chart, String requestedId,
                                       Path requestedFolder, SongEntry requestedEntry) {
        return load(host, chart, requestedId, requestedFolder, requestedEntry,
                PlaybackPolicy.resolve(PlaybackMode.LEGACY, requestedEntry));
    }

    public static PsychLuaRuntime load(GameplayScreen host, SongChart chart, String requestedId,
                                       Path requestedFolder, SongEntry requestedEntry,
                                       PlaybackPolicy policy) {
        String id = requestedId == null || requestedId.isBlank()
                ? (ClientSession.songId == null || ClientSession.songId.isBlank()
                ? chart.title : ClientSession.songId) : requestedId;
        SongEntry entry = requestedEntry != null ? requestedEntry : SongLibrary.get(id);
        Path folder = requestedFolder != null ? requestedFolder
                : ClientSession.resolvedFolder != null ? ClientSession.resolvedFolder
                : entry == null ? null : entry.folder;
        Path root = entry == null ? folder : entry.modRoot;
        PsychLuaRuntime runtime = new PsychLuaRuntime(host, chart, id, folder, root, entry, policy);
        runtime.applyNoteTypeConfigs();
        runtime.loadScripts(entry);
        runtime.syncWorldSpriteEntities(host.psychLuaMachinePosition(),
                com.fnfmod.client.gameplay.StageOrientation.facing());
        return runtime;
    }

    private PsychLuaRuntime(GameplayScreen host, SongChart chart, String songId, Path songFolder, Path modRoot,
                            SongEntry entry, PlaybackPolicy policy) {
        this.host = host;
        this.chart = chart;
        this.songId = songId == null ? "unknown" : songId;
        this.songFolder = normalize(songFolder);
        this.modRoot = normalize(modRoot);
        this.externalContent = entry == null || entry.externalContent == null
                ? SongLibrary.allExternalContent() : EnumSet.copyOf(entry.externalContent);
        this.playbackPolicy = policy == null ? PlaybackPolicy.resolve(PlaybackMode.LEGACY, entry) : policy;
        this.assets = new PsychAssetResolver(this.songFolder, entry, this.playbackPolicy, chart.stage);
        this.fontLoader = new LuaFontLoader(assets.roots(SongLibrary.ExternalContent.FONTS), SongLibrary.fontsDir(),
                !assets.roots(SongLibrary.ExternalContent.FONTS).isEmpty());
        this.soundPlayer = new PsychSoundPlayer(assets::sound,
                tag -> call("onSoundFinished", tag));
    }

    private boolean allows(SongLibrary.ExternalContent content) {
        return externalContent.contains(content) && playbackPolicy.allows(null, content);
    }

    /**
     * The naked mod's scripts are installed, user-controlled engine scripts rather
     * than content owned by the current song.  A lightweight songs/ entry therefore
     * must not suppress them.  They still obey the installed-mod Lua permission,
     * mod-world isolation and the dedicated-server no-Lua policy.
     */
    private boolean allowsGlobalLua() {
        if (!playbackPolicy.luaAllowed()) return false;
        Path global = SongLibrary.globalSharedAssetRoot();
        return global != null && SongLibrary.getExternalFolderContent(
                global.toAbsolutePath().normalize().toString())
                .contains(SongLibrary.ExternalContent.LUA);
    }

    /** Psych 1.0 custom_notetypes/*.txt property files are applied before Lua onCreate. */
    private void applyNoteTypeConfigs() {
        for (SongChart.Note note : chart.notes) {
            String type = note.noteType == null ? "" : note.noteType;
            switch (type) {
                case "Hurt Note" -> {
                    note.ignoreNote = note.playerSide;
                    note.hitCausesMiss = true;
                    note.missHealth = 0.3;
                }
                case "Alt Animation" -> note.animSuffix = "-alt";
                case "No Animation" -> {
                    note.noAnimation = true;
                    note.noMissAnimation = true;
                }
                case "GF Sing" -> note.gfNote = true;
                default -> { }
            }
        }
        if (!allows(SongLibrary.ExternalContent.LUA) || chart.notes.isEmpty()) return;
        Map<String, List<Integer>> indicesByType = new LinkedHashMap<>();
        for (int i = 0; i < chart.notes.size(); i++) {
            String type = chart.notes.get(i).noteType;
            if (type != null && !type.isBlank()) {
                indicesByType.computeIfAbsent(type, key -> new ArrayList<>()).add(i);
            }
        }
        for (var entry : indicesByType.entrySet()) {
            Path config = findCustomNoteFile(entry.getKey(), ".txt");
            if (config == null) continue;
            try {
                for (String rawLine : Files.readAllLines(config)) {
                    String line = rawLine.trim();
                    if (line.isBlank() || line.startsWith("#") || line.startsWith("//")) continue;
                    int colon = line.indexOf(':');
                    int equals = line.indexOf('=');
                    int separator = colon < 0 ? equals : equals < 0 ? colon : Math.min(colon, equals);
                    if (separator <= 0) continue;
                    String property = line.substring(0, separator).trim();
                    if (property.equals("noteType") || property.startsWith("extraData")) continue;
                    Object value = interpretConfigValue(line.substring(separator + 1).trim());
                    for (int index : entry.getValue()) host.psychLuaSetGroup("unspawnNotes", index, property, value);
                }
            } catch (Exception error) {
                warnOnce("custom note config " + config.getFileName() + ": " + compactError(error));
            }
        }
    }

    private Path findCustomNoteFile(String noteType, String extension) {
        for (Path root : new Path[]{modRoot, songFolder}) {
            if (root == null) continue;
            Path directory = root.resolve("custom_notetypes");
            if (!Files.isDirectory(directory)) continue;
            String wanted = noteType + extension;
            try (Stream<Path> files = Files.list(directory)) {
                Path match = files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().equalsIgnoreCase(wanted))
                        .findFirst().orElse(null);
                if (match != null) return match;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static Object interpretConfigValue(String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        if (value.equalsIgnoreCase("true")) return true;
        if (value.equalsIgnoreCase("false")) return false;
        if (value.equalsIgnoreCase("null")) return null;
        try { return Double.parseDouble(value); } catch (NumberFormatException ignored) { return value; }
    }

    private void loadScripts(SongEntry entry) {
        LinkedHashSet<Path> files = new LinkedHashSet<>();
        // Global scripts belong to the naked installed mod, not to the selected
        // song. This includes lightweight config/fnfmod/songs entries.
        if (allowsGlobalLua()) {
            addLuaFiles(SongLibrary.scriptsDir(), files);
        }
        boolean songLuaAllowed = allows(SongLibrary.ExternalContent.LUA);
        boolean stageFound = false;
        if (modRoot != null && songLuaAllowed) {
            addLuaFiles(modRoot.resolve("scripts"), files);
            stageFound = addStageLua(modRoot, chart.stage, files);
            for (String type : chart.notes.stream().map(n -> n.noteType).filter(s -> s != null && !s.isBlank()).distinct().toList()) {
                addLuaFile(modRoot.resolve("custom_notetypes").resolve(type + ".lua"), files);
            }
            for (String type : chart.events.stream().map(e -> e.name).filter(s -> s != null && !s.isBlank()).distinct().toList()) {
                addLuaFile(modRoot.resolve("custom_events").resolve(type + ".lua"), files);
            }
            addLuaFiles(modRoot.resolve("data").resolve(songId), files);
            // Psych keeps audio-side scripts beside Inst/Voices under songs/<song>.
            addLuaFiles(modRoot.resolve("songs").resolve(songId), files);
        }
        // Base-engine stage scripts belong to first configured directory, not
        // song source directory. Its own Lua checklist decides availability.
        if (!stageFound && playbackPolicy.luaAllowed()) {
            Path shared = SongLibrary.primaryExternalAssetRoot(SongLibrary.ExternalContent.LUA);
            addStageLua(shared, chart.stage, files);
        }
        if (songLuaAllowed) {
            if (entry != null) addLuaFiles(entry.folder, files);
            addLuaFiles(songFolder, files);
        }

        for (Path file : files) loadOne(file);
        if (!scripts.isEmpty()) {
            createPhase = 1;
            call("onCreate");
            createPhase = 2;
            call("onCreatePost");
            createPhase = 3;
            call("onStartCountdown");
            FnfMod.LOGGER.info("Loaded {} Psych Lua script(s) for {}", scripts.size(), songId);
        }
    }

    private static Path normalize(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
    }

    private static void addLuaFile(Path file, Set<Path> out) {
        Path normalized = normalize(file);
        if (normalized != null && Files.isRegularFile(normalized)) out.add(normalized);
    }

    private static void addLuaFiles(Path directory, Set<Path> out) {
        if (directory == null || !Files.isDirectory(directory)) return;
        try (Stream<Path> stream = Files.list(directory)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".lua"))
                    .sorted().forEach(path -> addLuaFile(path, out));
        } catch (Exception ignored) {}
    }

    /** Psych forks place stage Lua in several equivalent folders. */
    private static boolean addStageLua(Path root, String stageId, Set<Path> out) {
        if (root == null || stageId == null || stageId.isBlank()) return false;
        String filename = stageId.trim() + ".lua";
        for (String folder : new String[]{"stages", "scripts/stages", "data/stages", "assets/stages"}) {
            Path candidate = root.resolve(folder).resolve(filename);
            if (Files.isRegularFile(candidate)) {
                addLuaFile(candidate, out);
                return true;
            }
        }
        return false;
    }

    private Script loadOne(Path file) {
        Globals globals = null;
        try {
            Script existing = findScript(file.toString());
            if (existing != null) return existing;
            globals = JsePlatform.standardGlobals();
            BudgetDebugLib budget = new BudgetDebugLib();
            globals.load(budget);
            scriptBudgets.put(globals, budget);
            sandbox(globals);
            installConstants(globals, file);
            installCallbacks(globals);
            budget.begin();
            try {
                globals.load(Files.readString(file), file.toString()).call();
            } finally {
                budget.end();
            }
            Script script = new Script(file, globals, budget);
            scripts.add(script);
            scriptSnapshot = null;
            return script;
        } catch (Throwable error) {
            if (globals != null) scriptBudgets.remove(globals);
            report(file.getFileName() + ": " + compactError(error));
            return null;
        }
    }

    private Path resolveLuaScript(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String relative = raw.replace('\\', '/');
        if (!relative.toLowerCase(Locale.ROOT).endsWith(".lua")) relative += ".lua";
        for (Path root : new Path[]{modRoot, songFolder}) {
            if (root == null) continue;
            Path candidate = root.resolve(relative).normalize();
            if (candidate.startsWith(root) && Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    private boolean addDynamicScript(String raw) {
        Path file = resolveLuaScript(raw);
        if (file == null || findScript(file.toString()) != null) return false;
        Script script = loadOne(file);
        if (script == null) return false;
        if (createPhase >= 1) callSpecific(file.toString(), "onCreate", new Object[0]);
        if (createPhase >= 2) callSpecific(file.toString(), "onCreatePost", new Object[0]);
        if (createPhase >= 3) callSpecific(file.toString(), "onStartCountdown", new Object[0]);
        return true;
    }

    private boolean removeDynamicScript(String raw) {
        Script script = findScript(raw);
        if (script != null && scripts.remove(script)) {
            scriptBudgets.remove(script.globals);
            scriptSnapshot = null;
            return true;
        }
        return false;
    }

    private static void sandbox(Globals globals) {
        // Do not expose host-process, filesystem, environment, classloading, or
        // dynamic-code facilities to gameplay scripts. In particular LuaJ's
        // os.exit calls System.exit and os.getenv exposes Java environment data.
        for (String blocked : new String[]{"luajava", "io", "os", "package", "require",
                "dofile", "loadfile", "load", "loadstring", "debug"}) {
            globals.set(blocked, LuaValue.NIL);
        }
    }

    private void installConstants(Globals g, Path file) {
        g.set("Function_Continue", FUNCTION_CONTINUE);
        g.set("Function_Stop", FUNCTION_STOP);
        g.set("Function_StopLua", FUNCTION_STOP_LUA);
        g.set("Function_StopHScript", FUNCTION_STOP_HSCRIPT);
        g.set("Function_StopAll", FUNCTION_STOP_ALL);
        g.set("version", "1.0.4-minecraft");
        g.set("scriptName", file.toString());
        g.set("songName", chart.title);
        g.set("songPath", songId);
        g.set("loadedSongName", chart.title);
        g.set("loadedSongPath", songId);
        g.set("bpm", chart.startBpm);
        g.set("curBpm", chart.startBpm);
        g.set("scrollSpeed", chart.speed);
        g.set("crochet", 60000.0 / Math.max(1, chart.startBpm));
        g.set("stepCrochet", 15000.0 / Math.max(1, chart.startBpm));
        g.set("songLength", host.psychLuaSongLength());
        g.set("screenWidth", host.psychLuaScreenWidth());
        g.set("screenHeight", host.psychLuaScreenHeight());
        g.set("curStage", chart.stage);
        g.set("difficultyName", ClientSession.difficulty == null ? "normal" : ClientSession.difficulty);
        g.set("difficultyPath", ClientSession.difficulty == null ? "normal" : ClientSession.difficulty);
        g.set("hasVocals", LuaValue.valueOf(chart.needsVoices));
        g.set("downscroll", LuaValue.valueOf(ClientOptions.get().downscroll));
        g.set("middlescroll", LuaValue.valueOf(ClientOptions.get().middlescroll));
        g.set("ghostTapping", LuaValue.valueOf(ClientOptions.get().ghostTapping));
        g.set("botPlay", LuaValue.valueOf(ClientOptions.get().botplay));
        g.set("practice", LuaValue.FALSE);
        g.set("isStoryMode", LuaValue.FALSE);
        g.set("buildTarget", "windows");
        g.set("boyfriendName", chart.player1);
        g.set("dadName", chart.player2);
        g.set("gfName", chart.player3);
        for (int i = 0; i < 4; i++) {
            g.set("defaultPlayerStrumX" + i, host.psychLuaStrumX(true, i));
            g.set("defaultPlayerStrumY" + i, host.psychLuaStrumY(true, i));
            g.set("defaultOpponentStrumX" + i, host.psychLuaStrumX(false, i));
            g.set("defaultOpponentStrumY" + i, host.psychLuaStrumY(false, i));
        }
        installScriptLocation(g, file);
        installChartConstants(g);
        installPreferenceConstants(g);
    }

    /** Psych's script-location globals. Mod folders are reported relative to the pack root. */
    private void installScriptLocation(Globals g, Path file) {
        String mod = modRoot == null ? "" : modRoot.getFileName().toString();
        g.set("modFolder", mod);
        g.set("currentModDirectory", mod);
        g.set("luaDebugMode", LuaValue.FALSE);
        g.set("luaDeprecatedWarnings", LuaValue.FALSE);
    }

    private void installChartConstants(Globals g) {
        String difficulty = ClientSession.difficulty == null || ClientSession.difficulty.isBlank()
                ? "normal" : ClientSession.difficulty;
        g.set("difficulty", difficulty);
        g.set("difficultyNameTranslation", difficulty);
        g.set("chartPath", songFolder == null ? "" : songFolder.toString());
        String activeWeek = com.fnfmod.client.ClientStorySession.weekId();
        g.set("week", activeWeek);
        g.set("weekRaw", activeWeek);
        g.set("seenCutscene", LuaValue.FALSE);
        g.set("deaths", 0);
        g.set("healthGainMult", 1.0);
        g.set("healthLossMult", 1.0);
        g.set("instakillOnMiss", LuaValue.FALSE);
        for (String role : new String[]{"Boyfriend", "Opponent", "Girlfriend"}) {
            String lookup = switch (role) {
                case "Opponent" -> "dad";
                case "Girlfriend" -> "gf";
                default -> "boyfriend";
            };
            g.set("default" + role + "X", host.psychLuaDefaultCharacterX(lookup));
            g.set("default" + role + "Y", host.psychLuaDefaultCharacterY(lookup));
        }
    }

    /**
     * Psych's client-preference globals. Options Blockified does not have keep
     * Psych's documented default so a script reading them still branches sanely.
     */
    private void installPreferenceConstants(Globals g) {
        ClientOptions options = ClientOptions.get();
        g.set("hideHud", LuaValue.valueOf("none".equals(ClientOptions.effectiveHudStyle())));
        g.set("noteOffset", options.offsetMs);
        g.set("noResetButton", LuaValue.FALSE);
        g.set("framerate", Minecraft.getInstance().options.framerateLimit().get());
        g.set("timeBarType", "Time Left");
        g.set("scoreZoom", LuaValue.TRUE);
        g.set("cameraZoomOnBeat", LuaValue.TRUE);
        g.set("flashingLights", LuaValue.TRUE);
        g.set("healthBarAlpha", 1.0);
        g.set("lowQuality", LuaValue.FALSE);
        g.set("shadersEnabled", LuaValue.FALSE);
        g.set("guitarHeroSustains", LuaValue.TRUE);
        g.set("noteSkin", chart.noteTexture == null || chart.noteTexture.isBlank()
                ? "Default" : chart.noteTexture);
        g.set("splashSkin", chart.noteSplashTexture == null || chart.noteSplashTexture.isBlank()
                ? "Psych" : chart.noteSplashTexture);
        g.set("holdSplashSkin", chart.holdSplashTexture == null || chart.holdSplashTexture.isBlank()
                ? "Default" : chart.holdSplashTexture);
        g.set("splashAlpha", 0.6);
        g.set("noteSkinPostfix", "");
        g.set("splashSkinPostfix", "");
    }

    private void installCallbacks(Globals g) {
        fn(g, "debugPrint", args -> { debugPrint(args); return LuaValue.NIL; });
        // Render distance for the length of the song; restored when it ends. The
        // value is clamped to Minecraft's own 2..32 range.
        fn(g, "setRenderDistance", args -> {
            RenderDistanceControl.apply(args.checkint(1));
            return LuaValue.NIL;
        });
        fn(g, "getRenderDistance", args -> LuaValue.valueOf(RenderDistanceControl.current()));
        fn(g, "getSongPosition", args -> LuaValue.valueOf(host.psychLuaSongPosition()));
        // Base player FOV (Minecraft's 30-110 slider). Camera Zoom multiplies it,
        // so raising the base FOV widens the zoom range. Restored when the song ends.
        fn(g, "setFOV", args -> { host.psychLuaSetFov(args.optdouble(1, 70)); return LuaValue.NIL; });
        fn(g, "getFOV", args -> LuaValue.valueOf(host.psychLuaGetFov()));
        // HUD style override + time-bar visibility (rating/time visibility also work
        // through setProperty('rating.visible'/'timeBar.visible'/'timeTxt.visible', ...)).
        fn(g, "setHudStyle", args -> { host.psychLuaSetHudStyle(args.optjstring(1, "")); return LuaValue.NIL; });
        fn(g, "getHudStyle", args -> LuaValue.valueOf(host.psychLuaGetHudStyle()));
        fn(g, "showTimeBar", args -> { host.psychLuaShowTimeBar(args.optboolean(1, true)); return LuaValue.NIL; });
        fn(g, "getHealth", args -> LuaValue.valueOf(host.psychLuaHealth()));
        fn(g, "setHealth", args -> { host.psychLuaSetHealth(args.optdouble(1, 1)); return LuaValue.NIL; });
        fn(g, "addHealth", args -> { host.psychLuaSetHealth(host.psychLuaHealth() + args.optdouble(1, 0)); return LuaValue.NIL; });
        // Scripts may drive the score, but a run they touched is never saved as a
        // personal best, so charts can show custom scoring without faking records.
        fn(g, "addScore", args -> { host.psychLuaAddScore(args.optint(1, 0)); return LuaValue.NIL; });
        fn(g, "setScore", args -> { host.psychLuaSetScore(args.optint(1, 0)); return LuaValue.NIL; });
        fn(g, "addHits", args -> { host.psychLuaAddHits(args.optint(1, 0)); return LuaValue.NIL; });
        fn(g, "setHits", args -> { host.psychLuaSetHits(args.optint(1, 0)); return LuaValue.NIL; });
        fn(g, "setRatingPercent", args -> {
            host.psychLuaSetRatingPercent(args.optdouble(1, 0)); return LuaValue.NIL; });
        fn(g, "setRatingName", args -> {
            host.psychLuaSetRatingName(args.optjstring(1, "")); return LuaValue.NIL; });
        fn(g, "setRatingFC", args -> {
            host.psychLuaSetRatingFC(args.optjstring(1, "")); return LuaValue.NIL; });
        // Blockified redraws the HUD every frame, so the score text is always current.
        fn(g, "updateScoreText", args -> LuaValue.NIL);
        fn(g, "cameraShake", args -> {
            String camera = args.optjstring(1, "game");
            double intensity = args.optdouble(2, 0.05);
            double duration = args.optdouble(3, 0.5);
            boolean hud = camera.toLowerCase(Locale.ROOT).contains("hud");
            GameplayCamera.shake(hud ? 0 : duration, hud ? 0 : intensity,
                    hud ? duration : 0, hud ? intensity : 0);
            return LuaValue.NIL;
        });
        fn(g, "cameraFlash", args -> {
            CameraOverlay.flash(args.optjstring(1, "game"), color(args.optjstring(2, "FFFFFF")),
                    args.optdouble(3, 1), luaBoolean(args, 4, false));
            return LuaValue.NIL;
        });
        fn(g, "cameraFade", args -> {
            CameraOverlay.fade(args.optjstring(1, "game"), color(args.optjstring(2, "FFFFFF")),
                    args.optdouble(3, 1), luaBoolean(args, 4, false), luaBoolean(args, 5, false));
            return LuaValue.NIL;
        });
        fn(g, "addMisses", args -> { host.psychLuaAddMisses(args.optint(1, 0)); return LuaValue.NIL; });
        fn(g, "setMisses", args -> { host.psychLuaSetMisses(args.optint(1, 0)); return LuaValue.NIL; });
        fn(g, "getProperty", args -> toLua(getProperty(args.checkjstring(1))));
        fn(g, "setProperty", args -> { setProperty(args.checkjstring(1), fromLua(args.arg(2))); return LuaValue.TRUE; });
        fn(g, "getPropertyFromGroup", args -> toLua(host.psychLuaGetGroup(
                args.checkjstring(1), args.checkint(2), args.optjstring(3, ""))));
        fn(g, "setPropertyFromGroup", args -> { host.psychLuaSetGroup(args.checkjstring(1), args.checkint(2),
                args.optjstring(3, ""), fromLua(args.arg(4))); return LuaValue.TRUE; });
        fn(g, "setVar", args -> { sharedVars.put(args.checkjstring(1), args.arg(2)); return args.arg(2); });
        fn(g, "getVar", args -> sharedVars.getOrDefault(args.checkjstring(1), LuaValue.NIL));
        fn(g, "triggerEvent", args -> { host.psychLuaTriggerEvent(args.optjstring(1, ""),
                args.optjstring(2, ""), args.optjstring(3, ""), args.optjstring(4, ""),
                args.optjstring(5, ""), args.optjstring(6, ""), args.optjstring(7, ""),
                args.optjstring(8, "")); return LuaValue.NIL; });
        fn(g, "runMinecraftCommand", args -> LuaValue.valueOf(host.psychLuaRunMinecraftCommand(
                args.optjstring(1, ""), args.optjstring(2, "player"))));
        // Short Blockified alias; explicit name remains preferred in shared Psych scripts.
        fn(g, "runCommand", args -> LuaValue.valueOf(host.psychLuaRunMinecraftCommand(
                args.optjstring(1, ""), args.optjstring(2, "player"))));
        fn(g, "cameraSetTarget", args -> { host.psychLuaCameraTarget(args.optjstring(1, "")); return LuaValue.TRUE; });
        // Blockified pivot-orbit mode. Camera Follow Pos supplies the camera
        // position/radius and Camera Rotation 3D supplies all orbital animation.
        // cameraOrbit(pinned, x, y, z, duration, easing): follow uses
        // focus-relative XYZ; pinned uses absolute stage-local XYZ.
        fn(g, "cameraOrbit", args -> {
            host.psychLuaCameraOrbit(args.optboolean(1, false), args.optdouble(2, 0),
                    args.optdouble(3, 0), args.optdouble(4, 0), args.optdouble(5, 0.5),
                    args.optjstring(6, "smooth"));
            return LuaValue.TRUE;
        });
        fn(g, "startCameraOrbit", args -> {
            host.psychLuaCameraOrbit(args.optboolean(1, false), args.optdouble(2, 0),
                    args.optdouble(3, 0), args.optdouble(4, 0), args.optdouble(5, 0.5),
                    args.optjstring(6, "smooth"));
            return LuaValue.TRUE;
        });
        fn(g, "stopCameraOrbit", args -> { GameplayCamera.stopOrbit(); return LuaValue.TRUE; });
        fn(g, "endSong", args -> { host.psychLuaEndSong(); return LuaValue.TRUE; });
        fn(g, "restartSong", args -> { host.psychLuaRestartSong(); return LuaValue.TRUE; });
        fn(g, "exitSong", args -> { host.psychLuaExitSong(); return LuaValue.TRUE; });
        fn(g, "keyboardPressed", args -> LuaValue.valueOf(keyboardDown(args.optjstring(1, ""))));
        fn(g, "keyboardJustPressed", args -> LuaValue.valueOf(keyboardJustPressed(args.optjstring(1, ""))));
        fn(g, "keyboardReleased", args -> LuaValue.valueOf(keyboardReleased(args.optjstring(1, ""))));
        fn(g, "keyPressed", args -> LuaValue.valueOf(controlDown(args.optjstring(1, ""))));
        fn(g, "keyJustPressed", args -> { String control = controlName(args.optjstring(1, ""));
                return LuaValue.valueOf(controlDown(control) && !previousControls.getOrDefault(control, false)); });
        fn(g, "keyReleased", args -> { String control = controlName(args.optjstring(1, ""));
                return LuaValue.valueOf(!controlDown(control) && previousControls.getOrDefault(control, false)); });
        fn(g, "mouseClicked", args -> LuaValue.valueOf(mouseDown(args.optjstring(1, "left"))));
        fn(g, "mousePressed", args -> LuaValue.valueOf(mouseDown(args.optjstring(1, "left"))));
        fn(g, "mouseReleased", args -> LuaValue.FALSE);
        fn(g, "anyGamepadPressed", args -> LuaValue.FALSE);
        fn(g, "anyGamepadJustPressed", args -> LuaValue.FALSE);
        fn(g, "anyGamepadReleased", args -> LuaValue.FALSE);
        fn(g, "getRunningScripts", args -> { LuaTable out = new LuaTable(); int i = 1;
                for (Script script : scripts) out.set(i++, script.path.toString()); return out; });
        fn(g, "isRunning", args -> LuaValue.valueOf(findScript(args.optjstring(1, "")) != null));
        fn(g, "callOnLuas", args -> toLua(call(args.optjstring(1, ""), luaArgs(args.arg(2)))));
        fn(g, "callOnScripts", args -> toLua(call(args.optjstring(1, ""), luaArgs(args.arg(2)))));
        fn(g, "setOnLuas", args -> { setAll(args.optjstring(1, ""), fromLua(args.arg(2))); return LuaValue.TRUE; });
        fn(g, "setOnScripts", args -> { setAll(args.optjstring(1, ""), fromLua(args.arg(2))); return LuaValue.TRUE; });
        fn(g, "callScript", args -> toLua(callSpecific(args.optjstring(1, ""), args.optjstring(2, ""), luaArgs(args.arg(3)))));
        fn(g, "addLuaScript", args -> LuaValue.valueOf(addDynamicScript(args.optjstring(1, ""))));
        fn(g, "removeLuaScript", args -> LuaValue.valueOf(removeDynamicScript(args.optjstring(1, ""))));
        fn(g, "setGlobalFromScript", args -> { Script script = findScript(args.optjstring(1, ""));
                if (script == null) return LuaValue.FALSE;
                script.globals.set(args.optjstring(2, ""), args.arg(3)); return LuaValue.TRUE; });
        fn(g, "getGlobalFromScript", args -> { Script script = findScript(args.optjstring(1, ""));
                return script == null ? LuaValue.NIL : script.globals.get(args.optjstring(2, "")); });

        fn(g, "makeLuaSprite", args -> { makeObject(args, false, false); return LuaValue.NIL; });
        fn(g, "makeAnimatedLuaSprite", args -> { makeObject(args, false, true); return LuaValue.NIL; });
        fn(g, "makeFlxAnimateSprite", args -> {
            LuaObject o = object(args.checkjstring(1));
            disposeGraphic(o);
            o.textObject = false;
            o.image = args.optjstring(2, "");
            o.x = args.optdouble(3, 0);
            o.y = args.optdouble(4, 0);
            o.sizeExplicit = false;
            return LuaValue.valueOf(loadObjectAtlas(o, o.image, "animateatlas"));
        });
        fn(g, "loadAnimateAtlas", args -> LuaValue.valueOf(loadObjectAtlas(
                object(args.checkjstring(1)), args.optjstring(2, ""), "animateatlas")));
        fn(g, "makeLuaText", args -> { makeObject(args, true, false); return LuaValue.NIL; });
        fn(g, "makeGraphic", args -> { LuaObject o = object(args.checkjstring(1)); disposeGraphic(o);
                o.image = ""; o.missingAsset = false; o.imageBlockedByPolicy = false;
                o.width = args.optint(2, 256); o.height = args.optint(3, 256);
                o.graphicWidth = o.width; o.graphicHeight = o.height; o.sizeExplicit = true;
                o.color = color(args.optjstring(4, "FFFFFF")); return LuaValue.NIL; });
        fn(g, "addLuaSprite", args -> { LuaObject o = object(args.checkjstring(1));
                if (!o.orderExplicit) o.order = args.optboolean(2, false)
                        ? HudLayerOrder.LUA_DEFAULT : 0;
                o.added = true; return LuaValue.NIL; });
        fn(g, "addLuaText", args -> { object(args.checkjstring(1)).added = true; return LuaValue.NIL; });
        fn(g, "removeLuaSprite", args -> { removeObject(args.checkjstring(1)); return LuaValue.NIL; });
        fn(g, "removeLuaText", args -> { removeObject(args.checkjstring(1)); return LuaValue.NIL; });
        fn(g, "luaSpriteExists", args -> LuaValue.valueOf(objects.containsKey(args.checkjstring(1))));
        fn(g, "luaTextExists", args -> LuaValue.valueOf(objects.containsKey(args.checkjstring(1))));
        fn(g, "setTextString", args -> { object(args.checkjstring(1)).text = args.optjstring(2, ""); return LuaValue.NIL; });
        fn(g, "getTextString", args -> LuaValue.valueOf(object(args.checkjstring(1)).text));
        fn(g, "setTextSize", args -> { object(args.checkjstring(1)).textSize = args.optint(2, 16); return LuaValue.NIL; });
        fn(g, "getTextSize", args -> LuaValue.valueOf(object(args.checkjstring(1)).textSize));
        fn(g, "setTextFont", args -> { object(args.checkjstring(1)).fontName = args.optjstring(2, ""); return LuaValue.NIL; });
        fn(g, "getTextFont", args -> LuaValue.valueOf(object(args.checkjstring(1)).fontName));
        fn(g, "setTextWidth", args -> { object(args.checkjstring(1)).width = Math.max(0, args.optdouble(2, 0)); return LuaValue.NIL; });
        fn(g, "getTextWidth", args -> LuaValue.valueOf(object(args.checkjstring(1)).width));
        fn(g, "setTextHeight", args -> {
            object(args.checkjstring(1)).heightLimit = Math.max(0, args.optdouble(2, 0));
            return LuaValue.TRUE;
        });
        fn(g, "setTextAutoSize", args -> {
            object(args.checkjstring(1)).autoSize = luaBoolean(args, 2, true);
            return LuaValue.TRUE;
        });
        fn(g, "setTextItalic", args -> {
            object(args.checkjstring(1)).italic = luaBoolean(args, 2, false);
            return LuaValue.TRUE;
        });
        fn(g, "setTextAlignment", args -> {
            object(args.checkjstring(1)).alignment = args.optjstring(2, "left");
            return LuaValue.TRUE;
        });
        fn(g, "setTextBorder", args -> {
            LuaObject target = object(args.checkjstring(1));
            target.borderSize = args.optdouble(2, 0);
            target.borderColor = color(args.optjstring(3, "000000"));
            target.borderStyle = args.optjstring(4, "outline");
            return LuaValue.TRUE;
        });
        fn(g, "setObjectBorder", args -> {
            String tag = args.checkjstring(1);
            double requested = args.optdouble(2, 0);
            double size = Double.isFinite(requested) ? Math.max(0, requested) : 0;
            int borderColor = color(args.optjstring(3, "000000"));
            String style = args.optjstring(4, "outline");
            if (style.equalsIgnoreCase("none")) size = 0;
            LuaObject target = objects.get(tag);
            if (target != null && !target.textObject) {
                target.objectBorderSize = size;
                target.objectBorderColor = borderColor;
                syncObjectBorder(target);
                return LuaValue.TRUE;
            }
            return LuaValue.valueOf(host.psychLuaSetObjectBorder(tag, size, borderColor));
        });
        fn(g, "setTextColor", args -> { object(args.checkjstring(1)).color = color(args.optjstring(2, "FFFFFF")); return LuaValue.NIL; });
        fn(g, "setObjectCamera", args -> { object(args.checkjstring(1)).camera = args.optjstring(2, "game"); return LuaValue.NIL; });
        fn(g, "setWorldSpriteBillboard", args -> { object(args.checkjstring(1)).worldBillboard = args.optboolean(2, true); return LuaValue.NIL; });
        // World-camera see-through for any Lua object (text or sprite): draw over
        // geometry instead of being occluded. Off by default (respects depth).
        fn(g, "setObjectSeeThrough", args -> { object(args.checkjstring(1)).worldSeeThrough = args.optboolean(2, true); return LuaValue.NIL; });
        fn(g, "setWorldSpriteLighting", args -> { object(args.checkjstring(1)).worldLighting = args.optboolean(2, true); return LuaValue.NIL; });
        fn(g, "setWorldSpriteGravity", args -> { object(args.checkjstring(1)).worldGravity = args.optboolean(2, true); return LuaValue.NIL; });
        fn(g, "setWorldSpriteCollision", args -> { object(args.checkjstring(1)).worldCollision = args.optboolean(2, true); return LuaValue.NIL; });
        fn(g, "setWorldSpriteShadows", args -> { object(args.checkjstring(1)).worldShadow = args.optboolean(2, true); return LuaValue.NIL; });
        fn(g, "setWorldSpriteIRLightsShadows", args -> { object(args.checkjstring(1)).worldIrlightsShadows = args.optboolean(2, true); return LuaValue.NIL; });
        fn(g, "setWorldSpriteIrlightsShadows", args -> { object(args.checkjstring(1)).worldIrlightsShadows = args.optboolean(2, true); return LuaValue.NIL; });
        fn(g, "setObjectRotation", args -> { LuaObject o = object(args.checkjstring(1));
                o.rotationX = args.optdouble(2, o.rotationX); o.rotationY = args.optdouble(3, o.rotationY);
                o.angle = args.optdouble(4, o.angle); return LuaValue.NIL; });
        fn(g, "setObjectOrder", args -> { LuaObject o = object(args.checkjstring(1));
                o.order = args.optint(2, 0); o.orderExplicit = true; return LuaValue.NIL; });
        fn(g, "getObjectOrder", args -> LuaValue.valueOf(object(args.checkjstring(1)).order));
        fn(g, "setObjectAntialiasing", args -> { setTargetAntialiasing(args.checkjstring(1),
                args.optboolean(2, true)); return LuaValue.NIL; });
        fn(g, "setSpriteAntialiasing", args -> { setTargetAntialiasing(args.checkjstring(1),
                args.optboolean(2, true)); return LuaValue.NIL; });
        fn(g, "setGraphicSize", args -> { String tag = args.checkjstring(1);
                double w = args.optdouble(2, 0); double h = args.arg(3).isnil() ? 0 : args.optdouble(3, 0);
                if (isCharacterTag(tag)) setCharacterGraphicSize(tag, w, h);
                else setGraphicSize(object(tag), w, h); return LuaValue.NIL; });
        fn(g, "scaleObject", args -> { String tag = args.checkjstring(1);
                double x = args.optdouble(2, 1); double y = args.optdouble(3, 1);
                if (isCharacterTag(tag)) { host.psychLuaSetProperty(tag + ".scale.x", x);
                    host.psychLuaSetProperty(tag + ".scale.y", y); }
                else { LuaObject o = object(tag); o.scaleX = x; o.scaleY = y; }
                return LuaValue.NIL; });
        fn(g, "loadGraphic", args -> { LuaObject o = object(args.checkjstring(1));
                o.image = args.optjstring(2, ""); o.sizeExplicit = false; loadObjectImage(o, o.image); return LuaValue.NIL; });
        fn(g, "loadFrames", args -> LuaValue.valueOf(loadObjectAtlas(object(args.checkjstring(1)),
                args.optjstring(2, ""), args.optjstring(3, "auto"))));
        fn(g, "loadMultipleFrames", args -> LuaValue.valueOf(loadMultipleFrames(args)));
        fn(g, "updateHitbox", args -> { updateHitbox(object(args.checkjstring(1))); return LuaValue.NIL; });
        fn(g, "setBlendMode", args -> {
            object(args.checkjstring(1)).blend = args.optjstring(2, "");
            return LuaValue.TRUE;
        });
        fn(g, "getPixelColor", args -> LuaValue.valueOf(
                pixelColor(args.checkjstring(1), args.optint(2, 0), args.optint(3, 0))));
        fn(g, "precacheImage", args -> { precacheImage(args.optjstring(1, "")); return LuaValue.NIL; });
        fn(g, "addAnimationByPrefix", args -> LuaValue.valueOf(addAnimationByPrefix(args)));
        fn(g, "luaSpriteAddAnimationByPrefix", args -> LuaValue.valueOf(addAnimationByPrefix(args)));
        fn(g, "addAnimationByIndices", args -> LuaValue.valueOf(addAnimationByIndices(args, true)));
        fn(g, "luaSpriteAddAnimationByIndices", args -> LuaValue.valueOf(addAnimationByIndices(args, true)));
        fn(g, "addAnimation", args -> LuaValue.valueOf(addAnimationByIndices(args, false)));
        fn(g, "addAnimationBySymbol", args -> LuaValue.valueOf(addAnimationBySymbol(args, false)));
        fn(g, "addAnimationBySymbolIndices", args -> LuaValue.valueOf(addAnimationBySymbol(args, true)));
        fn(g, "playAnim", args -> LuaValue.valueOf(playAnimation(args, false)));
        fn(g, "objectPlayAnimation", args -> LuaValue.valueOf(playAnimation(args, false)));
        fn(g, "luaSpritePlayAnimation", args -> LuaValue.valueOf(playAnimation(args, false)));
        fn(g, "addOffset", args -> { addAnimationOffset(args); return LuaValue.NIL; });
        fn(g, "screenCenter", args -> { LuaObject o = object(args.checkjstring(1)); String axes = args.optjstring(2, "xy");
                if (axes.contains("x")) o.x = (host.psychLuaScreenWidth() - o.width * o.scaleX) / 2; if (axes.contains("y")) o.y = (host.psychLuaScreenHeight() - o.height * o.scaleY) / 2; return LuaValue.NIL; });
        fn(g, "getMidpointX", args -> { String tag = args.checkjstring(1);
                return LuaValue.valueOf(isCharacterTag(tag) ? host.psychLuaCharacterMidpointX(tag)
                        : object(tag).x + object(tag).width / 2); });
        fn(g, "getMidpointY", args -> { String tag = args.checkjstring(1);
                return LuaValue.valueOf(isCharacterTag(tag) ? host.psychLuaCharacterMidpointY(tag)
                        : object(tag).y + object(tag).height / 2); });
        fn(g, "getGraphicMidpointX", args -> { String tag = args.checkjstring(1);
                return LuaValue.valueOf(isCharacterTag(tag) ? host.psychLuaCharacterMidpointX(tag)
                        : object(tag).x + object(tag).width / 2); });
        fn(g, "getGraphicMidpointY", args -> { String tag = args.checkjstring(1);
                return LuaValue.valueOf(isCharacterTag(tag) ? host.psychLuaCharacterMidpointY(tag)
                        : object(tag).y + object(tag).height / 2); });
        fn(g, "getScreenPositionX", args -> { String tag = args.checkjstring(1);
                return LuaValue.valueOf(isCharacterTag(tag) ? host.psychLuaCharacterX(tag) : object(tag).x); });
        fn(g, "getScreenPositionY", args -> { String tag = args.checkjstring(1);
                return LuaValue.valueOf(isCharacterTag(tag) ? host.psychLuaCharacterY(tag) : object(tag).y); });
        fn(g, "objectsOverlap", args -> LuaValue.valueOf(overlap(object(args.checkjstring(1)), object(args.checkjstring(2)))));
        fn(g, "getMouseX", args -> LuaValue.ZERO); fn(g, "getMouseY", args -> LuaValue.ZERO);

        fn(g, "runTimer", args -> { runTimer(args.optjstring(1, ""), args.optdouble(2, 1), args.optint(3, 1)); return LuaValue.NIL; });
        fn(g, "cancelTimer", args -> { timers.remove(args.optjstring(1, "")); return LuaValue.NIL; });
        fn(g, "cancelTween", args -> { tweens.remove(args.optjstring(1, "")); return LuaValue.NIL; });
        // Full-bright / flat (unlit) the main performers at once. Sprites, text, and
        // extra characters use the per-object .fullbright property (or .lighting).
        fn(g, "setFlatShading", args -> { setSceneFlatShading(args.optboolean(1, true)); return LuaValue.NIL; });
        fn(g, "setFullbright", args -> { setSceneFlatShading(args.optboolean(1, true)); return LuaValue.NIL; });
        // Shading is changed through the built-in Directional Shading event so
        // chart events and triggerEvent() always share one canonical path.
        fn(g, "getBlockShading", args -> LuaValue.valueOf(
                com.fnfmod.client.render.DirectionalShadingControl.blockShadingEnabled()));
        fn(g, "getMobShading", args -> LuaValue.valueOf(
                com.fnfmod.client.render.DirectionalShadingControl.entityShadingEnabled()));
        tweenFn(g, "doTweenX", "x"); tweenFn(g, "doTweenY", "y"); tweenFn(g, "doTweenZ", "z");
        tweenFn(g, "doTweenAngle", "angle"); tweenFn(g, "doTweenAlpha", "alpha");
        tweenFn(g, "doTweenAngleX", "rotation.x"); tweenFn(g, "doTweenAngleY", "rotation.y");
        tweenFn(g, "doTweenAngleZ", "rotation.z");
        tweenFn(g, "doTweenRotationX", "rotation.x"); tweenFn(g, "doTweenRotationY", "rotation.y");
        tweenFn(g, "doTweenRotationZ", "rotation.z");
        fn(g, "doTweenZoom", args -> { String tag = args.checkjstring(1); double value = args.optdouble(3, 1);
                double duration = args.optdouble(4, 1); String ease = args.optjstring(5, "linear");
                String camera = args.optjstring(2, "camGame");
                String path = camera.equalsIgnoreCase("hud") || camera.equalsIgnoreCase("camHUD")
                        ? "camHUD.zoom" : "camGame.zoom";
                startTween(tag, path, number(getProperty(path), 1), value, duration, ease); return LuaValue.NIL; });
        noteTweenFn(g, "noteTweenX", "x"); noteTweenFn(g, "noteTweenY", "y");
        noteTweenFn(g, "noteTweenAngle", "angle"); noteTweenFn(g, "noteTweenAlpha", "alpha");
        noteTweenFn(g, "noteTweenDirection", "direction");
        fn(g, "doTweenColor", args -> {
            startColorTween(args.checkjstring(1), args.checkjstring(2), args.optjstring(3, "FFFFFF"),
                    args.optdouble(4, 1), args.optjstring(5, "linear"));
            return LuaValue.valueOf(args.checkjstring(1));
        });
        fn(g, "startTween", args -> {
            startTweenTable(args);
            return LuaValue.valueOf(args.checkjstring(1));
        });

        fn(g, "getRandomInt", args -> LuaValue.valueOf(randomInt(args.optint(1, 0), args.optint(2, Integer.MAX_VALUE), args.optjstring(3, ""))));
        fn(g, "getRandomFloat", args -> LuaValue.valueOf(args.optdouble(1, 0) + random.nextDouble() * (args.optdouble(2, 1) - args.optdouble(1, 0))));
        fn(g, "getRandomBool", args -> LuaValue.valueOf(random.nextDouble() * 100 < args.optdouble(1, 50)));
        fn(g, "stringStartsWith", args -> LuaValue.valueOf(args.checkjstring(1).startsWith(args.checkjstring(2))));
        fn(g, "stringEndsWith", args -> LuaValue.valueOf(args.checkjstring(1).endsWith(args.checkjstring(2))));
        fn(g, "stringTrim", args -> LuaValue.valueOf(args.checkjstring(1).trim()));
        fn(g, "stringSplit", args -> stringArray(args.checkjstring(1).split(java.util.regex.Pattern.quote(args.checkjstring(2)), -1)));
        fn(g, "getColorFromHex", args -> LuaValue.valueOf(color(args.checkjstring(1))));
        fn(g, "getColorFromString", args -> LuaValue.valueOf(color(args.checkjstring(1))));
        fn(g, "getColorFromName", args -> LuaValue.valueOf(color(args.checkjstring(1))));
        fn(g, "FlxColor", args -> LuaValue.valueOf(color(args.checkjstring(1))));
        fn(g, "checkFileExists", args -> LuaValue.valueOf(resolveSafe(args.checkjstring(1)) != null));
        fn(g, "getTextFromFile", args -> LuaValue.valueOf(readSafe(args.checkjstring(1))));
        fn(g, "directoryFileList", args -> directoryList(args.checkjstring(1)));
        fn(g, "saveFile", args -> LuaValue.valueOf(writeSafe(args.checkjstring(1), args.optjstring(2, ""))));
        fn(g, "deleteFile", args -> LuaValue.valueOf(deleteSafe(args.checkjstring(1))));

        // Save data. Psych keeps values in memory until the script flushes them.
        fn(g, "initSaveData", args -> {
            LuaSaveData.init(args.checkjstring(1), args.optjstring(2, "psychenginemods"));
            return LuaValue.NIL;
        });
        fn(g, "flushSaveData", args -> {
            LuaSaveData.flush(args.checkjstring(1), args.optjstring(2, "psychenginemods"));
            return LuaValue.NIL;
        });
        fn(g, "eraseSaveData", args -> {
            LuaSaveData.erase(args.checkjstring(1), args.optjstring(2, "psychenginemods"));
            return LuaValue.NIL;
        });
        fn(g, "getDataFromSave", args -> {
            Object value = LuaSaveData.get(args.checkjstring(1), "psychenginemods", args.checkjstring(2));
            return value == null ? args.arg(3) : toLua(value);
        });
        fn(g, "setDataFromSave", args -> {
            LuaSaveData.set(args.checkjstring(1), "psychenginemods", args.checkjstring(2),
                    fromLua(args.arg(3)));
            return LuaValue.NIL;
        });

        // Achievements are stored in their own slot and flushed as they change.
        fn(g, "achievementExists", args -> LuaValue.valueOf(LuaSaveData.exists(args.checkjstring(1))));
        fn(g, "isAchievementUnlocked", args -> LuaValue.valueOf(LuaSaveData.unlocked(args.checkjstring(1))));
        fn(g, "unlockAchievement", args -> {
            String name = args.checkjstring(1);
            LuaSaveData.unlock(name);
            return LuaValue.valueOf(name);
        });
        fn(g, "getAchievementScore", args -> LuaValue.valueOf(LuaSaveData.score(args.checkjstring(1))));
        fn(g, "setAchievementScore", args -> LuaValue.valueOf(LuaSaveData.setScore(
                args.checkjstring(1), args.optdouble(2, 0), luaBoolean(args, 3, true))));
        fn(g, "addAchievementScore", args -> LuaValue.valueOf(LuaSaveData.addScore(
                args.checkjstring(1), args.optdouble(2, 1), luaBoolean(args, 3, true))));

        // Blockified has no translation tables, so a phrase resolves to the
        // caller's default and asset paths are already in the only language present.
        fn(g, "getTranslationPhrase", args -> {
            String fallback = args.optjstring(2, args.checkjstring(1));
            return LuaValue.valueOf(formatTranslation(fallback, args.arg(3)));
        });
        fn(g, "getFileTranslation", args -> LuaValue.valueOf(args.checkjstring(1)));

        // Discord Rich Presence is not part of Blockified; accept and ignore so
        // shared scripts that advertise a song do not error.
        fn(g, "changeDiscordPresence", args -> LuaValue.NIL);
        fn(g, "changeDiscordClientID", args -> LuaValue.NIL);

        fn(g, "getModSetting", args -> modSetting(args.checkjstring(1), args.optjstring(2, "")));

        // Shaders are unsupported, but these must still return the right kind of
        // value: a script that reads a uniform back would otherwise use nil as a
        // number and error inside its own code rather than here.
        fn(g, "getShaderBool", args -> LuaValue.FALSE);
        fn(g, "getShaderInt", args -> LuaValue.ZERO);
        fn(g, "getShaderFloat", args -> LuaValue.ZERO);
        fn(g, "getShaderBoolArray", args -> new LuaTable());
        fn(g, "getShaderIntArray", args -> new LuaTable());
        fn(g, "getShaderFloatArray", args -> new LuaTable());

        fn(g, "openCustomSubstate", args -> {
            openSubstate(args.checkjstring(1), luaBoolean(args, 2, false));
            return LuaValue.NIL;
        });
        fn(g, "closeCustomSubstate", args -> { closeSubstate(); return LuaValue.NIL; });
        fn(g, "insertToCustomSubstate", args -> {
            insertToSubstate(args.checkjstring(1), args.optint(2, -1));
            return LuaValue.NIL;
        });

        fn(g, "getPropertyFromClass", args -> toLua(
                classProperty(args.checkjstring(1), args.checkjstring(2))));
        fn(g, "setPropertyFromClass", args -> {
            setClassProperty(args.checkjstring(1), args.checkjstring(2), fromLua(args.arg(3)));
            return LuaValue.NIL;
        });
        fn(g, "characterPlayAnim", args -> LuaValue.valueOf(host.psychLuaPlayCharacterAnimation(
                args.checkjstring(1), args.checkjstring(2), args.optboolean(3, false))));
        fn(g, "characterDance", args -> LuaValue.valueOf(
                host.psychLuaCharacterDance(args.optjstring(1, "boyfriend"))));
        fn(g, "getCharacterX", args -> LuaValue.valueOf(
                host.psychLuaCharacterX(args.optjstring(1, "boyfriend"))));
        fn(g, "getCharacterY", args -> LuaValue.valueOf(
                host.psychLuaCharacterY(args.optjstring(1, "boyfriend"))));
        fn(g, "setCharacterX", args -> LuaValue.valueOf(host.psychLuaSetCharacterX(
                args.optjstring(1, "boyfriend"), args.optdouble(2, 0))));
        fn(g, "setCharacterY", args -> LuaValue.valueOf(host.psychLuaSetCharacterY(
                args.optjstring(1, "boyfriend"), args.optdouble(2, 0))));
        fn(g, "addBlockifiedCharacter", args -> LuaValue.valueOf(host.psychLuaAddCharacter(
                args.checkjstring(1), args.checkjstring(2), args.optdouble(3, 0),
                args.optdouble(4, 0), args.optdouble(5, 0), args.optdouble(6, 0),
                args.optjstring(7, "idle"), args.optjstring(8, "player"))));
        fn(g, "makeBlockifiedCharacter", args -> LuaValue.valueOf(host.psychLuaAddCharacter(
                args.checkjstring(1), args.checkjstring(2), args.optdouble(3, 0),
                args.optdouble(4, 0), args.optdouble(5, 0), args.optdouble(6, 0),
                args.optjstring(7, "idle"), args.optjstring(8, "player"))));
        fn(g, "removeBlockifiedCharacter", args -> LuaValue.valueOf(
                host.psychLuaRemoveCharacter(args.checkjstring(1))));
        // Character-to-character pushing. Block collision, gravity and everything
        // else stay untouched, so a performer with collisions off still stands on
        // the ground and simply stops shoving (and being shoved by) other characters.
        fn(g, "setCharacterCollision", args -> LuaValue.valueOf(
                host.psychLuaSetCharacterCollision(args.checkjstring(1), luaBoolean(args, 2, true))));
        fn(g, "getCharacterCollision", args -> LuaValue.valueOf(
                host.psychLuaCharacterCollision(args.checkjstring(1))));
        fn(g, "setCharacterCollisions", args -> LuaValue.valueOf(
                host.psychLuaSetCharacterCollision(args.checkjstring(1), luaBoolean(args, 2, true))));
        fn(g, "setAllCharacterCollisions", args -> {
            host.psychLuaSetAllCharacterCollisions(luaBoolean(args, 1, true));
            return LuaValue.NIL;
        });
        // Drops a performer's stage offset and returns it to its resting spot, so
        // it stops being pinned there after a cancelled tween.
        fn(g, "resetCharacterPosition", args -> LuaValue.valueOf(
                host.psychLuaResetCharacterPosition(args.checkjstring(1))));
        // Vanilla blob shadow under a character. Shadows stay on unless a script
        // turns them off, so existing songs look the same.
        fn(g, "setCharacterShadow", args -> LuaValue.valueOf(
                host.psychLuaSetCharacterShadow(args.checkjstring(1), luaBoolean(args, 2, true))));
        fn(g, "getCharacterShadow", args -> LuaValue.valueOf(
                host.psychLuaCharacterShadow(args.checkjstring(1))));
        fn(g, "setAllCharacterShadows", args -> {
            host.psychLuaSetAllCharacterShadows(luaBoolean(args, 1, true));
            return LuaValue.NIL;
        });
        fn(g, "setCharacterIRLightsShadows", args -> LuaValue.valueOf(
                host.psychLuaSetProperty(args.checkjstring(1) + ".irlightsShadows",
                        luaBoolean(args, 2, true))));
        fn(g, "getCharacterIRLightsShadows", args -> toLua(
                host.psychLuaGetProperty(args.checkjstring(1) + ".irlightsShadows")));
        fn(g, "blockifiedCharacterExists", args -> LuaValue.valueOf(
                host.psychLuaExtraCharacterExists(args.checkjstring(1))));
        fn(g, "setBlockifiedCharacterPosition", args -> LuaValue.valueOf(
                host.psychLuaSetCharacterPosition(args.checkjstring(1), args.optdouble(2, 0),
                        args.optdouble(3, 0), args.optdouble(4, 0))));
        fn(g, "setBlockifiedCharacterRotation", args -> LuaValue.valueOf(
                host.psychLuaSetCharacterRotation(args.checkjstring(1), args.optdouble(2, 0))));
        fn(g, "changeBlockifiedCharacter", args -> LuaValue.valueOf(
                host.psychLuaChangeExtraCharacter(args.checkjstring(1), args.checkjstring(2),
                        args.optjstring(3, ""))));
        fn(g, "getCameraFollowX", args -> toLua(host.psychLuaGetProperty("camFollow.x")));
        fn(g, "getCameraFollowY", args -> toLua(host.psychLuaGetProperty("camFollow.y")));
        fn(g, "setCameraFollowPoint", args -> { host.psychLuaSetProperty("camFollow.x", args.optdouble(1, 0));
                host.psychLuaSetProperty("camFollow.y", args.optdouble(2, 0)); return LuaValue.NIL; });
        fn(g, "addCameraFollowPoint", args -> { host.psychLuaSetProperty("camFollow.x",
                    number(host.psychLuaGetProperty("camFollow.x"), 0) + args.optdouble(1, 0));
                host.psychLuaSetProperty("camFollow.y",
                    number(host.psychLuaGetProperty("camFollow.y"), 0) + args.optdouble(2, 0)); return LuaValue.NIL; });
        fn(g, "getCameraScrollX", args -> toLua(host.psychLuaGetProperty("camGame.scroll.x")));
        fn(g, "getCameraScrollY", args -> toLua(host.psychLuaGetProperty("camGame.scroll.y")));
        fn(g, "setCameraScroll", args -> { host.psychLuaSetProperty("camGame.scroll.x", args.optdouble(1, 0));
                host.psychLuaSetProperty("camGame.scroll.y", args.optdouble(2, 0)); return LuaValue.NIL; });
        fn(g, "addCameraScroll", args -> { host.psychLuaSetProperty("camGame.scroll.x",
                    number(host.psychLuaGetProperty("camGame.scroll.x"), 0) + args.optdouble(1, 0));
                host.psychLuaSetProperty("camGame.scroll.y",
                    number(host.psychLuaGetProperty("camGame.scroll.y"), 0) + args.optdouble(2, 0)); return LuaValue.NIL; });
        fn(g, "setCameraBopEnabled", args -> { GameplayCamera.setBopEnabled(
                args.optjstring(1, "both"), args.optboolean(2, true)); return LuaValue.NIL; });
        fn(g, "setDefaultCameraBop", args -> { GameplayCamera.setBopEnabled(
                args.optjstring(1, "both"), args.optboolean(2, true)); return LuaValue.NIL; });
        fn(g, "getCameraBopEnabled", args -> LuaValue.valueOf(
                GameplayCamera.bopEnabled(args.optjstring(1, "both"))));
        fn(g, "getDefaultCameraBop", args -> LuaValue.valueOf(
                GameplayCamera.bopEnabled(args.optjstring(1, "both"))));
        fn(g, "runHaxeCode", args -> { String code = args.optjstring(1, "");
                if (code.contains("game.boyfriend != null") || code.contains("game.dad != null")
                        || code.contains("game.gf != null")) return LuaValue.TRUE;
                return LuaValue.NIL; });

        fn(g, "setScrollFactor", args -> { LuaObject o = object(args.checkjstring(1));
                o.scrollFactorX = args.optdouble(2, 1); o.scrollFactorY = args.optdouble(3, o.scrollFactorX);
                return LuaValue.NIL; });

        fn(g, "precacheSound", args -> LuaValue.valueOf(soundPlayer.precache(args.optjstring(1, ""))));
        fn(g, "precacheMusic", args -> LuaValue.valueOf(soundPlayer.precache(args.optjstring(1, ""))));
        fn(g, "playSound", args -> {
            String tag = args.optjstring(3, "");
            boolean played = playOrDeferSound(args.optjstring(1, ""), (float) args.optdouble(2, 1),
                    tag, args.optboolean(4, false));
            return played && !tag.isBlank() ? LuaValue.valueOf(tag) : LuaValue.NIL;
        });
        fn(g, "playMusic", args -> LuaValue.valueOf(playOrDeferSound(
                "@music/" + args.optjstring(1, ""), (float) args.optdouble(2, 1),
                "__music", args.optboolean(3, false))));
        fn(g, "stopSound", args -> { String tag = args.optjstring(1, "");
                tag = tag.isBlank() ? "__music" : tag;
                reconstructionSounds.remove(tag); soundPlayer.stop(tag); return LuaValue.NIL; });
        fn(g, "pauseSound", args -> { soundPlayer.pause(args.optjstring(1, "")); return LuaValue.NIL; });
        fn(g, "resumeSound", args -> { soundPlayer.resume(args.optjstring(1, "")); return LuaValue.NIL; });
        fn(g, "luaSoundExists", args -> { String tag = args.optjstring(1, "");
                return LuaValue.valueOf(reconstructionSounds.containsKey(tag) || soundPlayer.exists(tag)); });
        fn(g, "getSoundPitch", args -> LuaValue.valueOf(soundPlayer.pitch(args.optjstring(1, ""))));
        fn(g, "getSoundTime", args -> LuaValue.valueOf(soundPlayer.timeMs(args.optjstring(1, ""))));
        fn(g, "getSoundVolume", args -> { String tag = args.optjstring(1, "");
                DeferredSound sound = reconstructionSounds.get(tag);
                return LuaValue.valueOf(sound == null ? soundPlayer.volume(tag) : sound.volume()); });
        fn(g, "soundFadeIn", args -> {
            soundPlayer.fade(soundTag(args.optjstring(1, "")), (float) args.optdouble(2, 1),
                    (float) args.optdouble(3, 0), (float) args.optdouble(4, 1), false);
            return LuaValue.NIL;
        });
        fn(g, "soundFadeOut", args -> {
            String tag = soundTag(args.optjstring(1, ""));
            soundPlayer.fade(tag, (float) args.optdouble(2, 1), soundPlayer.volume(tag),
                    (float) args.optdouble(3, 0), true);
            return LuaValue.NIL;
        });
        fn(g, "soundFadeCancel", args -> {
            soundPlayer.cancelFade(soundTag(args.optjstring(1, "")));
            return LuaValue.NIL;
        });
        // Psych's music fades are the same ramps aimed at the music channel.
        fn(g, "musicFadeIn", args -> {
            soundPlayer.fade("__music", (float) args.optdouble(1, 1),
                    (float) args.optdouble(2, 0), (float) args.optdouble(3, 1), false);
            return LuaValue.NIL;
        });
        fn(g, "musicFadeOut", args -> {
            soundPlayer.fade("__music", (float) args.optdouble(1, 1),
                    soundPlayer.volume("__music"), (float) args.optdouble(2, 0), true);
            return LuaValue.NIL;
        });
        fn(g, "setSoundPitch", args -> { soundPlayer.setPitch(args.optjstring(1, ""),
                (float) args.optdouble(2, 1)); return LuaValue.NIL; });
        fn(g, "setSoundTime", args -> { soundPlayer.setTimeMs(args.optjstring(1, ""),
                (float) args.optdouble(2, 0)); return LuaValue.NIL; });
        fn(g, "setSoundVolume", args -> { String tag = args.optjstring(1, "");
                float volume = (float) args.optdouble(2, 1);
                DeferredSound sound = reconstructionSounds.get(tag);
                if (sound != null) reconstructionSounds.put(tag,
                        new DeferredSound(sound.name(), volume, sound.tag(), sound.loop()));
                soundPlayer.setVolume(tag, volume); return LuaValue.NIL; });
        fn(g, "setHealthBarColors", args -> {
            host.psychLuaSetProperty("healthBar.leftBar.color", color(args.optjstring(1, "FF0000")));
            host.psychLuaSetProperty("healthBar.rightBar.color", color(args.optjstring(2, "00FF00")));
            return LuaValue.NIL;
        });

        // WARNING: this runs after every real registration above, so a name listed
        // here overwrites its implementation. Only genuinely unsupported Psych
        // functions belong in this list.
        registerNoOps(g,
                // GLSL shaders on Flixel sprites. The setters report failure by
                // returning nil; the getters below return typed empties instead,
                // so a script reading one back does not get a nil arithmetic error.
                "initLuaShader", "setSpriteShader", "removeSpriteShader",
                "setShaderBool", "setShaderInt", "setShaderFloat", "setShaderBoolArray", "setShaderIntArray",
                "setShaderFloatArray", "setShaderSampler2D",
                // HScript needs Haxe's interpreter.
                "addHScript", "removeHScript", "runHaxeFunction", "addHaxeLibrary",
                "callOnHScript", "setOnHScript",
                // Video and dialogue substates.
                "startDialogue", "startVideo",
                // Arbitrary Haxe method calls and class instantiation.
                "callMethod", "callMethodFromClass", "createInstance", "addInstance", "instanceArg",
                // Flixel groups.
                "addToGroup", "removeFromGroup", "updateHitboxFromGroup",
                // Blockified has no time bar or story-mode week to load into.
                "setTimeBarColors", "loadSong", "addCharacterToList",
                // The countdown is driven by the gameplay screen, not by scripts.
                "startCountdown", "close",
                // Controllers are not read during gameplay.
                "gamepadAnalogX", "gamepadAnalogY", "gamepadJustPressed", "gamepadPressed",
                "gamepadReleased",
                // Legacy Blockified aliases kept so old scripts do not error.
                "luaSpriteMakeGraphic", "scaleLuaSprite", "setLuaSpriteCamera", "setLuaSpriteScrollFactor",
                "getPropertyLuaSprite", "setPropertyLuaSprite");
    }

    private void fn(Globals globals, String name, Function<Varargs, LuaValue> function) {
        globals.set(name, new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                BudgetDebugLib budget = scriptBudgets.get(globals);
                if (budget != null) budget.enterHostCall();
                try { return function.apply(args); }
                catch (Throwable error) { warnOnce(name + ": " + compactError(error)); return LuaValue.NIL; }
                finally { if (budget != null) budget.leaveHostCall(); }
            }
        });
    }

    private void registerNoOps(Globals g, String... names) {
        for (String name : names) fn(g, name, args -> LuaValue.NIL);
    }

    private void makeObject(Varargs args, boolean text, boolean animated) {
        String tag = args.checkjstring(1);
        removeObject(tag);
        LuaObject object = object(tag);
        object.textObject = text;
        if (text) {
            object.text = args.optjstring(2, "");
            object.width = args.optdouble(3, 0);
            object.sizeExplicit = object.width > 0;
            object.x = args.optdouble(4, 0);
            object.y = args.optdouble(5, 0);
            // Psych uses five arguments. Blockified Engine accepts an optional
            // sixth Z coordinate for text assigned to the world camera.
            object.z = args.optdouble(6, 0);
        } else {
            object.image = args.optjstring(2, "");
            object.x = args.optdouble(3, 0);
            object.y = args.optdouble(4, 0);
            if (animated) {
                loadObjectAtlas(object, object.image, args.optjstring(5, "auto"));
            } else {
                // Psych uses four arguments. Blockified Engine accepts an optional
                // fifth Z coordinate for sprites assigned to the world camera.
                object.z = args.optdouble(5, 0);
                loadObjectImage(object, object.image);
            }
        }
    }

    private void loadObjectImage(LuaObject object, String imageName) {
        Path png = resolveImage(imageName);
        if (png == null) {
            disposeGraphic(object);
            boolean requested = imageName != null && !imageName.isBlank();
            object.imageBlockedByPolicy = requested && !allows(SongLibrary.ExternalContent.IMAGES);
            object.missingAsset = requested && !object.imageBlockedByPolicy;
            if (object.missingAsset) warnOnce("image not found: " + imageName);
            return;
        }
        SpriteImageCache.Handle handle = null;
        try {
            handle = SpriteImageCache.acquire(png, object.antialiasing);
            if (handle == null) {
                disposeGraphic(object);
                object.imageBlockedByPolicy = false;
                object.missingAsset = true;
                return;
            }
            disposeGraphic(object);
            object.imageBlockedByPolicy = false;
            object.missingAsset = false;
            object.imageHandle = handle;
            object.imageSource = png;
            object.dynamicTexture = handle.dynamicTexture();
            object.texture = handle.textureId();
            object.textureWidth = handle.width();
            object.textureHeight = handle.height();
            object.graphicWidth = handle.width();
            object.graphicHeight = handle.height();
            if (!object.sizeExplicit) {
                object.width = handle.width();
                object.height = handle.height();
            }
            handle = null;
        } catch (Exception error) {
            if (handle != null && object.imageHandle != handle) handle.close();
            disposeGraphic(object);
            object.imageBlockedByPolicy = false;
            object.missingAsset = true;
            warnOnce("image " + imageName + ": " + compactError(error));
        }
    }

    private boolean loadObjectAtlas(LuaObject object, String imageName, String spriteType) {
        object.image = imageName == null ? "" : imageName;
        if (object.image.isBlank()) return false;
        String type = spriteType == null ? "auto" : spriteType.toLowerCase(Locale.ROOT).replace("_", "");
        boolean animateType = type.equals("animate") || type.equals("animateatlas")
                || type.equals("textureatlas") || type.equals("multianimateatlas");
        if (!(type.equals("auto") || type.equals("sparrow") || type.equals("sparrowatlas")
                || type.equals("sparrowv2") || animateType)) {
            warnOnce("animated sprite type '" + spriteType + "' is not supported");
            return false;
        }
        Path animateFolder = assets.animateFolder(imageName);
        if (animateType || type.equals("auto") && animateFolder != null) {
            SparrowAtlas atlas = AnimateAtlas.load(animateFolder);
            if (atlas == null || atlas.allFrames().isEmpty()) {
                if (atlas != null) atlas.close();
                warnOnce("Animate atlas missing or invalid for " + imageName);
                return false;
            }
            atlas.setAntialiasing(object.antialiasing);
            disposeGraphic(object);
            object.image = imageName == null ? "" : imageName;
            object.imageBlockedByPolicy = false;
            object.missingAsset = false;
            object.atlas = atlas;
            object.texture = atlas.texture();
            object.textureWidth = atlas.width();
            object.textureHeight = atlas.height();
            SparrowAtlas.Frame first = atlas.allFrames().get(0);
            object.graphicWidth = Math.max(1, first.frameW);
            object.graphicHeight = Math.max(1, first.frameH);
            if (!object.sizeExplicit) {
                object.width = object.graphicWidth;
                object.height = object.graphicHeight;
            }
            return true;
        }
        Path png = resolveImage(imageName);
        if (png == null) {
            disposeGraphic(object);
            object.imageBlockedByPolicy = !allows(SongLibrary.ExternalContent.IMAGES);
            object.missingAsset = !object.imageBlockedByPolicy;
            if (object.missingAsset) warnOnce("animated image not found: " + imageName);
            return false;
        }
        String filename = png.getFileName().toString();
        int dot = filename.lastIndexOf('.');
        Path xml = png.resolveSibling((dot < 0 ? filename : filename.substring(0, dot)) + ".xml");
        SparrowAtlas atlas = SpriteAtlasCache.acquire(png, xml, object.antialiasing);
        if (atlas == null || atlas.allFrames().isEmpty()) {
            if (atlas != null) atlas.close();
            warnOnce("Sparrow XML missing or empty for " + imageName + " (expected " + xml.getFileName() + ")");
            loadObjectImage(object, imageName);
            return false;
        }
        atlas.setAntialiasing(object.antialiasing);
        disposeGraphic(object);
        object.imageBlockedByPolicy = false;
        object.missingAsset = false;
        object.atlas = atlas;
        object.atlasPng = png;
        object.atlasXml = xml;
        object.texture = atlas.texture();
        object.textureWidth = atlas.width();
        object.textureHeight = atlas.height();
        SparrowAtlas.Frame first = atlas.allFrames().get(0);
        object.graphicWidth = Math.max(1, first.frameW);
        object.graphicHeight = Math.max(1, first.frameH);
        if (!object.sizeExplicit) {
            object.width = object.graphicWidth;
            object.height = object.graphicHeight;
        }
        return true;
    }

    /**
     * Psych's loadMultipleFrames: merges extra Sparrow sheets into one sprite so
     * later addAnimationByPrefix calls can name symbols from any of them. The
     * first sheet stays the object's primary graphic; each animation remembers
     * which sheet its frames came from so they blit from the right texture.
     */
    private boolean loadMultipleFrames(Varargs args) {
        LuaObject object = object(args.checkjstring(1));
        List<String> images = new ArrayList<>();
        LuaValue second = args.arg(2);
        if (second.istable()) {
            for (int i = 1; i <= second.length(); i++) images.add(second.get(i).tojstring());
        } else {
            for (int i = 2; i <= args.narg(); i++) images.add(args.checkjstring(i));
        }
        if (images.isEmpty()) return false;

        for (SparrowAtlas extra : object.extraAtlases) extra.close();
        object.extraAtlases.clear();
        if (!loadObjectAtlas(object, images.get(0), "auto")) return false;

        for (int i = 1; i < images.size(); i++) {
            SparrowAtlas extra = loadAtlas(images.get(i), object.antialiasing);
            if (extra == null) {
                warnOnce("loadMultipleFrames: no Sparrow XML for " + images.get(i));
                continue;
            }
            extra.setAntialiasing(object.antialiasing);
            object.extraAtlases.add(extra);
        }
        return true;
    }

    /** Loads one Sparrow sheet by image name without binding it to an object. */
    private SparrowAtlas loadAtlas(String imageName, boolean antialiasing) {
        Path png = resolveImage(imageName);
        if (png == null) return null;
        String filename = png.getFileName().toString();
        int dot = filename.lastIndexOf('.');
        Path xml = png.resolveSibling((dot < 0 ? filename : filename.substring(0, dot)) + ".xml");
        SparrowAtlas atlas = SpriteAtlasCache.acquire(png, xml, antialiasing);
        if (atlas != null && atlas.allFrames().isEmpty()) {
            atlas.close();
            return null;
        }
        return atlas;
    }

    /**
     * Psych's updateHitbox: resets the drawn size to the source graphic scaled by
     * the sprite's scale, undoing an earlier setGraphicSize.
     */
    private static void updateHitbox(LuaObject object) {
        object.width = Math.max(1, object.graphicWidth) * Math.abs(object.scaleX);
        object.height = Math.max(1, object.graphicHeight) * Math.abs(object.scaleY);
        object.sizeExplicit = true;
    }

    /**
     * Psych's getPixelColor, returning packed ARGB from the sprite's source image.
     * Reads the atlas sheet or the uploaded graphic, so it works for both static
     * and animated Lua sprites.
     */
    private int pixelColor(String tag, int x, int y) {
        LuaObject object = objects.get(tag);
        if (object == null) return 0;
        NativeImage image = object.atlas != null ? object.atlas.image()
                : object.dynamicTexture == null ? null : object.dynamicTexture.getPixels();
        if (image == null) return 0;
        if (x < 0 || y < 0 || x >= image.getWidth() || y >= image.getHeight()) return 0;
        // NativeImage packs ABGR; Psych scripts expect ARGB.
        int abgr = image.getPixelRGBA(x, y);
        int alpha = abgr >>> 24;
        int blue = (abgr >> 16) & 0xFF;
        int green = (abgr >> 8) & 0xFF;
        int red = abgr & 0xFF;
        return alpha << 24 | red << 16 | green << 8 | blue;
    }

    /**
     * Psych's precacheImage. Loading the sheet now moves the disk read and GPU
     * upload off the first frame that shows it, which is the whole point of the call.
     */
    private void precacheImage(String imageName) {
        if (imageName == null || imageName.isBlank()) return;
        Path png = resolveImage(imageName);
        if (png == null) {
            if (allows(SongLibrary.ExternalContent.IMAGES)) {
                warnOnce("precacheImage: image not found: " + imageName);
            }
            return;
        }
        String filename = png.getFileName().toString();
        int dot = filename.lastIndexOf('.');
        Path xml = png.resolveSibling((dot < 0 ? filename : filename.substring(0, dot)) + ".xml");
        if (Files.isRegularFile(xml)) SpriteAtlasCache.warm(png, xml, true);
        else SpriteImageCache.warm(png, true);
    }

    /**
     * Looks a symbol prefix up across the sprite's primary sheet and any sheets
     * added by loadMultipleFrames, so an animation can name a symbol from any of them.
     */
    private static List<SparrowAtlas.Frame> framesByPrefix(LuaObject object, String prefix) {
        List<SparrowAtlas.Frame> frames = object.atlas == null
                ? List.of() : object.atlas.framesByPrefix(prefix);
        if (!frames.isEmpty()) return frames;
        for (SparrowAtlas extra : object.extraAtlases) {
            List<SparrowAtlas.Frame> found = extra.framesByPrefix(prefix);
            if (!found.isEmpty()) return found;
        }
        return List.of();
    }

    private static List<SparrowAtlas.Frame> framesByIndices(
            LuaObject object, String prefix, List<Integer> indices) {
        List<SparrowAtlas.Frame> frames = object.atlas == null
                ? List.of() : object.atlas.framesByIndices(prefix, indices);
        if (!frames.isEmpty()) return frames;
        for (SparrowAtlas extra : object.extraAtlases) {
            List<SparrowAtlas.Frame> found = extra.framesByIndices(prefix, indices);
            if (!found.isEmpty()) return found;
        }
        return List.of();
    }

    private static void setGraphicSize(LuaObject object, double requestedWidth, double requestedHeight) {
        double sourceWidth = Math.max(1, object.graphicWidth);
        double sourceHeight = Math.max(1, object.graphicHeight);
        if (requestedWidth > 0 && requestedHeight > 0) {
            object.width = requestedWidth;
            object.height = requestedHeight;
        } else if (requestedWidth > 0) {
            object.width = requestedWidth;
            object.height = requestedWidth * sourceHeight / sourceWidth;
        } else if (requestedHeight > 0) {
            object.height = requestedHeight;
            object.width = requestedHeight * sourceWidth / sourceHeight;
        }
        object.sizeExplicit = true;
    }

    private boolean addAnimationByPrefix(Varargs args) {
        LuaObject object = objects.get(args.checkjstring(1));
        if (object == null || object.atlas == null) return false;
        String name = args.checkjstring(2);
        List<SparrowAtlas.Frame> frames = framesByPrefix(object, args.checkjstring(3));
        if (frames.isEmpty()) {
            warnOnce("animation prefix '" + args.checkjstring(3) + "' not found in " + object.image);
            return false;
        }
        putAnimation(object, name, frames, args.optdouble(4, 24), luaBoolean(args, 5, true));
        return true;
    }

    private boolean addAnimationByIndices(Varargs args, boolean withPrefix) {
        LuaObject object = objects.get(args.checkjstring(1));
        if (object == null || object.atlas == null) return false;
        String name = args.checkjstring(2);
        int indicesArg = withPrefix ? 4 : 3;
        List<Integer> indices = luaIndices(args.arg(indicesArg));
        List<SparrowAtlas.Frame> frames;
        if (withPrefix) {
            frames = framesByIndices(object, args.checkjstring(3), indices);
        } else {
            List<SparrowAtlas.Frame> source = object.atlas.allFrames();
            frames = new ArrayList<>();
            for (int index : indices) if (index >= 0 && index < source.size()) frames.add(source.get(index));
        }
        if (frames.isEmpty()) {
            warnOnce("animation '" + name + "' has no valid frames in " + object.image);
            return false;
        }
        int rateArg = indicesArg + 1;
        boolean defaultLoop = !withPrefix;
        putAnimation(object, name, frames, args.optdouble(rateArg, 24),
                luaBoolean(args, rateArg + 1, defaultLoop));
        return true;
    }

    private void putAnimation(LuaObject object, String name, List<SparrowAtlas.Frame> frames,
                              double frameRate, boolean looped) {
        putAnimation(object, name, frames, frameRate, looped, sheetOf(object, frames));
    }

    private void putAnimation(LuaObject object, String name, List<SparrowAtlas.Frame> frames,
                              double frameRate, boolean looped, SparrowAtlas sheet) {
        LuaAnimation animation = new LuaAnimation();
        animation.name = name;
        animation.frames = List.copyOf(frames);
        animation.frameRate = Math.max(0, frameRate);
        animation.looped = looped;
        if (sheet != null) {
            animation.sheet = sheet;
            animation.texture = sheet.texture();
            animation.textureWidth = sheet.width();
            animation.textureHeight = sheet.height();
        }
        object.animations.put(name, animation);
        if (object.currentAnimation == null) startAnimation(object, name, true, false, 0);
    }

    /** Finds which of the object's sheets these frames came from, primary first. */
    private static SparrowAtlas sheetOf(LuaObject object, List<SparrowAtlas.Frame> frames) {
        if (frames.isEmpty()) return object.atlas;
        SparrowAtlas.Frame first = frames.get(0);
        if (object.atlas != null && object.atlas.allFrames().contains(first)) return object.atlas;
        for (SparrowAtlas extra : object.extraAtlases) {
            if (extra.allFrames().contains(first)) return extra;
        }
        return object.atlas;
    }

    private boolean playAnimation(Varargs args, boolean defaultForced) {
        String tag = args.checkjstring(1);
        boolean forced = luaBoolean(args, 3, defaultForced);
        String animation = args.checkjstring(2);
        // Psych resolves a created Lua object before reflecting built-in character
        // fields. This is essential for ordinary tags such as back_bf/back_gf:
        // Blockified's Minecraft character bridge intentionally maps unknown role
        // names to BF, so asking the bridge first consumed the sprite call.
        LuaObject object = objects.get(tag);
        if (object != null) {
            return startAnimation(object, animation, forced,
                    luaBoolean(args, 4, false), args.optint(5, 0));
        }
        return host.psychLuaPlayCharacterAnimation(tag, animation, forced);
    }

    /** Psych's FlxAnimate symbol APIs. */
    private boolean addAnimationBySymbol(Varargs args, boolean withIndices) {
        LuaObject object = objects.get(args.checkjstring(1));
        if (object == null || object.atlas == null) return false;
        String name = args.checkjstring(2);
        String symbol = args.checkjstring(3);
        List<SparrowAtlas.Frame> frames = object.atlas.framesBySymbol(symbol);
        int rateArg = 4;
        if (withIndices) {
            List<Integer> indices = luaIndices(args.arg(4));
            List<SparrowAtlas.Frame> selected = new ArrayList<>();
            for (Integer index : indices) {
                if (index != null && index >= 0 && index < frames.size()) selected.add(frames.get(index));
            }
            frames = selected;
            rateArg = 5;
        }
        if (frames.isEmpty()) {
            warnOnce("Animate symbol '" + symbol + "' not found in " + object.image);
            return false;
        }
        putAnimation(object, name, frames, args.optdouble(rateArg, 24),
                luaBoolean(args, rateArg + 1, false), object.atlas);
        return true;
    }

    private boolean startAnimation(LuaObject object, String name, boolean forced, boolean reverse, int startFrame) {
        LuaAnimation animation = object.animations.get(name);
        if (animation == null || animation.frames.isEmpty()) return false;
        if (!forced && name.equals(object.currentAnimation) && !object.animationFinished) return true;
        object.currentAnimation = name;
        object.animationFrame = Math.max(0, Math.min(animation.frames.size() - 1, startFrame));
        object.animationElapsed = 0;
        object.animationReverse = reverse;
        object.animationFinished = false;
        return true;
    }

    private void addAnimationOffset(Varargs args) {
        LuaObject object = objects.get(args.checkjstring(1));
        if (object == null) return;
        object.animationOffsets.put(args.checkjstring(2),
                new double[]{args.optdouble(3, 0), args.optdouble(4, 0)});
    }

    private static List<Integer> luaIndices(LuaValue value) {
        List<Integer> out = new ArrayList<>();
        if (value == null || value.isnil()) {
            out.add(0);
        } else if (value.istable()) {
            for (int i = 1; i <= value.length(); i++) {
                LuaValue item = value.get(i);
                if (item.isnumber()) out.add(item.toint());
            }
        } else {
            for (String part : value.tojstring().split(",")) {
                try { out.add(Integer.parseInt(part.trim())); } catch (NumberFormatException ignored) {}
            }
        }
        return out;
    }

    private static boolean luaBoolean(Varargs args, int index, boolean fallback) {
        LuaValue value = args.arg(index);
        return value.isnil() ? fallback : value.toboolean();
    }

    private LuaObject object(String tag) {
        return objects.computeIfAbsent(tag, key -> { LuaObject o = new LuaObject(); o.tag = key; return o; });
    }

    private void removeObject(String tag) {
        LuaObject object = objects.remove(tag);
        if (object != null) disposeGraphic(object);
        removeWorldSpriteEntity(tag);
    }

    private void disposeGraphic(LuaObject object) {
        if (object.bakedTexture != null || object.bakedDynamic != null) {
            WorldTextTextures.release(object.bakedTexture, object.bakedDynamic);
            object.bakedTexture = null;
            object.bakedDynamic = null;
            object.bakedKey = null;
        }
        if (object.imageHandle != null) {
            object.imageHandle.close();
            object.imageHandle = null;
            object.dynamicTexture = null;
        } else if (object.dynamicTexture != null) {
            if (object.texture != null) {
                Minecraft.getInstance().getTextureManager().release(object.texture);
            } else {
                object.dynamicTexture.close();
            }
            object.dynamicTexture = null;
        }
        if (object.atlas != null) {
            object.atlas.close();
            object.atlas = null;
        }
        for (SparrowAtlas extra : object.extraAtlases) extra.close();
        object.extraAtlases.clear();
        object.imageSource = null;
        object.atlasPng = null;
        object.atlasXml = null;
        object.texture = null;
        object.textureWidth = 0;
        object.textureHeight = 0;
        object.animations.clear();
        object.animationOffsets.clear();
        object.currentAnimation = null;
        object.animationFrame = 0;
        object.animationElapsed = 0;
        object.animationFinished = false;
    }

    private static boolean overlap(LuaObject a, LuaObject b) {
        return a.x < b.x + b.width && a.x + a.width > b.x
                && a.y < b.y + b.height && a.y + a.height > b.y;
    }

    private boolean isCharacterTag(String raw) {
        if (raw == null) return false;
        if (host.psychLuaExtraCharacterExists(raw)) return true;
        String tag = raw.toLowerCase(Locale.ROOT);
        return tag.equals("boyfriend") || tag.equals("boyfriendgroup")
                || tag.equals("bf") || tag.equals("player")
                || tag.equals("dad") || tag.equals("dadgroup")
                || tag.equals("opponent") || tag.equals("opponentgroup")
                || tag.equals("gf") || tag.equals("gfgroup")
                || tag.equals("girlfriend") || tag.equals("girlfriendgroup")
                || tag.equals("speakers");
    }

    private void setTargetAntialiasing(String tag, boolean enabled) {
        if (isCharacterTag(tag)) host.psychLuaSetProperty(tag + ".antialiasing", enabled);
        else setAntialiasing(object(tag), enabled);
    }

    private void setCharacterGraphicSize(String tag, double width, double height) {
        double sourceWidth = number(host.psychLuaGetProperty(tag + ".width"), 0);
        double sourceHeight = number(host.psychLuaGetProperty(tag + ".height"), 0);
        if (sourceWidth <= 0 || sourceHeight <= 0 || width <= 0) return;
        double scaleX = width / sourceWidth;
        double scaleY = height > 0 ? height / sourceHeight : scaleX;
        host.psychLuaSetProperty(tag + ".scale.x", scaleX);
        host.psychLuaSetProperty(tag + ".scale.y", scaleY);
    }

    private Script findScript(String query) {
        if (query == null) return null;
        String wanted = query.replace('\\', '/').toLowerCase(Locale.ROOT);
        for (Script script : scripts) {
            String full = script.path.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
            String file = script.path.getFileName().toString().toLowerCase(Locale.ROOT);
            if (full.endsWith(wanted) || file.equals(wanted) || file.equals(wanted + ".lua")) return script;
        }
        return null;
    }

    private Object callSpecific(String scriptName, String functionName, Object[] args) {
        Script script = findScript(scriptName);
        if (script == null) return FUNCTION_CONTINUE;
        try {
            LuaValue function = script.globals.get(functionName);
            if (!function.isfunction()) return FUNCTION_CONTINUE;
            LuaValue[] values = new LuaValue[args.length];
            for (int i = 0; i < args.length; i++) values[i] = toLua(args[i]);
            script.budget.begin();
            return fromLua(function.invoke(LuaValue.varargsOf(values)).arg1());
        } catch (Throwable error) {
            warnOnce(script.path.getFileName() + " " + functionName + ": " + compactError(error));
            return FUNCTION_CONTINUE;
        } finally {
            script.budget.end();
        }
    }

    private static Object[] luaArgs(LuaValue value) {
        if (!value.istable()) return new Object[0];
        LuaTable table = value.checktable();
        Object[] result = new Object[table.length()];
        for (int i = 0; i < result.length; i++) result[i] = fromLua(table.get(i + 1));
        return result;
    }

    private boolean keyDown(int key) {
        if (key < 0) return false;
        queriedKeys.add(key);
        long window = Minecraft.getInstance().getWindow().getWindow();
        return GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS;
    }

    private boolean keyboardDown(String name) {
        for (int key : physicalKeyCodes(name)) if (keyDown(key)) return true;
        return false;
    }

    private boolean keyboardJustPressed(String name) {
        for (int key : physicalKeyCodes(name)) {
            if (keyDown(key) && !previousKeys.getOrDefault(key, false)) return true;
        }
        return false;
    }

    private boolean keyboardReleased(String name) {
        for (int key : physicalKeyCodes(name)) {
            if (!keyDown(key) && previousKeys.getOrDefault(key, false)) return true;
        }
        return false;
    }

    private static int[] physicalKeyCodes(String raw) {
        String key = controlName(raw);
        return switch (key) {
            case "SHIFT" -> new int[]{GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT};
            case "CONTROL", "CTRL" -> new int[]{GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL};
            case "ALT" -> new int[]{GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT};
            case "SUPER", "META", "WINDOWS" -> new int[]{GLFW.GLFW_KEY_LEFT_SUPER, GLFW.GLFW_KEY_RIGHT_SUPER};
            default -> new int[]{keyCode(key)};
        };
    }

    private static String controlName(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    private boolean controlDown(String raw) {
        String control = controlName(raw);
        queriedControls.add(control);
        return switch (control) {
            // Psych's short direction names are note controls, not arrow keys.
            case "LEFT", "NOTE_LEFT" -> FnfKeys.NOTE_LEFT.isDown();
            case "DOWN", "NOTE_DOWN" -> FnfKeys.NOTE_DOWN.isDown();
            case "UP", "NOTE_UP" -> FnfKeys.NOTE_UP.isDown();
            case "RIGHT", "NOTE_RIGHT" -> FnfKeys.NOTE_RIGHT.isDown();
            case "UI_LEFT" -> keyDown(GLFW.GLFW_KEY_LEFT) || FnfKeys.NOTE_LEFT.isDown();
            case "UI_DOWN" -> keyDown(GLFW.GLFW_KEY_DOWN) || FnfKeys.NOTE_DOWN.isDown();
            case "UI_UP" -> keyDown(GLFW.GLFW_KEY_UP) || FnfKeys.NOTE_UP.isDown();
            case "UI_RIGHT" -> keyDown(GLFW.GLFW_KEY_RIGHT) || FnfKeys.NOTE_RIGHT.isDown();
            case "ACCEPT" -> keyDown(GLFW.GLFW_KEY_SPACE) || keyDown(GLFW.GLFW_KEY_ENTER)
                    || keyDown(GLFW.GLFW_KEY_KP_ENTER);
            case "BACK" -> keyDown(GLFW.GLFW_KEY_BACKSPACE) || keyDown(GLFW.GLFW_KEY_ESCAPE);
            case "PAUSE" -> keyDown(GLFW.GLFW_KEY_ENTER) || keyDown(GLFW.GLFW_KEY_ESCAPE);
            case "RESET" -> keyDown(GLFW.GLFW_KEY_R);
            case "VOLUME_MUTE" -> keyDown(GLFW.GLFW_KEY_0);
            case "VOLUME_UP" -> FnfKeys.VOLUME_UP.isDown();
            case "VOLUME_DOWN" -> FnfKeys.VOLUME_DOWN.isDown();
            case "DEBUG_1" -> keyDown(GLFW.GLFW_KEY_7);
            case "DEBUG_2" -> keyDown(GLFW.GLFW_KEY_8);
            default -> keyDown(keyCode(control));
        };
    }

    private static int keyCode(String raw) {
        if (raw == null || raw.isBlank()) return -1;
        String key = raw.trim().toUpperCase(Locale.ROOT).replace(" ", "_");
        if (key.length() == 1) {
            char c = key.charAt(0);
            if (c >= 'A' && c <= 'Z') return GLFW.GLFW_KEY_A + c - 'A';
            if (c >= '0' && c <= '9') return GLFW.GLFW_KEY_0 + c - '0';
        }
        return switch (key) {
            case "APOSTROPHE", "QUOTE" -> GLFW.GLFW_KEY_APOSTROPHE;
            case "COMMA" -> GLFW.GLFW_KEY_COMMA;
            case "MINUS", "DASH" -> GLFW.GLFW_KEY_MINUS;
            case "PERIOD", "DOT" -> GLFW.GLFW_KEY_PERIOD;
            case "SLASH" -> GLFW.GLFW_KEY_SLASH;
            case "ZERO" -> GLFW.GLFW_KEY_0; case "ONE" -> GLFW.GLFW_KEY_1;
            case "TWO" -> GLFW.GLFW_KEY_2; case "THREE" -> GLFW.GLFW_KEY_3;
            case "FOUR" -> GLFW.GLFW_KEY_4; case "FIVE" -> GLFW.GLFW_KEY_5;
            case "SIX" -> GLFW.GLFW_KEY_6; case "SEVEN" -> GLFW.GLFW_KEY_7;
            case "EIGHT" -> GLFW.GLFW_KEY_8; case "NINE" -> GLFW.GLFW_KEY_9;
            case "SEMICOLON" -> GLFW.GLFW_KEY_SEMICOLON;
            case "EQUAL", "PLUS" -> GLFW.GLFW_KEY_EQUAL;
            case "LEFT_BRACKET", "LBRACKET", "OPEN_BRACKET" -> GLFW.GLFW_KEY_LEFT_BRACKET;
            case "BACKSLASH" -> GLFW.GLFW_KEY_BACKSLASH;
            case "RIGHT_BRACKET", "RBRACKET", "CLOSE_BRACKET" -> GLFW.GLFW_KEY_RIGHT_BRACKET;
            case "GRAVE_ACCENT", "GRAVE", "BACKQUOTE", "TILDE" -> GLFW.GLFW_KEY_GRAVE_ACCENT;
            case "LEFT" -> GLFW.GLFW_KEY_LEFT; case "RIGHT" -> GLFW.GLFW_KEY_RIGHT;
            case "UP" -> GLFW.GLFW_KEY_UP; case "DOWN" -> GLFW.GLFW_KEY_DOWN;
            case "SPACE" -> GLFW.GLFW_KEY_SPACE; case "ENTER", "RETURN" -> GLFW.GLFW_KEY_ENTER;
            case "ESC", "ESCAPE" -> GLFW.GLFW_KEY_ESCAPE; case "TAB" -> GLFW.GLFW_KEY_TAB;
            case "SHIFT", "LEFT_SHIFT", "LSHIFT" -> GLFW.GLFW_KEY_LEFT_SHIFT;
            case "RIGHT_SHIFT", "RSHIFT" -> GLFW.GLFW_KEY_RIGHT_SHIFT;
            case "CONTROL", "CTRL", "LEFT_CONTROL", "LEFT_CTRL", "LCTRL" -> GLFW.GLFW_KEY_LEFT_CONTROL;
            case "RIGHT_CONTROL", "RIGHT_CTRL", "RCTRL" -> GLFW.GLFW_KEY_RIGHT_CONTROL;
            case "ALT", "LEFT_ALT", "LALT" -> GLFW.GLFW_KEY_LEFT_ALT;
            case "RIGHT_ALT", "RALT", "ALT_GR" -> GLFW.GLFW_KEY_RIGHT_ALT;
            case "LEFT_SUPER", "LEFT_META", "LEFT_WINDOWS" -> GLFW.GLFW_KEY_LEFT_SUPER;
            case "RIGHT_SUPER", "RIGHT_META", "RIGHT_WINDOWS" -> GLFW.GLFW_KEY_RIGHT_SUPER;
            case "MENU" -> GLFW.GLFW_KEY_MENU;
            case "BACKSPACE" -> GLFW.GLFW_KEY_BACKSPACE;
            case "INSERT" -> GLFW.GLFW_KEY_INSERT;
            case "DELETE" -> GLFW.GLFW_KEY_DELETE; case "HOME" -> GLFW.GLFW_KEY_HOME;
            case "END" -> GLFW.GLFW_KEY_END; case "PAGEUP", "PAGE_UP" -> GLFW.GLFW_KEY_PAGE_UP;
            case "PAGEDOWN", "PAGE_DOWN" -> GLFW.GLFW_KEY_PAGE_DOWN;
            case "CAPS_LOCK", "CAPSLOCK" -> GLFW.GLFW_KEY_CAPS_LOCK;
            case "SCROLL_LOCK", "SCROLLLOCK" -> GLFW.GLFW_KEY_SCROLL_LOCK;
            case "NUM_LOCK", "NUMLOCK" -> GLFW.GLFW_KEY_NUM_LOCK;
            case "PRINT_SCREEN", "PRINTSCREEN" -> GLFW.GLFW_KEY_PRINT_SCREEN;
            case "PAUSE" -> GLFW.GLFW_KEY_PAUSE;
            case "F1" -> GLFW.GLFW_KEY_F1; case "F2" -> GLFW.GLFW_KEY_F2; case "F3" -> GLFW.GLFW_KEY_F3;
            case "F4" -> GLFW.GLFW_KEY_F4; case "F5" -> GLFW.GLFW_KEY_F5; case "F6" -> GLFW.GLFW_KEY_F6;
            case "F7" -> GLFW.GLFW_KEY_F7; case "F8" -> GLFW.GLFW_KEY_F8; case "F9" -> GLFW.GLFW_KEY_F9;
            case "F10" -> GLFW.GLFW_KEY_F10; case "F11" -> GLFW.GLFW_KEY_F11; case "F12" -> GLFW.GLFW_KEY_F12;
            case "F13" -> GLFW.GLFW_KEY_F13; case "F14" -> GLFW.GLFW_KEY_F14; case "F15" -> GLFW.GLFW_KEY_F15;
            case "F16" -> GLFW.GLFW_KEY_F16; case "F17" -> GLFW.GLFW_KEY_F17; case "F18" -> GLFW.GLFW_KEY_F18;
            case "F19" -> GLFW.GLFW_KEY_F19; case "F20" -> GLFW.GLFW_KEY_F20; case "F21" -> GLFW.GLFW_KEY_F21;
            case "F22" -> GLFW.GLFW_KEY_F22; case "F23" -> GLFW.GLFW_KEY_F23; case "F24" -> GLFW.GLFW_KEY_F24;
            case "F25" -> GLFW.GLFW_KEY_F25;
            case "NUMPADZERO", "NUMPAD_0", "KP_0" -> GLFW.GLFW_KEY_KP_0;
            case "NUMPADONE", "NUMPAD_1", "KP_1" -> GLFW.GLFW_KEY_KP_1;
            case "NUMPADTWO", "NUMPAD_2", "KP_2" -> GLFW.GLFW_KEY_KP_2;
            case "NUMPADTHREE", "NUMPAD_3", "KP_3" -> GLFW.GLFW_KEY_KP_3;
            case "NUMPADFOUR", "NUMPAD_4", "KP_4" -> GLFW.GLFW_KEY_KP_4;
            case "NUMPADFIVE", "NUMPAD_5", "KP_5" -> GLFW.GLFW_KEY_KP_5;
            case "NUMPADSIX", "NUMPAD_6", "KP_6" -> GLFW.GLFW_KEY_KP_6;
            case "NUMPADSEVEN", "NUMPAD_7", "KP_7" -> GLFW.GLFW_KEY_KP_7;
            case "NUMPADEIGHT", "NUMPAD_8", "KP_8" -> GLFW.GLFW_KEY_KP_8;
            case "NUMPADNINE", "NUMPAD_9", "KP_9" -> GLFW.GLFW_KEY_KP_9;
            case "NUMPADDECIMAL", "NUMPAD_DECIMAL", "KP_DECIMAL" -> GLFW.GLFW_KEY_KP_DECIMAL;
            case "NUMPADDIVIDE", "NUMPAD_DIVIDE", "KP_DIVIDE" -> GLFW.GLFW_KEY_KP_DIVIDE;
            case "NUMPADMULTIPLY", "NUMPAD_MULTIPLY", "KP_MULTIPLY" -> GLFW.GLFW_KEY_KP_MULTIPLY;
            case "NUMPADMINUS", "NUMPAD_SUBTRACT", "KP_SUBTRACT" -> GLFW.GLFW_KEY_KP_SUBTRACT;
            case "NUMPADPLUS", "NUMPAD_ADD", "KP_ADD" -> GLFW.GLFW_KEY_KP_ADD;
            case "NUMPADENTER", "NUMPAD_ENTER", "KP_ENTER" -> GLFW.GLFW_KEY_KP_ENTER;
            case "NUMPADEQUAL", "NUMPAD_EQUAL", "KP_EQUAL" -> GLFW.GLFW_KEY_KP_EQUAL;
            default -> -1;
        };
    }

    private static boolean mouseDown(String raw) {
        String button = raw == null ? "left" : raw.toLowerCase(Locale.ROOT);
        int code = button.contains("right") ? GLFW.GLFW_MOUSE_BUTTON_RIGHT
                : button.contains("middle") ? GLFW.GLFW_MOUSE_BUTTON_MIDDLE : GLFW.GLFW_MOUSE_BUTTON_LEFT;
        return GLFW.glfwGetMouseButton(Minecraft.getInstance().getWindow().getWindow(), code) == GLFW.GLFW_PRESS;
    }

    private Object getProperty(String path) {
        Object hostValue = host.psychLuaGetProperty(path);
        if (hostValue != null) return hostValue;
        int dot = path.indexOf('.');
        String tag = dot < 0 ? path : path.substring(0, dot);
        LuaObject object = objects.get(tag);
        if (object == null) return sharedVars.containsKey(path) ? fromLua(sharedVars.get(path)) : null;
        return objectProperty(object, dot < 0 ? "" : path.substring(dot + 1));
    }

    private void setProperty(String path, Object value) {
        if (host.psychLuaSetProperty(path, value)) return;
        int dot = path.indexOf('.');
        if (dot < 0) { sharedVars.put(path, toLua(value)); return; }
        LuaObject object = objects.get(path.substring(0, dot));
        if (object != null) {
            setObjectProperty(object, path.substring(dot + 1), value);
            syncObjectBorder(object);
        }
        else sharedVars.put(path, toLua(value));
    }

    /** Entry point for Psych's built-in Set Property event. */
    public void setPropertyFromEvent(String path, Object value) {
        if (path == null || path.isBlank()) return;
        setProperty(path.trim(), value);
    }

    private static Object objectProperty(LuaObject o, String property) {
        LuaAnimation animation = currentAnimation(o);
        double[] offset = currentAnimationOffset(o);
        return switch (property) {
            case "x" -> o.x; case "y" -> o.y; case "z" -> o.z;
            case "width" -> o.width; case "height" -> o.height;
            case "alpha" -> o.alpha; case "angle" -> o.angle; case "visible" -> o.visible;
            case "antialiasing" -> o.antialiasing;
            case "rotation.x", "rotationX", "angleX" -> o.rotationX;
            case "rotation.y", "rotationY", "angleY" -> o.rotationY;
            case "rotation.z", "rotationZ", "angleZ" -> o.angle;
            case "color" -> o.color; case "text" -> o.text; case "scale.x" -> o.scaleX;
            case "borderSize", "objectBorderSize" -> o.textObject ? o.borderSize : o.objectBorderSize;
            case "borderColor", "objectBorderColor" -> o.textObject ? o.borderColor : o.objectBorderColor;
            case "scale.y" -> o.scaleY; case "offset.x" -> offset[0]; case "offset.y" -> offset[1];
            case "scrollFactor.x" -> o.scrollFactorX; case "scrollFactor.y" -> o.scrollFactorY;
            case "billboard", "worldBillboard", "alwaysFaceCamera" -> o.worldBillboard;
            case "lighting", "worldLighting", "affectedByLighting" -> o.worldLighting;
            case "fullbright", "fullBright", "unlit", "flat", "flatShading", "flatshading" -> !o.worldLighting;
            case "seeThrough", "seethrough", "worldSeeThrough", "throughWalls", "noDepth" -> o.worldSeeThrough;
            case "grav", "gravity" -> o.worldGravity;
            case "collision", "collisions", "solid" -> o.worldCollision;
            case "shadow", "shadows", "worldShadow", "worldShadows" -> o.worldShadow;
            case "irlightsShadow", "irlightsShadows", "worldIRLightsShadow", "worldIRLightsShadows",
                    "projectedShadow", "projectedShadows" -> o.worldIrlightsShadows;
            case "animation.curAnim.name" -> animation == null ? null : animation.name;
            case "animation.curAnim.curFrame" -> o.animationFrame;
            case "animation.curAnim.finished" -> o.animationFinished;
            case "animation.curAnim.frameRate" -> animation == null ? null : animation.frameRate;
            case "animation.curAnim.looped" -> animation == null ? null : animation.looped;
            default -> null;
        };
    }

    private static void setObjectProperty(LuaObject o, String property, Object value) {
        LuaAnimation animation = currentAnimation(o);
        switch (property) {
            case "x" -> o.x = number(value, o.x); case "y" -> o.y = number(value, o.y);
            case "z" -> o.z = number(value, o.z);
            case "width" -> { o.width = number(value, o.width); o.sizeExplicit = true; }
            case "height" -> { o.height = number(value, o.height); o.sizeExplicit = true; }
            case "alpha" -> o.alpha = number(value, o.alpha); case "angle" -> o.angle = number(value, o.angle);
            case "rotation.x", "rotationX", "angleX" -> o.rotationX = number(value, o.rotationX);
            case "rotation.y", "rotationY", "angleY" -> o.rotationY = number(value, o.rotationY);
            case "rotation.z", "rotationZ", "angleZ" -> o.angle = number(value, o.angle);
            case "visible" -> o.visible = bool(value); case "color" -> o.color = value instanceof Number n ? n.intValue() : color(String.valueOf(value));
            case "borderSize", "objectBorderSize" -> {
                double requested = number(value, o.textObject ? o.borderSize : o.objectBorderSize);
                double size = Double.isFinite(requested) ? Math.max(0, requested) : 0;
                if (o.textObject) o.borderSize = size;
                else o.objectBorderSize = size;
            }
            case "borderColor", "objectBorderColor" -> {
                int parsed = value instanceof Number n ? n.intValue() : color(String.valueOf(value));
                if (o.textObject) o.borderColor = parsed;
                else o.objectBorderColor = parsed;
            }
            case "antialiasing" -> setAntialiasing(o, bool(value));
            case "text" -> o.text = String.valueOf(value); case "scale.x" -> o.scaleX = number(value, o.scaleX);
            case "scale.y" -> o.scaleY = number(value, o.scaleY);
            case "scrollFactor.x" -> o.scrollFactorX = number(value, o.scrollFactorX);
            case "scrollFactor.y" -> o.scrollFactorY = number(value, o.scrollFactorY);
            case "billboard", "worldBillboard", "alwaysFaceCamera" -> o.worldBillboard = bool(value);
            case "lighting", "worldLighting", "affectedByLighting" -> o.worldLighting = bool(value);
            case "fullbright", "fullBright", "unlit", "flat", "flatShading", "flatshading" -> o.worldLighting = !bool(value);
            case "seeThrough", "seethrough", "worldSeeThrough", "throughWalls", "noDepth" -> o.worldSeeThrough = bool(value);
            case "grav", "gravity" -> o.worldGravity = bool(value);
            case "collision", "collisions", "solid" -> o.worldCollision = bool(value);
            case "shadow", "shadows", "worldShadow", "worldShadows" -> o.worldShadow = bool(value);
            case "irlightsShadow", "irlightsShadows", "worldIRLightsShadow", "worldIRLightsShadows",
                    "projectedShadow", "projectedShadows" -> o.worldIrlightsShadows = bool(value);
            case "offset.x" -> currentAnimationOffsetForWrite(o)[0] = number(value, currentAnimationOffset(o)[0]);
            case "offset.y" -> currentAnimationOffsetForWrite(o)[1] = number(value, currentAnimationOffset(o)[1]);
            case "animation.curAnim.curFrame" -> {
                if (animation != null && !animation.frames.isEmpty()) {
                    o.animationFrame = Math.max(0, Math.min(animation.frames.size() - 1,
                            (int) number(value, o.animationFrame)));
                    o.animationElapsed = 0;
                }
            }
            case "animation.curAnim.frameRate" -> {
                if (animation != null) animation.frameRate = Math.max(0, number(value, animation.frameRate));
            }
            case "animation.curAnim.looped" -> { if (animation != null) animation.looped = bool(value); }
            default -> {}
        }
    }

    private static LuaAnimation currentAnimation(LuaObject object) {
        return object.currentAnimation == null ? null : object.animations.get(object.currentAnimation);
    }

    private static void setAntialiasing(LuaObject object, boolean enabled) {
        if (object.antialiasing == enabled) return;
        if (object.imageHandle != null && object.imageSource != null) {
            SpriteImageCache.Handle replacement = SpriteImageCache.acquire(object.imageSource, enabled);
            if (replacement != null) {
                object.imageHandle.close();
                object.imageHandle = replacement;
                object.dynamicTexture = replacement.dynamicTexture();
                object.texture = replacement.textureId();
            }
        } else if (object.atlas != null && object.atlasPng != null && object.atlasXml != null) {
            SparrowAtlas replacement = SpriteAtlasCache.acquire(object.atlasPng, object.atlasXml, enabled);
            if (replacement != null) {
                ResourceLocation previousTexture = object.atlas.texture();
                object.atlas.close();
                object.atlas = replacement;
                object.texture = replacement.texture();
                object.textureWidth = replacement.width();
                object.textureHeight = replacement.height();
                for (LuaAnimation animation : object.animations.values()) {
                    if (java.util.Objects.equals(animation.texture, previousTexture)) {
                        animation.texture = replacement.texture();
                        animation.textureWidth = replacement.width();
                        animation.textureHeight = replacement.height();
                    }
                }
            }
        }
        object.antialiasing = enabled;
        try {
            if (object.dynamicTexture != null) object.dynamicTexture.setFilter(enabled, false);
            if (object.atlas != null) object.atlas.setAntialiasing(enabled);
        } catch (Throwable ignored) {
            // Texture filtering cannot break script execution.
        }
    }

    private static double[] currentAnimationOffset(LuaObject object) {
        return object.currentAnimation == null ? new double[]{0, 0}
                : object.animationOffsets.getOrDefault(object.currentAnimation, new double[]{0, 0});
    }

    private static double[] currentAnimationOffsetForWrite(LuaObject object) {
        if (object.currentAnimation == null) return new double[]{0, 0};
        return object.animationOffsets.computeIfAbsent(object.currentAnimation, key -> new double[]{0, 0});
    }

    private void tweenFn(Globals g, String name, String property) {
        fn(g, name, args -> { String tag = args.checkjstring(1); String target = args.checkjstring(2);
            String path = target + "." + property;
            // A tween aimed at something that does not exist used to run silently
            // for its whole duration and change nothing, which reads exactly like
            // a broken tween. Say so instead.
            if (getProperty(path) == null) {
                warnOnce(name + ": nothing named '" + target + "' has a '" + property
                        + "' property, so this tween will not move anything");
            }
            double from = number(getProperty(path), 0); double to = args.optdouble(3, from);
            startTween(tag, path, from, to, args.optdouble(4, 1), args.optjstring(5, "linear"));
            return LuaValue.NIL; });
    }

    private void noteTweenFn(Globals g, String name, String property) {
        fn(g, name, args -> { String tag = args.checkjstring(1); int note = args.checkint(2);
            String path = "strumLineNotes." + note + "." + property;
            double from = number(host.psychLuaGetGroup("strumLineNotes", note, property), 0);
            startTween(tag, path, from, args.optdouble(3, from), args.optdouble(4, 1), args.optjstring(5, "linear"));
            return LuaValue.NIL; });
    }

    private void startTween(String tag, String path, double from, double to, double seconds, String ease) {
        tweens.put(tag, new Tween(tag, List.of(new TweenTarget(path, from, to)),
                GameplayClock.now(), Math.max(1, (long) (seconds * 1000)),
                ease == null ? "linear" : ease, null, 0, 0, 0));
    }

    /**
     * Psych's doTweenColor. Blends the packed colour channel by channel so the
     * sprite passes through the colours between the two, not through the integers.
     */
    private void startColorTween(String tag, String target, String colorText,
                                 double seconds, String ease) {
        String path = target + ".color";
        int from = (int) number(getProperty(path), 0xFFFFFF);
        int to = color(colorText);
        tweens.put(tag, new Tween(tag, List.of(), GameplayClock.now(),
                Math.max(1, (long) (seconds * 1000)), ease == null ? "linear" : ease,
                path, from, to, 0));
    }

    /**
     * Psych's startTween: one tween driving every key of a values table, with an
     * options table for ease and start delay. A {@code color} key is routed
     * through the per-channel blend instead of being treated as a plain number.
     */
    private void startTweenTable(Varargs args) {
        String tag = args.checkjstring(1);
        String target = args.checkjstring(2);
        LuaValue values = args.arg(3);
        if (!values.istable()) {
            warnOnce("startTween: values must be a table of properties");
            return;
        }
        double seconds = args.optdouble(4, 1);
        LuaValue options = args.arg(5);
        String ease = "linear";
        double startDelay = 0;
        if (options.istable()) {
            LuaValue easeValue = options.get("ease");
            if (!easeValue.isnil()) ease = easeValue.tojstring();
            LuaValue delayValue = options.get("startDelay");
            if (!delayValue.isnil()) startDelay = delayValue.todouble();
        }

        List<TweenTarget> targets = new ArrayList<>();
        String colorPath = null;
        int colorFrom = 0;
        int colorTo = 0;
        for (LuaValue key : values.checktable().keys()) {
            String property = key.tojstring();
            String path = target + "." + property;
            LuaValue value = values.get(key);
            if (property.equals("color")) {
                colorPath = path;
                colorFrom = (int) number(getProperty(path), 0xFFFFFF);
                colorTo = value.isnumber() ? value.toint() : color(value.tojstring());
                continue;
            }
            double from = number(getProperty(path), 0);
            targets.add(new TweenTarget(path, from, value.todouble()));
        }
        if (targets.isEmpty() && colorPath == null) {
            warnOnce("startTween: '" + target + "' had no tweenable properties");
            return;
        }
        tweens.put(tag, new Tween(tag, List.copyOf(targets), GameplayClock.now(),
                Math.max(1, (long) (seconds * 1000)), ease, colorPath, colorFrom, colorTo,
                Math.max(0, (long) (startDelay * 1000))));
    }

    /** Blank tags address the music channel, matching stopSound's behaviour. */
    private static String soundTag(String tag) {
        return tag == null || tag.isBlank() ? "__music" : tag;
    }

    /** Strum members are addressed through the host's group API, everything else by path. */
    private void applyTweenValue(String path, double value) {
        if (path.startsWith("strumLineNotes.")) {
            String[] parts = path.split("\\.");
            if (parts.length >= 3) {
                host.psychLuaSetGroup("strumLineNotes", Integer.parseInt(parts[1]), parts[2], value);
            }
            return;
        }
        setProperty(path, value);
    }

    /** Per-channel RGB blend, returned as a packed 0xRRGGBB value. */
    private static double blendColor(int from, int to, double progress) {
        int red = channel(from, 16, to, progress);
        int green = channel(from, 8, to, progress);
        int blue = channel(from, 0, to, progress);
        return red << 16 | green << 8 | blue;
    }

    private static int channel(int from, int shift, int to, double progress) {
        int start = (from >> shift) & 0xFF;
        int end = (to >> shift) & 0xFF;
        return Math.max(0, Math.min(255, (int) Math.round(start + (end - start) * progress)));
    }

    private void runTimer(String tag, double seconds, int loops) {
        long interval = Math.max(1, (long) (seconds * 1000));
        timers.put(tag, new Timer(tag, interval, Math.max(1, loops), GameplayClock.now() + interval, 0));
    }

    /** Global toggle: full-bright the main performers and every existing world object. */
    private void setSceneFlatShading(boolean on) {
        for (String tag : new String[]{"boyfriend", "dad", "gf"}) {
            host.psychLuaSetProperty(tag + ".fullbright", on);
        }
        for (LuaObject object : objects.values()) {
            object.worldLighting = !on;
        }
    }

    public void update(double elapsedSeconds) {
        if (closed) return;
        updateAnimations(elapsedSeconds);
        soundPlayer.update();
        if (scripts.isEmpty()) {
            syncWorldSpriteEntities(host.psychLuaMachinePosition(),
                    com.fnfmod.client.gameplay.StageOrientation.facing());
            return;
        }
        syncGlobals();
        call("onUpdate", elapsedSeconds);
        updateTweensAndTimers();
        int step = (int) Math.floor(host.psychLuaBeat() * 4);
        int beat = (int) Math.floor(host.psychLuaBeat());
        int section = host.psychLuaSection();
        int meterBeat = host.psychLuaMeterBeat();
        int measure = host.psychLuaMeasure();
        if (lastStep == Integer.MIN_VALUE || step < lastStep) lastStep = step - 1;
        for (int guard = 0; lastStep < step && guard < 512; guard++) {
            lastStep++;
            setAll("curStep", lastStep);
            call("onStepHit");
        }
        if (lastBeat == Integer.MIN_VALUE || beat < lastBeat) lastBeat = beat - 1;
        for (int guard = 0; lastBeat < beat && guard < 128; guard++) {
            lastBeat++;
            setAll("curBeat", lastBeat);
            call("onBeatHit");
        }
        if (lastSection == Integer.MIN_VALUE || section < lastSection) lastSection = section - 1;
        for (int guard = 0; lastSection < section && guard < 64; guard++) {
            lastSection++;
            setAll("curSection", lastSection);
            call("onSectionHit");
        }
        if (meterBeat != lastMeterBeat) {
            lastMeterBeat = meterBeat;
            call("onMeterBeatHit", host.psychLuaBeatInMeasure(),
                    host.psychLuaTimeSignatureNumerator(), host.psychLuaTimeSignatureDenominator());
        }
        if (measure != lastMeasure) {
            lastMeasure = measure;
            call("onMeasureHit", measure);
        }
        if (!songStarted && host.psychLuaSongStarted()) { songStarted = true; call("onSongStart"); }
        updateSubstate(elapsedSeconds);
        call("onUpdatePost", elapsedSeconds);
        syncWorldSpriteEntities(host.psychLuaMachinePosition(),
                com.fnfmod.client.gameplay.StageOrientation.facing());
        for (int key : queriedKeys) previousKeys.put(key, keyDown(key));
        for (String control : queriedControls) previousControls.put(control, controlDown(control));
    }

    private void updateAnimations(double elapsedSeconds) {
        double elapsed = Math.max(0, Math.min(1, elapsedSeconds));
        for (LuaObject object : objects.values()) {
            LuaAnimation animation = currentAnimation(object);
            if (animation == null || animation.frames.size() <= 1 || animation.frameRate <= 0
                    || object.animationFinished) continue;
            object.animationElapsed += elapsed * animation.frameRate;
            int framesToAdvance = (int) Math.floor(object.animationElapsed);
            if (framesToAdvance <= 0) continue;
            object.animationElapsed -= framesToAdvance;
            int direction = object.animationReverse ? -1 : 1;
            int next = object.animationFrame + direction * framesToAdvance;
            if (animation.looped) {
                object.animationFrame = Math.floorMod(next, animation.frames.size());
            } else if (next < 0 || next >= animation.frames.size()) {
                object.animationFrame = next < 0 ? 0 : animation.frames.size() - 1;
                object.animationFinished = true;
            } else {
                object.animationFrame = next;
            }
        }
    }

    private void syncGlobals() {
        setAll("songPosition", host.psychLuaSongPosition());
        setAll("curDecBeat", host.psychLuaBeat());
        setAll("curDecStep", host.psychLuaBeat() * 4);
        setAll("curMeterBeat", host.psychLuaMeterBeat());
        setAll("curBeatInMeasure", host.psychLuaBeatInMeasure());
        setAll("curMeasure", host.psychLuaMeasure());
        setAll("timeSignatureNumerator", host.psychLuaTimeSignatureNumerator());
        setAll("timeSignatureDenominator", host.psychLuaTimeSignatureDenominator());
        setAll("score", host.psychLuaScore());
        setAll("misses", host.psychLuaMisses());
        setAll("combo", host.psychLuaCombo());
        setAll("health", host.psychLuaHealth());
        setAll("mustHitSection", host.psychLuaMustHit());
        setAll("gfSection", host.psychLuaGfSection());
        setAll("altAnim", host.psychLuaAltAnim());
        setAll("hits", host.psychLuaHits());
        setAll("deaths", host.psychLuaDeaths());
        setAll("totalPlayed", host.psychLuaTotalPlayed());
        setAll("totalNotesHit", host.psychLuaTotalNotesHit());
        setAll("rating", host.psychLuaRating());
        setAll("ratingName", host.psychLuaRatingName());
        setAll("ratingFC", host.psychLuaRatingFC());
        setAll("playbackRate", host.psychLuaPlaybackRate());
        setAll("inGameOver", host.psychLuaInGameOver());
        setAll("startedCountdown", host.psychLuaStartedCountdown());
    }

    private void updateTweensAndTimers() {
        long now = GameplayClock.now();
        for (Tween tween : new ArrayList<>(tweens.values())) {
            long elapsed = now - tween.start - tween.startDelayMs;
            if (elapsed < 0) continue; // still inside the tween's start delay
            double t = Math.min(1, elapsed / (double) tween.durationMs);
            double eased = PsychEasing.apply(tween.ease, t);
            for (TweenTarget target : tween.targets) {
                applyTweenValue(target.path, target.from + (target.to - target.from) * eased);
            }
            if (tween.colorPath != null) {
                applyTweenValue(tween.colorPath, blendColor(tween.colorFrom, tween.colorTo, eased));
            }
            if (t >= 1) { tweens.remove(tween.tag); call("onTweenCompleted", tween.tag); }
        }
        for (Timer initial : new ArrayList<>(timers.values())) {
            Timer timer = initial;
            int guard = 0;
            while (timer != null && now >= timer.nextAt && guard++ < 512) {
                int completed = timer.completed + 1;
                int left = Math.max(0, timer.totalLoops - completed);
                call("onTimerCompleted", timer.tag, completed, left);
                // The callback may cancel or replace its own tag. Respect that
                // instead of restoring an old timer over the script's decision.
                if (timers.get(timer.tag) != timer) break;
                if (left == 0) {
                    timers.remove(timer.tag);
                    break;
                }
                timer = timer.advance(timer.nextAt + timer.intervalMs);
                timers.put(timer.tag, timer);
            }
        }
    }

    public void onEvent(String name, String value1, String value2, double time) {
        setAll("eventName", name); call("onEvent", name, value1, value2, time);
    }

    public boolean onGoodNoteHitPre(int index, int lane, String type, boolean sustain) {
        return !isStop(call("goodNoteHitPre", index, lane, type, sustain));
    }

    public void onGoodNoteHit(int index, int lane, String type, boolean sustain) {
        call("goodNoteHit", index, lane, type, sustain);
    }

    public boolean onOpponentNoteHitPre(int index, int lane, String type, boolean sustain) {
        return !isStop(call("opponentNoteHitPre", index, lane, type, sustain));
    }

    public void onOpponentNoteHit(int index, int lane, String type, boolean sustain) {
        call("opponentNoteHit", index, lane, type, sustain);
    }

    public void onNoteMiss(int index, int lane, String type, boolean sustain) {
        call("noteMiss", index, lane, type, sustain);
    }

    public void onNoteMissPress(int lane) { call("noteMissPress", lane); }
    public boolean onPause() { return !isStop(call("onPause")); }
    public void onResume() { call("onResume"); }
    public boolean onEndSong() { return !isStop(call("onEndSong")); }

    /** Fired for a keypress that hit no note; Psych calls this instead of a miss. */
    public void onGhostTap(int lane) { call("onGhostTap", lane); }

    /** Psych splits key input into a cancellable pre pass and a plain notification. */
    public boolean onKeyPressPre(int lane) { return !isStop(call("onKeyPressPre", lane)); }
    public void onKeyPress(int lane) { call("onKeyPress", lane); }
    public boolean onKeyReleasePre(int lane) { return !isStop(call("onKeyReleasePre", lane)); }
    public void onKeyRelease(int lane) { call("onKeyRelease", lane); }

    /** Returning Function_Stop from onGameOver cancels the death, as in Psych. */
    public boolean onGameOver() { return !isStop(call("onGameOver")); }
    public void onGameOverStart() { call("onGameOverStart"); }
    public void onGameOverConfirm(boolean retry) { call("onGameOverConfirm", retry); }

    public void onCountdownStarted() { call("onCountdownStarted"); }

    /** {@code counter} counts 0-4 across "3", "2", "1", "Go!", and the silent final tick. */
    public boolean onCountdownTick(int counter) {
        return !isStop(call("onCountdownTick", counter));
    }

    public void onSpawnNote(int index, int lane, String type, boolean sustain, double strumTime) {
        call("onSpawnNote", index, lane, type, sustain, strumTime);
    }

    /** Announced once per chart event when the chart is first loaded. */
    public void onEventPushed(String name, String value1, String value2, double time) {
        call("onEventPushed", name, value1, value2, time);
    }

    public void eventEarlyTrigger(String name, String value1, String value2, double time) {
        call("eventEarlyTrigger", name, value1, value2, time);
    }

    /** {@code focus} is the character role the camera moved to. */
    public void onMoveCamera(String focus) { call("onMoveCamera", focus); }

    /** Returning Function_Stop keeps Blockified's own rating values unchanged. */
    public boolean onRecalculateRating() { return !isStop(call("onRecalculateRating")); }

    public boolean preUpdateScore(boolean miss) { return !isStop(call("preUpdateScore", miss)); }
    public void onUpdateScore(boolean miss) { call("onUpdateScore", miss); }

    /** Fired when a note is judged, with the rating id ("sick"/"good"/…) and current combo. */
    public void onRatingPopup(String name, int combo) { call("onRatingPopup", name, combo); }

    public void reloadFonts() { fontLoader.close(); }

    private static boolean isStop(Object result) {
        if (!(result instanceof Number number)) return false;
        int value = number.intValue();
        return value == FUNCTION_STOP || value == FUNCTION_STOP_ALL;
    }

    public Object call(String name, Object... args) {
        Script[] active = activeScripts();
        if (active.length == 0) return FUNCTION_CONTINUE;
        Object result = FUNCTION_CONTINUE;
        LuaValue[] values = null; // built once, only if a script actually has the callback
        for (Script script : active) {
            try {
                LuaValue function = script.globals.get(name);
                if (!function.isfunction()) continue;
                if (values == null) {
                    values = new LuaValue[args.length];
                    for (int i = 0; i < args.length; i++) values[i] = toLua(args[i]);
                }
                script.budget.begin();
                LuaValue value = function.invoke(LuaValue.varargsOf(values)).arg1();
                if (!value.isnil()) result = fromLua(value);
            } catch (Throwable error) {
                warnOnce(script.path.getFileName() + " " + name + ": " + compactError(error));
            } finally {
                script.budget.end();
            }
        }
        return result;
    }

    /** Cached script snapshot; rebuilt only after {@link #scripts} changes. */
    private Script[] activeScripts() {
        Script[] snapshot = scriptSnapshot;
        if (snapshot == null) {
            snapshot = scripts.toArray(new Script[0]);
            scriptSnapshot = snapshot;
        }
        return snapshot;
    }

    private void setAll(String name, Object value) {
        LuaValue lua = toLua(value);
        for (Script script : scripts) script.globals.set(name, lua);
    }

    private static final class BudgetDebugLib extends DebugLib {
        private long deadline = Long.MAX_VALUE;
        private long remainingInstructions = Long.MAX_VALUE;
        private int depth;
        private int hostDepth;
        private long suspendedDeadline = Long.MAX_VALUE;
        private long hostCallStarted;

        void begin() {
            if (depth++ > 0) return;
            remainingInstructions = MAX_CALL_INSTRUCTIONS;
            deadline = System.nanoTime() + CALL_WALL_CEILING_NANOS;
        }

        void end() {
            if (depth <= 0) {
                depth = 0;
                return;
            }
            if (--depth > 0) return;
            remainingInstructions = Long.MAX_VALUE;
            deadline = Long.MAX_VALUE;
            hostDepth = 0;
            suspendedDeadline = Long.MAX_VALUE;
            hostCallStarted = 0;
        }

        /**
         * The wall ceiling measures Lua work, not time inside trusted Blockified
         * APIs. PNG/XML decoding, texture upload and audio pre-cache can legally
         * take over a second on first load; charging that time terminated large
         * Psych onCreate callbacks immediately after the host function returned.
         * The instruction counter remains active throughout the host call.
         */
        void enterHostCall() {
            if (depth <= 0 || hostDepth++ > 0) return;
            hostCallStarted = System.nanoTime();
            suspendedDeadline = deadline;
            deadline = Long.MAX_VALUE;
        }

        void leaveHostCall() {
            if (hostDepth <= 0) {
                hostDepth = 0;
                return;
            }
            if (--hostDepth > 0) return;
            long elapsed = Math.max(0, System.nanoTime() - hostCallStarted);
            deadline = suspendedDeadline == Long.MAX_VALUE
                    ? Long.MAX_VALUE : saturatingAdd(suspendedDeadline, elapsed);
            suspendedDeadline = Long.MAX_VALUE;
            hostCallStarted = 0;
        }

        private static long saturatingAdd(long left, long right) {
            if (right > 0 && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
            return left + right;
        }

        @Override
        public void onInstruction(int pc, Varargs varargs, int top) {
            if (--remainingInstructions < 0 || System.nanoTime() > deadline) {
                throw new LuaError("gameplay Lua exceeded its execution budget");
            }
            super.onInstruction(pc, varargs, top);
        }
    }

    public void render(GuiGraphics gui, boolean hud) {
        if (hud) renderHud(gui, Integer.MIN_VALUE, Integer.MAX_VALUE);
        else renderGame(gui);
    }

    public void renderGame(GuiGraphics gui) {
        renderScreenObjects(gui, CameraGroup.GAME, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    public void renderGame(GuiGraphics gui, int minOrderInclusive, int maxOrderExclusive) {
        renderScreenObjects(gui, CameraGroup.GAME, minOrderInclusive, maxOrderExclusive);
    }

    public void renderHud(GuiGraphics gui, int minOrderInclusive, int maxOrderExclusive) {
        renderScreenObjects(gui, CameraGroup.HUD, minOrderInclusive, maxOrderExclusive);
    }

    /** Draws HUD objects when caller already owns the shared 1280x720 Psych canvas. */
    public void renderHudInCanvas(GuiGraphics gui, int minOrderInclusive, int maxOrderExclusive) {
        renderScreenObjects(gui, CameraGroup.HUD, minOrderInclusive, maxOrderExclusive, false);
    }

    public void renderOther(GuiGraphics gui) {
        renderScreenObjects(gui, CameraGroup.OTHER, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    private enum CameraGroup { GAME, HUD, OTHER, WORLD }

    private void renderScreenObjects(GuiGraphics gui, CameraGroup group,
                                     int minOrderInclusive, int maxOrderExclusive) {
        renderScreenObjects(gui, group, minOrderInclusive, maxOrderExclusive, true);
    }

    private void renderScreenObjects(GuiGraphics gui, CameraGroup group,
                                     int minOrderInclusive, int maxOrderExclusive,
                                     boolean applyCanvas) {
        if (closed || objects.isEmpty()) return;
        if (applyCanvas) {
            if (group == CameraGroup.GAME) {
                // Lua game sprites apply their camera transform individually.
                // A single zoomed parent matrix makes scrollFactor=0 overlays
                // resize even though they are intended to follow the camera.
                PsychCanvas.push(gui, 1f);
            } else {
                PsychCanvas.push(gui, 1f);
            }
        }
        List<LuaObject> visible = objects.values().stream().filter(o -> o.added && o.visible)
                .filter(o -> !o.imageBlockedByPolicy)
                .filter(o -> cameraGroup(o.camera) == group)
                .filter(o -> o.order >= minOrderInclusive && o.order < maxOrderExclusive)
                .sorted(Comparator.comparingInt(o -> o.order)).toList();
        // Draw order, not depth, implements Psych layering. A non-zero GUI Z can
        // survive into later phases and force a "back" sprite over native characters/notes.
        for (LuaObject object : visible) renderObject(gui, object, 0f);
        if (applyCanvas) PsychCanvas.pop(gui);
    }

    private static CameraGroup cameraGroup(String raw) {
        String camera = raw == null ? "game" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (camera) {
            case "game", "camgame" -> CameraGroup.GAME;
            case "hud", "camhud" -> CameraGroup.HUD;
            case "world" -> CameraGroup.WORLD;
            default -> CameraGroup.OTHER;
        };
    }

    /**
     * Returns song-owned objects that occupy the 3D stage. Invisible objects are
     * included so the editor list can recover and modify them even when they cannot be clicked.
     */
    public List<EditableWorldObject> editableWorldObjects() {
        if (closed || objects.isEmpty()) return List.of();
        List<EditableWorldObject> result = new ArrayList<>();
        for (LuaObject o : objects.values()) {
            if (!o.added || !"world".equalsIgnoreCase(o.camera)) continue;
            LuaAnimation animation = currentAnimation(o);
            String kind = o.textObject ? "text"
                    : o.atlas != null ? "spritesheet"
                    : o.texture == null && !o.missingAsset ? "graph" : "sprite";
            result.add(new EditableWorldObject(
                    o.tag, kind, o.image, o.text,
                    o.x, o.y, o.z, o.width, o.height,
                    o.scaleX, o.scaleY, o.alpha,
                    o.rotationX, o.rotationY, o.angle,
                    o.color & 0xFFFFFF, o.textSize,
                    o.visible, o.worldBillboard, o.worldLighting, o.worldSeeThrough, o.antialiasing,
                    o.borderSize, o.borderColor & 0xFFFFFF, o.borderStyle,
                    o.alignment, o.italic,
                    o.currentAnimation == null ? "" : o.currentAnimation,
                    List.copyOf(o.animations.keySet()),
                    animation == null ? 24 : (int) Math.round(animation.frameRate),
                    animation == null || animation.looped));
        }
        return List.copyOf(result);
    }

    /** Applies an editor snapshot to the same Lua object; its tag and ownership stay intact. */
    public boolean applyWorldObjectEdit(EditableWorldObject edit) {
        if (closed || edit == null) return false;
        LuaObject o = objects.get(edit.tag());
        if (o == null || !o.added || !"world".equalsIgnoreCase(o.camera)) return false;

        String nextImage = edit.image() == null ? "" : edit.image();
        boolean imageChanged = !java.util.Objects.equals(o.image, nextImage);
        if (imageChanged && !edit.kind().equals("text") && !edit.kind().equals("graph")) {
            o.image = nextImage;
            if (edit.kind().equals("spritesheet")) loadObjectAtlas(o, nextImage, "auto");
            else loadObjectImage(o, nextImage);
        }

        o.x = editorFinite(edit.x(), o.x);
        o.y = editorFinite(edit.y(), o.y);
        o.z = editorFinite(edit.z(), o.z);
        o.width = Math.max(0, editorFinite(edit.width(), o.width));
        o.height = Math.max(0, editorFinite(edit.height(), o.height));
        o.sizeExplicit = true;
        o.scaleX = editorFinite(edit.scaleX(), o.scaleX);
        o.scaleY = editorFinite(edit.scaleY(), o.scaleY);
        o.alpha = Math.max(0, Math.min(1, editorFinite(edit.alpha(), o.alpha)));
        o.rotationX = editorFinite(edit.rotationX(), o.rotationX);
        o.rotationY = editorFinite(edit.rotationY(), o.rotationY);
        o.angle = editorFinite(edit.rotationZ(), o.angle);
        o.color = edit.color() & 0xFFFFFF;
        o.visible = edit.visible();
        o.text = edit.text() == null ? "" : edit.text();
        o.textSize = Math.max(1, Math.min(512, edit.textSize()));
        o.worldBillboard = edit.billboard();
        o.worldLighting = edit.lighting();
        o.worldSeeThrough = edit.seeThrough();
        o.borderSize = Math.max(0, editorFinite(edit.borderSize(), o.borderSize));
        o.borderColor = edit.borderColor() & 0xFFFFFF;
        o.borderStyle = edit.borderStyle() == null ? "none" : edit.borderStyle();
        o.alignment = edit.alignment() == null ? "left" : edit.alignment();
        o.italic = edit.italic();
        setAntialiasing(o, edit.antialiasing());

        if (edit.animation() != null && !edit.animation().isBlank()
                && o.animations.containsKey(edit.animation())) {
            if (!edit.animation().equals(o.currentAnimation)) {
                startAnimation(o, edit.animation(), true, false, 0);
            }
            LuaAnimation animation = currentAnimation(o);
            if (animation != null) {
                animation.frameRate = Math.max(1, Math.min(240, edit.fps()));
                animation.looped = edit.loop();
            }
        }
        // Free-cam edits arrive at render-rate. Refresh the entity host now instead
        // of waiting for the next gameplay/Lua tick, keeping art and gizmo together.
        syncWorldSpriteEntities(host.psychLuaMachinePosition(),
                com.fnfmod.client.gameplay.StageOrientation.facing());
        return true;
    }

    private static double editorFinite(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }

    public void renderWorld(PoseStack poseStack, Camera camera, BlockPos speakers, Direction facing) {
        if (closed || objects.isEmpty()) return;
        syncWorldSpriteEntities(speakers, facing);
        List<LuaObject> visible = objects.values().stream()
                .filter(o -> o.added && o.visible && !o.imageBlockedByPolicy
                        && o.textObject && o.camera.equalsIgnoreCase("world"))
                .sorted(Comparator.comparingInt(o -> o.order))
                .toList();
        List<LuaWorldObject> worldObjects = new ArrayList<>(visible.size());
        for (LuaObject o : visible) {
            // Text remains a direct world draw. Sprite-like objects are now rendered
            // by their real client-side entity during Minecraft's entity pass.
            Font selected = o.fontName.isBlank() ? null : fontLoader.get(o.fontName);
            Font font = selected == null ? Minecraft.getInstance().font : selected;
            worldObjects.add(new LuaWorldObject.Text(
                    font, o.text == null ? "" : o.text,
                    o.x, o.y, o.z, o.width, o.textSize,
                    o.scaleX, o.scaleY, o.alpha, o.angle, o.rotationX, o.rotationY,
                    o.color, o.worldBillboard, o.worldLighting, o.worldSeeThrough,
                    o.borderSize, o.borderColor, o.borderStyle, o.alignment, o.italic));
        }
        LuaWorldObjectRenderer.render(poseStack, camera, speakers, facing, worldObjects);
    }

    private LuaWorldObject.Sprite worldSpriteSnapshot(LuaObject o) {
        if (o == null || !o.added || !o.visible || o.textObject || o.imageBlockedByPolicy
                || !o.camera.equalsIgnoreCase("world")) return null;
        SparrowAtlas.Frame frame = o.texture == null ? null : currentFrame(o);
        LuaWorldObject.Frame renderFrame = frame == null ? null
                : new LuaWorldObject.Frame(frame.x, frame.y, frame.w, frame.h,
                frame.frameX, frame.frameY, frame.rotated);
        double[] offset = currentAnimationOffset(o);
        return new LuaWorldObject.Sprite(
                activeTexture(o), activeTextureWidth(o), activeTextureHeight(o),
                renderFrame, offset[0], offset[1],
                0, 0, 0, o.width, o.height, o.graphicWidth, o.graphicHeight,
                o.scaleX, o.scaleY, o.alpha, o.angle, o.rotationX, o.rotationY,
                o.missingAsset ? 0xFFFFFF : o.color,
                o.worldBillboard, o.worldLighting, o.worldSeeThrough);
    }

    /** Keeps entity physics and Psych's stage-local XYZ properties in both directions. */
    private void syncWorldSpriteEntities(BlockPos speakers, Direction facing) {
        if (closed || speakers == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.level instanceof ClientLevel level)) return;
        Direction stageFacing = facing == null ? Direction.NORTH : facing;

        Set<String> active = new LinkedHashSet<>();
        for (LuaObject o : objects.values()) {
            if (!o.added || o.textObject || o.imageBlockedByPolicy
                    || !o.camera.equalsIgnoreCase("world")) continue;
            active.add(o.tag);
            WorldEntityBinding binding = worldSpriteEntities.get(o.tag);
            boolean created = false;
            if (binding == null || binding.entity.level() != level || binding.entity.isRemoved()) {
                if (binding != null) removeWorldSpriteEntity(o.tag);
                WorldSpriteEntity entity = new WorldSpriteEntity(FnfMod.WORLD_SPRITE_ENTITY.get(), level);
                entity.setId(WorldSpriteEntity.allocateClientId());
                binding = new WorldEntityBinding(entity);
                worldSpriteEntities.put(o.tag, binding);
                WorldEntityBinding visualBinding = binding;
                WorldSpriteEntityVisuals.bind(entity, () -> {
                    LuaWorldObject.Sprite sprite = worldSpriteSnapshot(o);
                    return sprite == null ? null : new WorldSpriteEntityVisuals.Visual(
                            sprite, visualBinding.facing, o.worldIrlightsShadows);
                });
                level.addEntity(entity);
                created = true;
            }
            binding.facing = stageFacing;
            WorldSpriteEntity entity = binding.entity;
            entity.setNoGravity(!o.worldGravity);
            entity.noPhysics = !o.worldCollision;
            entity.setVisualBounds(o.width * Math.abs(o.scaleX) * LuaWorldObjectRenderer.PIXEL_SCALE,
                    o.height * Math.abs(o.scaleY) * LuaWorldObjectRenderer.PIXEL_SCALE);
            ObjectBorderRegistry.set(entity, o.objectBorderSize, o.objectBorderColor);
            PerformerShadows.setEnabled(entity.getId(), o.worldShadow);

            boolean authored = created || different(o.x, binding.lastX)
                    || different(o.y, binding.lastY) || different(o.z, binding.lastZ);
            if (authored || !o.worldGravity) {
                Vec3 world = worldFromStage(speakers, stageFacing, o.x, o.y, o.z);
                entity.setPos(world);
                if (!o.worldGravity) entity.setDeltaMovement(Vec3.ZERO);
            } else {
                double[] local = stageFromWorld(speakers, stageFacing, entity.position());
                o.x = local[0]; o.y = local[1]; o.z = local[2];
            }
            binding.lastX = o.x; binding.lastY = o.y; binding.lastZ = o.z;
        }
        for (String tag : new ArrayList<>(worldSpriteEntities.keySet())) {
            if (!active.contains(tag)) removeWorldSpriteEntity(tag);
        }
    }

    private void removeWorldSpriteEntity(String tag) {
        WorldEntityBinding binding = worldSpriteEntities.remove(tag);
        if (binding == null) return;
        WorldSpriteEntityVisuals.unbind(binding.entity);
        ObjectBorderRegistry.clear(binding.entity);
        PerformerShadows.setEnabled(binding.entity.getId(), true);
        if (binding.entity.level() instanceof ClientLevel level
                && level.getEntity(binding.entity.getId()) != null) {
            level.removeEntity(binding.entity.getId(), Entity.RemovalReason.DISCARDED);
        }
    }

    private static boolean different(double a, double b) {
        return !Double.isFinite(b) || Math.abs(a - b) > 1.0e-7;
    }

    private void syncObjectBorder(LuaObject object) {
        if (object == null) return;
        WorldEntityBinding binding = worldSpriteEntities.get(object.tag);
        if (binding != null) {
            ObjectBorderRegistry.set(binding.entity, object.objectBorderSize,
                    object.objectBorderColor);
        }
    }

    private static Vec3 worldFromStage(BlockPos speakers, Direction facing,
                                       double x, double y, double z) {
        Direction right = facing.getCounterClockWise();
        Vec3 origin = Vec3.atCenterOf(speakers);
        return origin.add(
                right.getStepX() * x * LuaWorldObjectRenderer.PIXEL_SCALE
                        + facing.getStepX() * z * LuaWorldObjectRenderer.PIXEL_SCALE,
                -y * LuaWorldObjectRenderer.PIXEL_SCALE,
                right.getStepZ() * x * LuaWorldObjectRenderer.PIXEL_SCALE
                        + facing.getStepZ() * z * LuaWorldObjectRenderer.PIXEL_SCALE);
    }

    private static double[] stageFromWorld(BlockPos speakers, Direction facing, Vec3 world) {
        Direction right = facing.getCounterClockWise();
        Vec3 origin = Vec3.atCenterOf(speakers);
        Vec3 delta = world.subtract(origin);
        double scale = LuaWorldObjectRenderer.PIXEL_SCALE;
        return new double[]{
                (delta.x * right.getStepX() + delta.z * right.getStepZ()) / scale,
                -delta.y / scale,
                (delta.x * facing.getStepX() + delta.z * facing.getStepZ()) / scale};
    }

    private void renderObject(GuiGraphics gui, LuaObject o, float z) {
        int alpha = Math.max(0, Math.min(255, (int) Math.round(o.alpha * 255)));
        int color = (o.color & 0x00FFFFFF) | alpha << 24;
        double drawX = o.x;
        double drawY = o.y;
        double cameraScaleX = 1;
        double cameraScaleY = 1;
        if (cameraGroup(o.camera) == CameraGroup.GAME) {
            PsychCameraTransform.Result transformed = PsychCameraTransform.apply(
                    o.x, o.y, o.scrollFactorX, o.scrollFactorY,
                    host.psychLuaGameCameraX(), host.psychLuaGameCameraY(),
                    GameplayCamera.gameZoom(), GameplayCamera.gameShakeX(),
                    GameplayCamera.gameShakeY());
            drawX = transformed.x();
            drawY = transformed.y();
            cameraScaleX = transformed.scaleX();
            cameraScaleY = transformed.scaleY();
        }
        if (!o.textObject && o.objectBorderSize > 0) {
            renderObjectBorder(gui, o, z, drawX, drawY, cameraScaleX, cameraScaleY, alpha);
        }
        gui.pose().pushPose();
        gui.pose().translate(drawX, drawY, z);
        gui.pose().scale((float) (o.scaleX * cameraScaleX),
                (float) (o.scaleY * cameraScaleY), 1);
        if (o.angle != 0) gui.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees((float) o.angle));
        // Gui batches do not guarantee that the previous layer left source-alpha
        // blending active. Every Lua object must support the full 0..1 range.
        RenderSystem.enableBlend();
        // Psych's "add" blend brightens what is underneath; every other mode it
        // accepts has no distinct Minecraft equivalent and draws normally.
        boolean additive = "add".equalsIgnoreCase(o.blend) || "additive".equalsIgnoreCase(o.blend);
        if (additive) {
            RenderSystem.blendFunc(com.mojang.blaze3d.platform.GlStateManager.SourceFactor.SRC_ALPHA,
                    com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE);
        } else {
            RenderSystem.defaultBlendFunc();
        }
        if (o.textObject) {
            float scale = Math.max(0.25f, o.textSize / 9f);
            gui.pose().scale(scale, scale, 1);
            Font selected = o.fontName.isBlank() ? null : fontLoader.get(o.fontName);
            Font font = selected == null ? Minecraft.getInstance().font : selected;
            int wrapWidth = o.width <= 0 || o.autoSize ? Integer.MAX_VALUE
                    : Math.max(1, (int) Math.floor(o.width / scale));
            renderText(gui, o, font, wrapWidth, color);
        } else if (o.texture != null || o.missingAsset) {
            float red = o.missingAsset ? 1 : ((o.color >> 16) & 255) / 255f;
            float green = o.missingAsset ? 1 : ((o.color >> 8) & 255) / 255f;
            float blue = o.missingAsset ? 1 : (o.color & 255) / 255f;
            // GUI fills and other render batches may leave blending disabled.
            // Lua sprite alpha is continuous (0..1), so explicitly restore the
            // normal source-alpha blend and use GuiGraphics' texture tint.
            gui.setColor(red, green, blue, alpha / 255f);
            SparrowAtlas.Frame frame = currentFrame(o);
            if (frame == null) {
                int drawWidth = Math.max(1, (int) o.width);
                int drawHeight = Math.max(1, (int) o.height);
                if (o.missingAsset) {
                    gui.blit(activeTexture(o), 0, 0, drawWidth, drawHeight,
                            0, 0, MissingAssetTexture.width(), MissingAssetTexture.height(),
                            MissingAssetTexture.width(), MissingAssetTexture.height());
                } else {
                    gui.blit(activeTexture(o), 0, 0, 0, 0, drawWidth, drawHeight,
                            Math.max(1, activeTextureWidth(o)), Math.max(1, activeTextureHeight(o)));
                }
            } else {
                renderAtlasFrame(gui, o, frame);
            }
            gui.setColor(1, 1, 1, 1);
        } else {
            gui.fill(0, 0, Math.max(1, (int) o.width), Math.max(1, (int) o.height), color);
        }
        gui.pose().popPose();
    }

    /** Draws a silhouette around a 2D object in fixed Psych-canvas pixels. */
    private void renderObjectBorder(GuiGraphics gui, LuaObject o, float z,
                                    double drawX, double drawY,
                                    double cameraScaleX, double cameraScaleY, int alpha) {
        int stroke = borderStroke(o.objectBorderSize);
        if (stroke == 0 || alpha == 0) return;
        int color = o.objectBorderColor & 0xFFFFFF;
        float red = ((color >> 16) & 255) / 255f;
        float green = ((color >> 8) & 255) / 255f;
        float blue = (color & 255) / 255f;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        for (int dx = -stroke; dx <= stroke; dx++) {
            for (int dy = -stroke; dy <= stroke; dy++) {
                if (dx == 0 && dy == 0) continue;
                gui.pose().pushPose();
                gui.pose().translate(drawX + dx, drawY + dy, z);
                gui.pose().scale((float) (o.scaleX * cameraScaleX),
                        (float) (o.scaleY * cameraScaleY), 1);
                if (o.angle != 0) {
                    gui.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees((float) o.angle));
                }
                gui.setColor(red, green, blue, alpha / 255f);
                SparrowAtlas.Frame frame = currentFrame(o);
                if (o.texture != null || o.missingAsset) {
                    if (frame == null) {
                        int drawWidth = Math.max(1, (int) o.width);
                        int drawHeight = Math.max(1, (int) o.height);
                        ResourceLocation mask = activeSilhouetteTexture(o);
                        gui.blit(mask, 0, 0, 0, 0, drawWidth, drawHeight,
                                Math.max(1, activeTextureWidth(o)), Math.max(1, activeTextureHeight(o)));
                    } else {
                        renderAtlasFrame(gui, o, frame, activeSilhouetteTexture(o));
                    }
                } else {
                    gui.setColor(1, 1, 1, 1);
                    gui.fill(0, 0, Math.max(1, (int) o.width), Math.max(1, (int) o.height),
                            alpha << 24 | color);
                }
                gui.setColor(1, 1, 1, 1);
                gui.pose().popPose();
            }
        }
    }

    private static SparrowAtlas.Frame currentFrame(LuaObject object) {
        LuaAnimation animation = currentAnimation(object);
        if (animation != null && !animation.frames.isEmpty()) {
            SparrowAtlas.Frame frame = animation.frames.get(Math.max(0, Math.min(animation.frames.size() - 1,
                    object.animationFrame)));
            SparrowAtlas sheet = animation.sheet == null ? object.atlas : animation.sheet;
            if (sheet != null) sheet.prepareFrame(frame);
            return frame;
        }
        if (object.atlas == null || object.atlas.allFrames().isEmpty()) return null;
        SparrowAtlas.Frame frame = object.atlas.allFrames().get(0);
        object.atlas.prepareFrame(frame);
        return frame;
    }

    /**
     * Draws a Lua text object with Psych's alignment, border, and italic styling.
     * The border is stroked first so the fill always sits on top of it, matching
     * FlxText's outline and shadow styles.
     */
    private void renderText(GuiGraphics gui, LuaObject o, Font font, int wrapWidth, int color) {
        List<net.minecraft.util.FormattedCharSequence> lines = wrapWidth == Integer.MAX_VALUE
                ? List.of(Component.literal(o.text).getVisualOrderText())
                : font.split(Component.literal(o.text), wrapWidth);
        // Psych stops drawing once the text passes an explicit height limit.
        int maxLines = o.heightLimit <= 0 ? lines.size()
                : Math.max(1, (int) Math.floor(o.heightLimit / 9.0));
        int borderAlpha = Math.max(0, Math.min(255, (int) Math.round(o.alpha * 255)));
        int borderColor = (o.borderColor & 0x00FFFFFF) | borderAlpha << 24;
        int stroke = (int) Math.round(Math.abs(o.borderSize));

        for (int i = 0; i < Math.min(maxLines, lines.size()); i++) {
            var line = lines.get(i);
            int y = i * 9;
            int x = alignedX(o, font.width(line), wrapWidth);
            if (stroke > 0) drawTextBorder(gui, o, font, line, x, y, stroke, borderColor);
            drawTextLine(gui, o, font, line, x, y, color);
        }
    }

    /** Psych's left/center/right/justify alignment inside the wrap width. */
    private static int alignedX(LuaObject o, int lineWidth, int wrapWidth) {
        if (wrapWidth == Integer.MAX_VALUE) return 0;
        return switch (o.alignment.toLowerCase(Locale.ROOT)) {
            case "center", "centered" -> (wrapWidth - lineWidth) / 2;
            case "right" -> wrapWidth - lineWidth;
            default -> 0;
        };
    }

    private void drawTextBorder(GuiGraphics gui, LuaObject o, Font font,
                                net.minecraft.util.FormattedCharSequence line,
                                int x, int y, int stroke, int borderColor) {
        if ("shadow".equalsIgnoreCase(o.borderStyle)) {
            drawTextLine(gui, o, font, line, x + stroke, y + stroke, borderColor);
            return;
        }
        // Outline draws the eight surrounding offsets, like FlxText's OUTLINE style.
        for (int dx = -stroke; dx <= stroke; dx++) {
            for (int dy = -stroke; dy <= stroke; dy++) {
                if (dx == 0 && dy == 0) continue;
                drawTextLine(gui, o, font, line, x + dx, y + dy, borderColor);
            }
        }
    }

    /** Italic is a horizontal shear, since Minecraft fonts have no oblique variant. */
    private void drawTextLine(GuiGraphics gui, LuaObject o, Font font,
                              net.minecraft.util.FormattedCharSequence line,
                              int x, int y, int color) {
        if (!o.italic) {
            gui.drawString(font, line, x, y, color, false);
            return;
        }
        gui.pose().pushPose();
        org.joml.Matrix4f shear = new org.joml.Matrix4f().set(new float[]{
                1, 0, 0, 0,
                -0.2f, 1, 0, 0,
                0, 0, 1, 0,
                0, 0, 0, 1});
        gui.pose().translate(0, y, 0);
        gui.pose().mulPose(shear);
        gui.pose().translate(0, -y, 0);
        gui.drawString(font, line, x, y, color, false);
        gui.pose().popPose();
    }

    /** The sheet the current animation draws from, falling back to the object's own. */
    private static ResourceLocation activeTexture(LuaObject object) {
        LuaAnimation animation = currentAnimation(object);
        if (animation != null && animation.texture != null) return animation.texture;
        return object.missingAsset ? MissingAssetTexture.texture() : object.texture;
    }

    /** No authored cap; beyond the fixed canvas cannot add visible coverage. */
    private static int borderStroke(double size) {
        if (!Double.isFinite(size) || size <= 0) return 0;
        return (int) Math.min(1280, Math.round(size));
    }

    private static ResourceLocation activeSilhouetteTexture(LuaObject object) {
        LuaAnimation animation = currentAnimation(object);
        if (animation != null && animation.sheet != null) return animation.sheet.silhouetteTexture();
        if (object.atlas != null) return object.atlas.silhouetteTexture();
        if (object.imageHandle != null) {
            ResourceLocation mask = object.imageHandle.silhouetteTexture();
            if (mask != null) return mask;
        }
        return activeTexture(object);
    }

    private static int activeTextureWidth(LuaObject object) {
        LuaAnimation animation = currentAnimation(object);
        if (animation != null && animation.texture != null) return animation.textureWidth;
        return object.missingAsset ? MissingAssetTexture.width() : object.textureWidth;
    }

    private static int activeTextureHeight(LuaObject object) {
        LuaAnimation animation = currentAnimation(object);
        if (animation != null && animation.texture != null) return animation.textureHeight;
        return object.missingAsset ? MissingAssetTexture.height() : object.textureHeight;
    }

    private static void renderAtlasFrame(GuiGraphics gui, LuaObject object, SparrowAtlas.Frame frame) {
        renderAtlasFrame(gui, object, frame, activeTexture(object));
    }

    private static void renderAtlasFrame(GuiGraphics gui, LuaObject object, SparrowAtlas.Frame frame,
                                         ResourceLocation texture) {
        double[] offset = currentAnimationOffset(object);
        // Keep one scale for the whole atlas. Scaling every frame against its
        // own frameWidth/frameHeight makes trimmed animations wobble and warp.
        float scaleX = (float) (object.width / Math.max(1, object.graphicWidth));
        float scaleY = (float) (object.height / Math.max(1, object.graphicHeight));
        gui.pose().pushPose();
        gui.pose().translate((-frame.frameX - offset[0]) * scaleX,
                (-frame.frameY - offset[1]) * scaleY, 0);
        gui.pose().scale(scaleX, scaleY, 1);
        if (frame.rotated) {
            gui.pose().translate(0, frame.w, 0);
            gui.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(-90));
        }
        gui.blit(texture, 0, 0, frame.x, frame.y, frame.w, frame.h,
                Math.max(1, activeTextureWidth(object)), Math.max(1, activeTextureHeight(object)));
        gui.pose().popPose();
    }

    private Path resolveSafe(String relative) {
        if (relative == null || relative.isBlank()) return null;
        for (Path root : new Path[]{songFolder, modRoot}) {
            if (root == null) continue;
            Path path = root.resolve(relative).normalize();
            if (path.startsWith(root) && Files.exists(path)) return path;
        }
        return null;
    }

    private Path resolveImage(String name) {
        // Resolver applies song-source and shared-directory checklists separately.
        return assets.image(name);
    }

    public boolean playSoundEvent(String sound, float volume) {
        return playOrDeferSound(sound, volume, "", false);
    }

    private boolean playOrDeferSound(String name, float volume, String tag, boolean loop) {
        if (!host.isEditorReconstructing()) return soundPlayer.play(name, volume, tag, loop);
        // Transient untagged sounds should have finished before the selected point.
        // Preserve tagged loops because they represent ongoing stage/menu music.
        if (tag != null && !tag.isBlank()) {
            if (loop) reconstructionSounds.put(tag, new DeferredSound(name, volume, tag, true));
            else reconstructionSounds.remove(tag);
        }
        return true;
    }

    public void beginEditorReconstructionAudio() {
        soundPlayer.pauseAll();
    }

    public void finishEditorReconstructionAudio() {
        for (DeferredSound sound : List.copyOf(reconstructionSounds.values())) {
            soundPlayer.play(sound.name(), sound.volume(), sound.tag(), sound.loop());
        }
        reconstructionSounds.clear();
        soundPlayer.resumeAll();
    }

    private String readSafe(String path) {
        try { Path file = resolveSafe(path); return file == null ? "" : Files.readString(file); }
        catch (Exception ignored) { return ""; }
    }

    private boolean writeSafe(String path, String content) {
        if (songFolder == null) return false;
        try {
            Path target = songFolder.resolve(path).normalize();
            if (!target.startsWith(songFolder)) return false;
            Files.createDirectories(target.getParent());
            Files.writeString(target, content);
            return true;
        } catch (Exception ignored) { return false; }
    }

    // ------------------------------------------------------------- substates

    private void openSubstate(String name, boolean pauseGame) {
        if (substateName != null) closeSubstate();
        substateName = name == null ? "" : name;
        substatePauses = pauseGame;
        substateMembers.clear();
        call("onCustomSubstateCreate", substateName);
        call("onCustomSubstateCreatePost", substateName);
    }

    private void closeSubstate() {
        if (substateName == null) return;
        String name = substateName;
        substateName = null;
        substatePauses = false;
        substateMembers.clear();
        call("onCustomSubstateDestroy", name);
    }

    /** Psych inserts an existing Lua sprite into the open substate at a position. */
    private void insertToSubstate(String tag, int position) {
        if (substateName == null || tag == null || !objects.containsKey(tag)) return;
        substateMembers.remove(tag);
        if (position < 0 || position >= substateMembers.size()) substateMembers.add(tag);
        else substateMembers.add(position, tag);
    }

    /** True while a substate opened with pauseGame is holding gameplay. */
    public boolean substatePausesGame() {
        return substateName != null && substatePauses;
    }

    public boolean substateOpen() {
        return substateName != null;
    }

    /** Draws the open substate's members above every other layer. */
    public void renderSubstate(GuiGraphics gui) {
        if (closed || substateName == null) return;
        float z = 0;
        for (String tag : substateMembers) {
            LuaObject object = objects.get(tag);
            if (object != null && object.visible) renderObject(gui, object, z++);
        }
    }

    private void updateSubstate(double elapsedSeconds) {
        if (substateName == null) return;
        call("onCustomSubstateUpdate", substateName, elapsedSeconds);
        call("onCustomSubstateUpdatePost", substateName, elapsedSeconds);
    }

    // ------------------------------------------------------- class reflection

    /**
     * Psych scripts reach into engine classes through these calls. Blockified has
     * no Haxe classes, so the well-known Psych paths are mapped onto the equivalent
     * Blockified property; anything else returns nil rather than guessing.
     */
    private Object classProperty(String classVar, String variable) {
        String path = classPropertyPath(classVar, variable);
        if (path == null) return null;
        return switch (path) {
            case "@screenWidth" -> VIRTUAL_WIDTH;
            case "@screenHeight" -> VIRTUAL_HEIGHT;
            default -> getProperty(path);
        };
    }

    private void setClassProperty(String classVar, String variable, Object value) {
        String path = classPropertyPath(classVar, variable);
        if (path == null || path.startsWith("@")) return;
        setProperty(path, value);
    }

    /**
     * Maps a Psych class path to a Blockified property path. Client preferences
     * and PlayState fields are already exposed as script globals, so they route
     * through the same property lookup a plain getProperty would use.
     */
    private static String classPropertyPath(String classVar, String variable) {
        if (classVar == null || variable == null) return null;
        String owner = classVar.substring(classVar.lastIndexOf('.') + 1);
        String field = variable.startsWith("data.") ? variable.substring(5) : variable;
        return switch (owner) {
            case "ClientPrefs" -> field;
            case "PlayState" -> field.startsWith("instance.") ? field.substring(9) : field;
            case "FlxG" -> switch (field) {
                case "width" -> "@screenWidth";
                case "height" -> "@screenHeight";
                default -> null;
            };
            default -> null;
        };
    }

    /** Deletion is confined to the song folder, exactly like saveFile's writes. */
    private boolean deleteSafe(String path) {
        if (songFolder == null) return false;
        try {
            Path target = songFolder.resolve(path).normalize();
            if (!target.startsWith(songFolder)) return false;
            return Files.deleteIfExists(target);
        } catch (Exception ignored) { return false; }
    }

    /**
     * Psych substitutes {@code {1}}, {@code {2}}, ... in a translated phrase with
     * the supplied arguments. The phrase itself is the caller's default here.
     */
    private static String formatTranslation(String phrase, LuaValue args) {
        if (phrase == null || !args.istable()) return phrase == null ? "" : phrase;
        String result = phrase;
        LuaTable table = args.checktable();
        for (int i = 1; i <= table.length(); i++) {
            result = result.replace("{" + i + "}", table.get(i).tojstring());
        }
        return result;
    }

    /**
     * Psych's getModSetting reads a value the player chose from the pack's
     * settings.json. Blockified has no settings UI, so the declared default is
     * returned; a script branching on it still behaves as the pack intended.
     */
    private LuaValue modSetting(String saveTag, String modName) {
        Path root = modName == null || modName.isBlank() ? modRoot
                : SongLibrary.modsDir().resolve(modName);
        if (root == null) return LuaValue.NIL;
        Path settings = root.resolve("data").resolve("settings.json");
        if (!Files.isRegularFile(settings)) settings = root.resolve("settings.json");
        if (!Files.isRegularFile(settings)) return LuaValue.NIL;
        try {
            var parsed = com.google.gson.JsonParser.parseString(Files.readString(settings));
            if (!parsed.isJsonArray()) return LuaValue.NIL;
            for (var element : parsed.getAsJsonArray()) {
                if (!element.isJsonObject()) continue;
                var entry = element.getAsJsonObject();
                if (!entry.has("save") || !entry.get("save").getAsString().equals(saveTag)) continue;
                if (!entry.has("value")) return LuaValue.NIL;
                var value = entry.get("value");
                if (!value.isJsonPrimitive()) return LuaValue.NIL;
                var primitive = value.getAsJsonPrimitive();
                if (primitive.isBoolean()) return LuaValue.valueOf(primitive.getAsBoolean());
                if (primitive.isNumber()) return LuaValue.valueOf(primitive.getAsDouble());
                return LuaValue.valueOf(primitive.getAsString());
            }
        } catch (Exception error) {
            warnOnce("getModSetting: " + compactError(error));
        }
        return LuaValue.NIL;
    }

    private LuaValue directoryList(String folder) {
        Path path = resolveSafe(folder);
        if (path == null || !Files.isDirectory(path)) return new LuaTable();
        try (Stream<Path> stream = Files.list(path)) {
            return stringArray(stream.map(p -> p.getFileName().toString()).sorted().toArray(String[]::new));
        } catch (Exception ignored) { return new LuaTable(); }
    }

    private static LuaTable stringArray(String[] values) {
        LuaTable table = new LuaTable();
        for (int i = 0; i < values.length; i++) table.set(i + 1, values[i]);
        return table;
    }

    private int randomInt(int min, int max, String excluded) {
        if (max < min) { int tmp = min; min = max; max = tmp; }
        Set<Integer> skip = new LinkedHashSet<>();
        for (String part : excluded.split(",")) try { skip.add(Integer.parseInt(part.trim())); } catch (Exception ignored) {}
        int value;
        int tries = 0;
        do { value = min + random.nextInt(Math.max(1, max - min + 1)); } while (skip.contains(value) && ++tries < 100);
        return value;
    }

    private static int color(String raw) {
        return PsychColor.parse(raw);
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.doubleValue() != 0;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static double number(Object value, double fallback) {
        if (value instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(value)); } catch (Exception ignored) { return fallback; }
    }

    private static LuaValue toLua(Object value) {
        if (value == null) return LuaValue.NIL;
        if (value instanceof LuaValue lua) return lua;
        if (value instanceof Boolean b) return LuaValue.valueOf(b);
        if (value instanceof Number n) return LuaValue.valueOf(n.doubleValue());
        if (value instanceof List<?> list) {
            LuaTable table = new LuaTable();
            for (int i = 0; i < list.size(); i++) table.set(i + 1, toLua(list.get(i)));
            return table;
        }
        return LuaValue.valueOf(String.valueOf(value));
    }

    private static Object fromLua(LuaValue value) {
        if (value == null || value.isnil()) return null;
        if (value.isboolean()) return value.toboolean();
        if (value.isnumber()) return value.todouble();
        if (value.isstring()) return value.tojstring();
        return value;
    }

    private void report(String message) {
        String text = "[Psych Lua] " + message;
        FnfMod.LOGGER.warn(text);
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.displayClientMessage(Component.literal(text), false);
        }
    }

    /**
     * Psych's {@code debugPrint(text, color)}: shows a fading trace line in the
     * top-left overlay (not chat). The optional second argument is an FlxColor
     * (int) or a colour name/hex; alpha defaults to opaque when none is given.
     */
    private void debugPrint(Varargs args) {
        String text = args.optjstring(1, "");
        int color = 0xFFFFFFFF;
        LuaValue c = args.arg(2);
        if (c.isnumber()) {
            color = (int) (long) c.todouble();
        } else if (c.isstring()) {
            color = PsychColor.parse(c.tojstring());
        }
        if ((color & 0xFF000000) == 0) color |= 0xFF000000; // opaque unless alpha was supplied
        debugLines.add(new DebugLine(text, color, System.currentTimeMillis()));
        while (debugLines.size() > DEBUG_LINE_MAX) debugLines.remove(0);
        FnfMod.LOGGER.info("[Psych Lua] {}", text);
    }

    /** Draws the debugPrint trace lines top-left, in screen space, above the HUD. */
    public void renderDebugOverlay(GuiGraphics gui) {
        if (debugLines.isEmpty()) return;
        long now = System.currentTimeMillis();
        debugLines.removeIf(line -> now - line.bornMs > DEBUG_LINE_LIFETIME_MS);
        Font font = Minecraft.getInstance().font;
        int x = 10;
        int y = 10;
        int step = font.lineHeight + 2;
        for (DebugLine line : debugLines) {
            long age = now - line.bornMs;
            float fade = age > DEBUG_LINE_LIFETIME_MS - DEBUG_LINE_FADE_MS
                    ? Math.max(0f, (DEBUG_LINE_LIFETIME_MS - age) / (float) DEBUG_LINE_FADE_MS) : 1f;
            int alpha = Math.max(4, Math.round(fade * 255));
            int color = (alpha << 24) | (line.color & 0xFFFFFF);
            gui.drawString(font, line.text, x, y, color, true); // drop shadow for readability
            y += step;
        }
    }

    private void warnOnce(String message) {
        if (warned.add(message)) report(message);
    }

    private static String compactError(Throwable error) {
        Throwable cause = error instanceof LuaError && error.getCause() != null ? error.getCause() : error;
        String text = cause.getMessage();
        return text == null || text.isBlank() ? cause.getClass().getSimpleName() : text.replace('\n', ' ');
    }

    @Override public void close() {
        if (closed) return;
        call("onDestroy");
        closed = true;
        for (String tag : new ArrayList<>(worldSpriteEntities.keySet())) removeWorldSpriteEntity(tag);
        for (LuaObject object : objects.values()) disposeGraphic(object);
        soundPlayer.close();
        fontLoader.close();
        scripts.clear(); scriptBudgets.clear(); scriptSnapshot = null;
        objects.clear(); timers.clear(); tweens.clear(); sharedVars.clear();
    }
}
