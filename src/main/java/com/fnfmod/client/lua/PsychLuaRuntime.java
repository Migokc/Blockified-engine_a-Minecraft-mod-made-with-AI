package com.fnfmod.client.lua;

import com.fnfmod.FnfMod;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.fnfmod.chart.SongChart;
import com.fnfmod.client.ClientOptions;
import com.fnfmod.client.ClientSession;
import com.fnfmod.client.FnfKeys;
import com.fnfmod.client.camera.GameplayCamera;
import com.fnfmod.client.gui.GameplayScreen;
import com.fnfmod.client.render.LuaWorldSpriteRenderer;
import com.fnfmod.client.render.SparrowAtlas;
import com.fnfmod.song.SongEntry;
import com.fnfmod.song.SongLibrary;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;

/** Sandboxed Psych Engine 1.0.x Lua compatibility runtime for gameplay. */
public final class PsychLuaRuntime implements AutoCloseable {
    /** Psych Engine's logical game canvas, independent of Minecraft GUI scale. */
    public static final int VIRTUAL_WIDTH = 1280;
    public static final int VIRTUAL_HEIGHT = 720;
    /** Keep camera groups separated from native notes/HUD regardless of Psych object order values. */
    private static final float GAME_OBJECT_Z = -200f;
    private static final float HUD_OBJECT_Z = 200f;
    private static final float OBJECT_ORDER_Z_STEP = 0.001f;
    private static final AtomicInteger NEXT_TEXTURE = new AtomicInteger();
    public static final int FUNCTION_CONTINUE = 0;
    public static final int FUNCTION_STOP = 1;
    public static final int FUNCTION_STOP_LUA = 2;
    public static final int FUNCTION_STOP_HSCRIPT = 3;
    public static final int FUNCTION_STOP_ALL = 4;

    private record Script(Path path, Globals globals) {}

    private static final class LuaAnimation {
        String name;
        List<SparrowAtlas.Frame> frames = List.of();
        double frameRate = 24;
        boolean looped = true;
    }

    private static final class LuaObject {
        String tag;
        String image = "";
        String text = "";
        String fontName = "";
        String camera = "game";
        double x, y, z, width = 100, height = 100, graphicWidth = 100, graphicHeight = 100;
        double scaleX = 1, scaleY = 1;
        double alpha = 1, angle;
        int color = 0xFFFFFFFF;
        int textSize = 16;
        boolean visible = true, added, textObject, sizeExplicit;
        /** World-camera behavior. Billboard matches vanilla name tags; lighting matches world entities. */
        boolean worldBillboard = true, worldLighting = true;
        int order;
        ResourceLocation texture;
        DynamicTexture dynamicTexture;
        int textureWidth, textureHeight;
        SparrowAtlas atlas;
        final Map<String, LuaAnimation> animations = new LinkedHashMap<>();
        final Map<String, double[]> animationOffsets = new LinkedHashMap<>();
        String currentAnimation;
        int animationFrame;
        double animationElapsed;
        boolean animationReverse, animationFinished;
    }

    private record Timer(String tag, long intervalMs, int totalLoops, long nextAt, int completed) {
        Timer advance(long next) { return new Timer(tag, intervalMs, totalLoops, next, completed + 1); }
    }

    private record Tween(String tag, String path, double from, double to, long start,
                         long durationMs, String ease) {}

    private final GameplayScreen host;
    private final SongChart chart;
    private final String songId;
    private final Path songFolder;
    private final Path modRoot;
    private final EnumSet<SongLibrary.ExternalContent> externalContent;
    private final List<Script> scripts = new ArrayList<>();
    private final Map<String, LuaObject> objects = new LinkedHashMap<>();
    private final Map<String, LuaValue> sharedVars = new HashMap<>();
    private final Map<String, Timer> timers = new LinkedHashMap<>();
    private final Map<String, Tween> tweens = new LinkedHashMap<>();
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
    private boolean songStarted;
    private boolean closed;

    public static PsychLuaRuntime load(GameplayScreen host, SongChart chart) {
        return load(host, chart, null, null, null);
    }

    public static PsychLuaRuntime load(GameplayScreen host, SongChart chart, String requestedId,
                                       Path requestedFolder, SongEntry requestedEntry) {
        String id = requestedId == null || requestedId.isBlank()
                ? (ClientSession.songId == null || ClientSession.songId.isBlank()
                ? chart.title : ClientSession.songId) : requestedId;
        SongEntry entry = requestedEntry != null ? requestedEntry : SongLibrary.get(id);
        Path folder = requestedFolder != null ? requestedFolder
                : ClientSession.resolvedFolder != null ? ClientSession.resolvedFolder
                : entry == null ? null : entry.folder;
        Path root = entry == null ? folder : entry.modRoot;
        PsychLuaRuntime runtime = new PsychLuaRuntime(host, chart, id, folder, root, entry);
        runtime.applyNoteTypeConfigs();
        runtime.loadScripts(entry);
        return runtime;
    }

