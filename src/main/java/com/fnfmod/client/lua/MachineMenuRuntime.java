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
import com.fnfmod.song.WeekDefinition;
import com.fnfmod.song.WeekLibrary;
import com.fnfmod.world.ModContentScope;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
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
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Sandboxed, bounded Lua runtime used only for custom machine menus. */
public final class MachineMenuRuntime implements AutoCloseable {
    private final com.fnfmod.client.render.LuaLayerRenderer layerRenderer = new com.fnfmod.client.render.LuaLayerRenderer();
    private net.minecraft.core.BlockPos layerOrigin = net.minecraft.core.BlockPos.ZERO;
    private net.minecraft.core.Direction layerFacing = net.minecraft.core.Direction.NORTH;

    public record EditorWidget(String id, String kind, String space,
                               double x, double y, double z, double width, double height,
                               double angle, double rotationX, double rotationY,
                               double alpha, int order, boolean visible, boolean billboard,
                               boolean lighting, String renderMode, boolean seeThrough) {}

    /** A visual-editor tween which is replayed whenever this menu opens. */
    public record EditorTween(String tag, String target, String property, double from, double to,
                              double duration, String easing, double delay) {}
    /** Primitive properties copied between visual-editor objects. */
    public record EditorProperties(Map<String, LuaValue> values) {}
    public record EditorCaptureResult(boolean valid, boolean baseline, int changedProperties) {}
    public record EditorBounds(double left, double top, double right, double bottom) {}
    private record EditorLuaCall(String tag, double delay, String statement) {}

    /** Opaque in-memory editor snapshot used by undo/redo; Lua callbacks stay attached. */
    public static final class EditorHistoryState {
        private final List<Widget> widgets;
        private final Map<String, Map<String, LuaValue>> values;
        private final Map<String, AnimatedState> animations;
        private final List<EditorTween> editorTweens;
        private final Set<String> removedIds, detectedTags;
        private final Map<String, Map<String, Double>> snapshotHeads;
        private final Map<String, Double> snapshotTimes;
        private final int nextOrder, snapshotSequence;
        private final double cameraX, cameraY, cameraZ, cameraPitch, cameraYaw, cameraRoll,
                cameraFov, cameraZoom;
        private final Vec3 orbitPivot;

        private EditorHistoryState(List<Widget> widgets, Map<String, Map<String, LuaValue>> values,
                                   Map<String, AnimatedState> animations,
                                   List<EditorTween> editorTweens, Set<String> removedIds,
                                   Set<String> detectedTags,
                                   Map<String, Map<String, Double>> snapshotHeads,
                                   Map<String, Double> snapshotTimes, int nextOrder,
                                   int snapshotSequence, double cameraX, double cameraY,
                                   double cameraZ, double cameraPitch, double cameraYaw,
                                   double cameraRoll, double cameraFov, double cameraZoom,
                                   Vec3 orbitPivot) {
            this.widgets = widgets; this.values = values; this.animations = animations;
            this.editorTweens = editorTweens; this.removedIds = removedIds;
            this.detectedTags = detectedTags; this.snapshotHeads = snapshotHeads;
            this.snapshotTimes = snapshotTimes; this.nextOrder = nextOrder;
            this.snapshotSequence = snapshotSequence; this.cameraX = cameraX;
            this.cameraY = cameraY; this.cameraZ = cameraZ; this.cameraPitch = cameraPitch;
            this.cameraYaw = cameraYaw; this.cameraRoll = cameraRoll;
            this.cameraFov = cameraFov; this.cameraZoom = cameraZoom;
            this.orbitPivot = orbitPivot;
        }
    }

    public interface Host {
        int screenWidth();
        int screenHeight();
        void openSongSelect(byte returnTarget);
        boolean openSongDetails(String songId);
        boolean playSong(String songId, String difficulty, boolean duet, byte playSide,
                         byte playbackMode, byte returnTarget, String targetMachine);
        void openSettings();
        void openCharacterEditor();
        void openChartEditor(String songId, String difficulty);
        void closeMenu();
        boolean exitWorld();
        void saveData(String snbt);
    }

    private record Widget(String kind, String id, LuaTable data) {}
    private record MenuWorldObject(net.minecraft.core.Direction facing,
                                   com.fnfmod.client.render.LuaWorldObject object) {}
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
    private final String machineTag;
    private final Host host;
    /** Authored camera used by the visual editor even while its free navigation camera is active. */
    private final com.fnfmod.client.camera.MenuCameraController editorCameraOverride;
    private final List<FnfPayloads.SongInfo> songs;
    private final Globals globals;
    private final BudgetDebugLib budget = new BudgetDebugLib();
    private final List<Widget> widgets = new ArrayList<>();
    private final Map<String, AnimatedState> animatedStates = new LinkedHashMap<>();
    private final Map<String, Tween> tweens = new LinkedHashMap<>();
    private final List<EditorTween> editorTweens = new ArrayList<>();
    private final java.util.Set<String> editorRemovedIds = new LinkedHashSet<>();
    private final Set<String> detectedTweenTags = new LinkedHashSet<>();
    private final Map<String, Map<String, Double>> editorSnapshotHeads = new LinkedHashMap<>();
    private final Map<String, Double> editorSnapshotTimes = new LinkedHashMap<>();
    private final Map<String, Map<String, LuaValue>> editorSourceValues = new LinkedHashMap<>();
    private final Map<String, Double> editorSourceCamera = new LinkedHashMap<>();
    private boolean editorSourceCaptured;
    private int editorSnapshotSequence;
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
    private double screenCursorX, screenCursorY;
    /** Approximate projected bounds for clickable 3D menu controls, in GUI coordinates. */
    private final Map<String, double[]> worldHitboxes = new LinkedHashMap<>();
    private final Map<String, org.joml.Matrix3f> worldLayerPicking = new LinkedHashMap<>();
    private int editorViewportX;
    private int editorViewportY;
    private int editorViewportWidth;
    private int editorViewportHeight;
    private LuaTable cursor;
    private MenuScrollInput scrollInput;
    private final java.util.Set<Integer> queriedKeys = new java.util.HashSet<>();
    private final java.util.Map<Integer, Boolean> previousKeys = new java.util.HashMap<>();
    private final java.util.Set<Integer> justPressedKeys = new java.util.HashSet<>();
    private final java.util.Set<Integer> justReleasedKeys = new java.util.HashSet<>();
    private final java.util.Set<Integer> eventDownKeys = new java.util.HashSet<>();
    private boolean keyboardSuppressed;
    /** Widget ids currently hovered, so onHover/onHoverExit fire once per transition. */
    private final java.util.Set<String> hoveredWidgets = new java.util.HashSet<>();
    /** Extra Lua pages live in machine/screens/&lt;name&gt;.lua and share this runtime/audio player. */
    private String currentScreen = "main";
    private String pendingScreen;

    public MachineMenuRuntime(MachineDefinition definition, String machineTag, String snbt,
                              List<FnfPayloads.SongInfo> songs, Host host) {
        this(definition, machineTag, snbt, songs, host, null);
    }

    public MachineMenuRuntime(MachineDefinition definition, String machineTag, String snbt,
                              List<FnfPayloads.SongInfo> songs, Host host,
                              com.fnfmod.client.camera.MenuCameraController editorCameraOverride) {
        this.definition = definition;
        this.machineTag = machineTag == null ? "" : machineTag;
        this.host = host;
        this.editorCameraOverride = editorCameraOverride;
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
        installTextApi();
        installTweens();
        installLayerEffects();
        installMachine();
        installSound();
        installMenuBlur();
        installCursor();
        installKeyboard();
        installCamera();
        installEditorLuaSupport();
        globals.set("machineData", machineData);
        // Machine menus share Psych's fixed canvas. These stay stable when the
        // window resolution or Minecraft GUI scale changes.
        globals.set("screenWidth", PsychCanvas.WIDTH);
        globals.set("screenHeight", PsychCanvas.HEIGHT);
        loadScript();
        buildPreloadTasks();
    }

    private com.fnfmod.client.camera.MenuCameraController menuCameraController() {
        return editorCameraOverride != null ? editorCameraOverride
                : com.fnfmod.client.camera.MenuCameraController.active();
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

    /** Stops page-shared audio when another screen takes ownership of song preview audio. */
    public void stopSharedAudio() {
        if (soundPlayer == null) return;
        soundPlayer.close();
        soundPlayer = null;
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
            if (!editorSourceCaptured && !scriptSource.contains(EDITOR_LUA_BEGIN))
                captureEditorSourceState();
            // One-way compatibility: old visual-editor JSON remains readable until
            // the user saves, at which point it is written into menu.lua and removed.
            applyEditorLayout();
            detectLuaTweens(scriptSource);
        } catch (Throwable throwable) {
            budget.end();
            fail(throwable);
        }
    }

    /** Structured companion saved by the visual editor; handcrafted menu.lua remains readable. */
    private Path editorLayoutFile() {
        return definition.root() == null ? null : definition.root().resolve("menu-layout.json");
    }

