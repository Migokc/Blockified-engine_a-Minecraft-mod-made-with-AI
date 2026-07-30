package com.fnfmod.client.gui;

import com.fnfmod.chart.Conductor;
import com.fnfmod.chart.CameraShotClipboard;
import com.fnfmod.chart.ChartEventTypes;
import com.fnfmod.chart.SongChart;
import com.fnfmod.client.ClientOptions;
import com.fnfmod.client.ClientSession;
import com.fnfmod.client.FnfKeys;
import com.fnfmod.client.anim.CharacterAnimations;
import com.fnfmod.client.anim.ExtraCharacterRoster;
import com.fnfmod.client.audio.SongPlayer;
import com.fnfmod.client.audio.HitsoundPlayer;
import com.fnfmod.block.FunkinMachineBlock;
import com.fnfmod.client.camera.GameplayCamera;
import com.fnfmod.client.math.Easing;
import com.fnfmod.client.gui.editor.ChartEditorScreen;
import com.fnfmod.client.gameplay.GameplayEventDispatcher;
import com.fnfmod.client.gameplay.PsychGameplayScene;
import com.fnfmod.client.gameplay.PsychBuiltinEventHandler;
import com.fnfmod.client.gameplay.PsychAssetResolver;
import com.fnfmod.gameplay.PlaybackMode;
import com.fnfmod.gameplay.PlaybackPolicy;
import com.fnfmod.client.gameplay.FieldOfViewControl;
import com.fnfmod.client.gameplay.RenderDistanceControl;
import com.fnfmod.client.gameplay.StageChunkLoader;
import com.fnfmod.client.gameplay.StageOrientation;
import com.fnfmod.gameplay.GameplayClock;
import com.fnfmod.gameplay.PerformerCollisions;
import com.fnfmod.gameplay.PerformerPin;
import com.fnfmod.gameplay.PerformerShadows;
import com.fnfmod.gameplay.PsychRating;
import com.fnfmod.client.camera.CameraOverlay;
import com.fnfmod.client.lua.PsychLuaRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.function.Supplier;
import com.fnfmod.client.render.NoteStyle;
import com.fnfmod.client.render.HudLayerOrder;
import com.fnfmod.client.render.WarningFlag;
import com.fnfmod.client.render.PsychCanvas;
import com.fnfmod.client.render.NonFnfHudState;
import com.fnfmod.client.render.PsychHudState;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.UUID;
import java.nio.file.Path;

/** The rhythm gameplay screen. */
public class GameplayScreen extends Screen implements PsychBuiltinEventHandler.Host {

    private enum Phase { COUNTDOWN, PLAYING, PAUSED, GAMEOVER, RESULTS }

    /** Which side(s) the local player controls. BOTH is solo-only. */
    public enum PlayMode { PLAYER, OPPONENT, BOTH }

    // Psych Engine default judgement windows (ms)
    private static final double SICK = 45, GOOD = 90, BAD = 135, SHIT = 166;
    private static final double HOLD_RELEASE_GRACE_MS = 300;
    private static final String[] DIR_NAMES = {"left", "down", "up", "right"};
    private static final int HUD_WIDTH = PsychCanvas.WIDTH;
    private static final int HUD_HEIGHT = PsychCanvas.HEIGHT;
    /** Native Minecraft font/icons need this compensation inside Psych's 1280x720 canvas. */
    private static final float FNF_NATIVE_UI_SCALE = 2f;
    /** Psych Engine's 160px note graphic rendered at default 0.7 scale. */
    private static final float PSYCH_NOTE_WIDTH = 112f;
    /** Blockified default presentation is 90% of Psych's native note size. */
    private static final float DEFAULT_NOTE_SIZE = PSYCH_NOTE_WIDTH * 0.9f;

    private final BlockPos machinePos;
    private final SongChart chart;
    private final SongPlayer songPlayer;
    private final PlayMode mode;
    private final boolean playBoth;
    private final boolean myChartSideIsPlayer;
    private final UUID partnerId;
    private final int botEntityId;
    private final String partnerName;
    private String partnerAnimSet;
    private String myAnimSet;
    private final String initialPartnerAnimSet;
    private final String initialMyAnimSet;
    private String playerIdleSuffix = "";
    private String opponentIdleSuffix = "";
    private String eventPlayerIcon;
    private String eventOpponentIcon;
    private final boolean duet;
    private long startAtEpochMs;

    private final Conductor conductor;
    /** Pristine notes restored before Lua is recreated on a song restart. */
    private final List<SongChart.Note> originalLuaNotes;

    // camera focus timeline (per chart section)
    private final double[] secStarts;
    private final boolean[] secFocusPlayer;
    private final boolean[] secFocusGirlfriend;
    /** Per-section alt-animation flag, exposed to Lua as Psych's {@code altAnim}. */
    private final boolean[] secAltAnim;
    private final double[] secBeatMs;
    private int camSection = -1;
    /** Null follows Must Hit sections; otherwise an event owns camera focus. */
    private String cameraFocusOverride;

    private static class GameNote {
        final SongChart.Note data;
        /** Set once the note enters the scroll window and onSpawnNote has fired. */
        boolean spawnAnnounced;
        /** Resolved custom hitsound path, computed once (null = default/none). */
        Path cachedHitsound;
        boolean hitsoundResolved;
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
    /** Game overs this session, exposed to Lua as Psych's {@code deaths}. */
    private int deaths;
    /** Last Psych countdown index announced to Lua; -1 before the countdown starts. */
    private int lastCountdownTick = -1;
    private boolean countdownAnnounced;
    /**
     * Rating values a script forced with setRatingPercent/Name/FC. Null means the
     * value is still computed from live counters, so a script can override one
     * part of the rating without freezing the rest.
     */
    private Double forcedRatingPercent;
    private String forcedRatingName;
    private String forcedRatingFC;
    /**
     * Set once a script writes score, hits, or a rating. Such a run is shown
     * normally but never saved as a personal best, the same treatment botplay gets.
     */
    private boolean scriptAlteredScore;
    /** When a pausing Lua substate took over, so the song can be resumed in step. */
    private long substatePausedAtMs = -1;
    private final int[] judgements = new int[5]; // sick good bad shit miss
    private double accuracySum;
    private int accuracyCount;
    private float health = 1.0f;
    private final PsychHudState fnfHud;
    /** Lua-controllable state for the non-FNF HUD styles (bar + score/miss text). */
    private final NonFnfHudState textHud = new NonFnfHudState();

    // input / strums
    private final boolean[] laneHeld = new boolean[4];
    /** Timestamped note-input queue, drained each frame when precise input is on. */
    private final com.fnfmod.client.input.NoteInput noteInput = new com.fnfmod.client.input.NoteInput();
    /** GLFW-side held state, so key auto-repeat does not enqueue duplicate presses. */
    private final boolean[] glfwLaneHeld = new boolean[4];
    /** High-rate raw key backend, created on demand when precise input is on. */
    private com.fnfmod.client.input.WindowsRawKeyBackend rawInput;
    private boolean rawInputUnavailable;
    /** Hittable notes published each frame so the backend can sound a hit sub-frame. */
    private final com.fnfmod.client.input.HitsoundSnapshot hitsoundSnapshot =
            new com.fnfmod.client.input.HitsoundSnapshot();
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
    /**
     * Sustain notes replay the sing animation so it keeps moving through the hold.
     * BBS advances an animation state one frame per game tick (50 ms), so replaying
     * every frame keeps the loop as tight as the animation itself.
     */
    private static final long BBS_HOLD_LOOP_MS = 50;
    private long lastSingMs;
    private long partnerLastSingMs;
    private int lastSentHealthHalf = Integer.MIN_VALUE;
    private int lastSentFoodLevel = Integer.MIN_VALUE;
    private long lastVanillaHudSyncMs;
    private final long[] lastHoldSingMs = new long[4];
    private long lastFrameNano;
    private boolean endSent;
    private int pauseSelection;
    private long pausedAtMs;
    private boolean openingMinecraftPause;
    /** Prevents the hard-coded emergency-exit chord from running twice. */
    private boolean forceExitStarted;
    private boolean resourcesDisposed;
    /** Prevents one held Enter press from pausing and then confirming Resume via key repeat. */
    private boolean enterReady = true;
    private boolean editorPlaytest;
    private boolean editorPreview;
    private double editorStartMs;
    // Free-camera playtest state (Ctrl+Shift+Space). freeCamMove toggles the
    // spectator move/look with a captured mouse; freeCamSpeed is scroll-adjusted.
    private boolean freeCam;
    private boolean freeCamMove;
    private double freeCamSpeed = 6.0;
    private double freeCamLastCursorX, freeCamLastCursorY;
    private long freeCamPausedAtMs;
    private long freeCamLastMoveNano;
    private String freeCamMessage = "";
    private long freeCamMessageUntil;
    // Camera Follow Pos options for the copied shot. Override = fixed at the
    // anchor; otherwise attached to the focused character. Machine vs camera frame.
    private boolean freeCamOverride = true;
    private boolean freeCamCameraFrame;
    // Where the player stood before an editor playtest teleported them to the
    // machine stage, restored when the playtest returns to the editor.
    private Vec3 editorReturnPos;
    private float editorReturnYaw, editorReturnPitch;
    private Supplier<Screen> editorReturnFactory;
    private PsychLuaRuntime luaRuntime;
    private final GameplayEventDispatcher eventDispatcher;
    private final ExtraCharacterRoster extraCharacters;
    // Stage offsets for the main performers, tweenable in Legacy/Minecraft modes.
    private final PerformerTween playerPerformerTween = new PerformerTween();
    private final PerformerTween opponentPerformerTween = new PerformerTween();
    private final PsychBuiltinEventHandler psychBuiltinEvents = new PsychBuiltinEventHandler();
    private PsychNoteTextureCache customNoteTextures;
    private String runtimeSongId;
    private Path runtimeSongFolder;
    private SongEntry runtimeSongEntry;
    private PlaybackPolicy playbackPolicy;
    private PsychGameplayScene psychScene;
    private PsychAssetResolver assetResolver;
    private boolean vanillaMusicMuted;
    private final double[] luaStrumX = new double[8];
    private final double[] luaStrumY = new double[8];
    private final double[] luaStrumAlpha = new double[8];
    private final double[] luaStrumAngle = new double[8];
    private final boolean[] luaStrumDownScroll = new boolean[8];
    /** Psych strum scroll direction in degrees; 90 is the normal vertical path. */
    private final double[] luaStrumDirection = new double[8];
    private double eventScrollMultiplier = 1;
    private double eventScrollFrom = 1;
    private double eventScrollTarget = 1;
    private double eventScrollStartMs;
    private double eventScrollDurationMs;

    public GameplayScreen(BlockPos machinePos, SongChart chart, SongPlayer songPlayer,
                          PlayMode mode, UUID partnerId, String partnerName,
                          String partnerAnimSet, int botEntityId, long startAtEpochMs) {
        super(Component.literal("FNF"));
        this.machinePos = machinePos;
        this.chart = chart;
        this.songPlayer = songPlayer;
        this.extraCharacters = new ExtraCharacterRoster(machinePos);
        this.eventDispatcher = new GameplayEventDispatcher(machinePos, () -> editorPlaytest,
                this::applyCameraFocusEvent, event -> {
                    if (luaRuntime != null) {
                        luaRuntime.onEvent(event.name, event.value1, event.value2, event.timeMs);
                    }
                });
        this.fnfHud = new PsychHudState(HUD_WIDTH, HUD_HEIGHT, ClientOptions.get().downscroll);
        this.mode = mode;
        this.playBoth = mode == PlayMode.BOTH;
        this.myChartSideIsPlayer = mode != PlayMode.OPPONENT;
        this.partnerId = partnerId;
        this.botEntityId = botEntityId;
        this.partnerName = partnerName == null ? "" : partnerName;
        this.partnerAnimSet = partnerAnimSet == null || partnerAnimSet.isEmpty()
                ? CharacterAnimations.DEFAULT_SET : partnerAnimSet;
        this.initialPartnerAnimSet = this.partnerAnimSet;
        this.duet = partnerId != null;
        this.startAtEpochMs = startAtEpochMs;
        this.conductor = new Conductor(chart);
        applySongNoteTextures(chart);
        this.originalLuaNotes = chart.notes.stream().map(SongChart.Note::copy).toList();
        this.runtimeSongId = ClientSession.songId;
        this.runtimeSongEntry = ClientSession.songId == null ? null : SongLibrary.get(ClientSession.songId);
        this.runtimeSongFolder = ClientSession.resolvedFolder != null ? ClientSession.resolvedFolder
                : runtimeSongEntry == null ? null : runtimeSongEntry.folder;
        this.playbackPolicy = new PlaybackPolicy(ClientSession.playbackMode, ClientSession.songAssets);
        CharacterAnimations.useSongFolder(playbackPolicy.songAssets() && runtimeSongEntry != null
                ? runtimeSongEntry.animationRoot() : null, chart.player1, chart.player2);
        // A full mod pack defines its performers through the chart's player IDs,
        // not the shared global animation-set option (which stays "none" unless the
        // player picked a config/fnfmod/animations character). Use the chart
        // character so the mod's own bundled character — and its sing states and
        // Change Character events — actually resolve instead of "none".
        String resolvedAnimSet = ClientOptions.get().animationSet;
        String localCharacter = myChartSideIsPlayer ? chart.player1 : chart.player2;
        if (playbackPolicy.songAssets() && runtimeSongEntry != null
                && localCharacter != null && !localCharacter.isBlank()) {
            resolvedAnimSet = CharacterAnimations.modSet(localCharacter);
        }
        this.myAnimSet = resolvedAnimSet;
        this.initialMyAnimSet = resolvedAnimSet;
        this.assetResolver = new PsychAssetResolver(runtimeSongFolder, runtimeSongEntry, playbackPolicy, chart.stage);
        this.customNoteTextures = new PsychNoteTextureCache(
                assetResolver.customNoteRoots(),
                playbackPolicy.allows(runtimeSongEntry, SongLibrary.ExternalContent.IMAGES));
        this.psychScene = PsychGameplayScene.load(chart, runtimeSongFolder, runtimeSongEntry, playbackPolicy);
        Arrays.fill(luaStrumX, Double.NaN);
        Arrays.fill(luaStrumY, Double.NaN);
        Arrays.fill(luaStrumAlpha, 1.0);
        Arrays.fill(luaStrumDownScroll, ClientOptions.get().downscroll);
        Arrays.fill(luaStrumDirection, 90.0);

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
        secFocusGirlfriend = new boolean[n];
        secAltAnim = new boolean[n];
        secBeatMs = new double[n];
        double time = 0;
        double bpm = chart.startBpm;
        for (int i = 0; i < n; i++) {
            SongChart.Section s = i < chart.sections.size() ? chart.sections.get(i) : null;
            if (s != null && s.changeBPM && s.bpm > 0) bpm = s.bpm;
            secStarts[i] = time;
            secFocusPlayer[i] = s == null || s.mustHit;
            secFocusGirlfriend[i] = s != null && s.gfSection;
            secAltAnim[i] = s != null && s.altAnim;
            secBeatMs[i] = 60000.0 / bpm;
            time += (s == null ? 4 : s.sectionBeats) * (60000.0 / bpm);
        }

        beginCamera();
        applyPsychCameraDefaults();
        lastFrameNano = System.nanoTime();
        // Fresh song (or restart): drop any paused time carried over from before.
        GameplayClock.reset();
    }

    /** Standalone chart-editor playtest. It never creates or leaves a server song session. */
    public static GameplayScreen editorPlaytest(BlockPos machinePos, SongChart chart, SongPlayer player,
                                                double startMs, boolean preview, String songId, Path songFolder,
                                                SongEntry songEntry, Supplier<Screen> returnFactory) {
        return editorPlaytest(machinePos, chart, player, startMs, preview, songId, songFolder,
                songEntry, returnFactory, null);
    }

    /**
     * {@code requestedPolicy} is the look the chart was opened with. The editor
     * has to supply it because opening the editor tears the session down, so by
     * the time a playtest starts there is no session left to read the look from.
     */
    public static GameplayScreen editorPlaytest(BlockPos machinePos, SongChart chart, SongPlayer player,
                                                double startMs, boolean preview, String songId, Path songFolder,
                                                SongEntry songEntry, Supplier<Screen> returnFactory,
                                                PlaybackPolicy requestedPolicy) {
        GameplayScreen screen = new GameplayScreen(machinePos, chart, player, PlayMode.PLAYER,
                null, "", CharacterAnimations.DEFAULT_SET, -1, System.currentTimeMillis() + 1000);
        screen.editorPlaytest = true;
        screen.editorPreview = preview;
        screen.editorStartMs = Math.max(0, startMs);
        screen.editorReturnFactory = returnFactory;
        screen.runtimeSongId = songId;
        screen.runtimeSongFolder = songFolder;
        screen.runtimeSongEntry = songEntry;
        boolean currentSessionSong = ClientSession.activePos != null && songId != null
                && songId.equals(ClientSession.songId);
        if (requestedPolicy != null) {
            // The look the editor was opened with, which is the only accurate
            // source once the session has been left.
            screen.playbackPolicy = requestedPolicy;
        } else if (currentSessionSong) {
            screen.playbackPolicy = new PlaybackPolicy(ClientSession.playbackMode, ClientSession.songAssets);
        } else {
            // No look was chosen for this chart. Use the same default a normal
            // play starts from, so a playtest is not silently a different mode.
            screen.playbackPolicy = PlaybackPolicy.resolve(PlaybackMode.MINECRAFT, songEntry);
        }
        CharacterAnimations.useSongFolder(screen.playbackPolicy.songAssets() && songEntry != null
                ? songEntry.animationRoot() : null, chart.player1, chart.player2);
        screen.assetResolver = new PsychAssetResolver(songFolder, songEntry, screen.playbackPolicy, chart.stage);
        screen.customNoteTextures.close();
        screen.psychScene.close();
        screen.customNoteTextures = new PsychNoteTextureCache(
                screen.assetResolver.customNoteRoots(),
                screen.playbackPolicy.allows(songEntry, SongLibrary.ExternalContent.IMAGES));
        screen.psychScene = PsychGameplayScene.load(chart, songFolder, songEntry, screen.playbackPolicy);
        // The normal constructor starts a session camera before it knows this is
        // an editor playtest. Rebuild it using the editor-safe virtual stage.
        GameplayCamera.end();
        screen.beginCamera();
        screen.applyPsychCameraDefaults();
        screen.prepareEditorStart();
        return screen;
    }

    /**
     * Teleports the playtesting player through a server command, the way a real
     * song load positions the player. A plain client-side setPos desyncs from the
     * server, which then corrects the client back, so the stage placement and the
     * return both need this. Requires the world to allow commands (cheats); with
     * commands off the player simply cannot be moved onto the editor stage.
     */
    private void editorServerTeleport(double x, double y, double z, float yaw) {
        // Uses Minecraft.getInstance(), not the screen's minecraft field: this runs
        // from beginCamera during the playtest factory, before the screen is shown,
        // so that field is still null there.
        var player = Minecraft.getInstance().player;
        if (player == null || player.connection == null) return;
        String command = String.format(java.util.Locale.ROOT,
                "tp @s %.4f %.4f %.4f %.2f 0", x, y, z, yaw);
        try {
            player.connection.sendCommand(command);
        } catch (Exception ignored) {
            // No command permission: the client-side setPos is the best we can do.
        }
    }

