package com.fnfmod.client.lua;

import com.fnfmod.FnfMod;
import com.fnfmod.client.render.MachineAtlasCache;
import com.fnfmod.client.render.MachineTextureCache;
import com.fnfmod.client.render.SparrowAtlas;
import com.fnfmod.gameplay.PlaybackMode;
import com.fnfmod.machine.MachineDefinition;
import com.fnfmod.machine.MachineLibrary;
import com.fnfmod.net.FnfPayloads;
import com.fnfmod.world.ModContentScope;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Sandboxed, bounded Lua runtime used only for custom machine menus. */
public final class MachineMenuRuntime implements AutoCloseable {

    public interface Host {
        int screenWidth();
        int screenHeight();
        void openSongSelect();
        boolean openSongDetails(String songId);
        boolean playSong(String songId, String difficulty, boolean duet, byte playSide, byte playbackMode);
        void openSettings();
        void openCharacterEditor();
        void openChartEditor(String songId, String difficulty);
        void closeMenu();
        void saveData(String snbt);
    }

    private record Widget(String kind, String id, LuaTable data) {}
    private record AnimationDef(String prefix, double fps, boolean loop) {}

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
    private static final long CALL_BUDGET_NANOS = 25_000_000L;

    private final MachineDefinition definition;
    private final Host host;
    private final List<FnfPayloads.SongInfo> songs;
    private final Globals globals;
    private final BudgetDebugLib budget = new BudgetDebugLib();
    private final List<Widget> widgets = new ArrayList<>();
    private final Map<String, AnimatedState> animatedStates = new LinkedHashMap<>();
    private final LuaTable machineData;
    private String error;

