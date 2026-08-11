package com.fnfmod.client.lua;

import com.fnfmod.FnfMod;
import com.fnfmod.client.audio.PsychSoundPlayer;
import com.fnfmod.client.render.MachineAtlasCache;
import com.fnfmod.client.render.MachineTextureCache;
import com.fnfmod.client.render.MissingAssetTexture;
import com.fnfmod.client.render.PsychCanvas;
import com.fnfmod.client.render.SparrowAtlas;
import com.fnfmod.gameplay.PlaybackMode;
import com.fnfmod.machine.MachineDefinition;
import com.fnfmod.machine.MachineLibrary;
import com.fnfmod.net.FnfPayloads;
import com.fnfmod.song.SongLibrary;
import com.fnfmod.world.ModContentScope;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Sandboxed, bounded Lua runtime used only for custom machine menus. */
public final class MachineMenuRuntime implements AutoCloseable {

    public interface Host {
        int screenWidth();
        int screenHeight();
        void openSongSelect(byte returnTarget);
        boolean openSongDetails(String songId);
        boolean playSong(String songId, String difficulty, boolean duet, byte playSide,
                         byte playbackMode, byte returnTarget);
        void openSettings();
        void openCharacterEditor();
        void openChartEditor(String songId, String difficulty);
        void closeMenu();
        void saveData(String snbt);
    }

    private record Widget(String kind, String id, LuaTable data) {}
    private record AnimationDef(String prefix, double fps, boolean loop) {}
    private record Tween(String tag, String widgetId, String property, double from, double to,
                         double finalValue, long startedNanos, long durationNanos,
                         String ease, boolean color) {}

    /** Psych-style repeating timer; fires onTimerCompleted(tag, loops, loopsLeft). */
    private record Timer(String tag, long intervalNanos, int totalLoops, long nextAt, int completed) {
        Timer advance(long next) {
            return new Timer(tag, intervalNanos, totalLoops, next, completed + 1);
        }
    }

    private static final class AnimatedState {
        final String id;
        final LuaTable data;
        final Map<String, AnimationDef> animations = new LinkedHashMap<>();
        String animation = "";
        String prefix = "";
        double fps = 24.0;
        boolean loop = true;
        boolean playing;
        boolean finished;
        int frame;
        double frameProgress;
        long lastNanos = System.nanoTime();
        Path pngPath;
        Path xmlPath;
        SparrowAtlas atlas;
        int atlasGeneration = -1;

        AnimatedState(String id, LuaTable data) {
            this.id = id;
            this.data = data;
        }
    }

    private static final int MAX_WIDGETS = 256;
    private static final int MAX_TWEENS = 256;
    // Runaway-script guard is measured in executed Lua instructions, not wall
    // time, so it is immune to JVM warmup. An infinite loop burns through this in
    // a fraction of a second; a normal menu uses a tiny fraction of it.
    private static final long MAX_CALL_INSTRUCTIONS = 50_000_000L;
    // Large wall-clock backstop only, so a pathological call still cannot hang the
    // client. Kept far above cold-start stalls (class loading, Lua compilation,
    // font parsing) that previously tripped a 25 ms budget on the first menu open.
    private static final long CALL_WALL_CEILING_NANOS = 5_000_000_000L;

    private final MachineDefinition definition;
    private final Host host;
    private final List<FnfPayloads.SongInfo> songs;
    private final Globals globals;
    private final BudgetDebugLib budget = new BudgetDebugLib();
    private final List<Widget> widgets = new ArrayList<>();
    private final Map<String, AnimatedState> animatedStates = new LinkedHashMap<>();
    private final Map<String, Tween> tweens = new LinkedHashMap<>();
    private final Map<String, Timer> timers = new LinkedHashMap<>();
    private final LuaTable machineData;
    private final LuaFontLoader fontLoader;
    /** OpenAL sound player for menu playSound; created on first use, closed with the menu. */
    private PsychSoundPlayer soundPlayer;
    // Vanilla menu-blur control. When uncontrolled the screen keeps the player's
    // own blur option; once a script touches it, menuBlurValue is authoritative
    // (a value below 1 means no blur). The tween interpolates it in wall-clock time.
    private boolean menuBlurControlled;
    private double menuBlurValue;
    private boolean menuBlurTweening;
    private String menuBlurTweenTag;
    private double menuBlurFrom, menuBlurTo;
    private long menuBlurStartNanos, menuBlurDurationNanos;
    private String menuBlurEase = "linear";
    private int nextWidgetOrder;
    private byte songExitTarget = FnfPayloads.LeaveC2S.RETURN_MACHINE_MENU;
    private String error;
    // Two-phase open: the script's top-level chunk runs immediately (defining the
    // widgets, so their assets are known), but onOpen and rendering wait until the
    // screen has preloaded everything. This keeps heavy first-frame loading out of
    // the live menu, where it froze the game and desynced tweens.
    private boolean opened;
    // Split preload: background tasks decode assets off the render thread; main tasks
    // do the cheap GL upload / finalize afterwards on the render thread.
    private List<Runnable> backgroundPreload;
    private List<Runnable> mainPreload;
    private final List<String> pendingPrecache = new ArrayList<>();
    private String scriptSource = "";
    // Live cursor position in canvas (1280x720) coordinates, refreshed each frame,
    // plus the cursor table exposed to Lua.
    private double cursorX, cursorY;
    private LuaTable cursor;
    /** Widget ids currently hovered, so onHover/onHoverExit fire once per transition. */
    private final java.util.Set<String> hoveredWidgets = new java.util.HashSet<>();

    public MachineMenuRuntime(MachineDefinition definition, String snbt,
                              List<FnfPayloads.SongInfo> songs, Host host) {
        this.definition = definition;
        this.host = host;
        this.songs = songs == null ? List.of() : List.copyOf(songs);
        List<Path> fontRoots = new ArrayList<>();
        if (definition.root() != null) fontRoots.add(definition.root());
        ModContentScope.activeMod().map(ModContentScope.ActiveMod::root).ifPresent(fontRoots::add);
        this.fontLoader = new LuaFontLoader(fontRoots, SongLibrary.fontsDir(), true);
        CompoundTag initial;
        try {
            initial = snbt == null || snbt.isBlank() ? new CompoundTag() : TagParser.parseTag(snbt);
        } catch (Exception ignored) {
            initial = new CompoundTag();
        }
        machineData = MachineLuaData.toLua(initial);
        globals = JsePlatform.standardGlobals();
        globals.load(budget);
        sandbox(globals);
        installUi();
        installTweens();
        installMachine();
        installSound();
        installMenuBlur();
        installCursor();
        globals.set("machineData", machineData);
        // Machine menus share Psych's fixed canvas. These stay stable when the
        // window resolution or Minecraft GUI scale changes.
        globals.set("screenWidth", PsychCanvas.WIDTH);
        globals.set("screenHeight", PsychCanvas.HEIGHT);
        loadScript();
        buildPreloadTasks();
    }

    public boolean loaded() {
        return error == null;
    }

    public String error() {
        return error;
    }

    public String dataSnbt() {
        return MachineLuaData.toNbt(machineData).toString();
    }

    private void loadScript() {
        if (definition.menuScript() == null || !Files.isRegularFile(definition.menuScript())
                || !ModContentScope.allowsContentPath(definition.menuScript())) {
            error = "menu.lua missing or outside active mod.";
            return;
        }
        try {
            scriptSource = Files.readString(definition.menuScript());
            budget.begin();
            globals.load(scriptSource, definition.menuScript().toString()).call();
            budget.end();
        } catch (Throwable throwable) {
            budget.end();
            fail(throwable);
        }
    }

    /**
     * Runs onOpen and marks the menu live. The screen calls this only after every
     * preload task has finished, so onOpen (which typically starts tweens) begins
     * exactly when rendering does and stays in sync.
     */
    public void open() {
        if (opened || error != null) return;
        opened = true;
        callGlobal("onOpen");
    }

    /**
     * Collects preload work for every asset the top-level widgets reference, split so
     * the heavy decoding runs on a worker thread and only the cheap GL upload runs on
     * the render thread. Fonts and short sounds finalize directly on the main pass.
     */
    private void buildPreloadTasks() {
        backgroundPreload = new ArrayList<>();
        mainPreload = new ArrayList<>();
        if (!loaded()) return;

        java.util.LinkedHashSet<String> fonts = new java.util.LinkedHashSet<>();
        for (Widget widget : widgets) {
            LuaTable data = widget.data();
            switch (widget.kind()) {
                case "image", "sprite" -> {
                    Path file = resolveAsset(data.get("path").optjstring(""));
                    if (file != null) {
                        backgroundPreload.add(() -> MachineTextureCache.prefetch(file));
                        mainPreload.add(() -> MachineTextureCache.get(file));
                    }
                }
                case "animatedSprite" -> {
                    Path png = resolveAsset(data.get("path").optjstring(""));
                    Path xml = resolveAsset(data.get("xml").optjstring(""));
                    if (png != null) {
                        backgroundPreload.add(() -> MachineAtlasCache.prefetch(png, xml));
                        mainPreload.add(() -> MachineAtlasCache.get(png, xml));
                    }
                }
                default -> { }
            }
            String font = data.get("font").optjstring("").trim();
            if (!font.isEmpty()) fonts.add(font);
        }
        for (String font : fonts) mainPreload.add(() -> fontLoader.get(font));

        // Every sound named by a literal in playSound/precacheSound anywhere in the
        // script is preloaded, not just top-level precacheSound, so a sound played
        // from a click handler is already decoded and does not lag on first play.
        java.util.LinkedHashSet<String> sounds = scanSoundNames(scriptSource);
        sounds.addAll(pendingPrecache);
        if (!sounds.isEmpty()) {
            soundPlayer(); // create on the render thread before the worker touches it
            for (String sound : sounds) {
                backgroundPreload.add(() -> soundPlayer().prefetch(sound));
                mainPreload.add(() -> soundPlayer().precache(sound));
            }
        }
    }