    private void applyEditorLayout() {
        Path file = editorLayoutFile();
        if (file == null || !Files.isRegularFile(file) || !ModContentScope.allowsContentPath(file)) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (root.has("widgets") && root.get("widgets").isJsonArray()) {
                for (JsonElement element : root.getAsJsonArray("widgets")) {
                    if (!element.isJsonObject()) continue;
                    JsonObject json = element.getAsJsonObject();
                    String id = json.has("id") ? json.get("id").getAsString() : "";
                    String kind = json.has("kind") ? json.get("kind").getAsString() : "label";
                    Widget widget = widget(id);
                    if (widget == null) widget = addEditorWidget(kind, id);
                    if (widget == null) continue;
                    for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                        if (entry.getKey().equals("id") || entry.getKey().equals("kind")
                                || !entry.getValue().isJsonPrimitive()) continue;
                        var primitive = entry.getValue().getAsJsonPrimitive();
                        if (primitive.isBoolean()) widget.data().set(entry.getKey(),
                                LuaValue.valueOf(primitive.getAsBoolean()));
                        else if (primitive.isNumber()) widget.data().set(entry.getKey(), primitive.getAsDouble());
                        else if (primitive.isString()) widget.data().set(entry.getKey(), primitive.getAsString());
                    }
                }
            }
            if (root.has("camera") && root.get("camera").isJsonObject()) {
                JsonObject camera = root.getAsJsonObject("camera");
                var controller = menuCameraController();
                if (controller != null) {
                    controller.setPosition(number(camera, "x", controller.position().x),
                            number(camera, "y", controller.position().y),
                            number(camera, "z", controller.position().z), 0, "linear", "");
                    controller.setRotation(number(camera, "pitch", controller.pitch()),
                            number(camera, "yaw", controller.yaw()),
                            number(camera, "roll", controller.roll()), 0, "linear", "");
                    controller.setFov(number(camera, "fov", controller.fov()));
                    controller.setZoom(number(camera, "zoom", controller.zoom()), 0, "linear", "");
                }
            }
            editorTweens.clear();
            if (root.has("tweens") && root.get("tweens").isJsonArray()) {
                for (JsonElement element : root.getAsJsonArray("tweens")) {
                    if (!element.isJsonObject()) continue;
                    JsonObject json = element.getAsJsonObject();
                    String tag = string(json, "tag", "");
                    String target = string(json, "target", "");
                    String property = string(json, "property", "");
                    if (tag.isBlank() || target.isBlank() || property.isBlank()) continue;
                    editorTweens.add(new EditorTween(tag, target, property,
                            number(json, "from", 0), number(json, "to", 0),
                            Math.max(0, number(json, "duration", 1)),
                            string(json, "easing", "linear"),
                            Math.max(0, number(json, "delay", 0))));
                }
            }
        } catch (Exception error) {
            FnfMod.LOGGER.warn("Could not apply menu layout {}: {}", file, error.toString());
        }
    }

    private static double number(JsonObject object, String key, double fallback) {
        try { return object.has(key) ? object.get(key).getAsDouble() : fallback; }
        catch (Exception ignored) { return fallback; }
    }

    private static String string(JsonObject object, String key, String fallback) {
        try { return object.has(key) ? object.get(key).getAsString() : fallback; }
        catch (Exception ignored) { return fallback; }
    }

    public List<EditorWidget> editorWidgets() {
        return orderedWidgets().stream().map(widget -> new EditorWidget(widget.id(), widget.kind(),
                widget.data().get("space").optjstring("screen"),
                widget.data().get("x").optdouble(0), widget.data().get("y").optdouble(0),
                widget.data().get("z").optdouble(0), widget.data().get("width").optdouble(1),
                widget.data().get("height").optdouble(1), widget.data().get("angle").optdouble(0),
                widget.data().get("rotationX").optdouble(0),
                widget.data().get("rotationY").optdouble(0),
                widget.data().get("alpha").optdouble(1),
                widget.data().get("order").optint(0),
                widget.data().get("visible").optboolean(true),
                widget.data().get("billboard").optboolean(false),
                widget.data().get("lighting").optboolean(false),
                worldRenderMode(widget.data()).luaName(),
                widget.data().get("seeThrough").optboolean(false))).toList();
    }

    public EditorWidget editorWidget(String id) {
        return editorWidgets().stream().filter(value -> value.id().equals(id)).findFirst().orElse(null);
    }

    /**
     * Renames an editor object without replacing its Lua table. Keeping the table is
     * important: callbacks attached by the user's script remain attached while the
     * editor's id-indexed animation, tween, hover, and keyframe state follows the
     * new name.
     */
    public boolean editorRename(String oldId, String newId) {
        String renamed = newId == null ? "" : newId.trim();
        if (oldId == null || oldId.isBlank() || oldId.equals(renamed)) return !renamed.isBlank();
        if (!renamed.matches("[A-Za-z_][A-Za-z0-9_]*") || renamed.length() > 64
                || widget(renamed) != null) return false;
        Widget old = widget(oldId);
        if (old == null) return false;

        int index = widgets.indexOf(old);
        old.data().set("id", renamed);
        widgets.set(index, new Widget(old.kind(), renamed, old.data()));
        for(Widget w:widgets) for(String field:List.of("parent","mask","group"))
            if(w.data().get(field).optjstring("").equals(oldId)) w.data().set(field,renamed);

        AnimatedState animation = animatedStates.remove(oldId);
        if (animation != null) animatedStates.put(renamed, renamedAnimation(animation, renamed));

        for (var entry : new ArrayList<>(tweens.entrySet())) {
            Tween value = entry.getValue();
            if (!value.widgetId().equals(oldId)) continue;
            tweens.put(entry.getKey(), new Tween(value.tag(), renamed, value.property(),
                    value.from(), value.to(), value.finalValue(), value.startedNanos(),
                    value.durationNanos(), value.ease(), value.color()));
        }
        for (int i = 0; i < editorTweens.size(); i++) {
            EditorTween value = editorTweens.get(i);
            if (value.target().equals(oldId)) editorTweens.set(i, new EditorTween(value.tag(), renamed,
                    value.property(), value.from(), value.to(), value.duration(), value.easing(), value.delay()));
        }

        moveEditorKey(editorSnapshotHeads, oldId, renamed);
        moveEditorKey(editorSnapshotTimes, oldId, renamed);
        // The renamed object needs a complete managed Lua definition. Retaining its
        // old source baseline would incorrectly omit unchanged, non-default fields.
        editorSourceValues.remove(oldId);
        editorSourceValues.remove(renamed);
        editorRemovedIds.add(oldId);
        if (hoveredWidgets.remove(oldId)) hoveredWidgets.add(renamed);
        double[] hitbox = worldHitboxes.remove(oldId);
        if (hitbox != null) worldHitboxes.put(renamed, hitbox);
        return true;
    }

    private static <T> void moveEditorKey(Map<String, T> values, String oldId, String newId) {
        T value = values.remove(oldId);
        if (value != null) values.put(newId, value);
    }

    private AnimatedState renamedAnimation(AnimatedState source, String id) {
        AnimatedState copy = new AnimatedState(id, source.data);
        copy.animations.putAll(source.animations);
        copy.animation = source.animation;
        copy.prefix = source.prefix;
        copy.fps = source.fps;
        copy.loop = source.loop;
        copy.playing = source.playing;
        copy.finished = source.finished;
        copy.frame = source.frame;
        copy.frameProgress = source.frameProgress;
        copy.lastNanos = source.lastNanos;
        copy.pngPath = source.pngPath;
        copy.xmlPath = source.xmlPath;
        copy.atlas = source.atlas;
        copy.atlasGeneration = source.atlasGeneration;
        installAnimatedControls(copy);
        return copy;
    }

    /** Viewport used by the visual editor's captured 3D frame and its cursor hit-testing. */
    public void setEditorWorldViewport(int x, int y, int width, int height) {
        editorViewportX = x;
        editorViewportY = y;
        editorViewportWidth = Math.max(0, width);
        editorViewportHeight = Math.max(0, height);
    }

    public boolean editorSetNumber(String id, String property, double value) {
        Widget widget = widget(id);
        if (widget == null || !Double.isFinite(value) || !EDITOR_NUMBER_FIELDS.contains(property)) return false;
        widget.data().set(property, value);
        return true;
    }

    public double editorNumber(String id, String property, double fallback) {
        Widget value = widget(id);
        return value == null || !EDITOR_NUMBER_FIELDS.contains(property)
                ? fallback : value.data().get(property).optdouble(fallback);
    }

    public boolean editorBoolean(String id, String property, boolean fallback) {
        Widget value = widget(id);
        return value == null || !EDITOR_BOOLEAN_FIELDS.contains(property)
                ? fallback : value.data().get(property).optboolean(fallback);
    }

    public boolean editorSetString(String id, String property, String value) {
        Widget widget = widget(id);
        if (widget == null || !EDITOR_STRING_FIELDS.contains(property)) return false;
        widget.data().set(property, value == null ? "" : value);
        return true;
    }

    public String editorString(String id, String property) {
        Widget value = widget(id);
        return value == null || !EDITOR_STRING_FIELDS.contains(property)
                ? "" : value.data().get(property).optjstring("");
    }

    public boolean editorToggle(String id, String property) {
        Widget widget = widget(id);
        if (widget == null || !EDITOR_BOOLEAN_FIELDS.contains(property)) return false;
        widget.data().set(property, LuaValue.valueOf(!widget.data().get(property).optboolean(false)));
        return true;
    }

    /** Copies every editor-visible primitive except layer order and object identity. */
    public EditorProperties editorCopyProperties(String id) {
        Widget widget = widget(id);
        if (widget == null) return null;
        Map<String, LuaValue> copied = new LinkedHashMap<>();
        for (String field : EDITOR_NUMBER_FIELDS) copyProperty(widget.data(), copied, field);
        for (String field : EDITOR_STRING_FIELDS) copyProperty(widget.data(), copied, field);
        for (String field : EDITOR_BOOLEAN_FIELDS) copyProperty(widget.data(), copied, field);
        copied.remove("order");
        return new EditorProperties(Map.copyOf(copied));
    }

    /** Applies copied primitives while preserving the destination's id, kind, callbacks, and layer. */
    public int editorPasteProperties(String id, EditorProperties properties) {
        Widget widget = widget(id);
        if (widget == null || properties == null || properties.values() == null) return 0;
        int changed = 0;
        for (var entry : properties.values().entrySet()) {
            String field = entry.getKey();
            if (field.equals("order") || !EDITOR_NUMBER_FIELDS.contains(field)
                    && !EDITOR_STRING_FIELDS.contains(field) && !EDITOR_BOOLEAN_FIELDS.contains(field)) continue;
            LuaValue source = entry.getValue();
            if (source == null || source.isnil()) continue;
            LuaValue old = widget.data().get(field);
            boolean different = old.isnil()
                    || source.isnumber() && (!old.isnumber() || Math.abs(old.todouble() - source.todouble()) > 0.0000001)
                    || source.isstring() && (!old.isstring() || !old.tojstring().equals(source.tojstring()))
                    || source.isboolean() && (!old.isboolean() || old.toboolean() != source.toboolean());
            if (!different) continue;
            if (source.isnumber()) widget.data().set(field, LuaValue.valueOf(source.todouble()));
            else if (source.isstring()) widget.data().set(field, LuaValue.valueOf(source.tojstring()));
            else if (source.isboolean()) widget.data().set(field, LuaValue.valueOf(source.toboolean()));
            else continue;
            changed++;
        }
        return changed;
    }

    private static void copyProperty(LuaTable source, Map<String, LuaValue> output, String field) {
        LuaValue value = source.get(field);
        if (value.isnumber()) output.put(field, LuaValue.valueOf(value.todouble()));
        else if (value.isstring()) output.put(field, LuaValue.valueOf(value.tojstring()));
        else if (value.isboolean()) output.put(field, LuaValue.valueOf(value.toboolean()));
    }

    /** Move an object through the visual stack, normalizing order values afterwards. */
    public boolean editorMoveLayer(String id, int amount) {
        List<Widget> ordered = orderedWidgets();
        int index = -1;
        for (int i = 0; i < ordered.size(); i++) if (ordered.get(i).id().equals(id)) { index = i; break; }
        if (index < 0 || ordered.size() < 2) return false;
        int destination = Math.max(0, Math.min(ordered.size() - 1, index + amount));
        if (destination == index) return false;
        Widget moved = ordered.remove(index);
        ordered.add(destination, moved);
        for (int i = 0; i < ordered.size(); i++) ordered.get(i).data().set("order", i);
        nextWidgetOrder = ordered.size();
        return true;
    }

    /** Drag-and-drop layer placement. Orders are normalized after every drop target. */
    public boolean editorMoveLayerTo(String id, String targetId) {
        if (id == null || targetId == null || id.equals(targetId)) return false;
        List<Widget> ordered = orderedWidgets();
        Widget moved = null, target = null;
        for (Widget value : ordered) {
            if (value.id().equals(id)) moved = value;
            if (value.id().equals(targetId)) target = value;
        }
        if (moved == null || target == null) return false;
        ordered.remove(moved);
        int destination = ordered.indexOf(target);
        if (destination < 0) return false;
        ordered.add(destination, moved);
        for (int i = 0; i < ordered.size(); i++) ordered.get(i).data().set("order", i);
        nextWidgetOrder = ordered.size();
        return true;
    }

    /** Places a layer by the editor's Photoshop-style top-first row index. */
    public boolean editorMoveLayerToDisplayIndex(String id, int displayIndex) {
        List<Widget> ordered = orderedWidgets();
        Widget moved = ordered.stream().filter(value -> value.id().equals(id)).findFirst().orElse(null);
        if (moved == null) return false;
        ordered.remove(moved);
        int destination = Math.max(0, Math.min(ordered.size(), ordered.size() - displayIndex));
        ordered.add(destination, moved);
        for (int i = 0; i < ordered.size(); i++) ordered.get(i).data().set("order", i);
        nextWidgetOrder = ordered.size();
        return true;
    }

    public boolean editorDelete(String id) {
        Widget value = widget(id);
        if (value == null) return false;
        widgets.remove(value);
        animatedStates.remove(id);
        hoveredWidgets.remove(id);
        editorTweens.removeIf(tween -> tween.target().equals(id));
        editorRemovedIds.add(id);
        return true;
    }

    public List<EditorTween> editorTweens() { return List.copyOf(editorTweens); }
    public boolean editorTweenIsDetected(int index) {
        return index >= 0 && index < editorTweens.size() && detectedTweenTags.contains(editorTweens.get(index).tag());
    }

    public EditorHistoryState editorCaptureHistory() {
        Map<String, Map<String, LuaValue>> values = new LinkedHashMap<>();
        for (Widget widget : widgets) {
            Map<String, LuaValue> fields = new LinkedHashMap<>();
            for (String field : EDITOR_NUMBER_FIELDS) copyEditorPrimitive(widget.data(), fields, field);
            for (String field : EDITOR_STRING_FIELDS) copyEditorPrimitive(widget.data(), fields, field);
            for (String field : EDITOR_BOOLEAN_FIELDS) copyEditorPrimitive(widget.data(), fields, field);
            values.put(widget.id(), fields);
        }
        Map<String, Map<String, Double>> heads = new LinkedHashMap<>();
        editorSnapshotHeads.forEach((id, fields) -> heads.put(id, new LinkedHashMap<>(fields)));
        var controller = menuCameraController();
        Vec3 position = controller == null ? Vec3.ZERO : controller.position();
        return new EditorHistoryState(new ArrayList<>(widgets), values,
                new LinkedHashMap<>(animatedStates), new ArrayList<>(editorTweens),
                new LinkedHashSet<>(editorRemovedIds), new LinkedHashSet<>(detectedTweenTags),
                heads, new LinkedHashMap<>(editorSnapshotTimes), nextWidgetOrder,
                editorSnapshotSequence, position.x, position.y, position.z,
                controller == null ? 0 : controller.pitch(), controller == null ? 0 : controller.yaw(),
                controller == null ? 0 : controller.roll(), controller == null ? 70 : controller.fov(),
                controller == null ? 1 : controller.zoom(),
                controller == null ? null : controller.storedOrbitPivot());
    }

    public void editorRestoreHistory(EditorHistoryState state) {
        if (state == null) return;
        widgets.clear(); widgets.addAll(state.widgets);
        for (Widget widget : widgets) {
            // Widget records are immutable but their Lua tables intentionally stay
            // alive for callbacks. Restore the table ID explicitly after a rename.
            widget.data().set("id", widget.id());
            Map<String, LuaValue> fields = state.values.getOrDefault(widget.id(), Map.of());
            for (String field : EDITOR_NUMBER_FIELDS) widget.data().set(field, LuaValue.NIL);
            for (String field : EDITOR_STRING_FIELDS) widget.data().set(field, LuaValue.NIL);
            for (String field : EDITOR_BOOLEAN_FIELDS) widget.data().set(field, LuaValue.NIL);
            fields.forEach(widget.data()::set);
        }
        animatedStates.clear(); animatedStates.putAll(state.animations);
        editorTweens.clear(); editorTweens.addAll(state.editorTweens);
        editorRemovedIds.clear(); editorRemovedIds.addAll(state.removedIds);
        detectedTweenTags.clear(); detectedTweenTags.addAll(state.detectedTags);
        editorSnapshotHeads.clear();
        state.snapshotHeads.forEach((id, fields) ->
                editorSnapshotHeads.put(id, new LinkedHashMap<>(fields)));
        editorSnapshotTimes.clear(); editorSnapshotTimes.putAll(state.snapshotTimes);
        editorSnapshotSequence = state.snapshotSequence;
        nextWidgetOrder = state.nextOrder;
        tweens.clear(); hoveredWidgets.clear(); worldHitboxes.clear();
        var controller = menuCameraController();
        if (controller != null) {
            controller.setPosition(state.cameraX, state.cameraY, state.cameraZ, 0, "linear", "");
            controller.setRotation(state.cameraPitch, state.cameraYaw, state.cameraRoll, 0, "linear", "");
            controller.setFov(state.cameraFov);
            controller.setZoom(state.cameraZoom, 0, "linear", "");
            controller.setStoredOrbitPivot(state.orbitPivot);
        }
    }

    public boolean editorAddTween(String target, String property, double to, double duration, String easing) {
        if (target == null || target.isBlank() || property == null || property.isBlank()
                || !Double.isFinite(to) || !Double.isFinite(duration)) return false;
        String tag = "editor_" + target.replaceAll("[^a-zA-Z0-9_]", "_") + "_" + property;
        double from = editorTweens.stream().filter(tween -> tween.tag().equals(tag))
                .mapToDouble(EditorTween::from).findFirst().orElseGet(() -> editorTweenValue(target, property));
        if (!Double.isFinite(from)) return false;
        editorTweens.removeIf(tween -> tween.tag().equals(tag));
        if (editorTweens.size() >= MAX_TWEENS) return false;
        EditorTween tween = new EditorTween(tag, target, property, from, to,
                Math.max(0, Math.min(3600, duration)), easing == null || easing.isBlank() ? "linear" : easing, 0);
        editorTweens.add(tween);
        previewEditorTween(tween);
        return true;
    }

    public boolean editorRemoveTween(int index) {
        if (index < 0 || index >= editorTweens.size()) return false;
        editorTweens.remove(index);
        editorSnapshotHeads.clear();
        editorSnapshotTimes.clear();
        return true;
    }

    /** Captures every tweenable property now; later captures tween only what changed. */
    public EditorCaptureResult editorCaptureSnapshot(String target, double duration, String easing) {
        Map<String, Double> current = editorSnapshotValues(target);
        if (current.isEmpty()) return new EditorCaptureResult(false, false, 0);
        Map<String, Double> previous = editorSnapshotHeads.get(target);
        if (previous == null) {
            previous = latestEditorSnapshot(target);
            if (previous.isEmpty()) {
                editorSnapshotHeads.put(target, new LinkedHashMap<>(current));
                editorSnapshotTimes.put(target, 0.0);
                return new EditorCaptureResult(true, true, 0);
            }
            editorSnapshotHeads.put(target, previous);
        }
        double seconds = Math.max(0, Math.min(3600, duration));
        double delay = editorSnapshotTimes.computeIfAbsent(target, ignored -> editorTweens.stream()
                .filter(value -> !detectedTweenTags.contains(value.tag()) && value.target().equals(target))
                .mapToDouble(value -> value.delay() + value.duration()).max().orElse(0));
        String ease = easing == null || easing.isBlank() ? "linear" : easing;
        int changed = 0;
        for (var entry : current.entrySet()) {
            Double from = previous.get(entry.getKey());
            if (from == null || Math.abs(from - entry.getValue()) < 0.0000001) continue;
            if (editorTweens.size() >= MAX_TWEENS) break;
            String tag;
            do {
                tag = "editor_" + target.replaceAll("[^a-zA-Z0-9_]", "_") + "_kf_"
                        + (++editorSnapshotSequence) + "_" + entry.getKey();
            } while (hasEditorTweenTag(tag));
            editorTweens.add(new EditorTween(tag, target, entry.getKey(), from, entry.getValue(),
                    seconds, ease, delay));
            changed++;
        }
        editorSnapshotHeads.put(target, new LinkedHashMap<>(current));
        if (changed > 0) editorSnapshotTimes.put(target, delay + seconds);
        return new EditorCaptureResult(true, false, changed);
    }

    private boolean hasEditorTweenTag(String tag) {
        for (EditorTween value : editorTweens) if (value.tag().equals(tag)) return true;
        return false;
    }

    private Map<String, Double> latestEditorSnapshot(String target) {
        Map<String, Double> values = new LinkedHashMap<>();
        editorTweens.stream().filter(value -> !detectedTweenTags.contains(value.tag())
                        && value.target().equals(target))
                .sorted(Comparator.comparingDouble(EditorTween::delay))
                .forEach(value -> values.put(value.property(), value.to()));
        return values;
    }

    private Map<String, Double> editorSnapshotValues(String target) {
        Map<String, Double> values = new LinkedHashMap<>();
        if ("camera".equals(target)) {
            var controller = menuCameraController();
            if (controller == null) return values;
            values.put("x", controller.position().x); values.put("y", controller.position().y);
            values.put("z", controller.position().z); values.put("pitch", (double) controller.pitch());
            values.put("yaw", (double) controller.yaw()); values.put("roll", (double) controller.roll());
            values.put("zoom", controller.zoom());
            return values;
        }
        Widget widget = widget(target);
        if (widget == null) return values;
        List<String> properties = new ArrayList<>(List.of("x", "y", "alpha", "angle",
                "width", "height", "fontScale", "color"));
        properties.addAll(LuaLayerEffects.NUMBERS.keySet());
        if (isWorld(widget.data())) {
            properties.add(2, "z"); properties.add("rotationX"); properties.add("rotationY");
        }
        for (String property : properties) {
            LuaValue value = LuaLayerEffects.NUMBERS.containsKey(property)
                    ? LuaLayerEffects.value(widget.data(),property) : widget.data().get(property);
            if (!value.isnil() && value.isnumber()) values.put(property, value.todouble());
        }
        return values;
    }

    /** Best-effort discovery of ordinary constant-argument Lua tweens for the keyframe list. */
    private void detectLuaTweens(String source) {
        detectedTweenTags.clear();
        String base = sourceWithoutEditorBlock(source == null ? "" : source);
        Pattern pattern = Pattern.compile(
                "doTween(X|Y|Z|Alpha|Angle|Width|Height|FontScale)\\s*\\(\\s*['\\\"]([^'\\\"]+)['\\\"]\\s*,\\s*['\\\"]([^'\\\"]+)['\\\"]\\s*,\\s*(-?[0-9]+(?:\\.[0-9]+)?)\\s*,\\s*([0-9]+(?:\\.[0-9]+)?)\\s*,\\s*['\\\"]([^'\\\"]+)['\\\"]",
                Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(base);
        while (matcher.find()) {
            String tag = matcher.group(2), target = matcher.group(3);
            String property = switch (matcher.group(1).toLowerCase(Locale.ROOT)) {
                case "x" -> "x"; case "y" -> "y"; case "z" -> "z"; case "alpha" -> "alpha";
                case "angle" -> "angle"; case "width" -> "width"; case "height" -> "height";
                default -> "fontScale";
            };
            double from = editorTweenValue(target, property);
            if (!Double.isFinite(from)) continue;
            editorTweens.removeIf(value -> value.tag().equals(tag));
            editorTweens.add(new EditorTween(tag, target, property, from,
                    Double.parseDouble(matcher.group(4)), Double.parseDouble(matcher.group(5)), matcher.group(6), 0));
            detectedTweenTags.add(tag);
        }
        Pattern cameraPattern = Pattern.compile(
                "doTweenCam(X|Y|Z|RotationX|RotationY|RotationZ|Zoom)\\s*\\(\\s*['\\\"]([^'\\\"]+)['\\\"]\\s*,\\s*(-?[0-9]+(?:\\.[0-9]+)?)\\s*,\\s*([0-9]+(?:\\.[0-9]+)?)\\s*,\\s*['\\\"]([^'\\\"]+)['\\\"]",
                Pattern.CASE_INSENSITIVE);
        Matcher cameraMatcher = cameraPattern.matcher(base);
        while (cameraMatcher.find()) {
            String tag = cameraMatcher.group(2);
            String property = switch (cameraMatcher.group(1).toLowerCase(Locale.ROOT)) {
                case "x" -> "x"; case "y" -> "y"; case "z" -> "z";
                case "rotationx" -> "pitch"; case "rotationy" -> "yaw";
                case "rotationz" -> "roll"; default -> "zoom";
            };
            double from = editorTweenValue("camera", property);
            if (!Double.isFinite(from)) continue;
            editorTweens.removeIf(value -> value.tag().equals(tag));
            editorTweens.add(new EditorTween(tag, "camera", property, from,
                    Double.parseDouble(cameraMatcher.group(3)),
                    Double.parseDouble(cameraMatcher.group(4)), cameraMatcher.group(5), 0));
            detectedTweenTags.add(tag);
        }
    }

    public boolean editorPreviewTween(String target, String property, double to, double duration, String easing) {
        double from = editorTweenValue(target, property);
        if (!Double.isFinite(from)) return false;
        previewEditorTween(new EditorTween("editor_preview", target, property, from, to,
                Math.max(0, duration), easing == null ? "linear" : easing, 0));
        return true;
    }

    private double editorTweenValue(String target, String property) {
        if ("camera".equals(target)) {
            var controller = menuCameraController();
            if (controller == null) return Double.NaN;
            return switch (property) {
                case "x" -> controller.position().x; case "y" -> controller.position().y;
                case "z" -> controller.position().z; case "pitch" -> controller.pitch();
                case "yaw" -> controller.yaw(); case "roll" -> controller.roll();
                case "zoom" -> controller.zoom(); default -> Double.NaN;
            };
        }
        Widget value = widget(target);
        return value == null || !EDITOR_NUMBER_FIELDS.contains(property)
                ? Double.NaN : value.data().get(property).optdouble(defaultTweenValue(property));
    }

    private void previewEditorTween(EditorTween tween) {
        if ("camera".equals(tween.target())) {
            var controller = menuCameraController();
            if (controller == null) return;
            Vec3 p = controller.position();
            switch (tween.property()) {
                case "x" -> controller.setPosition(tween.to(), p.y, p.z, tween.duration(), tween.easing(), tween.tag());
                case "y" -> controller.setPosition(p.x, tween.to(), p.z, tween.duration(), tween.easing(), tween.tag());
                case "z" -> controller.setPosition(p.x, p.y, tween.to(), tween.duration(), tween.easing(), tween.tag());
                case "pitch" -> controller.setRotation(tween.to(), controller.yaw(), controller.roll(), tween.duration(), tween.easing(), tween.tag());
                case "yaw" -> controller.setRotation(controller.pitch(), tween.to(), controller.roll(), tween.duration(), tween.easing(), tween.tag());
                case "roll" -> controller.setRotation(controller.pitch(), controller.yaw(), tween.to(), tween.duration(), tween.easing(), tween.tag());
                case "zoom" -> controller.setZoom(tween.to(), tween.duration(), tween.easing(), tween.tag());
            }
            return;
        }
        Widget value = widget(tween.target());
        if (value == null || !EDITOR_NUMBER_FIELDS.contains(tween.property())) return;
        value.data().set(tween.property(), tween.from());
        startTween(tween.tag(), tween.target(), tween.property(), tween.from(), tween.to(),
                tween.duration(), tween.easing(), tween.property().equals("color")||tween.property().startsWith("gradientColor"));
    }

    public EditorWidget editorAdd(String kind) {
        String safeKind = switch (kind == null ? "" : kind) {
            case "image", "panel", "graph", "button", "animatedSprite", "gradient", "group" -> kind;
            default -> "label";
        };
        int suffix = 1;
        String id;
        do id = "editor_" + safeKind.toLowerCase(Locale.ROOT) + "_" + suffix++; while (widget(id) != null);
        Widget widget = addEditorWidget(safeKind, id);
        String addedId = id;
        return widget == null ? null : editorWidgets().stream()
                .filter(value -> value.id().equals(addedId)).findFirst().orElse(null);
    }

    private Widget addEditorWidget(String rawKind, String id) {
        if (id == null || id.isBlank() || widgets.size() >= MAX_WIDGETS) return null;
        String kind = switch (rawKind == null ? "" : rawKind) {
            case "image", "panel", "graph", "button", "animatedSprite", "gradient", "group" -> rawKind;
            default -> "label";
        };
        LuaTable table = new LuaTable();
        table.set("id", id); table.set("kind", kind); table.set("visible", LuaValue.TRUE);
        table.set("alpha", 1); table.set("order", nextWidgetOrder++); table.set("color", 0xFFFFFF);
        installWorldFields(table);
        table.set("x", 0.5); table.set("y", 0.5); table.set("width", kind.equals("label") ? 0 : 180);
        table.set("height", kind.equals("label") ? 10 : 48); table.set("angle", 0);
        if (kind.equals("image") || kind.equals("animatedSprite")) {
            table.set("path", "images/menu_assets/image.png"); installSpriteFields(table);
        }
        if (kind.equals("animatedSprite")) {
            table.set("xml", "images/menu_assets/image.xml"); table.set("fps", 24);
            table.set("loop", LuaValue.TRUE); table.set("animation", ""); table.set("frame", 0);
            AnimatedState state = new AnimatedState(id, table);
            animatedStates.put(id, state); installAnimatedControls(state);
        }
        if (kind.equals("label") || kind.equals("button")) {
            table.set("text", kind.equals("label") ? "New label" : "New button");
            installTextFields(table);
        }
        if (kind.equals("button")) {
            table.set("backgroundColor", 0x333333);
            table.set("hoverColor", 0x555555);
        }
        if (kind.equals("graph")) { table.set("shape", "rectangle"); table.set("borderSize", 0); }
        if (kind.equals("gradient")) table.set("gradientType", "linear");
        Widget widget = new Widget(kind, id, table);
        widgets.add(widget);
        return widget;
    }

    private static final java.util.Set<String> EDITOR_NUMBER_FIELDS = effectFields(java.util.Set.of(
            "x", "y", "z", "width", "height", "alpha", "angle", "rotationX", "rotationY",
            "fontScale", "fontQuality", "order", "color", "fps", "borderSize", "borderColor",
            "lineSpacing", "letterSpacing", "backgroundColor", "hoverColor", "parentWidth", "parentHeight"), LuaLayerEffects.NUMBERS.keySet());
    private static final java.util.Set<String> EDITOR_STRING_FIELDS = effectFields(java.util.Set.of(
            "space", "coordinates", "text", "path", "xml", "font", "shape", "alignment",
            "borderStyle", "renderMode"), LuaLayerEffects.STRINGS.keySet());
    private static final java.util.Set<String> EDITOR_BOOLEAN_FIELDS = effectFields(java.util.Set.of(
            "visible", "billboard", "lighting", "seeThrough", "shadow", "antialiasing", "loop", "italic"), LuaLayerEffects.BOOLEANS.keySet());

    private static java.util.Set<String> effectFields(java.util.Set<String> base, java.util.Set<String> effects) {
        var result = new java.util.LinkedHashSet<>(base); result.addAll(effects); return java.util.Collections.unmodifiableSet(result);
    }

    private static final String EDITOR_LUA_BEGIN = "-- BLOCKIFIED VISUAL EDITOR BEGIN";
    private static final String EDITOR_LUA_END = "-- BLOCKIFIED VISUAL EDITOR END";

    /** Saves visual work as a managed, human-editable block inside menu.lua. */
    public boolean saveEditorLayout() {
        Path file = screenScript(currentScreen);
        if (file == null || definition.root() == null || !ModContentScope.allowsContentPath(file)) return false;
        try {
            String source = Files.isRegularFile(file) ? Files.readString(file) : scriptSource;
            String block = buildEditorLua();
            String written = replaceEditorLuaBlock(source == null ? "" : source, block);
            Files.writeString(file, written);
            scriptSource = written;
            if (currentScreen.equals("main")) {
                Path legacy = editorLayoutFile();
                if (legacy != null) Files.deleteIfExists(legacy);
            }
            return true;
        } catch (Exception error) {
            FnfMod.LOGGER.warn("Could not save visual menu Lua {}: {}", file, error.toString());
            return false;
        }
    }

    private String buildEditorLua() {
        StringBuilder out = new StringBuilder(4096);
        out.append(EDITOR_LUA_BEGIN).append('\n');
        out.append("-- Managed by the visual editor. Code outside this block is preserved.\n");
        out.append("-- Safe object IDs become Lua variables for direct callbacks below this block.\n");
        out.append("blockifiedEditor.begin()\n");
        out.append("local __be\n");
        for (String id : editorRemovedIds) {
            out.append("blockifiedEditor.remove(").append(luaString(id)).append(")\n");
        }
        for (Widget widget : orderedWidgets()) {
            LuaTable data = widget.data();
            String binding = editorLuaBinding(widget.id());
            boolean named = !binding.equals("__be");
            if (named) out.append("local ");
            out.append(binding).append(" = ui.get(").append(luaString(widget.id())).append(")\n");
            out.append("if ").append(binding).append(" == nil then ").append(binding)
                    .append(" = ").append(luaConstructor(widget)).append(" end\n");
            for (String field : EDITOR_NUMBER_FIELDS) {
                LuaValue value = data.get(field);
                if (value.isnil()) continue;
                double saved = editorTweens.stream().filter(tween -> !detectedTweenTags.contains(tween.tag()))
                        .filter(tween -> tween.target().equals(widget.id()) && tween.property().equals(field))
                        .mapToDouble(EditorTween::from).findFirst().orElse(value.todouble());
                if (!shouldWriteEditorNumber(widget, field, saved)) continue;
                out.append(binding).append('.').append(field).append(" = ").append(luaNumber(saved)).append('\n');
            }
            for (String field : EDITOR_STRING_FIELDS) {
                LuaValue value = data.get(field);
                if (!value.isnil() && shouldWriteEditorString(widget, field, value.tojstring()))
                    out.append(binding).append('.').append(field).append(" = ")
                        .append(luaString(value.tojstring())).append('\n');
            }
            for (String field : EDITOR_BOOLEAN_FIELDS) {
                LuaValue value = data.get(field);
                if (!value.isnil() && shouldWriteEditorBoolean(widget, field, value.toboolean()))
                    out.append(binding).append('.').append(field).append(" = ")
                        .append(value.toboolean() ? "true" : "false").append('\n');
            }
        }
        var controller = menuCameraController();
        if (controller != null) {
            double x = editorCameraStart("x", controller.position().x);
            double y = editorCameraStart("y", controller.position().y);
            double z = editorCameraStart("z", controller.position().z);
            double pitch = editorCameraStart("pitch", controller.pitch());
            double yaw = editorCameraStart("yaw", controller.yaw());
            double roll = editorCameraStart("roll", controller.roll());
            double fov = controller.fov();
            double zoom = editorCameraStart("zoom", controller.zoom());
            if (cameraChanged("x", x) || cameraChanged("y", y) || cameraChanged("z", z))
                out.append("camera.setPosition(").append(luaNumber(x)).append(", ")
                        .append(luaNumber(y)).append(", ").append(luaNumber(z)).append(")\n");
            if (cameraChanged("pitch", pitch) || cameraChanged("yaw", yaw) || cameraChanged("roll", roll))
                out.append("camera.setRotation(").append(luaNumber(pitch)).append(", ")
                        .append(luaNumber(yaw)).append(", ").append(luaNumber(roll)).append(")\n");
            if (cameraChanged("fov", fov))
                out.append("camera.setFOV(").append(luaNumber(fov)).append(")\n");
            if (cameraChanged("zoom", zoom))
                out.append("camera.setZoom(").append(luaNumber(zoom)).append(")\n");
        }
        for (EditorTween tween : editorTweens) {
            if (detectedTweenTags.contains(tween.tag())) continue;
            out.append("blockifiedEditor.keyframe(").append(luaString(tween.tag())).append(", ")
                    .append(luaString(tween.target())).append(", ").append(luaString(tween.property())).append(", ")
                    .append(luaNumber(tween.from())).append(", ").append(luaNumber(tween.to())).append(", ")
                    .append(luaNumber(tween.duration())).append(", ").append(luaString(tween.easing())).append(", ")
                    .append(luaNumber(tween.delay())).append(")\n");
        }
        List<EditorTween> managedTweens = editorTweens.stream()
                .filter(tween -> !detectedTweenTags.contains(tween.tag())).toList();
        List<EditorLuaCall> managedCalls = editorLuaCalls(managedTweens);
        if (!managedCalls.isEmpty()) {
            out.append("local __blockifiedUserOnOpen = onOpen\n");
            out.append("function onOpen()\n");
            out.append("    if __blockifiedUserOnOpen then __blockifiedUserOnOpen() end\n");
            for (EditorLuaCall call : managedCalls) {
                if (call.delay() <= 0) out.append("    ").append(call.statement()).append('\n');
                else out.append("    runTimer(").append(luaString("__be_kf_" + call.tag()))
                        .append(", ").append(luaNumber(call.delay())).append(")\n");
            }
            out.append("end\n");
            if (managedCalls.stream().anyMatch(call -> call.delay() > 0)) {
                out.append("local __blockifiedUserOnTimerCompleted = onTimerCompleted\n");
                out.append("function onTimerCompleted(tag, loops, loopsLeft)\n");
                out.append("    if __blockifiedUserOnTimerCompleted then __blockifiedUserOnTimerCompleted(tag, loops, loopsLeft) end\n");
                for (EditorLuaCall call : managedCalls) if (call.delay() > 0) {
                    out.append("    if tag == ").append(luaString("__be_kf_" + call.tag()))
                            .append(" then ").append(call.statement()).append(" end\n");
                }
                out.append("end\n");
            }
        }
        out.append(EDITOR_LUA_END).append('\n');
        return out.toString();
    }

    private void captureEditorSourceState() {
        editorSourceValues.clear();
        for (Widget widget : widgets) {
            Map<String, LuaValue> values = new LinkedHashMap<>();
            for (String field : EDITOR_NUMBER_FIELDS) copyEditorPrimitive(widget.data(), values, field);
            for (String field : EDITOR_STRING_FIELDS) copyEditorPrimitive(widget.data(), values, field);
            for (String field : EDITOR_BOOLEAN_FIELDS) copyEditorPrimitive(widget.data(), values, field);
            editorSourceValues.put(widget.id(), values);
        }
        editorSourceCamera.clear();
        var controller = menuCameraController();
        if (controller != null) {
            editorSourceCamera.put("x", controller.position().x);
            editorSourceCamera.put("y", controller.position().y);
            editorSourceCamera.put("z", controller.position().z);
            editorSourceCamera.put("pitch", (double) controller.pitch());
            editorSourceCamera.put("yaw", (double) controller.yaw());
            editorSourceCamera.put("roll", (double) controller.roll());
            editorSourceCamera.put("fov", controller.fov());
            editorSourceCamera.put("zoom", controller.zoom());
        }
        editorSourceCaptured = true;
    }

    private static void copyEditorPrimitive(LuaTable source, Map<String, LuaValue> output, String field) {
        LuaValue value = source.get(field);
        if (value.isnumber()) output.put(field, LuaValue.valueOf(value.todouble()));
        else if (value.isstring()) output.put(field, LuaValue.valueOf(value.tojstring()));
        else if (value.isboolean()) output.put(field, LuaValue.valueOf(value.toboolean()));
    }

    private boolean shouldWriteEditorNumber(Widget widget, String field, double value) {
        LuaValue source = editorSourceValues.getOrDefault(widget.id(), Map.of()).get(field);
        if (source != null && source.isnumber()) return Math.abs(source.todouble() - value) > 0.0000001;
        double fallback = editorDefaultNumber(widget.kind(), field);
        return !Double.isFinite(fallback) || Math.abs(fallback - value) > 0.0000001;
    }

    private boolean shouldWriteEditorString(Widget widget, String field, String value) {
        LuaValue source = editorSourceValues.getOrDefault(widget.id(), Map.of()).get(field);
        if (source != null && source.isstring()) return !source.tojstring().equals(value);
        return !editorDefaultString(widget, field).equals(value);
    }

    private boolean shouldWriteEditorBoolean(Widget widget, String field, boolean value) {
        LuaValue source = editorSourceValues.getOrDefault(widget.id(), Map.of()).get(field);
        if (source != null && source.isboolean()) return source.toboolean() != value;
        return editorDefaultBoolean(field) != value;
    }

    private static double editorDefaultNumber(String kind, String field) {
        if (LuaLayerEffects.NUMBERS.containsKey(field)) return LuaLayerEffects.NUMBERS.get(field);
        return switch (field) {
            case "x", "y" -> 0.5;
            case "z", "angle", "rotationX", "rotationY", "borderSize",
                    "lineSpacing", "letterSpacing" -> 0;
            case "width" -> kind.equals("label") ? 0 : 1;
            case "height" -> kind.equals("label") ? 10 : 1;
            case "alpha", "fontScale" -> 1;
            case "fontQuality" -> LuaFontLoader.DEFAULT_OVERSAMPLE;
            case "color" -> kind.equals("panel") ? 0xAA111111L : 0xFFFFFF;
            case "backgroundColor" -> 0x333333;
            case "hoverColor" -> 0x555555;
            case "fps" -> 24;
            case "borderColor" -> kind.equals("graph") ? 0xFFFFFF : 0;
            // Order is structural rather than cosmetic: an editor-created layer
            // may need to sit between objects authored outside the managed block.
            default -> Double.NaN;
        };
    }

    private static String editorDefaultString(Widget widget, String field) {
        if (LuaLayerEffects.STRINGS.containsKey(field)) return LuaLayerEffects.STRINGS.get(field);
        return switch (field) {
            // These values are already embedded in the fallback constructor.
            case "text", "path", "xml", "shape" -> widget.data().get(field).optjstring("");
            case "space" -> "screen";
            case "coordinates" -> "relative";
            case "font" -> "";
            case "alignment" -> "center";
            case "borderStyle" -> "outline";
            case "renderMode" -> "auto";
            default -> "";
        };
    }

    private static boolean editorDefaultBoolean(String field) {
        return switch (field) {
            case "visible", "billboard", "shadow", "antialiasing", "loop" -> true;
            default -> false;
        };
    }

    private boolean cameraChanged(String property, double value) {
        Double source = editorSourceCamera.get(property);
        return source == null || Math.abs(source - value) > 0.0000001;
    }

    private static List<EditorLuaCall> editorLuaCalls(List<EditorTween> tweens) {
        List<EditorLuaCall> result = new ArrayList<>();
        for (EditorTween tween : tweens)
            result.add(new EditorLuaCall(tween.tag(), tween.delay(), luaTweenCall(tween)));
        return result;
    }

    private static String replaceEditorLuaBlock(String source, String block) {
        int begin = source.indexOf(EDITOR_LUA_BEGIN);
        int end = begin < 0 ? -1 : source.indexOf(EDITOR_LUA_END, begin);
        if (begin >= 0 && end >= 0) {
            end += EDITOR_LUA_END.length();
            if (end < source.length() && source.charAt(end) == '\r') end++;
            if (end < source.length() && source.charAt(end) == '\n') end++;
            return source.substring(0, begin) + block + source.substring(end);
        }
        return source.stripTrailing() + (source.isBlank() ? "" : "\n\n") + block;
    }

    private static String sourceWithoutEditorBlock(String source) {
        int begin = source.indexOf(EDITOR_LUA_BEGIN);
        int end = begin < 0 ? -1 : source.indexOf(EDITOR_LUA_END, begin);
        if (begin < 0 || end < 0) return source;
        return source.substring(0, begin) + source.substring(end + EDITOR_LUA_END.length());
    }

    private static String luaConstructor(Widget widget) {
        LuaTable data = widget.data();
        String id = luaString(widget.id());
        String text = luaString(data.get("text").optjstring(""));
        return switch (widget.kind()) {
            case "gradient" -> "ui.gradient(" + id + ", 0.5, 0.5, 1, 1)";
            case "group" -> "ui.group(" + id + ", 0.5, 0.5, 1, 1)";
            case "image", "sprite" -> "ui.image(" + id + ", " + luaString(data.get("path").optjstring("")) + ", 0.5, 0.5, 1, 1)";
            case "animatedSprite" -> "ui.animatedSprite(" + id + ", "
                    + luaString(data.get("path").optjstring("")) + ", "
                    + luaString(data.get("xml").optjstring("")) + ", 0.5, 0.5, 1, 1)";
            case "graph" -> "ui.graph(" + id + ", " + luaString(data.get("shape").optjstring("rectangle"))
                    + ", 0.5, 0.5, 1, 1, 'FFFFFF')";
            case "button" -> "ui.button(" + id + ", " + text + ", 0.5, 0.5, 1, 1)";
            case "toggle" -> "ui.toggle(" + id + ", " + text + ", 0.5, 0.5, 1, 1)";
            case "slider" -> "ui.slider(" + id + ", " + text + ", 0.5, 0.5, 1, 1)";
            case "panel" -> "ui.panel(" + id + ", '', 0.5, 0.5, 1, 1)";
            default -> "ui.label(" + id + ", " + text + ", 0.5, 0.5, 0, 10)";
        };
    }

    private static String luaTweenCall(EditorTween tween) {
        String tag = luaString(tween.tag()), target = luaString(tween.target());
        String to = luaNumber(tween.to()), duration = luaNumber(tween.duration()), easing = luaString(tween.easing());
        if (LuaLayerEffects.NUMBERS.containsKey(tween.property())) return "doTweenEffect(" + tag + ", " + target
                + ", " + luaString(tween.property()) + ", " + to + ", " + duration + ", " + easing + ")";
        if (tween.target().equals("camera")) {
            return switch (tween.property()) {
                case "x" -> "doTweenCamX(" + tag + ", " + to + ", " + duration + ", " + easing + ")";
                case "y" -> "doTweenCamY(" + tag + ", " + to + ", " + duration + ", " + easing + ")";
                case "z" -> "doTweenCamZ(" + tag + ", " + to + ", " + duration + ", " + easing + ")";
                case "pitch" -> "doTweenCamRotationX(" + tag + ", " + to + ", " + duration + ", " + easing + ")";
                case "yaw" -> "doTweenCamRotationY(" + tag + ", " + to + ", " + duration + ", " + easing + ")";
                case "roll" -> "doTweenCamRotationZ(" + tag + ", " + to + ", " + duration + ", " + easing + ")";
                default -> "doTweenCamZoom(" + tag + ", " + to + ", " + duration + ", " + easing + ")";
            };
        }
        String function = switch (tween.property()) {
            case "x" -> "doTweenX"; case "y" -> "doTweenY"; case "z" -> "doTweenZ";
            case "alpha" -> "doTweenAlpha";
            case "angle" -> "doTweenAngle"; case "width" -> "doTweenWidth";
            case "height" -> "doTweenHeight"; case "fontScale" -> "doTweenFontScale";
            case "rotationX" -> "doTweenRotationX"; case "rotationY" -> "doTweenRotationY";
            case "color" -> "doTweenColor"; default -> "doTweenX";
        };
        return function + "(" + tag + ", " + target + ", " + to + ", " + duration + ", " + easing + ")";
    }

    private static String luaString(String value) {
        String safe = value == null ? "" : value;
        return "'" + safe.replace("\\", "\\\\").replace("'", "\\'")
                .replace("\r", "\\r").replace("\n", "\\n") + "'";
    }

    private static String editorLuaBinding(String id) {
        if (id == null || !id.matches("[A-Za-z_][A-Za-z0-9_]*")) return "__be";
        return switch (id) {
            // Lua keywords and core Blockified namespaces must remain callable.
            case "and", "break", "do", "else", "elseif", "end", "false", "for", "function",
                    "goto", "if", "in", "local", "nil", "not", "or", "repeat", "return",
                    "then", "true", "until", "while", "ui", "machine", "camera",
                    "blockifiedEditor", "screenWidth", "screenHeight", "machineData" -> "__be";
            default -> id;
        };
    }

    private static String luaNumber(double value) {
        if (!Double.isFinite(value)) return "0";
        if (Math.abs(value - Math.rint(value)) < 0.0000001) return Long.toString(Math.round(value));
        return String.format(Locale.ROOT, "%.6f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private double editorCameraStart(String property, double fallback) {
        return editorTweens.stream().filter(tween -> !detectedTweenTags.contains(tween.tag()))
                .filter(tween -> tween.target().equals("camera")
                        && tween.property().equals(property))
                .mapToDouble(EditorTween::from).findFirst().orElse(fallback);
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
            double quality = data.get("fontQuality").optdouble(LuaFontLoader.DEFAULT_OVERSAMPLE);
            if (!font.isEmpty()) mainPreload.add(() -> fontLoader.get(font, quality));
        }

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

    private void installLayerEffects() {
        LuaLayerEffects.install(globals, new LuaLayerEffects.Host() {
            public LuaTable data(String id) { Widget w = widget(id); return w == null ? null : w.data(); }
            public LuaTable create(String kind, String id, double x, double y, double width, double height) {
                widgets.removeIf(w -> w.id().equals(id));
                Widget w = addEditorWidget(kind, id);
                if (w == null) throw new LuaError("widget limit exceeded");
                w.data().set("x", x); w.data().set("y", y); w.data().set("width", width); w.data().set("height", height);
                return w.data();
            }
            public void tween(String tag, String id, String field, double target, double seconds, String easing) {
                startTween(tag, id, field, LuaLayerEffects.number(data(id), field), target, seconds, easing, field.startsWith("gradientColor"));
            }
        });
        LuaTable ui = globals.get("ui").checktable();
        ui.set("gradient", globals.get("makeLuaGradient"));
        ui.set("group", globals.get("makeLuaGroup"));
        LuaObjectParenting.install(globals, parentingHost());
    }

    private LuaObjectParenting.Host parentingHost() {
        return new LuaObjectParenting.Host() {
            public LuaTable data(String id) { Widget w=widget(id); return w==null ? null : w.data(); }
            public String space(String id) { return isWorld(data(id)) ? "world" : "screen"; }
            public org.joml.Matrix4f transform(String id) { return menuTransform(widget(id), new java.util.HashSet<>()); }
            public double width(String id) { return effectiveWidth(widget(id)); }
            public double height(String id) { return effectiveHeight(widget(id)); }
            public void position(String id,double x,double y,double z) { LuaTable d=data(id); d.set("x",x);d.set("y",y);d.set("z",z); }
            public void localTransform(String id,org.joml.Matrix4f m) {
                LuaTable d=data(id); boolean root=d.get("parent").optjstring("").isBlank();
                position(id,m.m30(),m.m31(),m.m32());
                if(root && isWorld(d)) {
                    d.set("coordinates","minecraft");
                    m=new org.joml.Matrix4f().rotateY((float)Math.toRadians(net.minecraft.core.Direction.NORTH.toYRot())).mul(m);
                }
                var r=LuaObjectParenting.rotation(m); d.set("rotationX",r.x);d.set("rotationY",r.y);d.set("angle",r.z);
                var s=m.getScale(new org.joml.Vector3f());
                d.set("width",width(id)*s.x);d.set("height",height(id)*s.y);
            }
        };
    }

    public boolean editorParent(String child,String parent,boolean keepPosition) {
        try {
            if(parent==null || parent.isBlank()) LuaObjectParenting.detach(parentingHost(),child,keepPosition);
            else LuaObjectParenting.attach(parentingHost(),child,parent,keepPosition);
            return true;
        } catch(LuaError error) { return false; }
    }

    private org.joml.Matrix4f menuTransform(Widget w, java.util.Set<String> seen) {
        var matrix=new org.joml.Matrix4f();
        if(w==null || seen.size()>=32 || !seen.add(w.id())) return matrix;
        LuaTable d=w.data(); Widget parent=widget(d.get("parent").optjstring(""));
        if(parent!=null && isWorld(d)==isWorld(parent.data())) {
            matrix.set(menuTransform(parent,seen));
            matrix.scale((float)(effectiveWidth(parent)/Math.max(.0001,d.get("parentWidth").optdouble(effectiveWidth(parent)))),
                    (float)(effectiveHeight(parent)/Math.max(.0001,d.get("parentHeight").optdouble(effectiveHeight(parent)))),1);
            matrix.translate((float)d.get("x").optdouble(0),(float)d.get("y").optdouble(0),(float)d.get("z").optdouble(0));
        } else if(isWorld(d)) {
            Vec3 pos=worldPositionRaw(d,layerOrigin,layerFacing);
            var facing=isMinecraftCoordinates(d) ? net.minecraft.core.Direction.NORTH : layerFacing;
            matrix.translate((float)pos.x,(float)pos.y,(float)pos.z);
            if(d.get("billboard").optboolean(true)) matrix.rotate(Minecraft.getInstance().gameRenderer.getMainCamera().rotation());
            else matrix.rotateY((float)Math.toRadians(-facing.toYRot()));
        } else matrix.translate(coordinate(d.get("x").optdouble(.5),PsychCanvas.WIDTH),
                coordinate(d.get("y").optdouble(.5),PsychCanvas.HEIGHT),0);
        return matrix.rotateXYZ((float)Math.toRadians(isWorld(d)?d.get("rotationX").optdouble(0):0),
                (float)Math.toRadians(isWorld(d)?d.get("rotationY").optdouble(0):0),
                (float)Math.toRadians(d.get("angle").optdouble(0)));
    }

    private static String widgetFingerprint(Widget w) {
        StringBuilder out=new StringBuilder(w.kind());
        for(LuaValue key:w.data().keys()) {
            LuaValue v=w.data().get(key);
            if(v.isnumber() || v.isboolean() || v.type()==LuaValue.TSTRING) out.append('|').append(key).append('=').append(v);
        }
        return out.toString();
    }
    private boolean layered(Widget w) {
        return LuaLayerEffects.active(w.data()) || w.kind().equals("gradient") || w.kind().equals("group")
                || !w.data().get("parent").optjstring("").isBlank();
    }
    private com.fnfmod.client.render.LuaLayerRenderer.Surface menuSurface(Widget w,Font font) {
        if(!layerRenderer.enter(w.id())) return null;
        try {
            LuaTable d=w.data(); int width=effectiveWidth(w),height=effectiveHeight(w);
            Widget maskWidget=widget(d.get("mask").optjstring(""));
            var mask=maskWidget==null ? null : menuSurface(maskWidget,font);
            List<Widget> children=w.kind().equals("group") ? orderedWidgets().stream()
                    .filter(c->c.data().get("group").optjstring("").equals(w.id())).toList() : List.of();
            String signature=widgetFingerprint(w)+children.stream().map(MachineMenuRuntime::widgetFingerprint).toList();
            if(w.kind().equals("group")) signature+=widgets.stream().map(MachineMenuRuntime::widgetFingerprint).toList();
            double raster=Math.max(1,Minecraft.getInstance().getWindow().getHeight()/(double)PsychCanvas.HEIGHT);
            int rw=Math.max(1,(int)Math.ceil(width*raster)),rh=Math.max(1,(int)Math.ceil(height*raster));
            return layerRenderer.paint(w.id(),rw,rh,signature,d,mask,gui->{
                gui.pose().scale((float)(rw/(double)width),(float)(rh/(double)height),1);
                if(w.kind().equals("group")) {
                    gui.pose().scale(width/1280f,height/720f,1);
                    for(Widget child:children) { renderWidget(gui,font,child,-100000,-100000); gui.flush(); }
                } else {
                    int cx=coordinate(d.get("x").optdouble(.5),PsychCanvas.WIDTH);
                    int cy=coordinate(d.get("y").optdouble(.5),PsychCanvas.HEIGHT);
                    gui.pose().translate(-cx+width/2f,-cy+height/2f,0);
                    LuaValue angle=d.get("angle"),visible=d.get("visible");
                    d.set("angle",0);d.set("visible",LuaValue.TRUE);
                    try { renderWidgetRaw(gui,font,w,d.get("hovered").optboolean(false)?cx:-100000,
                            d.get("hovered").optboolean(false)?cy:-100000); gui.flush(); }
                    finally { d.set("angle",angle);d.set("visible",visible); }
                }
            });
        } finally { layerRenderer.leave(w.id()); }
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
        // Any existing constructor can become a 3D object without a parallel API:
        // ui.world(widget, x, y, z). Coordinates are block offsets from the menu origin.
        ui.set("world", function(args -> {
            int at = offset(args, ui);
            LuaValue value = args.arg(at);
            LuaTable table = value.istable() ? value.checktable()
                    : widgets.stream().filter(widget -> widget.id().equals(value.optjstring("")))
                    .map(Widget::data).findFirst().orElse(null);
            if (table == null) return LuaValue.FALSE;
            table.set("space", "world");
            table.set("coordinates", "relative");
            table.set("x", args.arg(at + 1).optdouble(table.get("x").optdouble(0)));
            table.set("y", args.arg(at + 2).optdouble(table.get("y").optdouble(0)));
            table.set("z", args.arg(at + 3).optdouble(table.get("z").optdouble(0)));
            return table;
        }));
        // Minecraft-coordinate counterpart used by the visual editor and by menus
        // which must stay at one exact point regardless of machine position/facing.
        ui.set("worldAbsolute", function(args -> {
            int at = offset(args, ui);
            LuaValue value = args.arg(at);
            LuaTable table = value.istable() ? value.checktable()
                    : widgets.stream().filter(widget -> widget.id().equals(value.optjstring("")))
                    .map(Widget::data).findFirst().orElse(null);
            if (table == null) return LuaValue.FALSE;
            table.set("space", "world");
            table.set("coordinates", "minecraft");
            table.set("x", args.arg(at + 1).optdouble(table.get("x").optdouble(0)));
            table.set("y", args.arg(at + 2).optdouble(table.get("y").optdouble(0)));
            table.set("z", args.arg(at + 3).optdouble(table.get("z").optdouble(0)));
            return table;
        }));
        globals.set("setObjectOrder", function(args -> {
            Widget widget = widget(args.arg1().optjstring(""));
            if (widget == null) return LuaValue.FALSE;
            widget.data().set("order", args.arg(2).checkint());
            return LuaValue.TRUE;
        }));
        globals.set("setObjectRenderMode", renderModeSetter());
        globals.set("setWorldSpriteRenderMode", renderModeSetter());
        globals.set("setObjectShaderMode", renderModeSetter());
        globals.set("setWorldSpriteLighting", function(args -> {
            Widget value = widget(args.arg(1).optjstring(""));
            if (value == null) return LuaValue.FALSE;
            value.data().set("lighting", LuaValue.valueOf(args.arg(2).optboolean(true)));
            value.data().set("renderMode", "auto");
            return LuaValue.TRUE;
        }));
        globals.set("ui", ui);
    }

    private LuaValue renderModeSetter() {
        return function(args -> {
            Widget value = widget(args.arg(1).optjstring(""));
            if (value == null) return LuaValue.FALSE;
            String mode = normalizeRenderMode(args.arg(2).optjstring("auto"));
            value.data().set("renderMode", mode);
            if (!mode.equals("auto")) value.data().set("lighting",
                    LuaValue.valueOf(mode.equals("lit")));
            return LuaValue.TRUE;
        });
    }

    /** Psych-compatible text helpers backed by the same properties as menu labels/buttons. */
    private void installTextApi() {
        globals.set("setTextString", function(args -> {
            Widget value = widget(args.arg(1).optjstring(""));
            if (value == null) return LuaValue.FALSE;
            value.data().set("text", args.arg(2).optjstring("")); return LuaValue.TRUE;
        }));
        globals.set("getTextString", function(args -> {
            Widget value = widget(args.arg(1).optjstring(""));
            return LuaValue.valueOf(value == null ? "" : value.data().get("text").optjstring(""));
        }));
        globals.set("setTextSize", function(args -> {
            Widget value = widget(args.arg(1).optjstring(""));
            if (value == null) return LuaValue.FALSE;
            value.data().set("fontScale", Math.max(0.1, args.arg(2).optdouble(16) / 16.0));
            return LuaValue.TRUE;
        }));
        globals.set("setTextColor", function(args -> {
            Widget value = widget(args.arg(1).optjstring(""));
            if (value == null) return LuaValue.FALSE;
            value.data().set("color", luaColor(args.arg(2), 0xFFFFFF)); return LuaValue.TRUE;
        }));
        globals.set("setTextFont", function(args -> {
            Widget value = widget(args.arg(1).optjstring(""));
            if (value == null) return LuaValue.FALSE;
            value.data().set("font", args.arg(2).optjstring("")); return LuaValue.TRUE;
        }));
        globals.set("setTextQuality", function(args -> {
            Widget value = widget(args.arg(1).optjstring(""));
            if (value == null) return LuaValue.FALSE;
            value.data().set("fontQuality", LuaFontLoader.clampOversample(
                    args.arg(2).optdouble(LuaFontLoader.DEFAULT_OVERSAMPLE)));
            return LuaValue.TRUE;
        }));
        globals.set("getTextQuality", function(args -> {
            Widget value = widget(args.arg(1).optjstring(""));
            return LuaValue.valueOf(value == null ? LuaFontLoader.DEFAULT_OVERSAMPLE
                    : value.data().get("fontQuality").optdouble(LuaFontLoader.DEFAULT_OVERSAMPLE));
        }));
        globals.set("setTextAlignment", function(args -> {
            Widget value = widget(args.arg(1).optjstring(""));
            if (value == null) return LuaValue.FALSE;
            value.data().set("alignment", normalizeAlignment(args.arg(2).optjstring("center")));
            return LuaValue.TRUE;
        }));
        globals.set("setTextBorder", function(args -> {
            Widget value = widget(args.arg(1).optjstring(""));
            if (value == null) return LuaValue.FALSE;
            value.data().set("borderSize", Math.max(0, args.arg(2).optdouble(1)));
            value.data().set("borderColor", luaColor(args.arg(3), 0));
            value.data().set("borderStyle", args.arg(4).optjstring("outline"));
            return LuaValue.TRUE;
        }));
        globals.set("setTextItalic", textBooleanSetter("italic"));
        globals.set("setTextShadow", textBooleanSetter("shadow"));
        globals.set("setTextLineSpacing", textNumberSetter("lineSpacing"));
        globals.set("setTextLetterSpacing", textNumberSetter("letterSpacing"));
    }

    private LuaValue textBooleanSetter(String property) {
        return function(args -> {
            Widget value = widget(args.arg(1).optjstring(""));
            if (value == null) return LuaValue.FALSE;
            value.data().set(property, LuaValue.valueOf(args.arg(2).optboolean(false)));
            return LuaValue.TRUE;
        });
    }

    private LuaValue textNumberSetter(String property) {
        return function(args -> {
            Widget value = widget(args.arg(1).optjstring(""));
            if (value == null) return LuaValue.FALSE;
            value.data().set(property, args.arg(2).optdouble(0)); return LuaValue.TRUE;
        });
    }

    /** Lua-side editor metadata: visual keyframes remain Lua, never a companion JSON. */
    private void installEditorLuaSupport() {
        LuaTable editor = new LuaTable();
        editor.set("begin", function(args -> {
            captureEditorSourceState();
            return LuaValue.TRUE;
        }));
        editor.set("keyframe", function(args -> {
            int at = offset(args, editor);
            String tag = args.arg(at).optjstring("");
            String target = args.arg(at + 1).optjstring("");
            String property = args.arg(at + 2).optjstring("");
            double from = args.arg(at + 3).optdouble(0);
            double to = args.arg(at + 4).optdouble(0);
            double duration = Math.max(0, args.arg(at + 5).optdouble(1));
            String easing = args.arg(at + 6).optjstring("linear");
            double delay = Math.max(0, args.arg(at + 7).optdouble(0));
            if (!tag.isBlank() && !target.isBlank() && !property.isBlank()) {
                editorTweens.removeIf(value -> value.tag().equals(tag));
                editorTweens.add(new EditorTween(tag, target, property, from, to, duration, easing, delay));
            }
            return LuaValue.TRUE;
        }));
        editor.set("remove", function(args -> {
            String id = args.arg(offset(args, editor)).optjstring("");
            if (id.isBlank()) return LuaValue.FALSE;
            editorRemovedIds.add(id);
            animatedStates.remove(id);
            tweens.values().removeIf(value -> value.widgetId().equals(id));
            return LuaValue.valueOf(widgets.removeIf(value -> value.id().equals(id)));
        }));
        globals.set("blockifiedEditor", editor);
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
            installWorldFields(table);
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
            installWorldFields(table);
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
                    installTextFields(table);
                }
            }
            if (kind.equals("button")) {
                table.set("backgroundColor", 0x333333);
                table.set("hoverColor", 0x555555);
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

    private static void installTextFields(LuaTable table) {
        table.set("font", ""); table.set("fontScale", 1.0);
        table.set("fontQuality", LuaFontLoader.DEFAULT_OVERSAMPLE); table.set("shadow", LuaValue.TRUE);
        table.set("alignment", "center"); table.set("borderSize", 0.0); table.set("borderColor", 0);
        table.set("borderStyle", "outline"); table.set("italic", LuaValue.FALSE);
        table.set("lineSpacing", 0.0); table.set("letterSpacing", 0.0);
    }

    private static String normalizeAlignment(String value) {
        return switch (value == null ? "" : value.trim().toLowerCase(Locale.ROOT)) {
            case "left" -> "left"; case "right" -> "right"; default -> "center";
        };
    }

    private static void installWorldFields(LuaTable table) {
        table.set("space", "screen");
        table.set("coordinates", "relative");
        table.set("z", 0.0);
        table.set("rotationX", 0.0);
        table.set("rotationY", 0.0);
        table.set("billboard", LuaValue.TRUE);
        table.set("lighting", LuaValue.FALSE);
        table.set("renderMode", "auto");
        table.set("seeThrough", LuaValue.FALSE);
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
        machine.set("id", machineTag);
        machine.set("tag", machineTag);
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
        machine.set("getWeeks", function(args -> weeksTable()));
        machine.set("getWeek", function(args -> {
            String id = args.arg(offset(args, machine)).optjstring("");
            WeekDefinition week = WeekLibrary.find(id);
            return week == null ? LuaValue.NIL : weekTable(week);
        }));
        machine.set("hasWeek", function(args -> {
            String id = args.arg(offset(args, machine)).optjstring("");
            return LuaValue.valueOf(WeekLibrary.find(id) != null);
        }));
        machine.set("getScreen", function(args -> LuaValue.valueOf(currentScreen)));
        machine.set("getScreens", function(args -> {
            LuaTable table = new LuaTable();
            List<String> screens = availableScreens();
            for (int i = 0; i < screens.size(); i++) table.set(i + 1, screens.get(i));
            return table;
        }));
        machine.set("openScreen", function(args -> {
            String name = args.arg(offset(args, machine)).optjstring("").trim();
            if (screenScript(name) == null) return LuaValue.FALSE;
            pendingScreen = canonicalScreen(name);
            return LuaValue.TRUE;
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
            String targetMachine = "";
            if (options.istable()) {
                targetMachine = options.get("machine").optjstring("").trim().toLowerCase(Locale.ROOT);
                if (!targetMachine.isEmpty()
                        && (targetMachine.length() > 64 || !targetMachine.matches("[a-z0-9_-]+")))
                    throw new LuaError("machine.playSong: machine must be a virtual-machine tag (letters, numbers, _ or -; max 64).");
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
                    playbackMode, returnTarget, targetMachine));
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
        machine.set("exitWorld", function(args -> LuaValue.valueOf(host.exitWorld())));
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

    private LuaTable weeksTable() {
        LuaTable table = new LuaTable();
        List<WeekDefinition> values = WeekLibrary.all();
        for (int i = 0; i < values.size(); i++) table.set(i + 1, weekTable(values.get(i)));
        return table;
    }

    private LuaTable weekTable(WeekDefinition week) {
        LuaTable table = new LuaTable();
        table.set("id", week.id());
        table.set("name", week.displayName());
        table.set("storyName", week.storyName());
        table.set("weekName", week.weekName());
        table.set("background", week.background());
        table.set("weekBefore", week.weekBefore());
        table.set("startUnlocked", LuaValue.valueOf(week.startUnlocked()));
        table.set("hiddenUntilUnlocked", LuaValue.valueOf(week.hiddenUntilUnlocked()));
        table.set("hideStoryMode", LuaValue.valueOf(week.hideStoryMode()));
        table.set("hideFreeplay", LuaValue.valueOf(week.hideFreeplay()));
        table.set("image", week.imageFile() == null ? LuaValue.NIL : LuaValue.valueOf(week.imageFile().toString()));
        LuaTable difficulties = new LuaTable();
        for (int i = 0; i < week.difficulties().size(); i++) difficulties.set(i + 1, week.difficulties().get(i));
        table.set("difficulties", difficulties);
        LuaTable characters = new LuaTable();
        for (int i = 0; i < week.characters().size(); i++) characters.set(i + 1, week.characters().get(i));
        table.set("characters", characters);
        LuaTable weekSongs = new LuaTable();
        for (int i = 0; i < week.songs().size(); i++) {
            WeekDefinition.Song song = week.songs().get(i);
            LuaTable value = new LuaTable();
            value.set("id", song.id()); value.set("icon", song.icon()); value.set("color", song.color());
            value.set("playable", LuaValue.valueOf(findSong(song.id()).isPresent()));
            weekSongs.set(i + 1, value);
        }
        table.set("songs", weekSongs);
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
        installTweenFunction("doTweenZ", "z");
        installTweenFunction("doTweenAlpha", "alpha");
        installTweenFunction("doTweenAngle", "angle");
        installTweenFunction("doTweenRotationX", "rotationX");
        installTweenFunction("doTweenRotationY", "rotationY");
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
            var menuCamera = menuCameraController();
            if (menuCamera != null) removed |= menuCamera.cancelTween(tag);
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

    /** 3D Minecraft camera controls for authored menu scenes. Zoom is a FOV multiplier. */
    private void installCamera() {
        LuaTable camera = new LuaTable();
        com.fnfmod.client.camera.MenuCameraController controller = menuCameraController();
        if (controller != null) controller.onComplete(tag ->
                callGlobal("onTweenCompleted", LuaValue.valueOf(tag)));

        camera.set("setPosition", function(args -> {
            int at = offset(args, camera);
            var value = menuCameraController();
            if (value == null) return LuaValue.FALSE;
            value.setPosition(args.arg(at).optdouble(value.position().x),
                    args.arg(at + 1).optdouble(value.position().y),
                    args.arg(at + 2).optdouble(value.position().z), 0, "linear", "");
            return LuaValue.TRUE;
        }));
        camera.set("setRotation", function(args -> {
            int at = offset(args, camera);
            var value = menuCameraController();
            if (value == null) return LuaValue.FALSE;
            value.setRotation(args.arg(at).optdouble(value.pitch()),
                    args.arg(at + 1).optdouble(value.yaw()), args.arg(at + 2).optdouble(value.roll()),
                    0, "linear", "");
            return LuaValue.TRUE;
        }));
        camera.set("lookAt", function(args -> {
            int at = offset(args, camera);
            var value = menuCameraController();
            if (value == null) return LuaValue.FALSE;
            value.lookAt(args.arg(at).optdouble(0), args.arg(at + 1).optdouble(0),
                    args.arg(at + 2).optdouble(0), 0, "linear", "");
            return LuaValue.TRUE;
        }));
        camera.set("setZoom", function(args -> {
            int at = offset(args, camera);
            var value = menuCameraController();
            if (value == null) return LuaValue.FALSE;
            value.setZoom(args.arg(at).optdouble(1), 0, "linear", "");
            return LuaValue.TRUE;
        }));
        camera.set("setFOV", function(args -> {
            int at = offset(args, camera);
            var value = menuCameraController();
            if (value == null) return LuaValue.FALSE;
            value.setFov(args.arg(at).optdouble(value.fov()));
            return LuaValue.TRUE;
        }));
        camera.set("getX", function(args -> LuaValue.valueOf(cameraPosition(0))));
        camera.set("getY", function(args -> LuaValue.valueOf(cameraPosition(1))));
        camera.set("getZ", function(args -> LuaValue.valueOf(cameraPosition(2))));
        camera.set("getPitch", function(args -> LuaValue.valueOf(cameraRotation(0))));
        camera.set("getYaw", function(args -> LuaValue.valueOf(cameraRotation(1))));
        camera.set("getRoll", function(args -> LuaValue.valueOf(cameraRotation(2))));
        camera.set("getZoom", function(args -> {
            var value = menuCameraController();
            return LuaValue.valueOf(value == null ? 1 : value.zoom());
        }));
        camera.set("getFOV", function(args -> {
            var value = menuCameraController();
            return LuaValue.valueOf(value == null ? 70 : value.fov());
        }));
        camera.set("reset", function(args -> {
            int at = offset(args, camera);
            var value = menuCameraController();
            if (value == null) return LuaValue.FALSE;
            value.reset(0, "linear", "");
            return LuaValue.TRUE;
        }));
        globals.set("camera", camera);
        globals.set("setFOV", function(args -> {
            var value = menuCameraController();
            if (value == null) return LuaValue.FALSE;
            value.setFov(args.arg(1).optdouble(value.fov()));
            return LuaValue.TRUE;
        }));
        globals.set("getFOV", function(args -> {
            var value = menuCameraController();
            return LuaValue.valueOf(value == null ? 70 : value.fov());
        }));
        installCameraAxisTween("doTweenCamX", false, 0);
        installCameraAxisTween("doTweenCamY", false, 1);
        installCameraAxisTween("doTweenCamZ", false, 2);
        installCameraAxisTween("doTweenCamRotationX", true, 0);
        installCameraAxisTween("doTweenCamRotationY", true, 1);
        installCameraAxisTween("doTweenCamRotationZ", true, 2);
        globals.set("doTweenCamZoom", function(args -> {
            var value = menuCameraController();
            if (value == null) return LuaValue.FALSE;
            value.setZoom(args.arg(2).optdouble(value.zoom()), args.arg(3).optdouble(1),
                    args.arg(4).optjstring("linear"), args.arg(1).optjstring("cameraZoom"));
            return LuaValue.TRUE;
        }));
    }

    private void installCameraAxisTween(String name, boolean rotation, int axis) {
        globals.set(name, function(args -> {
            var value = menuCameraController();
            if (value == null) return LuaValue.FALSE;
            String tag = args.arg(1).checkjstring();
            double fallback = rotation ? cameraRotation(axis) : cameraPosition(axis);
            double target = args.arg(2).optdouble(fallback);
            double duration = args.arg(3).optdouble(1);
            String easing = args.arg(4).optjstring("linear");
            if (rotation) value.tweenRotationAxis(axis, target, duration, easing, tag);
            else value.tweenPositionAxis(axis, target, duration, easing, tag);
            return LuaValue.TRUE;
        }));
    }

    private double cameraPosition(int axis) {
        var value = menuCameraController();
        if (value == null) return 0;
        return axis == 0 ? value.position().x : axis == 1 ? value.position().y : value.position().z;
    }

    private double cameraRotation(int axis) {
        var value = menuCameraController();
        if (value == null) return 0;
        return axis == 0 ? value.pitch() : axis == 1 ? value.yaw() : value.roll();
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
        scrollInput.reset();
        if (pendingScreen != null && error == null) switchScreen();
        for (int key : queriedKeys) previousKeys.put(key, rawKeyDown(key));
        justPressedKeys.clear();
        justReleasedKeys.clear();
    }

    public String editorCurrentScreen() { return currentScreen; }
    public boolean isMainScreen() { return currentScreen.equals("main"); }
    public boolean returnToMainScreen() {
        if (isMainScreen()) return true;
        pendingScreen = "main";
        switchScreen();
        return error == null && isMainScreen();
    }
    public List<String> editorScreens() { return List.copyOf(availableScreens()); }
    public boolean editorOpenScreen(String name) {
        String next = canonicalScreen(name);
        if (screenScript(next) == null) return false;
        if (next.equals(currentScreen)) return true;
        pendingScreen = next;
        switchScreen();
        return error == null && next.equals(currentScreen);
    }

    private void switchScreen() {
        String next = pendingScreen;
        pendingScreen = null;
        Path script = screenScript(next);
        if (script == null || next.equals(currentScreen)) return;
        callGlobal("onClose");
        if (error != null) return;
        widgets.clear();
        animatedStates.clear();
        hoveredWidgets.clear();
        tweens.clear();
        timers.clear();
        editorTweens.clear();
        editorRemovedIds.clear();
        detectedTweenTags.clear();
        editorSnapshotHeads.clear();
        editorSnapshotTimes.clear();
        editorSourceValues.clear();
        editorSourceCamera.clear();
        editorSourceCaptured = false;
        editorSnapshotSequence = 0;
        nextWidgetOrder = 0;
        scrollInput.reset();
        for (String callback : List.of("onOpen", "onClose", "onUpdate", "onTimerCompleted",
                "onTweenCompleted", "onSoundFinished", "onMouseDown", "onMouseUp", "onClick",
                "onHover", "onHoverExit", "onKeyPress", "onKeyRelease", "onScroll"))
            globals.set(callback, LuaValue.NIL);
        try {
            scriptSource = Files.readString(script);
            budget.begin();
            globals.load(scriptSource, script.toString()).call();
            budget.end();
            if (!editorSourceCaptured && !scriptSource.contains(EDITOR_LUA_BEGIN))
                captureEditorSourceState();
            if (next.equals("main")) applyEditorLayout();
            detectLuaTweens(scriptSource);
            currentScreen = next;
            callGlobal("onOpen");
        } catch (Throwable throwable) {
            budget.end();
            fail(throwable);
        }
    }

    private List<String> availableScreens() {
        List<String> result = new ArrayList<>();
        result.add("main");
        if (definition.root() == null) return result;
        Path directory = definition.root().resolve("screens").normalize();
        if (!Files.isDirectory(directory) || !ModContentScope.allowsContentPath(directory)) return result;
        try (var files = Files.list(directory)) {
            files.filter(Files::isRegularFile).map(path -> path.getFileName().toString())
                    .filter(name -> name.toLowerCase(Locale.ROOT).endsWith(".lua"))
                    .map(name -> name.substring(0, name.length() - 4))
                    .sorted().forEach(result::add);
        } catch (Exception ignored) {}
        return result;
    }

    private Path screenScript(String name) {
        String id = canonicalScreen(name);
        if (id.equals("main")) return definition.menuScript();
        if (definition.root() == null || !id.matches("[a-z0-9_.-]+")) return null;
        Path root = definition.root().toAbsolutePath().normalize();
        Path file = root.resolve("screens").resolve(id + ".lua").normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file) || !ModContentScope.allowsContentPath(file)) return null;
        return file;
    }

    private static String canonicalScreen(String name) {
        if (name == null || name.isBlank()) return "main";
        String value = name.trim().toLowerCase(Locale.ROOT);
        if (value.endsWith(".lua")) value = value.substring(0, value.length() - 4);
        return value;
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
        screenCursorX = mouseX;
        screenCursorY = mouseY;
        updateCursor();
        updateHover();
        List<Widget> ordered = orderedWidgets();
        PsychCanvas.push(gui, 1f);
        try {
            for (int i = 0; i < ordered.size(); i++) {
                if (isWorld(ordered.get(i).data()) || LuaLayerEffects.hidden(ordered.get(i).data())) continue;
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

    /** Editor preview: fits the same 1280x720 menu canvas inside a reserved rectangle. */
    public void renderInViewport(GuiGraphics gui, Font font, int mouseX, int mouseY,
                                 int viewportX, int viewportY, int viewportWidth, int viewportHeight) {
        advanceTweens();
        advanceAnimations();
        double scale = Math.max(1.0e-6, Math.min(viewportWidth / (double) PsychCanvas.WIDTH,
                viewportHeight / (double) PsychCanvas.HEIGHT));
        double left = viewportX + (viewportWidth - PsychCanvas.WIDTH * scale) * 0.5;
        double top = viewportY + (viewportHeight - PsychCanvas.HEIGHT * scale) * 0.5;
        double canvasMouseX = (mouseX - left) / scale;
        double canvasMouseY = (mouseY - top) / scale;
        cursorX = canvasMouseX; cursorY = canvasMouseY;
        screenCursorX = mouseX; screenCursorY = mouseY;
        updateCursor(); updateHover();
        List<Widget> ordered = orderedWidgets();
        gui.enableScissor(viewportX, viewportY, viewportX + viewportWidth, viewportY + viewportHeight);
        gui.pose().pushPose();
        try {
            gui.pose().translate(left, top, 0);
            gui.pose().scale((float) scale, (float) scale, 1);
            for (int i = 0; i < ordered.size(); i++) {
                if (isWorld(ordered.get(i).data()) || LuaLayerEffects.hidden(ordered.get(i).data())) continue;
                gui.pose().pushPose();
                gui.pose().translate(0, 0, i + 1);
                renderWidget(gui, font, ordered.get(i), canvasMouseX, canvasMouseY);
                gui.flush();
                gui.pose().popPose();
            }
        } finally {
            gui.pose().popPose();
            gui.disableScissor();
        }
    }

    /** Editor-only hit testing: selects objects without executing their Lua click callbacks. */
    public String editorHitTest(double mouseX, double mouseY, int viewportX, int viewportY,
                                int viewportWidth, int viewportHeight) {
        double scale = viewportScale(viewportWidth, viewportHeight);
        double left = viewportLeft(viewportX, viewportWidth, scale);
        double top = viewportTop(viewportY, viewportHeight, scale);
        double canvasX = (mouseX - left) / scale;
        double canvasY = (mouseY - top) / scale;
        List<Widget> ordered = orderedWidgets();
        for (int i = ordered.size() - 1; i >= 0; i--) {
            Widget value = ordered.get(i);
            if (!value.data().get("visible").optboolean(true) || LuaLayerEffects.hidden(value.data())) continue;
            boolean hit = isWorld(value.data()) ? worldContains(value.id(), mouseX, mouseY)
                    : contains(value, canvasX, canvasY);
            if (hit) return value.id();
        }
        return "";
    }

    /** Current editor-space selection rectangle for screen and projected world widgets. */
    public EditorBounds editorBounds(String id, int viewportX, int viewportY,
                                     int viewportWidth, int viewportHeight) {
        Widget value = widget(id);
        if (value == null) return null;
        if (isWorld(value.data())) {
            double[] bounds = worldHitboxes.get(id);
            return bounds == null ? null : new EditorBounds(bounds[0], bounds[1], bounds[2], bounds[3]);
        }
        double scale = viewportScale(viewportWidth, viewportHeight);
        double left = viewportLeft(viewportX, viewportWidth, scale);
        double top = viewportTop(viewportY, viewportHeight, scale);
        int widgetWidth = effectiveWidth(value);
        int widgetHeight = effectiveHeight(value);
        if(layered(value)) {
            var matrix=menuTransform(value,new java.util.HashSet<>());
            double minX=Double.POSITIVE_INFINITY,minY=minX,maxX=Double.NEGATIVE_INFINITY,maxY=maxX;
            for(int sx:new int[]{-1,1}) for(int sy:new int[]{-1,1}) {
                var p=matrix.transformPosition(new org.joml.Vector3f(sx*widgetWidth/2f,sy*widgetHeight/2f,0));
                minX=Math.min(minX,p.x);minY=Math.min(minY,p.y);maxX=Math.max(maxX,p.x);maxY=Math.max(maxY,p.y);
            }
            return new EditorBounds(left+minX*scale,top+minY*scale,left+maxX*scale,top+maxY*scale);
        }
        int centerX = coordinate(value.data().get("x").optdouble(0.5), PsychCanvas.WIDTH);
        int centerY = coordinate(value.data().get("y").optdouble(0.5), PsychCanvas.HEIGHT);
        return new EditorBounds(left + (centerX - widgetWidth / 2.0) * scale,
                top + (centerY - widgetHeight / 2.0) * scale,
                left + (centerX + widgetWidth / 2.0) * scale,
                top + (centerY + widgetHeight / 2.0) * scale);
    }

    /** World centre used by editor camera orbiting around the selected 3D widget. */
    public Vec3 editorWorldPosition(String id, net.minecraft.core.BlockPos origin,
                                    net.minecraft.core.Direction facing) {
        Widget value = widget(id);
        if (value == null || origin == null || !isWorld(value.data())) return null;
        return worldPosition(value.data(), origin, facing);
    }

    public boolean editorIsParented(String id) {
        Widget w=widget(id);return w!=null&&!w.data().get("parent").optjstring("").isBlank();
    }
    public Vec3 editorParentDelta(String id,Vec3 delta) {
        Widget w=widget(id);if(w==null) return delta;
        Widget p=widget(w.data().get("parent").optjstring(""));if(p==null) return delta;
        var basis=menuTransform(p,new java.util.HashSet<>());
        basis.scale((float)(effectiveWidth(p)/Math.max(.0001,w.data().get("parentWidth").optdouble(effectiveWidth(p)))),
                (float)(effectiveHeight(p)/Math.max(.0001,w.data().get("parentHeight").optdouble(effectiveHeight(p)))),1);
        if(Math.abs(basis.determinant())<.000001) return Vec3.ZERO;
        var v=basis.invert().transformDirection(new org.joml.Vector3f((float)delta.x,(float)delta.y,(float)delta.z));
        return new Vec3(v.x,v.y,v.z);
    }

    /** Converts editor-visible world objects to stable Minecraft coordinates in-place. */
    public void editorUseMinecraftCoordinates(net.minecraft.core.BlockPos origin,
                                               net.minecraft.core.Direction facing) {
        if (origin == null) return;
        for (Widget value : widgets) {
            LuaTable data = value.data();
            if (!isWorld(data) || isMinecraftCoordinates(data) || editorIsParented(value.id())) continue;
            Vec3 position = worldPosition(data, origin, facing);
            data.set("x", position.x);
            data.set("y", position.y);
            data.set("z", position.z);
            data.set("coordinates", "minecraft");
        }
    }

    /** Changes one world widget's coordinate basis without moving it visually. */
    public boolean editorSetMinecraftCoordinates(String id, boolean minecraftCoordinates,
                                                  net.minecraft.core.BlockPos origin,
                                                  net.minecraft.core.Direction facing) {
        Widget value = widget(id);
        if (value == null || origin == null || !isWorld(value.data())) return false;
        LuaTable data = value.data();
        if(editorIsParented(id)) return false;
        if (minecraftCoordinates == isMinecraftCoordinates(data)) return true;
        Vec3 position = worldPosition(data, origin, facing);
        if (minecraftCoordinates) {
            data.set("x", position.x);
            data.set("y", position.y);
            data.set("z", position.z);
            data.set("coordinates", "minecraft");
            return true;
        }
        net.minecraft.core.Direction forward = facing == null
                ? net.minecraft.core.Direction.NORTH : facing;
        net.minecraft.core.Direction right = forward.getCounterClockWise();
        Vec3 delta = position.subtract(Vec3.atCenterOf(origin));
        data.set("x", delta.x * right.getStepX() + delta.z * right.getStepZ());
        data.set("y", delta.y);
        data.set("z", delta.x * forward.getStepX() + delta.z * forward.getStepZ());
        data.set("coordinates", "relative");
        return true;
    }

    public double editorWorldSizeBlocks(String id) {
        Widget value = widget(id);
        if (value == null || !isWorld(value.data())) return 1.0;
        return Math.max(1.0 / 64.0, Math.max(effectiveWidth(value), effectiveHeight(value)) / 64.0);
    }

    private static double viewportScale(int width, int height) {
        return Math.max(1.0e-6, Math.min(width / (double) PsychCanvas.WIDTH,
                height / (double) PsychCanvas.HEIGHT));
    }

    private static double viewportLeft(int x, int width, double scale) {
        return x + (width - PsychCanvas.WIDTH * scale) * 0.5;
    }

    private static double viewportTop(int y, int height, double scale) {
        return y + (height - PsychCanvas.HEIGHT * scale) * 0.5;
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
        if (!widget.data().get("visible").optboolean(true)) return;
        if (!layered(widget)) { renderWidgetRaw(gui,font,widget,mouseX,mouseY); return; }
        gui.flush();
        var surface=menuSurface(widget,font);
        if(surface==null) return;
        gui.pose().pushPose();
        gui.pose().mulPose(menuTransform(widget,new java.util.HashSet<>()));
        int w=effectiveWidth(widget),h=effectiveHeight(widget);
        com.fnfmod.client.render.LuaLayerRenderer.draw(gui.pose().last().pose(),surface,-w/2f,-h/2f,w,h,
                widget.kind().equals("group")?(float)widget.data().get("alpha").optdouble(1):1,false,false);
        gui.pose().popPose();
    }

    private void renderWidgetRaw(GuiGraphics gui, Font font, Widget widget, double mouseX,double mouseY) {
        LuaTable data = widget.data();
        if (!data.get("visible").optboolean(true)) return;
        int width = effectiveWidth(widget);
        int height = effectiveHeight(widget);
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
            case "panel", "gradient" -> gui.fill(x, y, x + width, y + height, color);
            case "graph" -> renderGraph(gui, data, centerX, centerY, width, height);
            case "image", "sprite" -> renderImage(gui, data, centerX, centerY, width, height);
            case "animatedSprite" -> renderAnimatedSprite(gui, widget.id(), data,
                    centerX, centerY, width, height);
            case "label" -> drawStyledText(gui, textFont, data, centerX, centerY,
                    width, height, color, fontScale, textShadow);
            case "button" -> {
                gui.fill(x, y, x + width, y + height,
                        controlColor(data, hover ? "hoverColor" : "backgroundColor",
                                hover ? 0x555555 : 0x333333));
                drawStyledText(gui, textFont, data, centerX, centerY,
                        width, height, color, fontScale, textShadow);
            }
            case "toggle" -> {
                boolean value = data.get("value").optboolean(false);
                gui.fill(x, y, x + width, y + height, value ? 0xCC397A49 : 0xCC5A3333);
                String original = data.get("text").optjstring("");
                data.set("text", original + ": " + (value ? "ON" : "OFF"));
                drawStyledText(gui, textFont, data, centerX, centerY,
                        width, height, color, fontScale, textShadow);
                data.set("text", original);
            }
            case "slider" -> {
                double min = data.get("min").optdouble(0);
                double max = data.get("max").optdouble(1);
                double value = data.get("value").optdouble(min);
                double ratio = max <= min ? 0 : Math.max(0, Math.min(1, (value - min) / (max - min)));
                gui.fill(x, y, x + width, y + height, 0xCC222222);
                gui.fill(x, y, x + (int) Math.round(width * ratio), y + height, 0xCC5577AA);
                drawStyledText(gui, textFont, data, centerX, centerY,
                        width, height, color, fontScale, textShadow);
            }
        }
    }

    /** Draws menu widgets whose space is 'world' into Minecraft's 3D scene. */
    public void renderWorld(com.mojang.blaze3d.vertex.PoseStack poseStack,
                            net.minecraft.client.Camera camera,
                            net.minecraft.core.BlockPos origin,
                            net.minecraft.core.Direction facing) {
        renderWorldObjects(poseStack, camera, origin, facing);
    }

    /** Editor entry point. It deliberately shares the normal menu's world transform. */
    public void renderWorldEditor(com.mojang.blaze3d.vertex.PoseStack poseStack,
                                  net.minecraft.client.Camera camera,
                                  net.minecraft.core.BlockPos origin,
                                  net.minecraft.core.Direction facing) {
        renderWorldObjects(poseStack, camera, origin, facing);
    }

    private void renderWorldObjects(com.mojang.blaze3d.vertex.PoseStack poseStack,
                                    net.minecraft.client.Camera camera,
                                    net.minecraft.core.BlockPos origin,
                                    net.minecraft.core.Direction facing) {
        layerOrigin=origin==null?net.minecraft.core.BlockPos.ZERO:origin;
        layerFacing=facing==null?net.minecraft.core.Direction.NORTH:facing;
        List<MenuWorldObject> out = new ArrayList<>();
        worldHitboxes.clear();
        worldLayerPicking.clear();
        for (Widget widget : orderedWidgets()) {
            LuaTable data = widget.data();
            if (!isWorld(data) || !data.get("visible").optboolean(true) || LuaLayerEffects.hidden(data)) continue;
            // Absolute Minecraft coordinates also use absolute Minecraft rotation.
            // Relative objects remain attached to the physical machine's facing.
            net.minecraft.core.Direction forward = isMinecraftCoordinates(data)
                    ? net.minecraft.core.Direction.NORTH
                    : facing == null ? net.minecraft.core.Direction.NORTH : facing;
            net.minecraft.core.Direction right = forward.getCounterClockWise();
            Vec3 anchor = Vec3.atCenterOf(origin == null ? net.minecraft.core.BlockPos.ZERO : origin);
            Vec3 position = worldPosition(data,
                    origin == null ? net.minecraft.core.BlockPos.ZERO : origin, facing);
            Vec3 local = position.subtract(anchor);
            double x = (local.x * right.getStepX() + local.z * right.getStepZ()) * 64.0;
            double y = -local.y * 64.0;
            double z = (local.x * forward.getStepX() + local.z * forward.getStepZ()) * 64.0;
            double width = Math.max(1, isTextWidget(widget) ? effectiveWidth(widget)
                    : data.get("width").optdouble(64));
            double height = Math.max(1, isTextWidget(widget) ? effectiveHeight(widget)
                    : data.get("height").optdouble(64));
            double alpha = Math.max(0, Math.min(1, data.get("alpha").optdouble(1)));
            double angle = data.get("angle").optdouble(0);
            double rotationX = data.get("rotationX").optdouble(0);
            double rotationY = data.get("rotationY").optdouble(0);
            int color = data.get("color").optint(0xFFFFFF) & 0xFFFFFF;
            boolean billboard = data.get("billboard").optboolean(true);
            boolean lighting = data.get("lighting").optboolean(false);
            com.fnfmod.client.render.LuaWorldObject.RenderMode renderMode = worldRenderMode(data);
            boolean seeThrough = data.get("seeThrough").optboolean(false);
            projectWorldHitbox(widget.id(), data, camera, origin, facing, width, height);

            if(layered(widget)) {
                flushMenuWorld(out,poseStack,camera,origin); out.clear();
                // Same local texture in editor and runtime; 3D depth is still tested by Minecraft.
                var surface=menuSurface(widget,Minecraft.getInstance().font);
                if(surface!=null) {
                    var transform=menuTransform(widget,new java.util.HashSet<>());
                    projectLayerPicking(widget,transform,camera,width,height);
                    poseStack.pushPose();
                    poseStack.translate(-camera.getPosition().x,-camera.getPosition().y,-camera.getPosition().z);
                    poseStack.mulPose(transform);
                    poseStack.scale(1/64f,-1/64f,1/64f);
                    com.fnfmod.client.render.LuaLayerRenderer.draw(poseStack.last().pose(),surface,
                            (float)-width/2,(float)-height/2,(float)width,(float)height,
                            widget.kind().equals("group")?(float)alpha:1,true,seeThrough);
                    poseStack.popPose();
                }
                continue;
            }

            switch (widget.kind()) {
                case "label" -> out.add(new MenuWorldObject(forward, worldText(data, x, y, z, width, alpha, angle,
                        rotationX, rotationY, color, billboard, lighting, renderMode,
                        seeThrough, false)));
                case "image", "sprite" -> {
                    Path file = resolveAsset(data.get("path").optjstring(""));
                    ResourceLocation texture = MachineTextureCache.get(file);
                    MachineTextureCache.Size size = MachineTextureCache.size(file);
                    out.add(new MenuWorldObject(forward, worldSprite(texture, size.width(), size.height(), null, x, y, z,
                            width, height, width, height, alpha, angle, rotationX, rotationY,
                            color, billboard, lighting, renderMode, seeThrough)));
                }
                case "animatedSprite" -> {
                    AnimatedState state = animatedStates.get(widget.id());
                    SparrowAtlas atlas = state == null ? null : atlasFor(state);
                    List<SparrowAtlas.Frame> frames = atlas == null || state.prefix.isBlank()
                            ? List.of() : animationFrames(atlas, state.prefix);
                    if (atlas != null && !frames.isEmpty()) {
                        SparrowAtlas.Frame frame = frames.get(Math.max(0,
                                Math.min(frames.size() - 1, state.frame)));
                        var worldFrame = new com.fnfmod.client.render.LuaWorldObject.Frame(
                                frame.x, frame.y, frame.w, frame.h, frame.frameX, frame.frameY, frame.rotated);
                        out.add(new MenuWorldObject(forward, worldSprite(atlas.texture(), atlas.width(), atlas.height(), worldFrame,
                                x, y, z, width, height, frame.frameW, frame.frameH, alpha, angle,
                                rotationX, rotationY, color, billboard, lighting, renderMode,
                                seeThrough)));
                    }
                }
                default -> {
                    boolean hovered = data.get("hovered").optboolean(false);
                    boolean textControl = widget.kind().equals("button")
                            || widget.kind().equals("toggle") || widget.kind().equals("slider");
                    int background = switch (widget.kind()) {
                        case "button" -> luaColor(data.get(hovered ? "hoverColor" : "backgroundColor"),
                                hovered ? 0x555555 : 0x333333);
                        case "slider" -> hovered ? 0x555555 : 0x333333;
                        case "toggle" -> data.get("value").optboolean(false) ? 0x397A49 : 0x5A3333;
                        default -> color;
                    };
                    out.add(new MenuWorldObject(forward, worldSprite(null, 1, 1, null, x, y, z, width, height,
                            width, height, alpha, angle, rotationX, rotationY, background,
                            billboard, lighting, renderMode, seeThrough)));
                    if (!widget.kind().equals("panel") && !widget.kind().equals("graph")) {
                        out.add(new MenuWorldObject(forward, worldText(data, x, y, z, width, alpha, angle,
                                rotationX, rotationY, color, billboard, lighting, renderMode, seeThrough,
                                textControl)));
                    }
                }
            }
        }
        flushMenuWorld(out,poseStack,camera,origin);
    }

    private void flushMenuWorld(List<MenuWorldObject> out,com.mojang.blaze3d.vertex.PoseStack poseStack,
                                net.minecraft.client.Camera camera,net.minecraft.core.BlockPos origin) {
        net.minecraft.core.BlockPos anchor = origin == null ? net.minecraft.core.BlockPos.ZERO : origin;
        for (int i = 0; i < out.size(); i++) {
            MenuWorldObject value = out.get(i);
            com.fnfmod.client.render.LuaWorldObjectRenderer.render(poseStack, camera,
                    anchor, value.facing(), List.of(value.object()), i);
        }
    }

    /** Free-cam-style world selection box and visible centre/origin cube for the editor. */
    public void renderEditorSelection(com.mojang.blaze3d.vertex.PoseStack poseStack,
                                      net.minecraft.client.Camera camera,
                                      net.minecraft.core.BlockPos origin,
                                      net.minecraft.core.Direction facing,
                                      String id) {
        Widget widget = widget(id);
        if (widget == null || camera == null || origin == null || !isWorld(widget.data())) return;
        LuaTable data = widget.data();
        net.minecraft.core.Direction forward = facing == null ? net.minecraft.core.Direction.NORTH : facing;
        Vec3 world = worldPosition(data, origin, facing);
        Vec3 cameraPos = camera.getPosition();
        var buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        var lines = com.fnfmod.client.render.OverlayLines.renderType();
        var vertices = buffers.getBuffer(lines);
        double width = Math.max(24, Math.abs(data.get("width").optdouble(1)));
        double height = Math.max(24, Math.abs(data.get("height").optdouble(1)));
        double halfWidth = width * 0.5 * com.fnfmod.client.render.LuaWorldObjectRenderer.PIXEL_SCALE + 0.1;
        double halfHeight = height * 0.5 * com.fnfmod.client.render.LuaWorldObjectRenderer.PIXEL_SCALE + 0.1;

        poseStack.pushPose();
        poseStack.translate(world.x - cameraPos.x, world.y - cameraPos.y, world.z - cameraPos.z);
        poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-forward.toYRot()));
        net.minecraft.client.renderer.LevelRenderer.renderLineBox(poseStack, vertices,
                -halfWidth, -halfHeight, -0.05, halfWidth, halfHeight, 0.05,
                1f, 0.9f, 0.28f, 1f);
        double center = 0.09;
        net.minecraft.client.renderer.LevelRenderer.renderLineBox(poseStack, vertices,
                -center, -center, -center, center, center, center,
                1f, 1f, 0.45f, 1f);
        poseStack.popPose();
        buffers.endBatch(lines);
    }

    private void projectWorldHitbox(String id, LuaTable data, net.minecraft.client.Camera camera,
                                    net.minecraft.core.BlockPos origin,
                                    net.minecraft.core.Direction facing,
                                    double widthPixels, double heightPixels) {
        if (camera == null || origin == null) return;
        Vec3 center = worldPosition(data, origin, facing);
        Vec3 delta = center.subtract(camera.getPosition());
        org.joml.Vector3f look = camera.getLookVector();
        org.joml.Vector3f left = camera.getLeftVector();
        org.joml.Vector3f up = camera.getUpVector();
        double depth = delta.x * look.x + delta.y * look.y + delta.z * look.z;
        if (depth <= 0.02) return;
        Minecraft minecraft = Minecraft.getInstance();
        double guiW = minecraft.getWindow().getGuiScaledWidth();
        double guiH = minecraft.getWindow().getGuiScaledHeight();
        double effectiveFov = com.fnfmod.client.camera.MenuCameraController.effectiveFov(70.0);
        double fov = Math.toRadians(Math.max(10, Math.min(170, effectiveFov)));
        double focal = guiH / (2.0 * Math.tan(fov * 0.5));
        double side = delta.x * left.x + delta.y * left.y + delta.z * left.z;
        double vertical = delta.x * up.x + delta.y * up.y + delta.z * up.z;
        double cx = guiW * 0.5 - side / depth * focal;
        double cy = guiH * 0.5 - vertical / depth * focal;
        double halfW = Math.max(2, widthPixels / 64.0 / depth * focal * 0.5);
        double halfH = Math.max(2, heightPixels / 64.0 / depth * focal * 0.5);
        if (editorViewportWidth > 0 && editorViewportHeight > 0) {
            double targetAspect = editorViewportWidth / (double) editorViewportHeight;
            double sourceAspect = guiW / Math.max(1.0, guiH);
            double cropX = 0, cropY = 0, cropW = guiW, cropH = guiH;
            if (sourceAspect > targetAspect) {
                cropW = guiH * targetAspect;
                cropX = (guiW - cropW) * 0.5;
            } else if (sourceAspect < targetAspect) {
                cropH = guiW / targetAspect;
                cropY = (guiH - cropH) * 0.5;
            }
            double scaleX = editorViewportWidth / Math.max(1.0, cropW);
            double scaleY = editorViewportHeight / Math.max(1.0, cropH);
            cx = editorViewportX + (cx - cropX) * scaleX;
            cy = editorViewportY + (cy - cropY) * scaleY;
            halfW *= scaleX;
            halfH *= scaleY;
        }
        worldHitboxes.put(id, new double[]{cx - halfW, cy - halfH, cx + halfW, cy + halfH});
    }

    /** Perspective-correct plane picking, including inherited rotation/non-uniform scale. */
    private void projectLayerPicking(Widget w,org.joml.Matrix4f transform,net.minecraft.client.Camera camera,
                                     double width,double height) {
        double gw=Minecraft.getInstance().getWindow().getGuiScaledWidth(),gh=Minecraft.getInstance().getWindow().getGuiScaledHeight();
        double fov=com.fnfmod.client.camera.MenuCameraController.effectiveFov(70);
        double focal=gh/(2*Math.tan(Math.toRadians(Math.clamp(fov,10,170)/2)));
        double sx=1,sy=1,ox=0,oy=0;
        if(editorViewportWidth>0&&editorViewportHeight>0) {
            double target=editorViewportWidth/(double)editorViewportHeight,cw=gw,ch=gh;
            if(gw/gh>target) cw=gh*target;else ch=gw/target;
            sx=editorViewportWidth/cw;sy=editorViewportHeight/ch;
            ox=editorViewportX-(gw-cw)*.5*sx;oy=editorViewportY-(gh-ch)*.5*sy;
        }
        org.joml.Vector3f[] p=new org.joml.Vector3f[3];
        for(int i=0;i<3;i++) {
            var v=transform.transformPosition(new org.joml.Vector3f(
                    (float)((i==1?.5:-.5)*width/64),(float)((i==2?-.5:.5)*height/64),0));
            v.sub((float)camera.getPosition().x,(float)camera.getPosition().y,(float)camera.getPosition().z);
            double depth=v.dot(camera.getLookVector());
            double nx=gw*.5*depth-v.dot(camera.getLeftVector())*focal;
            double ny=gh*.5*depth-v.dot(camera.getUpVector())*focal;
            p[i]=new org.joml.Vector3f((float)(nx*sx+ox*depth),(float)(ny*sy+oy*depth),(float)depth);
        }
        var homography=new org.joml.Matrix3f().setColumn(0,new org.joml.Vector3f(p[1]).sub(p[0]))
                .setColumn(1,new org.joml.Vector3f(p[2]).sub(p[0])).setColumn(2,p[0]);
        if(Math.abs(homography.determinant())<.000001) return;
        double minX=Double.POSITIVE_INFINITY,minY=minX,maxX=Double.NEGATIVE_INFINITY,maxY=maxX;
        for(int x=0;x<2;x++) for(int y=0;y<2;y++) {
            var point=homography.transform(new org.joml.Vector3f(x,y,1));if(point.z<=.02) return;
            double px=point.x/point.z,py=point.y/point.z;
            minX=Math.min(minX,px);minY=Math.min(minY,py);maxX=Math.max(maxX,px);maxY=Math.max(maxY,py);
        }
        worldLayerPicking.put(w.id(),homography.invert());
        worldHitboxes.put(w.id(),new double[]{minX,minY,maxX,maxY});
    }

    private com.fnfmod.client.render.LuaWorldObject.Text worldText(
            LuaTable data, double x, double y, double z, double width, double alpha,
            double angle, double rotationX, double rotationY, int color,
            boolean billboard, boolean lighting,
            com.fnfmod.client.render.LuaWorldObject.RenderMode renderMode, boolean seeThrough,
            boolean surfaceAttached) {
        float fontScale = (float) Math.max(0.1, Math.min(16, data.get("fontScale").optdouble(1)));
        return new com.fnfmod.client.render.LuaWorldObject.Text(
                widgetFont(data, Minecraft.getInstance().font), data.get("text").optjstring(""),
                x, y, z, width, Math.max(1, Math.round(9 * fontScale)), 1, 1, alpha,
                angle, rotationX, rotationY, color, billboard, lighting, renderMode, seeThrough,
                data.get("borderSize").optdouble(0), data.get("borderColor").optint(0),
                data.get("borderStyle").optjstring("outline"),
                data.get("alignment").optjstring("center"), data.get("italic").optboolean(false),
                data.get("lineSpacing").optdouble(0), data.get("letterSpacing").optdouble(0),
                surfaceAttached, true);
    }

    private static com.fnfmod.client.render.LuaWorldObject.Sprite worldSprite(
            ResourceLocation texture, int textureWidth, int textureHeight,
            com.fnfmod.client.render.LuaWorldObject.Frame frame,
            double x, double y, double z, double width, double height,
            double graphicWidth, double graphicHeight, double alpha, double angle,
            double rotationX, double rotationY, int color,
            boolean billboard, boolean lighting,
            com.fnfmod.client.render.LuaWorldObject.RenderMode renderMode, boolean seeThrough) {
        return new com.fnfmod.client.render.LuaWorldObject.Sprite(texture, textureWidth, textureHeight,
                frame, 0, 0, x, y, z, width, height, graphicWidth, graphicHeight,
                1, 1, alpha, angle, rotationX, rotationY, color,
                billboard, lighting, renderMode, seeThrough);
    }

    private static com.fnfmod.client.render.LuaWorldObject.RenderMode worldRenderMode(LuaTable data) {
        String mode = data.get("renderMode").optjstring("auto");
        return com.fnfmod.client.render.LuaWorldObject.RenderMode.resolve(
                mode.equalsIgnoreCase("auto") ? null : mode,
                data.get("lighting").optboolean(false));
    }

    private static String normalizeRenderMode(String value) {
        if (value == null || value.isBlank() || value.equalsIgnoreCase("auto")) return "auto";
        return com.fnfmod.client.render.LuaWorldObject.RenderMode.resolve(value, false).luaName();
    }

    private static boolean isWorld(LuaTable data) {
        return "world".equalsIgnoreCase(data.get("space").optjstring("screen"));
    }

    private static boolean isMinecraftCoordinates(LuaTable data) {
        return "minecraft".equalsIgnoreCase(data.get("coordinates").optjstring("relative"));
    }

    private Vec3 worldPosition(LuaTable data, net.minecraft.core.BlockPos origin,
                                      net.minecraft.core.Direction facing) {
        if(!data.get("parent").optjstring("").isBlank()) {
            layerOrigin=origin==null?net.minecraft.core.BlockPos.ZERO:origin;
            layerFacing=facing==null?net.minecraft.core.Direction.NORTH:facing;
            var m=menuTransform(widget(data.get("id").optjstring("")),new java.util.HashSet<>());
            return new Vec3(m.m30(),m.m31(),m.m32());
        }
        return worldPositionRaw(data,origin,facing);
    }

    private static Vec3 worldPositionRaw(LuaTable data, net.minecraft.core.BlockPos origin,
                                      net.minecraft.core.Direction facing) {
        if (isMinecraftCoordinates(data)) {
            return new Vec3(data.get("x").optdouble(0), data.get("y").optdouble(0),
                    data.get("z").optdouble(0));
        }
        net.minecraft.core.Direction forward = facing == null
                ? net.minecraft.core.Direction.NORTH : facing;
        net.minecraft.core.Direction right = forward.getCounterClockWise();
        return Vec3.atCenterOf(origin == null ? net.minecraft.core.BlockPos.ZERO : origin).add(
                right.getStepX() * data.get("x").optdouble(0)
                        + forward.getStepX() * data.get("z").optdouble(0),
                data.get("y").optdouble(0),
                right.getStepZ() * data.get("x").optdouble(0)
                        + forward.getStepZ() * data.get("z").optdouble(0));
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

    /** Buttons retain their established 80% panel opacity, multiplied by widget alpha. */
    private int controlColor(LuaTable data, String property, int fallback) {
        int alpha = (int) Math.round(Math.max(0, Math.min(1,
                data.get("alpha").optdouble(1))) * 0.8 * 255);
        return alpha << 24 | luaColor(data.get(property), fallback);
    }

    private static int luaColor(LuaValue value, int fallback) {
        if (value == null || value.isnil()) return fallback;
        return value.isnumber() ? value.toint() & 0xFFFFFF
                : PsychColor.parse(value.optjstring("FFFFFF")) & 0xFFFFFF;
    }

    private Font widgetFont(LuaTable data, Font fallback) {
        String name = data.get("font").optjstring("").trim();
        if (name.isEmpty()) return fallback;
        Font selected = fontLoader.get(name,
                data.get("fontQuality").optdouble(LuaFontLoader.DEFAULT_OVERSAMPLE));
        return selected == null ? fallback : selected;
    }

    private void drawStyledText(GuiGraphics gui, Font font, LuaTable data,
                                int centerX, int centerY, int boxWidth, int boxHeight,
                                int color, float scale, boolean shadow) {
        String[] lines = data.get("text").optjstring("").split("\\n", -1);
        double lineSpacing = data.get("lineSpacing").optdouble(0);
        double letterSpacing = data.get("letterSpacing").optdouble(0);
        String alignment = normalizeAlignment(data.get("alignment").optjstring("center"));
        boolean italic = data.get("italic").optboolean(false);
        int border = Math.max(0, (int) Math.ceil(data.get("borderSize").optdouble(0)));
        int borderColor = (color >>> 24) << 24 | data.get("borderColor").optint(0) & 0xFFFFFF;
        String borderStyle = data.get("borderStyle").optjstring("outline");
        gui.pose().pushPose();
        try {
            gui.pose().translate(centerX, centerY, 0);
            gui.pose().scale(scale, scale, 1);
            double step = Math.max(1, font.lineHeight + lineSpacing);
            double totalHeight = font.lineHeight + Math.max(0, lines.length - 1) * step;
            double localWidth = boxWidth / Math.max(0.1, scale);
            int y = (int) Math.round(-totalHeight / 2.0);
            for (String line : lines) {
                double measured = textWidth(font, line, letterSpacing, italic);
                int x = switch (alignment) {
                    case "left" -> (int) Math.round(-localWidth / 2.0);
                    case "right" -> (int) Math.round(localWidth / 2.0 - measured);
                    default -> (int) Math.round(-measured / 2.0);
                };
                drawStyledLine(gui, font, line, x, y, color, shadow, italic,
                        letterSpacing, border, borderColor, borderStyle);
                y += (int) Math.round(step);
            }
        } finally {
            gui.pose().popPose();
        }
    }

    private static void drawStyledLine(GuiGraphics gui, Font font, String text, int x, int y,
                                       int color, boolean shadow, boolean italic, double spacing,
                                       int border, int borderColor, String borderStyle) {
        if (border > 0) {
            if (borderStyle.equalsIgnoreCase("shadow")) {
                drawSpacedLine(gui, font, text, x + border, y + border, borderColor, false, italic, spacing);
            } else if (!borderStyle.equalsIgnoreCase("none")) {
                for (int ox = -border; ox <= border; ox++) for (int oy = -border; oy <= border; oy++) {
                    if (ox == 0 && oy == 0 || ox * ox + oy * oy > border * border + 1) continue;
                    drawSpacedLine(gui, font, text, x + ox, y + oy, borderColor, false, italic, spacing);
                }
            }
        }
        drawSpacedLine(gui, font, text, x, y, color, shadow && border == 0, italic, spacing);
    }

    private static void drawSpacedLine(GuiGraphics gui, Font font, String text, double x, int y,
                                       int color, boolean shadow, boolean italic, double spacing) {
        if (spacing == 0) {
            Component component = italic ? Component.literal(text).withStyle(ChatFormatting.ITALIC)
                    : Component.literal(text);
            gui.drawString(font, component, (int) Math.round(x), y, color, shadow);
            return;
        }
        for (int point : text.codePoints().toArray()) {
            String glyph = new String(Character.toChars(point));
            Component component = italic ? Component.literal(glyph).withStyle(ChatFormatting.ITALIC)
                    : Component.literal(glyph);
            gui.drawString(font, component, (int) Math.round(x), y, color, shadow);
            x += font.width(component) + spacing;
        }
    }

    private static double textWidth(Font font, String text, double spacing, boolean italic) {
        if (spacing == 0) return font.width(italic
                ? Component.literal(text).withStyle(ChatFormatting.ITALIC) : Component.literal(text));
        int[] points = text.codePoints().toArray();
        double width = points.length == 0 ? 0 : -spacing;
        for (int point : points) {
            Component glyph = italic ? Component.literal(new String(Character.toChars(point))).withStyle(ChatFormatting.ITALIC)
                    : Component.literal(new String(Character.toChars(point)));
            width += font.width(glyph) + spacing;
        }
        return width;
    }

    private static boolean isTextWidget(Widget widget) {
        return widget.kind().equals("label") || widget.kind().equals("button")
                || widget.kind().equals("toggle") || widget.kind().equals("slider");
    }

    private int effectiveWidth(Widget widget) {
        double configured = widget.data().get("width").optdouble(1);
        if (!widget.kind().equals("label") || configured > 1) return Math.max(1, (int) Math.round(configured));
        Font font = widgetFont(widget.data(), Minecraft.getInstance().font);
        double widest = 1;
        for (String line : widget.data().get("text").optjstring("").split("\\n", -1))
            widest = Math.max(widest, textWidth(font, line, widget.data().get("letterSpacing").optdouble(0),
                    widget.data().get("italic").optboolean(false)));
        return Math.max(1, (int) Math.ceil(widest * widget.data().get("fontScale").optdouble(1)
                + widget.data().get("borderSize").optdouble(0) * 2 + 4));
    }

    private int effectiveHeight(Widget widget) {
        double configured = widget.data().get("height").optdouble(1);
        if (!widget.kind().equals("label") || configured > 10) return Math.max(1, (int) Math.round(configured));
        int lines = widget.data().get("text").optjstring("").split("\\n", -1).length;
        double lineHeight = Minecraft.getInstance().font.lineHeight
                + widget.data().get("lineSpacing").optdouble(0);
        return Math.max(1, (int) Math.ceil((Minecraft.getInstance().font.lineHeight
                + Math.max(0, lines - 1) * lineHeight) * widget.data().get("fontScale").optdouble(1)
                + widget.data().get("borderSize").optdouble(0) * 2 + 4));
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
        globals.set("soundExists", function(args -> LuaValue.valueOf(soundPlayer != null
                && soundPlayer.exists(args.arg(1).optjstring("")))));
        globals.set("getSoundVolume", function(args -> LuaValue.valueOf(soundPlayer == null ? 0
                : soundPlayer.volume(args.arg(1).optjstring("")))));
        globals.set("setSoundPitch", function(args -> {
            if (soundPlayer != null) soundPlayer.setPitch(args.arg(1).optjstring(""),
                    (float) args.arg(2).optdouble(1));
            return LuaValue.NIL;
        }));
        globals.set("getSoundPitch", function(args -> LuaValue.valueOf(soundPlayer == null ? 1
                : soundPlayer.pitch(args.arg(1).optjstring("")))));
        globals.set("setSoundTime", function(args -> {
            if (soundPlayer != null) soundPlayer.setTimeMs(args.arg(1).optjstring(""),
                    (float) args.arg(2).optdouble(0));
            return LuaValue.NIL;
        }));
        globals.set("getSoundTime", function(args -> LuaValue.valueOf(soundPlayer == null ? 0
                : soundPlayer.timeMs(args.arg(1).optjstring("")))));
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
        scrollInput = new MenuScrollInput(globals, cursor, this::call);

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
                    && (isWorld(widget.data()) ? worldContains(widget.id(), screenCursorX, screenCursorY)
                    : contains(widget, cursorX, cursorY)));
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

    /** Psych-style physical keyboard polling for custom menu scripts. */
    private void installKeyboard() {
        globals.set("keyboardPressed", function(args -> LuaValue.valueOf(
                keyboardState(args.arg(1).optjstring(""), 0))));
        globals.set("keyboardJustPressed", function(args -> LuaValue.valueOf(
                keyboardState(args.arg(1).optjstring(""), 1))));
        globals.set("keyboardReleased", function(args -> LuaValue.valueOf(
                keyboardState(args.arg(1).optjstring(""), 2))));
    }

    /** state: 0=held, 1=pressed this update, 2=released this update. */
    private boolean keyboardState(String name, int state) {
        if (keyboardSuppressed) return false;
        for (int key : PsychLuaRuntime.physicalKeyCodes(name)) {
            if (key < 0) continue;
            queriedKeys.add(key);
            boolean down = rawKeyDown(key);
            if (state == 0 && down) return true;
            if (state == 1 && (justPressedKeys.contains(key)
                    || down && !previousKeys.getOrDefault(key, false))) return true;
            if (state == 2 && (justReleasedKeys.contains(key)
                    || !down && previousKeys.getOrDefault(key, false))) return true;
        }
        return false;
    }

    private static boolean rawKeyDown(int key) {
        if (key < 0) return false;
        long window = Minecraft.getInstance().getWindow().getWindow();
        return GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS;
    }

    /** Called by the owning Screen; repeats do not re-fire onKeyPress. */
    public void keyPressed(int key, int scanCode, int modifiers) {
        if (keyboardSuppressed || key < 0 || !eventDownKeys.add(key)) return;
        queriedKeys.add(key);
        justPressedKeys.add(key);
        justReleasedKeys.remove(key);
        callGlobal("onKeyPress", LuaValue.valueOf(keyName(key, scanCode)),
                LuaValue.valueOf(key), LuaValue.valueOf(modifiers));
    }

    public void keyReleased(int key, int scanCode, int modifiers) {
        if (keyboardSuppressed || key < 0) return;
        eventDownKeys.remove(key);
        queriedKeys.add(key);
        justReleasedKeys.add(key);
        justPressedKeys.remove(key);
        callGlobal("onKeyRelease", LuaValue.valueOf(keyName(key, scanCode)),
                LuaValue.valueOf(key), LuaValue.valueOf(modifiers));
    }

    public void setKeyboardSuppressed(boolean suppressed) {
        keyboardSuppressed = suppressed;
        if (suppressed) {
            justPressedKeys.clear();
            justReleasedKeys.clear();
        }
    }

    /** Avoid carrying wheel/physical-key edges across a child pause/settings screen. */
    public void suspendInput() {
        scrollInput.reset();
        justPressedKeys.clear();
        justReleasedKeys.clear();
        eventDownKeys.clear();
        previousKeys.clear();
    }

    /** Stable Psych-style name for callback arguments; printable keys use their glyph. */
    private static String keyName(int key, int scanCode) {
        String printable = GLFW.glfwGetKeyName(key, scanCode);
        if (printable != null && !printable.isBlank()) return printable.toUpperCase(Locale.ROOT);
        if (key >= GLFW.GLFW_KEY_F1 && key <= GLFW.GLFW_KEY_F25)
            return "F" + (key - GLFW.GLFW_KEY_F1 + 1);
        if (key >= GLFW.GLFW_KEY_KP_0 && key <= GLFW.GLFW_KEY_KP_9)
            return "NUMPAD" + (key - GLFW.GLFW_KEY_KP_0);
        return switch (key) {
            case GLFW.GLFW_KEY_SPACE -> "SPACE";
            case GLFW.GLFW_KEY_ENTER -> "ENTER";
            case GLFW.GLFW_KEY_ESCAPE -> "ESCAPE";
            case GLFW.GLFW_KEY_TAB -> "TAB";
            case GLFW.GLFW_KEY_BACKSPACE -> "BACKSPACE";
            case GLFW.GLFW_KEY_INSERT -> "INSERT";
            case GLFW.GLFW_KEY_DELETE -> "DELETE";
            case GLFW.GLFW_KEY_RIGHT -> "RIGHT";
            case GLFW.GLFW_KEY_LEFT -> "LEFT";
            case GLFW.GLFW_KEY_DOWN -> "DOWN";
            case GLFW.GLFW_KEY_UP -> "UP";
            case GLFW.GLFW_KEY_PAGE_UP -> "PAGEUP";
            case GLFW.GLFW_KEY_PAGE_DOWN -> "PAGEDOWN";
            case GLFW.GLFW_KEY_HOME -> "HOME";
            case GLFW.GLFW_KEY_END -> "END";
            case GLFW.GLFW_KEY_CAPS_LOCK -> "CAPS_LOCK";
            case GLFW.GLFW_KEY_SCROLL_LOCK -> "SCROLL_LOCK";
            case GLFW.GLFW_KEY_NUM_LOCK -> "NUM_LOCK";
            case GLFW.GLFW_KEY_PRINT_SCREEN -> "PRINT_SCREEN";
            case GLFW.GLFW_KEY_PAUSE -> "PAUSE";
            case GLFW.GLFW_KEY_LEFT_SHIFT -> "LEFT_SHIFT";
            case GLFW.GLFW_KEY_LEFT_CONTROL -> "LEFT_CONTROL";
            case GLFW.GLFW_KEY_LEFT_ALT -> "LEFT_ALT";
            case GLFW.GLFW_KEY_LEFT_SUPER -> "LEFT_SUPER";
            case GLFW.GLFW_KEY_RIGHT_SHIFT -> "RIGHT_SHIFT";
            case GLFW.GLFW_KEY_RIGHT_CONTROL -> "RIGHT_CONTROL";
            case GLFW.GLFW_KEY_RIGHT_ALT -> "RIGHT_ALT";
            case GLFW.GLFW_KEY_RIGHT_SUPER -> "RIGHT_SUPER";
            case GLFW.GLFW_KEY_MENU -> "MENU";
            case GLFW.GLFW_KEY_KP_DECIMAL -> "NUMPADDECIMAL";
            case GLFW.GLFW_KEY_KP_DIVIDE -> "NUMPADDIVIDE";
            case GLFW.GLFW_KEY_KP_MULTIPLY -> "NUMPADMULTIPLY";
            case GLFW.GLFW_KEY_KP_SUBTRACT -> "NUMPADMINUS";
            case GLFW.GLFW_KEY_KP_ADD -> "NUMPADPLUS";
            case GLFW.GLFW_KEY_KP_ENTER -> "NUMPADENTER";
            case GLFW.GLFW_KEY_KP_EQUAL -> "NUMPADEQUAL";
            default -> "KEY_" + key;
        };
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
                    && (isWorld(widget.data()) ? worldContains(widget.id(), screenCursorX, screenCursorY)
                    : contains(widget, cursorX, cursorY));
            widget.data().set("hovered", LuaValue.valueOf(now));
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
            if (isWorld(widget.data())) {
                if (worldContains(widget.id(), screenCursorX, screenCursorY)) return widget.id();
            } else if (contains(widget, mouseX, mouseY)) return widget.id();
        }
        return "";
    }

    /** Whether the given canvas point is inside a widget's center-anchored bounds. */
    private boolean contains(Widget widget, double mouseX, double mouseY) {
        LuaTable data = widget.data();
        if(LuaLayerEffects.hidden(data)) return false;
        int width = effectiveWidth(widget);
        int height = effectiveHeight(widget);
        if(layered(widget)) {
            var m=menuTransform(widget,new java.util.HashSet<>());
            if(Math.abs(m.determinant())<.000001) return false;
            var p=m.invert().transformPosition(new org.joml.Vector3f((float)mouseX,(float)mouseY,0));
            double u=p.x/width+.5,v=p.y/height+.5;
            return u>=0 && u<1 && v>=0 && v<1 && (!data.get("maskHitTest").optboolean(false)||layerRenderer.hit(widget.id(),u,v));
        }
        int x = coordinate(data.get("x").optdouble(0.5), PsychCanvas.WIDTH) - width / 2;
        int y = coordinate(data.get("y").optdouble(0.5), PsychCanvas.HEIGHT) - height / 2;
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private boolean worldContains(String id, double mouseX, double mouseY) {
        Widget w=widget(id);if(w==null||LuaLayerEffects.hidden(w.data())) return false;
        var inverse=worldLayerPicking.get(id);
        if(inverse!=null) {
            var p=inverse.transform(new org.joml.Vector3f((float)mouseX,(float)mouseY,1));
            if(Math.abs(p.z)<.000001) return false;
            double u=p.x/p.z,v=p.y/p.z;
            return u>=0&&u<1&&v>=0&&v<1&&(!w.data().get("maskHitTest").optboolean(false)||layerRenderer.hit(id,u,v));
        }
        double[] bounds = worldHitboxes.get(id);
        return bounds != null && mouseX >= bounds[0] && mouseX < bounds[2]
                && mouseY >= bounds[1] && mouseY < bounds[3];
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

    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        return dispatchScroll(mouseX, mouseY, screenToCanvasX(mouseX), screenToCanvasY(mouseY), dx, dy);
    }

    public boolean mouseScrolledInViewport(double mouseX, double mouseY, double dx, double dy,
                                           int vx, int vy, int vw, int vh) {
        double scale = viewportScale(vw, vh);
        return dispatchScroll(mouseX, mouseY, (mouseX - viewportLeft(vx, vw, scale)) / scale,
                (mouseY - viewportTop(vy, vh, scale)) / scale, dx, dy);
    }

    private boolean dispatchScroll(double mouseX, double mouseY, double canvasX, double canvasY,
                                   double dx, double dy) {
        cursorX = canvasX; cursorY = canvasY;
        screenCursorX = mouseX; screenCursorY = mouseY;
        updateCursor();
        LuaTable target = null;
        List<Widget> ordered = orderedWidgets();
        for (int i = ordered.size() - 1; i >= 0; i--) {
            Widget widget = ordered.get(i);
            LuaTable data = widget.data();
            if (!data.get("visible").optboolean(true) || LuaLayerEffects.hidden(data)
                    || !data.get("onScroll").isfunction()) continue;
            if (isWorld(data) ? worldContains(widget.id(), mouseX, mouseY)
                    : contains(widget, canvasX, canvasY)) {
                target = data;
                break;
            }
        }
        return scrollInput.scroll(dx, dy, target);
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
            boolean hit = isWorld(data) ? worldContains(widget.id(), mouseX, mouseY)
                    : contains(widget, canvasMouseX, canvasMouseY);
            if (!data.get("visible").optboolean(true) || !hit
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
                    double ratio;
                    if (isWorld(data)) {
                        double[] bounds = worldHitboxes.get(widget.id());
                        ratio = bounds == null || bounds[2] <= bounds[0] ? 0
                                : (mouseX - bounds[0]) / (bounds[2] - bounds[0]);
                    } else ratio = (canvasMouseX - x) / width;
                    data.set("value", min + (max - min) * Math.max(0, Math.min(1, ratio)));
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
            boolean hit = isWorld(data) ? worldContains(widget.id(), mouseX, mouseY)
                    : contains(widget, canvasMouseX, canvasMouseY);
            if (!data.get("visible").optboolean(true) || !hit
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
        layerRenderer.close();
        if (opened) callGlobal("onClose");
        tweens.clear();
        timers.clear();
        fontLoader.close();
        if (soundPlayer != null) {
            stopSharedAudio();
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
