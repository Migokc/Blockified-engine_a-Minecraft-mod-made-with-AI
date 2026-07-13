package com.fnfmod.client.gui;

import com.fnfmod.chart.Conductor;
import com.fnfmod.chart.CommandEventPlaceholders;
import com.fnfmod.chart.SongChart;
import com.fnfmod.client.ClientOptions;
import com.fnfmod.client.ClientSession;
import com.fnfmod.client.FnfKeys;
import com.fnfmod.client.anim.CharacterAnimations;
import com.fnfmod.client.audio.SongPlayer;
import com.fnfmod.block.FunkinMachineBlock;
import com.fnfmod.client.camera.GameplayCamera;
import com.fnfmod.client.gui.editor.ChartEditorScreen;
import com.fnfmod.client.lua.PsychLuaRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.function.Supplier;
import com.fnfmod.client.render.NoteStyle;
import com.fnfmod.client.render.PsychNoteTextureCache;
import com.fnfmod.net.FnfPayloads;
import com.fnfmod.song.SongEntry;
import com.fnfmod.song.SongLibrary;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.UUID;
import java.nio.file.Path;

/** The rhythm gameplay screen. */
public class GameplayScreen extends Screen {

    private enum Phase { COUNTDOWN, PLAYING, PAUSED, GAMEOVER, RESULTS }

    /** Which side(s) the local player controls. BOTH is solo-only. */
    public enum PlayMode { PLAYER, OPPONENT, BOTH }

    // Psych Engine default judgement windows (ms)
    private static final double SICK = 45, GOOD = 90, BAD = 135, SHIT = 166;
    private static final double HOLD_RELEASE_GRACE_MS = 300;
    private static final String[] DIR_NAMES = {"left", "down", "up", "right"};

    private final BlockPos machinePos;
    private final SongChart chart;
    private final SongPlayer songPlayer;
    private final PlayMode mode;
    private final boolean playBoth;
    private final boolean myChartSideIsPlayer;
    private final UUID partnerId;
    private final int botEntityId;
    private final String partnerName;
    private final String partnerAnimSet;
    private final String myAnimSet;
    private final boolean duet;
    private long startAtEpochMs;

    private final Conductor conductor;
    /** Pristine notes restored before Lua is recreated on a song restart. */
    private final List<SongChart.Note> originalLuaNotes;

    // camera focus timeline (per chart section)
    private final double[] secStarts;
    private final boolean[] secFocusPlayer;
    private final String[] secEase;
    private final double[] secBeatMs;
    private int camSection = -1;
    /** Null follows Must Hit sections; otherwise an event owns camera focus. */
    private Boolean cameraFocusOverride;

    private static class GameNote {
        final SongChart.Note data;
        boolean hit, missed, holdDropped;
        boolean holdComplete;
        /** ms when the hold was released (>=0 = in the re-tap grace window), -1 = held. */
        double releasedMs = -1;

        GameNote(SongChart.Note data) {
            this.data = data;
        }

        double endMs() {
            return data.timeMs + Math.max(0, data.sustainMs);
        }
    }

    @SuppressWarnings("unchecked")
    private final List<GameNote>[] myLanes = new List[4];
    @SuppressWarnings("unchecked")
    private final List<GameNote>[] otherLanes = new List[4];
    private final int[] myLaneIndex = new int[4];
    private final int[] otherLaneIndex = new int[4];

    private Phase phase = Phase.COUNTDOWN;
    private double songPos;
    private int totalMyNotes;

    // scoring
    private int score, combo, misses, maxCombo;
    private final int[] judgements = new int[5]; // sick good bad shit miss
    private double accuracySum;
    private int accuracyCount;
    private float health = 1.0f;

    // input / strums
    private final boolean[] laneHeld = new boolean[4];
    @SuppressWarnings("unchecked")
    private final List<GameNote>[] activeHolds = new List[4];
    private final double[] myStrumFlash = new double[4];   // >0 confirm remaining ms
    private final double[] otherStrumFlash = new double[4];
    private double voicesMutedUntil = -1;

    // partner
    private int partnerScore, partnerCombo;
    private boolean partnerEnded, partnerFailed;
    private int partnerEndScore, partnerEndMisses;
    private float partnerEndAccuracy;

    // fx
    private record Popup(String text, int color, long bornMs) {}
    private final List<Popup> popups = new ArrayList<>();
    private record Splash(int lane, int variant, float x, float y, long bornMs,
                          String texture, float alpha) {}
    private final List<Splash> splashes = new ArrayList<>();
    private record CoverEnd(int lane, float x, float y, long bornMs) {}
    private final List<CoverEnd> coverEnds = new ArrayList<>();
    private static final double SPLASH_FPS = 24.0;
    private int lastBeat = -1;
    private int eventIndex;
    private boolean preSongEventsProcessed;
    private long lastSingMs;
    private long partnerLastSingMs;
    private int lastSentHealthHalf = Integer.MIN_VALUE;
    private final long[] lastHoldSingMs = new long[4];
    private long lastFrameNano;
    private boolean endSent;
    private int pauseSelection;
    private long pausedAtMs;
    /** Prevents one held Enter press from pausing and then confirming Resume via key repeat. */
    private boolean enterReady = true;
    private boolean editorPlaytest;
    private boolean editorPreview;
    private double editorStartMs;
    private Supplier<Screen> editorReturnFactory;
    private PsychLuaRuntime luaRuntime;
    private final PsychNoteTextureCache customNoteTextures;
    private boolean vanillaMusicMuted;
    private final double[] luaStrumX = new double[8];
    private final double[] luaStrumY = new double[8];
    private final double[] luaStrumAlpha = new double[8];
    private final double[] luaStrumAngle = new double[8];

    public GameplayScreen(BlockPos machinePos, SongChart chart, SongPlayer songPlayer,
                          PlayMode mode, UUID partnerId, String partnerName,
                          String partnerAnimSet, int botEntityId, long startAtEpochMs) {
        super(Component.literal("FNF"));
        this.machinePos = machinePos;
        this.chart = chart;
        this.songPlayer = songPlayer;
        this.mode = mode;
        this.playBoth = mode == PlayMode.BOTH;
        this.myChartSideIsPlayer = mode != PlayMode.OPPONENT;
        this.partnerId = partnerId;
        this.botEntityId = botEntityId;
        this.partnerName = partnerName == null ? "" : partnerName;
        this.partnerAnimSet = partnerAnimSet == null || partnerAnimSet.isEmpty()
                ? CharacterAnimations.DEFAULT_SET : partnerAnimSet;
        this.myAnimSet = ClientOptions.get().animationSet;
        this.duet = partnerId != null;
        this.startAtEpochMs = startAtEpochMs;
        this.conductor = new Conductor(chart);
        this.originalLuaNotes = chart.notes.stream().map(SongChart.Note::copy).toList();
        SongEntry resourceEntry = ClientSession.songId == null ? null : SongLibrary.get(ClientSession.songId);
        Path resourceRoot = resourceEntry == null ? ClientSession.resolvedFolder
                : (resourceEntry.modRoot != null ? resourceEntry.modRoot : resourceEntry.folder);
        this.customNoteTextures = new PsychNoteTextureCache(ClientSession.resolvedFolder, resourceRoot,
                resourceEntry == null || resourceEntry.allows(SongLibrary.ExternalContent.IMAGES));
        Arrays.fill(luaStrumX, Double.NaN);
        Arrays.fill(luaStrumY, Double.NaN);
        Arrays.fill(luaStrumAlpha, 1.0);

        for (int i = 0; i < 4; i++) {
            myLanes[i] = new ArrayList<>();
            otherLanes[i] = new ArrayList<>();
            activeHolds[i] = new ArrayList<>();
        }
        for (SongChart.Note n : chart.notes) {
            GameNote gn = new GameNote(n);
            // BOTH mode merges every note into one centered strumline
            boolean mine = playBoth || n.playerSide == myChartSideIsPlayer;
            if (mine) {
                myLanes[n.lane].add(gn);
                if (!n.ratingDisabled) totalMyNotes++;
            } else {
                otherLanes[n.lane].add(gn);
            }
        }
        chart.sortEvents();

        // camera focus timeline from chart sections
        int n = Math.max(1, chart.sections.size());
        secStarts = new double[n];
        secFocusPlayer = new boolean[n];
        secEase = new String[n];
        secBeatMs = new double[n];
        double time = 0;
        double bpm = chart.startBpm;
        for (int i = 0; i < n; i++) {
            SongChart.Section s = i < chart.sections.size() ? chart.sections.get(i) : null;
            if (s != null && s.changeBPM && s.bpm > 0) bpm = s.bpm;
            secStarts[i] = time;
            secFocusPlayer[i] = s == null || s.mustHit;
            secEase[i] = s == null || s.camEase == null ? "smooth" : s.camEase;
            secBeatMs[i] = 60000.0 / bpm;
            time += (s == null ? 4 : s.sectionBeats) * (60000.0 / bpm);
        }

        beginCamera();
        lastFrameNano = System.nanoTime();
    }

    /** Standalone chart-editor playtest. It never creates or leaves a server song session. */
    public static GameplayScreen editorPlaytest(BlockPos machinePos, SongChart chart, SongPlayer player,
                                                double startMs, boolean preview, Supplier<Screen> returnFactory) {
        GameplayScreen screen = new GameplayScreen(machinePos, chart, player, PlayMode.PLAYER,
                null, "", CharacterAnimations.DEFAULT_SET, -1, System.currentTimeMillis() + 1000);
        screen.editorPlaytest = true;
        screen.editorPreview = preview;
        screen.editorStartMs = Math.max(0, startMs);
        screen.editorReturnFactory = returnFactory;
        screen.prepareEditorStart();
        return screen;
    }

    private void prepareEditorStart() {
        if (!editorPlaytest || editorStartMs <= 0) return;
        double cutoff = editorStartMs - SHIT;
        for (int lane = 0; lane < 4; lane++) {
            myLaneIndex[lane] = skipNotesBefore(myLanes[lane], cutoff);
            otherLaneIndex[lane] = skipNotesBefore(otherLanes[lane], cutoff);
        }
        while (eventIndex < chart.events.size() && chart.events.get(eventIndex).beforeSong) {
            eventIndex++;
        }
        while (eventIndex < chart.events.size() && !chart.events.get(eventIndex).beforeSong
                && chart.events.get(eventIndex).timeMs < editorStartMs) {
            eventIndex++;
        }
    }

    private static int skipNotesBefore(List<GameNote> notes, double cutoff) {
        int index = 0;
        while (index < notes.size() && notes.get(index).endMs() < cutoff) {
            GameNote note = notes.get(index++);
            note.hit = true;
            note.holdComplete = true;
        }
        return index;
    }

    private void beginCamera() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        // stage layout is derived from the machine block's facing (same math as the server teleport)
        Direction facing = Direction.NORTH;
        var state = mc.level.getBlockState(machinePos);
        if (state.hasProperty(FunkinMachineBlock.FACING)) {
            facing = state.getValue(FunkinMachineBlock.FACING);
        }
        Direction right = facing.getCounterClockWise();
        double cx = machinePos.getX() + 0.5 + facing.getStepX() * 2.0;
        double cz = machinePos.getZ() + 0.5 + facing.getStepZ() * 2.0;
        double cy = machinePos.getY() + 1.0;
        Vec3 playerSpot = new Vec3(cx + right.getStepX() * 1.5, cy, cz + right.getStepZ() * 1.5);
        Vec3 opponentSpot = new Vec3(cx - right.getStepX() * 1.5, cy, cz - right.getStepZ() * 1.5);