    public MachineMenuRuntime(MachineDefinition definition, String snbt,
                              List<FnfPayloads.SongInfo> songs, Host host) {
        this.definition = definition;
        this.host = host;
        this.songs = songs == null ? List.of() : List.copyOf(songs);
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
        installMachine();
        globals.set("machineData", machineData);
        load();
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

    private void load() {
        if (definition.menuScript() == null || !Files.isRegularFile(definition.menuScript())
                || !ModContentScope.allowsContentPath(definition.menuScript())) {
            error = "menu.lua missing or outside active mod.";
            return;
        }
        try {
            budget.begin();
            globals.load(Files.readString(definition.menuScript()), definition.menuScript().toString()).call();
            budget.end();
            callGlobal("onOpen");
        } catch (Throwable throwable) {
            budget.end();
            fail(throwable);
        }
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
            return LuaValue.valueOf(widgets.removeIf(widget -> widget.id().equals(id)));
        }));
        ui.set("clear", function(args -> {
            widgets.clear();
            animatedStates.clear();
            return LuaValue.TRUE;
        }));
        globals.set("ui", ui);
    }

    private LuaValue creator(String kind, LuaTable ui) {
        return function(args -> {
            if (widgets.size() >= MAX_WIDGETS) throw new LuaError("widget limit exceeded");
            int at = offset(args, ui);
            String id = args.arg(at).checkjstring();
            widgets.removeIf(widget -> widget.id().equals(id));
            animatedStates.remove(id);
            LuaTable table = new LuaTable();
            table.set("id", id);
            table.set("kind", kind);
            table.set("visible", LuaValue.TRUE);
            table.set("alpha", 1.0);
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
            host.saveData(dataSnbt());
            host.openSongSelect();
            return LuaValue.TRUE;
        }));
        machine.set("join", function(args -> {
            host.saveData(dataSnbt());
            host.openSongSelect();
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
            if (options.istable()) {
                duet = options.get("duet").optboolean(false);
                playSide = parsePlaySide(options.get("playAs"));
                playbackMode = parsePlaybackMode(options.get("look"));
            } else {
                duet = options.optboolean(false);
                playSide = parsePlaySide(args.arg(at + 3));
                playbackMode = parsePlaybackMode(args.arg(at + 4));
            }
            host.saveData(dataSnbt());
            return LuaValue.valueOf(host.playSong(id, difficulty, duet, playSide, playbackMode));
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

    private static byte parsePlaybackMode(LuaValue value) {
        if (value.isnumber()) return PlaybackMode.fromNetworkId((byte) value.toint()).networkId();
        return PlaybackMode.parse(value.optjstring("minecraft")).networkId();
    }

    public void tick() {
        callGlobal("onUpdate", LuaValue.valueOf(0.05));
    }

    public void render(GuiGraphics gui, Font font, int mouseX, int mouseY) {
        advanceAnimations();
        for (Widget widget : List.copyOf(widgets)) renderWidget(gui, font, widget, mouseX, mouseY);
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

    private void renderWidget(GuiGraphics gui, Font font, Widget widget, int mouseX, int mouseY) {
        LuaTable data = widget.data();
        if (!data.get("visible").optboolean(true)) return;
        int width = Math.max(1, (int) Math.round(data.get("width").optdouble(1)));
        int height = Math.max(1, (int) Math.round(data.get("height").optdouble(1)));
        int centerX = coordinate(data.get("x").optdouble(0.5), host.screenWidth());
        int centerY = coordinate(data.get("y").optdouble(0.5), host.screenHeight());
        int x = centerX - width / 2;
        int y = centerY - height / 2;
        int color = color(data);
        boolean hover = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        switch (widget.kind()) {
            case "panel" -> gui.fill(x, y, x + width, y + height, color);
            case "image", "sprite" -> renderImage(gui, data, centerX, centerY, width, height);
            case "animatedSprite" -> renderAnimatedSprite(gui, widget.id(), data,
                    centerX, centerY, width, height);
            case "label" -> gui.drawCenteredString(font, data.get("text").optjstring(""), centerX,
                    centerY - font.lineHeight / 2, color);
            case "button" -> {
                gui.fill(x, y, x + width, y + height, hover ? 0xCC555555 : 0xCC333333);
                gui.drawCenteredString(font, data.get("text").optjstring(""), centerX,
                        centerY - font.lineHeight / 2, color);
            }
            case "toggle" -> {
                boolean value = data.get("value").optboolean(false);
                gui.fill(x, y, x + width, y + height, value ? 0xCC397A49 : 0xCC5A3333);
                gui.drawCenteredString(font, data.get("text").optjstring("") + ": " + (value ? "ON" : "OFF"),
                        centerX, centerY - font.lineHeight / 2, color);
            }
            case "slider" -> {
                double min = data.get("min").optdouble(0);
                double max = data.get("max").optdouble(1);
                double value = data.get("value").optdouble(min);
                double ratio = max <= min ? 0 : Math.max(0, Math.min(1, (value - min) / (max - min)));
                gui.fill(x, y, x + width, y + height, 0xCC222222);
                gui.fill(x, y, x + (int) Math.round(width * ratio), y + height, 0xCC5577AA);
                gui.drawCenteredString(font, data.get("text").optjstring(""), centerX,
                        centerY - font.lineHeight / 2, color);
            }
        }
    }

    private void renderImage(GuiGraphics gui, LuaTable data, int centerX, int centerY,
                             int width, int height) {
        try {
            Path file = resolveAsset(data.get("path").optjstring(""));
            ResourceLocation texture = MachineTextureCache.get(file);
            if (texture == null) return;
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
        if (atlas == null || state.prefix.isBlank()) return;
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

    private Path resolveAsset(String raw) {
        if (definition.root() == null || raw == null || raw.isBlank()) return null;
        Path file = definition.root().resolve(raw).normalize();
        return file.startsWith(definition.root()) && ModContentScope.allowsContentPath(file) ? file : null;
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
        if (button != 0) return false;
        for (int i = widgets.size() - 1; i >= 0; i--) {
            Widget widget = widgets.get(i);
            LuaTable data = widget.data();
            if (!data.get("visible").optboolean(true) || widget.kind().equals("label")
                    || widget.kind().equals("panel") || widget.kind().equals("image")
                    || widget.kind().equals("sprite") || widget.kind().equals("animatedSprite")) continue;
            int width = Math.max(1, (int) Math.round(data.get("width").optdouble(1)));
            int height = Math.max(1, (int) Math.round(data.get("height").optdouble(1)));
            int x = coordinate(data.get("x").optdouble(0.5), host.screenWidth()) - width / 2;
            int y = coordinate(data.get("y").optdouble(0.5), host.screenHeight()) - height / 2;
            if (mouseX < x || mouseX >= x + width || mouseY < y || mouseY >= y + height) continue;
            if (widget.kind().equals("toggle")) {
                data.set("value", LuaValue.valueOf(!data.get("value").optboolean(false)));
                call(data.get("onChange"), data, data.get("value"));
            } else if (widget.kind().equals("slider")) {
                double min = data.get("min").optdouble(0);
                double max = data.get("max").optdouble(1);
                data.set("value", min + (max - min) * Math.max(0, Math.min(1, (mouseX - x) / width)));
                call(data.get("onChange"), data, data.get("value"));
            } else {
                call(data.get("onClick"), data);
            }
            return true;
        }
        return false;
    }

    private int color(LuaTable data) {
        int rgb = data.get("color").optint(0xFFFFFF);
        int alpha = (int) Math.round(Math.max(0, Math.min(1, data.get("alpha").optdouble(1))) * 255);
        return alpha << 24 | rgb & 0xFFFFFF;
    }

    private static int coordinate(double value, int extent) {
        return Math.abs(value) <= 1.0 ? (int) Math.round(value * extent) : (int) Math.round(value);
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
        callGlobal("onClose");
    }

    private static final class BudgetDebugLib extends DebugLib {
        private long deadline = Long.MAX_VALUE;

        void begin() {
            deadline = System.nanoTime() + CALL_BUDGET_NANOS;
        }

        void end() {
            deadline = Long.MAX_VALUE;
        }

        @Override
        public void onInstruction(int pc, Varargs varargs, int top) {
            if (System.nanoTime() > deadline) throw new LuaError("machine menu exceeded execution budget");
            super.onInstruction(pc, varargs, top);
        }
    }
}