    /** String-literal sound paths named by playSound(tag, name, ...) or precacheSound(name). */
    private static java.util.LinkedHashSet<String> scanSoundNames(String source) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        if (source == null || source.isEmpty()) return names;
        String literal = "(\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*')";
        // playSound: tag first, path second — capture the second string literal.
        java.util.regex.Matcher play = java.util.regex.Pattern
                .compile("playSound\\s*\\(\\s*" + literal + "\\s*,\\s*" + literal).matcher(source);
        while (play.find()) addSoundLiteral(names, play.group(2));
        java.util.regex.Matcher precache = java.util.regex.Pattern
                .compile("precacheSound\\s*\\(\\s*" + literal).matcher(source);
        while (precache.find()) addSoundLiteral(names, precache.group(1));
        return names;
    }

    private static void addSoundLiteral(java.util.Set<String> names, String quoted) {
        if (quoted == null || quoted.length() < 2) return;
        String inner = quoted.substring(1, quoted.length() - 1)
                .replace("\\\"", "\"").replace("\\'", "'").replace("\\\\", "\\");
        if (!inner.isBlank()) names.add(inner);
    }

    /** Off-render-thread decode tasks; run these first, then {@link #takeMainPreloadTasks()}. */
    public List<Runnable> takeBackgroundPreloadTasks() {
        List<Runnable> tasks = backgroundPreload == null ? List.of() : backgroundPreload;
        backgroundPreload = null;
        return tasks;
    }

    /** Render-thread finalize tasks; run after the background decode has finished. */
    public List<Runnable> takeMainPreloadTasks() {
        List<Runnable> tasks = mainPreload == null ? List.of() : mainPreload;
        mainPreload = null;
        return tasks;
    }

    private static void sandbox(Globals globals) {
        for (String blocked : new String[]{"luajava", "io", "os", "package", "require",
                "dofile", "loadfile", "load", "debug"}) {
            globals.set(blocked, LuaValue.NIL);
        }
    }

    private void installUi() {
        LuaTable ui = new LuaTable();
        ui.set("label", creator("label", ui));
        ui.set("button", creator("button", ui));
        ui.set("panel", creator("panel", ui));
        ui.set("image", creator("image", ui));
        ui.set("sprite", creator("sprite", ui));
        ui.set("animatedSprite", creator("animatedSprite", ui));
        ui.set("graph", graphCreator(ui));
        ui.set("graphic", ui.get("graph"));
        ui.set("toggle", creator("toggle", ui));
        ui.set("slider", creator("slider", ui));
        ui.set("get", function(args -> {
            String id = args.arg(offset(args, ui)).optjstring("");
            return widgets.stream().filter(widget -> widget.id().equals(id))
                    .map(widget -> (LuaValue) widget.data()).findFirst().orElse(LuaValue.NIL);
        }));
        ui.set("remove", function(args -> {
            String id = args.arg(offset(args, ui)).optjstring("");
            animatedStates.remove(id);
            tweens.values().removeIf(tween -> tween.widgetId().equals(id));
            return LuaValue.valueOf(widgets.removeIf(widget -> widget.id().equals(id)));
        }));
        ui.set("clear", function(args -> {
            widgets.clear();
            animatedStates.clear();
            tweens.clear();
            nextWidgetOrder = 0;
            return LuaValue.TRUE;
        }));
        globals.set("setObjectOrder", function(args -> {
            Widget widget = widget(args.arg1().optjstring(""));
            if (widget == null) return LuaValue.FALSE;
            widget.data().set("order", args.arg(2).checkint());
            return LuaValue.TRUE;
        }));
        globals.set("ui", ui);
    }

    private LuaValue graphCreator(LuaTable ui) {
        return function(args -> {
            if (widgets.size() >= MAX_WIDGETS) throw new LuaError("widget limit exceeded");
            int at = offset(args, ui);
            String id = args.arg(at).checkjstring();
            widgets.removeIf(widget -> widget.id().equals(id));
            animatedStates.remove(id);
            tweens.values().removeIf(tween -> tween.widgetId().equals(id));

            LuaValue shapeArg = args.arg(at + 1);
            boolean explicitShape = shapeArg.isstring();
            String shape = explicitShape ? shapeArg.optjstring("rectangle") : "rectangle";
            int geometryAt = explicitShape ? at + 2 : at + 1;
            LuaTable table = new LuaTable();
            table.set("id", id);
            table.set("kind", "graph");
            table.set("shape", shape);
            table.set("visible", LuaValue.TRUE);
            table.set("alpha", 1.0);
            table.set("order", nextWidgetOrder++);
            table.set("color", luaColor(args.arg(geometryAt + 4), 0xFFFFFF));
            table.set("angle", 0.0);
            table.set("borderSize", 0.0);
            table.set("borderColor", 0xFFFFFF);
            table.set("thickness", 1.0);
            setGeometry(table, args, geometryAt, 64, 64);
            widgets.add(new Widget("graph", id, table));
            return table;
        });
    }

    private LuaValue creator(String kind, LuaTable ui) {
        return function(args -> {
            if (widgets.size() >= MAX_WIDGETS) throw new LuaError("widget limit exceeded");
            int at = offset(args, ui);
            String id = args.arg(at).checkjstring();
            widgets.removeIf(widget -> widget.id().equals(id));
            animatedStates.remove(id);
            tweens.values().removeIf(tween -> tween.widgetId().equals(id));
            LuaTable table = new LuaTable();
            table.set("id", id);
            table.set("kind", kind);
            table.set("visible", LuaValue.TRUE);
            table.set("alpha", 1.0);
            table.set("order", nextWidgetOrder++);
            table.set("color", kind.equals("panel") ? 0xAA111111 : 0xFFFFFFFF);
            if (kind.equals("image") || kind.equals("sprite")) {
                table.set("path", args.arg(at + 1).optjstring(""));
                setGeometry(table, args, at + 2, 64, 64);
                installSpriteFields(table);
            } else if (kind.equals("animatedSprite")) {
                String png = args.arg(at + 1).optjstring("");
                String xml = args.arg(at + 2).optjstring(inferXml(png));
                table.set("path", png);
                table.set("xml", xml);
                setGeometry(table, args, at + 3, 64, 64);
                installSpriteFields(table);
                table.set("fps", 24.0);
                table.set("loop", LuaValue.TRUE);
                table.set("playing", LuaValue.FALSE);
                table.set("finished", LuaValue.FALSE);
                table.set("animation", "");
                table.set("frame", 0);
                AnimatedState state = new AnimatedState(id, table);
                animatedStates.put(id, state);
                installAnimatedControls(state);
            } else {
                table.set("text", args.arg(at + 1).optjstring(""));
                setGeometry(table, args, at + 2,
                        kind.equals("label") ? 0 : 180, kind.equals("label") ? 10 : 24);
                if (!kind.equals("panel")) {
                    table.set("font", "");
                    table.set("fontScale", 1.0);
                    table.set("shadow", LuaValue.TRUE);
                }
            }
            if (kind.equals("toggle")) table.set("value", LuaValue.FALSE);
            if (kind.equals("slider")) {
                table.set("value", 0.0);
                table.set("min", 0.0);
                table.set("max", 1.0);
            }
            widgets.add(new Widget(kind, id, table));
            return table;
        });
    }

    private static void installSpriteFields(LuaTable table) {
        table.set("angle", 0.0);
        table.set("flipX", LuaValue.FALSE);
        table.set("flipY", LuaValue.FALSE);
        table.set("antialiasing", LuaValue.TRUE);
    }

    private static String inferXml(String png) {
        if (png == null || png.isBlank()) return "";
        int dot = png.lastIndexOf('.');
        return (dot < 0 ? png : png.substring(0, dot)) + ".xml";
    }

    private void installAnimatedControls(AnimatedState state) {
        LuaTable table = state.data;
        LuaValue add = function(args -> {
            int at = offset(args, table);
            String name = args.arg(at).optjstring("").trim();
            String prefix = args.arg(at + 1).optjstring("").trim();
            double fps = clampFps(args.arg(at + 2).optdouble(24.0));
            boolean loop = args.arg(at + 3).optboolean(true);
            SparrowAtlas atlas = atlasFor(state);
            if (name.isBlank() || prefix.isBlank() || atlas == null
                    || animationFrames(atlas, prefix).isEmpty()) return LuaValue.FALSE;
            state.animations.put(name, new AnimationDef(prefix, fps, loop));
            return LuaValue.TRUE;
        });
        table.set("addAnimation", add);
        table.set("addAnimationByPrefix", add);

        LuaValue play = function(args -> {
            int at = offset(args, table);
            String name = args.arg(at).optjstring("").trim();
            boolean restart = args.arg(at + 1).optboolean(false);
            return LuaValue.valueOf(playAnimation(state, name, restart));
        });
        table.set("play", play);
        table.set("playAnimation", play);
        table.set("pause", function(args -> {
            state.playing = false;
            syncAnimationData(state);
            return LuaValue.TRUE;
        }));
        table.set("resume", function(args -> {
            if (!state.prefix.isBlank() && !state.finished) state.playing = true;
            state.lastNanos = System.nanoTime();
            syncAnimationData(state);
            return LuaValue.valueOf(state.playing);
        }));
        table.set("stop", function(args -> {
            state.playing = false;
            state.finished = false;
            state.frame = 0;
            state.frameProgress = 0;
            syncAnimationData(state);
            return LuaValue.TRUE;
        }));
        table.set("setFrame", function(args -> {
            int at = offset(args, table);
            SparrowAtlas atlas = atlasFor(state);
            List<SparrowAtlas.Frame> frames = atlas == null ? List.of() : animationFrames(atlas, state.prefix);
            if (frames.isEmpty()) return LuaValue.FALSE;
            state.frame = Math.max(0, Math.min(frames.size() - 1, args.arg(at).optint(0)));
            state.frameProgress = 0;
            syncAnimationData(state);
            return LuaValue.TRUE;
        }));
        table.set("getAnimations", function(args -> {
            LuaTable names = new LuaTable();
            SparrowAtlas atlas = atlasFor(state);
            if (atlas == null) return names;
            int index = 1;
            for (String name : atlas.animationNames()) names.set(index++, name);
            return names;
        }));
    }

    private boolean playAnimation(AnimatedState state, String name, boolean restart) {
        if (name == null || name.isBlank()) return false;
        SparrowAtlas atlas = atlasFor(state);
        if (atlas == null) return false;
        AnimationDef definition = state.animations.get(name);
        String prefix = definition == null ? name : definition.prefix();
        List<SparrowAtlas.Frame> frames = animationFrames(atlas, prefix);
        if (frames.isEmpty()) return false;
        boolean changed = !name.equals(state.animation) || !prefix.equals(state.prefix);
        state.animation = name;
        state.prefix = prefix;
        state.fps = definition == null ? clampFps(state.data.get("fps").optdouble(24.0)) : definition.fps();
        state.loop = definition == null ? state.data.get("loop").optboolean(true) : definition.loop();
        if (changed || restart || state.finished) {
            state.frame = 0;
            state.frameProgress = 0;
        }
        state.playing = true;
        state.finished = false;
        state.lastNanos = System.nanoTime();
        syncAnimationData(state);
        return true;
    }

    private SparrowAtlas atlasFor(AnimatedState state) {
        Path png = resolveAsset(state.data.get("path").optjstring(""));
        Path xml = resolveAsset(state.data.get("xml").optjstring(""));
        int generation = MachineLibrary.generation();
        if (state.atlas != null && state.atlasGeneration == generation
                && java.util.Objects.equals(state.pngPath, png)
                && java.util.Objects.equals(state.xmlPath, xml)) return state.atlas;
        state.pngPath = png;
        state.xmlPath = xml;
        state.atlasGeneration = generation;
        state.atlas = MachineAtlasCache.get(png, xml);
        return state.atlas;
    }

    private static List<SparrowAtlas.Frame> animationFrames(SparrowAtlas atlas, String prefix) {
        if (atlas == null || prefix == null || prefix.isBlank()) return List.of();
        List<SparrowAtlas.Frame> frames = atlas.framesByPrefix(prefix);
        return frames.isEmpty() ? atlas.frames(prefix) : frames;
    }

    private static double clampFps(double fps) {
        return Math.max(0.1, Math.min(240.0, Double.isFinite(fps) ? fps : 24.0));
    }

    private static void syncAnimationData(AnimatedState state) {
        state.data.set("animation", state.animation);
        state.data.set("frame", state.frame);
        state.data.set("playing", LuaValue.valueOf(state.playing));
        state.data.set("finished", LuaValue.valueOf(state.finished));
        state.data.set("fps", state.fps);
        state.data.set("loop", LuaValue.valueOf(state.loop));
    }

    private static void setGeometry(LuaTable table, Varargs args, int at, double width, double height) {
        table.set("x", args.arg(at).optdouble(0.5));
        table.set("y", args.arg(at + 1).optdouble(0.5));
        table.set("width", args.arg(at + 2).optdouble(width));
        table.set("height", args.arg(at + 3).optdouble(height));
    }

    private void installMachine() {
        LuaTable machine = new LuaTable();
        machine.set("profileId", definition.id());
        machine.set("openSongSelect", function(args -> {
            int at = offset(args, machine);
            host.saveData(dataSnbt());
            host.openSongSelect(parseSongExitTarget(args.arg(at), songExitTarget));
            return LuaValue.TRUE;
        }));
        machine.set("join", function(args -> {
            int at = offset(args, machine);
            host.saveData(dataSnbt());
            host.openSongSelect(parseSongExitTarget(args.arg(at), songExitTarget));
            return LuaValue.TRUE;
        }));
        machine.set("getSongs", function(args -> songsTable()));
        machine.set("getSong", function(args -> {
            String id = args.arg(offset(args, machine)).optjstring("");
            FnfPayloads.SongInfo song = findSong(id).orElse(null);
            return song == null ? LuaValue.NIL : songTable(song);
        }));
        machine.set("hasSong", function(args -> {
            String id = args.arg(offset(args, machine)).optjstring("");
            return LuaValue.valueOf(findSong(id).isPresent());
        }));
        machine.set("setSongExitTarget", function(args -> {
            int at = offset(args, machine);
            songExitTarget = parseSongExitTarget(args.arg(at), songExitTarget);
            return LuaValue.valueOf(songExitTargetName(songExitTarget));
        }));
        machine.set("getSongExitTarget", function(args ->
                LuaValue.valueOf(songExitTargetName(songExitTarget))));
        machine.set("openSongDetails", function(args -> {
            String id = args.arg(offset(args, machine)).optjstring("");
            host.saveData(dataSnbt());
            return LuaValue.valueOf(host.openSongDetails(id));
        }));
        machine.set("playSong", function(args -> {
            int at = offset(args, machine);
            String id = args.arg(at).optjstring("");
            FnfPayloads.SongInfo song = findSong(id).orElse(null);
            if (song == null) return LuaValue.FALSE;
            String difficulty = args.arg(at + 1).optjstring(defaultDifficulty(song));
            LuaValue options = args.arg(at + 2);
            boolean duet;
            byte playSide;
            byte playbackMode;
            byte returnTarget;
            if (options.istable()) {
                duet = options.get("duet").optboolean(false);
                playSide = parsePlaySide(options.get("playAs"));
                playbackMode = parsePlaybackMode(options.get("look"));
                LuaValue requestedReturn = options.get("returnTo");
                if (requestedReturn.isnil()) requestedReturn = options.get("onExit");
                returnTarget = parseSongExitTarget(requestedReturn, songExitTarget);
            } else {
                duet = options.optboolean(false);
                playSide = parsePlaySide(args.arg(at + 3));
                playbackMode = parsePlaybackMode(args.arg(at + 4));
                returnTarget = parseSongExitTarget(args.arg(at + 5), songExitTarget);
            }
            host.saveData(dataSnbt());
            return LuaValue.valueOf(host.playSong(id, difficulty, duet, playSide,
                    playbackMode, returnTarget));
        }));
        machine.set("openSettings", function(args -> {
            host.saveData(dataSnbt());
            host.openSettings();
            return LuaValue.TRUE;
        }));
        machine.set("openOptions", machine.get("openSettings"));
        machine.set("openCharacterEditor", function(args -> {
            host.saveData(dataSnbt());
            host.openCharacterEditor();
            return LuaValue.TRUE;
        }));
        machine.set("openChartEditor", function(args -> {
            int at = offset(args, machine);
            String id = args.arg(at).optjstring("");
            String difficulty = args.arg(at + 1).optjstring("");
            host.saveData(dataSnbt());
            host.openChartEditor(id, difficulty);
            return LuaValue.TRUE;
        }));
        machine.set("close", function(args -> {
            host.saveData(dataSnbt());
            host.closeMenu();
            return LuaValue.TRUE;
        }));
        machine.set("saveData", function(args -> {
            host.saveData(dataSnbt());
            return LuaValue.TRUE;
        }));
        globals.set("machine", machine);
    }

    private LuaTable songsTable() {
        LuaTable table = new LuaTable();
        for (int i = 0; i < songs.size(); i++) table.set(i + 1, songTable(songs.get(i)));
        return table;
    }

    private LuaTable songTable(FnfPayloads.SongInfo song) {
        LuaTable table = new LuaTable();
        table.set("id", song.id());
        table.set("name", song.name());
        table.set("opponentIcon", song.opponentIcon());
        LuaTable difficulties = new LuaTable();
        for (int i = 0; i < song.difficulties().size(); i++) {
            difficulties.set(i + 1, song.difficulties().get(i));
        }
        table.set("difficulties", difficulties);
        return table;
    }

    private java.util.Optional<FnfPayloads.SongInfo> findSong(String id) {
        if (id == null || id.isBlank()) return java.util.Optional.empty();
        return songs.stream().filter(song -> song.id().equalsIgnoreCase(id.trim())).findFirst();
    }

    private static String defaultDifficulty(FnfPayloads.SongInfo song) {
        if (song.difficulties().contains("normal")) return "normal";
        return song.difficulties().isEmpty() ? "normal" : song.difficulties().getFirst();
    }

    private static byte parsePlaySide(LuaValue value) {
        if (value.isnumber()) return (byte) Math.max(0, Math.min(2, value.toint()));
        return switch (value.optjstring("player").trim().toLowerCase(Locale.ROOT)) {
            case "opponent", "enemy", "dad" -> 1;
            case "both" -> 2;
            default -> 0;
        };
    }

    private void installTweens() {
        installTweenFunction("doTweenX", "x");
        installTweenFunction("doTweenY", "y");
        installTweenFunction("doTweenAlpha", "alpha");
        installTweenFunction("doTweenAngle", "angle");
        installTweenFunction("doTweenWidth", "width");
        installTweenFunction("doTweenHeight", "height");
        installTweenFunction("doTweenFontScale", "fontScale");
        globals.set("doTweenColor", function(args -> {
            String tag = args.arg(1).checkjstring();
            String widgetId = args.arg(2).checkjstring();
            Widget widget = widget(widgetId);
            if (widget == null) return LuaValue.FALSE;
            int from = widget.data().get("color").optint(0xFFFFFF) & 0xFFFFFF;
            int to = args.arg(3).isnumber()
                    ? args.arg(3).toint() & 0xFFFFFF
                    : PsychColor.parse(args.arg(3).optjstring("FFFFFF")) & 0xFFFFFF;
            startTween(tag, widgetId, "color", from, to,
                    args.arg(4).optdouble(1), args.arg(5).optjstring("linear"), true);
            return LuaValue.TRUE;
        }));
        globals.set("cancelTween", function(args -> {
            String tag = args.arg(1).optjstring("");
            boolean removed = tweens.remove(tag) != null;
            if (menuBlurTweening && tag.equals(menuBlurTweenTag)) {
                menuBlurTweening = false;
                menuBlurTweenTag = null;
                removed = true;
            }
            return LuaValue.valueOf(removed);
        }));
        // Psych-style timers. runTimer(tag, seconds, loops) fires
        // onTimerCompleted(tag, loops, loopsLeft) each interval; loops defaults to 1.
        globals.set("runTimer", function(args -> {
            runTimer(args.arg(1).optjstring(""), args.arg(2).optdouble(1), args.arg(3).optint(1));
            return LuaValue.NIL;
        }));
        globals.set("cancelTimer", function(args ->
                LuaValue.valueOf(timers.remove(args.arg(1).optjstring("")) != null)));
    }

    private void runTimer(String tag, double seconds, int loops) {
        if (!timers.containsKey(tag) && timers.size() >= MAX_TWEENS) {
            throw new LuaError("timer limit exceeded");
        }
        long interval = Math.max(1_000_000L, (long) (Math.max(0, seconds) * 1_000_000_000L));
        timers.put(tag, new Timer(tag, interval, Math.max(1, loops), System.nanoTime() + interval, 0));
    }

    private void updateTimers() {
        long now = System.nanoTime();
        for (Timer timer : new ArrayList<>(timers.values())) {
            if (now < timer.nextAt()) continue;
            int completed = timer.completed() + 1;
            int left = Math.max(0, timer.totalLoops() - completed);
            if (left == 0) {
                timers.remove(timer.tag());
            } else {
                timers.put(timer.tag(), timer.advance(now + timer.intervalNanos()));
            }
            callGlobal("onTimerCompleted", LuaValue.valueOf(timer.tag()),
                    LuaValue.valueOf(completed), LuaValue.valueOf(left));
        }
    }

    private void installTweenFunction(String name, String property) {
        globals.set(name, function(args -> {
            String tag = args.arg(1).checkjstring();
            String widgetId = args.arg(2).checkjstring();
            Widget widget = widget(widgetId);
            if (widget == null) return LuaValue.FALSE;
            double from = widget.data().get(property).optdouble(defaultTweenValue(property));
            startTween(tag, widgetId, property, from, args.arg(3).optdouble(from),
                    args.arg(4).optdouble(1), args.arg(5).optjstring("linear"), false);
            return LuaValue.TRUE;
        }));
    }

    private void startTween(String tag, String widgetId, String property, double from, double to,
                            double seconds, String ease, boolean color) {
        if (tag == null || tag.isBlank()) throw new LuaError("tween tag cannot be blank");
        if (!tweens.containsKey(tag) && tweens.size() >= MAX_TWEENS) {
            throw new LuaError("tween limit exceeded");
        }
        double finalValue = to;
        if (!color && (property.equals("x") || property.equals("y"))
                && normalizedCoordinate(from) != normalizedCoordinate(to)) {
            double extent = property.equals("x") ? PsychCanvas.WIDTH : PsychCanvas.HEIGHT;
            if (normalizedCoordinate(from)) from *= extent;
            if (normalizedCoordinate(to)) to *= extent;
        }
        double safeSeconds = Math.max(0, Math.min(3600, seconds));
        long duration = Math.max(1_000_000L, (long) (safeSeconds * 1_000_000_000L));
        tweens.put(tag, new Tween(tag, widgetId, property, from, to, finalValue,
                System.nanoTime(), duration,
                ease == null || ease.isBlank() ? "linear" : ease, color));
    }

    private static double defaultTweenValue(String property) {
        return property.equals("alpha") || property.equals("fontScale") ? 1 : 0;
    }

    private Widget widget(String id) {
        if (id == null || id.isBlank()) return null;
        return widgets.stream().filter(widget -> widget.id().equals(id)).findFirst().orElse(null);
    }

    private static byte parsePlaybackMode(LuaValue value) {
        if (value.isnumber()) return PlaybackMode.fromNetworkId((byte) value.toint()).networkId();
        return PlaybackMode.parse(value.optjstring("minecraft")).networkId();
    }

    private static byte parseSongExitTarget(LuaValue value, byte fallback) {
        if (value == null || value.isnil()) return fallback;
        return switch (value.optjstring("").trim().toLowerCase(Locale.ROOT)) {
            case "menu", "machine", "custom", "custom-menu" ->
                    FnfPayloads.LeaveC2S.RETURN_MACHINE_MENU;
            case "world", "close", "none", "no-menu" ->
                    FnfPayloads.LeaveC2S.RETURN_WORLD;
            case "selector", "songs", "song-select", "song-selector" ->
                    FnfPayloads.LeaveC2S.RETURN_SELECTOR;
            default -> fallback;
        };
    }

    private static String songExitTargetName(byte target) {
        return switch (FnfPayloads.LeaveC2S.normalizeReturnTarget(target)) {
            case FnfPayloads.LeaveC2S.RETURN_MACHINE_MENU -> "menu";
            case FnfPayloads.LeaveC2S.RETURN_SELECTOR -> "selector";
            default -> "world";
        };
    }

    public void tick() {
        if (soundPlayer != null) soundPlayer.update();
        callGlobal("onUpdate", LuaValue.valueOf(0.05));
    }

    private void advanceTweens() {
        updateTimers();
        long now = System.nanoTime();
        if (menuBlurTweening) {
            double progress = Math.min(1, Math.max(0,
                    (now - menuBlurStartNanos) / (double) menuBlurDurationNanos));
            double eased = PsychEasing.apply(menuBlurEase, progress);
            menuBlurValue = menuBlurFrom + (menuBlurTo - menuBlurFrom) * eased;
            if (progress >= 1) {
                menuBlurValue = menuBlurTo;
                menuBlurTweening = false;
                String tag = menuBlurTweenTag;
                menuBlurTweenTag = null;
                if (tag != null && !tag.isBlank()) callGlobal("onTweenCompleted", LuaValue.valueOf(tag));
            }
        }
        for (Tween tween : List.copyOf(tweens.values())) {
            Widget widget = widget(tween.widgetId());
            if (widget == null) {
                tweens.remove(tween.tag(), tween);
                continue;
            }
            double progress = Math.min(1, Math.max(0,
                    (now - tween.startedNanos()) / (double) tween.durationNanos()));
            double eased = PsychEasing.apply(tween.ease(), progress);
            if (tween.color()) {
                widget.data().set(tween.property(), blendColor(
                        (int) tween.from(), (int) tween.to(), eased));
            } else {
                widget.data().set(tween.property(), progress >= 1 ? tween.finalValue()
                        : tween.from() + (tween.to() - tween.from()) * eased);
            }
            if (progress >= 1 && tweens.remove(tween.tag(), tween)) {
                call(widget.data().get("onTweenCompleted"), widget.data(),
                        LuaValue.valueOf(tween.tag()));
                callGlobal("onTweenCompleted", LuaValue.valueOf(tween.tag()));
            }
        }
    }

    private static int blendColor(int from, int to, double amount) {
        double t = Math.max(0, Math.min(1, amount));
        int r = (int) Math.round(((from >> 16) & 255) + (((to >> 16) & 255) - ((from >> 16) & 255)) * t);
        int g = (int) Math.round(((from >> 8) & 255) + (((to >> 8) & 255) - ((from >> 8) & 255)) * t);
        int b = (int) Math.round((from & 255) + ((to & 255) - (from & 255)) * t);
        return r << 16 | g << 8 | b;
    }

    public void render(GuiGraphics gui, Font font, int mouseX, int mouseY) {
        advanceTweens();
        advanceAnimations();
        double canvasMouseX = screenToCanvasX(mouseX);
        double canvasMouseY = screenToCanvasY(mouseY);
        cursorX = canvasMouseX;
        cursorY = canvasMouseY;
        updateCursor();
        updateHover();
        List<Widget> ordered = orderedWidgets();
        PsychCanvas.push(gui, 1f);
        try {
            for (int i = 0; i < ordered.size(); i++) {
                gui.pose().pushPose();
                try {
                    gui.pose().translate(0, 0, i + 1);
                    renderWidget(gui, font, ordered.get(i), canvasMouseX, canvasMouseY);
                    gui.flush();
                } finally {
                    gui.pose().popPose();
                }
            }
        } finally {
            PsychCanvas.pop(gui);
        }
    }

    private List<Widget> orderedWidgets() {
        List<Widget> ordered = new ArrayList<>(widgets);
        ordered.sort(Comparator.comparingInt(widget -> widget.data().get("order").optint(0)));
        return ordered;
    }

    private void advanceAnimations() {
        long now = System.nanoTime();
        for (AnimatedState state : List.copyOf(animatedStates.values())) {
            SparrowAtlas atlas = atlasFor(state);
            if (atlas == null) continue;
            if (state.prefix.isBlank()) {
                String initial = state.animations.isEmpty()
                        ? atlas.animationNames().stream().findFirst().orElse("")
                        : state.animations.keySet().iterator().next();
                if (!initial.isBlank()) playAnimation(state, initial, false);
                continue;
            }
            List<SparrowAtlas.Frame> frames = animationFrames(atlas, state.prefix);
            if (frames.isEmpty()) {
                state.playing = false;
                syncAnimationData(state);
                continue;
            }
            state.fps = clampFps(state.data.get("fps").optdouble(state.fps));
            state.loop = state.data.get("loop").optboolean(state.loop);
            double elapsed = Math.max(0, Math.min(0.25, (now - state.lastNanos) / 1_000_000_000.0));
            state.lastNanos = now;
            if (!state.playing || state.finished) continue;
            state.frameProgress += elapsed * state.fps;
            int steps = (int) Math.floor(state.frameProgress);
            state.frameProgress -= steps;
            boolean completed = false;
            while (steps-- > 0) {
                if (state.frame + 1 < frames.size()) {
                    state.frame++;
                } else if (state.loop) {
                    state.frame = 0;
                } else {
                    state.frame = frames.size() - 1;
                    state.playing = false;
                    state.finished = true;
                    completed = true;
                    break;
                }
            }
            syncAnimationData(state);
            if (completed && animatedStates.get(state.id) == state) {
                call(state.data.get("onComplete"), state.data, LuaValue.valueOf(state.animation));
            }
        }
    }

    private void renderWidget(GuiGraphics gui, Font font, Widget widget,
                              double mouseX, double mouseY) {
        LuaTable data = widget.data();
        if (!data.get("visible").optboolean(true)) return;
        int width = Math.max(1, (int) Math.round(data.get("width").optdouble(1)));
        int height = Math.max(1, (int) Math.round(data.get("height").optdouble(1)));
        int centerX = coordinate(data.get("x").optdouble(0.5), PsychCanvas.WIDTH);
        int centerY = coordinate(data.get("y").optdouble(0.5), PsychCanvas.HEIGHT);
        int x = centerX - width / 2;
        int y = centerY - height / 2;
        int color = color(data);
        Font textFont = widgetFont(data, font);
        float fontScale = (float) Math.max(0.1, Math.min(16.0,
                data.get("fontScale").optdouble(1.0)));
        boolean textShadow = data.get("shadow").optboolean(true);
        boolean hover = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        data.set("hovered", LuaValue.valueOf(hover && data.get("visible").optboolean(true)));
        switch (widget.kind()) {
            case "panel" -> gui.fill(x, y, x + width, y + height, color);
            case "graph" -> renderGraph(gui, data, centerX, centerY, width, height);
            case "image", "sprite" -> renderImage(gui, data, centerX, centerY, width, height);
            case "animatedSprite" -> renderAnimatedSprite(gui, widget.id(), data,
                    centerX, centerY, width, height);
            case "label" -> drawCenteredText(gui, textFont, data.get("text").optjstring(""),
                    centerX, centerY, color, fontScale, textShadow);
            case "button" -> {
                gui.fill(x, y, x + width, y + height, hover ? 0xCC555555 : 0xCC333333);
                drawCenteredText(gui, textFont, data.get("text").optjstring(""),
                        centerX, centerY, color, fontScale, textShadow);
            }
            case "toggle" -> {
                boolean value = data.get("value").optboolean(false);
                gui.fill(x, y, x + width, y + height, value ? 0xCC397A49 : 0xCC5A3333);
                drawCenteredText(gui, textFont,
                        data.get("text").optjstring("") + ": " + (value ? "ON" : "OFF"),
                        centerX, centerY, color, fontScale, textShadow);
            }
            case "slider" -> {
                double min = data.get("min").optdouble(0);
                double max = data.get("max").optdouble(1);
                double value = data.get("value").optdouble(min);
                double ratio = max <= min ? 0 : Math.max(0, Math.min(1, (value - min) / (max - min)));
                gui.fill(x, y, x + width, y + height, 0xCC222222);
                gui.fill(x, y, x + (int) Math.round(width * ratio), y + height, 0xCC5577AA);
                drawCenteredText(gui, textFont, data.get("text").optjstring(""),
                        centerX, centerY, color, fontScale, textShadow);
            }
        }
    }

    private void renderGraph(GuiGraphics gui, LuaTable data, int centerX, int centerY,
                             int requestedWidth, int requestedHeight) {
        int width = Math.max(1, Math.min(requestedWidth, PsychCanvas.WIDTH * 2));
        int height = Math.max(1, Math.min(requestedHeight, PsychCanvas.HEIGHT * 2));
        int fill = color(data);
        int border = widgetColor(data, "borderColor", 0xFFFFFF);
        int borderSize = Math.max(0, Math.min(Math.max(width, height),
                (int) Math.round(data.get("borderSize").optdouble(0))));
        int thickness = Math.max(1, Math.min(Math.max(width, height),
                (int) Math.round(data.get("thickness").optdouble(1))));
        String shape = data.get("shape").optjstring("rectangle").trim().toLowerCase(Locale.ROOT);

        gui.pose().pushPose();
        try {
            gui.pose().translate(centerX, centerY, 0);
            gui.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(
                    (float) data.get("angle").optdouble(0)));
            switch (shape) {
                case "circle", "ellipse", "oval" -> {
                    if (borderSize > 0) drawEllipse(gui, width, height, border);
                    drawEllipse(gui, Math.max(0, width - borderSize * 2),
                            Math.max(0, height - borderSize * 2), fill);
                }
                case "line" -> {
                    int x1 = -width / 2;
                    int y1 = -height / 2;
                    int x2 = x1 + width - 1;
                    int y2 = y1 + height - 1;
                    if (borderSize > 0) drawLine(gui, x1, y1, x2, y2,
                            thickness + borderSize * 2, border);
                    drawLine(gui, x1, y1, x2, y2, thickness, fill);
                }
                case "triangle", "polygon" -> {
                    List<double[]> points = graphPoints(data, shape, width, height);
                    fillPolygon(gui, points, fill);
                    if (borderSize > 0) strokePolygon(gui, points, borderSize, border);
                }
                default -> {
                    int left = -width / 2;
                    int top = -height / 2;
                    if (borderSize > 0) gui.fill(left, top, left + width, top + height, border);
                    int inset = Math.min(borderSize, Math.min(width, height) / 2);
                    gui.fill(left + inset, top + inset, left + width - inset,
                            top + height - inset, fill);
                }
            }
        } finally {
            gui.pose().popPose();
        }
    }

    private static void drawEllipse(GuiGraphics gui, int width, int height, int color) {
        if (width <= 0 || height <= 0 || color >>> 24 == 0) return;
        double rx = width / 2.0;
        double ry = height / 2.0;
        int top = -height / 2;
        for (int row = 0; row < height; row++) {
            double dy = (top + row + 0.5) / ry;
            double span = rx * Math.sqrt(Math.max(0, 1 - dy * dy));
            int left = (int) Math.ceil(-span);
            int right = (int) Math.floor(span);
            if (right >= left) gui.fill(left, top + row, right + 1, top + row + 1, color);
        }
    }

    private static void drawLine(GuiGraphics gui, int x1, int y1, int x2, int y2,
                                 int thickness, int color) {
        if (color >>> 24 == 0) return;
        int steps = Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1));
        int radius = Math.max(0, thickness / 2);
        for (int i = 0; i <= Math.max(1, steps); i++) {
            double t = steps == 0 ? 0 : i / (double) steps;
            int x = (int) Math.round(x1 + (x2 - x1) * t);
            int y = (int) Math.round(y1 + (y2 - y1) * t);
            gui.fill(x - radius, y - radius, x - radius + thickness,
                    y - radius + thickness, color);
        }
    }

    private static List<double[]> graphPoints(LuaTable data, String shape, int width, int height) {
        List<double[]> points = new ArrayList<>();
        LuaValue raw = data.get("points");
        if (raw.istable()) {
            LuaTable table = raw.checktable();
            if (table.length() > 0 && table.get(1).istable()) {
                for (int i = 1; i <= Math.min(64, table.length()); i++) {
                    LuaValue point = table.get(i);
                    if (!point.istable()) continue;
                    points.add(new double[]{point.get(1).optdouble(0), point.get(2).optdouble(0)});
                }
            } else {
                for (int i = 1; i + 1 <= Math.min(128, table.length()); i += 2) {
                    points.add(new double[]{table.get(i).optdouble(0), table.get(i + 1).optdouble(0)});
                }
            }
        }
        if (points.size() >= 3) return points;
        int left = -width / 2, right = left + width - 1;
        int top = -height / 2, bottom = top + height - 1;
        if (shape.equals("triangle")) {
            return List.of(new double[]{0, top}, new double[]{right, bottom},
                    new double[]{left, bottom});
        }
        return List.of(new double[]{0, top}, new double[]{right, 0},
                new double[]{0, bottom}, new double[]{left, 0});
    }

    private static void fillPolygon(GuiGraphics gui, List<double[]> points, int color) {
        if (points.size() < 3 || color >>> 24 == 0) return;
        int minY = (int) Math.floor(points.stream().mapToDouble(point -> point[1]).min().orElse(0));
        int maxY = (int) Math.ceil(points.stream().mapToDouble(point -> point[1]).max().orElse(0));
        minY = Math.max(-8192, minY);
        maxY = Math.min(8192, maxY);
        for (int y = minY; y <= maxY; y++) {
            double scanY = y + 0.5;
            List<Double> intersections = new ArrayList<>();
            for (int i = 0; i < points.size(); i++) {
                double[] a = points.get(i);
                double[] b = points.get((i + 1) % points.size());
                if ((a[1] <= scanY && b[1] > scanY) || (b[1] <= scanY && a[1] > scanY)) {
                    intersections.add(a[0] + (scanY - a[1]) * (b[0] - a[0]) / (b[1] - a[1]));
                }
            }
            intersections.sort(Double::compareTo);
            for (int i = 0; i + 1 < intersections.size(); i += 2) {
                int left = (int) Math.ceil(intersections.get(i));
                int right = (int) Math.floor(intersections.get(i + 1));
                if (right >= left) gui.fill(left, y, right + 1, y + 1, color);
            }
        }
    }

    private static void strokePolygon(GuiGraphics gui, List<double[]> points,
                                      int thickness, int color) {
        for (int i = 0; i < points.size(); i++) {
            double[] a = points.get(i);
            double[] b = points.get((i + 1) % points.size());
            drawLine(gui, (int) Math.round(a[0]), (int) Math.round(a[1]),
                    (int) Math.round(b[0]), (int) Math.round(b[1]), thickness, color);
        }
    }

    private int widgetColor(LuaTable data, String property, int fallback) {
        int alpha = (int) Math.round(Math.max(0, Math.min(1,
                data.get("alpha").optdouble(1))) * 255);
        return alpha << 24 | data.get(property).optint(fallback) & 0xFFFFFF;
    }

    private static int luaColor(LuaValue value, int fallback) {
        if (value == null || value.isnil()) return fallback;
        return value.isnumber() ? value.toint() & 0xFFFFFF
                : PsychColor.parse(value.optjstring("FFFFFF")) & 0xFFFFFF;
    }

    private Font widgetFont(LuaTable data, Font fallback) {
        String name = data.get("font").optjstring("").trim();
        if (name.isEmpty()) return fallback;
        Font selected = fontLoader.get(name);
        return selected == null ? fallback : selected;
    }

    private static void drawCenteredText(GuiGraphics gui, Font font, String text,
                                         int centerX, int centerY, int color, float scale,
                                         boolean shadow) {
        gui.pose().pushPose();
        try {
            gui.pose().translate(centerX, centerY, 0);
            gui.pose().scale(scale, scale, 1);
            gui.drawString(font, text, -font.width(text) / 2, -font.lineHeight / 2,
                    color, shadow);
        } finally {
            gui.pose().popPose();
        }
    }

    private void renderImage(GuiGraphics gui, LuaTable data, int centerX, int centerY,
                             int width, int height) {
        try {
            String requestedPath = data.get("path").optjstring("");
            Path file = resolveAsset(requestedPath);
            ResourceLocation texture = MachineTextureCache.get(file);
            boolean missing = texture == null && !requestedPath.isBlank();
            if (texture == null && !missing) return;
            if (missing) {
                renderMissingAsset(gui, data, centerX, centerY, width, height);
                return;
            }
            MachineTextureCache.setAntialiasing(file, data.get("antialiasing").optboolean(true));
            beginSpriteColor(gui, data);
            gui.pose().pushPose();
            try {
                gui.pose().translate(centerX, centerY, 0);
                gui.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(
                        (float) data.get("angle").optdouble(0)));
                gui.pose().scale(data.get("flipX").optboolean(false) ? -1 : 1,
                        data.get("flipY").optboolean(false) ? -1 : 1, 1);
                gui.blit(texture, -width / 2, -height / 2, 0, 0,
                        width, height, width, height);
            } finally {
                gui.pose().popPose();
                endSpriteColor(gui);
            }
        } catch (Exception ignored) {}
    }

    private void renderAnimatedSprite(GuiGraphics gui, String id, LuaTable data,
                                      int centerX, int centerY, int width, int height) {
        AnimatedState state = animatedStates.get(id);
        SparrowAtlas atlas = state == null ? null : atlasFor(state);
        if (atlas == null) {
            if (state != null && !data.get("path").optjstring("").isBlank()) {
                renderMissingAsset(gui, data, centerX, centerY, width, height);
            }
            return;
        }
        if (state.prefix.isBlank()) return;
        List<SparrowAtlas.Frame> frames = animationFrames(atlas, state.prefix);
        if (frames.isEmpty()) return;
        SparrowAtlas.Frame frame = frames.get(Math.max(0, Math.min(frames.size() - 1, state.frame)));
        atlas.setAntialiasing(data.get("antialiasing").optboolean(true));
        float scale = Math.min(width / (float) Math.max(1, frame.frameW),
                height / (float) Math.max(1, frame.frameH));
        int rgb = data.get("color").optint(0xFFFFFF);
        float oldAlpha = SparrowAtlas.globalAlpha;
        float oldR = SparrowAtlas.tintR, oldG = SparrowAtlas.tintG, oldB = SparrowAtlas.tintB;
        SparrowAtlas.globalAlpha = oldAlpha * (float) Math.max(0, Math.min(1,
                data.get("alpha").optdouble(1)));
        SparrowAtlas.tintR = oldR * ((rgb >> 16 & 255) / 255f);
        SparrowAtlas.tintG = oldG * ((rgb >> 8 & 255) / 255f);
        SparrowAtlas.tintB = oldB * ((rgb & 255) / 255f);
        gui.pose().pushPose();
        try {
            gui.pose().translate(centerX, centerY, 0);
            gui.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(
                    (float) data.get("angle").optdouble(0)));
            gui.pose().scale(data.get("flipX").optboolean(false) ? -1 : 1,
                    data.get("flipY").optboolean(false) ? -1 : 1, 1);
            atlas.drawScaled(gui, frame, 0, 0, scale);
        } finally {
            gui.pose().popPose();
            SparrowAtlas.globalAlpha = oldAlpha;
            SparrowAtlas.tintR = oldR;
            SparrowAtlas.tintG = oldG;
            SparrowAtlas.tintB = oldB;
        }
    }

    private static void renderMissingAsset(GuiGraphics gui, LuaTable data,
                                           int centerX, int centerY, int width, int height) {
        float alpha = (float) Math.max(0, Math.min(1, data.get("alpha").optdouble(1)));
        gui.flush();
        gui.setColor(1, 1, 1, alpha);
        gui.pose().pushPose();
        try {
            gui.pose().translate(centerX, centerY, 0);
            gui.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(
                    (float) data.get("angle").optdouble(0)));
            gui.pose().scale(data.get("flipX").optboolean(false) ? -1 : 1,
                    data.get("flipY").optboolean(false) ? -1 : 1, 1);
            gui.blit(MissingAssetTexture.texture(), -width / 2, -height / 2,
                    width, height, 0, 0,
                    MissingAssetTexture.width(), MissingAssetTexture.height(),
                    MissingAssetTexture.width(), MissingAssetTexture.height());
        } finally {
            gui.pose().popPose();
            endSpriteColor(gui);
        }
    }

    /**
     * Resolves a menu asset path. By default it looks in the machine folder first,
     * then falls back to the owning mod's root, so assets shared across machines
     * (sounds, fonts, images) can live in {@code <mod>/sounds/...} etc. A {@code mod:}
     * prefix forces the mod root and {@code machine:} forces the machine folder.
     */
    private Path resolveAsset(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String path = raw.trim();
        Path modRoot = ModContentScope.activeMod().map(ModContentScope.ActiveMod::root).orElse(null);
        if (path.regionMatches(true, 0, "mod:", 0, 4)) {
            return resolveUnder(modRoot, path.substring(4));
        }
        if (path.regionMatches(true, 0, "machine:", 0, 8)) {
            return resolveUnder(definition.root(), path.substring(8));
        }
        Path fromMachine = resolveUnder(definition.root(), path);
        if (fromMachine != null && Files.isRegularFile(fromMachine)) return fromMachine;
        Path fromMod = resolveUnder(modRoot, path);
        if (fromMod != null && Files.isRegularFile(fromMod)) return fromMod;
        return fromMachine != null ? fromMachine : fromMod;
    }

    private static Path resolveUnder(Path base, String raw) {
        if (base == null || raw == null || raw.isBlank()) return null;
        Path file = base.resolve(raw).normalize();
        return file.startsWith(base) && ModContentScope.allowsContentPath(file) ? file : null;
    }

    // ------------------------------------------------------------------- sound

    /**
     * Menu sound playback. Following the menu widget convention, the tag and path
     * come first, then the optional config. Files resolve like every other asset:
     * relative to the machine folder, staying inside the active mod. An OGG
     * extension is optional so {@code playSound('click', 'sounds/click')} matches
     * Psych's convention. The tag lets a sound be stopped, paused/resumed, or
     * re-volumed later; pass an empty tag for a fire-and-forget sound from a pool.
     */
    private void installSound() {
        globals.set("playSound", function(args -> {
            String tag = args.arg(1).optjstring("");
            String name = args.arg(2).optjstring("");
            float volume = (float) args.arg(3).optdouble(1.0);
            boolean loop = args.arg(4).optboolean(false);
            return LuaValue.valueOf(soundPlayer().play(name,
                    volume, tag.isBlank() ? null : tag, loop));
        }));
        globals.set("stopSound", function(args -> {
            if (soundPlayer != null) soundPlayer.stop(args.arg(1).optjstring(""));
            return LuaValue.NIL;
        }));
        globals.set("pauseSound", function(args -> {
            if (soundPlayer != null) soundPlayer.pause(args.arg(1).optjstring(""));
            return LuaValue.NIL;
        }));
        globals.set("resumeSound", function(args -> {
            if (soundPlayer != null) soundPlayer.resume(args.arg(1).optjstring(""));
            return LuaValue.NIL;
        }));
        globals.set("setSoundVolume", function(args -> {
            if (soundPlayer != null) {
                soundPlayer.setVolume(args.arg(1).optjstring(""), (float) args.arg(2).optdouble(1.0));
            }
            return LuaValue.NIL;
        }));
        globals.set("precacheSound", function(args -> {
            String name = args.arg(1).optjstring("");
            // Before the menu opens, defer to the preload gate so the decode happens
            // during loading instead of freezing the live menu; after, decode now.
            if (!opened) {
                pendingPrecache.add(name);
                return LuaValue.TRUE;
            }
            return LuaValue.valueOf(soundPlayer().precache(name));
        }));
    }

    private PsychSoundPlayer soundPlayer() {
        if (soundPlayer == null) {
            soundPlayer = new PsychSoundPlayer(this::resolveSound, this::onSoundFinished);
        }
        return soundPlayer;
    }

    private Path resolveSound(String name) {
        Path direct = resolveAsset(name);
        if (direct != null && Files.isRegularFile(direct)) return direct;
        String withExt = name != null && name.toLowerCase(Locale.ROOT).endsWith(".ogg")
                ? name : name + ".ogg";
        return resolveAsset(withExt);
    }

    private void onSoundFinished(String tag) {
        if (tag != null && !tag.isBlank()) callGlobal("onSoundFinished", LuaValue.valueOf(tag));
    }

    // --------------------------------------------------------------- menu blur

    /**
     * Controls Minecraft's default menu background blur for this menu only. Until a
     * script calls one of these, the player's own blur setting is left alone. A
     * radius below 1 disables the blur; larger values blur more. {@code resetMenuBlur}
     * hands control back to the vanilla setting.
     */
    private void installMenuBlur() {
        globals.set("setMenuBlur", function(args -> {
            takeMenuBlur(Math.max(0, args.arg(1).optdouble(0)));
            return LuaValue.NIL;
        }));
        globals.set("enableMenuBlur", function(args -> {
            takeMenuBlur(Math.max(1, args.arg(1).optdouble(enabledMenuBlurDefault())));
            return LuaValue.NIL;
        }));
        globals.set("disableMenuBlur", function(args -> {
            takeMenuBlur(0);
            return LuaValue.NIL;
        }));
        globals.set("resetMenuBlur", function(args -> {
            menuBlurControlled = false;
            menuBlurTweening = false;
            menuBlurTweenTag = null;
            return LuaValue.NIL;
        }));
        globals.set("getMenuBlur", function(args ->
                LuaValue.valueOf(menuBlurControlled ? menuBlurValue : configuredMenuBlur())));
        globals.set("doTweenMenuBlur", function(args -> {
            String tag = args.arg(1).optjstring("");
            double target = Math.max(0, args.arg(2).optdouble(0));
            double seconds = Math.max(0, Math.min(3600, args.arg(3).optdouble(1)));
            String ease = args.arg(4).optjstring("linear");
            menuBlurFrom = menuBlurControlled ? menuBlurValue : configuredMenuBlur();
            menuBlurTo = target;
            menuBlurStartNanos = System.nanoTime();
            menuBlurDurationNanos = Math.max(1_000_000L, (long) (seconds * 1_000_000_000L));
            menuBlurEase = ease == null || ease.isBlank() ? "linear" : ease;
            menuBlurTweenTag = tag;
            menuBlurTweening = true;
            menuBlurControlled = true;
            menuBlurValue = menuBlurFrom;
            return LuaValue.TRUE;
        }));
    }

    private void takeMenuBlur(double value) {
        menuBlurControlled = true;
        menuBlurTweening = false;
        menuBlurTweenTag = null;
        menuBlurValue = value;
    }

    /** The player's live accessibility option, including 0 (Off). */
    private static double configuredMenuBlur() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft == null ? 0 : Math.max(0, minecraft.options.getMenuBackgroundBlurriness());
    }

    /** A visible default for enableMenuBlur() when the player currently has blur disabled. */
    private static double enabledMenuBlurDefault() {
        double configured = configuredMenuBlur();
        return configured >= 1 ? configured : 8;
    }

    /** True once a script has taken over the menu blur; the screen then uses {@link #currentMenuBlur()}. */
    public boolean isMenuBlurControlled() {
        return menuBlurControlled;
    }

    /** The blur radius the screen should apply this frame (below 1 means no blur). */
    public double currentMenuBlur() {
        return menuBlurValue;
    }

    // ------------------------------------------------------------------ cursor

    /**
     * Cursor helpers. All positions are in canvas (1280x720) coordinates, matching
     * widget x/y. The live {@code cursor} table is refreshed every frame:
     * {@code cursor.x}, {@code cursor.y}, {@code cursor.down} (left button), and
     * {@code cursor.overId} (topmost visible widget under the cursor, or ""). Each
     * widget also gets a live {@code hovered} boolean.
     */
    private void installCursor() {
        cursor = new LuaTable();
        cursor.set("x", 0.0);
        cursor.set("y", 0.0);
        cursor.set("down", LuaValue.FALSE);
        cursor.set("overId", "");
        globals.set("cursor", cursor);

        globals.set("getMouseX", function(args -> LuaValue.valueOf(cursorX)));
        globals.set("getMouseY", function(args -> LuaValue.valueOf(cursorY)));
        globals.set("isMouseDown", function(args ->
                LuaValue.valueOf(mouseButtonDown(args.arg(1).optint(0)))));
        globals.set("getHoveredObject", function(args ->
                LuaValue.valueOf(hoveredId(cursorX, cursorY))));
        globals.set("mouseOver", function(args -> {
            Widget widget = widget(args.arg(1).optjstring(""));
            return LuaValue.valueOf(widget != null
                    && widget.data().get("visible").optboolean(true)
                    && contains(widget.data(), cursorX, cursorY));
        }));
        // Arbitrary rectangle test; x/y are the center (like a widget), width/height
        // in canvas pixels.
        globals.set("mouseInside", function(args -> {
            double centerX = coordinate(args.arg(1).optdouble(0.5), PsychCanvas.WIDTH);
            double centerY = coordinate(args.arg(2).optdouble(0.5), PsychCanvas.HEIGHT);
            double halfW = args.arg(3).optdouble(0) / 2;
            double halfH = args.arg(4).optdouble(0) / 2;
            return LuaValue.valueOf(cursorX >= centerX - halfW && cursorX < centerX + halfW
                    && cursorY >= centerY - halfH && cursorY < centerY + halfH);
        }));
    }

    private void updateCursor() {
        if (cursor == null) return;
        cursor.set("x", cursorX);
        cursor.set("y", cursorY);
        cursor.set("down", LuaValue.valueOf(mouseButtonDown(0)));
        cursor.set("overId", hoveredId(cursorX, cursorY));
    }

    /**
     * Fires hover enter/exit callbacks once per transition: the widget method
     * {@code function widget:onHover()} / {@code widget:onHoverExit()} (called with
     * the widget as self, like onClick) and the globals {@code onHover(id)} /
     * {@code onHoverExit(id)}. Runs before the draw loop so a callback may safely
     * add, remove, or tween widgets.
     */
    private void updateHover() {
        for (Widget widget : List.copyOf(widgets)) {
            boolean now = widget.data().get("visible").optboolean(true)
                    && contains(widget.data(), cursorX, cursorY);
            boolean was = hoveredWidgets.contains(widget.id());
            if (now && !was) {
                hoveredWidgets.add(widget.id());
                call(widget.data().get("onHover"), widget.data());
                callGlobal("onHover", LuaValue.valueOf(widget.id()));
            } else if (!now && was) {
                hoveredWidgets.remove(widget.id());
                call(widget.data().get("onHoverExit"), widget.data());
                callGlobal("onHoverExit", LuaValue.valueOf(widget.id()));
            }
        }
        // Drop ids whose widget no longer exists so the set cannot grow unbounded.
        hoveredWidgets.removeIf(id -> widget(id) == null);
    }

    /** Topmost visible widget under the given canvas point, or "" if none. */
    private String hoveredId(double mouseX, double mouseY) {
        List<Widget> ordered = orderedWidgets();
        for (int i = ordered.size() - 1; i >= 0; i--) {
            Widget widget = ordered.get(i);
            if (!widget.data().get("visible").optboolean(true)) continue;
            if (contains(widget.data(), mouseX, mouseY)) return widget.id();
        }
        return "";
    }

    /** Whether the given canvas point is inside a widget's center-anchored bounds. */
    private static boolean contains(LuaTable data, double mouseX, double mouseY) {
        int width = Math.max(1, (int) Math.round(data.get("width").optdouble(1)));
        int height = Math.max(1, (int) Math.round(data.get("height").optdouble(1)));
        int x = coordinate(data.get("x").optdouble(0.5), PsychCanvas.WIDTH) - width / 2;
        int y = coordinate(data.get("y").optdouble(0.5), PsychCanvas.HEIGHT) - height / 2;
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private static boolean mouseButtonDown(int button) {
        try {
            long window = net.minecraft.client.Minecraft.getInstance().getWindow().getWindow();
            return GLFW.glfwGetMouseButton(window, Math.max(0, button)) == GLFW.GLFW_PRESS;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void beginSpriteColor(GuiGraphics gui, LuaTable data) {
        int rgb = data.get("color").optint(0xFFFFFF);
        float alpha = (float) Math.max(0, Math.min(1, data.get("alpha").optdouble(1)));
        gui.flush();
        gui.setColor((rgb >> 16 & 255) / 255f, (rgb >> 8 & 255) / 255f,
                (rgb & 255) / 255f, alpha);
    }

    private static void endSpriteColor(GuiGraphics gui) {
        gui.flush();
        gui.setColor(1, 1, 1, 1);
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        double canvasMouseX = screenToCanvasX(mouseX);
        double canvasMouseY = screenToCanvasY(mouseY);
        List<Widget> ordered = orderedWidgets();
        for (int i = ordered.size() - 1; i >= 0; i--) {
            Widget widget = ordered.get(i);
            LuaTable data = widget.data();
            // Decorative widgets (no handler and not a control) let the press fall
            // through to whatever is under them.
            if (!data.get("visible").optboolean(true) || !contains(data, canvasMouseX, canvasMouseY)
                    || !isInteractive(widget)) continue;

            // Press callback (any button), per-widget and global.
            call(data.get("onMouseDown"), data, LuaValue.valueOf(button));
            callGlobal("onMouseDown", LuaValue.valueOf(widget.id()), LuaValue.valueOf(button));

            // Click behavior is left-button only, matching the widget conventions.
            if (button == 0) {
                if (widget.kind().equals("toggle")) {
                    data.set("value", LuaValue.valueOf(!data.get("value").optboolean(false)));
                    call(data.get("onChange"), data, data.get("value"));
                } else if (widget.kind().equals("slider")) {
                    int width = Math.max(1, (int) Math.round(data.get("width").optdouble(1)));
                    int x = coordinate(data.get("x").optdouble(0.5), PsychCanvas.WIDTH) - width / 2;
                    double min = data.get("min").optdouble(0);
                    double max = data.get("max").optdouble(1);
                    data.set("value", min + (max - min) * Math.max(0,
                            Math.min(1, (canvasMouseX - x) / width)));
                    call(data.get("onChange"), data, data.get("value"));
                }
                call(data.get("onClick"), data);
                callGlobal("onClick", LuaValue.valueOf(widget.id()));
            }
            return true;
        }
        return false;
    }

    /** Release callback: fires onMouseUp on the widget under the cursor. */
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        double canvasMouseX = screenToCanvasX(mouseX);
        double canvasMouseY = screenToCanvasY(mouseY);
        List<Widget> ordered = orderedWidgets();
        for (int i = ordered.size() - 1; i >= 0; i--) {
            Widget widget = ordered.get(i);
            LuaTable data = widget.data();
            if (!data.get("visible").optboolean(true) || !contains(data, canvasMouseX, canvasMouseY)
                    || !isInteractive(widget)) continue;
            call(data.get("onMouseUp"), data, LuaValue.valueOf(button));
            callGlobal("onMouseUp", LuaValue.valueOf(widget.id()), LuaValue.valueOf(button));
            return true;
        }
        return false;
    }

    /** A widget receives clicks if it is a control or defines a mouse handler. */
    private static boolean isInteractive(Widget widget) {
        switch (widget.kind()) {
            case "button", "toggle", "slider" -> { return true; }
            default -> {
                LuaTable data = widget.data();
                return data.get("onClick").isfunction() || data.get("onMouseDown").isfunction()
                        || data.get("onMouseUp").isfunction();
            }
        }
    }

    private int color(LuaTable data) {
        int rgb = data.get("color").optint(0xFFFFFF);
        int alpha = (int) Math.round(Math.max(0, Math.min(1, data.get("alpha").optdouble(1))) * 255);
        return alpha << 24 | rgb & 0xFFFFFF;
    }

    private static int coordinate(double value, int extent) {
        return Math.abs(value) <= 1.0 ? (int) Math.round(value * extent) : (int) Math.round(value);
    }

    private static boolean normalizedCoordinate(double value) {
        return Math.abs(value) <= 1.0;
    }

    private double canvasScale() {
        return Math.max(1.0e-6, Math.min(host.screenWidth() / (double) PsychCanvas.WIDTH,
                host.screenHeight() / (double) PsychCanvas.HEIGHT));
    }

    private double screenToCanvasX(double x) {
        double scale = canvasScale();
        return (x - (host.screenWidth() - PsychCanvas.WIDTH * scale) * 0.5) / scale;
    }

    private double screenToCanvasY(double y) {
        double scale = canvasScale();
        return (y - (host.screenHeight() - PsychCanvas.HEIGHT * scale) * 0.5) / scale;
    }

    private void callGlobal(String name, LuaValue... args) {
        call(globals.get(name), args);
    }

    private void call(LuaValue function, LuaValue... args) {
        if (function == null || !function.isfunction() || error != null) return;
        try {
            budget.begin();
            function.invoke(LuaValue.varargsOf(args));
        } catch (Throwable throwable) {
            fail(throwable);
        } finally {
            budget.end();
        }
    }

    private void fail(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) message = throwable.getClass().getSimpleName();
        error = message.length() > 300 ? message.substring(0, 300) : message;
        FnfMod.LOGGER.warn("Machine menu Lua error in {}: {}", definition.id(), error);
    }

    private static int offset(Varargs args, LuaTable owner) {
        return args.arg1() == owner ? 2 : 1;
    }

    private static LuaValue function(java.util.function.Function<Varargs, LuaValue> body) {
        return new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return body.apply(args);
            }
        };
    }

    @Override
    public void close() {
        if (opened) callGlobal("onClose");
        tweens.clear();
        timers.clear();
        fontLoader.close();
        if (soundPlayer != null) {
            soundPlayer.close();
            soundPlayer = null;
        }
    }

    private static final class BudgetDebugLib extends DebugLib {
        private long deadline = Long.MAX_VALUE;
        private long remainingInstructions = Long.MAX_VALUE;

        void begin() {
            remainingInstructions = MAX_CALL_INSTRUCTIONS;
            deadline = System.nanoTime() + CALL_WALL_CEILING_NANOS;
        }

        void end() {
            remainingInstructions = Long.MAX_VALUE;
            deadline = Long.MAX_VALUE;
        }

        @Override
        public void onInstruction(int pc, Varargs varargs, int top) {
            // Instruction count is the primary guard; the wall-clock ceiling is a
            // large backstop. The old wall-only budget falsely tripped on the
            // first menu opened after launch, when a cold JVM stalled wall time
            // between instructions (Lua compilation, class loading, font parsing).
            if (--remainingInstructions < 0 || System.nanoTime() > deadline) {
                throw new LuaError("machine menu exceeded execution budget");
            }
            super.onInstruction(pc, varargs, top);
        }
    }
}