    private void prepareEditorStart() {
        if (!editorPlaytest || editorStartMs <= 0) return;
        double cutoff = editorStartMs - SHIT;
        for (int lane = 0; lane < 4; lane++) {
            myLaneIndex[lane] = skipNotesBefore(myLanes[lane], cutoff);
            otherLaneIndex[lane] = skipNotesBefore(otherLanes[lane], cutoff);
        }
        // Keep the event cursor at zero. When playback starts, events up to the
        // requested conductor time run immediately so camera/event state is
        // reconstructed instead of silently discarded.
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

        // Hold the stage region loaded and ticking for the song so the camera,
        // bot and performers never fall into an unloaded chunk. Radius follows
        // the render distance (capped) so everything visible stays valid.
        StageChunkLoader.load(machinePos, Math.max(3, Math.min(12, RenderDistanceControl.current())));

        // stage layout is derived from the machine block's facing (same math as
        // the server teleport). Reuse the cached facing when it is already known
        // (e.g. a restart, where the player may currently be far and the machine
        // chunk unloaded), otherwise read it now while the chunk is loaded.
        Direction facing = StageOrientation.facingOr(mc.level, machinePos);
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
        float[] myBase = CharacterAnimations.baseCameraOffset(myAnimSet, myRole());

        if (editorPlaytest) {
            // Put the performer on the machine's real stage so the camera, player
            // and opponent spot line up exactly as a normally selected song does.
            // The player's original transform is restored when the editor reopens.
            float yaw = facing.toYRot();
            if (editorReturnPos == null) {
                editorReturnPos = mc.player.position();
                editorReturnYaw = mc.player.getYRot();
                editorReturnPitch = mc.player.getXRot();
            } else {
                // Restart: return to the pre-song spot first (a known-loaded place
                // near the machine), exactly like starting the song again, then the
                // stage teleport below immediately moves onto the mark. This keeps a
                // song the player wandered far from restarting on the correct spot.
                mc.player.setPos(editorReturnPos.x, editorReturnPos.y, editorReturnPos.z);
                editorServerTeleport(editorReturnPos.x, editorReturnPos.y, editorReturnPos.z,
                        editorReturnYaw);
            }
            mc.player.setPos(playerSpot.x, playerSpot.y, playerSpot.z);
            mc.player.setYRot(yaw);
            mc.player.setYHeadRot(yaw);
            mc.player.setYBodyRot(yaw);
            // A real song load teleports the player through the server. The editor
            // has no session, so the client-side setPos above would be corrected
            // straight back off the stage; a server tp moves the authoritative
            // entity, which is what actually keeps the player on the stage.
            editorServerTeleport(playerSpot.x, playerSpot.y, playerSpot.z, yaw);
            Supplier<Vec3> opponentFocus = () -> opponentSpot.add(0, 1.0, 0);
            float[] opponentBase = CharacterAnimations.baseCameraOffset(
                    CharacterAnimations.DEFAULT_SET, "opponent");
            GameplayCamera.begin(playerSpot.add(0, 1.0, 0), yaw, mePos, mePos, opponentFocus,
                    myBase, opponentBase, playbackPolicy.mode());
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
        float[] partnerBase = CharacterAnimations.baseCameraOffset(partnerAnimSet, partnerRole());
        GameplayCamera.begin(anchor, facing.toYRot(), mePos,
                myChartSideIsPlayer ? mePos : otherPos,
                myChartSideIsPlayer ? otherPos : mePos,
                myChartSideIsPlayer ? myBase : partnerBase,
                myChartSideIsPlayer ? partnerBase : myBase, playbackPolicy.mode());
    }

    // ------------------------------------------------------------------ timing

    private void updateSongPos() {
        if (phase == Phase.PAUSED || phase == Phase.GAMEOVER) return;
        // A substate opened with pauseGame holds the song where it is, like Psych.
        if (luaRuntime != null && luaRuntime.substatePausesGame()) return;
        double base = editorPlaytest ? editorStartMs : 0;
        double audioOffset = chart.offsetMs;
        if (!songPlayer.isStarted()) {
            double countdownTime = System.currentTimeMillis() - startAtEpochMs;
            songPos = countdownTime + base;
            if (phase == Phase.COUNTDOWN) announceCountdown(countdownTime);
            if (phase == Phase.COUNTDOWN && countdownTime >= 0) {
                phase = Phase.PLAYING;
            }
            // The chart offset shifts the song audio, not the notes. Start (and
            // seek) the song at the note-clock moment its playback position would
            // reach zero: a positive offset delays the song so its lead-in stays
            // silent, while a negative offset seeks past the intro so nothing is
            // heard before the chart begins.
            if (phase == Phase.PLAYING && songPos >= Math.max(base, audioOffset)) {
                songPlayer.start();
                songPlayer.seekMs(songPos - audioOffset);
            }
        }
        if (songPlayer.isStarted()) {
            songPos = songPlayer.positionMs() + audioOffset + ClientOptions.get().offsetMs;
        }
    }

    /**
     * Starts and stops the song alongside a pausing Lua substate, reusing the same
     * countdown-shift the pause menu applies so reopening does not skip the intro.
     */
    private void syncSubstatePause() {
        boolean pausing = luaRuntime != null && luaRuntime.substatePausesGame();
        if (pausing && substatePausedAtMs < 0) {
            substatePausedAtMs = System.currentTimeMillis();
            songPlayer.pause();
        } else if (!pausing && substatePausedAtMs >= 0) {
            long held = System.currentTimeMillis() - substatePausedAtMs;
            substatePausedAtMs = -1;
            if (songPlayer.isStarted()) songPlayer.resume();
            else startAtEpochMs += held;
        }
    }

    /**
     * Mirrors Psych's countdown hooks onto Blockified's one-second cadence.
     * {@code counter} runs 0-3 across "3", "2", "1", and "Go!", matching the
     * indices Psych passes to {@code onCountdownTick}.
     */
    private void announceCountdown(double countdownTime) {
        if (luaRuntime == null || countdownTime > 0) return;
        if (!countdownAnnounced) {
            countdownAnnounced = true;
            luaRuntime.onCountdownStarted();
        }
        int remainingSeconds = (int) Math.ceil(-countdownTime / 1000.0);
        if (remainingSeconds > 4) return;
        int counter = 4 - remainingSeconds;
        if (counter <= lastCountdownTick) return;
        lastCountdownTick = counter;
        luaRuntime.onCountdownTick(counter);
    }

    private double pxPerMs() {
        var o = ClientOptions.get();
        double speed = o.constantScrollSpeed ? o.scrollSpeedMult
                : chart.speed * o.scrollSpeedMult * currentEventScrollMultiplier();
        return 0.45 * speed;
    }

    private double currentEventScrollMultiplier() {
        if (eventScrollDurationMs <= 0) return eventScrollMultiplier;
        double t = (songPos - eventScrollStartMs) / eventScrollDurationMs;
        if (t >= 1) {
            eventScrollMultiplier = eventScrollTarget;
            eventScrollDurationMs = 0;
        } else if (t > 0) {
            eventScrollMultiplier = eventScrollFrom
                    + (eventScrollTarget - eventScrollFrom) * t;
        }
        return eventScrollMultiplier;
    }

    // ------------------------------------------------------------------ tick logic

    @Override
    protected void init() {
        super.init();
        muteVanillaMusic();
    }

    private void applyPsychCameraDefaults() {
        if (psychScene != null && playbackPolicy.usesPsychCamera()) {
            GameplayCamera.setBaseGameZoom(psychScene.defaultZoom());
        }
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

        // Freeze the animation clock whenever the song is not advancing, so tweens,
        // timers and camera moves hold their progress across a pause instead of
        // finishing while the game is frozen. Runs before the early returns below.
        boolean songRunning = (phase == Phase.PLAYING || phase == Phase.COUNTDOWN)
                && !freeCam
                && !(luaRuntime != null && luaRuntime.substatePausesGame());
        GameplayClock.setRunning(songRunning);

        // Free camera freezes the song like a pause; only the camera updates.
        if (freeCam) {
            updateFreeCam();
            return;
        }

        if (luaRuntime == null && width > 0 && height > 0) {
            luaRuntime = PsychLuaRuntime.load(this, chart, runtimeSongId, runtimeSongFolder,
                    runtimeSongEntry, playbackPolicy);
            rebuildNoteLanesAfterLuaCreate();
            // Psych announces the whole event list once, right after scripts load,
            // so a script can pre-cache assets for events it will receive later.
            for (SongChart.Event event : chart.events) {
                luaRuntime.onEventPushed(event.name, event.value1, event.value2, event.timeMs);
            }
        }
        // Morph the performers during the count-in. applyForm is a no-op once the
        // form is already on, so retrying each frame just covers a partner or bot
        // whose entity has not streamed in yet.
        if (phase == Phase.COUNTDOWN) prepareCharacterForms();
        // Load-triggered events must run before updateSongPos can start audio.
        if (!preSongEventsProcessed && phase == Phase.COUNTDOWN) processPreSongEvents();
        updateSongPos();
        if (psychScene != null && (phase == Phase.PLAYING || phase == Phase.COUNTDOWN)) {
            double stepMs = 15000.0 / Math.max(1, conductor.bpmAt(Math.max(0, songPos)));
            psychScene.update(dtMs / 1000.0, stepMs, songPlayer.playbackRate());
        }
        // Psych scripts run after camera follow calculation. Their camFollow /
        // camFollowPos writes therefore win for this frame, matching Psych.
        if (luaRuntime != null && (phase == Phase.PLAYING || phase == Phase.COUNTDOWN)) {
            luaRuntime.update(dtMs / 1000.0);
        }
        // Extra-character tweens run independently of Lua so Legacy and Minecraft
        // charts can animate performers through the Tween Character event alone.
        extraCharacters.update();
        applyPerformerTweens();
        songPlayer.applyVolumes();
        syncVanillaHud();

        // keep bodies square with the look direction so animations stay oriented
        alignBody(minecraft.player);
        if (partnerId != null && minecraft.level != null) {
            alignBody(minecraft.level.getPlayerByUUID(partnerId));
        }

        if (phase != Phase.PLAYING && phase != Phase.COUNTDOWN) return;
        syncSubstatePause();
        // While a pausing substate is open the chart does not advance, so notes,
        // events and holds are all left exactly where they were.
        if (luaRuntime != null && luaRuntime.substatePausesGame()) return;

        // Judge queued precise-input presses before misses are swept, so a late
        // press can still hit a note about to scroll past — same order the direct
        // GLFW handler had (it ran during the input poll, before this update).
        if (ClientOptions.get().preciseInput) {
            publishHitsoundSnapshot();
            updateRawInput();
            noteInput.drain(e -> {
                if (e.press()) processLanePress(e.lane(), e.nano());
                else processLaneRelease(e.lane(), e.nano());
            });
        } else if (rawInput != null) {
            // Turned off mid-song: stop the backend and drop its queue, or it would
            // keep filling a queue nothing drains.
            rawInput.stop();
            rawInput = null;
            noteInput.clear();
            java.util.Arrays.fill(glfwLaneHeld, false);
        }

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

        announceSpawnedNotes();

        // botplay hits your notes automatically before misses are swept
        botplayTick();

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
                    endPsychHold(hold.data);
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
                    if (!hold.data.noAnimation) {
                        // Psych sprite sustain timing uses character JSON FPS. Minecraft's
                        // body animation still has its own coarser replay timer.
                        animatePsychHold(hold.data, lane,
                                hold.data.playerSide ? "boyfriend" : "dad");
                        if (hold.data.playerSide == myChartSideIsPlayer
                                && nowMs - lastHoldSingMs[lane] > BBS_HOLD_LOOP_MS && minecraft.player != null) {
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
                        endPsychHold(hold.data);
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
                        if (songPos < n.endMs()) {
                            if (!n.data.noAnimation && n.data.sustainMs > 30) {
                                animatePsychHold(n.data, lane,
                                        n.data.playerSide ? "boyfriend" : "dad");
                            }
                            break;
                        }
                        endPsychHold(n.data);
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
                            if (!n.data.noAnimation) animatePsychNote(n.data, lane, false,
                                    n.data.playerSide ? "boyfriend" : "dad");
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
                GameplayCamera.focusSection(secFocusPlayer[idx], Math.min(700, secBeatMs[idx] * 2));
                String cameraTarget = secFocusGirlfriend[idx] ? "gf"
                        : secFocusPlayer[idx] ? "boyfriend" : "dad";
                if (psychScene != null) {
                    psychScene.focus(cameraTarget);
                }
                if (luaRuntime != null) luaRuntime.onMoveCamera(cameraTarget);
            }
        }

        // idle bounce on beat (idle/idle2 alternate when both exist)
        int beat = (int) Math.floor(conductor.beatAt(Math.max(0, songPos)));
        if (beat != lastBeat) {
            lastBeat = beat;
            long nowMs = System.currentTimeMillis();
            // FNF beat zoom: bump every measure (4 beats)
            if (phase == Phase.PLAYING && beat >= 0 && beat % 4 == 0) {
                GameplayCamera.bumpZoom(playbackPolicy.usesPsychCamera() ? 0.015f : 0.03f);
            }
            if (phase == Phase.PLAYING && psychScene != null) psychScene.beat(beat);
            if (phase == Phase.PLAYING) {
                // loopIdle characters are driven by their BBS "main" state, which
                // setForm starts and each sing returns to on its own. Re-triggering
                // idle would expire that looping state, so leave it to BBS.
                double singHold = singHoldMs();
                if (nowMs - lastSingMs > singHold && minecraft.player != null
                        && !CharacterAnimations.loopIdle(myAnimSet, myRole())) {
                    playIdle(minecraft.player, myAnimSet, myRole(), beat);
                }
                if (partnerId != null && minecraft.level != null && nowMs - partnerLastSingMs > singHold
                        && !CharacterAnimations.loopIdle(partnerAnimSet, partnerRole())) {
                    Player partner = minecraft.level.getPlayerByUUID(partnerId);
                    if (partner != null) playIdle(partner, partnerAnimSet, partnerRole(), beat);
                }
                // Chart-added performers get the same beat idle. loopIdle ones are
                // skipped inside danceAll, exactly like the two above.
                extraCharacters.danceAll();
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
        psychBuiltinEvents.execute(event, this);
        eventDispatcher.execute(eventIndex, event);
    }

    private void applyCameraFocusEvent(SongChart.Event event) {
        String target = event.value1 == null ? "" : event.value1.trim();
        if (target.isEmpty()) {
            cameraFocusOverride = null;
            int section = camSection < 0 ? 0 : Math.min(camSection, secFocusPlayer.length - 1);
            GameplayCamera.focusSection(secFocusPlayer[section],
                    Math.min(700, secBeatMs[section] * 2));
            if (psychScene != null) {
                psychScene.focus(secFocusGirlfriend[section] ? "gf"
                        : secFocusPlayer[section] ? "boyfriend" : "dad");
            }
            return;
        }

        String role;
        if (target.equalsIgnoreCase("player") || target.equalsIgnoreCase("boyfriend")
                || target.equalsIgnoreCase("bf")) role = "boyfriend";
        else if (target.equalsIgnoreCase("opponent") || target.equalsIgnoreCase("dad")) role = "dad";
        else if (target.equalsIgnoreCase("gf") || target.equalsIgnoreCase("girlfriend")
                || target.equalsIgnoreCase("speakers")) role = "gf";
        else return;
        cameraFocusOverride = role;
        String eventEase = event.value2 == null || event.value2.isBlank() ? "smooth" : event.value2;
        GameplayCamera.focus(!role.equals("dad"), eventEase, 500);
        if (psychScene != null) psychScene.focus(role);
    }

    @Override
    public PlaybackMode playbackMode() {
        return playbackPolicy == null ? PlaybackMode.MINECRAFT : playbackPolicy.mode();
    }

    @Override
    public void eventHey(String target, double durationSeconds) {
        if (psychScene != null) psychScene.hey(target, durationSeconds);
        playMinecraftHey(target, durationSeconds);
    }

    @Override
    public void eventSetGirlfriendSpeed(int speed) {
        if (psychScene != null) psychScene.setGirlfriendDanceSpeed(speed);
    }

    @Override
    public void eventAddCameraZoom(float gameAmount, float hudAmount) {
        if (GameplayCamera.canBumpZoom()) {
            GameplayCamera.addZoomImpulse(gameAmount, hudAmount);
        }
    }

    @Override
    public void eventPlayAnimation(String target, String animation) {
        if (animation == null || animation.isBlank()) return;
        if (extraCharacters.exists(target)) {
            extraCharacters.play(target, animation);
            return;
        }
        if (psychScene != null) psychScene.playSpecialAnimation(target, animation);
        playMinecraftCharacterAnimation(target, animation);
    }

    @Override
    public void eventCameraFollow(Double x, Double y, Double z, String easing,
                                  boolean overrideMovement, boolean cameraRelative, boolean extended,
                                  Double durationSeconds) {
        if (psychScene != null) {
            if (!extended || x == null && y == null && z == null) psychScene.forceCamera(x, y);
            else psychScene.forceCameraExtended(x, y, overrideMovement, easing);
        }
        GameplayCamera.forceFramePosition(x, y, z, easing, overrideMovement, cameraRelative, extended,
                durationSeconds);
    }

    @Override
    public void eventCameraRotation(Double pitch, Double yaw, Double roll, String easing) {
        GameplayCamera.rotateTo(pitch, yaw, roll, easing);
    }

    @Override
    public void eventAltIdle(String target, String suffix) {
        if (psychScene != null) psychScene.setIdleSuffix(target, suffix);
        if (target.equals("boyfriend")) playerIdleSuffix = suffix == null ? "" : suffix;
        else if (target.equals("dad")) opponentIdleSuffix = suffix == null ? "" : suffix;
    }

    @Override
    public void eventScreenShake(double gameDuration, double gameIntensity,
                                 double hudDuration, double hudIntensity) {
        GameplayCamera.shake(gameDuration, gameIntensity, hudDuration, hudIntensity);
    }

    @Override
    public void eventChangeCharacter(String target, String characterId) {
        if (characterId == null || characterId.isBlank()) return;
        String selected = characterId.trim();
        if (extraCharacters.changeDefinition(target, selected, null)) return;
        String animationDefinition = CharacterAnimations.modSet(selected);
        if (psychScene != null) psychScene.changeCharacter(target, selected);
        if (restrictsMinecraftSongAssets()) return;
        if (target.equals("boyfriend")) eventPlayerIcon = selected;
        else if (target.equals("dad")) eventOpponentIcon = selected;
        if (localControlsRole(target)) {
            if (!CharacterAnimations.isDisabled(myAnimSet)) myAnimSet = animationDefinition;
        } else if (partnerControlsRole(target)) {
            if (!CharacterAnimations.isDisabled(partnerAnimSet)) partnerAnimSet = animationDefinition;
        }
        String activeSet = localControlsRole(target) ? myAnimSet
                : partnerControlsRole(target) ? partnerAnimSet : animationDefinition;
        GameplayCamera.setBaseOffset(target.equals("boyfriend"),
                CharacterAnimations.baseCameraOffset(activeSet,
                        target.equals("dad") ? "opponent" : "player"));

        // Apply the new form now. The beat loop skips playIdle for a loopIdle
        // definition, so without this a change to one would never swap the form —
        // it worked for other characters only because their idle re-applied it.
        if (target.equals("boyfriend") || target.equals("dad")) {
            boolean boyfriendSide = target.equals("boyfriend");
            if (performerEntity(boyfriendSide) instanceof Player performer) {
                CharacterAnimations.prepare(performer, activeSet, boyfriendSide ? "player" : "opponent");
            }
        }
    }

    @Override
    public void eventChangeScrollSpeed(double multiplier, double durationSeconds) {
        if (ClientOptions.get().constantScrollSpeed || !Double.isFinite(multiplier)) return;
        eventScrollFrom = currentEventScrollMultiplier();
        eventScrollTarget = Math.max(0.01, multiplier);
        eventScrollStartMs = songPos;
        eventScrollDurationMs = Math.max(0, durationSeconds) * 1000.0
                / Math.max(0.01, songPlayer.playbackRate());
        if (eventScrollDurationMs <= 0) eventScrollMultiplier = eventScrollTarget;
    }

    @Override
    public void eventSetProperty(String property, Object value) {
        if (property == null || property.isBlank()) return;
        if (luaRuntime != null) luaRuntime.setPropertyFromEvent(property, value);
        else psychLuaSetProperty(property, value);
    }

    @Override
    public void eventPlaySound(String sound, float volume) {
        if (sound == null || sound.isBlank()) return;
        float gain = Math.max(0, volume);
        if (luaRuntime != null) luaRuntime.playSoundEvent(sound, gain);
        else HitsoundPlayer.play(assetResolver.sound(sound), gain);
    }

    @Override
    public void eventAddCharacter(String tag, String definition, double x, double y, double z,
                                  double rotation, String animation, String role) {
        extraCharacters.create(tag, definition, x, y, z, (float) rotation, animation, role);
    }

    @Override
    public void eventRemoveCharacter(String tag) {
        extraCharacters.remove(tag);
    }

    @Override
    public void eventTweenCharacter(String tag, Double x, Double y, Double z, Double rotation,
                                    double seconds, String easing) {
        // bf/dad target the real stage performers (Legacy/Minecraft); other tags
        // fall through to the client-only Add Character roster.
        PerformerTween performer = performerTweenFor(tag);
        if (performer != null) {
            performer.begin(x, y, z, rotation, seconds, easing);
            return;
        }
        extraCharacters.tween(tag, x, y, z, rotation, seconds, easing);
    }

    private PerformerTween performerTweenFor(String tag) {
        if (tag == null) return null;
        String value = tag.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (value) {
            case "bf", "boyfriend", "player" -> playerPerformerTween;
            case "dad", "opponent" -> opponentPerformerTween;
            default -> null;
        };
    }

    /**
     * Lua bf/dad property routing. Only active outside FNF mode, where those
     * names address the 3D stage performers rather than Psych sprites.
     */
    private PerformerTween luaPerformerTween(String tag) {
        if (playbackPolicy == null || playbackPolicy.usesPsychCamera()) return null;
        return performerTweenFor(tag);
    }

    private boolean localControlsRole(String role) {
        return (myChartSideIsPlayer && role.equals("boyfriend"))
                || (!myChartSideIsPlayer && role.equals("dad"));
    }

    private boolean partnerControlsRole(String role) {
        return partnerId != null && ((myChartSideIsPlayer && role.equals("dad"))
                || (!myChartSideIsPlayer && role.equals("boyfriend")));
    }

    private boolean playMinecraftCharacterAnimation(String role, String animation) {
        if (extraCharacters.exists(role)) return extraCharacters.play(role, animation);
        role = minecraftCharacterRole(role);
        if (role.equals("gf")) return false;
        boolean played = false;
        if (localControlsRole(role) && minecraft.player != null) {
            float[] cameraOffset = playMinecraftAnimation(minecraft.player, myAnimSet, myRole(), animation);
            if (cameraOffset != null) {
                lastSingMs = System.currentTimeMillis();
                GameplayCamera.sing(role.equals("boyfriend"), cameraOffset[0], cameraOffset[1]);
                played = true;
            }
        }
        if (partnerControlsRole(role) && minecraft.level != null) {
            Player partner = minecraft.level.getPlayerByUUID(partnerId);
            if (partner != null) {
                float[] cameraOffset = playMinecraftAnimation(partner, partnerAnimSet, partnerRole(), animation);
                if (cameraOffset != null) {
                    partnerLastSingMs = System.currentTimeMillis();
                    GameplayCamera.sing(role.equals("boyfriend"), cameraOffset[0], cameraOffset[1]);
                    played = true;
                }
            }
        }
        return played;
    }

    private static float[] playMinecraftAnimation(Player player, String set, String role,
                                                  String requestedAnimation) {
        if (requestedAnimation == null || requestedAnimation.isBlank()) return null;
        float[] exact = CharacterAnimations.play(player, set, role, requestedAnimation);
        if (exact != null) return exact;
        String conventional = minecraftAnimationName(requestedAnimation);
        return conventional.equalsIgnoreCase(requestedAnimation.trim()) ? null
                : CharacterAnimations.play(player, set, role, conventional);
    }

    private static String minecraftCharacterRole(String role) {
        if (role == null) return "boyfriend";
        String normalized = role.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.equals("gf") || normalized.equals("girlfriend")
                || normalized.equals("speakers")) return "gf";
        if (normalized.equals("dad") || normalized.equals("opponent")) return "dad";
        return "boyfriend";
    }

    /** Play the Blockified/BBS counterpart of Psych's Hey! event. */
    private void playMinecraftHey(String role, double durationSeconds) {
        if (!"boyfriend".equals(role)) return; // Psych Hey! targets BF, GF, or both.
        long idleMarker = System.currentTimeMillis()
                + Math.max(0, Math.round(durationSeconds * 1000.0)) - Math.round(singHoldMs());
        if (localControlsRole(role) && minecraft.player != null
                && CharacterAnimations.play(minecraft.player, myAnimSet, myRole(), "hey") != null) {
            lastSingMs = idleMarker;
        }
        if (partnerControlsRole(role) && minecraft.level != null) {
            Player partner = minecraft.level.getPlayerByUUID(partnerId);
            if (partner != null && CharacterAnimations.play(partner, partnerAnimSet,
                    partnerRole(), "hey") != null) {
                partnerLastSingMs = idleMarker;
            }
        }
    }

    private static String minecraftAnimationName(String animation) {
        String lower = animation.trim().toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("singleft")) return "left";
        if (lower.startsWith("singdown")) return "down";
        if (lower.startsWith("singup")) return "up";
        if (lower.startsWith("singright")) return "right";
        if (lower.contains("miss")) return "miss";
        if (lower.contains("hey") || lower.contains("cheer")) return "hey";
        if (lower.contains("idle") || lower.startsWith("dance")) return "idle";
        return lower;
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
            coverEnds.add(new CoverEnd(lane, laneX(true, lane), laneY(true, lane), System.currentTimeMillis()));
            if (coverEnds.size() > 8) coverEnds.remove(0);
        }
    }