        Supplier<Vec3> mePos = () -> {
            var p = Minecraft.getInstance().player;
            return p == null ? null : p.position().add(0, 1.0, 0);
        };
        float[] myBase = CharacterAnimations.baseCameraOffset(myAnimSet);

        if (playBoth) {
            // one character center stage playing everything: camera just stays on them
            Vec3 anchor = new Vec3(cx, cy, cz);
            GameplayCamera.begin(anchor, facing.toYRot(), mePos, mePos, mePos, myBase, myBase);
            return;
        }

        Vec3 anchor = myChartSideIsPlayer ? playerSpot : opponentSpot;
        Supplier<Vec3> otherPos;
        if (partnerId != null) {
            Vec3 fallback = myChartSideIsPlayer ? opponentSpot : playerSpot;
            otherPos = () -> {
                var level = Minecraft.getInstance().level;
                var p = level == null ? null : level.getPlayerByUUID(partnerId);
                return p == null ? fallback : p.position().add(0, 1.0, 0);
            };
        } else {
            // Solo: follow the exact bot armor stand; fall back until its spawn packet arrives.
            Vec3 botSpot = myChartSideIsPlayer ? opponentSpot : playerSpot;
            otherPos = () -> {
                var level = Minecraft.getInstance().level;
                var bot = level == null || botEntityId < 0 ? null : level.getEntity(botEntityId);
                return bot == null || bot.isRemoved() ? botSpot : bot.position().add(0, 1.0, 0);
            };
        }
        float[] partnerBase = CharacterAnimations.baseCameraOffset(partnerAnimSet);
        GameplayCamera.begin(anchor, facing.toYRot(), mePos,
                myChartSideIsPlayer ? mePos : otherPos,
                myChartSideIsPlayer ? otherPos : mePos,
                myChartSideIsPlayer ? myBase : partnerBase,
                myChartSideIsPlayer ? partnerBase : myBase);
    }

    // ------------------------------------------------------------------ timing

    private void updateSongPos() {
        if (phase == Phase.PAUSED || phase == Phase.GAMEOVER) return;
        if (!songPlayer.isStarted()) {
            double countdownTime = System.currentTimeMillis() - startAtEpochMs;
            songPos = countdownTime + (editorPlaytest ? editorStartMs : 0);
            if (countdownTime >= 0 && phase == Phase.COUNTDOWN) {
                songPlayer.start();
                if (editorPlaytest && editorStartMs > 0) {
                    songPlayer.seekMs(editorStartMs + chart.offsetMs - ClientOptions.get().offsetMs);
                }
                phase = Phase.PLAYING;
            }
        }
        if (songPlayer.isStarted()) {
            songPos = songPlayer.positionMs() - chart.offsetMs + ClientOptions.get().offsetMs;
        }
    }

    private double pxPerMs() {
        var o = ClientOptions.get();
        double speed = o.constantScrollSpeed ? o.scrollSpeedMult : chart.speed * o.scrollSpeedMult;
        return 0.45 * speed * (height / 720.0);
    }

    // ------------------------------------------------------------------ tick logic

    @Override
    protected void init() {
        super.init();
        muteVanillaMusic();
    }

    private void muteVanillaMusic() {
        if (minecraft == null) return;
        minecraft.getSoundManager().updateSourceVolume(SoundSource.MUSIC, 0f);
        vanillaMusicMuted = true;
    }

    private void restoreVanillaMusic() {
        if (!vanillaMusicMuted || minecraft == null) return;
        minecraft.getSoundManager().updateSourceVolume(SoundSource.MUSIC,
                minecraft.options.getSoundSourceVolume(SoundSource.MUSIC));
        vanillaMusicMuted = false;
    }

    private void logic() {
        long now = System.nanoTime();
        double dtMs = (now - lastFrameNano) / 1_000_000.0;
        lastFrameNano = now;
        if (dtMs > 100) dtMs = 100;

        if (luaRuntime == null && width > 0 && height > 0) {
            luaRuntime = PsychLuaRuntime.load(this, chart);
            rebuildNoteLanesAfterLuaCreate();
        }
        // Load-triggered events must run before updateSongPos can start audio.
        if (!preSongEventsProcessed && phase == Phase.COUNTDOWN) processPreSongEvents();
        updateSongPos();
        if (luaRuntime != null && (phase == Phase.PLAYING || phase == Phase.COUNTDOWN)) {
            luaRuntime.update(dtMs / 1000.0);
        }
        songPlayer.applyVolumes();
        syncVanillaHealth();

        // keep bodies square with the look direction so animations stay oriented
        alignBody(minecraft.player);
        if (partnerId != null && minecraft.level != null) {
            alignBody(minecraft.level.getPlayerByUUID(partnerId));
        }

        if (phase != Phase.PLAYING && phase != Phase.COUNTDOWN) return;

        songPlayer.resync();
        if (phase == Phase.PLAYING) processEvents();

        // un-mute vocals after miss
        if (voicesMutedUntil >= 0 && songPos > voicesMutedUntil) {
            songPlayer.setPlayerVoiceVolume(1f);
            songPlayer.setOpponentVoiceVolume(1f);
            voicesMutedUntil = -1;
        }

        // strum flash decay
        for (int i = 0; i < 4; i++) {
            if (myStrumFlash[i] > 0) myStrumFlash[i] -= dtMs;
            if (otherStrumFlash[i] > 0) otherStrumFlash[i] -= dtMs;
        }

        // misses (notes that scrolled past)
        sweepMisses(myLanes, myLaneIndex);

        // holds
        for (int lane = 0; lane < 4; lane++) {
            var holds = activeHolds[lane];
            for (var it = holds.iterator(); it.hasNext(); ) {
                GameNote hold = it.next();
                double end = hold.endMs();
                if (songPos >= end) {
                    it.remove();
                    boolean graceExpiredBeforeEnd = hold.releasedMs >= 0
                            && end - hold.releasedMs > HOLD_RELEASE_GRACE_MS;
                    if (graceExpiredBeforeEnd) {
                        hold.holdDropped = true;
                        missHold(lane, hold);
                    } else {
                        hold.holdComplete = true;
                        if (laneHeld[lane]) spawnCoverEnd(lane);
                    }
                } else if (laneHeld[lane]) {
                    hold.releasedMs = -1; // holding (or resumed within grace)
                    health = Math.min(2f, health + (float) (hold.data.hitHealth * dtMs / 1000.0));
                    strumFlashFor(hold)[lane] = Math.max(strumFlashFor(hold)[lane], 40);
                    // loop the sing animation while the note is held
                    long nowMs = System.currentTimeMillis();
                    if (!hold.data.noAnimation && (hold.data.playerSide == myChartSideIsPlayer || playBoth)) {
                        if (nowMs - lastHoldSingMs[lane] > 180 && minecraft.player != null) {
                            CharacterAnimations.play(minecraft.player, myAnimSet, myRole(),
                                    DIR_NAMES[lane] + hold.data.animSuffix);
                            lastHoldSingMs[lane] = nowMs;
                            lastSingMs = nowMs; // suppress idle bop during the hold
                        }
                    }
                } else {
                    // Released — 0.3s window to press again and keep the hold
                    if (hold.releasedMs < 0) hold.releasedMs = songPos;
                    if (songPos - hold.releasedMs > HOLD_RELEASE_GRACE_MS) {
                        // grace expired: the remaining trail disappears, counts as a miss
                        hold.holdDropped = true;
                        it.remove();
                        missHold(lane, hold);
                    }
                }
            }
        }

        // bot plays the other side in solo (unless the player is playing both sides)
        if (!duet && !playBoth) {
            for (int lane = 0; lane < 4; lane++) {
                List<GameNote> list = otherLanes[lane];
                while (otherLaneIndex[lane] < list.size()) {
                    GameNote n = list.get(otherLaneIndex[lane]);
                    if (n.hit) {
                        if (songPos < n.endMs()) break;
                        otherLaneIndex[lane]++;
                        continue;
                    }
                    if (songPos >= n.data.timeMs) {
                        if (n.data.blockHit && songPos - n.data.timeMs <= SHIT) break;
                        if (!n.data.ignoreNote && !n.data.blockHit) {
                            int noteIndex = chart.notes.indexOf(n.data);
                            boolean runDefault = luaRuntime == null || luaRuntime.onOpponentNoteHitPre(
                                    noteIndex, lane, n.data.noteType, n.data.sustainMs > 30);
                            if (!runDefault) break;
                            n.hit = true;
                            otherStrumFlash[lane] = Math.max(120, n.data.sustainMs);
                            if (luaRuntime != null) luaRuntime.onOpponentNoteHit(
                                    noteIndex, lane, n.data.noteType, n.data.sustainMs > 30);
                        } else {
                            n.hit = true;
                        }
                    } else {
                        break;
                    }
                }
            }
        }

        // camera focus follows the chart's sections
        double camPos = Math.max(0, songPos);
        int idx = Math.max(0, camSection);
        if (camSection >= 0 && camPos < secStarts[camSection]) idx = 0; // song jumped backwards (restart)
        while (idx + 1 < secStarts.length && camPos >= secStarts[idx + 1]) idx++;
        if (idx != camSection) {
            camSection = idx;
            if (cameraFocusOverride == null) {
                GameplayCamera.focus(secFocusPlayer[idx], secEase[idx], Math.min(700, secBeatMs[idx] * 2));
            }
        }

        // idle bounce on beat (idle/idle2 alternate when both exist)
        int beat = (int) Math.floor(conductor.beatAt(Math.max(0, songPos)));
        if (beat != lastBeat) {
            lastBeat = beat;
            long nowMs = System.currentTimeMillis();
            // FNF beat zoom: bump every measure (4 beats)
            if (phase == Phase.PLAYING && beat >= 0 && beat % 4 == 0) {
                GameplayCamera.bumpZoom(0.03f);
            }
            if (phase == Phase.PLAYING) {
                if (nowMs - lastSingMs > 600 && minecraft.player != null) {
                    playIdle(minecraft.player, myAnimSet, myRole(), beat);
                }
                if (partnerId != null && minecraft.level != null && nowMs - partnerLastSingMs > 600) {
                    Player partner = minecraft.level.getPlayerByUUID(partnerId);
                    if (partner != null) playIdle(partner, partnerAnimSet, partnerRole(), beat);
                }
            }
        }

        // song end
        if (phase == Phase.PLAYING && songPlayer.isFinished()) {
            if (!anyNotesLeft(myLanes, myLaneIndex)) finishSong(false);
        }
    }

    /** Psych onCreate may change noteType/mustPress before gameplay begins. */
    private void rebuildNoteLanesAfterLuaCreate() {
        for (SongChart.Note note : chart.notes) note.lane = Mth.clamp(note.lane, 0, 3);
        chart.sortNotes();
        for (int lane = 0; lane < 4; lane++) {
            myLanes[lane].clear();
            otherLanes[lane].clear();
            myLaneIndex[lane] = 0;
            otherLaneIndex[lane] = 0;
        }
        totalMyNotes = 0;
        for (SongChart.Note note : chart.notes) {
            GameNote gameNote = new GameNote(note);
            boolean mine = playBoth || note.playerSide == myChartSideIsPlayer;
            (mine ? myLanes[note.lane] : otherLanes[note.lane]).add(gameNote);
            if (mine && !note.ratingDisabled) totalMyNotes++;
        }
    }

    private void processEvents() {
        while (eventIndex < chart.events.size() && chart.events.get(eventIndex).timeMs <= songPos) {
            int currentEventIndex = eventIndex++;
            SongChart.Event event = chart.events.get(currentEventIndex);
            if (!event.beforeSong) executeEvent(currentEventIndex, event);
        }
    }

    private void processPreSongEvents() {
        preSongEventsProcessed = true;
        for (int i = 0; i < chart.events.size(); i++) {
            SongChart.Event event = chart.events.get(i);
            if (event.beforeSong) executeEvent(i, event);
        }
        while (eventIndex < chart.events.size() && chart.events.get(eventIndex).beforeSong) {
            eventIndex++;
        }
    }

    private void executeEvent(int eventIndex, SongChart.Event event) {
        if (isMinecraftCommandEvent(event)) {
            if ("server".equalsIgnoreCase(event.value2.trim())) {
                PacketDistributor.sendToServer(new FnfPayloads.CommandEventC2S(machinePos, eventIndex));
            } else {
                runPlayerCommand(event.value1);
            }
        } else if (isCameraZoomEvent(event)) {
            try {
                GameplayCamera.zoomTo(Float.parseFloat(event.value1.trim()), event.value2);
            } catch (NumberFormatException ignored) {}
        } else if (isCameraFocusEvent(event)) {
            applyCameraFocusEvent(event);
        }
        if (luaRuntime != null) luaRuntime.onEvent(event.name, event.value1, event.value2, event.timeMs);
    }

    private static boolean isMinecraftCommandEvent(SongChart.Event event) {
        return event != null && (event.name.equalsIgnoreCase("Minecraft Command")
                || event.name.equalsIgnoreCase("Run Minecraft Command"));
    }

    private static boolean isCameraZoomEvent(SongChart.Event event) {
        return event != null && event.name.equalsIgnoreCase("Camera Zoom");
    }

    private static boolean isCameraFocusEvent(SongChart.Event event) {
        return event != null && event.name.equalsIgnoreCase("Camera Focus");
    }

    private void applyCameraFocusEvent(SongChart.Event event) {
        String target = event.value1 == null ? "" : event.value1.trim();
        if (target.isEmpty()) {
            cameraFocusOverride = null;
            int section = camSection < 0 ? 0 : Math.min(camSection, secFocusPlayer.length - 1);
            GameplayCamera.focus(secFocusPlayer[section], secEase[section],
                    Math.min(700, secBeatMs[section] * 2));
            return;
        }

        boolean player;
        if (target.equalsIgnoreCase("player")) player = true;
        else if (target.equalsIgnoreCase("opponent")) player = false;
        else return;
        cameraFocusOverride = player;
        String eventEase = event.value2 == null || event.value2.isBlank() ? "smooth" : event.value2;
        GameplayCamera.focus(player, eventEase, 500);
    }

    private void runPlayerCommand(String rawCommand) {
        if (minecraft.player == null || minecraft.player.connection == null || rawCommand == null) return;
        Direction facing = Direction.NORTH;
        if (minecraft.level != null) {
            var state = minecraft.level.getBlockState(machinePos);
            if (state.hasProperty(FunkinMachineBlock.FACING)) facing = state.getValue(FunkinMachineBlock.FACING);
        }
        String command = CommandEventPlaceholders.expand(rawCommand, machinePos, facing).trim();
        while (command.startsWith("/")) command = command.substring(1).trim();
        if (command.isEmpty()) return;
        try {
            minecraft.player.connection.sendCommand(command);
        } catch (Exception e) {
            minecraft.player.displayClientMessage(
                    Component.literal("FNF event command failed: " + e.getMessage()), false);
        }
    }

    private void sweepMisses(List<GameNote>[] lanes, int[] laneIndex) {
        for (int lane = 0; lane < 4; lane++) {
            List<GameNote> list = lanes[lane];
            while (laneIndex[lane] < list.size()) {
                GameNote n = list.get(laneIndex[lane]);
                if (n.hit || n.missed) {
                    if (n.hit && activeHolds[lane].contains(n) && !n.holdComplete) break;
                    laneIndex[lane]++;
                    continue;
                }
                if (songPos - n.data.timeMs > SHIT * Math.max(0, n.data.lateHitMult)) {
                    if (n.data.ignoreNote) n.hit = true;
                    else missNote(lane, n);
                    laneIndex[lane]++;
                } else {
                    break;
                }
            }
        }
    }

    private boolean anyNotesLeft(List<GameNote>[] lanes, int[] laneIndex) {
        for (int lane = 0; lane < 4; lane++) {
            for (int i = laneIndex[lane]; i < lanes[lane].size(); i++) {
                GameNote n = lanes[lane].get(i);
                if (!n.hit && !n.missed) return true;
            }
        }
        return false;
    }

    /** All playable notes flash the (single) player strumline. */
    private double[] strumFlashFor(GameNote n) {
        return myStrumFlash;
    }

    private void spawnCoverEnd(int lane) {
        if (NoteStyle.holdCoverEndFrames(lane) > 0) {
            coverEnds.add(new CoverEnd(lane, laneX(true, lane), receptorY(), System.currentTimeMillis()));
            if (coverEnds.size() > 8) coverEnds.remove(0);
        }
    }

    private void playIdle(Player p, String set, String role, int beat) {
        String action = (beat & 1) == 1 ? "idle2" : "idle";
        if (CharacterAnimations.play(p, set, role, action) == null && !"idle".equals(action)) {
            CharacterAnimations.play(p, set, role, "idle");
        }
    }

    /** Vanilla HUD mode: push FNF health onto the real hearts (never lethal, restored after). */
    private void syncVanillaHealth() {
        if (!"vanilla".equals(ClientOptions.effectiveHudStyle()) || minecraft.player == null) return;
        float maxHp = minecraft.player.getMaxHealth();
        float target = Math.max(1f, Math.min(maxHp, maxHp * (health / 2f)));
        int half = Math.round(target * 2f);
        if (half != lastSentHealthHalf) {
            lastSentHealthHalf = half;
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new FnfPayloads.SetHealthC2S(half / 2f));
        }
    }

    private static void alignBody(Player p) {
        if (p == null) return;
        float yaw = p.getYHeadRot();
        p.yBodyRot = yaw;
        p.yBodyRotO = yaw;
    }

    private String myRole() {
        return myChartSideIsPlayer ? "player" : "opponent";
    }

    private String partnerRole() {
        return myChartSideIsPlayer ? "opponent" : "player";
    }

    // ------------------------------------------------------------------ hit / miss

    /**
     * Song position sampled right now, for judging inputs. The cached songPos
     * is only refreshed once per frame, so key events would otherwise be judged
     * up to a full frame late (very noticeable with v-sync / capped fps).
     */
    private double liveSongPos() {
        if (songPlayer.isStarted() && !songPlayer.isPaused()) {
            return songPlayer.positionMs() - chart.offsetMs + ClientOptions.get().offsetMs;
        }
        return songPos;
    }

    private GameNote findClosest(List<GameNote> list, int startIndex, double inputPos) {
        GameNote best = null;
        double bestDist = Double.MAX_VALUE;
        for (int i = startIndex; i < list.size(); i++) {
            GameNote n = list.get(i);
            if (n.hit || n.missed || n.data.blockHit || !canHitAt(n, inputPos)) continue;
            double dist = n.data.timeMs - inputPos;
            double abs = Math.abs(dist);
            boolean priority = best == null || (best.data.lowPriority && !n.data.lowPriority)
                    || best.data.lowPriority == n.data.lowPriority && abs < bestDist;
            if (priority) {
                best = n;
                bestDist = abs;
            }
        }
        return best;
    }

    private boolean canHitAt(GameNote note, double positionMs) {
        double distance = note.data.timeMs - positionMs;
        return distance >= -SHIT * Math.max(0, note.data.lateHitMult)
                && distance <= SHIT * Math.max(0, note.data.earlyHitMult);
    }

    private GameNote findGameNote(SongChart.Note data) {
        for (int lane = 0; lane < 4; lane++) {
            for (GameNote note : myLanes[lane]) if (note.data == data) return note;
            for (GameNote note : otherLanes[lane]) if (note.data == data) return note;
        }
        return null;
    }

    private void hitAttempt(int lane) {
        hitAttempt(lane, true);
    }

    private void hitAttempt(int lane, boolean allowGhostMiss) {
        double inputPos = liveSongPos();
        GameNote best = findClosest(myLanes[lane], myLaneIndex[lane], inputPos);

        if (best == null) {
            if (allowGhostMiss && !ClientOptions.get().ghostTapping) {
                ghostMiss(lane);
            }
            return;
        }
        int bestIndex = chart.notes.indexOf(best.data);
        if (luaRuntime != null && !luaRuntime.onGoodNoteHitPre(
                bestIndex, best.data.lane, best.data.noteType, best.data.sustainMs > 30)) {
            return;
        }
        if (best.data.hitCausesMiss || "Hurt Note".equals(best.data.noteType)) {
            creditHarmfulHit(best);
            return;
        }

        double bestDist = Math.abs(best.data.timeMs - inputPos);
        int judgement;
        if (bestDist <= SICK) judgement = 0;
        else if (bestDist <= GOOD) judgement = 1;
        else if (bestDist <= BAD) judgement = 2;
        else judgement = 3;

        if (!best.data.hitsoundDisabled && best.data.hitsoundVolume > 0) {
            Path customSound = customNoteTextures.resolveSound(best.data.hitsound);
            if (customSound != null) {
                com.fnfmod.client.audio.HitsoundPlayer.play(customSound,
                        (float) (ClientOptions.get().hitsoundVolume * best.data.hitsoundVolume));
            } else if (best.data.hitsound == null || best.data.hitsound.isBlank()
                    || best.data.hitsound.equalsIgnoreCase("hitsound")) {
                com.fnfmod.client.audio.HitsoundPlayer.play();
            }
        }

        creditHit(best, judgement);
        // stacked-note protection: notes in this lane impossibly close to the one
        // just hit (BOTH mode = 40ms merge window; otherwise ~20ms overlap) are
        // credited by the same press so they can't become forced misses
        double stackMs = playBoth ? 40 : 1;
        List<GameNote> list = myLanes[lane];
        for (int i = myLaneIndex[lane]; i < list.size(); i++) {
            GameNote n = list.get(i);
            if (n.data.timeMs > best.data.timeMs + stackMs) break;
            if (n == best || n.hit || n.missed) continue;
            if (Math.abs(n.data.timeMs - best.data.timeMs) <= stackMs
                    && !n.data.hitCausesMiss && !"Hurt Note".equals(n.data.noteType)) {
                int noteIndex = chart.notes.indexOf(n.data);
                if (luaRuntime == null || luaRuntime.onGoodNoteHitPre(
                        noteIndex, n.data.lane, n.data.noteType, n.data.sustainMs > 30)) {
                    creditHit(n, judgement);
                }
            }
        }

        songPlayer.setPlayerVoiceVolume(1f);
        songPlayer.setOpponentVoiceVolume(1f);
        voicesMutedUntil = -1;

        String[] names = {"SICK!!", "GOOD", "BAD", "SHIT"};
        int[] colors = {0xFF66FFFF, 0xFF66FF66, 0xFFFFAA33, 0xFFFF5555};
        if (!best.data.ratingDisabled) {
            addPopup(names[judgement] + (combo > 1 ? "  " + combo : ""), colors[judgement]);
        }

        if (!best.data.noAnimation) sing(lane, false, best.data);
        sendNoteEvent(lane, (byte) judgement);
    }

    private void creditHit(GameNote n, int judgement) {
        int[] points = {350, 200, 100, 50};
        double[] accWeight = {1.0, 0.67, 0.34, 0.0};
        n.hit = true;
        if (!n.data.ratingDisabled) {
            judgements[judgement]++;
            accuracyCount++;
            accuracySum += accWeight[judgement];
            score += points[judgement];
            combo++;
            maxCombo = Math.max(maxCombo, combo);
        }
        health = Math.min(2f, health + (float) n.data.hitHealth);
        strumFlashFor(n)[n.data.lane] = 150;
        if (n.data.sustainMs > 30) activeHolds[n.data.lane].add(n);
        if (luaRuntime != null) luaRuntime.onGoodNoteHit(
                chart.notes.indexOf(n.data), n.data.lane, n.data.noteType, n.data.sustainMs > 30);

        // FNF splashes only fire on sick hits
        int customSplashVariants = customNoteTextures.splashVariants(n.data.noteSplashTexture, n.data.lane);
        int splashVariants = customSplashVariants > 0
                ? customSplashVariants : NoteStyle.splashVariants(n.data.lane);
        if (judgement == 0 && !n.data.noteSplashDisabled && splashVariants > 0) {
            int variant = (int) (Math.random() * splashVariants);
            splashes.add(new Splash(n.data.lane, variant,
                    laneX(true, n.data.lane), receptorY(), System.currentTimeMillis(),
                    customSplashVariants > 0 ? n.data.noteSplashTexture : "",
                    (float) n.data.noteSplashAlpha));
            if (splashes.size() > 16) splashes.remove(0);
        }
    }

    private void missNote(int lane, GameNote n) {
        n.missed = true;
        misses++;
        judgements[4]++;
        accuracyCount++;
        comboBreak();
        score -= 10;
        health = Math.max(0, health - (float) n.data.missHealth);
        muteVoices(n.data.playerSide);
        addPopup("MISS", 0xFF8877AA);
        if (!n.data.noMissAnimation) sing(lane, true, n.data);
        sendNoteEvent(lane, (byte) 4);
        if (luaRuntime != null) luaRuntime.onNoteMiss(
                chart.notes.indexOf(n.data), lane, n.data.noteType, n.data.sustainMs > 30);
        checkDeath();
    }

    private void ghostMiss(int lane) {
        comboBreak();
        score -= 10;
        health = Math.max(0, health - 0.04f);
        muteVoices(myChartSideIsPlayer);
        sing(lane, true);
        if (luaRuntime != null) luaRuntime.onNoteMissPress(lane);
        checkDeath();
    }

    /** Dropping a sustain early = full miss (score, stats, health, vocals). */
    private void missHold(int lane, GameNote hold) {
        misses++;
        judgements[4]++;
        accuracyCount++;
        comboBreak();
        score -= 10;
        health = Math.max(0, health - (float) hold.data.missHealth);
        muteVoices(hold.data.playerSide);
        addPopup("MISS", 0xFF8877AA);
        if (!hold.data.noMissAnimation) sing(lane, true, hold.data);
        sendNoteEvent(lane, (byte) 4);
        if (luaRuntime != null) luaRuntime.onNoteMiss(
                chart.notes.indexOf(hold.data), lane, hold.data.noteType, true);
        checkDeath();
    }

    private void creditHarmfulHit(GameNote note) {
        note.hit = true;
        misses++;
        judgements[4]++;
        accuracyCount++;
        comboBreak();
        score -= 10;
        float damage = (float) ("Hurt Note".equals(note.data.noteType)
                && Math.abs(note.data.missHealth - 0.0475) < 0.0001 ? 0.3 : note.data.missHealth);
        health = Math.max(0, health - damage);
        muteVoices(note.data.playerSide);
        addPopup("OUCH", 0xFFFF3333);
        if (!note.data.noMissAnimation) sing(note.data.lane, true, note.data);
        sendNoteEvent(note.data.lane, (byte) 4);
        int index = chart.notes.indexOf(note.data);
        if (luaRuntime != null) {
            luaRuntime.onNoteMiss(index, note.data.lane, note.data.noteType, note.data.sustainMs > 30);
            luaRuntime.onGoodNoteHit(index, note.data.lane, note.data.noteType, note.data.sustainMs > 30);
        }
        checkDeath();
    }

    private void comboBreak() {
        combo = 0;
    }

    private void muteVoices(boolean playerStem) {
        if (playerStem) songPlayer.setPlayerVoiceVolume(0f);
        else songPlayer.setOpponentVoiceVolume(0f);
        voicesMutedUntil = songPos + 300;
    }

    private void checkDeath() {
        if (health <= 0 && phase == Phase.PLAYING) {
            phase = Phase.GAMEOVER;
            songPlayer.pause();
            if (duet) finishSong(true);
        }
    }

    private void sing(int lane, boolean miss) {
        sing(lane, miss, null);
    }

    private void sing(int lane, boolean miss, SongChart.Note note) {
        lastSingMs = System.currentTimeMillis();
        if (minecraft.player != null) {
            String suffix = note == null || note.animSuffix == null ? "" : note.animSuffix;
            String action = miss ? "miss" + suffix : DIR_NAMES[lane] + suffix;
            float[] camOff = CharacterAnimations.play(minecraft.player, myAnimSet, myRole(),
                    action);
            if (camOff != null && !miss) {
                GameplayCamera.sing(myChartSideIsPlayer, camOff[0], camOff[1]);
                // playing both sides: the camera may be focused on either side, nudge both
                if (playBoth) {
                    GameplayCamera.sing(!myChartSideIsPlayer, camOff[0], camOff[1]);
                }
            }
        }
    }

    private void sendNoteEvent(int lane, byte judgement) {
        if (duet) {
            PacketDistributor.sendToServer(new FnfPayloads.NoteEventC2S(machinePos, lane, judgement, combo, score));
        }
    }

    private float accuracy() {
        return accuracyCount == 0 ? 1f : (float) (accuracySum / accuracyCount);
    }

    private void finishSong(boolean failed) {
        if (!endSent && luaRuntime != null && !luaRuntime.onEndSong()) return;
        if (editorPlaytest) {
            endSent = true;
            if (!failed) phase = Phase.RESULTS;
            return;
        }
        if (!endSent) {
            endSent = true;
            PacketDistributor.sendToServer(new FnfPayloads.SongEndC2S(
                    machinePos, score, misses, accuracy(), failed));
            if (!failed) saveBestScore();
        }
        if (!failed) phase = Phase.RESULTS;
    }

    private void saveBestScore() {
        var r = new com.fnfmod.client.ScoreStore.Record();
        r.score = score;
        r.maxCombo = maxCombo;
        r.sick = judgements[0];
        r.good = judgements[1];
        r.bad = judgements[2];
        r.shit = judgements[3];
        r.missed = misses;
        r.totalNotes = totalMyNotes;
        if (!ClientSession.songId.isEmpty()) {
            int playMode = switch (mode) {
                case PLAYER -> 0;
                case OPPONENT -> 1;
                case BOTH -> 2;
            };
            com.fnfmod.client.ScoreStore.submit(
                    ClientSession.songId, ClientSession.difficulty, playMode, r);
        }
    }

    // ------------------------------------------------------------------ partner events

    public void onPartnerNote(int lane, byte judgement, int pCombo, int pScore) {
        partnerScore = pScore;
        partnerCombo = pCombo;
        if (lane < 0 || lane > 3) return;
        if (judgement < 4) {
            otherStrumFlash[lane] = 150;
            // consume the closest unhit note on their side so it disappears
            List<GameNote> list = otherLanes[lane];
            for (int i = otherLaneIndex[lane]; i < list.size(); i++) {
                GameNote n = list.get(i);
                if (n.hit || n.missed) continue;
                if (Math.abs(n.data.timeMs - songPos) < 250) n.hit = true;
                break;
            }
        }
        partnerLastSingMs = System.currentTimeMillis();
        // animate the partner's body with their chosen animation set
        if (partnerId != null && minecraft.level != null) {
            Player partner = minecraft.level.getPlayerByUUID(partnerId);
            if (partner != null) {
                float[] camOff = CharacterAnimations.play(partner, partnerAnimSet, partnerRole(),
                        judgement == 4 ? "miss" : DIR_NAMES[lane]);
                if (camOff != null && judgement != 4) {
                    GameplayCamera.sing(!myChartSideIsPlayer, camOff[0], camOff[1]);
                }
            }
        }
    }

    public void onPartnerEnd(int pScore, int pMisses, float pAccuracy, boolean failed) {
        partnerEnded = true;
        partnerFailed = failed;
        partnerEndScore = pScore;
        partnerEndMisses = pMisses;
        partnerEndAccuracy = pAccuracy;
    }

    public void abort(String reason) {
        GameplayCamera.end();
        songPlayer.dispose();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal(reason), false);
            CharacterAnimations.stop(minecraft.player);
        }
        minecraft.setScreen(null);
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (editorPreview && (keyCode == GLFW.GLFW_KEY_F12 || keyCode == GLFW.GLFW_KEY_ESCAPE)) {
            exit();
            return true;
        }
        boolean enter = keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER;
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            handleEscape();
            return true;
        }
        if (enter && enterReady && !duet && (phase == Phase.PLAYING || phase == Phase.COUNTDOWN)) {
            enterReady = false;
            pauseSong();
            return true;
        }
        if (phase == Phase.PAUSED) {
            if (keyCode == GLFW.GLFW_KEY_UP) pauseSelection = Math.max(0, pauseSelection - 1);
            if (keyCode == GLFW.GLFW_KEY_DOWN) pauseSelection = Math.min(pauseOptionCount() - 1, pauseSelection + 1);
            if (enter && enterReady) {
                enterReady = false;
                activatePauseOption();
            }
            return true;
        }
        if (phase == Phase.GAMEOVER) {
            if (keyCode == GLFW.GLFW_KEY_R && !duet) restart();
            if (enter || keyCode == GLFW.GLFW_KEY_BACKSPACE) exit();
            return true;
        }
        if (phase == Phase.RESULTS) {
            if (enter || keyCode == GLFW.GLFW_KEY_SPACE) exit();
            return true;
        }

        int lane = FnfKeys.laneForKey(keyCode, scanCode);
        if (lane >= 0 && !laneHeld[lane]) {
            laneHeld[lane] = true;
            // Judge input during the countdown too: notes at the very start of a chart
            // otherwise only get the half of their hit window after the song begins
            // (the earlier half falls during the countdown), which is an unfair miss.
            if (phase == Phase.PLAYING || phase == Phase.COUNTDOWN) {
                // a released hold in its grace window resumes on this press...
                boolean resumed = false;
                for (GameNote h : activeHolds[lane]) {
                    if (h.releasedMs >= 0 && songPos - h.releasedMs <= HOLD_RELEASE_GRACE_MS) {
                        h.releasedMs = -1;
                        resumed = true;
                    }
                }
                // ...and the same press can still hit an overlapping note. During the
                // countdown, empty presses don't ghost-miss so warm-up taps before the
                // song starts aren't punished.
                hitAttempt(lane, phase == Phase.PLAYING && !resumed);
                if (phase == Phase.COUNTDOWN) myStrumFlash[lane] = Math.max(myStrumFlash[lane], 40);
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            enterReady = true;
        }
        int lane = FnfKeys.laneForKey(keyCode, scanCode);
        if (lane >= 0) {
            laneHeld[lane] = false;
            return true;
        }
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    private void handleEscape() {
        switch (phase) {
            case PLAYING, COUNTDOWN -> {
                if (duet) {
                    exit();
                } else {
                    pauseSong();
                }
            }
            case PAUSED -> resumeFromPause();
            case GAMEOVER, RESULTS -> exit();
        }
    }

    private void pauseSong() {
        if (luaRuntime != null && !luaRuntime.onPause()) return;
        phase = Phase.PAUSED;
        pausedAtMs = System.currentTimeMillis();
        pauseSelection = 0;
        songPlayer.pause();
    }

    private void resumeFromPause() {
        if (songPlayer.isStarted()) {
            songPlayer.resume();
        } else {
            // still in countdown: push the start time forward by the paused duration
            startAtEpochMs += System.currentTimeMillis() - pausedAtMs;
        }
        phase = songPlayer.isStarted() ? Phase.PLAYING : Phase.COUNTDOWN;
        lastFrameNano = System.nanoTime();
        if (luaRuntime != null) luaRuntime.onResume();
    }

    private void activatePauseOption() {
        if (editorPlaytest) {
            switch (pauseSelection) {
                case 0 -> resumeFromPause();
                case 1 -> restart();
                case 2 -> exit();
            }
            return;
        }
        if (duet) {
            if (pauseSelection == 0) resumeFromPause();
            else exit();
            return;
        }
        switch (pauseSelection) {
            case 0 -> resumeFromPause();
            case 1 -> restart();
            case 2 -> openCurrentChartInEditor();
            case 3 -> exit();
        }
    }

    private int pauseOptionCount() {
        return editorPlaytest ? 3 : (duet ? 2 : 4);
    }

    private void openCurrentChartInEditor() {
        String currentSongId = ClientSession.songId == null || ClientSession.songId.isBlank()
                ? chart.title : ClientSession.songId;
        SongEntry sourceEntry = SongLibrary.get(currentSongId);
        Path originalDirectory = sourceEntry == null ? ClientSession.resolvedFolder
                : (sourceEntry.chartOriginRoot != null ? sourceEntry.chartOriginRoot
                : (sourceEntry.modRoot != null ? sourceEntry.modRoot : sourceEntry.folder));
        ChartEditorScreen editor = new ChartEditorScreen(currentSongId,
                ClientSession.difficulty, chart, ClientSession.resolvedFolder, originalDirectory, machinePos);

        GameplayCamera.end();
        PacketDistributor.sendToServer(new FnfPayloads.LeaveC2S(machinePos, false, false));
        ClientSession.reset();
        if (minecraft.player != null) CharacterAnimations.stop(minecraft.player);
        // setScreen removes this gameplay screen, which disposes its SongPlayer;
        // the editor loads its own audio streams from the captured song folder.
        minecraft.setScreen(editor);
    }

    private void restart() {
        // A restart is a fresh Psych song run. Destroy the old VM first so its
        // objects, timers, tweens and globals cannot leak into the next run.
        PsychLuaRuntime oldLua = luaRuntime;
        luaRuntime = null;
        if (oldLua != null) oldLua.close();

        // onCreate may edit unspawnNotes. Restore the parsed chart before the
        // new VM runs onCreate again on the next logic tick.
        chart.notes.clear();
        originalLuaNotes.stream().map(SongChart.Note::copy).forEach(chart.notes::add);
        chart.sortNotes();

        songPlayer.reset();
        songPlayer.setPlaybackRate(1f);
        songPlayer.setPlayerVoiceVolume(1f);
        for (int i = 0; i < 4; i++) {
            for (GameNote n : myLanes[i]) { n.hit = false; n.missed = false; n.holdDropped = false; n.holdComplete = false; n.releasedMs = -1; }
            for (GameNote n : otherLanes[i]) { n.hit = false; n.missed = false; n.holdDropped = false; n.holdComplete = false; n.releasedMs = -1; }
            myLaneIndex[i] = 0;
            otherLaneIndex[i] = 0;
            activeHolds[i].clear();
            myStrumFlash[i] = 0;
            otherStrumFlash[i] = 0;
            laneHeld[i] = false;
        }
        Arrays.fill(luaStrumX, Double.NaN);
        Arrays.fill(luaStrumY, Double.NaN);
        Arrays.fill(luaStrumAlpha, 1.0);
        Arrays.fill(luaStrumAngle, 0.0);
        score = 0; combo = 0; misses = 0; maxCombo = 0;
        java.util.Arrays.fill(judgements, 0);
        accuracySum = 0; accuracyCount = 0;
        health = 1f;
        popups.clear();
        splashes.clear();
        coverEnds.clear();
        endSent = false;
        voicesMutedUntil = -1;
        songPlayer.setOpponentVoiceVolume(1f);
        eventIndex = 0;
        preSongEventsProcessed = false;
        lastBeat = -1;
        lastSingMs = 0;
        partnerLastSingMs = 0;
        Arrays.fill(lastHoldSingMs, 0);
        camSection = -1; // re-evaluate camera focus from the top of the chart
        cameraFocusOverride = null;
        GameplayCamera.resetSongState();
        startAtEpochMs = System.currentTimeMillis() + 2000;
        if (editorPlaytest) prepareEditorStart();
        phase = Phase.COUNTDOWN;
        lastFrameNano = System.nanoTime();
    }

    private void exit() {
        GameplayCamera.end();
        if (editorPlaytest) {
            songPlayer.dispose();
            if (minecraft.player != null) CharacterAnimations.stop(minecraft.player);
            minecraft.setScreen(editorReturnFactory == null ? null : editorReturnFactory.get());
            return;
        }
        // Solo play returns to the song menu instead of the world (on finish, quit, or death).
        // Duet keeps returning to the world to avoid host/guest contention over one machine.
        boolean reopenMenu = !duet;
        // finishedOnly = the song ended normally (server already tore the session down on SongEnd);
        // otherwise the server cancels the still-active session. Either way it then reopens the menu.
        PacketDistributor.sendToServer(new FnfPayloads.LeaveC2S(machinePos, endSent, reopenMenu));
        ClientSession.reset();
        songPlayer.dispose();
        if (minecraft.player != null) CharacterAnimations.stop(minecraft.player);
        minecraft.setScreen(reopenMenu
                ? new WaitingScreen(Component.literal("Returning to song list..."))
                : null);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (phase == Phase.PAUSED) {
            int idx = pauseOptionAt(mouseY);
            if (idx >= 0) {
                pauseSelection = idx;
                activatePauseOption();
            }
            return true;
        }
        if (phase == Phase.RESULTS || phase == Phase.GAMEOVER) {
            exit();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private int pauseOptionAt(double mouseY) {
        int count = pauseOptionCount();
        int startY = height / 2 - 10;
        for (int i = 0; i < count; i++) {
            int y = startY + i * 16;
            if (mouseY >= y - 2 && mouseY < y + 12) return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------ render

    private void addPopup(String text, int color) {
        popups.add(new Popup(text, color, System.currentTimeMillis()));
        if (popups.size() > 8) popups.remove(0);
    }

    /** FNF-like proportions: notes are ~13% of screen height. */
    private float noteSize() {
        float byHeight = Math.max(24f, Math.min(64f, height * 0.13f));
        return Math.min(byHeight, width / 13f);
    }

    private float laneX(boolean mine, int lane) {
        boolean playerSide = playBoth || mine == myChartSideIsPlayer;
        double overridden = luaStrumX[(playerSide ? 4 : 0) + lane];
        if (!Double.isNaN(overridden)) return (float) overridden;
        return baseLaneX(mine, lane);
    }

    private float baseLaneX(boolean mine, int lane) {
        float spacing = noteSize() * 1.12f;
        if (playBoth) {
            return width * 0.5f + (lane - 1.5f) * spacing;
        }
        if (ClientOptions.get().middlescroll) {
            if (mine) {
                return width * 0.5f + (lane - 1.5f) * spacing;
            }
            // opponent notes split to the screen edges (Psych middlescroll style)
            float edgeSpacing = spacing * 1.05f;
            return lane < 2
                    ? width * 0.07f + lane * edgeSpacing
                    : width * 0.93f - (3 - lane) * edgeSpacing;
        }
        // FNF layout is fixed: player-side notes right, opponent-side left —
        // playing as the opponent doesn't swap the strumlines
        boolean rightSide = mine == myChartSideIsPlayer;
        float center = rightSide ? width * 0.73f : width * 0.27f;
        return center + (lane - 1.5f) * spacing;
    }

    /** Center x of the strumline the local player actually plays on. */
    private float myStrumsCenterX() {
        return playBoth ? width * 0.5f : (myChartSideIsPlayer ? width * 0.73f : width * 0.27f);
    }

    private float receptorY() {
        float margin = noteSize() * 0.65f + 14;
        return ClientOptions.get().downscroll ? height - margin : margin;
    }

    private float laneY(boolean mine, int lane) {
        boolean playerSide = playBoth || mine == myChartSideIsPlayer;
        double overridden = luaStrumY[(playerSide ? 4 : 0) + lane];
        return Double.isNaN(overridden) ? receptorY() : (float) overridden;
    }

    // ------------------------------------------------------------------ Psych Lua bridge

    public int psychLuaScreenWidth() { return PsychLuaRuntime.VIRTUAL_WIDTH; }
    public int psychLuaScreenHeight() { return PsychLuaRuntime.VIRTUAL_HEIGHT; }
    public double psychLuaSongLength() { return songPlayer.durationMs(); }
    public double psychLuaSongPosition() { return songPos; }
    public double psychLuaBeat() { return conductor.beatAt(Math.max(0, songPos)); }
    public int psychLuaSection() { return Math.max(0, camSection); }
    public boolean psychLuaSongStarted() { return songPlayer.isStarted(); }
    public int psychLuaScore() { return score; }
    public int psychLuaMisses() { return misses; }
    public int psychLuaCombo() { return combo; }
    public double psychLuaHealth() { return health; }
    public boolean psychLuaMustHit() {
        int section = Math.max(0, Math.min(psychLuaSection(), secFocusPlayer.length - 1));
        return secFocusPlayer[section];
    }
    public void psychLuaSetHealth(double value) { health = (float) Math.max(0, Math.min(2, value)); }
    public void psychLuaAddScore(int value) { score += value; }
    public void psychLuaSetScore(int value) { score = value; }
    public void psychLuaAddMisses(int value) { misses = Math.max(0, misses + value); }
    public void psychLuaSetMisses(int value) { misses = Math.max(0, value); }

    public double psychLuaStrumX(boolean playerSide, int lane) {
        int safeLane = Math.max(0, Math.min(3, lane));
        int index = (playerSide ? 4 : 0) + safeLane;
        if (!Double.isNaN(luaStrumX[index])) return luaStrumX[index];
        boolean mine = playBoth || playerSide == myChartSideIsPlayer;
        return baseLaneX(mine, safeLane);
    }

    public double psychLuaStrumY(boolean playerSide, int lane) {
        int index = (playerSide ? 4 : 0) + Math.max(0, Math.min(3, lane));
        return Double.isNaN(luaStrumY[index]) ? receptorY() : luaStrumY[index];
    }

    private int luaGroupIndex(String group, int index) {
        if (group == null) return -1;
        return switch (group.toLowerCase(java.util.Locale.ROOT)) {
            case "strumlinenotes" -> index;
            case "playerstrums" -> index + 4;
            case "opponentstrums" -> index;
            default -> -1;
        };
    }

    public Object psychLuaGetGroup(String group, int index, String property) {
        if (group != null && (group.equalsIgnoreCase("unspawnNotes") || group.equalsIgnoreCase("notes"))) {
            if (index < 0 || index >= chart.notes.size()) return null;
            SongChart.Note note = chart.notes.get(index);
            GameNote gameNote = findGameNote(note);
            return switch (property == null ? "" : property) {
                case "strumTime" -> note.timeMs;
                case "noteData" -> note.lane;
                case "mustPress" -> note.playerSide;
                case "sustainLength" -> note.sustainMs;
                case "noteType" -> note.noteType;
                case "isSustainNote" -> note.sustainMs > 30;
                case "gfNote" -> note.gfNote;
                case "altAnim" -> note.altAnim;
                case "texture" -> note.texture;
                case "animSuffix" -> note.animSuffix;
                case "hitsound" -> note.hitsound;
                case "noteSplashData.texture" -> note.noteSplashTexture;
                case "ignoreNote" -> note.ignoreNote;
                case "hitCausesMiss" -> note.hitCausesMiss;
                case "noAnimation" -> note.noAnimation;
                case "noMissAnimation" -> note.noMissAnimation;
                case "blockHit" -> note.blockHit;
                case "lowPriority" -> note.lowPriority;
                case "visible" -> note.visible;
                case "ratingDisabled" -> note.ratingDisabled;
                case "hitsoundDisabled" -> note.hitsoundDisabled;
                case "noteSplashData.disabled" -> note.noteSplashDisabled;
                case "multAlpha" -> note.multAlpha;
                case "multSpeed" -> note.multSpeed;
                case "hitHealth" -> note.hitHealth;
                case "missHealth" -> note.missHealth;
                case "alpha" -> note.alpha;
                case "angle" -> note.angle;
                case "offsetX" -> note.offsetX;
                case "offsetY" -> note.offsetY;
                case "offsetAngle" -> note.offsetAngle;
                case "scale.x" -> note.scaleX;
                case "scale.y" -> note.scaleY;
                case "earlyHitMult" -> note.earlyHitMult;
                case "lateHitMult" -> note.lateHitMult;
                case "hitsoundVolume" -> note.hitsoundVolume;
                case "noteSplashData.a" -> note.noteSplashAlpha;
                case "wasGoodHit" -> gameNote != null && gameNote.hit;
                case "tooLate", "missed" -> gameNote != null && gameNote.missed;
                case "canBeHit" -> gameNote != null && canHitAt(gameNote, liveSongPos());
                default -> null;
            };
        }
        int i = luaGroupIndex(group, index);
        if (i < 0 || i >= 8) return null;
        boolean playerSide = i >= 4;
        int lane = i & 3;
        return switch (property == null ? "" : property) {
            case "x" -> psychLuaStrumX(playerSide, lane);
            case "y" -> psychLuaStrumY(playerSide, lane);
            case "alpha" -> luaStrumAlpha[i];
            case "angle" -> luaStrumAngle[i];
            case "direction" -> 90.0;
            case "visible" -> luaStrumAlpha[i] > 0;
            default -> null;
        };
    }

    public void psychLuaSetGroup(String group, int index, String property, Object value) {
        if (group != null && (group.equalsIgnoreCase("unspawnNotes") || group.equalsIgnoreCase("notes"))) {
            if (index < 0 || index >= chart.notes.size()) return;
            SongChart.Note note = chart.notes.get(index);
            switch (property == null ? "" : property) {
                case "strumTime" -> note.timeMs = noteNumber(value, note.timeMs);
                case "noteData" -> note.lane = Mth.clamp((int) noteNumber(value, note.lane), 0, 3);
                case "mustPress" -> note.playerSide = noteBool(value, note.playerSide);
                case "sustainLength" -> note.sustainMs = Math.max(0, noteNumber(value, note.sustainMs));
                case "noteType" -> note.noteType = value == null ? "" : String.valueOf(value);
                case "altAnim" -> note.altAnim = noteBool(value, note.altAnim);
                case "gfNote" -> note.gfNote = noteBool(value, note.gfNote);
                case "texture" -> note.texture = value == null ? "" : String.valueOf(value);
                case "animSuffix" -> note.animSuffix = value == null ? "" : String.valueOf(value);
                case "hitsound" -> note.hitsound = value == null ? "hitsound" : String.valueOf(value);
                case "noteSplashData.texture" -> note.noteSplashTexture = value == null ? "" : String.valueOf(value);
                case "ignoreNote" -> note.ignoreNote = noteBool(value, note.ignoreNote);
                case "hitCausesMiss" -> note.hitCausesMiss = noteBool(value, note.hitCausesMiss);
                case "noAnimation" -> note.noAnimation = noteBool(value, note.noAnimation);
                case "noMissAnimation" -> note.noMissAnimation = noteBool(value, note.noMissAnimation);
                case "blockHit" -> note.blockHit = noteBool(value, note.blockHit);
                case "lowPriority" -> note.lowPriority = noteBool(value, note.lowPriority);
                case "visible" -> note.visible = noteBool(value, note.visible);
                case "ratingDisabled" -> note.ratingDisabled = noteBool(value, note.ratingDisabled);
                case "hitsoundDisabled" -> note.hitsoundDisabled = noteBool(value, note.hitsoundDisabled);
                case "noteSplashData.disabled" -> note.noteSplashDisabled = noteBool(value, note.noteSplashDisabled);
                case "multAlpha" -> note.multAlpha = Math.max(0, noteNumber(value, note.multAlpha));
                case "multSpeed" -> note.multSpeed = Math.max(0.01, noteNumber(value, note.multSpeed));
                case "hitHealth" -> note.hitHealth = Math.max(0, noteNumber(value, note.hitHealth));
                case "missHealth" -> note.missHealth = Math.max(0, noteNumber(value, note.missHealth));
                case "alpha" -> note.alpha = Math.max(0, noteNumber(value, note.alpha));
                case "angle" -> note.angle = noteNumber(value, note.angle);
                case "offsetX" -> note.offsetX = noteNumber(value, note.offsetX);
                case "offsetY" -> note.offsetY = noteNumber(value, note.offsetY);
                case "offsetAngle" -> note.offsetAngle = noteNumber(value, note.offsetAngle);
                case "scale.x" -> note.scaleX = noteNumber(value, note.scaleX);
                case "scale.y" -> note.scaleY = noteNumber(value, note.scaleY);
                case "earlyHitMult" -> note.earlyHitMult = Math.max(0, noteNumber(value, note.earlyHitMult));
                case "lateHitMult" -> note.lateHitMult = Math.max(0, noteNumber(value, note.lateHitMult));
                case "hitsoundVolume" -> note.hitsoundVolume = Math.max(0, noteNumber(value, note.hitsoundVolume));
                case "noteSplashData.a" -> note.noteSplashAlpha = Math.max(0, noteNumber(value, note.noteSplashAlpha));
                default -> { }
            }
            return;
        }
        int i = luaGroupIndex(group, index);
        if (i < 0 || i >= 8 || !(value instanceof Number number)) return;
        switch (property == null ? "" : property) {
            case "x" -> luaStrumX[i] = number.doubleValue();
            case "y" -> luaStrumY[i] = number.doubleValue();
            case "alpha" -> luaStrumAlpha[i] = Math.max(0, Math.min(1, number.doubleValue()));
            case "angle" -> luaStrumAngle[i] = number.doubleValue();
            default -> { }
        }
    }

    private static double noteNumber(Object value, double fallback) {
        if (value instanceof Number number) return number.doubleValue();
        try { return Double.parseDouble(String.valueOf(value)); }
        catch (Exception ignored) { return fallback; }
    }

    private static boolean noteBool(Object value, boolean fallback) {
        if (value instanceof Boolean bool) return bool;
        if (value instanceof Number number) return number.doubleValue() != 0;
        if (value != null) {
            if (String.valueOf(value).equalsIgnoreCase("true")) return true;
            if (String.valueOf(value).equalsIgnoreCase("false")) return false;
        }
        return fallback;
    }

    public Object psychLuaGetProperty(String path) {
        if (path == null) return null;
        return switch (path) {
            case "health" -> (double) health;
            case "songScore", "score" -> score;
            case "songMisses", "misses" -> misses;
            case "combo" -> combo;
            case "songPosition" -> songPos;
            case "playbackRate" -> (double) songPlayer.playbackRate();
            case "songSpeed" -> chart.speed;
            case "mustHitSection" -> psychLuaMustHit();
            case "curSection" -> psychLuaSection();
            case "camGame.zoom", "camHUD.zoom" -> 1.0;
            case "unspawnNotes.length", "notes.length" -> chart.notes.size();
            default -> null;
        };
    }

    public boolean psychLuaSetProperty(String path, Object value) {
        if (path == null) return false;
        double number = value instanceof Number n ? n.doubleValue() : 0;
        switch (path) {
            case "health" -> psychLuaSetHealth(number);
            case "songScore", "score" -> psychLuaSetScore((int) number);
            case "songMisses", "misses" -> psychLuaSetMisses((int) number);
            case "combo" -> combo = Math.max(0, (int) number);
            case "playbackRate" -> songPlayer.setPlaybackRate((float) number);
            case "camGame.zoom" -> GameplayCamera.zoomTo((float) (number - 1), "snap");
            default -> { return false; }
        }
        return true;
    }

    public void psychLuaTriggerEvent(String name, String value1, String value2) {
        executeEvent(-1, new SongChart.Event(songPos, name, value1, value2));
    }

    public void psychLuaCameraTarget(String target) {
        if (target == null || target.isBlank()) return;
        boolean player = target.equalsIgnoreCase("bf") || target.equalsIgnoreCase("boyfriend")
                || target.equalsIgnoreCase("player");
        cameraFocusOverride = player;
        GameplayCamera.focus(player, "smooth", 500);
    }

    public void psychLuaEndSong() { finishSong(false); }
    public void psychLuaRestartSong() { restart(); }
    public void psychLuaExitSong() { exit(); }

    public void reloadLuaFonts() {
        if (luaRuntime != null) luaRuntime.reloadFonts();
    }

    private float noteY(double timeMs, boolean mine, int lane, GameNote note) {
        double speed = note == null ? 1.0 : Math.max(0.01, note.data.multSpeed);
        double dist = (timeMs - songPos) * pxPerMs() * speed;
        float receptor = laneY(mine, lane);
        return (float) (ClientOptions.get().downscroll ? receptor - dist : receptor + dist);
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        logic();
        // no background dimming — the world stays fully visible during a song

        float noteSize = noteSize();
        boolean down = ClientOptions.get().downscroll;

        boolean fadeOpponent = ClientOptions.get().middlescroll && !playBoth;

        if (luaRuntime != null) luaRuntime.render(gui, false);

        // receptors (single centered strumline in BOTH mode)
        for (int lane = 0; lane < 4; lane++) {
            int myState = myStrumFlash[lane] > 0 ? 2 : (laneHeld[lane] ? 1 : 0);
            NoteStyle.setDrawAlpha((float) luaStrumAlpha[(myChartSideIsPlayer || playBoth ? 4 : 0) + lane]);
            NoteStyle.drawReceptor(gui, lane, laneX(true, lane), laneY(true, lane), noteSize, myState);
        }
        NoteStyle.setDrawAlpha(1f);
        if (!playBoth) {
            if (fadeOpponent) NoteStyle.setDrawAlpha(0.6f);
            for (int lane = 0; lane < 4; lane++) {
                int otherState = otherStrumFlash[lane] > 0 ? 2 : 0;
                int side = myChartSideIsPlayer ? 0 : 4;
                NoteStyle.setDrawAlpha((float) (luaStrumAlpha[side + lane] * (fadeOpponent ? 0.6 : 1)));
                NoteStyle.drawReceptor(gui, lane, laneX(false, lane), laneY(false, lane), noteSize, otherState);
            }
            if (fadeOpponent) NoteStyle.setDrawAlpha(1f);
        }

        // notes
        double visibleMs = (height + 100) / pxPerMs();
        renderNotes(gui, myLanes, myLaneIndex, true, noteSize, visibleMs);
        if (!playBoth) {
            if (fadeOpponent) NoteStyle.setDrawAlpha(0.6f);
            renderNotes(gui, otherLanes, otherLaneIndex, false, noteSize, visibleMs);
            if (fadeOpponent) NoteStyle.setDrawAlpha(1f);
        }

        // hit splashes over the receptors
        long nowMs = System.currentTimeMillis();
        for (var it = splashes.iterator(); it.hasNext(); ) {
            Splash s = it.next();
            int frame = (int) ((nowMs - s.bornMs) * SPLASH_FPS / 1000.0);
            int customFrames = customNoteTextures.splashFrames(s.texture, s.lane, s.variant);
            int total = customFrames > 0 ? customFrames : NoteStyle.splashFrameCount(s.lane, s.variant);
            if (total <= 0 || frame >= total) {
                it.remove();
                continue;
            }
            NoteStyle.setDrawAlpha(s.alpha);
            if (customFrames <= 0 || !customNoteTextures.drawSplash(gui, s.texture, s.lane, s.variant,
                    frame, s.x, s.y, noteSize * 2.2f)) {
                NoteStyle.drawSplash(gui, s.lane, s.variant, frame, s.x, s.y, noteSize * 2.2f);
            }
        }
        NoteStyle.setDrawAlpha(1f);

        // hold covers: looping effect over the receptor while a sustain is held
        for (int lane = 0; lane < 4; lane++) {
            if (!activeHolds[lane].isEmpty() && laneHeld[lane] && NoteStyle.hasHoldCover(lane)) {
                // Epoch milliseconds at 24 FPS exceeds int range and used to clamp
                // at Integer.MAX_VALUE, selecting the same atlas frame forever.
                long frame = (long) (nowMs * SPLASH_FPS / 1000.0);
                NoteStyle.drawHoldCover(gui, lane, frame, laneX(true, lane), laneY(true, lane), noteSize);
            }
        }
        // one-shot burst when a sustain finishes cleanly
        for (var it = coverEnds.iterator(); it.hasNext(); ) {
            CoverEnd c = it.next();
            int frame = (int) ((nowMs - c.bornMs) * SPLASH_FPS / 1000.0);
            int total = NoteStyle.holdCoverEndFrames(c.lane);
            if (total <= 0 || frame >= total) {
                it.remove();
                continue;
            }
            NoteStyle.drawHoldCoverEnd(gui, c.lane, frame, c.x, c.y, noteSize);
        }

        renderHud(gui, noteSize);
        if (luaRuntime != null) luaRuntime.render(gui, true);

        switch (phase) {
            case COUNTDOWN -> renderCountdown(gui);
            case PAUSED -> renderPause(gui);
            case GAMEOVER -> renderGameOver(gui);
            case RESULTS -> renderResults(gui);
            default -> {}
        }
    }

    private void renderNotes(GuiGraphics gui, List<GameNote>[] lanes, int[] laneStart,
                             boolean mine, float noteSize, double visibleMs) {
        boolean down = ClientOptions.get().downscroll;
        for (int lane = 0; lane < 4; lane++) {
            float x = laneX(mine, lane);
            boolean playerSide = playBoth || mine == myChartSideIsPlayer;
            double layoutAlpha = ClientOptions.get().middlescroll && !playBoth && !mine ? 0.6 : 1.0;
            double laneAlpha = luaStrumAlpha[(playerSide ? 4 : 0) + lane] * layoutAlpha;
            List<GameNote> list = lanes[lane];
            // sweepMisses advances laneStart past a note the moment it's missed, so
            // back up over any missed/dropped long notes whose grey trail is still on-screen
            // (drawn until 200ms after the note's end) — otherwise they'd just vanish.
            int start = laneStart[lane];
            while (start > 0) {
                GameNote prev = list.get(start - 1);
                if ((prev.missed || prev.holdDropped) && prev.data.sustainMs > 30
                        && songPos - prev.endMs() <= 200) start--;
                else break;
            }
            for (int i = start; i < list.size(); i++) {
                GameNote n = list.get(i);
                if (n.data.timeMs - songPos > visibleMs / Math.max(0.01, n.data.multSpeed)) continue;
                if ((n.missed || n.holdDropped) && songPos - n.endMs() > 200) continue;
                if (!n.data.visible || n.data.alpha <= 0 || n.data.multAlpha <= 0) continue;
                NoteStyle.setDrawAlpha((float) (laneAlpha * n.data.alpha * n.data.multAlpha));

                boolean beingHeld = activeHolds[lane].contains(n);
                if (n.hit && !beingHeld && n.data.sustainMs <= 30) continue;
                if (n.hit && !beingHeld && (n.holdComplete || songPos > n.endMs())) continue;
                // Fully missed and dropped long notes share the gray/translucent trail style.
                boolean missedLong = (n.missed || n.holdDropped) && n.data.sustainMs > 30;
                if (missedLong) NoteStyle.setMissed(true);

                // sustain trail (missed long notes still show the remaining gray trail)
                if (n.data.sustainMs > 30 && (!n.holdDropped || missedLong)) {
                    double from = beingHeld || n.hit ? songPos : n.data.timeMs;
                    float y1 = noteY(from, mine, lane, n);
                    float y2 = noteY(n.endMs(), mine, lane, n);
                    float top = Math.min(y1, y2), bottom = Math.max(y1, y2);
                    float center = (top + bottom) * 0.5f;
                    float half = (bottom - top) * 0.5f * (float) Math.max(0.01, n.data.scaleY);
                    top = center - half + (float) n.data.offsetY;
                    bottom = center + half + (float) n.data.offsetY;
                    float drawX = x + (float) n.data.offsetX;
                    boolean custom = customNoteTextures.drawHold(gui, n.data.texture, lane, drawX,
                            top, bottom, noteSize * (float) Math.max(0.01, n.data.scaleX), down);
                    if (!custom) NoteStyle.drawHoldPiece(gui, lane, drawX, top, bottom,
                            noteSize * (float) Math.max(0.01, n.data.scaleX), down);
                }

                // A dropped hold already had its head hit; only restore the head when
                // the entire long note was missed from the start.
                if (!n.hit && (!n.missed || missedLong)) {
                    float y = noteY(n.data.timeMs, mine, lane, n);
                    if (y > -noteSize && y < height + noteSize) {
                        gui.pose().pushPose();
                        gui.pose().translate(x + n.data.offsetX, y + n.data.offsetY, 0);
                        gui.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(
                                (float) (n.data.angle + n.data.offsetAngle)));
                        gui.pose().scale((float) Math.max(0.01, n.data.scaleX),
                                (float) Math.max(0.01, n.data.scaleY), 1);
                        if (!customNoteTextures.drawNote(gui, n.data.texture, lane, 0, 0, noteSize)) {
                            NoteStyle.drawNote(gui, lane, 0, 0, noteSize);
                        }
                        gui.pose().popPose();
                        if (!missedLong && "Hurt Note".equals(n.data.noteType)) {
                            gui.drawCenteredString(font, "!", (int) (x + n.data.offsetX),
                                    (int) (y + n.data.offsetY) - 4, 0xFFFF0000);
                        }
                    }
                }
                if (missedLong) NoteStyle.setMissed(false);
            }
        }
        NoteStyle.setDrawAlpha(1f);
    }

    private static final net.minecraft.resources.ResourceLocation XP_BAR_BACKGROUND =
            net.minecraft.resources.ResourceLocation.withDefaultNamespace("hud/experience_bar_background");
    private static final net.minecraft.resources.ResourceLocation XP_BAR_PROGRESS =
            net.minecraft.resources.ResourceLocation.withDefaultNamespace("hud/experience_bar_progress");

    private static net.minecraft.resources.ResourceLocation vanilla(String p) {
        return net.minecraft.resources.ResourceLocation.withDefaultNamespace(p);
    }
    private static final net.minecraft.resources.ResourceLocation FOOD_EMPTY = vanilla("hud/food_empty");
    private static final net.minecraft.resources.ResourceLocation FOOD_HALF = vanilla("hud/food_half");
    private static final net.minecraft.resources.ResourceLocation FOOD_FULL = vanilla("hud/food_full");
    private static final net.minecraft.resources.ResourceLocation HEART_CONTAINER = vanilla("hud/heart/container");
    private static final net.minecraft.resources.ResourceLocation HEART_FULL = vanilla("hud/heart/full");
    private static final net.minecraft.resources.ResourceLocation HEART_HALF = vanilla("hud/heart/half");

    private boolean down() { return ClientOptions.get().downscroll; }

    // HUD cluster positions. Upscroll = above the bottom hotbar (unchanged).
    // Downscroll = below a hotbar flush at the top of the screen (mirror order).
    /** Hearts/hunger row. */
    private int iconRowY() { return down() ? 24 : height - 39; }
    /** XP-style bar row. */
    private int xpBarY() { return down() ? 34 : height - 29; }
    /** The XP level number / score text always sits just above the bar (both scroll directions). */
    private int numberY(int barY) { return barY - 9; }

    private void renderHud(GuiGraphics gui, float noteSize) {
        String style = ClientOptions.effectiveHudStyle();
        switch (style) {
            case "fnf" -> renderFnfHud(gui);
            case "vanilla" -> renderVanillaHud(gui);
            default -> renderTextHud(gui, style);
        }
        renderCommonHud(gui);
    }

    private String scoreLine(String style) {
        String acc = String.format("%.2f", accuracy() * 100) + "%";
        return switch (style) {
            case "abbreviated" -> "S: " + score + "  M: " + misses + "  Acc: " + acc;
            case "numbers" -> score + "   " + misses + "   " + acc;
            default -> "Score: " + score + "   Misses: " + misses + "   Accuracy: " + acc;
        };
    }

    private void outlinedCentered(GuiGraphics gui, String line, int cx, int y, int color) {
        int tx = cx - font.width(line) / 2;
        gui.drawString(font, line, tx + 1, y, 0, false);
        gui.drawString(font, line, tx - 1, y, 0, false);
        gui.drawString(font, line, tx, y + 1, 0, false);
        gui.drawString(font, line, tx, y - 1, 0, false);
        gui.drawString(font, line, tx, y, color, false);
    }

    /** default / abbreviated / numbers: XP-style bar + centered green outlined text. */
    private void renderTextHud(GuiGraphics gui, String style) {
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        int barX = width / 2 - 91;
        int barY = xpBarY();
        gui.blitSprite(XP_BAR_BACKGROUND, barX, barY, 182, 5);
        int fill = (int) (Math.min(1f, health / 2f) * 183.0f);
        if (fill > 0) gui.blitSprite(XP_BAR_PROGRESS, 182, 5, 0, 0, barX, barY, fill, 5);
        outlinedCentered(gui, scoreLine(style), width / 2, numberY(barY), 0x80FF20);
    }

    /** vanilla: real hearts (health), replica XP bar (misses), replica hunger (accuracy). */
    private void renderVanillaHud(GuiGraphics gui) {
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        // hearts driven by the player's real (server-synced) health
        float hp = minecraft.player != null ? minecraft.player.getHealth() : 20f;
        int halfHearts = Math.round(hp);
        int hy = iconRowY();
        int hleft = width / 2 - 91;
        for (int i = 0; i < 10; i++) {
            int x = hleft + i * 8;
            gui.blitSprite(HEART_CONTAINER, x, hy, 9, 9);
            int rel = halfHearts - i * 2;
            if (rel >= 2) gui.blitSprite(HEART_FULL, x, hy, 9, 9);
            else if (rel == 1) gui.blitSprite(HEART_HALF, x, hy, 9, 9);
        }

        // hunger (accuracy) on the right
        int foodUnits = Math.round(accuracy() * 20f);
        int right = width / 2 + 91;
        for (int i = 0; i < 10; i++) {
            int x = right - i * 8 - 9;
            gui.blitSprite(FOOD_EMPTY, x, hy, 9, 9);
            int rel = foodUnits - i * 2;
            if (rel >= 2) gui.blitSprite(FOOD_FULL, x, hy, 9, 9);
            else if (rel == 1) gui.blitSprite(FOOD_HALF, x, hy, 9, 9);
        }

        // XP bar (misses) with the miss count as the level number
        int barX = width / 2 - 91;
        int barY = xpBarY();
        gui.blitSprite(XP_BAR_BACKGROUND, barX, barY, 182, 5);
        int fill = (int) ((misses % 10) / 10.0f * 183.0f);
        if (fill > 0) gui.blitSprite(XP_BAR_PROGRESS, 182, 5, 0, 0, barX, barY, fill, 5);
        outlinedCentered(gui, String.valueOf(misses), width / 2, numberY(barY), 0x80FF20);
    }

    /** fnf: Psych-style health bar with icons + score text (all vanilla GUI hidden). */
    private void renderFnfHud(GuiGraphics gui) {
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        int barW = Math.min(320, width / 2);
        int barX = width / 2 - barW / 2;
        // downscroll: midway up — high enough to feel top-anchored, low enough that icons don't clip
        int barY = down() ? 28 : height - 40;
        int barH = 8;

        // green = player health (right), red = opponent (left); split at health/2
        float frac = Math.min(1f, Math.max(0f, health / 2f));
        int split = barX + (int) (barW * (1 - frac));

        String playerIcon = ClientOptions.get().playerIcon;
        String botIcon = ClientOptions.get().botIcon;
        // bar side colors come from each character.json when available
        int oppColor = colorOr(com.fnfmod.client.render.IconLibrary.barColor(botIcon), 0xCC2233);
        int plColor = colorOr(com.fnfmod.client.render.IconLibrary.barColor(playerIcon), 0x33CC33);
        gui.fill(barX - 2, barY - 2, barX + barW + 2, barY + barH + 2, 0xFF000000);
        gui.fill(barX, barY, split, barY + barH, 0xFF000000 | oppColor);
        gui.fill(split, barY, barX + barW, barY + barH, 0xFF000000 | plColor);

        // per-beat bop with an OutCirc ease; bigger icons sitting close together
        float beatFrac = (float) (conductor.beatAt(Math.max(0, songPos)) % 1.0);
        if (beatFrac < 0) beatFrac += 1;
        float t = Math.min(1f, beatFrac * 2f);          // 0..1 over the first half-beat
        float outCirc = (float) Math.sqrt(1f - (t - 1f) * (t - 1f));
        float bop = (1f - outCirc) * 0.30f;             // snappy pop, eases out
        float iconSize = 64f * (1f + bop);
        int iconY = barY + barH / 2;
        float gap = iconSize * 0.34f;                    // icons close to the split
        if (botIcon != null && !botIcon.isEmpty()) {
            int frame = frac > 0.8f ? 1 : 0;
            com.fnfmod.client.render.IconLibrary.draw(gui, botIcon, frame,
                    split - gap, iconY, iconSize, false);
        }
        if (playerIcon != null && !playerIcon.isEmpty()) {
            int frame = frac < 0.2f ? 1 : 0;
            com.fnfmod.client.render.IconLibrary.draw(gui, playerIcon, frame,
                    split + gap, iconY, iconSize, true);
        }

        String line = "Score: " + score + "   Misses: " + misses
                + "   Accuracy: " + String.format("%.2f", accuracy() * 100) + "%";
        int ty = down() ? barY + barH + 22 : barY + barH + 22;
        gui.drawCenteredString(font, line, width / 2, ty, 0xFFFFFFFF);
    }

    private static int colorOr(int rgb, int fallback) {
        return rgb >= 0 ? rgb : fallback;
    }

    private void renderCommonHud(GuiGraphics gui) {
        // song progress bar + title: top on upscroll, bottom on downscroll
        boolean down = down();
        int progY = down ? height - 2 : 0;
        int titleY = down ? height - 12 : 6;
        if (songPlayer.isStarted() && songPlayer.durationMs() > 0) {
            float frac = (float) Math.min(1, Math.max(0, songPos / songPlayer.durationMs()));
            gui.fill(0, progY, (int) (width * frac), progY + 2, 0xFFDD44AA);
        }
        gui.drawCenteredString(font, chart.title, width / 2, titleY, 0x99FFFFFF);

        // partner info
        if (duet) {
            gui.drawString(font, partnerName, 8, 16, 0xFFFFAAEE);
            gui.drawString(font, "Score: " + partnerScore + "  Combo: " + partnerCombo, 8, 27, 0xFFCCCCCC);
        }

        // rating popups (position from the user's Rating Position setting, or default)
        var opts = ClientOptions.get();
        float baseX = opts.ratingX >= 0 ? (float) (opts.ratingX * width)
                : (playBoth || opts.middlescroll ? width * 0.75f : myStrumsCenterX());
        float baseY = opts.ratingY >= 0 ? (float) (opts.ratingY * height) : height * 0.4f;
        long now = System.currentTimeMillis();
        popups.removeIf(p -> now - p.bornMs > 700);
        for (Popup p : popups) {
            float age = (now - p.bornMs) / 700f;
            int alpha = (int) (255 * (1 - age));
            if (alpha <= 8) continue;
            int color = (alpha << 24) | (p.color & 0xFFFFFF);
            int y = (int) (baseY - age * 18);
            gui.drawCenteredString(font, p.text, (int) baseX, y, color);
        }
    }

    private void renderCountdown(GuiGraphics gui) {
        double remaining = -songPos;
        if (remaining <= 0) return;
        String text = remaining > 3000 ? "Ready..." : String.valueOf((int) Math.ceil(remaining / 1000.0));
        gui.pose().pushPose();
        gui.pose().translate(width / 2f, height / 2f, 0);
        gui.pose().scale(3f, 3f, 1f);
        gui.drawCenteredString(font, text, 0, -4, 0xFFFFFF);
        gui.pose().popPose();
    }

    private void renderPause(GuiGraphics gui) {
        gui.fill(0, 0, width, height, 0xAA000000);
        gui.pose().pushPose();
        gui.pose().translate(width / 2f, height / 2f - 50, 0);
        gui.pose().scale(2f, 2f, 1f);
        gui.drawCenteredString(font, "PAUSED", 0, 0, 0xFFFFFF);
        gui.pose().popPose();

        String[] options = editorPlaytest
                ? new String[]{"Resume", "Restart", "Return to Editor"}
                : (duet ? new String[]{"Resume", "Quit"}
                : new String[]{"Resume", "Restart", "Edit Chart", "Quit"});
        int startY = height / 2 - 10;
        for (int i = 0; i < options.length; i++) {
            int color = i == pauseSelection ? 0xFFFFFF66 : 0xFFAAAAAA;
            gui.drawCenteredString(font,
                    (i == pauseSelection ? "> " : "") + options[i], width / 2, startY + i * 16, color);
        }
    }

    private void renderGameOver(GuiGraphics gui) {
        gui.fill(0, 0, width, height, 0xCC220011);
        gui.pose().pushPose();
        gui.pose().translate(width / 2f, height / 2f - 40, 0);
        gui.pose().scale(2.5f, 2.5f, 1f);
        gui.drawCenteredString(font, "BLUE BALLED", 0, 0, 0xFF6699FF);
        gui.pose().popPose();
        if (!duet) {
            gui.drawCenteredString(font, "Press R to retry", width / 2, height / 2 + 10, 0xFFFFFF);
        }
        gui.drawCenteredString(font, "Press Enter to give up", width / 2, height / 2 + 24, 0xAAAAAA);
    }

    private void renderResults(GuiGraphics gui) {
        int cx = width / 2;
        int panelW = 240;
        int px = cx - panelW / 2;
        int py = height / 2 - 88;
        int panelH = duet ? 190 : 156;
        gui.fill(px, py, px + panelW, py + panelH, 0x88000000);

        gui.pose().pushPose();
        gui.pose().translate(cx, py + 10, 0);
        gui.pose().scale(1.6f, 1.6f, 1f);
        gui.drawCenteredString(font, "CLEAR!", 0, 0, 0xFF66FF66);
        gui.pose().popPose();

        int ty = py + 30;
        resultLine(gui, px, ty, "Score", String.valueOf(score), panelW);
        resultLine(gui, px, ty + 12, "Max Combo", String.valueOf(maxCombo), panelW);
        resultLine(gui, px, ty + 24, "Accuracy", String.format("%.2f%%", accuracy() * 100), panelW);
        resultLine(gui, px, ty + 36, "Notes Hit", (judgements[0] + judgements[1] + judgements[2] + judgements[3]) + " / " + totalMyNotes, panelW);
        resultLine(gui, px, ty + 52, "Sick", String.valueOf(judgements[0]), panelW);
        resultLine(gui, px, ty + 62, "Good", String.valueOf(judgements[1]), panelW);
        resultLine(gui, px, ty + 72, "Bad", String.valueOf(judgements[2]), panelW);
        resultLine(gui, px, ty + 82, "Shit", String.valueOf(judgements[3]), panelW);
        resultLine(gui, px, ty + 92, "Missed", String.valueOf(misses), panelW);

        int by = ty + 106;
        if (duet) {
            if (partnerEnded) {
                gui.drawCenteredString(font, partnerName + (partnerFailed ? " failed!" : ""), cx, by, 0xFFFFAAEE);
                gui.drawCenteredString(font, String.format("Score %d  Miss %d  Acc %.2f%%",
                        partnerEndScore, partnerEndMisses, partnerEndAccuracy * 100), cx, by + 11, 0xFFCCCCCC);
            } else {
                gui.drawCenteredString(font, "Waiting for " + partnerName + "...", cx, by, 0xFFAAAAAA);
            }
            by += 24;
        }
        gui.drawCenteredString(font, "Press Enter to continue", cx, by, 0x88FFFFFF);
    }

    private void resultLine(GuiGraphics gui, int px, int y, String label, String value, int panelW) {
        gui.drawString(font, label, px + 14, y, 0xFFBBBBBB, false);
        gui.drawString(font, value, px + panelW - 14 - font.width(value), y, 0xFFFFFFFF, false);
    }

    // keep the world visible behind the notes
    @Override
    public void renderBackground(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        // intentionally empty: keep the world fully visible while playing
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        if (luaRuntime != null) luaRuntime.close();
        customNoteTextures.close();
        GameplayCamera.end();
        songPlayer.dispose();
        restoreVanillaMusic();
        super.removed();
    }
}