    private PsychLuaRuntime(GameplayScreen host, SongChart chart, String songId, Path songFolder, Path modRoot,
                            SongEntry entry) {
        this.host = host;
        this.chart = chart;
        this.songId = songId == null ? "unknown" : songId;
        this.songFolder = normalize(songFolder);
        this.modRoot = normalize(modRoot);
        this.externalContent = entry == null || entry.externalContent == null
                ? SongLibrary.allExternalContent() : EnumSet.copyOf(entry.externalContent);
        this.fontLoader = new LuaFontLoader(this.songFolder, this.modRoot, SongLibrary.fontsDir(),
                allows(SongLibrary.ExternalContent.FONTS));
    }

    private boolean allows(SongLibrary.ExternalContent content) {
        return externalContent.contains(content);
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
        // User-global scripts run for every song, independent of source-engine layout.
        addLuaFiles(SongLibrary.scriptsDir(), files);
        if (modRoot != null && allows(SongLibrary.ExternalContent.LUA)) {
            addLuaFiles(modRoot.resolve("scripts"), files);
            addLuaFile(modRoot.resolve("stages").resolve(chart.stage + ".lua"), files);
            for (String type : chart.notes.stream().map(n -> n.noteType).filter(s -> s != null && !s.isBlank()).distinct().toList()) {
                addLuaFile(modRoot.resolve("custom_notetypes").resolve(type + ".lua"), files);
            }
            for (String type : chart.events.stream().map(e -> e.name).filter(s -> s != null && !s.isBlank()).distinct().toList()) {
                addLuaFile(modRoot.resolve("custom_events").resolve(type + ".lua"), files);
            }
            addLuaFiles(modRoot.resolve("data").resolve(songId), files);
        }
        if (allows(SongLibrary.ExternalContent.LUA)) {
            if (entry != null) addLuaFiles(entry.folder, files);
            addLuaFiles(songFolder, files);
        }

        for (Path file : files) loadOne(file);
        if (!scripts.isEmpty()) {
            call("onCreate");
            call("onCreatePost");
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

    private void loadOne(Path file) {
        try {
            Globals globals = JsePlatform.standardGlobals();
            sandbox(globals);
            installConstants(globals, file);
            installCallbacks(globals);
            globals.load(Files.readString(file), file.toString()).call();
            scripts.add(new Script(file, globals));
        } catch (Throwable error) {
            report(file.getFileName() + ": " + compactError(error));
        }
    }

    private static void sandbox(Globals globals) {
        globals.set("luajava", LuaValue.NIL);
        globals.set("io", LuaValue.NIL);
        globals.set("package", LuaValue.NIL);
        globals.set("require", LuaValue.NIL);
        globals.set("dofile", LuaValue.NIL);
        globals.set("loadfile", LuaValue.NIL);
        LuaValue os = globals.get("os");
        if (os.istable()) {
            os.set("execute", LuaValue.NIL);
            os.set("remove", LuaValue.NIL);
            os.set("rename", LuaValue.NIL);
            os.set("tmpname", LuaValue.NIL);
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
        g.set("botPlay", LuaValue.FALSE);
        g.set("practice", LuaValue.FALSE);
        g.set("isStoryMode", LuaValue.FALSE);
        g.set("buildTarget", "windows");
        g.set("boyfriendName", chart.player1);
        g.set("dadName", chart.player2);
        g.set("gfName", "gf");
        for (int i = 0; i < 4; i++) {
            g.set("defaultPlayerStrumX" + i, host.psychLuaStrumX(true, i));
            g.set("defaultPlayerStrumY" + i, host.psychLuaStrumY(true, i));
            g.set("defaultOpponentStrumX" + i, host.psychLuaStrumX(false, i));
            g.set("defaultOpponentStrumY" + i, host.psychLuaStrumY(false, i));
        }
    }

    private void installCallbacks(Globals g) {
        fn(g, "debugPrint", args -> { report(args.optjstring(1, "")); return LuaValue.NIL; });
        fn(g, "getSongPosition", args -> LuaValue.valueOf(host.psychLuaSongPosition()));
        fn(g, "getHealth", args -> LuaValue.valueOf(host.psychLuaHealth()));
        fn(g, "setHealth", args -> { host.psychLuaSetHealth(args.optdouble(1, 1)); return LuaValue.NIL; });
        fn(g, "addHealth", args -> { host.psychLuaSetHealth(host.psychLuaHealth() + args.optdouble(1, 0)); return LuaValue.NIL; });
        fn(g, "addScore", args -> { host.psychLuaAddScore(args.optint(1, 0)); return LuaValue.NIL; });
        fn(g, "setScore", args -> { host.psychLuaSetScore(args.optint(1, 0)); return LuaValue.NIL; });
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
                args.optjstring(2, ""), args.optjstring(3, "")); return LuaValue.NIL; });
        fn(g, "cameraSetTarget", args -> { host.psychLuaCameraTarget(args.optjstring(1, "")); return LuaValue.TRUE; });
        fn(g, "endSong", args -> { host.psychLuaEndSong(); return LuaValue.TRUE; });
        fn(g, "restartSong", args -> { host.psychLuaRestartSong(); return LuaValue.TRUE; });
        fn(g, "exitSong", args -> { host.psychLuaExitSong(); return LuaValue.TRUE; });
        fn(g, "keyboardPressed", args -> LuaValue.valueOf(keyDown(keyCode(args.optjstring(1, "")))));
        fn(g, "keyboardJustPressed", args -> { int key = keyCode(args.optjstring(1, ""));
                return LuaValue.valueOf(keyDown(key) && !previousKeys.getOrDefault(key, false)); });
        fn(g, "keyboardReleased", args -> { int key = keyCode(args.optjstring(1, ""));
                return LuaValue.valueOf(!keyDown(key) && previousKeys.getOrDefault(key, false)); });
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

        fn(g, "makeLuaSprite", args -> { makeObject(args, false, false); return LuaValue.NIL; });
        fn(g, "makeAnimatedLuaSprite", args -> { makeObject(args, false, true); return LuaValue.NIL; });
        fn(g, "makeLuaText", args -> { makeObject(args, true, false); return LuaValue.NIL; });
        fn(g, "makeGraphic", args -> { LuaObject o = object(args.checkjstring(1)); disposeGraphic(o);
                o.width = args.optint(2, 256); o.height = args.optint(3, 256);
                o.graphicWidth = o.width; o.graphicHeight = o.height; o.sizeExplicit = true;
                o.color = color(args.optjstring(4, "FFFFFF")); return LuaValue.NIL; });
        fn(g, "addLuaSprite", args -> { object(args.checkjstring(1)).added = true; return LuaValue.NIL; });
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
        fn(g, "setTextColor", args -> { object(args.checkjstring(1)).color = color(args.optjstring(2, "FFFFFF")); return LuaValue.NIL; });
        fn(g, "setObjectCamera", args -> { object(args.checkjstring(1)).camera = args.optjstring(2, "game"); return LuaValue.NIL; });
        fn(g, "setWorldSpriteBillboard", args -> { object(args.checkjstring(1)).worldBillboard = args.optboolean(2, true); return LuaValue.NIL; });
        fn(g, "setWorldSpriteLighting", args -> { object(args.checkjstring(1)).worldLighting = args.optboolean(2, true); return LuaValue.NIL; });
        fn(g, "setWorldSpriteShadows", args -> { object(args.checkjstring(1)).worldLighting = args.optboolean(2, true); return LuaValue.NIL; });
        fn(g, "setObjectOrder", args -> { object(args.checkjstring(1)).order = args.optint(2, 0); return LuaValue.NIL; });
        fn(g, "getObjectOrder", args -> LuaValue.valueOf(object(args.checkjstring(1)).order));
        fn(g, "setGraphicSize", args -> { setGraphicSize(object(args.checkjstring(1)),
                args.optdouble(2, 0), args.arg(3).isnil() ? 0 : args.optdouble(3, 0)); return LuaValue.NIL; });
        fn(g, "scaleObject", args -> { LuaObject o = object(args.checkjstring(1)); o.scaleX = args.optdouble(2, 1);
                o.scaleY = args.optdouble(3, 1); return LuaValue.NIL; });
        fn(g, "loadGraphic", args -> { LuaObject o = object(args.checkjstring(1));
                o.image = args.optjstring(2, ""); o.sizeExplicit = false; loadObjectImage(o, o.image); return LuaValue.NIL; });
        fn(g, "loadFrames", args -> LuaValue.valueOf(loadObjectAtlas(object(args.checkjstring(1)),
                args.optjstring(2, ""), args.optjstring(3, "auto"))));
        fn(g, "addAnimationByPrefix", args -> LuaValue.valueOf(addAnimationByPrefix(args)));
        fn(g, "luaSpriteAddAnimationByPrefix", args -> LuaValue.valueOf(addAnimationByPrefix(args)));
        fn(g, "addAnimationByIndices", args -> LuaValue.valueOf(addAnimationByIndices(args, true)));
        fn(g, "luaSpriteAddAnimationByIndices", args -> LuaValue.valueOf(addAnimationByIndices(args, true)));
        fn(g, "addAnimation", args -> LuaValue.valueOf(addAnimationByIndices(args, false)));
        fn(g, "playAnim", args -> LuaValue.valueOf(playAnimation(args)));
        fn(g, "objectPlayAnimation", args -> LuaValue.valueOf(playAnimation(args)));
        fn(g, "luaSpritePlayAnimation", args -> LuaValue.valueOf(playAnimation(args)));
        fn(g, "addOffset", args -> { addAnimationOffset(args); return LuaValue.NIL; });
        fn(g, "screenCenter", args -> { LuaObject o = object(args.checkjstring(1)); String axes = args.optjstring(2, "xy");
                if (axes.contains("x")) o.x = (host.psychLuaScreenWidth() - o.width * o.scaleX) / 2; if (axes.contains("y")) o.y = (host.psychLuaScreenHeight() - o.height * o.scaleY) / 2; return LuaValue.NIL; });
        fn(g, "getMidpointX", args -> LuaValue.valueOf(object(args.checkjstring(1)).x + object(args.checkjstring(1)).width / 2));
        fn(g, "getMidpointY", args -> LuaValue.valueOf(object(args.checkjstring(1)).y + object(args.checkjstring(1)).height / 2));
        fn(g, "getGraphicMidpointX", args -> LuaValue.valueOf(object(args.checkjstring(1)).x + object(args.checkjstring(1)).width / 2));
        fn(g, "getGraphicMidpointY", args -> LuaValue.valueOf(object(args.checkjstring(1)).y + object(args.checkjstring(1)).height / 2));
        fn(g, "getScreenPositionX", args -> LuaValue.valueOf(object(args.checkjstring(1)).x));
        fn(g, "getScreenPositionY", args -> LuaValue.valueOf(object(args.checkjstring(1)).y));
        fn(g, "objectsOverlap", args -> LuaValue.valueOf(overlap(object(args.checkjstring(1)), object(args.checkjstring(2)))));
        fn(g, "getMouseX", args -> LuaValue.ZERO); fn(g, "getMouseY", args -> LuaValue.ZERO);

        fn(g, "runTimer", args -> { runTimer(args.optjstring(1, ""), args.optdouble(2, 1), args.optint(3, 1)); return LuaValue.NIL; });
        fn(g, "cancelTimer", args -> { timers.remove(args.optjstring(1, "")); return LuaValue.NIL; });
        fn(g, "cancelTween", args -> { tweens.remove(args.optjstring(1, "")); return LuaValue.NIL; });
        tweenFn(g, "doTweenX", "x"); tweenFn(g, "doTweenY", "y"); tweenFn(g, "doTweenZ", "z");
        tweenFn(g, "doTweenAngle", "angle"); tweenFn(g, "doTweenAlpha", "alpha");
        fn(g, "doTweenZoom", args -> { String tag = args.checkjstring(1); double value = args.optdouble(3, 1);
                double duration = args.optdouble(4, 1); String ease = args.optjstring(5, "linear");
                startTween(tag, "camGame.zoom", number(getProperty("camGame.zoom"), 1), value, duration, ease); return LuaValue.NIL; });
        noteTweenFn(g, "noteTweenX", "x"); noteTweenFn(g, "noteTweenY", "y");
        noteTweenFn(g, "noteTweenAngle", "angle"); noteTweenFn(g, "noteTweenAlpha", "alpha");

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

        registerNoOps(g, "setScrollFactor", "updateHitbox", "setBlendMode", "precacheImage",
                "precacheSound", "precacheMusic", "addCharacterToList", "characterDance", "playSound",
                "playMusic", "stopSound", "pauseSound", "resumeSound", "soundFadeIn", "soundFadeOut",
                "soundFadeCancel", "cameraShake", "cameraFlash", "cameraFade", "setHealthBarColors",
                "setTimeBarColors", "setTextBorder", "setTextAlignment", "setTextItalic",
                "setTextHeight", "setTextAutoSize",
                "loadMultipleFrames", "makeFlxAnimateSprite", "loadAnimateAtlas", "addAnimationBySymbol",
                "addAnimationBySymbolIndices", "initLuaShader", "setSpriteShader", "removeSpriteShader",
                "setShaderBool", "setShaderInt", "setShaderFloat", "setShaderBoolArray", "setShaderIntArray",
                "setShaderFloatArray", "setShaderSampler2D", "openCustomSubstate", "closeCustomSubstate",
                "insertToCustomSubstate", "startDialogue", "startVideo", "addHScript", "removeHScript",
                "runHaxeCode", "runHaxeFunction", "addHaxeLibrary", "updateScoreText", "startCountdown",
                "addLuaScript", "removeLuaScript", "close", "getModSetting", "getPropertyFromClass",
                "setPropertyFromClass", "callMethod", "callMethodFromClass", "createInstance", "addInstance",
                "instanceArg", "addToGroup", "removeFromGroup", "deleteFile", "initSaveData", "flushSaveData",
                "getDataFromSave", "setDataFromSave", "eraseSaveData", "getPixelColor", "setCharacterX",
                "setCharacterY", "getCharacterX", "getCharacterY", "characterPlayAnim",
                "luaSpriteMakeGraphic", "scaleLuaSprite", "setLuaSpriteCamera", "setLuaSpriteScrollFactor",
                "getPropertyLuaSprite", "setPropertyLuaSprite", "addHits", "setHits", "setRatingFC",
                "setRatingName", "setRatingPercent", "loadSong", "musicFadeIn", "musicFadeOut",
                "getSoundPitch", "getSoundTime", "getSoundVolume", "setSoundPitch", "setSoundTime",
                "setSoundVolume", "luaSoundExists", "gamepadAnalogX",
                "gamepadAnalogY", "gamepadJustPressed", "gamepadPressed", "gamepadReleased",
                "addCameraFollowPoint", "setCameraFollowPoint",
                "getCameraFollowX", "getCameraFollowY", "addCameraScroll", "setCameraScroll",
                "getCameraScrollX", "getCameraScrollY", "doTweenColor", "startTween", "noteTweenDirection",
                "updateHitboxFromGroup", "callOnHScript", "setOnHScript");
    }