    /**
     * How long a sing animation holds before the character returns to idle. FNF
     * ties this to the chart instead of wall time: Psych's default singDuration
     * of four steps is one beat, so it scales with the current BPM.
     */
    private void prepareCharacterForms() {
        if (minecraft.player != null) {
            CharacterAnimations.prepare(minecraft.player, myAnimSet, myRole());
        }
        if (partnerId != null && minecraft.level != null) {
            Player partner = minecraft.level.getPlayerByUUID(partnerId);
            if (partner != null) CharacterAnimations.prepare(partner, partnerAnimSet, partnerRole());
        }
    }

    private double singHoldMs() {
        double bpm = conductor.bpmAt(Math.max(0, songPos));
        if (!Double.isFinite(bpm) || bpm <= 0) return 600;
        return Mth.clamp(60000.0 / bpm, 100.0, 3000.0);
    }

    private void playIdle(Player p, String set, String role, int beat) {
        String suffix = "player".equals(role) ? playerIdleSuffix : opponentIdleSuffix;
        boolean hasSecondIdle = CharacterAnimations.hasAction(set, "idle2");
        // Minecraft animation sets have two canonical idle slots. Non-empty
        // Psych alt-idle suffix selects slot 2 as their Legacy/Minecraft variant.
        if (!hasSecondIdle && (beat & 1) == 1) return;
        String action = !suffix.isBlank() && hasSecondIdle ? "idle2"
                : (beat & 1) == 1 ? "idle2" : "idle";
        if (CharacterAnimations.play(p, set, role, action) == null && !"idle".equals(action)) {
            CharacterAnimations.play(p, set, role, "idle");
        }
    }

    /** Keep Minecraft's real heart/food state locked to Blockified while vanilla HUD is active. */
    private void syncVanillaHud() {
        if (editorPlaytest || !"vanilla".equals(effectiveHudStyle()) || minecraft.player == null) return;
        float maxHp = minecraft.player.getMaxHealth();
        float target = Math.max(1f, Math.min(maxHp, maxHp * (health / 2f)));
        int half = Math.round(target * 2f);
        int food = Math.max(0, Math.min(20, Math.round(accuracy() * 20f)));
        long now = System.currentTimeMillis();
        boolean localDrift = Math.round(minecraft.player.getHealth() * 2f) != half
                || minecraft.player.getFoodData().getFoodLevel() != food;
        if (half != lastSentHealthHalf || food != lastSentFoodLevel || localDrift
                || now - lastVanillaHudSyncMs >= 1000) {
            lastSentHealthHalf = half;
            lastSentFoodLevel = food;
            lastVanillaHudSyncMs = now;
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new FnfPayloads.SyncVanillaHudC2S(half / 2f, food));
        }
    }

    private static void alignBody(Player p) {
        if (p == null) return;
        float yaw = p.getYHeadRot();
        p.yBodyRot = yaw;
        p.yBodyRotO = yaw;
    }

    /**
     * Stage-local offset (X right, Y up, Z forward) plus a yaw offset for one main
     * performer, interpolated over time. Applied to the real Minecraft entity so
     * the gameplay camera, which follows the entity, tracks the movement too.
     */
    private static final class PerformerTween {
        private boolean active;
        private long start;
        private double durationMs;
        private String ease = "linear";
        private double fromX, fromY, fromZ, fromRotation;
        private double toX, toY, toZ, toRotation;
        private boolean tweenRotation;
        double x, y, z, rotation;
        // The performer's resting world position/yaw, i.e. exactly where the song
        // teleported it. 0,0,0 maps here, so the transform offset is honored for free.
        double homeX, homeY, homeZ;
        float homeYaw;
        boolean homeKnown;
        private double appliedX, appliedY, appliedZ;
        private boolean appliedKnown;

        void begin(Double tx, Double ty, Double tz, Double rot, double seconds, String easing) {
            fromX = x; fromY = y; fromZ = z; fromRotation = rotation;
            toX = tx == null ? x : finite(tx);
            toY = ty == null ? y : finite(ty);
            toZ = tz == null ? z : finite(tz);
            tweenRotation = rot != null;
            toRotation = rot == null || !Double.isFinite(rot) ? rotation : rot;
            if (seconds <= 0) {
                x = toX; y = toY; z = toZ;
                if (tweenRotation) rotation = toRotation;
                active = false;
                return;
            }
            durationMs = seconds * 1000.0;
            ease = easing == null || easing.isBlank() ? "linear" : easing;
            start = GameplayClock.now();
            active = true;
        }

        void update(long now) {
            if (!active) return;
            double t = durationMs <= 0 ? 1 : Math.min(1.0, (now - start) / durationMs);
            double f = Easing.apply(ease, t);
            x = fromX + (toX - fromX) * f;
            y = fromY + (toY - fromY) * f;
            z = fromZ + (toZ - fromZ) * f;
            if (tweenRotation) rotation = fromRotation + (toRotation - fromRotation) * f;
            if (t >= 1.0) active = false;
        }

        void captureHome(double hx, double hy, double hz, float yaw) {
            homeX = hx; homeY = hy; homeZ = hz; homeYaw = yaw;
            homeKnown = true;
        }

        /**
         * Where this tween last placed the entity. Comparing it against the
         * entity's actual position is how an outside teleport is noticed: a
         * command or a server correction moves the performer, and without this
         * the next frame would drag it straight back, flickering between the two.
         */
        void markApplied(double ax, double ay, double az) {
            appliedX = ax; appliedY = ay; appliedZ = az;
            appliedKnown = true;
        }

        void clearApplied() { appliedKnown = false; }

        boolean movedExternally(double cx, double cy, double cz, double threshold) {
            return appliedKnown && (Math.abs(cx - appliedX) > threshold
                    || Math.abs(cy - appliedY) > threshold
                    || Math.abs(cz - appliedZ) > threshold);
        }

        // Direct offset control for Lua setProperty / doTween, which drive the
        // value each frame; cancel any timed tween so the two do not fight.
        void setOffsetX(double value) { active = false; x = finite(value); }
        void setOffsetY(double value) { active = false; y = finite(value); }
        void setOffsetZ(double value) { active = false; z = finite(value); }
        void setOffsetRotation(double value) {
            active = false; rotation = finite(value); tweenRotation = true;
        }

        boolean rotates() { return tweenRotation || rotation != 0; }

        boolean engaged() {
            return active || x != 0 || y != 0 || z != 0 || rotation != 0;
        }

        void reset() {
            active = false;
            x = y = z = rotation = 0;
            tweenRotation = false;
            homeKnown = false;
            appliedKnown = false;
        }

        private static double finite(double value) {
            return Double.isFinite(value) ? value : 0;
        }
    }

    /**
     * How far a performer must jump in one frame to count as an outside teleport
     * rather than ordinary physics drift between frames. Well above a falling
     * performer's per-frame movement, well below any useful /tp distance.
     */
    private static final double EXTERNAL_TELEPORT_BLOCKS = 2.0;

    /** Moves the main performers each frame while a stage-offset tween is engaged. */
    private void applyPerformerTweens() {
        if (playbackPolicy == null || playbackPolicy.usesPsychCamera()) return;
        if (phase != Phase.PLAYING && phase != Phase.COUNTDOWN) return;
        long now = GameplayClock.now();
        playerPerformerTween.update(now);
        opponentPerformerTween.update(now);
        applyPerformer(true, playerPerformerTween);
        applyPerformer(false, opponentPerformerTween);
    }

    private void applyPerformer(boolean playerSide, PerformerTween tween) {
        if (minecraft.level == null) return;
        Entity entity = performerEntity(playerSide);
        if (entity == null || entity.isRemoved()) return;

        // While no offset is applied, keep learning the performer's real resting
        // spot (its teleport position, transform included). This is what 0,0,0
        // resolves to, and it also holds a moving performer's return target fixed.
        if (!tween.engaged()) {
            tween.captureHome(entity.getX(), entity.getY(), entity.getZ(), entity.getYRot());
            tween.clearApplied();
            return;
        }
        if (!tween.homeKnown) {
            tween.captureHome(entity.getX(), entity.getY(), entity.getZ(), entity.getYRot());
        }

        // Something outside this tween moved the performer - a /tp from a command
        // event or Lua, or a server position correction. Re-pinning it every frame
        // would flicker between the two positions, so the teleport wins: drop the
        // stage offset and treat the new spot as the performer's resting place.
        if (tween.movedExternally(entity.getX(), entity.getY(), entity.getZ(),
                EXTERNAL_TELEPORT_BLOCKS)) {
            tween.reset();
            tween.captureHome(entity.getX(), entity.getY(), entity.getZ(), entity.getYRot());
            return;
        }

        // Cached facing: re-reading the block would rotate the axes if the tween
        // carried the performer past the machine's view distance (chunk unloaded).
        Direction facing = StageOrientation.facing();
        // Offsets are stage-local: X camera-right, Y up, Z camera-forward.
        Direction right = facing.getCounterClockWise();
        double worldX = tween.homeX + right.getStepX() * tween.x + facing.getStepX() * tween.z;
        double worldY = tween.homeY + tween.y;
        double worldZ = tween.homeZ + right.getStepZ() * tween.x + facing.getStepZ() * tween.z;
        PerformerPin.pin(entity, worldX, worldY, worldZ);
        tween.markApplied(worldX, worldY, worldZ);

        // Rotate only performers the local client does not steer, so a moving
        // opponent/bot can turn without fighting the local player's own look.
        if (tween.rotates() && entity != minecraft.player) {
            float yaw = tween.homeYaw + (float) tween.rotation;
            entity.setYRot(yaw);
            if (entity instanceof Player performer) {
                performer.setYHeadRot(yaw);
                performer.setYBodyRot(yaw);
            }
        }
    }

    /** Returns a displaced performer to its resting spot, then clears the tween. */
    private void resetPerformer(boolean playerSide, PerformerTween tween) {
        if (tween.homeKnown && tween.engaged() && minecraft.level != null) {
            Entity entity = performerEntity(playerSide);
            if (entity != null && !entity.isRemoved()) {
                PerformerPin.pin(entity, tween.homeX, tween.homeY, tween.homeZ);
                entity.setYRot(tween.homeYaw);
                if (entity instanceof Player performer) {
                    performer.setYHeadRot(tween.homeYaw);
                    performer.setYBodyRot(tween.homeYaw);
                }
            }
        }
        tween.reset();
    }

    private static boolean isGravityProperty(String property) {
        return property.equals("grav") || property.equals("gravity");
    }

    private static boolean isShadowProperty(String property) {
        return property.equals("shadow") || property.equals("shadows");
    }

    private static boolean isCollisionProperty(String property) {
        return property.equals("collision") || property.equals("collisions")
                || property.equals("solid");
    }

    // Original noGravity per performer entity, so a chart that disables gravity is
    // undone on restart or quit instead of leaving the player floating.
    private final java.util.Map<Integer, Boolean> performerGravityOriginal = new java.util.HashMap<>();

    private void setPerformerGravity(Entity entity, boolean enabled) {
        performerGravityOriginal.putIfAbsent(entity.getId(), entity.isNoGravity());
        entity.setNoGravity(!enabled);
    }

    /**
     * Turns character-to-character pushing on or off for one performer. Real
     * players keep their block collision: only the push between entities is
     * suppressed, through {@link PerformerCollisions}.
     */
    public boolean psychLuaSetCharacterCollision(String tag, boolean enabled) {
        if (extraCharacters.exists(tag)) return extraCharacters.setCollision(tag, enabled);
        Entity performer = performerEntityForTag(tag);
        if (performer == null) return false;
        PerformerCollisions.setEnabled(performer.getId(), enabled);
        return true;
    }

    public boolean psychLuaCharacterCollision(String tag) {
        if (extraCharacters.exists(tag)) return extraCharacters.collision(tag);
        Entity performer = performerEntityForTag(tag);
        return performer != null && PerformerCollisions.enabled(performer.getId());
    }

    /**
     * Releases a performer from stage-offset control and puts it back on its
     * resting spot. Use this after cancelling a tween when the performer should
     * stop being held in place, for example before teleporting it somewhere else.
     */
    public boolean psychLuaResetCharacterPosition(String tag) {
        if (extraCharacters.exists(tag)) {
            return extraCharacters.setPosition(tag, 0, 0, 0);
        }
        PerformerTween tween = performerTweenFor(tag);
        if (tween == null) return false;
        // performerTweenFor already resolved the side; reuse that decision so the
        // entity looked up here is the same one the tween was driving.
        resetPerformer(tween == playerPerformerTween, tween);
        return true;
    }