    private void fn(Globals globals, String name, Function<Varargs, LuaValue> function) {
        globals.set(name, new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                try { return function.apply(args); }
                catch (Throwable error) { warnOnce(name + ": " + compactError(error)); return LuaValue.NIL; }
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
        } else {
            object.image = args.optjstring(2, "");
            object.x = args.optdouble(3, 0);
            object.y = args.optdouble(4, 0);
            if (animated) loadObjectAtlas(object, object.image, args.optjstring(5, "auto"));
            else loadObjectImage(object, object.image);
        }
    }

    private void loadObjectImage(LuaObject object, String imageName) {
        Path png = resolveImage(imageName);
        if (png == null) return;
        try (var input = Files.newInputStream(png)) {
            NativeImage image = NativeImage.read(input);
            DynamicTexture texture = new DynamicTexture(image);
            ResourceLocation id = FnfMod.id("psych_lua/" + NEXT_TEXTURE.incrementAndGet());
            Minecraft.getInstance().getTextureManager().register(id, texture);
            disposeGraphic(object);
            object.dynamicTexture = texture;
            object.texture = id;
            object.textureWidth = image.getWidth();
            object.textureHeight = image.getHeight();
            object.graphicWidth = image.getWidth();
            object.graphicHeight = image.getHeight();
            if (!object.sizeExplicit) {
                object.width = image.getWidth();
                object.height = image.getHeight();
            }
        } catch (Exception error) {
            warnOnce("image " + imageName + ": " + compactError(error));
        }
    }