    /**
     * Shows or hides one performer's vanilla blob shadow. This is the dark oval
     * on the ground, not a BBS form's shader shadow.
     */
    public boolean psychLuaSetCharacterShadow(String tag, boolean enabled) {
        if (extraCharacters.exists(tag)) return extraCharacters.setShadow(tag, enabled);
        Entity performer = performerEntityForTag(tag);
        if (performer == null) return false;
        PerformerShadows.setEnabled(performer.getId(), enabled);
        return true;
    }

    public boolean psychLuaCharacterShadow(String tag) {
        if (extraCharacters.exists(tag)) return extraCharacters.shadow(tag);
        Entity performer = performerEntityForTag(tag);
        return performer != null && PerformerShadows.enabled(performer.getId());
    }

    public void psychLuaSetAllCharacterShadows(boolean enabled) {
        for (boolean side : new boolean[]{true, false}) {
            Entity performer = performerEntity(side);
            if (performer != null) PerformerShadows.setEnabled(performer.getId(), enabled);
        }
        extraCharacters.setAllShadows(enabled);
    }

    /** Applies one collision setting to both players and every chart performer. */
    public void psychLuaSetAllCharacterCollisions(boolean enabled) {
        for (boolean side : new boolean[]{true, false}) {
            Entity performer = performerEntity(side);
            if (performer != null) PerformerCollisions.setEnabled(performer.getId(), enabled);
        }
        extraCharacters.setAllCollisions(enabled);
    }

    private void restorePerformerGravity() {
        if (!performerGravityOriginal.isEmpty() && minecraft.level != null) {
            for (var entry : performerGravityOriginal.entrySet()) {
                Entity entity = minecraft.level.getEntity(entry.getKey());
                if (entity != null) entity.setNoGravity(entry.getValue());
            }
        }
        performerGravityOriginal.clear();
    }

    /** Resolves a bf/dad Lua tag to the live stage entity, in any playback mode. */
    private Entity performerEntityForTag(String tag) {
        if (tag == null) return null;
        return switch (tag.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "bf", "boyfriend", "player" -> performerEntity(true);
            case "dad", "opponent" -> performerEntity(false);
            default -> null;
        };
    }

    /** The Minecraft entity standing on the requested chart side, if present. */
    private Entity performerEntity(boolean playerSide) {
        if (playerSide == myChartSideIsPlayer) return minecraft.player;
        if (partnerId != null) {
            return minecraft.level == null ? null : minecraft.level.getPlayerByUUID(partnerId);
        }
        return minecraft.level == null || botEntityId < 0 ? null
                : minecraft.level.getEntity(botEntityId);
    }

    private static boolean isWorldPixelProperty(String property) {
        return switch (property) {
            case "worldX", "worldY", "worldZ", "world.x", "world.y", "world.z" -> true;
            default -> false;
        };
    }