    private boolean loadObjectAtlas(LuaObject object, String imageName, String spriteType) {
        object.image = imageName == null ? "" : imageName;
        if (object.image.isBlank()) return false;
        String type = spriteType == null ? "auto" : spriteType.toLowerCase(Locale.ROOT).replace("_", "");
        if (!(type.equals("auto") || type.equals("sparrow") || type.equals("sparrowatlas")
                || type.equals("sparrowv2"))) {
            warnOnce("animated sprite type '" + spriteType + "' is not supported; use Sparrow XML");
            return false;
        }
        Path png = resolveImage(imageName);
        if (png == null) {
            warnOnce("animated image not found: " + imageName);
            return false;
        }
        String filename = png.getFileName().toString();
        int dot = filename.lastIndexOf('.');
        Path xml = png.resolveSibling((dot < 0 ? filename : filename.substring(0, dot)) + ".xml");
        SparrowAtlas atlas = SparrowAtlas.load(png, xml);
        if (atlas == null || atlas.allFrames().isEmpty()) {
            if (atlas != null) atlas.close();
            warnOnce("Sparrow XML missing or empty for " + imageName + " (expected " + xml.getFileName() + ")");
            loadObjectImage(object, imageName);
            return false;
        }
        disposeGraphic(object);
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
        List<SparrowAtlas.Frame> frames = object.atlas.framesByPrefix(args.checkjstring(3));
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
        List<SparrowAtlas.Frame> source = withPrefix
                ? object.atlas.framesByPrefix(args.checkjstring(3)) : object.atlas.allFrames();
        List<Integer> indices = luaIndices(args.arg(indicesArg));
        List<SparrowAtlas.Frame> frames = new ArrayList<>();
        for (int index : indices) if (index >= 0 && index < source.size()) frames.add(source.get(index));
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
        LuaAnimation animation = new LuaAnimation();
        animation.name = name;
        animation.frames = List.copyOf(frames);
        animation.frameRate = Math.max(0, frameRate);
        animation.looped = looped;
        object.animations.put(name, animation);
        if (object.currentAnimation == null) startAnimation(object, name, true, false, 0);
    }