    /**
     * A performer's live world position expressed in Lua world-sprite pixels
     * relative to the Funkin' Machine, matching {@link com.fnfmod.client.render.LuaWorldObjectRenderer}
     * (64 px per block; X stage-right, Y down, Z stage-forward). Returns null
     * when the performer or level is unavailable.
     */
    private Double performerWorldPixel(Entity performer, String property) {
        if (performer == null || minecraft.level == null) return null;
        Direction facing = StageOrientation.facing();
        Direction right = facing.getCounterClockWise();
        double dx = performer.getX() - (machinePos.getX() + 0.5);
        double dy = performer.getY() - (machinePos.getY() + 0.5);
        double dz = performer.getZ() - (machinePos.getZ() + 0.5);
        double scale = 64.0; // com.fnfmod.client.render.LuaWorldObjectRenderer.PIXEL_SCALE is 1/64
        return switch (property) {
            case "worldX", "world.x" -> (dx * right.getStepX() + dz * right.getStepZ()) * scale;
            case "worldY", "world.y" -> -dy * scale;
            case "worldZ", "world.z" -> (dx * facing.getStepX() + dz * facing.getStepZ()) * scale;
            default -> null;
        };
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
            return songPlayer.positionMs() + chart.offsetMs + ClientOptions.get().offsetMs;
        }
        return songPos;
    }

    /**
     * Song position for an input captured at a specific {@link System#nanoTime()}.
     * Judging against the press instant instead of the current frame removes the
     * up-to-one-frame timing error, which is what a high-rate input path needs.
     */
    private double liveSongPosAt(long eventNano) {
        if (songPlayer.isStarted() && !songPlayer.isPaused()) {
            return songPlayer.positionMsAt(eventNano) + chart.offsetMs + ClientOptions.get().offsetMs;
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

    /** Bot auto-hits your notes on time and sustains holds. Score is not saved. */
    private void botplayTick() {
        if (!ClientOptions.get().botplay || phase != Phase.PLAYING) return;
        for (int lane = 0; lane < 4; lane++) {
            List<GameNote> list = myLanes[lane];
            for (int i = myLaneIndex[lane]; i < list.size(); i++) {
                GameNote n = list.get(i);
                if (n.data.timeMs > songPos) break;
                if (!n.hit && !n.missed) {
                    if (!laneHeld[lane]) laneHeld[lane] = true;
                    hitAttempt(lane, false);
                }
            }
            // Keep the lane pressed while a sustain runs so the hold logic sustains
            // it; release once nothing is holding, so tap receptors relax normally.
            laneHeld[lane] = !activeHolds[lane].isEmpty();
        }
    }

    /**
     * The full effect of a lane key going down, at the given press timestamp.
     * Shared by the direct GLFW handler and the drained precise-input queue, so
     * both judge presses identically — only the timestamp source differs.
     */
    private void processLanePress(int lane, long nano) {
        if (laneHeld[lane]) return; // already down
        // Psych's pre pass can swallow the press entirely before anything reacts.
        if (luaRuntime != null && !luaRuntime.onKeyPressPre(lane)) return;
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
            hitAttempt(lane, phase == Phase.PLAYING && !resumed, liveSongPosAt(nano));
            if (phase == Phase.COUNTDOWN) myStrumFlash[lane] = Math.max(myStrumFlash[lane], 40);
        }
        if (luaRuntime != null) luaRuntime.onKeyPress(lane);
    }

    /**
     * Lazily starts the Windows raw-key backend and keeps its active state in sync
     * with focus and phase. It samples only while the window is focused and a song
     * is running; the lanes it maps are handled solely by it, the rest by GLFW.
     */
    private void updateRawInput() {
        if (rawInput == null && !rawInputUnavailable
                && com.fnfmod.client.input.WindowsRawKeyBackend.isSupported()) {
            // The press hook runs on the backend thread: it sounds the hit the moment
            // the press is seen. positionMsAt and the snapshot are both thread-safe.
            var backend = new com.fnfmod.client.input.WindowsRawKeyBackend(noteInput,
                    (lane, nano) -> hitsoundSnapshot.claim(lane, liveSongPosAt(nano)));
            backend.setKeys(FnfKeys.currentKeyCodes());
            if (backend.start()) rawInput = backend;
            else rawInputUnavailable = true; // load failed; stay on GLFW input
        }
        if (rawInput != null) {
            // Botplay ignores player input; the raw backend reads hardware directly,
            // so it must be silenced too or physical presses would sound extra hits.
            boolean active = (phase == Phase.PLAYING || phase == Phase.COUNTDOWN)
                    && !ClientOptions.get().botplay
                    && minecraft != null && minecraft.isWindowActive();
            rawInput.setActive(active);
        }
    }

    /** The full effect of a lane key going up, at the given release timestamp. */
    private void processLaneRelease(int lane, long nano) {
        if (luaRuntime != null && !luaRuntime.onKeyReleasePre(lane)) return;
        laneHeld[lane] = false;
        // Precise release: mark held sustains at the exact position the key went
        // up, so the re-press grace measures from that instant rather than the
        // frame. The drain runs before the hold loop, so this wins over the
        // frame-based fallback there. Off keeps the original frame timing.
        if (ClientOptions.get().preciseInput) {
            double releasePos = liveSongPosAt(nano);
            for (GameNote hold : activeHolds[lane]) {
                if (hold.releasedMs < 0) hold.releasedMs = releasePos;
            }
        }
        if (luaRuntime != null) luaRuntime.onKeyRelease(lane);
    }

    private static final com.fnfmod.client.input.HitsoundSnapshot.Hittable[] NO_HITTABLE =
            new com.fnfmod.client.input.HitsoundSnapshot.Hittable[0];

    /**
     * Publishes this frame's hittable notes so the input backend can sound a hit
     * the instant it detects the press. Only the notes near the current position
     * are listed, each with its exact hitsound recipe, so the backend and the game
     * thread always play the same sound for a given note.
     */
    private void publishHitsoundSnapshot() {
        hitsoundSnapshot.setWindow(SHIT);
        double lo = songPos - SHIT;
        double hi = songPos + SHIT;
        for (int lane = 0; lane < 4; lane++) {
            List<GameNote> notes = myLanes[lane];
            List<com.fnfmod.client.input.HitsoundSnapshot.Hittable> hittable = null;
            for (int i = myLaneIndex[lane]; i < notes.size(); i++) {
                GameNote n = notes.get(i);
                if (n.data.timeMs > hi) break; // lanes stay time-sorted
                if (n.hit || n.missed || n.data.blockHit || n.data.timeMs < lo) continue;
                if (hittable == null) hittable = new ArrayList<>(4);
                hittable.add(hittableFor(n));
            }
            hitsoundSnapshot.publish(lane, hittable == null ? NO_HITTABLE
                    : hittable.toArray(new com.fnfmod.client.input.HitsoundSnapshot.Hittable[0]));
        }
        hitsoundSnapshot.prune(lo);
    }

    /** The hitsound recipe for a note, mirroring the credit-time logic exactly. */
    private com.fnfmod.client.input.HitsoundSnapshot.Hittable hittableFor(GameNote n) {
        if (n.data.hitsoundDisabled || n.data.hitsoundVolume <= 0) {
            return new com.fnfmod.client.input.HitsoundSnapshot.Hittable(n.data.timeMs, null, false, 0);
        }
        if (!n.hitsoundResolved) {
            n.cachedHitsound = customNoteTextures.resolveSound(n.data.hitsound);
            n.hitsoundResolved = true;
        }
        boolean playDefault = n.cachedHitsound == null && (n.data.hitsound == null
                || n.data.hitsound.isBlank() || n.data.hitsound.equalsIgnoreCase("hitsound"));
        float volume = (float) (ClientOptions.get().hitsoundVolume * n.data.hitsoundVolume);
        return new com.fnfmod.client.input.HitsoundSnapshot.Hittable(
                n.data.timeMs, n.cachedHitsound, playDefault, volume);
    }

    private void hitAttempt(int lane, boolean allowGhostMiss) {
        hitAttempt(lane, allowGhostMiss, liveSongPos());
    }

    /**
     * Judges a lane press against an explicit song position, so a caller that
     * captured the press timestamp can supply the position at that instant. The
     * plain overload above uses the current frame position, as before.
     */
    private void hitAttempt(int lane, boolean allowGhostMiss, double inputPos) {
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

        // The precise-input backend may have already sounded this note sub-frame;
        // if so, skip here (and clear the claim) so it never plays twice. Otherwise
        // play it now through the same recipe, so both paths sound identically.
        if (hitsoundSnapshot.wasClaimed(lane, best.data.timeMs)) {
            hitsoundSnapshot.release(lane, best.data.timeMs);
        } else {
            com.fnfmod.client.input.HitsoundSnapshot.play(hittableFor(best));
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
            recalculateRating(false);
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
                    laneX(true, n.data.lane), laneY(true, n.data.lane), System.currentTimeMillis(),
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
        recalculateRating(true);
        checkDeath();
    }

    private void ghostMiss(int lane) {
        comboBreak();
        score -= 10;
        health = Math.max(0, health - 0.04f);
        muteVoices(myChartSideIsPlayer);
        sing(lane, true);
        if (luaRuntime != null) {
            luaRuntime.onGhostTap(lane);
            luaRuntime.onNoteMissPress(lane);
        }
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
        recalculateRating(true);
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
        recalculateRating(true);
        checkDeath();
    }

    /**
     * Fires Psych's onSpawnNote as each note enters the scroll window. Blockified
     * builds every note up front, so "spawn" is the moment the note would first
     * become visible rather than an allocation.
     */
    private void announceSpawnedNotes() {
        if (luaRuntime == null) return;
        double window = height / Math.max(0.0001, pxPerMs());
        double cutoff = songPos + window;
        for (List<GameNote>[] lanes : List.of(myLanes, otherLanes)) {
            for (List<GameNote> lane : lanes) {
                for (GameNote note : lane) {
                    if (note.data.timeMs > cutoff) break; // lanes stay time-sorted
                    if (note.spawnAnnounced) continue;
                    note.spawnAnnounced = true;
                    luaRuntime.onSpawnNote(chart.notes.indexOf(note.data), note.data.lane,
                            note.data.noteType, note.data.sustainMs > 30, note.data.timeMs);
                }
            }
        }
    }

    /**
     * Psych's rating pipeline: scripts may veto the recalculation with
     * Function_Stop, then get a pre/post pair around the score display update.
     * Blockified computes its rating from live counters, so the hooks exist for
     * script notification and cancellation rather than to hold a cached value.
     */
    private void recalculateRating(boolean miss) {
        if (luaRuntime == null) return;
        if (!luaRuntime.onRecalculateRating()) return;
        if (!luaRuntime.preUpdateScore(miss)) return;
        luaRuntime.onUpdateScore(miss);
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
            // Psych lets onGameOver cancel the death outright, so ask before committing.
            if (luaRuntime != null && !luaRuntime.onGameOver()) {
                health = Math.max(health, 0.001f);
                return;
            }
            phase = Phase.GAMEOVER;
            deaths++;
            songPlayer.pause();
            if (luaRuntime != null) luaRuntime.onGameOverStart();
            if (duet) finishSong(true);
        }
    }

    private void sing(int lane, boolean miss) {
        sing(lane, miss, null);
    }

    private void sing(int lane, boolean miss, SongChart.Note note) {
        String visualRole = note == null ? (myChartSideIsPlayer ? "boyfriend" : "dad")
                : note.gfNote ? "gf" : note.playerSide ? "boyfriend" : "dad";
        animatePsychNote(note, lane, miss, visualRole);
        if (localControlsRole(visualRole) && minecraft.player != null) {
            lastSingMs = System.currentTimeMillis();
            String suffix = note == null || note.animSuffix == null ? "" : note.animSuffix;
            boolean heyNote = !miss && note != null && "Hey!".equalsIgnoreCase(note.noteType);
            String action = heyNote ? "hey" : miss ? "miss" + suffix : DIR_NAMES[lane] + suffix;
            float[] camOff = CharacterAnimations.play(minecraft.player, myAnimSet, myRole(),
                    action);
            if (camOff != null && !miss) {
                GameplayCamera.sing(myChartSideIsPlayer, camOff[0], camOff[1]);
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
            // Botplay and script-altered results are shown but never recorded as a personal best.
            if (!failed && !ClientOptions.get().botplay && !scriptAlteredScore) saveBestScore();
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
        SongChart.Note matchedNote = null;
        if (judgement < 4) {
            otherStrumFlash[lane] = 150;
            // consume the closest unhit note on their side so it disappears
            List<GameNote> list = otherLanes[lane];
            for (int i = otherLaneIndex[lane]; i < list.size(); i++) {
                GameNote n = list.get(i);
                if (n.hit || n.missed) continue;
                if (Math.abs(n.data.timeMs - songPos) < 250) {
                    n.hit = true;
                    matchedNote = n.data;
                }
                break;
            }
        }
        partnerLastSingMs = System.currentTimeMillis();
        animatePsychNote(matchedNote, lane, judgement == 4,
                !myChartSideIsPlayer ? "boyfriend" : "dad");
        // animate the partner's body with their chosen animation set
        if (partnerId != null && minecraft.level != null) {
            Player partner = minecraft.level.getPlayerByUUID(partnerId);
            if (partner != null) {
                boolean heyNote = judgement != 4 && matchedNote != null
                        && "Hey!".equalsIgnoreCase(matchedNote.noteType);
                float[] camOff = CharacterAnimations.play(partner, partnerAnimSet, partnerRole(),
                        heyNote ? "hey" : judgement == 4 ? "miss" : DIR_NAMES[lane]);
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
        if (isForceExitChord(keyCode, modifiers)) {
            forceExit();
            return true;
        }
        // Ctrl+Shift+Space toggles the free camera during an editor playtest.
        if (editorPlaytest && keyCode == GLFW.GLFW_KEY_SPACE
                && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0
                && (modifiers & GLFW.GLFW_MOD_SHIFT) != 0) {
            toggleFreeCam();
            return true;
        }
        if (freeCam) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) { exitFreeCam(); return true; }
            // Copy the shot only while movement (and the grabbed cursor) is off.
            if (keyCode == GLFW.GLFW_KEY_C && !freeCamMove
                    && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0) {
                copyFreeCamShot();
                return true;
            }
            // M cycles Follow Pos Movement (override/attached); F cycles the Frame.
            if (keyCode == GLFW.GLFW_KEY_M) { freeCamOverride = !freeCamOverride; return true; }
            if (keyCode == GLFW.GLFW_KEY_F) { freeCamCameraFrame = !freeCamCameraFrame; return true; }
            return true; // movement uses polled keys; swallow the rest
        }
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
            if (keyCode == GLFW.GLFW_KEY_R && !duet) {
                if (luaRuntime != null) luaRuntime.onGameOverConfirm(true);
                restart();
            }
            if (enter || keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                if (luaRuntime != null) luaRuntime.onGameOverConfirm(false);
                exit();
            }
            return true;
        }
        if (phase == Phase.RESULTS) {
            if (enter || keyCode == GLFW.GLFW_KEY_SPACE) exit();
            return true;
        }

        int lane = FnfKeys.laneForKey(keyCode, scanCode);
        // Botplay owns the strumline; swallow lane keys so manual taps can't
        // interfere, while leaving pause, restart and force-exit keys alone.
        if (lane >= 0 && ClientOptions.get().botplay) return true;
        if (lane >= 0) {
            // Timestamp the press as early as possible. GLFW still dispatches this
            // once per frame, so it only matches "now"; a high-rate input path
            // (later part) supplies an earlier, more accurate nanoTime the same way.
            if (ClientOptions.get().preciseInput) {
                // The raw backend owns any lane it can poll; only the others fall
                // back to GLFW so a mapped key is never counted twice.
                if (rawInput != null && rawInput.handlesLane(lane)) return true;
                if (!glfwLaneHeld[lane]) { // ignore key auto-repeat
                    glfwLaneHeld[lane] = true;
                    noteInput.push(lane, true, System.nanoTime());
                }
                return true;
            }
            if (!laneHeld[lane]) {
                processLanePress(lane, System.nanoTime());
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            enterReady = true;
        }
        int lane = FnfKeys.laneForKey(keyCode, scanCode);
        if (lane >= 0 && ClientOptions.get().botplay) return true;
        if (lane >= 0) {
            if (ClientOptions.get().preciseInput) {
                if (rawInput != null && rawInput.handlesLane(lane)) return true;
                glfwLaneHeld[lane] = false;
                noteInput.push(lane, false, System.nanoTime());
            } else {
                processLaneRelease(lane, System.nanoTime());
            }
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

    // ---------------------------------------------------------- free camera

    private void toggleFreeCam() {
        if (freeCam) exitFreeCam();
        else enterFreeCam();
    }

    private void enterFreeCam() {
        if (!editorPlaytest || freeCam) return;
        if (phase != Phase.PLAYING && phase != Phase.COUNTDOWN) return;
        freeCam = true;
        freeCamPausedAtMs = System.currentTimeMillis();
        songPlayer.pause();
        noteInput.clear();
        hitsoundSnapshot.reset();
        java.util.Arrays.fill(glfwLaneHeld, false);
        freeCamLastMoveNano = System.nanoTime();
        GameplayCamera.beginFreeCam();
        setFreeCamMove(false);
    }

    private void exitFreeCam() {
        if (!freeCam) return;
        setFreeCamMove(false);
        freeCam = false;
        GameplayCamera.endFreeCam();
        // Resume audio/countdown exactly like leaving the pause menu.
        if (songPlayer.isStarted()) {
            songPlayer.resume();
        } else {
            startAtEpochMs += System.currentTimeMillis() - freeCamPausedAtMs;
        }
        lastFrameNano = System.nanoTime();
        noteInput.clear();
    }

    /** Grabs/releases the cursor for spectator look while keeping this screen open. */
    private void setFreeCamMove(boolean move) {
        if (move == freeCamMove) {
            if (!move) return;
        }
        freeCamMove = move;
        long window = minecraft.getWindow().getWindow();
        if (move) {
            double[] cx = new double[1];
            double[] cy = new double[1];
            GLFW.glfwGetCursorPos(window, cx, cy);
            freeCamLastCursorX = cx[0];
            freeCamLastCursorY = cy[0];
            GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
        } else {
            GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
        }
    }

    /** Per-frame free-camera look (captured mouse) and movement (polled keys). */
    private void updateFreeCam() {
        long now = System.nanoTime();
        double dt = Math.min(0.1, (now - freeCamLastMoveNano) / 1_000_000_000.0);
        freeCamLastMoveNano = now;
        if (!freeCamMove) return;

        long window = minecraft.getWindow().getWindow();
        double[] cx = new double[1];
        double[] cy = new double[1];
        GLFW.glfwGetCursorPos(window, cx, cy);
        double dx = cx[0] - freeCamLastCursorX;
        double dy = cy[0] - freeCamLastCursorY;
        freeCamLastCursorX = cx[0];
        freeCamLastCursorY = cy[0];
        double sensitivity = 0.15;
        if (dx != 0 || dy != 0) GameplayCamera.turnFreeCam(dx * sensitivity, dy * sensitivity);

        // Shift = finer, Ctrl = coarser rate for movement, roll and zoom.
        double rate = freeCamRateMultiplier(window);

        double forward = keyDown(window, GLFW.GLFW_KEY_W) - keyDown(window, GLFW.GLFW_KEY_S);
        double strafe = keyDown(window, GLFW.GLFW_KEY_D) - keyDown(window, GLFW.GLFW_KEY_A);
        double vertical = keyDown(window, GLFW.GLFW_KEY_E) - keyDown(window, GLFW.GLFW_KEY_Q);

        // Left/Right arrows roll; Up/Down arrows zoom; R resets roll and zoom.
        double roll = keyDown(window, GLFW.GLFW_KEY_RIGHT) - keyDown(window, GLFW.GLFW_KEY_LEFT);
        if (roll != 0) GameplayCamera.rollFreeCam(roll * 60 * dt * rate);
        double zoom = keyDown(window, GLFW.GLFW_KEY_UP) - keyDown(window, GLFW.GLFW_KEY_DOWN);
        if (zoom != 0) GameplayCamera.zoomFreeCam(zoom * 0.5 * dt * rate);
        if (keyDown(window, GLFW.GLFW_KEY_R) == 1) GameplayCamera.resetFreeRollZoom();

        if (forward != 0 || strafe != 0 || vertical != 0) {
            // Horizontal movement follows the yaw so W goes where the camera looks;
            // Q/E are world up/down. All expressed in the stage frame the Camera
            // Follow Pos offset uses (X right, Y up, Z forward).
            double yaw = Math.toRadians(GameplayCamera.freeYaw());
            double sin = Math.sin(yaw);
            double cos = Math.cos(yaw);
            double step = freeCamSpeed * dt * rate;
            double moveX = (forward * sin + strafe * cos) * step;
            double moveZ = (forward * cos - strafe * sin) * step;
            double moveY = vertical * step;
            GameplayCamera.moveFreeCam(moveX, moveY, moveZ);
        }

        // Never let the camera leave loaded chunks: the client only has terrain
        // out to the render distance around the player, and beyond it the view,
        // lighting and chunk state break. Leave a one-chunk safety margin.
        double maxHorizontal = Math.max(16.0, (RenderDistanceControl.current() - 1) * 16.0);
        int minY = minecraft.level == null ? -64 : minecraft.level.getMinBuildHeight();
        int maxY = minecraft.level == null ? 320 : minecraft.level.getMaxBuildHeight();
        double maxVertical = Math.max(64.0, maxY - minY);
        GameplayCamera.clampFreeCamToLoaded(maxHorizontal, maxVertical);
    }

    /** Held Shift slows the rate values change at; held Ctrl speeds it up. */
    private static double freeCamRateMultiplier(long window) {
        boolean shift = keyDown(window, GLFW.GLFW_KEY_LEFT_SHIFT) == 1
                || keyDown(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == 1;
        boolean ctrl = keyDown(window, GLFW.GLFW_KEY_LEFT_CONTROL) == 1
                || keyDown(window, GLFW.GLFW_KEY_RIGHT_CONTROL) == 1;
        if (shift && !ctrl) return 0.25;
        if (ctrl && !shift) return 4.0;
        return 1.0;
    }

    private static int keyDown(long window, int key) {
        return GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS ? 1 : 0;
    }

    private void copyFreeCamShot() {
        double[] follow = freeCamFollowValues();
        double bx = round2(follow[0]);
        double by = round2(follow[1]);
        double bz = round2(follow[2]);
        double pitch = round2(GameplayCamera.freePitch());
        double yaw = round2(GameplayCamera.freeYaw());
        double roll = round2(GameplayCamera.freeRoll());
        double zoom = round2(GameplayCamera.freeZoom());

        // Camera Follow Pos: extended 3D in the chosen Movement/Frame. v5 override
        // fixes it at the anchor; empty attaches it to the focused character. v6
        // camera aligns to the camera rotation; empty uses the machine facing.
        String movement = freeCamOverride ? "override" : "";
        String frame = freeCamCameraFrame ? "camera" : "";
        SongChart.Event pos = new SongChart.Event(0, ChartEventTypes.CAMERA_FOLLOW_POS,
                num(bx), num(by), num(bz), "", movement, frame, false);
        // Camera Rotation 3D: pitch, yaw, roll (v4 = ease, left empty).
        SongChart.Event rot = new SongChart.Event(0, ChartEventTypes.CAMERA_ROTATION_3D,
                num(pitch), num(yaw), num(roll), "", "", false);
        // Camera Zoom: amount (v2 = duration, v3 = ease, left empty).
        SongChart.Event cameraZoom = new SongChart.Event(0, ChartEventTypes.CAMERA_ZOOM,
                num(zoom), "", "", "", "", false);
        CameraShotClipboard.set(java.util.List.of(pos, rot, cameraZoom));
        freeCamMessage = "Copied camera shot — paste in the chart editor";
        freeCamMessageUntil = System.currentTimeMillis() + 3000;
    }

    /** Camera Follow Pos X/Y/Z for the current Movement/Frame options. */
    private double[] freeCamFollowValues() {
        Camera cam = minecraft.gameRenderer.getMainCamera();
        return GameplayCamera.followPosValues(freeCamOverride, freeCamCameraFrame,
                cam.getLeftVector(), cam.getUpVector(), cam.getLookVector());
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static String num(double value) {
        if (value == 0) return "0";
        String s = String.valueOf(value);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    private void pauseSong() {
        if (luaRuntime != null && !luaRuntime.onPause()) return;
        phase = Phase.PAUSED;
        pausedAtMs = System.currentTimeMillis();
        pauseSelection = 0;
        songPlayer.pause();
        // Drop queued presses and any stuck held-state so a pause never leaks input.
        noteInput.clear();
        hitsoundSnapshot.reset();
        java.util.Arrays.fill(glfwLaneHeld, false);
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
        noteInput.clear(); // discard anything queued while paused
        if (luaRuntime != null) luaRuntime.onResume();
    }

    private void activatePauseOption() {
        if (editorPlaytest) {
            switch (pauseSelection) {
                case 0 -> resumeFromPause();
                case 1 -> restart();
                case 2 -> openMinecraftPauseMenu();
                case 3 -> exit();
            }
            return;
        }
        if (duet) {
            if (pauseSelection == 0) resumeFromPause();
            else if (pauseSelection == 1) openMinecraftPauseMenu();
            else exit();
            return;
        }
        switch (pauseSelection) {
            case 0 -> resumeFromPause();
            case 1 -> restart();
            case 2 -> openCurrentChartInEditor();
            case 3 -> openMinecraftPauseMenu();
            case 4 -> exit();
        }
    }

    private int pauseOptionCount() {
        return pauseOptions().length;
    }

    private String[] pauseOptions() {
        return editorPlaytest
                ? new String[]{"Resume", "Restart", "Minecraft Pause Menu", "Return to Editor"}
                : duet
                ? new String[]{"Resume", "Minecraft Pause Menu", "Quit"}
                : new String[]{"Resume", "Restart", "Edit Chart", "Minecraft Pause Menu", "Quit"};
    }

    private void openMinecraftPauseMenu() {
        if (minecraft == null || minecraft.screen instanceof MinecraftPauseOverlayScreen) return;
        openingMinecraftPause = true;
        minecraft.setScreen(new MinecraftPauseOverlayScreen(this));
    }

    private void openCurrentChartInEditor() {
        String currentSongId = ClientSession.songId == null || ClientSession.songId.isBlank()
                ? chart.title : ClientSession.songId;
        SongEntry sourceEntry = SongLibrary.get(currentSongId);
        Path originalDirectory = sourceEntry == null ? ClientSession.resolvedFolder
                : (sourceEntry.chartOriginRoot != null ? sourceEntry.chartOriginRoot
                : (sourceEntry.modRoot != null ? sourceEntry.modRoot : sourceEntry.folder));
        // Captured before the session is reset below: the playtest needs the look
        // this song was actually running with, not a default.
        ChartEditorScreen editor = new ChartEditorScreen(currentSongId,
                ClientSession.difficulty, chart, ClientSession.resolvedFolder, originalDirectory,
                machinePos, playbackPolicy);

        GameplayCamera.end();
        PacketDistributor.sendToServer(new FnfPayloads.LeaveC2S(machinePos, false, false));
        ClientSession.reset();
        if (minecraft.player != null) CharacterAnimations.stop(minecraft.player);
        // setScreen removes this gameplay screen, which disposes its SongPlayer;
        // the editor loads its own audio streams from the captured song folder.
        minecraft.setScreen(editor);
    }

    /**
     * Restarts by tearing this run down and playing the song again from a brand
     * new screen, which is exactly what quitting and re-entering does. Resetting
     * fields in place had to remember every piece of state a chart, event or Lua
     * script could touch, and anything missed - tween targets in particular -
     * carried over into the next attempt. Rebuilding cannot miss anything.
     */
    private void restart() {
        // The machine cannot rotate between runs, so keep the captured facing
        // across the teardown: the new screen's stage teleport then uses it even
        // if the player wandered far and the machine chunk is currently unloaded.
        Direction restartFacing = StageOrientation.facing();
        boolean facingKnown = StageOrientation.isCaptured();
        // Drop every per-run resource. The audio player is kept because the new
        // screen reuses this instance, and marking the run disposed here stops
        // removed() from tearing the replacement's camera down behind it.
        disposeResources(false);
        if (facingKnown) StageOrientation.set(restartFacing);
        // Performer tweens move the real entities, and the new screen cannot know
        // where they started. Put them back on their marks before it takes over.
        resetPerformer(true, playerPerformerTween);
        resetPerformer(false, opponentPerformerTween);
        if (minecraft.player != null) CharacterAnimations.stop(minecraft.player);

        // onCreate and chart events may edit notes, so hand the new screen the
        // chart exactly as it was parsed.
        chart.notes.clear();
        originalLuaNotes.stream().map(SongChart.Note::copy).forEach(chart.notes::add);
        chart.sortNotes();

        songPlayer.reset();
        songPlayer.setPlaybackRate(1f);
        songPlayer.setPlayerVoiceVolume(1f);
        songPlayer.setOpponentVoiceVolume(1f);

        GameplayScreen next = new GameplayScreen(machinePos, chart, songPlayer, mode, partnerId,
                partnerName, initialPartnerAnimSet, botEntityId, System.currentTimeMillis() + 2000);
        if (editorPlaytest) carryEditorPlaytestState(next);
        minecraft.setScreen(next);
    }

    /**
     * Copies the editor playtest setup onto a restarted screen. The normal
     * constructor resolves its song from the active session, which a playtest
     * does not have, so the same overrides the playtest factory applies are
     * repeated here.
     */
    private void carryEditorPlaytestState(GameplayScreen next) {
        next.editorPlaytest = true;
        next.editorPreview = editorPreview;
        next.editorStartMs = editorStartMs;
        next.editorReturnFactory = editorReturnFactory;
        next.editorReturnPos = editorReturnPos;
        next.editorReturnYaw = editorReturnYaw;
        next.editorReturnPitch = editorReturnPitch;
        next.runtimeSongId = runtimeSongId;
        next.runtimeSongFolder = runtimeSongFolder;
        next.runtimeSongEntry = runtimeSongEntry;
        next.playbackPolicy = playbackPolicy;
        CharacterAnimations.useSongFolder(
                playbackPolicy.songAssets() && runtimeSongEntry != null
                        ? runtimeSongEntry.animationRoot() : null,
                chart.player1, chart.player2);
        next.assetResolver = new PsychAssetResolver(runtimeSongFolder, runtimeSongEntry,
                playbackPolicy, chart.stage);
        next.customNoteTextures.close();
        next.psychScene.close();
        next.customNoteTextures = new PsychNoteTextureCache(
                next.assetResolver.customNoteRoots(),
                playbackPolicy.allows(runtimeSongEntry, SongLibrary.ExternalContent.IMAGES));
        next.psychScene = PsychGameplayScene.load(chart, runtimeSongFolder, runtimeSongEntry,
                playbackPolicy);
        // The constructor already started a session camera; swap it for the
        // editor-safe virtual stage, exactly like the playtest factory does.
        GameplayCamera.end();
        next.beginCamera();
        next.applyPsychCameraDefaults();
        next.prepareEditorStart();
    }

    private void exit() {
        GameplayCamera.end();
        if (editorPlaytest) {
            songPlayer.dispose();
            if (minecraft.player != null) {
                CharacterAnimations.stop(minecraft.player);
                if (editorReturnPos != null) {
                    minecraft.player.setPos(editorReturnPos.x, editorReturnPos.y, editorReturnPos.z);
                    minecraft.player.setYRot(editorReturnYaw);
                    minecraft.player.setXRot(editorReturnPitch);
                    // Same reason as the stage placement: without a server tp the
                    // player would be snapped back to the playtest spot on exit.
                    editorServerTeleport(editorReturnPos.x, editorReturnPos.y, editorReturnPos.z,
                            editorReturnYaw);
                }
            }
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

    /**
     * Hard-coded recovery path for a broken chart or Lua script. This is deliberately
     * not exposed as a configurable key mapping, so Ctrl+Shift+Enter always remains
     * available. Explicit disposal also handles the vanilla pause overlay, where this
     * gameplay screen is alive but is not Minecraft's current screen.
     */
    void forceExit() {
        if (forceExitStarted) return;
        forceExitStarted = true;
        exit();
        disposeResources();
    }

    static boolean isForceExitChord(int keyCode, int modifiers) {
        boolean enter = keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER;
        return enter
                && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0
                && (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (freeCam) {
            // Right-click toggles spectator move/look, like holding a viewport.
            if (button == 1) setFreeCamMove(!freeCamMove);
            return true;
        }
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

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (freeCam) {
            // Wheel changes fly speed (blocks/second), clamped to a usable range.
            double factor = scrollY > 0 ? 1.15 : (scrollY < 0 ? 1 / 1.15 : 1);
            freeCamSpeed = Math.max(0.5, Math.min(80.0, freeCamSpeed * factor));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
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

    /** Psych logical size; PsychCanvas handles resolution and GUI-scale changes. */
    private float noteSize() {
        return DEFAULT_NOTE_SIZE;
    }

    private static void applySongNoteTextures(SongChart chart) {
        if (!ClientOptions.NOTE_SKIN_DEFAULT.equalsIgnoreCase(ClientOptions.get().noteSkin)) return;
        String noteTexture = chart.noteTexture == null ? "" : chart.noteTexture.trim();
        String splashTexture = chart.noteSplashTexture == null ? "" : chart.noteSplashTexture.trim();
        for (SongChart.Note note : chart.notes) {
            if ((note.texture == null || note.texture.isBlank()) && !noteTexture.isBlank()) {
                note.texture = noteTexture;
            }
            if ((note.noteSplashTexture == null || note.noteSplashTexture.isBlank())
                    && !splashTexture.isBlank()) {
                note.noteSplashTexture = splashTexture;
            }
        }
    }

    private String chartDefaultNoteTexture() {
        return ClientOptions.NOTE_SKIN_DEFAULT.equalsIgnoreCase(ClientOptions.get().noteSkin)
                ? chart.noteTexture : "";
    }

    private float laneX(boolean mine, int lane) {
        boolean playerSide = playBoth || mine == myChartSideIsPlayer;
        double overridden = luaStrumX[(playerSide ? 4 : 0) + lane];
        // Psych exposes StrumNote.x as top-left; renderer draws notes by center.
        if (!Double.isNaN(overridden)) return (float) overridden + noteSize() * 0.5f;
        return baseLaneX(mine, lane);
    }

    private float baseLaneX(boolean mine, int lane) {
        float spacing = PSYCH_NOTE_WIDTH;
        if (playBoth) {
            return HUD_WIDTH * 0.5f + (lane - 1.5f) * spacing;
        }
        boolean playerSide = mine == myChartSideIsPlayer;
        if (ClientOptions.get().middlescroll) {
            if (playerSide) {
                return 468f + lane * spacing;
            }
            return lane < 2
                    ? 138f + lane * spacing
                    : 1027f + (lane - 2) * spacing;
        }
        // FNF layout is fixed: player-side notes right, opponent-side left —
        // playing as the opponent doesn't swap the strumlines
        return (playerSide ? 788f : 148f) + lane * spacing;
    }

    /** Center x of the strumline the local player actually plays on. */
    private float myStrumsCenterX() {
        return playBoth ? HUD_WIDTH * 0.5f
                : (myChartSideIsPlayer ? 956f : 316f);
    }

    private float receptorY() {
        float top = ClientOptions.get().downscroll ? HUD_HEIGHT - 150f : 50f;
        return top + noteSize() * 0.5f;
    }

    private float laneY(boolean mine, int lane) {
        boolean playerSide = playBoth || mine == myChartSideIsPlayer;
        double overridden = luaStrumY[(playerSide ? 4 : 0) + lane];
        return Double.isNaN(overridden) ? receptorY() : (float) overridden + noteSize() * 0.5f;
    }

    private boolean laneDown(boolean mine, int lane) {
        boolean playerSide = playBoth || mine == myChartSideIsPlayer;
        return luaStrumDownScroll[(playerSide ? 4 : 0) + lane];
    }

    // ------------------------------------------------------------------ Psych Lua bridge

    public int psychLuaScreenWidth() { return PsychLuaRuntime.VIRTUAL_WIDTH; }
    public int psychLuaScreenHeight() { return PsychLuaRuntime.VIRTUAL_HEIGHT; }

    /** Base player FOV (Camera Zoom multiplies this). Clamped to Minecraft's 30-110. */
    public void psychLuaSetFov(double fov) { FieldOfViewControl.apply((int) Math.round(fov)); }
    public double psychLuaGetFov() { return FieldOfViewControl.current(); }
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
    public boolean psychLuaGfSection() {
        int section = Math.max(0, Math.min(psychLuaSection(), secFocusGirlfriend.length - 1));
        return secFocusGirlfriend[section];
    }
    public double psychLuaGameCameraX() {
        return psychScene == null ? PsychLuaRuntime.VIRTUAL_WIDTH * 0.5 : psychScene.cameraX();
    }
    public double psychLuaGameCameraY() {
        return psychScene == null ? PsychLuaRuntime.VIRTUAL_HEIGHT * 0.5 : psychScene.cameraY();
    }
    public double psychLuaCharacterMidpointX(String role) {
        if (extraCharacters.exists(role)) return extraCharacters.x(role);
        return psychScene == null ? 0 : psychScene.midpointX(role);
    }
    public double psychLuaCharacterMidpointY(String role) {
        if (extraCharacters.exists(role)) return extraCharacters.y(role);
        return psychScene == null ? 0 : psychScene.midpointY(role);
    }
    public void psychLuaSetHealth(double value) { health = (float) Math.max(0, Math.min(2, value)); }
    public void psychLuaAddMisses(int value) { misses = Math.max(0, misses + value); }
    public void psychLuaSetMisses(int value) { misses = Math.max(0, value); }

    /** Psych's {@code hits}: judged notes that were not misses. */
    public int psychLuaHits() {
        return judgements[0] + judgements[1] + judgements[2] + judgements[3];
    }
    /** Psych's {@code totalPlayed}: every judged note, hits and misses alike. */
    public int psychLuaTotalPlayed() { return accuracyCount; }
    /** Psych's {@code totalNotesHit}: accumulated per-judgement accuracy weight. */
    public double psychLuaTotalNotesHit() { return accuracySum; }
    public double psychLuaRating() {
        return forcedRatingPercent != null ? forcedRatingPercent
                : PsychRating.percent(accuracySum, accuracyCount);
    }
    public String psychLuaRatingName() {
        return forcedRatingName != null ? forcedRatingName
                : PsychRating.name(psychLuaRating(), accuracyCount);
    }
    public String psychLuaRatingFC() {
        return forcedRatingFC != null ? forcedRatingFC
                : PsychRating.fullCombo(misses, judgements[0], judgements[1], judgements[2], judgements[3]);
    }
    public void psychLuaSetRatingPercent(double value) {
        forcedRatingPercent = Math.max(0, Math.min(1, value));
        scriptAlteredScore = true;
    }
    public void psychLuaSetRatingName(String value) {
        forcedRatingName = value;
        scriptAlteredScore = true;
    }
    public void psychLuaSetRatingFC(String value) {
        forcedRatingFC = value;
        scriptAlteredScore = true;
    }

    /**
     * Psych's addHits/setHits. Blockified derives hits from the judgement tally,
     * so a script adjusting it moves the "sick" bucket, keeping hits, totalPlayed,
     * and the rating percent consistent with each other.
     */
    public void psychLuaSetHits(int value) {
        int current = psychLuaHits();
        psychLuaAddHits(value - current);
    }

    public void psychLuaAddHits(int value) {
        if (value == 0) return;
        judgements[0] = Math.max(0, judgements[0] + value);
        accuracyCount = Math.max(0, accuracyCount + value);
        accuracySum = Math.max(0, accuracySum + value);
        scriptAlteredScore = true;
    }

    public void psychLuaAddScore(int value) { score += value; scriptAlteredScore = true; }
    public void psychLuaSetScore(int value) { score = value; scriptAlteredScore = true; }
    public int psychLuaDeaths() { return deaths; }
    public boolean psychLuaInGameOver() { return phase == Phase.GAMEOVER; }
    /** Psych counts the countdown as started once gameplay is no longer waiting to begin. */
    public boolean psychLuaStartedCountdown() {
        return phase != Phase.COUNTDOWN || System.currentTimeMillis() >= startAtEpochMs;
    }
    public double psychLuaPlaybackRate() { return songPlayer.playbackRate(); }
    public boolean psychLuaAltAnim() {
        int section = Math.max(0, Math.min(psychLuaSection(), secAltAnim.length - 1));
        return secAltAnim.length > 0 && secAltAnim[section];
    }
    /** Stage-defined character positions, before any event or Lua movement. */
    public double psychLuaDefaultCharacterX(String role) {
        return psychScene == null ? 0 : psychScene.defaultX(role);
    }
    public double psychLuaDefaultCharacterY(String role) {
        return psychScene == null ? 0 : psychScene.defaultY(role);
    }

    public double psychLuaStrumX(boolean playerSide, int lane) {
        int safeLane = Math.max(0, Math.min(3, lane));
        int index = (playerSide ? 4 : 0) + safeLane;
        if (!Double.isNaN(luaStrumX[index])) return luaStrumX[index];
        boolean mine = playBoth || playerSide == myChartSideIsPlayer;
        return baseLaneX(mine, safeLane) - noteSize() * 0.5f;
    }

    public double psychLuaStrumY(boolean playerSide, int lane) {
        int index = (playerSide ? 4 : 0) + Math.max(0, Math.min(3, lane));
        return Double.isNaN(luaStrumY[index]) ? receptorY() - noteSize() * 0.5f : luaStrumY[index];
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
            case "direction" -> luaStrumDirection[i];
            case "downScroll" -> luaStrumDownScroll[i];
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
        if (i < 0 || i >= 8) return;
        String strumProperty = property == null ? "" : property;
        if (strumProperty.equals("downScroll")) {
            luaStrumDownScroll[i] = noteBool(value, luaStrumDownScroll[i]);
            return;
        }
        if (strumProperty.equals("visible")) {
            luaStrumAlpha[i] = noteBool(value, luaStrumAlpha[i] > 0) ? Math.max(0.0001, luaStrumAlpha[i]) : 0;
            return;
        }
        if (!(value instanceof Number number)) return;
        switch (strumProperty) {
            case "x" -> luaStrumX[i] = number.doubleValue();
            case "y" -> luaStrumY[i] = number.doubleValue();
            case "alpha" -> luaStrumAlpha[i] = Math.max(0, Math.min(1, number.doubleValue()));
            case "angle" -> luaStrumAngle[i] = number.doubleValue();
            case "direction" -> luaStrumDirection[i] = number.doubleValue();
            default -> { }
        }
    }

    private void animatePsychNote(SongChart.Note note, int lane, boolean miss, String fallbackRole) {
        if (psychScene == null) return;
        String role = note != null && note.gfNote ? "gf"
                : note == null ? fallbackRole
                : note.playerSide ? "boyfriend" : "dad";
        if (!miss && note != null && "Hey!".equalsIgnoreCase(note.noteType)) {
            psychScene.hey(role, 0.6);
        } else {
            psychScene.sing(role, lane, miss, note == null ? "" : note.animSuffix);
        }
    }

    private void animatePsychHold(SongChart.Note note, int lane, String fallbackRole) {
        if (psychScene == null) return;
        String role = note != null && note.gfNote ? "gf"
                : note == null ? fallbackRole
                : note.playerSide ? "boyfriend" : "dad";
        psychScene.hold(role, lane, note == null ? "" : note.animSuffix);
    }

    private void endPsychHold(SongChart.Note note) {
        if (psychScene == null || note == null) return;
        psychScene.endHold(note.gfNote ? "gf" : note.playerSide ? "boyfriend" : "dad");
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
        int extraDot = path.indexOf('.');
        if (extraDot > 0 && extraCharacters.exists(path.substring(0, extraDot))) {
            String tag = path.substring(0, extraDot);
            String property = path.substring(extraDot + 1);
            return switch (property) {
                case "x" -> extraCharacters.x(tag);
                case "y" -> extraCharacters.y(tag);
                case "z" -> extraCharacters.z(tag);
                case "angle", "rotation", "rotation.y", "rotationY", "angleY" ->
                        extraCharacters.rotation(tag);
                case "visible" -> extraCharacters.visible(tag);
                case "grav", "gravity" -> extraCharacters.gravity(tag);
                case "collision", "collisions", "solid" -> extraCharacters.collision(tag);
                case "shadow", "shadows" -> extraCharacters.shadow(tag);
                default -> null;
            };
        }
        if (extraDot > 0 && isGravityProperty(path.substring(extraDot + 1))) {
            Entity performer = performerEntityForTag(path.substring(0, extraDot));
            if (performer != null) return !performer.isNoGravity();
        }
        if (extraDot > 0 && isCollisionProperty(path.substring(extraDot + 1))) {
            Entity performer = performerEntityForTag(path.substring(0, extraDot));
            if (performer != null) return PerformerCollisions.enabled(performer.getId());
        }
        if (extraDot > 0 && isShadowProperty(path.substring(extraDot + 1))) {
            Entity performer = performerEntityForTag(path.substring(0, extraDot));
            if (performer != null) return PerformerShadows.enabled(performer.getId());
        }
        // World-camera pixel coordinates of a performer, relative to the Funkin'
        // Machine, in the same space Lua world sprites use (64 px = 1 block,
        // X = stage-right, Y = down, Z = stage-forward). Feed these straight to
        // makeLuaSprite(tag, image, worldX, worldY, worldZ) with a 'world' camera
        // to place an image where a performer stands.
        if (extraDot > 0 && isWorldPixelProperty(path.substring(extraDot + 1))) {
            Entity performer = performerEntityForTag(path.substring(0, extraDot));
            Double value = performerWorldPixel(performer, path.substring(extraDot + 1));
            if (value != null) return value;
        }
        if (extraDot > 0) {
            PerformerTween performer = luaPerformerTween(path.substring(0, extraDot));
            if (performer != null) {
                return switch (path.substring(extraDot + 1)) {
                    case "x" -> performer.x;
                    case "y" -> performer.y;
                    case "z" -> performer.z;
                    case "angle", "rotation", "rotation.y", "rotationY", "angleY" -> performer.rotation;
                    default -> null;
                };
            }
        }
        String hudStyle = effectiveHudStyle();
        if ("fnf".equals(hudStyle)) {
            if (path.equals("healthBar.leftBar.color")) return fnfOpponentBarColor();
            if (path.equals("healthBar.rightBar.color")) return fnfPlayerBarColor();
            Object hudValue = fnfHud.property(path,
                    Math.max(0, Math.min(100, health * 50.0)), fnfScoreText());
            if (hudValue != null) return hudValue;
        } else {
            Object hudValue = textHud.property(path, defaultHudBarX(), defaultHudBarY(),
                    defaultHudScoreX(), defaultHudScoreY(), TEXT_HUD_DEFAULT_COLOR,
                    generatedHudText(hudStyle));
            if (hudValue != null) return hudValue;
        }
        return switch (path) {
            case "health" -> (double) health;
            case "fov" -> psychLuaGetFov();
            case "songScore", "score" -> score;
            case "songMisses", "misses" -> misses;
            case "combo" -> combo;
            case "songPosition" -> songPos;
            case "playbackRate" -> (double) songPlayer.playbackRate();
            case "songSpeed" -> chart.speed;
            case "mustHitSection" -> psychLuaMustHit();
            case "gfSection" -> psychLuaGfSection();
            case "curSection" -> psychLuaSection();
            case "defaultCamZoom" -> psychScene == null ? 1.0 : (double) psychScene.defaultZoom();
            case "camFollow.x" -> psychScene == null ? psychLuaGameCameraX() : psychScene.targetCameraX();
            case "camFollow.y" -> psychScene == null ? psychLuaGameCameraY() : psychScene.targetCameraY();
            case "camFollowPos.x" -> psychLuaGameCameraX();
            case "camFollowPos.y" -> psychLuaGameCameraY();
            case "camGame.scroll.x" -> psychLuaGameCameraX() - PsychLuaRuntime.VIRTUAL_WIDTH * 0.5;
            case "camGame.scroll.y" -> psychLuaGameCameraY() - PsychLuaRuntime.VIRTUAL_HEIGHT * 0.5;
            case "boyfriendCameraOffset[0]" -> psychScene == null ? 0.0 : psychScene.stageCameraOffset("boyfriend", 0);
            case "boyfriendCameraOffset[1]" -> psychScene == null ? 0.0 : psychScene.stageCameraOffset("boyfriend", 1);
            case "opponentCameraOffset[0]" -> psychScene == null ? 0.0 : psychScene.stageCameraOffset("dad", 0);
            case "opponentCameraOffset[1]" -> psychScene == null ? 0.0 : psychScene.stageCameraOffset("dad", 1);
            case "girlfriendCameraOffset[0]" -> psychScene == null ? 0.0 : psychScene.stageCameraOffset("gf", 0);
            case "girlfriendCameraOffset[1]" -> psychScene == null ? 0.0 : psychScene.stageCameraOffset("gf", 1);
            case "camGame.zoom" -> (double) GameplayCamera.gameZoom();
            case "camHUD.zoom" -> (double) appliedHudZoom();
            case "camGame.bopEnabled", "gameCameraBopEnabled" -> GameplayCamera.bopEnabled("game");
            case "camHUD.bopEnabled", "hudCameraBopEnabled" -> GameplayCamera.bopEnabled("hud");
            case "unspawnNotes.length", "notes.length" -> chart.notes.size();
            default -> psychScene == null ? null : psychScene.property(path);
        };
    }

    public boolean psychLuaSetProperty(String path, Object value) {
        if (path == null) return false;
        int extraDot = path.indexOf('.');
        if (extraDot > 0 && extraCharacters.exists(path.substring(0, extraDot))) {
            String tag = path.substring(0, extraDot);
            String property = path.substring(extraDot + 1);
            return switch (property) {
                case "x" -> extraCharacters.setX(tag, noteNumber(value, extraCharacters.x(tag)));
                case "y" -> extraCharacters.setY(tag, noteNumber(value, extraCharacters.y(tag)));
                case "z" -> extraCharacters.setZ(tag, noteNumber(value, extraCharacters.z(tag)));
                case "angle", "rotation", "rotation.y", "rotationY", "angleY" ->
                        extraCharacters.setRotation(tag, noteNumber(value, extraCharacters.rotation(tag)));
                case "visible" -> extraCharacters.setVisible(tag, noteBool(value, true));
                case "grav", "gravity" -> extraCharacters.setGravity(tag, noteBool(value, true));
                case "collision", "collisions", "solid" ->
                        extraCharacters.setCollision(tag, noteBool(value, true));
                case "shadow", "shadows" -> extraCharacters.setShadow(tag, noteBool(value, true));
                default -> false;
            };
        }
        if (extraDot > 0 && isGravityProperty(path.substring(extraDot + 1))) {
            Entity performer = performerEntityForTag(path.substring(0, extraDot));
            if (performer != null) {
                setPerformerGravity(performer, noteBool(value, true));
                return true;
            }
        }
        if (extraDot > 0 && isCollisionProperty(path.substring(extraDot + 1))) {
            Entity performer = performerEntityForTag(path.substring(0, extraDot));
            if (performer != null) {
                PerformerCollisions.setEnabled(performer.getId(), noteBool(value, true));
                return true;
            }
        }
        if (extraDot > 0 && isShadowProperty(path.substring(extraDot + 1))) {
            Entity performer = performerEntityForTag(path.substring(0, extraDot));
            if (performer != null) {
                PerformerShadows.setEnabled(performer.getId(), noteBool(value, true));
                return true;
            }
        }
        if (extraDot > 0) {
            PerformerTween performer = luaPerformerTween(path.substring(0, extraDot));
            if (performer != null) {
                String property = path.substring(extraDot + 1);
                switch (property) {
                    case "x" -> performer.setOffsetX(noteNumber(value, performer.x));
                    case "y" -> performer.setOffsetY(noteNumber(value, performer.y));
                    case "z" -> performer.setOffsetZ(noteNumber(value, performer.z));
                    case "angle", "rotation", "rotation.y", "rotationY", "angleY" ->
                            performer.setOffsetRotation(noteNumber(value, performer.rotation));
                    default -> { return false; }
                }
                return true;
            }
        }
        // Lua may inspect the gameplay score and customize scoreTxt, but the
        // actual scored value is owned by note judgements.
        if (path.equals("songScore") || path.equals("score")) return true;
        double number = value instanceof Number n ? n.doubleValue() : 0;
        if ("fnf".equals(effectiveHudStyle())) {
            if (path.equals("healthBar.percent")) {
                psychLuaSetHealth(Math.max(0, Math.min(100, number)) / 50.0);
                return true;
            }
            if (fnfHud.setProperty(path, value)) return true;
        } else if (textHud.setProperty(path, value)) {
            return true;
        }
        switch (path) {
            case "health" -> psychLuaSetHealth(number);
            case "fov" -> psychLuaSetFov(number);
            case "songMisses", "misses" -> psychLuaSetMisses((int) number);
            case "combo" -> combo = Math.max(0, (int) number);
            case "playbackRate" -> songPlayer.setPlaybackRate((float) number);
            case "camGame.zoom" -> GameplayCamera.setGameZoom((float) number);
            case "camHUD.zoom" -> GameplayCamera.setHudZoom((float) number,
                    "fnf".equals(effectiveHudStyle()));
            case "camGame.bopEnabled", "gameCameraBopEnabled" ->
                    GameplayCamera.setBopEnabled("game", noteBool(value, true));
            case "camHUD.bopEnabled", "hudCameraBopEnabled" ->
                    GameplayCamera.setBopEnabled("hud", noteBool(value, true));
            case "camFollow.x" -> { if (psychScene != null) psychScene.setTargetCameraX(number); }
            case "camFollow.y" -> { if (psychScene != null) psychScene.setTargetCameraY(number); }
            case "camFollowPos.x" -> { if (psychScene != null) psychScene.setCameraX(number); }
            case "camFollowPos.y" -> { if (psychScene != null) psychScene.setCameraY(number); }
            case "camGame.scroll.x" -> { if (psychScene != null) psychScene.setCameraX(
                    number + PsychLuaRuntime.VIRTUAL_WIDTH * 0.5); }
            case "camGame.scroll.y" -> { if (psychScene != null) psychScene.setCameraY(
                    number + PsychLuaRuntime.VIRTUAL_HEIGHT * 0.5); }
            default -> {
                if (psychScene == null || !psychScene.setProperty(path, value)) return false;
            }
        }
        return true;
    }

    public void psychLuaTriggerEvent(String name, String value1, String value2) {
        psychLuaTriggerEvent(name, value1, value2, "", "", "", "", "");
    }

    /** Extended trigger so Lua can fire events that use Value 3-7. */
    public void psychLuaTriggerEvent(String name, String value1, String value2,
                                     String value3, String value4, String value5, String value6,
                                     String value7) {
        executeEvent(-1, new SongChart.Event(songPos, name, value1, value2,
                value3, value4, value5, value6, value7, false));
    }

    public boolean psychLuaRunMinecraftCommand(String command, String runner) {
        return eventDispatcher.runLuaCommand(command, runner);
    }

    public void psychLuaCameraTarget(String target) {
        if (target == null || target.isBlank()) return;
        String role = target.equalsIgnoreCase("gf") || target.equalsIgnoreCase("girlfriend")
                || target.equalsIgnoreCase("speakers") ? "gf"
                : target.equalsIgnoreCase("dad") || target.equalsIgnoreCase("opponent")
                ? "dad" : "boyfriend";
        cameraFocusOverride = role;
        GameplayCamera.focus(!role.equals("dad"), "smooth", 500);
        if (psychScene != null) psychScene.focus(role);
        if (luaRuntime != null) luaRuntime.onMoveCamera(role);
    }

    public boolean psychLuaPlayCharacterAnimation(String role, String animation, boolean force) {
        if (extraCharacters.exists(role)) return extraCharacters.play(role, animation);
        boolean psychPlayed = psychScene != null && psychScene.playAnimation(role, animation, force);
        return playMinecraftCharacterAnimation(role, animation) || psychPlayed;
    }

    public boolean psychLuaCharacterDance(String role) {
        if (extraCharacters.exists(role)) return extraCharacters.dance(role);
        return psychScene != null && psychScene.dance(role);
    }

    public double psychLuaCharacterX(String role) {
        if (extraCharacters.exists(role)) return extraCharacters.x(role);
        return psychScene == null ? 0 : psychScene.characterX(role);
    }

    public double psychLuaCharacterY(String role) {
        if (extraCharacters.exists(role)) return extraCharacters.y(role);
        return psychScene == null ? 0 : psychScene.characterY(role);
    }

    public boolean psychLuaSetCharacterX(String role, double value) {
        if (extraCharacters.exists(role)) return extraCharacters.setX(role, value);
        return psychScene != null && psychScene.setCharacterX(role, value);
    }

    public boolean psychLuaSetCharacterY(String role, double value) {
        if (extraCharacters.exists(role)) return extraCharacters.setY(role, value);
        return psychScene != null && psychScene.setCharacterY(role, value);
    }

    public boolean psychLuaExtraCharacterExists(String tag) {
        return extraCharacters.exists(tag);
    }

    public boolean psychLuaAddCharacter(String tag, String definition, double x, double y, double z,
                                        double rotation, String animation, String role) {
        return extraCharacters.create(tag, definition, x, y, z, (float) rotation, animation, role);
    }

    public boolean psychLuaRemoveCharacter(String tag) {
        return extraCharacters.remove(tag);
    }

    public boolean psychLuaSetCharacterPosition(String tag, double x, double y, double z) {
        return extraCharacters.setPosition(tag, x, y, z);
    }

    public boolean psychLuaSetCharacterZ(String tag, double value) {
        return extraCharacters.setZ(tag, value);
    }

    public double psychLuaCharacterZ(String tag) {
        return extraCharacters.z(tag);
    }

    public boolean psychLuaSetCharacterRotation(String tag, double value) {
        return extraCharacters.setRotation(tag, value);
    }

    public double psychLuaCharacterRotation(String tag) {
        return extraCharacters.rotation(tag);
    }

    public boolean psychLuaSetCharacterVisible(String tag, boolean visible) {
        return extraCharacters.setVisible(tag, visible);
    }

    public boolean psychLuaChangeExtraCharacter(String tag, String definition, String role) {
        return extraCharacters.changeDefinition(tag, definition, role);
    }

    public void psychLuaEndSong() { finishSong(false); }
    public void psychLuaRestartSong() { restart(); }
    public void psychLuaExitSong() { exit(); }

    public void reloadLuaFonts() {
        if (luaRuntime != null) luaRuntime.reloadFonts();
    }

    /** Called from the level render pass so Lua world sprites have real depth and lighting. */
    public void renderLuaWorld(PoseStack poseStack, Camera camera) {
        if (luaRuntime == null || minecraft.level == null) return;
        luaRuntime.renderWorld(poseStack, camera, machinePos, StageOrientation.facing());
    }

    private float noteY(double timeMs, boolean mine, int lane, GameNote note) {
        double speed = note == null ? 1.0 : Math.max(0.01, note.data.multSpeed);
        double dist = (timeMs - songPos) * pxPerMs() * speed;
        float receptor = laneY(mine, lane);
        return (float) (laneDown(mine, lane) ? receptor - dist : receptor + dist);
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        logic();
        // no background dimming — the world stays fully visible during a song

        float noteSize = noteSize();
        boolean fadeOpponent = ClientOptions.get().middlescroll && !playBoth;
        boolean fnfHud = "fnf".equals(effectiveHudStyle());

        if (playbackPolicy.usesPsychCamera()) {
            // FNF profile owns the game camera instead of exposing the Minecraft world.
            gui.fill(0, 0, width, height, 0xFF0B0B12);
            // Psych inserts addLuaSprite(tag, false) after stage backgrounds but before characters.
            if (psychScene != null) psychScene.renderBackground(gui);
            if (luaRuntime != null) {
                luaRuntime.renderGame(gui, Integer.MIN_VALUE, PsychGameplayScene.CHARACTER_ORDER);
            }
            if (psychScene != null) psychScene.renderCharactersAndForeground(gui);
            if (luaRuntime != null) {
                luaRuntime.renderGame(gui, PsychGameplayScene.CHARACTER_ORDER, Integer.MAX_VALUE);
            }
        } else if (luaRuntime != null) {
            luaRuntime.renderGame(gui);
        }
        // Game-camera flash/fade covers the stage but not the HUD, like Flixel.
        drawCameraOverlay(gui, CameraOverlay.Target.GAME);

        // Every non-world gameplay element shares Psych's 1280x720 space.
        // Physical size therefore follows resolution, never Minecraft GUI scale.
        PsychCanvas.push(gui, 1f);
        // Notes, receptors, ratings and Lua HUD always follow the beat bop (like
        // Psych's camHUD). The native health bar/score use their own zoom so the
        // bop leaves the non-FNF HUD elements alone in Legacy and Minecraft modes.
        float gameHudZoom = GameplayCamera.hudZoom(true);
        float barHudZoom = appliedHudZoom();
        pushHudCamera(gui, gameHudZoom);
        if (luaRuntime != null) {
            luaRuntime.renderHudInCanvas(gui, Integer.MIN_VALUE, HudLayerOrder.RECEPTORS);
        }

        // receptors (single centered strumline in BOTH mode)
        for (int lane = 0; lane < 4; lane++) {
            int myState = myStrumFlash[lane] > 0 ? 2 : (laneHeld[lane] ? 1 : 0);
            NoteStyle.setDrawAlpha((float) luaStrumAlpha[(myChartSideIsPlayer || playBoth ? 4 : 0) + lane]);
            if (!customNoteTextures.drawReceptor(gui, chartDefaultNoteTexture(), lane, myState,
                    laneX(true, lane), laneY(true, lane), noteSize)) {
                NoteStyle.drawReceptor(gui, lane, laneX(true, lane), laneY(true, lane), noteSize, myState);
            }
        }
        NoteStyle.setDrawAlpha(1f);
        if (!playBoth) {
            if (fadeOpponent) NoteStyle.setDrawAlpha(0.6f);
            for (int lane = 0; lane < 4; lane++) {
                int otherState = otherStrumFlash[lane] > 0 ? 2 : 0;
                int side = myChartSideIsPlayer ? 0 : 4;
                NoteStyle.setDrawAlpha((float) (luaStrumAlpha[side + lane] * (fadeOpponent ? 0.6 : 1)));
                if (!customNoteTextures.drawReceptor(gui, chartDefaultNoteTexture(), lane, otherState,
                        laneX(false, lane), laneY(false, lane), noteSize)) {
                    NoteStyle.drawReceptor(gui, lane, laneX(false, lane), laneY(false, lane), noteSize, otherState);
                }
            }
            if (fadeOpponent) NoteStyle.setDrawAlpha(1f);
        }
        if (luaRuntime != null) {
            luaRuntime.renderHudInCanvas(gui, HudLayerOrder.RECEPTORS, HudLayerOrder.NOTES);
        }

        // notes
        double visibleMs = (HUD_HEIGHT + 100) / pxPerMs();
        renderNotes(gui, myLanes, myLaneIndex, true, noteSize, visibleMs);
        if (!playBoth) {
            if (fadeOpponent) NoteStyle.setDrawAlpha(0.6f);
            renderNotes(gui, otherLanes, otherLaneIndex, false, noteSize, visibleMs);
            if (fadeOpponent) NoteStyle.setDrawAlpha(1f);
        }
        if (luaRuntime != null) {
            luaRuntime.renderHudInCanvas(gui, HudLayerOrder.NOTES, HudLayerOrder.HIT_EFFECTS);
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

        if (luaRuntime != null) {
            luaRuntime.renderHudInCanvas(gui, HudLayerOrder.HIT_EFFECTS, HudLayerOrder.HUD);
        }
        if (fnfHud) renderHud(gui, noteSize);
        gui.pose().popPose();

        // Time bar/title use screen space, not camHUD. Psych camera bops must
        // never resize or displace song progress.
        if (fnfHud) renderSongProgress(gui);
        if (fnfHud && luaRuntime != null) {
            // Objects ordered above native HUD remain above fixed time bar too,
            // while still receiving camHUD zoom themselves.
            pushHudCamera(gui, gameHudZoom);
            luaRuntime.renderHudInCanvas(gui, HudLayerOrder.HUD, Integer.MAX_VALUE);
            gui.pose().popPose();
        }
        PsychCanvas.pop(gui);

        if (!fnfHud) {
            // Native styles use Minecraft GUI coordinates. Their physical size
            // now follows both window resolution and the vanilla GUI-scale option.
            // The health bar / score stay out of the beat bop; only the ratings
            // and Lua HUD bop alongside the notes.
            pushHudCamera(gui, barHudZoom, width, height);
            renderHudBar(gui, noteSize);
            gui.pose().popPose();
            renderSongProgress(gui);

            pushHudCamera(gui, gameHudZoom, width, height);
            renderCommonHud(gui);
            gui.pose().popPose();

            // Keep setObjectOrder semantics: Lua objects above the HUD anchor
            // still draw above native HUD styles, but retain Psych coordinates.
            if (luaRuntime != null) {
                PsychCanvas.push(gui, 1f);
                pushHudCamera(gui, gameHudZoom);
                luaRuntime.renderHudInCanvas(gui, HudLayerOrder.HUD, Integer.MAX_VALUE);
                gui.pose().popPose();
                PsychCanvas.pop(gui);
            }
        }

        // HUD overlay sits above the note field but below menus and the top camera.
        drawCameraOverlay(gui, CameraOverlay.Target.HUD);

        switch (phase) {
            case COUNTDOWN -> renderCountdown(gui);
            case PAUSED -> renderPause(gui);
            case GAMEOVER -> renderGameOver(gui);
            case RESULTS -> renderResults(gui);
            default -> {}
        }
        if (luaRuntime != null) luaRuntime.renderOther(gui);
        drawCameraOverlay(gui, CameraOverlay.Target.OTHER);
        // A Psych custom substate sits above every gameplay and camera layer.
        if (luaRuntime != null) luaRuntime.renderSubstate(gui);
        // Song-warning flag rides on top of everything, in plain screen space.
        WarningFlag.render(gui, height);

        // Editor playtest orientation aid: a Blender-style world axis gizmo.
        if (editorPlaytest && ClientOptions.get().editorShowAxisGizmo) renderAxisGizmo(gui);
        if (freeCam) renderFreeCamOverlay(gui);
        // Psych debugPrint trace lines ride on top of everything, top-left.
        if (luaRuntime != null) luaRuntime.renderDebugOverlay(gui);
    }

    /** Free-camera HUD: the current shot values plus the controls, top-left. */
    private void renderFreeCamOverlay(GuiGraphics gui) {
        boolean showReadout = ClientOptions.get().editorShowCameraReadout;
        int x = 8;
        int y = 8;
        drawOutlined(gui, "FREE CAMERA — Ctrl+Shift+Space to exit", x, y, 0xFFFFEE66);
        y += 12;
        String moveState = freeCamMove ? "ON (right-click to release cursor)"
                : "OFF (right-click to move/look)";
        drawOutlined(gui, "Move/Look: " + moveState, x, y, 0xFFBFC7D5);
        y += 11;
        drawOutlined(gui, String.format(java.util.Locale.ROOT,
                "Speed: %.1f (scroll)   WASD move · E up · Q down", freeCamSpeed), x, y, 0xFFBFC7D5);
        y += 11;
        drawOutlined(gui, "Arrows: ←→ roll · ↑↓ zoom · R resets roll+zoom",
                x, y, 0xFFBFC7D5);
        y += 11;
        drawOutlined(gui, "Shift = slower · Ctrl = faster value changes", x, y, 0xFFBFC7D5);
        y += 11;
        drawOutlined(gui, "M = movement (override/attached) · F = frame (machine/camera)",
                x, y, 0xFFBFC7D5);
        y += 14;

        if (showReadout) {
            String movement = freeCamOverride ? "override" : "attached to focus";
            String frame = freeCamCameraFrame ? "camera" : "machine";
            double[] follow = freeCamFollowValues();
            drawOutlined(gui, "Camera Follow Pos (" + movement + ", " + frame + " frame):",
                    x, y, 0xFF7ABF4A);
            y += 11;
            drawOutlined(gui, String.format(java.util.Locale.ROOT,
                    "  X %.2f   Y %.2f   Z %.2f  (blocks)", follow[0], follow[1], follow[2]),
                    x, y, 0xFFE0E0E0);
            y += 12;
            drawOutlined(gui, "Camera Rotation 3D:", x, y, 0xFF4A8FE0);
            y += 11;
            drawOutlined(gui, String.format(java.util.Locale.ROOT,
                    "  Pitch %.2f   Yaw %.2f   Roll %.2f  (degrees)",
                    GameplayCamera.freePitch(), GameplayCamera.freeYaw(), GameplayCamera.freeRoll()),
                    x, y, 0xFFE0E0E0);
            y += 12;
            drawOutlined(gui, "Camera Zoom:", x, y, 0xFFE0A24A);
            y += 11;
            drawOutlined(gui, String.format(java.util.Locale.ROOT,
                    "  Amount %.2f  (-1 to 0.9)", GameplayCamera.freeZoom()), x, y, 0xFFE0E0E0);
            y += 12;
            String copyHint = freeCamMove ? "Release cursor (right-click), then Ctrl+C to copy"
                    : "Ctrl+C copies all three events — paste in the chart editor";
            drawOutlined(gui, copyHint, x, y, 0xFFFFEE66);
            y += 14;
        }
        if (System.currentTimeMillis() < freeCamMessageUntil) {
            drawOutlined(gui, freeCamMessage, x, y, 0xFF66FF88);
        }
    }

    /** Draws text with a 1px black outline for readability over the world. */
    private void drawOutlined(GuiGraphics gui, String text, int x, int y, int color) {
        int black = 0xFF000000;
        gui.drawString(font, text, x - 1, y, black, false);
        gui.drawString(font, text, x + 1, y, black, false);
        gui.drawString(font, text, x, y - 1, black, false);
        gui.drawString(font, text, x, y + 1, black, false);
        gui.drawString(font, text, x, y, color, false);
    }

    /**
     * Blender-style axis gizmo in the bottom-right corner, oriented to the live
     * game camera. The axes are <b>machine-relative</b>, matching the space Lua
     * world sprites and stage events use: X = stage-right, Y = up, Z = stage-
     * forward (the machine's facing). Positive axes are solid with a letter;
     * negative axes are hollow rings. Drawn back-to-front so the nearest sits on
     * top.
     */
    private void renderAxisGizmo(GuiGraphics gui) {
        Camera camera = minecraft.gameRenderer.getMainCamera();
        Vector3f left = camera.getLeftVector();
        Vector3f up = camera.getUpVector();
        Vector3f look = camera.getLookVector();

        // Machine facing defines the stage basis: Z along facing, X to its right.
        Direction facing = StageOrientation.facing();
        Direction right = facing.getCounterClockWise();
        float[] xDir = {right.getStepX(), 0, right.getStepZ()};
        float[] yDir = {0, 1, 0};
        float[] zDir = {facing.getStepX(), 0, facing.getStepZ()};

        int radius = 26;
        int centerX = width - radius - 16;
        int centerY = height - radius - 16;

        // Each stage axis becomes a 2D screen vector via the camera basis:
        // screenX grows right (= -left), screenY grows down (= -up), and depth
        // along the look direction decides draw order.
        record Axis2D(float sx, float sy, float depth, int color, String label) {}
        float[][] axes = {
                {xDir[0], xDir[1], xDir[2]}, {-xDir[0], -xDir[1], -xDir[2]},
                {yDir[0], yDir[1], yDir[2]}, {-yDir[0], -yDir[1], -yDir[2]},
                {zDir[0], zDir[1], zDir[2]}, {-zDir[0], -zDir[1], -zDir[2]},
        };
        int[] colors = {0xFFF0506A, 0xFFF0506A, 0xFF7ABF4A, 0xFF7ABF4A, 0xFF4A8FE0, 0xFF4A8FE0};
        String[] labels = {"X", "", "Y", "", "Z", ""};

        java.util.List<Axis2D> projected = new java.util.ArrayList<>(6);
        for (int i = 0; i < axes.length; i++) {
            float dx = axes[i][0], dy = axes[i][1], dz = axes[i][2];
            float sx = -(dx * left.x() + dy * left.y() + dz * left.z());
            float sy = -(dx * up.x() + dy * up.y() + dz * up.z());
            float depth = dx * look.x() + dy * look.y() + dz * look.z();
            projected.add(new Axis2D(sx, sy, depth, colors[i], labels[i]));
        }
        // Larger depth = pointing away from the camera, so draw those first.
        projected.sort((a, b) -> Float.compare(b.depth(), a.depth()));

        for (Axis2D axis : projected) {
            float ex = axis.sx() * radius;
            float ey = axis.sy() * radius;
            boolean positive = !axis.label().isEmpty();
            drawGizmoAxis(gui, centerX, centerY, ex, ey, axis.color(), positive, axis.label());
        }
    }

    private void drawGizmoAxis(GuiGraphics gui, int centerX, int centerY,
                               float ex, float ey, int color, boolean positive, String label) {
        double length = Math.sqrt(ex * ex + ey * ey);
        if (positive && length > 0.5) {
            float angle = (float) Math.atan2(ey, ex);
            gui.pose().pushPose();
            gui.pose().translate(centerX, centerY, 0);
            gui.pose().mulPose(Axis.ZP.rotation(angle));
            gui.fill(0, -1, (int) Math.round(length), 1, color);
            gui.pose().popPose();
        }

        int ballX = centerX + Math.round(ex);
        int ballY = centerY + Math.round(ey);
        int r = 5;
        if (positive) {
            gui.fill(ballX - r, ballY - r, ballX + r, ballY + r, color);
            int textColor = 0xFF10131A;
            gui.drawString(font, label, ballX - font.width(label) / 2 + 1,
                    ballY - font.lineHeight / 2 + 1, textColor, false);
        } else {
            // Hollow ring for negative axes: filled square with a darker cutout.
            gui.fill(ballX - r, ballY - r, ballX + r, ballY + r, color);
            gui.fill(ballX - r + 2, ballY - r + 2, ballX + r - 2, ballY + r - 2, 0xFF10131A);
        }
    }

    /** Fills the screen with a camera's active flash or fade colour, if any. */
    private void drawCameraOverlay(GuiGraphics gui, CameraOverlay.Target target) {
        int color = CameraOverlay.colorFor(target);
        if (color != 0) gui.fill(0, 0, width, height, color);
    }

    private void pushHudCamera(GuiGraphics gui, float zoom) {
        pushHudCamera(gui, zoom, HUD_WIDTH, HUD_HEIGHT);
    }

    private void pushHudCamera(GuiGraphics gui, float zoom, int canvasWidth, int canvasHeight) {
        gui.pose().pushPose();
        gui.pose().translate(GameplayCamera.hudShakeX(canvasWidth),
                GameplayCamera.hudShakeY(canvasHeight), 0);
        if (Math.abs(zoom - 1f) <= 0.0001f) return;
        gui.pose().translate(canvasWidth * 0.5f, canvasHeight * 0.5f, 0);
        gui.pose().scale(zoom, zoom, 1);
        gui.pose().translate(-canvasWidth * 0.5f, -canvasHeight * 0.5f, 0);
    }

    private float appliedHudZoom() {
        return GameplayCamera.hudZoom("fnf".equals(effectiveHudStyle()));
    }

    private void renderNotes(GuiGraphics gui, List<GameNote>[] lanes, int[] laneStart,
                             boolean mine, float noteSize, double visibleMs) {
        for (int lane = 0; lane < 4; lane++) {
            boolean down = laneDown(mine, lane);
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
                    if (y > -noteSize && y < HUD_HEIGHT + noteSize) {
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

    private boolean down() { return ClientOptions.get().downscroll; }

    /** FNF stays 1280x720; other styles use Minecraft's GUI-scaled viewport. */
    private int hudLayoutWidth() {
        return "fnf".equals(effectiveHudStyle()) ? HUD_WIDTH : width;
    }

    private int hudLayoutHeight() {
        return "fnf".equals(effectiveHudStyle()) ? HUD_HEIGHT : height;
    }

    // HUD cluster positions. Upscroll = above the bottom hotbar (unchanged).
    // Downscroll = below a hotbar flush at the top of the screen (mirror order).
    /** XP-style bar row. */
    private int xpBarY() { return down() ? 34 : hudLayoutHeight() - 29; }
    /** The XP level number / score text always sits just above the bar (both scroll directions). */
    private int numberY(int barY) { return barY - 9; }

    private void renderHud(GuiGraphics gui, float noteSize) {
        renderHudBar(gui, noteSize);
        renderCommonHud(gui);
    }

    /** Style-specific health bar / score, without the shared rating popups. */
    private void renderHudBar(GuiGraphics gui, float noteSize) {
        String style = effectiveHudStyle();
        switch (style) {
            case "fnf" -> renderFnfHud(gui);
            case "vanilla" -> renderVanillaHud(gui);
            default -> renderTextHud(gui, style);
        }
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
        // Black outline that carries the text's alpha. Using a bare 0 makes the
        // font force it fully opaque, so a faded score used to sit on a solid
        // black outline and read as black. When no alpha byte is given (opaque
        // callers), this stays 0 and the font keeps the outline opaque as before.
        int outline = color & 0xFF000000;
        gui.drawString(font, line, tx + 1, y, outline, false);
        gui.drawString(font, line, tx - 1, y, outline, false);
        gui.drawString(font, line, tx, y + 1, outline, false);
        gui.drawString(font, line, tx, y - 1, outline, false);
        gui.drawString(font, line, tx, y, color, false);
    }

    private static final int TEXT_HUD_DEFAULT_COLOR = 0x80FF20;

    // The non-FNF HUD renders in raw screen pixels, but Lua sprites/text use the
    // shared 1280x720 canvas (PsychCanvas, letterboxed). So healthBar/scoreTxt Lua
    // coordinates are exposed in that same canvas space and converted to screen
    // only when drawing: a HUD element and a Lua object at the same coordinate
    // then coincide. The default round-trips to its original screen spot exactly.
    private double canvasScale() { return Math.min(width / 1280.0, height / 720.0); }
    private double canvasToScreenX(double cx) { return (width - 1280 * canvasScale()) * 0.5 + cx * canvasScale(); }
    private double canvasToScreenY(double cy) { return (height - 720 * canvasScale()) * 0.5 + cy * canvasScale(); }
    private double screenToCanvasX(double sx) { return (sx - (width - 1280 * canvasScale()) * 0.5) / canvasScale(); }
    private double screenToCanvasY(double sy) { return (sy - (height - 720 * canvasScale()) * 0.5) / canvasScale(); }

    private double defaultHudBarX() { return screenToCanvasX(hudLayoutWidth() / 2.0 - 91); }
    private double defaultHudBarY() { return screenToCanvasY(xpBarY()); }
    private double defaultHudScoreX() { return screenToCanvasX(hudLayoutWidth() / 2.0); }
    private double defaultHudScoreY() { return screenToCanvasY(numberY(xpBarY())); }

    /** The generated text for the current non-FNF style (score line, or miss count for vanilla). */
    private String generatedHudText(String style) {
        return "vanilla".equals(style) ? String.valueOf(misses) : scoreLine(style);
    }

    /** default / abbreviated / numbers: XP-style bar + centered green outlined text. */
    private void renderTextHud(GuiGraphics gui, String style) {
        renderXpHud(gui, style, Math.min(1f, health / 2f), generatedHudText(style));
    }

    /** Vanilla: Minecraft renders its real hearts/food layers; Blockified supplies the miss XP bar. */
    private void renderVanillaHud(GuiGraphics gui) {
        renderXpHud(gui, "vanilla", (misses % 10) / 10.0f, generatedHudText("vanilla"));
    }

    /**
     * Shared XP-style bar + outlined text for the non-FNF HUD styles, honouring
     * the Lua-controlled {@link #textHud} state (position, visibility, alpha, scale,
     * colour and text override).
     */
    private void renderXpHud(GuiGraphics gui, String style, float fillFraction, String generated) {
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();

        if (textHud.barVisible && textHud.barAlpha > 0) {
            double barX = canvasToScreenX(textHud.barX(defaultHudBarX()));
            double barY = canvasToScreenY(textHud.barY(defaultHudBarY()));
            gui.pose().pushPose();
            gui.pose().translate(barX, barY, 0);
            gui.pose().scale((float) textHud.barScale, (float) textHud.barScale, 1);
            gui.setColor(1f, 1f, 1f, (float) textHud.barAlpha);
            gui.blitSprite(XP_BAR_BACKGROUND, 0, 0, 182, 5);
            int fill = (int) (Math.max(0f, Math.min(1f, fillFraction)) * 183.0f);
            if (fill > 0) gui.blitSprite(XP_BAR_PROGRESS, 182, 5, 0, 0, 0, 0, fill, 5);
            gui.setColor(1f, 1f, 1f, 1f);
            gui.pose().popPose();
        }

        if (textHud.scoreVisible && textHud.scoreAlpha > 0) {
            double scoreX = canvasToScreenX(textHud.scoreX(defaultHudScoreX()));
            double scoreY = canvasToScreenY(textHud.scoreY(defaultHudScoreY()));
            int alpha = (int) Math.round(Math.max(0, Math.min(1, textHud.scoreAlpha)) * 255);
            int color = (alpha << 24) | (textHud.scoreColor(TEXT_HUD_DEFAULT_COLOR) & 0xFFFFFF);
            gui.pose().pushPose();
            gui.pose().translate(scoreX, scoreY, 0);
            gui.pose().scale((float) textHud.scoreScale, (float) textHud.scoreScale, 1);
            outlinedCentered(gui, textHud.scoreText(generated), 0, 0, color);
            gui.pose().popPose();
        }
    }

    /** fnf: Psych-style health bar with icons + score text (all vanilla GUI hidden). */
    private void renderFnfHud(GuiGraphics gui) {
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        int barW = Math.max(1, (int) Math.round(fnfHud.barWidth));
        int barX = (int) Math.round(fnfHud.barX);
        int barY = (int) Math.round(fnfHud.barY);
        int barH = Math.max(1, (int) Math.round(fnfHud.barHeight));

        float frac = Math.min(1f, Math.max(0f, health / 2f));
        int split = barX + (int) (barW * (1 - frac));

        String animationPlayerIcon = blockifiedAnimationIcon("boyfriend");
        String animationOpponentIcon = blockifiedAnimationIcon("dad");
        boolean songIconsAllowed = !restrictsMinecraftSongAssets();
        String currentPlayerIcon = songIconsAllowed
                ? eventPlayerIcon != null ? changedCharacterIcon(eventPlayerIcon, "player")
                : !animationPlayerIcon.isBlank() ? animationPlayerIcon
                : assetResolver.characterIcon(chart.player1)
                : animationPlayerIcon;
        String currentOpponentIcon = songIconsAllowed
                ? eventOpponentIcon != null ? changedCharacterIcon(eventOpponentIcon, "opponent")
                : !animationOpponentIcon.isBlank() ? animationOpponentIcon
                : runtimeSongEntry != null && runtimeSongEntry.opponentIcon != null
                && !runtimeSongEntry.opponentIcon.isBlank()
                ? runtimeSongEntry.opponentIcon : assetResolver.characterIcon(chart.player2)
                : animationOpponentIcon;
        String playerIcon = selectedIcon(ClientOptions.get().playerIcon, currentPlayerIcon);
        String botIcon = selectedIcon(ClientOptions.get().botIcon, currentOpponentIcon);
        int oppColor = fnfHud.opponentColor >= 0 ? fnfHud.opponentColor
                : colorOr(com.fnfmod.client.render.IconLibrary.barColor(botIcon), 0xCC2233);
        int plColor = fnfHud.playerColor >= 0 ? fnfHud.playerColor
                : colorOr(com.fnfmod.client.render.IconLibrary.barColor(playerIcon), 0x33CC33);

        if (fnfHud.backgroundVisible && fnfHud.backgroundAlpha > 0) {
            int bx = (int) Math.round(fnfHud.backgroundX);
            int by = (int) Math.round(fnfHud.backgroundY);
            gui.fill(bx, by, bx + Math.max(1, (int) Math.round(fnfHud.backgroundWidth)),
                    by + Math.max(1, (int) Math.round(fnfHud.backgroundHeight)),
                    argb(0x000000, fnfHud.backgroundAlpha));
        }
        if (fnfHud.barVisible && fnfHud.barAlpha > 0) {
            gui.fill(barX, barY, split, barY + barH, argb(oppColor, fnfHud.barAlpha));
            gui.fill(split, barY, barX + barW, barY + barH, argb(plColor, fnfHud.barAlpha));
        }

        float beatFrac = (float) (conductor.beatAt(Math.max(0, songPos)) % 1.0);
        if (beatFrac < 0) beatFrac += 1;
        float t = Math.min(1f, beatFrac * 2f);
        float outCirc = (float) Math.sqrt(1f - (t - 1f) * (t - 1f));
        float bop = (1f - outCirc) * 0.30f;
        float iconSize = 64f * FNF_NATIVE_UI_SCALE * (1f + bop);
        int iconY = barY + barH / 2;
        float gap = iconSize * 0.34f;
        gui.setColor(1, 1, 1, (float) fnfHud.barAlpha);
        if (fnfHud.barVisible && botIcon != null && !botIcon.isEmpty()) {
            int frame = frac > 0.8f ? 1 : 0;
            com.fnfmod.client.render.IconLibrary.draw(gui, botIcon, frame,
                    split - gap, iconY, iconSize, false);
        }
        if (fnfHud.barVisible && playerIcon != null && !playerIcon.isEmpty()) {
            int frame = frac < 0.2f ? 1 : 0;
            com.fnfmod.client.render.IconLibrary.draw(gui, playerIcon, frame,
                    split + gap, iconY, iconSize, true);
        }
        gui.setColor(1, 1, 1, 1);

        if (fnfHud.scoreVisible && fnfHud.scoreAlpha > 0) {
            String line = fnfHud.scoreTextOverride == null ? fnfScoreText() : fnfHud.scoreTextOverride;
            gui.pose().pushPose();
            gui.pose().translate(fnfHud.scoreX + fnfHud.scoreWidth * 0.5, fnfHud.scoreY, 0);
            gui.pose().scale((float) fnfHud.scoreScaleX * FNF_NATIVE_UI_SCALE,
                    (float) fnfHud.scoreScaleY * FNF_NATIVE_UI_SCALE, 1);
            if (fnfHud.scoreAngle != 0) {
                gui.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees((float) fnfHud.scoreAngle));
            }
            gui.drawCenteredString(font, line, 0, 0, argb(fnfHud.scoreColor, fnfHud.scoreAlpha));
            gui.pose().popPose();
        }
    }

    private String fnfScoreText() {
        return "Score: " + score + "   Misses: " + misses
                + "   Accuracy: " + String.format("%.2f", accuracy() * 100) + "%";
    }

    private int fnfOpponentBarColor() {
        return fnfHud.opponentColor >= 0 ? fnfHud.opponentColor : 0xFFCC2233;
    }

    private int fnfPlayerBarColor() {
        return fnfHud.playerColor >= 0 ? fnfHud.playerColor : 0xFF33CC33;
    }

    private static int argb(int color, double alpha) {
        int a = Math.max(0, Math.min(255, (int) Math.round(alpha * 255)));
        return color & 0x00FFFFFF | a << 24;
    }

    private static int colorOr(int rgb, int fallback) {
        return rgb >= 0 ? rgb : fallback;
    }

    private void renderCommonHud(GuiGraphics gui) {
        int layoutWidth = hudLayoutWidth();
        int layoutHeight = hudLayoutHeight();
        // partner info and judgements belong to camHUD.
        if (duet) {
            gui.drawString(font, partnerName, 8, 16, 0xFFFFAAEE);
            gui.drawString(font, "Score: " + partnerScore + "  Combo: " + partnerCombo, 8, 27, 0xFFCCCCCC);
        }

        // rating popups (position from the user's Rating Position setting, or default)
        var opts = ClientOptions.get();
        float baseX = opts.ratingX >= 0 ? (float) (opts.ratingX * layoutWidth)
                : (playBoth || opts.middlescroll ? layoutWidth * 0.75f
                : myStrumsCenterX() * layoutWidth / HUD_WIDTH);
        float baseY = opts.ratingY >= 0 ? (float) (opts.ratingY * layoutHeight)
                : layoutHeight * 0.4f;
        long now = System.currentTimeMillis();
        popups.removeIf(p -> now - p.bornMs > 700);
        float ratingScale = "fnf".equals(effectiveHudStyle()) ? FNF_NATIVE_UI_SCALE : 1f;
        for (Popup p : popups) {
            float age = (now - p.bornMs) / 700f;
            int alpha = (int) (255 * (1 - age));
            if (alpha <= 8) continue;
            int color = (alpha << 24) | (p.color & 0xFFFFFF);
            float y = baseY - age * 18 * ratingScale;
            gui.pose().pushPose();
            gui.pose().translate(baseX, y, 0);
            gui.pose().scale(ratingScale, ratingScale, 1);
            gui.drawCenteredString(font, p.text, 0, 0, color);
            gui.pose().popPose();
        }
    }

    private String blockifiedAnimationIcon(String role) {
        if (playbackPolicy != null && playbackPolicy.usesPsychCamera()) return "";
        String set = localControlsRole(role) ? myAnimSet
                : partnerControlsRole(role) ? partnerAnimSet : "";
        return set.isBlank() ? "" : CharacterAnimations.icon(set,
                role.equals("dad") ? "opponent" : "player");
    }

    private String changedCharacterIcon(String characterId, String role) {
        if (restrictsMinecraftSongAssets()) return "";
        if (playbackPolicy == null || !playbackPolicy.usesPsychCamera()) {
            String icon = CharacterAnimations.icon(CharacterAnimations.modSet(characterId), role);
            if (!icon.isBlank()) return icon;
        }
        return assetResolver.characterIcon(characterId);
    }

    private boolean restrictsMinecraftSongAssets() {
        return playbackPolicy != null && playbackPolicy.mode() == PlaybackMode.MINECRAFT
                && !playbackPolicy.songAssets();
    }

    private static String selectedIcon(String setting, String songIcon) {
        return ClientOptions.SONG_ICON.equals(setting) || setting == null ? songIcon : setting;
    }

    private void renderSongProgress(GuiGraphics gui) {
        // Top on upscroll, bottom on downscroll. Deliberately outside camHUD.
        boolean down = down();
        int layoutWidth = hudLayoutWidth();
        int layoutHeight = hudLayoutHeight();
        int progY = down ? layoutHeight - 2 : 0;
        int titleY = down ? layoutHeight - 12 : 6;
        if (songPlayer.isStarted() && songPlayer.durationMs() > 0) {
            float frac = (float) Math.min(1, Math.max(0, songPos / songPlayer.durationMs()));
            gui.fill(0, progY, (int) (layoutWidth * frac), progY + 2, 0xFFDD44AA);
        }
        gui.drawCenteredString(font, chart.title, layoutWidth / 2, titleY, 0x99FFFFFF);
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

        String[] options = pauseOptions();
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

        if (ClientOptions.get().botplay) {
            gui.drawString(font, "BOTPLAY", px + 6, py + 5, 0xFFFFCC33);
            gui.drawString(font, "not saved", px + panelW - 6 - font.width("not saved"),
                    py + 5, 0xFFFFCC33);
        }

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
        // Gameplay must not pause the world — command events, TNT and mobs keep
        // ticking through a song. Only Blockified's own pause menu freezes it, and
        // Minecraft already limits that to true singleplayer (an unpublished
        // integrated server), which is exactly where a pause should stop the game.
        // The free camera also freezes the world: without this the player entity
        // keeps running physics (and chunks keep churning) while it is unpinned,
        // which desynced its position and the camera when flying near chunk edges.
        return phase == Phase.PAUSED || freeCam;
    }

    @Override
    public void removed() {
        if (openingMinecraftPause) {
            openingMinecraftPause = false;
            super.removed();
            return;
        }
        disposeResources();
        super.removed();
    }

    void disposeAfterMinecraftPauseDisconnect() {
        disposeResources();
    }

    private void disposeResources() {
        disposeResources(true);
    }

    /**
     * Tears the run down. A restart keeps the audio player alive because the
     * replacement screen reuses that same instance; everything else is rebuilt
     * from scratch. Setting the disposed flag first makes the later
     * {@link #removed()} call a no-op, so nothing is torn down twice.
     */
    private void disposeResources(boolean disposeAudio) {
        if (resourcesDisposed) return;
        resourcesDisposed = true;
        restorePerformerGravity();
        PerformerCollisions.clear();
        PerformerShadows.clear();
        RenderDistanceControl.restore();
        FieldOfViewControl.restore();
        StageChunkLoader.unload();
        StageOrientation.clear();
        WarningFlag.clear();
        if (rawInput != null) { rawInput.stop(); rawInput = null; }
        if (luaRuntime != null) luaRuntime.close();
        extraCharacters.close();
        customNoteTextures.close();
        if (psychScene != null) psychScene.close();
        if (freeCamMove) setFreeCamMove(false);
        freeCam = false;
        GameplayCamera.endFreeCam();
        GameplayCamera.end();
        if (disposeAudio) songPlayer.dispose();
        restoreVanillaMusic();
    }

    /** Active presentation mode may override the user's normal HUD preference. */
    public String effectiveHudStyle() {
        return playbackPolicy != null && playbackPolicy.forcesFnfHud()
                ? "fnf" : ClientOptions.effectiveHudStyle();
    }
}