    private boolean playAnimation(Varargs args) {
        LuaObject object = objects.get(args.checkjstring(1));
        if (object == null) return false;
        return startAnimation(object, args.checkjstring(2), luaBoolean(args, 3, false),
                luaBoolean(args, 4, false), args.optint(5, 0));
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
    }

    private void disposeGraphic(LuaObject object) {
        if (object.dynamicTexture != null) {
            object.dynamicTexture.close();
            object.dynamicTexture = null;
        }
        if (object.atlas != null) {
            object.atlas.close();
            object.atlas = null;
        }
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
            return fromLua(function.invoke(LuaValue.varargsOf(values)).arg1());
        } catch (Throwable error) {
            warnOnce(script.path.getFileName() + " " + functionName + ": " + compactError(error));
            return FUNCTION_CONTINUE;
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

    private static String controlName(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    private boolean controlDown(String raw) {
        String control = controlName(raw);
        queriedControls.add(control);
        return switch (control) {
            case "NOTE_LEFT" -> FnfKeys.NOTE_LEFT.isDown();
            case "NOTE_DOWN" -> FnfKeys.NOTE_DOWN.isDown();
            case "NOTE_UP" -> FnfKeys.NOTE_UP.isDown();
            case "NOTE_RIGHT" -> FnfKeys.NOTE_RIGHT.isDown();
            case "UI_LEFT" -> keyDown(GLFW.GLFW_KEY_LEFT);
            case "UI_DOWN" -> keyDown(GLFW.GLFW_KEY_DOWN);
            case "UI_UP" -> keyDown(GLFW.GLFW_KEY_UP);
            case "UI_RIGHT" -> keyDown(GLFW.GLFW_KEY_RIGHT);
            case "ACCEPT" -> keyDown(GLFW.GLFW_KEY_ENTER);
            case "BACK" -> keyDown(GLFW.GLFW_KEY_ESCAPE);
            case "PAUSE" -> keyDown(GLFW.GLFW_KEY_ENTER) || keyDown(GLFW.GLFW_KEY_ESCAPE);
            case "RESET" -> keyDown(GLFW.GLFW_KEY_R);
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
            case "LEFT" -> GLFW.GLFW_KEY_LEFT; case "RIGHT" -> GLFW.GLFW_KEY_RIGHT;
            case "UP" -> GLFW.GLFW_KEY_UP; case "DOWN" -> GLFW.GLFW_KEY_DOWN;
            case "SPACE" -> GLFW.GLFW_KEY_SPACE; case "ENTER", "RETURN" -> GLFW.GLFW_KEY_ENTER;
            case "ESC", "ESCAPE" -> GLFW.GLFW_KEY_ESCAPE; case "TAB" -> GLFW.GLFW_KEY_TAB;
            case "SHIFT" -> GLFW.GLFW_KEY_LEFT_SHIFT; case "CONTROL", "CTRL" -> GLFW.GLFW_KEY_LEFT_CONTROL;
            case "ALT" -> GLFW.GLFW_KEY_LEFT_ALT; case "BACKSPACE" -> GLFW.GLFW_KEY_BACKSPACE;
            case "DELETE" -> GLFW.GLFW_KEY_DELETE; case "HOME" -> GLFW.GLFW_KEY_HOME;
            case "END" -> GLFW.GLFW_KEY_END; case "PAGEUP", "PAGE_UP" -> GLFW.GLFW_KEY_PAGE_UP;
            case "PAGEDOWN", "PAGE_DOWN" -> GLFW.GLFW_KEY_PAGE_DOWN;
            case "F1" -> GLFW.GLFW_KEY_F1; case "F2" -> GLFW.GLFW_KEY_F2; case "F3" -> GLFW.GLFW_KEY_F3;
            case "F4" -> GLFW.GLFW_KEY_F4; case "F5" -> GLFW.GLFW_KEY_F5; case "F6" -> GLFW.GLFW_KEY_F6;
            case "F7" -> GLFW.GLFW_KEY_F7; case "F8" -> GLFW.GLFW_KEY_F8; case "F9" -> GLFW.GLFW_KEY_F9;
            case "F10" -> GLFW.GLFW_KEY_F10; case "F11" -> GLFW.GLFW_KEY_F11; case "F12" -> GLFW.GLFW_KEY_F12;
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
        if (object != null) setObjectProperty(object, path.substring(dot + 1), value);
        else sharedVars.put(path, toLua(value));
    }

    private static Object objectProperty(LuaObject o, String property) {
        LuaAnimation animation = currentAnimation(o);
        double[] offset = currentAnimationOffset(o);
        return switch (property) {
            case "x" -> o.x; case "y" -> o.y; case "z" -> o.z;
            case "width" -> o.width; case "height" -> o.height;
            case "alpha" -> o.alpha; case "angle" -> o.angle; case "visible" -> o.visible;
            case "color" -> o.color; case "text" -> o.text; case "scale.x" -> o.scaleX;
            case "scale.y" -> o.scaleY; case "offset.x" -> offset[0]; case "offset.y" -> offset[1];
            case "billboard", "worldBillboard", "alwaysFaceCamera" -> o.worldBillboard;
            case "lighting", "worldLighting", "shadows", "worldShadows", "affectedByLighting" -> o.worldLighting;
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
            case "visible" -> o.visible = bool(value); case "color" -> o.color = value instanceof Number n ? n.intValue() : color(String.valueOf(value));
            case "text" -> o.text = String.valueOf(value); case "scale.x" -> o.scaleX = number(value, o.scaleX);
            case "scale.y" -> o.scaleY = number(value, o.scaleY);
            case "billboard", "worldBillboard", "alwaysFaceCamera" -> o.worldBillboard = bool(value);
            case "lighting", "worldLighting", "shadows", "worldShadows", "affectedByLighting" -> o.worldLighting = bool(value);
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
            double from = number(getProperty(target + "." + property), 0); double to = args.optdouble(3, from);
            startTween(tag, target + "." + property, from, to, args.optdouble(4, 1), args.optjstring(5, "linear"));
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
        tweens.put(tag, new Tween(tag, path, from, to, System.currentTimeMillis(),
                Math.max(1, (long) (seconds * 1000)), ease == null ? "linear" : ease));
    }

    private void runTimer(String tag, double seconds, int loops) {
        long interval = Math.max(1, (long) (seconds * 1000));
        timers.put(tag, new Timer(tag, interval, Math.max(1, loops), System.currentTimeMillis() + interval, 0));
    }

    public void update(double elapsedSeconds) {
        if (closed) return;
        updateAnimations(elapsedSeconds);
        if (scripts.isEmpty()) return;
        syncGlobals();
        call("onUpdate", elapsedSeconds);
        updateTweensAndTimers();
        int step = (int) Math.floor(host.psychLuaBeat() * 4);
        int beat = (int) Math.floor(host.psychLuaBeat());
        int section = host.psychLuaSection();
        if (step != lastStep) { lastStep = step; setAll("curStep", step); call("onStepHit"); }
        if (beat != lastBeat) { lastBeat = beat; setAll("curBeat", beat); call("onBeatHit"); }
        if (section != lastSection) { lastSection = section; setAll("curSection", section); call("onSectionHit"); }
        if (!songStarted && host.psychLuaSongStarted()) { songStarted = true; call("onSongStart"); }
        call("onUpdatePost", elapsedSeconds);
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
        setAll("score", host.psychLuaScore());
        setAll("misses", host.psychLuaMisses());
        setAll("combo", host.psychLuaCombo());
        setAll("health", host.psychLuaHealth());
        setAll("mustHitSection", host.psychLuaMustHit());
        setAll("startedCountdown", true);
    }

    private void updateTweensAndTimers() {
        long now = System.currentTimeMillis();
        for (Tween tween : new ArrayList<>(tweens.values())) {
            double t = Math.min(1, (now - tween.start) / (double) tween.durationMs);
            double value = tween.from + (tween.to - tween.from) * ease(tween.ease, t);
            if (tween.path.startsWith("strumLineNotes.")) {
                String[] parts = tween.path.split("\\.");
                if (parts.length >= 3) host.psychLuaSetGroup("strumLineNotes", Integer.parseInt(parts[1]), parts[2], value);
            } else setProperty(tween.path, value);
            if (t >= 1) { tweens.remove(tween.tag); call("onTweenCompleted", tween.tag); }
        }
        for (Timer timer : new ArrayList<>(timers.values())) {
            if (now < timer.nextAt) continue;
            int completed = timer.completed + 1;
            int left = Math.max(0, timer.totalLoops - completed);
            call("onTimerCompleted", timer.tag, completed, left);
            if (left == 0) timers.remove(timer.tag);
            else timers.put(timer.tag, timer.advance(now + timer.intervalMs));
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

    public void reloadFonts() { fontLoader.close(); }

    private static boolean isStop(Object result) {
        if (!(result instanceof Number number)) return false;
        int value = number.intValue();
        return value == FUNCTION_STOP || value == FUNCTION_STOP_ALL;
    }

    public Object call(String name, Object... args) {
        Object result = FUNCTION_CONTINUE;
        for (Script script : new ArrayList<>(scripts)) {
            try {
                LuaValue function = script.globals.get(name);
                if (!function.isfunction()) continue;
                LuaValue[] values = new LuaValue[args.length];
                for (int i = 0; i < args.length; i++) values[i] = toLua(args[i]);
                LuaValue value = function.invoke(LuaValue.varargsOf(values)).arg1();
                if (!value.isnil()) result = fromLua(value);
            } catch (Throwable error) {
                warnOnce(script.path.getFileName() + " " + name + ": " + compactError(error));
            }
        }
        return result;
    }

    private void setAll(String name, Object value) {
        LuaValue lua = toLua(value);
        for (Script script : scripts) script.globals.set(name, lua);
    }

    public void render(GuiGraphics gui, boolean hud) {
        if (closed || objects.isEmpty()) return;
        float canvasScale = Math.min(gui.guiWidth() / (float) VIRTUAL_WIDTH,
                gui.guiHeight() / (float) VIRTUAL_HEIGHT);
        float canvasX = (gui.guiWidth() - VIRTUAL_WIDTH * canvasScale) * 0.5f;
        float canvasY = (gui.guiHeight() - VIRTUAL_HEIGHT * canvasScale) * 0.5f;

        gui.pose().pushPose();
        gui.pose().translate(canvasX, canvasY, 0);
        gui.pose().scale(canvasScale, canvasScale, 1);
        List<LuaObject> visible = objects.values().stream().filter(o -> o.added && o.visible)
                .filter(o -> !o.camera.equalsIgnoreCase("world"))
                .filter(o -> hud == !o.camera.equalsIgnoreCase("game"))
                .sorted(Comparator.comparingInt(o -> o.order)).toList();
        float baseZ = hud ? HUD_OBJECT_Z : GAME_OBJECT_Z;
        for (int i = 0; i < visible.size(); i++) {
            // Sorting implements setObjectOrder; a tiny local Z step keeps that order
            // deterministic without allowing large order values to cross native layers.
            renderObject(gui, visible.get(i), baseZ + i * OBJECT_ORDER_Z_STEP);
        }
        gui.pose().popPose();
    }

    public void renderWorld(PoseStack poseStack, Camera camera, BlockPos speakers, Direction facing) {
        if (closed || objects.isEmpty()) return;
        List<LuaWorldSpriteRenderer.Sprite> sprites = objects.values().stream()
                .filter(o -> o.added && o.visible && o.camera.equalsIgnoreCase("world"))
                .filter(o -> !o.textObject)
                .sorted(Comparator.comparingInt(o -> o.order))
                .map(o -> {
                    SparrowAtlas.Frame frame = o.texture == null ? null : currentFrame(o);
                    LuaWorldSpriteRenderer.Frame renderFrame = frame == null ? null
                            : new LuaWorldSpriteRenderer.Frame(frame.x, frame.y, frame.w, frame.h,
                            frame.frameX, frame.frameY, frame.rotated);
                    double[] offset = currentAnimationOffset(o);
                    return new LuaWorldSpriteRenderer.Sprite(
                            o.texture, o.textureWidth, o.textureHeight, renderFrame, offset[0], offset[1],
                            o.x, o.y, o.z, o.width, o.height, o.graphicWidth, o.graphicHeight,
                            o.scaleX, o.scaleY, o.alpha, o.angle, o.color,
                            o.worldBillboard, o.worldLighting);
                }).toList();
        LuaWorldSpriteRenderer.render(poseStack, camera, speakers, facing, sprites);
    }

    private void renderObject(GuiGraphics gui, LuaObject o, float z) {
        int alpha = Math.max(0, Math.min(255, (int) Math.round(o.alpha * 255)));
        int color = (o.color & 0x00FFFFFF) | alpha << 24;
        gui.pose().pushPose();
        gui.pose().translate(o.x, o.y, z);
        gui.pose().scale((float) o.scaleX, (float) o.scaleY, 1);
        if (o.angle != 0) gui.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees((float) o.angle));
        if (o.textObject) {
            float scale = Math.max(0.25f, o.textSize / 9f);
            gui.pose().scale(scale, scale, 1);
            Font selected = o.fontName.isBlank() ? null : fontLoader.get(o.fontName);
            Font font = selected == null ? Minecraft.getInstance().font : selected;
            int wrapWidth = o.width <= 0 ? Integer.MAX_VALUE
                    : Math.max(1, (int) Math.floor(o.width / scale));
            if (wrapWidth == Integer.MAX_VALUE) {
                gui.drawString(font, o.text, 0, 0, color, false);
            } else {
                int line = 0;
                for (var part : font.split(Component.literal(o.text), wrapWidth)) {
                    gui.drawString(font, part, 0, line++ * 9, color, false);
                }
            }
        } else if (o.texture != null) {
            float red = ((o.color >> 16) & 255) / 255f;
            float green = ((o.color >> 8) & 255) / 255f;
            float blue = (o.color & 255) / 255f;
            // GUI fills and other render batches may leave blending disabled.
            // Lua sprite alpha is continuous (0..1), so explicitly restore the
            // normal source-alpha blend and use GuiGraphics' texture tint.
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            gui.setColor(red, green, blue, alpha / 255f);
            SparrowAtlas.Frame frame = currentFrame(o);
            if (frame == null) {
                gui.blit(o.texture, 0, 0, 0, 0, Math.max(1, (int) o.width), Math.max(1, (int) o.height),
                        Math.max(1, o.textureWidth), Math.max(1, o.textureHeight));
            } else {
                renderAtlasFrame(gui, o, frame);
            }
            gui.setColor(1, 1, 1, 1);
        } else {
            gui.fill(0, 0, Math.max(1, (int) o.width), Math.max(1, (int) o.height), color);
        }
        gui.pose().popPose();
    }

    private static SparrowAtlas.Frame currentFrame(LuaObject object) {
        LuaAnimation animation = currentAnimation(object);
        if (animation != null && !animation.frames.isEmpty()) {
            return animation.frames.get(Math.max(0, Math.min(animation.frames.size() - 1,
                    object.animationFrame)));
        }
        return object.atlas == null || object.atlas.allFrames().isEmpty()
                ? null : object.atlas.allFrames().get(0);
    }

    private static void renderAtlasFrame(GuiGraphics gui, LuaObject object, SparrowAtlas.Frame frame) {
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
        gui.blit(object.texture, 0, 0, frame.x, frame.y, frame.w, frame.h,
                Math.max(1, object.textureWidth), Math.max(1, object.textureHeight));
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
        if (name == null || name.isBlank()) return null;
        if (!allows(SongLibrary.ExternalContent.IMAGES)) return null;
        String file = name.toLowerCase(Locale.ROOT).endsWith(".png") ? name : name + ".png";
        for (Path root : new Path[]{songFolder, modRoot}) {
            if (root == null) continue;
            for (String prefix : new String[]{"images", "assets/images", ""}) {
                Path base = prefix.isEmpty() ? root : root.resolve(prefix);
                Path path = base.resolve(file).normalize();
                if (path.startsWith(root) && Files.isRegularFile(path)) return path;
            }
        }
        return null;
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

    private static double ease(String name, double t) {
        String ease = name == null ? "linear" : name.toLowerCase(Locale.ROOT);
        if (ease.contains("sine")) return 1 - Math.cos(t * Math.PI / 2);
        if (ease.contains("quad")) return t * t;
        if (ease.contains("cube") || ease.contains("cubic")) return t * t * t;
        if (ease.contains("expo")) return t >= 1 ? 1 : 1 - Math.pow(2, -10 * t);
        if (ease.contains("smooth")) return t * t * (3 - 2 * t);
        return t;
    }

    private static int color(String raw) {
        String value = raw == null ? "FFFFFF" : raw.trim().replace("#", "");
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "white" -> 0xFFFFFFFF; case "black" -> 0xFF000000; case "red" -> 0xFFFF0000;
            case "green" -> 0xFF00FF00; case "blue" -> 0xFF0000FF; case "yellow" -> 0xFFFFFF00;
            default -> { try { long parsed = Long.parseLong(value, 16); yield (int) (value.length() <= 6 ? parsed | 0xFF000000L : parsed); }
                catch (Exception ignored) { yield 0xFFFFFFFF; } }
        };
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
        for (LuaObject object : objects.values()) disposeGraphic(object);
        fontLoader.close();
        scripts.clear(); objects.clear(); timers.clear(); tweens.clear(); sharedVars.clear();
    }
}
