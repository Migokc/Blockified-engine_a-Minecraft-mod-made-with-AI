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
import com.fnfmod.client.gameplay.FreeCamObjects;
import com.fnfmod.client.gameplay.NativeFilePicker;
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
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
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
import com.fnfmod.client.render.FreeCamCameraMarker;
import com.fnfmod.client.render.PerformerRotation;
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
public class GameplayScreen extends Screen implements PsychBuiltinEventHandler.Host, TextInputAwareScreen {

    private enum Phase { COUNTDOWN, PLAYING, PAUSED, GAMEOVER, RESULTS }

    /** One signature region in logical quarter-note beat space. */
    private record MeterSegment(double startBeat, SongChart.TimeSignature signature,
                                long firstPulse, long firstMeasure) {}

    /** Current written beat/bar position. beatInMeasure and measure are zero-based. */
    private record MeterPosition(SongChart.TimeSignature signature, int segment,
                                 long pulse, long measure, int beatInMeasure,
                                 double pulseProgress) {}

    /** Which side(s) the local player controls. BOTH is solo-only. */
    public enum PlayMode { PLAYER, OPPONENT, BOTH }

    // Psych Engine default judgement windows (ms)
    private static final double SICK = 45, GOOD = 90, BAD = 135, SHIT = 166;
    private static final double HOLD_RELEASE_GRACE_MS = 150;
    private static final String[] DIR_NAMES = {"left", "down", "up", "right"};
    private static final int HUD_WIDTH = PsychCanvas.WIDTH;
    private static final int HUD_HEIGHT = PsychCanvas.HEIGHT;
    /** Native Minecraft font/icons need this compensation inside Psych's 1280x720 canvas. */
    private static final float FNF_NATIVE_UI_SCALE = 2f;
    /** Psych Engine's 160px note graphic rendered at default 0.7 scale. */
    private static final float PSYCH_NOTE_WIDTH = 112f;
    /** Blockified default presentation is 90% of Psych's native note size. */
    private static final float DEFAULT_NOTE_SIZE = PSYCH_NOTE_WIDTH * 0.9f;
    private static final long CURSOR_IDLE_NANOS = 2_000_000_000L;

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
    // Solo opponent bot: a client-only RemotePlayer that gives the armor stand a real
    // BBS character when one resolves. Null (armor stand kept) otherwise.
    private com.fnfmod.client.gameplay.OpponentBotCharacter opponentBot;
    private String opponentBotSet;
    private String opponentBotRole = "opponent";
    private String playerIdleSuffix = "";
    private String opponentIdleSuffix = "";
    private record ObjectBorder(double size, int color) {}
    private ObjectBorder playerObjectBorder = new ObjectBorder(0, 0);
    private ObjectBorder opponentObjectBorder = new ObjectBorder(0, 0);
    private String eventPlayerIcon;
    private String eventOpponentIcon;
    private final boolean duet;
    private final byte songExitTarget;
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
    private int girlfriendDanceSpeed = 1;

    private static class GameNote {
        final SongChart.Note data;
        final int chartIndex;
        /** Set once the note enters the scroll window and onSpawnNote has fired. */
        boolean spawnAnnounced;
        /** Resolved custom hitsound path, computed once (null = default/none). */
        Path cachedHitsound;
        boolean hitsoundResolved;
        boolean hit, missed, holdDropped;
        boolean holdComplete;
        /** ms when the hold was released (>=0 = in the re-tap grace window), -1 = held. */
        double releasedMs = -1;
        /** Next song-time sustain callback; NaN for taps/inactive holds. */
        double nextSustainCallbackMs = Double.NaN;

        GameNote(SongChart.Note data, int chartIndex) {
            this.data = data;
            this.chartIndex = chartIndex;
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
    /** PlayState cursor inactivity timer; Windows fades through alpha cursors. */
    private final GameplayCursorFade gameplayCursorFade = new GameplayCursorFade();
    private long gameplayCursorLastActivityNanos;
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
    /** Custom sustain-arrow playback: one XML/spritesheet frame per two rendered frames. */
    private final GameNote[] mySustainArrowNote = new GameNote[4];
    private final GameNote[] otherSustainArrowNote = new GameNote[4];
    private final long[] mySustainArrowRenderFrames = new long[4];
    private final long[] otherSustainArrowRenderFrames = new long[4];
    private double voicesMutedUntil = -1;

    // partner
    private int partnerScore, partnerCombo;
    private boolean partnerEnded, partnerFailed;
    private int partnerEndScore, partnerEndMisses;
    private float partnerEndAccuracy;

    // fx
    private record Popup(String text, int color, long bornMs) {}
    private final List<Popup> popups = new ArrayList<>();
    // Lua-controlled HUD visibility. Scripts hide Blockified's built-in rating
    // popups and time bar to draw their own; setHudStyle overrides the user's
    // saved HUD preference for this song only.
    private boolean showRatingPopups = true;
    private boolean showTimeBar = true;
    private boolean showTimeText = true;
    private String hudStyleOverride;
    private record Splash(int lane, int variant, float x, float y, long bornMs,
                          String texture, float alpha) {}
    private final List<Splash> splashes = new ArrayList<>();
    private record CoverEnd(int lane, float x, float y, long bornMs) {}
    private final List<CoverEnd> coverEnds = new ArrayList<>();
    private static final double SPLASH_FPS = 24.0;
    private final List<MeterSegment> meterSegments;
    private int lastMeterSegment = -1;
    private long lastMeterPulse = Long.MIN_VALUE;
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
    /** Normal gameplay waits for the authoritative server reset before rebuilding. */
    private boolean restartPending;
    private boolean resourcesDisposed;
    /** Prevents one held Enter press from pausing and then confirming Resume via key repeat. */
    private boolean enterReady = true;
    private boolean editorPlaytest;
    private boolean editorPreview;
    private double editorStartMs;
    // Free-camera playtest state (Ctrl+Shift+Space). Holding LMB in empty viewport
    // space captures the mouse for spectator move/look; wheel adjusts freeCamSpeed.
    private boolean freeCam;
    private boolean freeCamMove;
    private double freeCamSpeed = 6.0;
    private double freeCamLastCursorX, freeCamLastCursorY;
    private boolean freeCamViewportDragging;
    private boolean freeCamViewportIgnoreWarpMotion;
    private Vec3 freeCamViewportPivot;
    private long freeCamPausedAtMs;
    private long freeCamLastMoveNano;
    private String freeCamMessage = "";
    private long freeCamMessageUntil;
    // Camera Follow Pos options for the copied shot. Override = fixed at the
    // anchor; otherwise attached to the focused character. Machine vs camera frame.
    private boolean freeCamOverride = true;
    private boolean freeCamCameraFrame;
    /** Export a static camera pose or pivot-orbit mode beginning at that pose. */
    private boolean freeCamOrbitShot;
    private boolean freeCamOrbitPinned;
    /** False = object authoring tab; true = camera authoring tab. */
    private boolean freeCamCameraTab;
    // On-screen free-cam control panel. Clickable when the cursor is released
    // (move/look off); the M/F/R/Ctrl+C keys still work alongside the buttons.
    private final java.util.List<FreeCamButton> freeCamButtons = new java.util.ArrayList<>();
    private boolean freeCamHelp;
    private boolean freeCamHideGui;
    private boolean freeCamMenuHidden;
    private boolean freeCamHudOverrideActive;
    private boolean savedHideGui;
    // In-game text entry (rename / fps) and colour picker, drawn as free-cam modals.
    private boolean freeCamTextEntry;
    private String freeCamTextTarget = "";
    private net.minecraft.client.gui.components.EditBox freeCamTextBox;
    // Numpad-. focus-on-selection camera move (expoOut interpolation).
    private boolean freeCamFocusing;
    private double[] freeCamFocusFrom = new double[3];
    private double[] freeCamFocusTo = new double[3];
    private long freeCamFocusStart;
    private long freeCamFocusDur;
    private Vec3 freeCamFocusPoint;
    private boolean freeCamColorPicker;
    private String freeCamColorTarget = "graph";   // "graph" or "border"
    private float pickHue, pickSat, pickBri;
    private int pickDrag;   // 0 none, 1 square, 2 hue bar
    private int freeCamColorOriginal;
    private net.minecraft.client.gui.components.EditBox freeCamColorHexBox;
    private boolean updatingFreeCamColorHex;
    private int colorCancelX, colorCancelY, colorCancelW, colorCancelH;
    private int colorDoneX, colorDoneY, colorDoneW, colorDoneH;
    private boolean freeCamAddMenu;
    private boolean freeCamExistingMenu;
    private boolean freeCamModMenu;
    private boolean freeCamAnimMenu;
    private boolean freeCamShowReadout = true;
    private boolean freeCamShowEntryCamera = true;
    private Vec3 freeCamEntryCameraPos;
    private Vector3f freeCamEntryCameraLeft;
    private Vector3f freeCamEntryCameraUp;
    private Vector3f freeCamEntryCameraLook;
    private float freeCamEntryCameraYaw;
    private float freeCamEntryCameraPitch;
    private float freeCamEntryCameraRoll;
    private int freeCamPanelScroll;
    private int freeCamPanelMaxScroll;
    private final com.fnfmod.client.gameplay.FreeCamObjects freeCamObjects =
            new com.fnfmod.client.gameplay.FreeCamObjects();
    private record FreeCamButton(int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }
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
        this.conductor = new Conductor(chart);
        this.meterSegments = buildMeterSegments(chart, conductor);
        this.songPlayer = songPlayer;
        this.extraCharacters = new ExtraCharacterRoster(machinePos);
        // A definition that resolves to a Psych character JSON spawns a real 2D character.
        this.extraCharacters.setCharacterResolver(
                def -> assetResolver == null ? null : assetResolver.character(def));
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
        this.songExitTarget = FnfPayloads.LeaveC2S.normalizeReturnTarget(
                ClientSession.pendingSongExitTarget);
        this.startAtEpochMs = startAtEpochMs;
        applySongNoteTextures(chart);
        this.originalLuaNotes = chart.notes.stream().map(SongChart.Note::copy).toList();
        this.runtimeSongId = ClientSession.songId;
        this.runtimeSongEntry = ClientSession.songId == null ? null : SongLibrary.get(ClientSession.songId);
        this.runtimeSongFolder = ClientSession.resolvedFolder != null ? ClientSession.resolvedFolder
                : runtimeSongEntry == null ? null : runtimeSongEntry.folder;
        this.playbackPolicy = new PlaybackPolicy(ClientSession.playbackMode, ClientSession.songAssets,
                ClientSession.luaAllowed);
        CharacterAnimations.useSongFolder(playbackPolicy.songAssets() && runtimeSongEntry != null
                ? runtimeSongEntry.animationRoot() : null, chart.player1, chart.player2);
        // Default (song) resolves the definition named by this chart role. None
        // remains disabled and an explicitly selected global definition remains
        // selected, including inside complete mod packs.
        String resolvedAnimSet = myChartSideIsPlayer
                ? ClientOptions.get().animationSet
                : ClientOptions.get().opponentAnimationSet;
        String localCharacter = myChartSideIsPlayer ? chart.player1 : chart.player2;
        if (playbackPolicy.songAssets() && runtimeSongEntry != null
                && CharacterAnimations.DEFAULT_SET.equalsIgnoreCase(resolvedAnimSet)
                && localCharacter != null && !localCharacter.isBlank()) {
            resolvedAnimSet = CharacterAnimations.modSet(localCharacter);
        }
        this.myAnimSet = resolvedAnimSet;
        this.initialMyAnimSet = resolvedAnimSet;
        // The solo bot plays the other chart role, so it uses that role's setting.
        this.opponentBotRole = partnerRole();
        String botCharacter = myChartSideIsPlayer ? chart.player2 : chart.player1;
        String botSet = myChartSideIsPlayer
                ? ClientOptions.get().opponentAnimationSet
                : ClientOptions.get().animationSet;
        if (playbackPolicy.songAssets() && runtimeSongEntry != null
                && CharacterAnimations.DEFAULT_SET.equalsIgnoreCase(botSet)
                && botCharacter != null && !botCharacter.isBlank()) {
            botSet = CharacterAnimations.modSet(botCharacter);
        }
        this.opponentBotSet = botSet;
        this.assetResolver = new PsychAssetResolver(runtimeSongFolder, runtimeSongEntry, playbackPolicy, chart.stage);
        this.customNoteTextures = new PsychNoteTextureCache(
                assetResolver.customNoteRoots(),
                playbackPolicy.allows(runtimeSongEntry, SongLibrary.ExternalContent.IMAGES));
        applySongNoteSkin();
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
        for (int noteIndex = 0; noteIndex < chart.notes.size(); noteIndex++) {
            SongChart.Note n = chart.notes.get(noteIndex);
            GameNote gn = new GameNote(n, noteIndex);
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
            screen.playbackPolicy = new PlaybackPolicy(ClientSession.playbackMode, ClientSession.songAssets,
                    ClientSession.luaAllowed);
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
        screen.applySongNoteSkin();
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
        String otherSet = partnerId == null ? opponentBotSet : partnerAnimSet;
        float[] partnerBase = CharacterAnimations.baseCameraOffset(otherSet, partnerRole());
        GameplayCamera.begin(anchor, facing.toYRot(), mePos,
                myChartSideIsPlayer ? mePos : otherPos,
                myChartSideIsPlayer ? otherPos : mePos,
                myChartSideIsPlayer ? myBase : partnerBase,
                myChartSideIsPlayer ? partnerBase : myBase, playbackPolicy.mode());
    }

    // ------------------------------------------------------------------ meter / timing

    private static List<MeterSegment> buildMeterSegments(SongChart chart, Conductor conductor) {
        List<MeterSegment> segments = new ArrayList<>();
        SongChart.TimeSignature signature = chart.startingTimeSignature();
        segments.add(new MeterSegment(0, signature, 0, 0));

        // V-Slice metadata can place meter changes at exact song times. Prefer
        // those over its synthetic editor sections so gameplay keeps the source
        // chart's real bar boundaries.
        if (!chart.meterChanges.isEmpty()) {
            for (SongChart.MeterChange change : chart.meterChanges) {
                appendMeterSegment(segments, conductor.beatAt(change.timeMs()),
                        new SongChart.TimeSignature(change.numerator(), change.denominator()));
            }
            return List.copyOf(segments);
        }

        double beat = 0;

        for (SongChart.Section section : chart.sections) {
            if (section.changeTimeSignature) {
                SongChart.TimeSignature changed = new SongChart.TimeSignature(
                        section.timeSignatureNumerator, section.timeSignatureDenominator);
                appendMeterSegment(segments, beat, changed);
            }
            beat += Math.max(0.25, section.sectionBeats);
        }
        return List.copyOf(segments);
    }

    private static void appendMeterSegment(List<MeterSegment> segments, double rawBeat,
                                           SongChart.TimeSignature signature) {
        double beat = Math.max(0, Double.isFinite(rawBeat) ? rawBeat : 0);
        MeterSegment previous = segments.get(segments.size() - 1);
        if (beat + 1.0e-7 < previous.startBeat) return;
        if (Math.abs(beat - previous.startBeat) < 1.0e-7) {
            segments.set(segments.size() - 1, new MeterSegment(
                    beat, signature, previous.firstPulse, previous.firstMeasure));
            return;
        }
        if (signature.equals(previous.signature)) return;
        double duration = beat - previous.startBeat;
        long firstPulse = previous.firstPulse
                + pulsesBefore(duration, previous.signature.pulseBeats());
        long firstMeasure = previous.firstMeasure
                + pulsesBefore(duration, previous.signature.barBeats());
        segments.add(new MeterSegment(beat, signature, firstPulse, firstMeasure));
    }

    /** Number of pulse starts in [0,duration), including the one at zero. */
    private static long pulsesBefore(double duration, double pulseLength) {
        if (!Double.isFinite(duration) || duration <= 0 || pulseLength <= 0) return 0;
        return Math.max(0, (long) Math.ceil(duration / pulseLength - 1.0e-7));
    }

    private MeterPosition meterAtBeat(double rawBeat) {
        double beat = Math.max(0, Double.isFinite(rawBeat) ? rawBeat : 0);
        int low = 0;
        int high = meterSegments.size() - 1;
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (meterSegments.get(middle).startBeat <= beat + 1.0e-7) low = middle;
            else high = middle - 1;
        }

        MeterSegment segment = meterSegments.get(low);
        SongChart.TimeSignature signature = segment.signature;
        double pulseLength = signature.pulseBeats();
        double local = Math.max(0, beat - segment.startBeat);
        double exactPulse = local / pulseLength;
        long localPulse = Math.max(0, (long) Math.floor(exactPulse + 1.0e-7));
        long localMeasure = localPulse / signature.numerator();
        int beatInMeasure = (int) (localPulse % signature.numerator());
        double progress = exactPulse - Math.floor(exactPulse);
        if (progress < 1.0e-7 || progress > 1.0 - 1.0e-7) progress = 0;
        return new MeterPosition(signature, low,
                segment.firstPulse + localPulse,
                segment.firstMeasure + localMeasure,
                beatInMeasure, Math.max(0, Math.min(1, progress)));
    }

    private MeterPosition currentMeter() {
        return meterAtBeat(conductor.beatAt(Math.max(0, songPos)));
    }

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
        markGameplayCursorActive();
        muteVanillaMusic();
        // Screen.init runs synchronously when Minecraft opens PlayState, before
        // the next level/world render. Load onCreate assets here so a 2D world
        // character exists for that first visible frame. Loading it from logic()
        // was one frame too late because the level render precedes Screen.render;
        // a large atlas made that late frame look like a noticeable pop-in.
        initializeLuaRuntime();
    }

    private void markGameplayCursorActive() {
        gameplayCursorLastActivityNanos = System.nanoTime();
        if (minecraft == null || freeCamMove) return;
        gameplayCursorFade.restore(minecraft.getWindow().getWindow());
    }

    private void updateGameplayCursor() {
        if (minecraft == null || resourcesDisposed || freeCamMove) return;
        if (phase == Phase.PAUSED) {
            gameplayCursorFade.restore(minecraft.getWindow().getWindow());
            return;
        }
        long now = System.nanoTime();
        if (gameplayCursorLastActivityNanos == 0) gameplayCursorLastActivityNanos = now;
        long inactive = Math.max(0, now - gameplayCursorLastActivityNanos);
        gameplayCursorFade.setOpacity(minecraft.getWindow().getWindow(),
                inactive <= CURSOR_IDLE_NANOS ? 1 : 0);
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
            applyExistingFreeCamEdits();
            updateFreeCam();
            return;
        }

        initializeLuaRuntime();
        // Spawn/morph the solo opponent during the count-in, before the first
        // note scan. Creating it afterward could lose a song's time-zero sing.
        if (phase == Phase.COUNTDOWN || phase == Phase.PLAYING) updateOpponentBot();
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
        extraCharacters.update(15000.0 / Math.max(1, conductor.bpmAt(Math.max(0, songPos))));
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
                    hold.nextSustainCallbackMs = Double.NaN;
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
                    fireSustainNoteHits(hold);
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
                                    DIR_NAMES[lane] + hold.data.animSuffix, localSkinChoice());
                            lastHoldSingMs[lane] = nowMs;
                            lastSingMs = nowMs; // suppress idle bop during the hold
                        }
                    }
                } else {
                    // Released — 0.3s window to press again and keep the hold
                    if (hold.releasedMs < 0) hold.releasedMs = songPos;
                    // Sustain callbacks represent successfully held segments. Do not
                    // replay skipped segments if the key returns during grace.
                    hold.nextSustainCallbackMs = songPos + sustainCallbackInterval(songPos);
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

        // Bot plays the other side in solo (unless the player is playing both sides).
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
                            fireSustainNoteHits(n);
                            break;
                        }
                        n.nextSustainCallbackMs = Double.NaN;
                        endPsychHold(n.data);
                        otherLaneIndex[lane]++;
                        continue;
                    }
                    if (songPos >= n.data.timeMs) {
                        if (n.data.blockHit && songPos - n.data.timeMs <= SHIT) break;
                        if (!n.data.ignoreNote && !n.data.blockHit) {
                            int noteIndex = n.chartIndex;
                            boolean runDefault = luaNoteHitPre(noteIndex, n.data);
                            if (!runDefault) break;
                            n.hit = true;
                            otherStrumFlash[lane] = Math.max(120, n.data.sustainMs);
                            if (!n.data.noAnimation) animatePsychNote(n.data, lane, false,
                                    n.data.playerSide ? "boyfriend" : "dad");
                            luaNoteHit(noteIndex, n.data, n.data.sustainMs > 30);
                            startSustainCallbacks(n);
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
                String cameraTarget = secFocusGirlfriend[idx] ? "gf"
                        : secFocusPlayer[idx] ? "boyfriend" : "dad";
                if (!secFocusGirlfriend[idx] || !focusExtraCharacter(cameraTarget,
                        null, Math.min(700, secBeatMs[idx] * 2))) {
                    GameplayCamera.focusSection(secFocusPlayer[idx], Math.min(700, secBeatMs[idx] * 2));
                }
                if (psychScene != null) {
                    psychScene.focus(cameraTarget);
                }
                if (luaRuntime != null) luaRuntime.onMoveCamera(cameraTarget);
            }
        }

        // Built-in musical bops use the written meter pulse. Quarter-note BPM
        // timing, notes, curBeat and onBeatHit remain Psych-compatible.
        MeterPosition meter = currentMeter();
        if (phase == Phase.PLAYING && (meter.segment != lastMeterSegment
                || meter.pulse != lastMeterPulse)) {
            lastMeterSegment = meter.segment;
            lastMeterPulse = meter.pulse;
            int pulse = (int) Math.min(Integer.MAX_VALUE, meter.pulse);
            // Camera zoom accents the first written beat of every measure.
            if (meter.beatInMeasure == 0) {
                GameplayCamera.bumpZoom(playbackPolicy.usesPsychCamera() ? 0.015f : 0.03f);
            }
            if (psychScene != null) psychScene.beat(pulse);
            // loopIdle characters are driven by their BBS "main" state, which
            // setForm starts and each sing returns to on its own. Re-triggering
            // idle would expire that looping state, so leave it to BBS.
            double singHold = singHoldMs();
            long nowMs = System.currentTimeMillis();
            if (nowMs - lastSingMs > singHold && minecraft.player != null
                    && !CharacterAnimations.loopIdle(myAnimSet, myRole())) {
                playIdle(minecraft.player, myAnimSet, myRole(), pulse, localSkinChoice());
            }
            if (partnerId != null && minecraft.level != null && nowMs - partnerLastSingMs > singHold
                    && !CharacterAnimations.loopIdle(partnerAnimSet, partnerRole())) {
                Player partner = minecraft.level.getPlayerByUUID(partnerId);
                if (partner != null) {
                    playIdle(partner, partnerAnimSet, partnerRole(), pulse,
                            CharacterAnimations.SkinChoice.form());
                }
            }
            // Chart-added 2D and 3D performers use the same written pulse.
            extraCharacters.danceAll(pulse, gfReplacementTag(), girlfriendDanceSpeed);
            extraCharacters.beat(pulse, 1, gfReplacementTag(), girlfriendDanceSpeed);
            if (opponentBot != null) {
                opponentBot.idle(pulse, singHold);
            }
        }

        // song end
        if (phase == Phase.PLAYING && songPlayer.isFinished()) {
            if (!anyNotesLeft(myLanes, myLaneIndex)) finishSong(false);
        }
    }

    /** Loads Lua/onCreate exactly once, early enough for first-frame world objects. */
    private void initializeLuaRuntime() {
        if (luaRuntime != null || width <= 0 || height <= 0 || resourcesDisposed) return;
        luaRuntime = PsychLuaRuntime.load(this, chart, runtimeSongId, runtimeSongFolder,
                runtimeSongEntry, playbackPolicy);
        rebuildNoteLanesAfterLuaCreate();
        // Psych announces the whole event list once, right after scripts load,
        // so a script can pre-cache assets for events it will receive later.
        for (SongChart.Event event : chart.events) {
            luaRuntime.onEventPushed(event.name, event.value1, event.value2, event.timeMs);
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
        for (int noteIndex = 0; noteIndex < chart.notes.size(); noteIndex++) {
            SongChart.Note note = chart.notes.get(noteIndex);
            GameNote gameNote = new GameNote(note, noteIndex);
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
            if (!secFocusGirlfriend[section] || !focusExtraCharacter("gf", null,
                    Math.min(700, secBeatMs[section] * 2))) {
                GameplayCamera.focusSection(secFocusPlayer[section],
                        Math.min(700, secBeatMs[section] * 2));
            }
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
        else if (isGfAlias(target)) role = "gf";
        else {
            String extra = resolvedExtraCharacterTarget(target);
            if (extra == null) return;
            cameraFocusOverride = extra;
            String eventEase = event.value2 == null || event.value2.isBlank() ? "smooth" : event.value2;
            focusExtraCharacter(extra, eventEase, 500);
            return;
        }
        cameraFocusOverride = role;
        String eventEase = event.value2 == null || event.value2.isBlank() ? "smooth" : event.value2;
        if (role.equals("gf") && focusExtraCharacter(role, eventEase, 500)) return;
        GameplayCamera.focus(!role.equals("dad"), eventEase, 500);
        if (psychScene != null) psychScene.focus(role);
    }

    @Override
    public PlaybackMode playbackMode() {
        return playbackPolicy == null ? PlaybackMode.MINECRAFT : playbackPolicy.mode();
    }

    @Override
    public void eventHey(String target, double durationSeconds) {
        String extra = resolvedExtraCharacterTarget(target);
        if (extra != null && isGfAlias(target)) extraCharacters.hey(extra, durationSeconds);
        if (psychScene != null) psychScene.hey(target, durationSeconds);
        playMinecraftHey(target, durationSeconds);
    }

    @Override
    public void eventSetGirlfriendSpeed(int speed) {
        girlfriendDanceSpeed = Math.max(1, speed);
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
        String extra = resolvedExtraCharacterTarget(target);
        if (extra != null) {
            extraCharacters.play(extra, animation);
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
    public void eventCameraRotation(Double pitch, Double yaw, Double roll, String easing,
                                    Double durationSeconds) {
        GameplayCamera.rotateTo(pitch, yaw, roll, easing, durationSeconds);
    }

    @Override
    public void eventCameraOrbit(boolean enabled, boolean pinned,
                                 double pivotX, double pivotY, double pivotZ,
                                 double durationSeconds, String easing) {
        if (!enabled) GameplayCamera.stopOrbit();
        else GameplayCamera.orbit(pinned, pivotX, pivotY, pivotZ, durationSeconds, easing);
    }

    @Override
    public void eventAltIdle(String target, String suffix) {
        String extra = resolvedExtraCharacterTarget(target);
        if (extra != null) {
            extraCharacters.setIdleSuffix(extra, suffix);
            return;
        }
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
        String extra = resolvedExtraCharacterTarget(target);
        if (extra != null && extraCharacters.changeDefinition(extra, selected, null)) return;
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
        boolean soloBotTarget = partnerId == null
                && opponentBotRole.equals(botRoleFor(target));
        boolean rebuildOpponentBot = soloBotTarget
                && !CharacterAnimations.isDisabled(opponentBotSet);
        if (rebuildOpponentBot) opponentBotSet = animationDefinition;
        String activeSet = localControlsRole(target) ? myAnimSet
                : partnerControlsRole(target) ? partnerAnimSet
                : soloBotTarget ? opponentBotSet : animationDefinition;
        GameplayCamera.setBaseOffset(target.equals("boyfriend"),
                CharacterAnimations.baseCameraOffset(activeSet,
                        target.equals("dad") ? "opponent" : "player"));

        // Apply the new form now. The beat loop skips playIdle for a loopIdle
        // definition, so without this a change to one would never swap the form —
        // it worked for other characters only because their idle re-applied it.
        if (target.equals("boyfriend") || target.equals("dad")) {
            boolean boyfriendSide = target.equals("boyfriend");
            if (performerEntity(boyfriendSide) instanceof Player performer) {
                CharacterAnimations.SkinChoice useSkin = localControlsRole(target)
                        && performer == minecraft.player
                        ? localSkinChoice() : CharacterAnimations.SkinChoice.form();
                CharacterAnimations.prepare(performer, activeSet,
                        boyfriendSide ? "player" : "opponent", useSkin);
            }
            // Solo opponent bot follows Change Character too: rebuild with the new
            // character (or fall back to the armor stand if it has no BBS form).
            if (rebuildOpponentBot) {
                if (opponentBot != null) {
                    opponentBot.remove(minecraft.level == null ? null : minecraft.level.getEntity(botEntityId));
                    opponentBot = null; // updateOpponentBot() re-creates it next tick
                }
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
        String extra = explicitExtraCharacterTarget(tag);
        if (extra != null) extraCharacters.remove(extra);
    }

    @Override
    public void eventTweenCharacter(String tag, Double x, Double y, Double z, Double rotation,
                                    double seconds, String easing) {
        String extra = resolvedExtraCharacterTarget(tag);
        if (extra != null) {
            extraCharacters.tween(extra, x, y, z, rotation, seconds, easing);
            return;
        }
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

    private boolean supportsExtraGirlfriend() {
        return playbackPolicy != null && !playbackPolicy.usesPsychCamera();
    }

    private static boolean isGfAlias(String raw) {
        if (raw == null) return false;
        String value = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return value.equals("gf") || value.equals("gfgroup")
                || value.equals("girlfriend") || value.equals("girlfriendgroup")
                || value.equals("speakers");
    }

    /** Reserved GF aliases prefer tag "gf", then "girlfriend", outside FNF look. */
    private String gfReplacementTag() {
        if (!supportsExtraGirlfriend()) return null;
        if (extraCharacters.exists("gf")) return "gf";
        return extraCharacters.exists("girlfriend") ? "girlfriend" : null;
    }

    /** Returns an actual extra tag, while preserving native GF ownership in FNF look. */
    private String resolvedExtraCharacterTarget(String raw) {
        if (isGfAlias(raw)) return gfReplacementTag();
        return extraCharacters.exists(raw) ? raw.trim().toLowerCase(java.util.Locale.ROOT) : null;
    }

    /** Blockified-specific APIs can still address an exact extra named GF in FNF look. */
    private String explicitExtraCharacterTarget(String raw) {
        String replacement = supportsExtraGirlfriend() && isGfAlias(raw) ? gfReplacementTag() : null;
        if (replacement != null) return replacement;
        return extraCharacters.exists(raw) ? raw.trim().toLowerCase(java.util.Locale.ROOT) : null;
    }

    private boolean focusExtraCharacter(String raw, String easing, double durationMs) {
        String tag = resolvedExtraCharacterTarget(raw);
        if (tag == null || playbackPolicy == null || playbackPolicy.usesPsychCamera()) return false;
        GameplayCamera.focusAt(() -> extraCharacters.focusWorldPosition(tag), 0, 0,
                easing == null || easing.isBlank() ? "smooth" : easing, durationMs);
        return true;
    }

    private boolean localControlsRole(String role) {
        return (myChartSideIsPlayer && role.equals("boyfriend"))
                || (!myChartSideIsPlayer && role.equals("dad"));
    }

    /** Skin choice follows the human-controlled performer, independent of chart side. */
    private CharacterAnimations.SkinChoice localSkinChoice() {
        return CharacterAnimations.configuredSkin(false);
    }

    private boolean partnerControlsRole(String role) {
        return partnerId != null && ((myChartSideIsPlayer && role.equals("dad"))
                || (!myChartSideIsPlayer && role.equals("boyfriend")));
    }

    private boolean playMinecraftCharacterAnimation(String role, String animation) {
        String extra = resolvedExtraCharacterTarget(role);
        if (extra != null) return extraCharacters.play(extra, animation);
        role = minecraftCharacterRole(role);
        if (role.equals("gf")) return false;
        boolean played = false;
        if (localControlsRole(role) && minecraft.player != null) {
            float[] cameraOffset = playMinecraftAnimation(minecraft.player, myAnimSet, myRole(),
                    animation, localSkinChoice());
            if (cameraOffset != null) {
                lastSingMs = System.currentTimeMillis();
                GameplayCamera.sing(role.equals("boyfriend"), cameraOffset[0], cameraOffset[1]);
                played = true;
            }
        }
        if (partnerControlsRole(role) && minecraft.level != null) {
            Player partner = minecraft.level.getPlayerByUUID(partnerId);
            if (partner != null) {
                float[] cameraOffset = playMinecraftAnimation(partner, partnerAnimSet, partnerRole(),
                        animation, CharacterAnimations.SkinChoice.form());
                if (cameraOffset != null) {
                    partnerLastSingMs = System.currentTimeMillis();
                    GameplayCamera.sing(role.equals("boyfriend"), cameraOffset[0], cameraOffset[1]);
                    played = true;
                }
            }
        }
        if (partnerId == null && opponentBot != null
                && opponentBot.role().equals(botRoleFor(role))) {
            float[] cameraOffset = opponentBot.playWithCameraOffset(animation);
            if (cameraOffset == null) {
                String conventional = minecraftAnimationName(animation);
                if (!conventional.equalsIgnoreCase(animation.trim())) {
                    cameraOffset = opponentBot.playWithCameraOffset(conventional);
                }
            }
            if (cameraOffset != null) {
                GameplayCamera.sing(role.equals("boyfriend"), cameraOffset[0], cameraOffset[1]);
                played = true;
            }
        }
        return played;
    }

    private static float[] playMinecraftAnimation(Player player, String set, String role,
                                                  String requestedAnimation,
                                                  CharacterAnimations.SkinChoice skinChoice) {
        if (requestedAnimation == null || requestedAnimation.isBlank()) return null;
        float[] exact = CharacterAnimations.play(player, set, role, requestedAnimation, skinChoice);
        if (exact != null) return exact;
        String conventional = minecraftAnimationName(requestedAnimation);
        return conventional.equalsIgnoreCase(requestedAnimation.trim()) ? null
                : CharacterAnimations.play(player, set, role, conventional, skinChoice);
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
                && CharacterAnimations.play(minecraft.player, myAnimSet, myRole(), "hey",
                localSkinChoice()) != null) {
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

    /**
     * Frame for a custom skin's animated confirm arrow. A negative value means
     * there is no actively held sustain, so normal static/press rendering is used.
     */
    private long customSustainStrumFrame(boolean mine, int lane) {
        GameNote sustain = null;
        if (mine) {
            if (!laneHeld[lane]) return -1;
            for (GameNote note : activeHolds[lane]) {
                if (!note.holdComplete && !note.holdDropped && songPos < note.endMs()) {
                    sustain = note;
                    break;
                }
            }
        } else {
            List<GameNote> notes = otherLanes[lane];
            int from = Math.max(0, otherLaneIndex[lane] - 1);
            for (int i = from; i < notes.size(); i++) {
                GameNote note = notes.get(i);
                if (note.data.timeMs > songPos) break;
                if (note.hit && note.data.sustainMs > 30 && songPos < note.endMs()) {
                    sustain = note;
                    break;
                }
            }
        }
        GameNote[] activeNotes = mine ? mySustainArrowNote : otherSustainArrowNote;
        long[] renderFrames = mine ? mySustainArrowRenderFrames : otherSustainArrowRenderFrames;
        if (sustain == null) {
            activeNotes[lane] = null;
            renderFrames[lane] = 0;
            return -1;
        }
        if (activeNotes[lane] != sustain) {
            activeNotes[lane] = sustain;
            renderFrames[lane] = 0;
        }
        // Hold each atlas frame for exactly two screen renders, then advance.
        return renderFrames[lane]++ / 2;
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
            CharacterAnimations.prepare(minecraft.player, myAnimSet, myRole(), localSkinChoice());
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

    private void playIdle(Player p, String set, String role, int beat,
                          CharacterAnimations.SkinChoice skinChoice) {
        String suffix = "player".equals(role) ? playerIdleSuffix : opponentIdleSuffix;
        boolean hasSecondIdle = CharacterAnimations.hasAction(set, role, "idle2");
        // Minecraft animation sets have two canonical idle slots. Non-empty
        // Psych alt-idle suffix selects slot 2 as their Legacy/Minecraft variant.
        if (!hasSecondIdle && (beat & 1) == 1) return;
        String action = !suffix.isBlank() && hasSecondIdle ? "idle2"
                : (beat & 1) == 1 ? "idle2" : "idle";
        if (CharacterAnimations.play(p, set, role, action, skinChoice) == null
                && !"idle".equals(action)) {
            CharacterAnimations.play(p, set, role, "idle", skinChoice);
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
        /** Lua/free-camera BBS pitch and roll; yaw remains the existing rotation field. */
        double rotationX, rotationZ;
        /** Render-only BBS scale; it deliberately does not change the entity hitbox. */
        double scaleX = 1, scaleY = 1, scaleZ = 1;
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
        void setOffsetRotationX(double value) { rotationX = finite(value); }
        void setOffsetRotationZ(double value) { rotationZ = finite(value); }
        void setScaleX(double value) { scaleX = finiteOr(value, 1); }
        void setScaleY(double value) { scaleY = finiteOr(value, 1); }
        void setScaleZ(double value) { scaleZ = finiteOr(value, 1); }

        boolean rotates() { return tweenRotation || rotation != 0; }

        boolean engaged() {
            return active || x != 0 || y != 0 || z != 0 || rotation != 0;
        }

        void reset() {
            active = false;
            x = y = z = rotation = rotationX = rotationZ = 0;
            scaleX = scaleY = scaleZ = 1;
            tweenRotation = false;
            homeKnown = false;
            appliedKnown = false;
        }

        private static double finite(double value) {
            return Double.isFinite(value) ? value : 0;
        }

        private static double finiteOr(double value, double fallback) {
            return Double.isFinite(value) ? value : fallback;
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
        PerformerRotation.set(performerVisualPlayer(playerSide), tween.rotationX, tween.rotationZ,
                tween.scaleX, tween.scaleY, tween.scaleZ);

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
            PerformerRotation.clear(performerVisualPlayer(playerSide));
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
        PerformerRotation.clear(performerVisualPlayer(playerSide));
        tween.reset();
    }

    private static boolean isGravityProperty(String property) {
        return property.equals("grav") || property.equals("gravity");
    }

    private static boolean isShadowProperty(String property) {
        return property.equals("shadow") || property.equals("shadows");
    }

    /** Full-bright / flat (unlit) toggle for a performer. */
    private static boolean isFullbrightProperty(String property) {
        return switch (property) {
            case "fullbright", "fullBright", "unlit", "flat", "flatShading", "flatshading" -> true;
            default -> false;
        };
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
        String extra = resolvedExtraCharacterTarget(tag);
        if (extra != null) return extraCharacters.setCollision(extra, enabled);
        Entity performer = performerEntityForTag(tag);
        if (performer == null) return false;
        PerformerCollisions.setEnabled(performer.getId(), enabled);
        return true;
    }

    public boolean psychLuaCharacterCollision(String tag) {
        String extra = resolvedExtraCharacterTarget(tag);
        if (extra != null) return extraCharacters.collision(extra);
        Entity performer = performerEntityForTag(tag);
        return performer != null && PerformerCollisions.enabled(performer.getId());
    }

    /**
     * Releases a performer from stage-offset control and puts it back on its
     * resting spot. Use this after cancelling a tween when the performer should
     * stop being held in place, for example before teleporting it somewhere else.
     */
    public boolean psychLuaResetCharacterPosition(String tag) {
        String extra = resolvedExtraCharacterTarget(tag);
        if (extra != null) {
            return extraCharacters.setPosition(extra, 0, 0, 0);
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
        String extra = resolvedExtraCharacterTarget(tag);
        if (extra != null) return extraCharacters.setShadow(extra, enabled);
        Entity performer = performerEntityForTag(tag);
        if (performer == null) return false;
        PerformerShadows.setEnabled(performer.getId(), enabled);
        return true;
    }

    /** Lua setObjectBorder for main, GF-replacement, and Add Character performers. */
    public boolean psychLuaSetObjectBorder(String tag, double size, int color) {
        String extra = resolvedExtraCharacterTarget(tag);
        if (extra != null) return extraCharacters.setBorder(extra, size, color);
        if (psychScene != null && psychScene.setObjectBorder(tag, size, color)) return true;
        if (tag == null) return false;
        String value = tag.trim().toLowerCase(java.util.Locale.ROOT);
        boolean playerSide;
        if (java.util.Set.of("bf", "boyfriend", "player", "boyfriendgroup", "0").contains(value)) {
            playerSide = true;
            playerObjectBorder = new ObjectBorder(size, color & 0xFFFFFF);
        } else if (java.util.Set.of("dad", "opponent", "dadgroup", "opponentgroup", "1").contains(value)) {
            playerSide = false;
            opponentObjectBorder = new ObjectBorder(size, color & 0xFFFFFF);
        } else {
            return false;
        }
        applyPerformerObjectBorder(playerSide);
        return true;
    }

    private void applyPerformerObjectBorder(boolean playerSide) {
        ObjectBorder border = playerSide ? playerObjectBorder : opponentObjectBorder;
        Entity base = performerEntity(playerSide);
        Player visual = performerVisualPlayer(playerSide);
        if (visual != null && visual != base) {
            com.fnfmod.client.render.ObjectBorderRegistry.clear(base);
        }
        Entity target = visual != null ? visual : base;
        com.fnfmod.client.render.ObjectBorderRegistry.set(target, border.size(), border.color());
    }

    public boolean psychLuaCharacterShadow(String tag) {
        String extra = resolvedExtraCharacterTarget(tag);
        if (extra != null) return extraCharacters.shadow(extra);
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

    /** Player actually rendered for a chart side; solo bots visually replace their stand. */
    private Player performerVisualPlayer(boolean playerSide) {
        Entity entity = performerEntity(playerSide);
        if (entity instanceof Player player) return player;
        if (playerSide != myChartSideIsPlayer && opponentBot != null) return opponentBot.player();
        return null;
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
        int bestIndex = best.chartIndex;
        if (!luaNoteHitPre(bestIndex, best.data)) {
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
                int noteIndex = n.chartIndex;
                if (luaNoteHitPre(noteIndex, n.data)) {
                    creditHit(n, judgement);
                    // Animate the stacked note's own character too. Otherwise an
                    // overlapping note merged into this press (e.g. a dad note stacked
                    // with a bf note in Both mode) is scored but never plays its sing
                    // animation, leaving that character idle. Its role is read from the
                    // note, so each side animates independently.
                    if (!n.data.noAnimation) sing(n.data.lane, false, n.data);
                }
            }
        }

        songPlayer.setPlayerVoiceVolume(1f);
        songPlayer.setOpponentVoiceVolume(1f);
        voicesMutedUntil = -1;

        String[] names = {"SICK!!", "GOOD", "BAD", "SHIT"};
        String[] ratingIds = {"sick", "good", "bad", "shit"};
        int[] colors = {0xFF66FFFF, 0xFF66FF66, 0xFFFFAA33, 0xFFFF5555};
        if (!best.data.ratingDisabled) {
            addPopup(names[judgement] + (combo > 1 ? "  " + combo : ""), colors[judgement]);
            // Fires even when the built-in popup is hidden, so a script can draw its own.
            if (luaRuntime != null) luaRuntime.onRatingPopup(ratingIds[judgement], combo);
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
        if (n.data.sustainMs > 30) {
            activeHolds[n.data.lane].add(n);
            startSustainCallbacks(n);
        }
        luaNoteHit(n.chartIndex, n.data, n.data.sustainMs > 30);

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
                n.chartIndex, lane, n.data.noteType, n.data.sustainMs > 30);
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
        hold.nextSustainCallbackMs = Double.NaN;
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
                hold.chartIndex, lane, hold.data.noteType, true);
        recalculateRating(true);
        checkDeath();
    }

    /** Psych-style sustain segments occur once per musical step, independent of FPS. */
    private double sustainCallbackInterval(double atMs) {
        double bpm = conductor.bpmAt(Math.max(0, atMs));
        if (!Double.isFinite(bpm) || bpm <= 0) bpm = Math.max(1, chart.startBpm);
        return Mth.clamp(15000.0 / bpm, 10.0, 1000.0);
    }

    private void startSustainCallbacks(GameNote note) {
        if (note.data.sustainMs <= 30) {
            note.nextSustainCallbackMs = Double.NaN;
            return;
        }
        double from = Math.max(songPos, note.data.timeMs);
        note.nextSustainCallbackMs = from + sustainCallbackInterval(from);
    }

    /**
     * Psych callback names describe chart characters, not input ownership:
     * boyfriend/player notes use goodNoteHit and dad/opponent notes use
     * opponentNoteHit even when Play as Opponent swaps who controls them.
     */
    private boolean luaNoteHitPre(int index, SongChart.Note note) {
        if (luaRuntime == null) return true;
        return note.playerSide
                ? luaRuntime.onGoodNoteHitPre(index, note.lane, note.noteType, note.sustainMs > 30)
                : luaRuntime.onOpponentNoteHitPre(index, note.lane, note.noteType, note.sustainMs > 30);
    }

    private void luaNoteHit(int index, SongChart.Note note, boolean sustain) {
        if (luaRuntime == null) return;
        if (note.playerSide) luaRuntime.onGoodNoteHit(index, note.lane, note.noteType, sustain);
        else luaRuntime.onOpponentNoteHit(index, note.lane, note.noteType, sustain);
    }

    private void fireSustainNoteHits(GameNote note) {
        if (luaRuntime == null || !Double.isFinite(note.nextSustainCallbackMs)) return;
        int index = note.chartIndex;
        int guard = 0;
        while (songPos >= note.nextSustainCallbackMs
                && note.nextSustainCallbackMs < note.endMs() && guard++ < 64) {
            double firedAt = note.nextSustainCallbackMs;
            luaNoteHit(index, note.data, true);
            note.nextSustainCallbackMs = firedAt + sustainCallbackInterval(firedAt);
        }
        skipExcessSustainCallbacks(note);
    }

    /** Avoid an unbounded callback burst after a very large clock jump. */
    private void skipExcessSustainCallbacks(GameNote note) {
        if (Double.isFinite(note.nextSustainCallbackMs) && songPos >= note.nextSustainCallbackMs) {
            note.nextSustainCallbackMs = songPos + sustainCallbackInterval(songPos);
        }
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
        int index = note.chartIndex;
        if (luaRuntime != null) {
            luaRuntime.onNoteMiss(index, note.data.lane, note.data.noteType, note.data.sustainMs > 30);
            luaNoteHit(index, note.data, note.data.sustainMs > 30);
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
                    luaRuntime.onSpawnNote(note.chartIndex, note.data.lane,
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
                    action, localSkinChoice());
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
            // Modal text entry / colour picker capture keys first.
            if (freeCamTextEntry) {
                if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) { commitTextEntry(); return true; }
                if (keyCode == GLFW.GLFW_KEY_ESCAPE) { freeCamTextEntry = false; freeCamTextBox = null; return true; }
                // EditBox handles word nav, selection, Ctrl+Backspace, clipboard, etc.
                if (freeCamTextBox != null) freeCamTextBox.keyPressed(keyCode, scanCode, modifiers);
                return true;
            }
            if (freeCamColorPicker) {
                if (keyCode == GLFW.GLFW_KEY_ESCAPE) closeFreeCamColorPicker(false);
                else if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                    closeFreeCamColorPicker(true);
                } else if (freeCamColorHexBox != null) {
                    freeCamColorHexBox.keyPressed(keyCode, scanCode, modifiers);
                }
                return true;
            }
            // Esc cancels a running transform, then closes help, then leaves free cam.
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                if (freeCamObjects.isTransforming()) freeCamObjects.cancelTransform();
                else if (freeCamHelp) freeCamHelp = false;
                else exitFreeCam();
                return true;
            }
            // F1 toggles the key/keybind help overlay.
            if (keyCode == GLFW.GLFW_KEY_F1) {
                if (!freeCamMenuHidden) freeCamHelp = !freeCamHelp;
                return true;
            }
            // Delete removes the selected editor object.
            if (keyCode == GLFW.GLFW_KEY_DELETE) { freeCamObjects.deleteSelected(); return true; }
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE && freeCamObjects.isTransforming()) {
                freeCamObjects.backspaceNumeric();
                return true;
            }
            // Numpad "." focuses the camera on the selected object (Blender "view selected").
            if (keyCode == GLFW.GLFW_KEY_KP_DECIMAL) { focusSelectedObject(); return true; }
            // Only Lua with the editor's predefined object comment can be pasted.
            if (keyCode == GLFW.GLFW_KEY_V && !freeCamMove
                    && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0
                    && !freeCamObjects.isTransforming() && !freeCamObjects.isSnapDragging()) {
                pasteFreeCamObject();
                return true;
            }
            // Ctrl+Z / Ctrl+Y (Ctrl+Shift+Z) undo/redo, when not mid-transform.
            if ((modifiers & GLFW.GLFW_MOD_CONTROL) != 0
                    && !freeCamObjects.isTransforming() && !freeCamObjects.isSnapDragging()) {
                if (keyCode == GLFW.GLFW_KEY_Z) {
                    if ((modifiers & GLFW.GLFW_MOD_SHIFT) != 0) freeCamObjects.redo();
                    else freeCamObjects.undo();
                    return true;
                }
                if (keyCode == GLFW.GLFW_KEY_Y) { freeCamObjects.redo(); return true; }
            }
            // Blender-style object transforms (cursor released only). G/R/S start a
            // move/rotate/scale; X/Y/Z lock an axis (Shift = plane); Enter confirms.
            if (!freeCamMove) {
                boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
                boolean alt = (modifiers & GLFW.GLFW_MOD_ALT) != 0;
                double gs = minecraft.getWindow().getGuiScale();
                double mxg = minecraft.mouseHandler.xpos() / gs;
                double myg = minecraft.mouseHandler.ypos() / gs;
                switch (keyCode) {
                    case GLFW.GLFW_KEY_G -> {
                        if (alt) resetSelectedTransform("Position", freeCamObjects::resetPosition);
                        else freeCamObjects.beginTransform(FreeCamObjects.Mode.MOVE, mxg, myg);
                        return true;
                    }
                    case GLFW.GLFW_KEY_R -> {
                        if (alt) resetSelectedTransform("Rotation", freeCamObjects::resetRotation);
                        else freeCamObjects.beginTransform(FreeCamObjects.Mode.ROTATE, mxg, myg);
                        return true;
                    }
                    case GLFW.GLFW_KEY_S -> {
                        if (alt) resetSelectedTransform("Scale", freeCamObjects::resetScale);
                        else freeCamObjects.beginTransform(FreeCamObjects.Mode.SCALE, mxg, myg);
                        return true;
                    }
                    case GLFW.GLFW_KEY_X -> {
                        if (freeCamObjects.isTransforming()) { freeCamObjects.setAxis(1, shift); return true; }
                    }
                    case GLFW.GLFW_KEY_Y -> {
                        if (freeCamObjects.isTransforming()) { freeCamObjects.setAxis(2, shift); return true; }
                    }
                    case GLFW.GLFW_KEY_Z -> {
                        if (freeCamObjects.isTransforming()) { freeCamObjects.setAxis(3, shift); return true; }
                    }
                    case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                        if (freeCamObjects.isTransforming()) { freeCamObjects.confirmTransform(); return true; }
                    }
                    default -> { }
                }
            }
            // Ctrl+C copies selected-object Lua; with no selection it copies camera events.
            if (keyCode == GLFW.GLFW_KEY_C && !freeCamMove
                    && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0) {
                if (freeCamObjects.hasSelection()) copySelectedLua();
                else copyFreeCamShot();
                return true;
            }
            // M cycles Follow Pos Movement (override/attached); bare F cycles the Frame.
            if (keyCode == GLFW.GLFW_KEY_M) { freeCamOverride = !freeCamOverride; return true; }
            if (keyCode == GLFW.GLFW_KEY_F
                    && (modifiers & (GLFW.GLFW_MOD_SHIFT | GLFW.GLFW_MOD_CONTROL
                    | GLFW.GLFW_MOD_ALT | GLFW.GLFW_MOD_SUPER)) == 0) {
                freeCamCameraFrame = !freeCamCameraFrame;
                return true;
            }
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
        // Snapshot final rendered gameplay-camera transform before free-cam takes over.
        Camera entryCamera = minecraft.gameRenderer.getMainCamera();
        freeCamEntryCameraPos = entryCamera.getPosition();
        freeCamEntryCameraLeft = new Vector3f(entryCamera.getLeftVector());
        freeCamEntryCameraUp = new Vector3f(entryCamera.getUpVector());
        freeCamEntryCameraLook = new Vector3f(entryCamera.getLookVector());
        freeCamEntryCameraYaw = entryCamera.getYRot();
        freeCamEntryCameraPitch = entryCamera.getXRot();
        freeCamEntryCameraRoll = entryCamera.getRoll();
        freeCam = true;
        freeCamCameraTab = false;
        freeCamPanelScroll = 0;
        beginFreeCamPresentation();
        freeCamPausedAtMs = System.currentTimeMillis();
        songPlayer.pause();
        noteInput.clear();
        hitsoundSnapshot.reset();
        java.util.Arrays.fill(glfwLaneHeld, false);
        freeCamLastMoveNano = System.nanoTime();
        GameplayCamera.beginFreeCam();
        setFreeCamMove(false);
        refreshExistingFreeCamObjects();
    }

    private void exitFreeCam() {
        if (!freeCam) return;
        // Flush a transform or panel edit made during this render before resuming Lua.
        applyExistingFreeCamEdits();
        setFreeCamMove(false);
        freeCam = false;
        restoreHideGui();
        freeCamObjects.despawnPerformers();
        freeCamEntryCameraPos = null;
        freeCamEntryCameraLeft = null;
        freeCamEntryCameraUp = null;
        freeCamEntryCameraLook = null;
        freeCamHelp = false;
        freeCamFocusing = false;
        freeCamFocusPoint = null;
        freeCamViewportDragging = false;
        freeCamViewportIgnoreWarpMotion = false;
        freeCamViewportPivot = null;
        freeCamTextEntry = false;
        freeCamTextBox = null;
        freeCamColorPicker = false;
        freeCamColorHexBox = null;
        pickDrag = 0;
        freeCamAddMenu = false;
        freeCamExistingMenu = false;
        freeCamModMenu = false;
        freeCamAnimMenu = false;
        freeCamPanelScroll = 0;
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

    /** Rebuilds lightweight adapters for objects already owned by Lua/the character roster. */
    private void refreshExistingFreeCamObjects() {
        freeCamObjects.clearLinked();
        if (luaRuntime != null) {
            for (PsychLuaRuntime.EditableWorldObject edit : luaRuntime.editableWorldObjects()) {
                FreeCamObjects.Type type = switch (edit.kind()) {
                    case "spritesheet" -> FreeCamObjects.Type.SPRITESHEET;
                    case "graph" -> FreeCamObjects.Type.GRAPH;
                    case "text" -> FreeCamObjects.Type.TEXT;
                    default -> FreeCamObjects.Type.SPRITE;
                };
                FreeCamObjects.Obj o = freeCamObjects.link(type,
                        FreeCamObjects.Source.LUA_RUNTIME, edit.tag());
                if (o == null) continue;
                o.texturePath = edit.image();
                o.text = edit.text();
                o.x = edit.x(); o.y = edit.y(); o.z = edit.z();
                o.width = edit.width(); o.height = edit.height();
                o.scaleX = edit.scaleX(); o.scaleY = edit.scaleY();
                o.alpha = edit.alpha(); o.color = edit.color(); o.visible = edit.visible();
                o.rotX = edit.rotationX(); o.rotY = edit.rotationY(); o.rotZ = edit.rotationZ();
                o.textSize = edit.textSize();
                o.billboard = edit.billboard(); o.lighting = edit.lighting();
                o.seeThrough = edit.seeThrough(); o.antialiasing = edit.antialiasing();
                o.borderSize = edit.borderSize(); o.borderColor = edit.borderColor();
                o.borderStyle = edit.borderStyle(); o.textAlign = edit.alignment();
                o.italic = edit.italic(); o.anim3d = edit.animation();
                o.availableAnimations = edit.animations();
                o.fps = edit.fps(); o.loop = edit.loop();
                freeCamObjects.markLinkedClean(o);
            }
        }
        for (ExtraCharacterRoster.EditableCharacter edit : extraCharacters.editableCharacters()) {
            FreeCamObjects.Type type = edit.character2D()
                    ? FreeCamObjects.Type.CHARACTER_2D : FreeCamObjects.Type.CHARACTER_3D;
            FreeCamObjects.Obj o = freeCamObjects.link(type,
                    FreeCamObjects.Source.EXTRA_CHARACTER, edit.tag());
            if (o == null) continue;
            o.characterDef = edit.definition();
            o.characterRole = edit.role();
            if (edit.character2D()) {
                o.x = edit.x() * 64.0;
                o.y = -edit.y() * 64.0;
                o.z = edit.z() * 64.0;
            } else {
                // Inverse of FreeCamObjects' 3D performer origin conversion.
                o.x = edit.x() * 64.0;
                o.y = (0.5 - edit.y()) * 64.0;
                o.z = (edit.z() + 2.0) * 64.0;
            }
            o.rotX = edit.rotationX();
            o.rotY = edit.rotationY();
            o.rotZ = edit.rotationZ();
            o.width = edit.width(); o.height = edit.height();
            o.scaleX = edit.scaleX(); o.scaleY = edit.scaleY(); o.scaleZ = edit.scaleZ();
            o.alpha = edit.alpha(); o.color = edit.color(); o.visible = edit.visible();
            o.billboard = edit.billboard(); o.lighting = edit.lighting();
            o.seeThrough = edit.seeThrough(); o.antialiasing = edit.antialiasing();
            o.anim3d = edit.animation();
            o.availableAnimations = edit.animations();
            freeCamObjects.markLinkedClean(o);
        }
        linkMainFreeCamCharacter(true, "boyfriend");
        linkMainFreeCamCharacter(false, "dad");
        freeCamMessage = "Linked " + freeCamObjects.linkedCount() + " existing world object(s)";
        freeCamMessageUntil = System.currentTimeMillis() + 3000;
    }

    /** Pushes adapter values back into their original objects. No duplicate is created. */
    private void applyExistingFreeCamEdits() {
        freeCamObjects.forEachChangedLinked(o -> {
            if (o.source == FreeCamObjects.Source.LUA_RUNTIME && luaRuntime != null) {
                String kind = switch (o.type) {
                    case SPRITESHEET -> "spritesheet";
                    case GRAPH -> "graph";
                    case TEXT -> "text";
                    default -> "sprite";
                };
                luaRuntime.applyWorldObjectEdit(new PsychLuaRuntime.EditableWorldObject(
                        o.sourceTag, kind, o.texturePath, o.text,
                        o.x, o.y, o.z, o.width, o.height,
                        o.scaleX, o.scaleY, o.alpha,
                        o.rotX, o.rotY, o.rotZ,
                        o.color, o.textSize, o.visible,
                        o.billboard, o.lighting, o.seeThrough, o.antialiasing,
                        o.borderSize, o.borderColor, o.borderStyle, o.textAlign, o.italic,
                        o.anim3d, o.availableAnimations, o.fps, o.loop));
            } else if (o.source == FreeCamObjects.Source.EXTRA_CHARACTER) {
                boolean twoD = o.type == FreeCamObjects.Type.CHARACTER_2D;
                double x = o.x / 64.0;
                double y = twoD ? -o.y / 64.0 : 0.5 - o.y / 64.0;
                double z = twoD ? o.z / 64.0 : o.z / 64.0 - 2.0;
                extraCharacters.applyCharacterEdit(new ExtraCharacterRoster.EditableCharacter(
                        o.sourceTag, o.characterDef, o.characterRole, twoD,
                        x, y, z, o.rotX, o.rotY, o.rotZ, o.visible,
                        o.width, o.height, o.scaleX, o.scaleY, o.scaleZ,
                        o.alpha, o.color, o.billboard, o.lighting,
                        o.seeThrough, o.antialiasing, o.anim3d, o.availableAnimations));
            } else if (o.source == FreeCamObjects.Source.MAIN_CHARACTER) {
                applyMainFreeCamCharacter(o);
            }
        });
    }

    private void linkMainFreeCamCharacter(boolean playerSide, String tag) {
        if (playbackPolicy == null || playbackPolicy.usesPsychCamera()) return;
        Player visual = performerVisualPlayer(playerSide);
        if (visual == null || !CharacterAnimations.hasActiveBbsForm(visual)) return;
        FreeCamObjects.Obj o = freeCamObjects.link(FreeCamObjects.Type.CHARACTER_3D,
                FreeCamObjects.Source.MAIN_CHARACTER, tag);
        if (o == null) return;
        String role = playerSide ? "player" : "opponent";
        o.characterRole = role;
        o.characterDef = performerSet(playerSide);
        Double worldX = performerWorldPixel(visual, "worldX");
        Double worldY = performerWorldPixel(visual, "worldY");
        Double worldZ = performerWorldPixel(visual, "worldZ");
        o.x = worldX == null ? 0 : worldX;
        o.y = worldY == null ? 0 : worldY;
        o.z = worldZ == null ? 0 : worldZ;
        PerformerTween tween = playerSide ? playerPerformerTween : opponentPerformerTween;
        o.rotX = tween.rotationX;
        o.rotY = tween.rotation;
        o.rotZ = tween.rotationZ;
        o.scaleX = tween.scaleX;
        o.scaleY = tween.scaleY;
        o.scaleZ = tween.scaleZ;
        o.width = 48;
        o.height = 96;
        o.billboard = false;
        o.anim3d = "idle";
        o.availableAnimations = CharacterAnimations.actionNames(o.characterDef, role);
        freeCamObjects.markLinkedClean(o);
    }

    private void applyMainFreeCamCharacter(FreeCamObjects.Obj o) {
        boolean playerSide = o.sourceTag.equalsIgnoreCase("boyfriend")
                || o.sourceTag.equalsIgnoreCase("bf") || o.sourceTag.equalsIgnoreCase("player");
        Entity entity = performerEntity(playerSide);
        if (entity == null) return;
        PerformerTween tween = playerSide ? playerPerformerTween : opponentPerformerTween;
        if (!tween.homeKnown) {
            tween.captureHome(entity.getX(), entity.getY(), entity.getZ(), entity.getYRot());
        }
        Direction facing = StageOrientation.facing();
        Direction right = facing.getCounterClockWise();
        double targetX = machinePos.getX() + 0.5
                + right.getStepX() * o.x / 64.0 + facing.getStepX() * o.z / 64.0;
        double targetY = machinePos.getY() + 0.5 - o.y / 64.0;
        double targetZ = machinePos.getZ() + 0.5
                + right.getStepZ() * o.x / 64.0 + facing.getStepZ() * o.z / 64.0;
        double dx = targetX - tween.homeX;
        double dz = targetZ - tween.homeZ;
        tween.setOffsetX(dx * right.getStepX() + dz * right.getStepZ());
        tween.setOffsetY(targetY - tween.homeY);
        tween.setOffsetZ(dx * facing.getStepX() + dz * facing.getStepZ());
        tween.setOffsetRotationX(o.rotX);
        tween.setOffsetRotation(o.rotY);
        tween.setOffsetRotationZ(o.rotZ);
        tween.setScaleX(o.scaleX);
        tween.setScaleY(o.scaleY);
        tween.setScaleZ(o.scaleZ);
    }

    private String performerSet(boolean playerSide) {
        if (playerSide == myChartSideIsPlayer) return myAnimSet;
        if (partnerId != null) return partnerAnimSet;
        return opponentBotSet == null ? CharacterAnimations.DEFAULT_SET : opponentBotSet;
    }

    /** Grabs/releases the cursor for spectator look while keeping this screen open. */
    private void setFreeCamMove(boolean move) {
        if (move == freeCamMove) {
            if (!move) return;
        }
        freeCamMove = move;
        if (move) {
            freeCamViewportDragging = false;
            freeCamViewportIgnoreWarpMotion = false;
            freeCamViewportPivot = null;
        }
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
            markGameplayCursorActive();
        }
    }

    /** Per-frame free-camera look (captured mouse) and movement (polled keys). */
    private void updateFreeCam() {
        long now = System.nanoTime();
        double dt = Math.min(0.1, (now - freeCamLastMoveNano) / 1_000_000_000.0);
        freeCamLastMoveNano = now;
        // The focus tween runs even with the cursor released (object selected).
        updateFreeCamFocus();
        if (!freeCamMove) return;

        long window = minecraft.getWindow().getWindow();
        // Movement is hold-to-grab. Polling as well as handling mouseReleased
        // prevents a missed release (focus change/cursor-mode transition) from
        // leaving the camera captured.
        if (GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS) {
            setFreeCamMove(false);
            return;
        }
        double[] cx = new double[1];
        double[] cy = new double[1];
        GLFW.glfwGetCursorPos(window, cx, cy);
        double dx = cx[0] - freeCamLastCursorX;
        double dy = cy[0] - freeCamLastCursorY;
        freeCamLastCursorX = cx[0];
        freeCamLastCursorY = cy[0];
        double sensitivity = 0.15;
        if (dx != 0 || dy != 0) {
            GameplayCamera.turnFreeCam(dx * sensitivity, dy * sensitivity);
            freeCamFocusPoint = null;
        }

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
            freeCamFocusPoint = null;
        }

        clampFreeCamToLoadedWorld();
    }

    /** Keep the camera inside the terrain currently loaded around the player. */
    private void clampFreeCamToLoadedWorld() {
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

    private void adjustFreeCamSpeed(double factor) {
        freeCamSpeed = Math.max(0.5, Math.min(80.0, freeCamSpeed * factor));
    }

    private void copyFreeCamShot() {
        // Orbit capture must start from the authored position immediately. Use
        // the stable machine frame and a constant Follow Pos sample so orbit
        // does not capture halfway through the usual 0.5-second position tween.
        double[] follow = freeCamOrbitShot ? freeCamFollowValues(false) : freeCamFollowValues();
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
        String frame = !freeCamOrbitShot && freeCamCameraFrame ? "camera" : "";
        SongChart.Event pos = new SongChart.Event(0, ChartEventTypes.CAMERA_FOLLOW_POS,
                num(bx), num(by), num(bz), freeCamOrbitShot ? "constant" : "",
                movement, frame, "", false);
        // Camera Rotation 3D is the orbital animator once pivot mode is active.
        // The values copied here establish a continuous zero-phase baseline.
        SongChart.Event rot = new SongChart.Event(0, ChartEventTypes.CAMERA_ROTATION_3D,
                num(pitch), num(yaw),
                num(roll), freeCamOrbitShot ? "constant" : "", "", false);
        // Camera Zoom: amount (v2 = duration, v3 = ease, left empty).
        SongChart.Event cameraZoom = new SongChart.Event(0, ChartEventTypes.CAMERA_ZOOM,
                num(zoom), "", "", "", "", false);
        java.util.List<SongChart.Event> events = new java.util.ArrayList<>();
        events.add(pos);
        events.add(rot);
        events.add(cameraZoom);
        if (freeCamOrbitShot) {
            double[] pivot = freeCamOrbitPivotValues();
            events.add(new SongChart.Event(0, ChartEventTypes.CAMERA_ORBIT,
                    "on", freeCamOrbitPinned ? "pin" : "follow",
                    num(round2(pivot[0])) + "," + num(round2(pivot[1])) + ","
                            + num(round2(pivot[2])), "", "", "", "", false));
        }
        CameraShotClipboard.set(events);
        freeCamMessage = "Copied camera shot — paste in the chart editor";
        freeCamMessageUntil = System.currentTimeMillis() + 3000;
    }

    private void toggleFreeCamShotMode() {
        freeCamOrbitShot = !freeCamOrbitShot;
        if (freeCamOrbitShot) {
            Camera camera = minecraft.gameRenderer.getMainCamera();
            freeCamFocusPoint = viewportPivot(camera);
            GameplayCamera.aimFreeCamAt(freeCamFocusPoint, camera.getYRot(), camera.getXRot());
        }
    }

    /** Camera Follow Pos X/Y/Z for the current Movement/Frame options. */
    private double[] freeCamFollowValues() {
        return freeCamFollowValues(freeCamCameraFrame);
    }

    private double[] freeCamFollowValues(boolean cameraFrame) {
        Camera cam = minecraft.gameRenderer.getMainCamera();
        return GameplayCamera.followPosValues(freeCamOverride, cameraFrame,
                cam.getLeftVector(), cam.getUpVector(), cam.getLookVector());
    }

    private double[] freeCamOrbitPivotValues() {
        Camera camera = minecraft.gameRenderer.getMainCamera();
        Vec3 pivot = freeCamFocusPoint == null ? viewportPivot(camera) : freeCamFocusPoint;
        double[] local = GameplayCamera.worldToFreeOffset(pivot);
        if (freeCamOrbitPinned) return local;
        double[] focus = GameplayCamera.worldToFreeOffset(GameplayCamera.focusWorldPos());
        return new double[]{local[0] - focus[0], local[1] - focus[1], local[2] - focus[2]};
    }

    private void setFreeCamOrbitPivotValue(int axis, double value) {
        if (!Double.isFinite(value)) return;
        double[] pivot = freeCamOrbitPivotValues();
        pivot[Math.max(0, Math.min(2, axis))] = value;
        Vec3 origin = freeCamOrbitPinned ? null : GameplayCamera.focusWorldPos();
        freeCamFocusPoint = GameplayCamera.stageOffsetFrom(origin, pivot[0], pivot[1], pivot[2]);
        Camera camera = minecraft.gameRenderer.getMainCamera();
        GameplayCamera.aimFreeCamAt(freeCamFocusPoint, camera.getYRot(), camera.getXRot());
    }

    private void toggleFreeCamOrbitPinned() {
        // The represented world point must not move when switching between an
        // absolute pinned pivot and a focus-relative pivot.
        Camera camera = minecraft.gameRenderer.getMainCamera();
        if (freeCamFocusPoint == null) freeCamFocusPoint = viewportPivot(camera);
        freeCamOrbitPinned = !freeCamOrbitPinned;
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
        markGameplayCursorActive();
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
        markGameplayCursorActive();
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
        PacketDistributor.sendToServer(new FnfPayloads.LeaveC2S(machinePos, false,
                FnfPayloads.LeaveC2S.RETURN_CHART_EDITOR));
        ClientSession.reset();
        if (minecraft.player != null) CharacterAnimations.stop(minecraft.player);
        // This non-pausing screen lets the integrated server process the leave
        // packet. It opens the editor only after blocks and player state are back.
        minecraft.setScreen(new RollbackWaitingScreen(machinePos, editor));
    }

    /**
     * Restarts by tearing this run down and playing the song again from a brand
     * new screen, which is exactly what quitting and re-entering does. Resetting
     * fields in place had to remember every piece of state a chart, event or Lua
     * script could touch, and anything missed - tween targets in particular -
     * carried over into the next attempt. Rebuilding cannot miss anything.
     */
    private void restart() {
        if (!editorPlaytest) {
            if (restartPending) return;
            restartPending = true;
            PacketDistributor.sendToServer(new FnfPayloads.RestartSongC2S(machinePos));
            return;
        }
        rebuildForRestart(botEntityId, 2000);
    }

    /** Called after the server restored a normal solo session to its pre-song state. */
    public void onServerRestart(FnfPayloads.RestartSongS2C payload) {
        if (editorPlaytest || resourcesDisposed || !machinePos.equals(payload.pos())) return;
        rebuildForRestart(payload.botEntityId(), Math.max(0, payload.startDelayMs()));
    }

    private void rebuildForRestart(int nextBotEntityId, long startDelayMs) {
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
                partnerName, initialPartnerAnimSet, nextBotEntityId,
                System.currentTimeMillis() + startDelayMs);
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
        next.applySongNoteSkin();
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
        // Direct custom-menu launches may return to that same menu or close every
        // menu. Built-in selection keeps its existing selector return. Duets always
        // return to the world to avoid host/guest contention over one machine.
        byte returnTarget = duet ? FnfPayloads.LeaveC2S.RETURN_WORLD : songExitTarget;
        // finishedOnly = the song ended normally (server already tore the session down on SongEnd);
        // otherwise the server cancels the still-active session. Either way it then reopens the menu.
        PacketDistributor.sendToServer(new FnfPayloads.LeaveC2S(machinePos, endSent, returnTarget));
        ClientSession.reset();
        songPlayer.dispose();
        if (minecraft.player != null) CharacterAnimations.stop(minecraft.player);
        String returning = returnTarget == FnfPayloads.LeaveC2S.RETURN_MACHINE_MENU
                ? "Returning to machine menu..." : "Returning to song list...";
        minecraft.setScreen(returnTarget == FnfPayloads.LeaveC2S.RETURN_WORLD
                ? null : new WaitingScreen(Component.literal(returning)));
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
    public void mouseMoved(double mouseX, double mouseY) {
        markGameplayCursorActive();
        super.mouseMoved(mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        markGameplayCursorActive();
        if (freeCam) {
            // The help modal swallows the next click to dismiss it.
            if (freeCamHelp) { freeCamHelp = false; return true; }
            // Modal text entry / colour picker intercept clicks.
            if (freeCamTextEntry) {
                if (freeCamTextBox != null) freeCamTextBox.mouseClicked(mouseX, mouseY, button);
                return true;
            }
            if (freeCamColorPicker) {
                if (freeCamColorHexBox != null
                        && freeCamColorHexBox.mouseClicked(mouseX, mouseY, button)) {
                    // This field is rendered manually rather than registered as a
                    // Screen child, so direct click forwarding must also assign
                    // the container focus that vanilla normally assigns for us.
                    setFocused(freeCamColorHexBox);
                    freeCamColorHexBox.setFocused(true);
                }
                if (button == 0) {
                    if (mouseX >= colorCancelX && mouseX < colorCancelX + colorCancelW
                            && mouseY >= colorCancelY && mouseY < colorCancelY + colorCancelH) {
                        closeFreeCamColorPicker(false);
                        return true;
                    }
                    if (mouseX >= colorDoneX && mouseX < colorDoneX + colorDoneW
                            && mouseY >= colorDoneY && mouseY < colorDoneY + colorDoneH) {
                        closeFreeCamColorPicker(true);
                        return true;
                    }
                    if (mouseX >= pickSqX && mouseX < pickSqX + 80
                            && mouseY >= pickSqY && mouseY < pickSqY + 80) {
                        pickDrag = 1; updatePicker(mouseX, mouseY); return true;
                    }
                    if (mouseX >= pickHueBarX && mouseX < pickHueBarX + pickHueBarW
                            && mouseY >= pickHueBarY && mouseY < pickHueBarY + 10) {
                        pickDrag = 2; updatePicker(mouseX, mouseY); return true;
                    }
                }
                return true;
            }
            // While transforming, clicks confirm (left) or cancel (right).
            if (freeCamObjects.isTransforming()) {
                if (button == 0) freeCamObjects.confirmTransform();
                else if (button == 1) freeCamObjects.cancelTransform();
                return true;
            }
            // While face-snap dragging, right-click cancels back to the pre-drag state.
            if (freeCamObjects.isSnapDragging()) {
                if (button == 1) freeCamObjects.cancelSnapDrag();
                return true;
            }
            // LMB gives editor interactions priority. Clicking empty viewport space
            // deselects and captures the cursor for camera movement until release.
            if (button == 0 && !freeCamMove) {
                for (FreeCamButton b : freeCamButtons) {
                    if (b.contains(mouseX, mouseY)) { b.action().run(); return true; }
                }
                // Shift is needed only for this initial press. It deliberately
                // bypasses objects/gizmos under the cursor; once grabbed, holding
                // LMB alone keeps camera movement active.
                if (hasShiftDown()) {
                    setFreeCamMove(true);
                    return true;
                }
                Camera cam = minecraft.gameRenderer.getMainCamera();
                Direction facing = StageOrientation.facing();
                Vec3 dir = cursorRayDir(mouseX, mouseY);
                if (freeCamObjects.cursorOverSelection(cam.getPosition(), dir, machinePos, facing)) {
                    freeCamObjects.beginSnapDrag();
                    freeCamObjects.snapToFace(raycastFromCursor(mouseX, mouseY), machinePos, facing);
                } else {
                    boolean hitObject = freeCamObjects.pick(cam.getPosition(), dir, machinePos, facing);
                    if (!hitObject) setFreeCamMove(true);
                }
                return true;
            }
            // Blender-style viewport navigation while cursor is available.
            if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE && !freeCamMove) {
                beginFreeCamViewportDrag();
                return true;
            }
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
    public boolean charTyped(char codePoint, int modifiers) {
        if (freeCam && freeCamColorPicker) {
            if (freeCamColorHexBox != null) freeCamColorHexBox.charTyped(codePoint, modifiers);
            return true;
        }
        if (freeCam && freeCamTextEntry) {
            if (freeCamTextBox != null) freeCamTextBox.charTyped(codePoint, modifiers);
            return true;
        }
        if (freeCam && freeCamObjects.inputNumeric(codePoint)) return true;
        return super.charTyped(codePoint, modifiers);
    }

    private void updatePicker(double mx, double my) {
        if (pickDrag == 1) {
            pickSat = (float) Math.max(0, Math.min(1, (mx - pickSqX) / 79.0));
            pickBri = 1f - (float) Math.max(0, Math.min(1, (my - pickSqY) / 79.0));
        } else if (pickDrag == 2) {
            pickHue = (float) Math.max(0, Math.min(1, (mx - pickHueBarX) / (pickHueBarW - 1.0)));
        }
        applyPickedColor();
        updateFreeCamColorHex();
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        markGameplayCursorActive();
        if (freeCam && freeCamColorPicker && pickDrag != 0) {
            updatePicker(mouseX, mouseY);
            return true;
        }
        if (freeCam && freeCamTextEntry && freeCamTextBox != null) {
            freeCamTextBox.mouseDragged(mouseX, mouseY, button, dragX, dragY);
            return true;
        }
        if (freeCam && button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE && freeCamViewportDragging) {
            // GLFW reports the programmatic edge warp as mouse motion. Discard that
            // one synthetic delta or the camera would jump by nearly a screen width.
            if (freeCamViewportIgnoreWarpMotion) {
                freeCamViewportIgnoreWarpMotion = false;
                return true;
            }
            updateFreeCamViewportDrag(dragX, dragY);
            wrapFreeCamViewportCursor(mouseX, mouseY);
            return true;
        }
        if (freeCam && button == GLFW.GLFW_MOUSE_BUTTON_LEFT && freeCamMove) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        markGameplayCursorActive();
        if (freeCam && pickDrag != 0) { pickDrag = 0; return true; }
        if (freeCam && button == 0 && freeCamObjects.isSnapDragging()) {
            freeCamObjects.endSnapDrag();
            return true;
        }
        if (freeCam && button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE && freeCamViewportDragging) {
            freeCamViewportDragging = false;
            freeCamViewportIgnoreWarpMotion = false;
            freeCamViewportPivot = null;
            return true;
        }
        if (freeCam && button == GLFW.GLFW_MOUSE_BUTTON_LEFT && freeCamMove) {
            setFreeCamMove(false);
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /** Builds a world-space ray from the cursor using the camera basis and vertical FOV. */
    private Vec3 cursorRayDir(double mouseX, double mouseY) {
        Camera cam = minecraft.gameRenderer.getMainCamera();
        Vec3 fwd = new Vec3(cam.getLookVector());
        Vec3 up = new Vec3(cam.getUpVector());
        Vec3 right = new Vec3(cam.getLeftVector()).scale(-1);
        double fov = Math.toRadians(minecraft.options.fov().get());
        double aspect = (double) width / Math.max(1, height);
        double ndcX = (mouseX / width) * 2 - 1;
        double ndcY = 1 - (mouseY / height) * 2;
        double t = Math.tan(fov / 2);
        return fwd.add(right.scale(ndcX * t * aspect)).add(up.scale(ndcY * t)).normalize();
    }

    /** Clips the cursor ray against blocks for face-snapping. */
    private BlockHitResult raycastFromCursor(double mouseX, double mouseY) {
        Camera cam = minecraft.gameRenderer.getMainCamera();
        Vec3 eye = cam.getPosition();
        Vec3 end = eye.add(cursorRayDir(mouseX, mouseY).scale(48));
        return minecraft.level.clip(new ClipContext(eye, end,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, minecraft.player));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        markGameplayCursorActive();
        if (freeCam) {
            // Fly/look mode follows Blender's fly navigation: wheel adjusts travel speed.
            if (freeCamMove) {
                double factor = scrollY > 0 ? Math.pow(1.15, scrollY)
                        : (scrollY < 0 ? Math.pow(1 / 1.15, -scrollY) : 1);
                adjustFreeCamSpeed(factor);
                return true;
            }
            // Over the left panel, the wheel scrolls the menu when it overflows the screen.
            if (mouseX <= 176 && freeCamPanelMaxScroll > 0 && !freeCamObjects.isTransforming()) {
                freeCamPanelScroll = Math.max(0, Math.min(freeCamPanelMaxScroll,
                        freeCamPanelScroll - (int) Math.signum(scrollY) * 16));
                return true;
            }
            // Normal viewport wheel is a positional dolly, not a Camera Zoom/FOV change.
            dollyFreeCam(scrollY);
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
            // Middlescroll follows input ownership, not chart character role:
            // the locally controlled strums stay centered when playing Dad.
            if (mine) {
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
    public BlockPos psychLuaMachinePosition() { return machinePos; }
    public double psychLuaBeat() { return conductor.beatAt(Math.max(0, songPos)); }
    public int psychLuaMeterBeat() {
        return (int) Math.min(Integer.MAX_VALUE, currentMeter().pulse);
    }
    public int psychLuaBeatInMeasure() { return currentMeter().beatInMeasure; }
    public int psychLuaMeasure() {
        return (int) Math.min(Integer.MAX_VALUE, currentMeter().measure);
    }
    public int psychLuaTimeSignatureNumerator() { return currentMeter().signature.numerator(); }
    public int psychLuaTimeSignatureDenominator() { return currentMeter().signature.denominator(); }
    public int psychLuaSection() { return Math.max(0, camSection); }
    public boolean psychLuaSongStarted() { return songPlayer.isStarted(); }
    public int psychLuaScore() { return score; }
    public int psychLuaMisses() { return misses; }
    public int psychLuaCombo() { return combo; }
    /**
     * Psych's health value belongs to the BF/player chart role. Internally we
     * keep local-player health so the same fail-state code works on either
     * side, therefore opponent play needs the complementary value.
     */
    public double psychLuaHealth() {
        return myChartSideIsPlayer || playBoth ? health : 2.0 - health;
    }
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
        String extra = resolvedExtraCharacterTarget(role);
        if (extra != null) return extraCharacters.x(extra);
        return psychScene == null ? 0 : psychScene.midpointX(role);
    }
    public double psychLuaCharacterMidpointY(String role) {
        String extra = resolvedExtraCharacterTarget(role);
        if (extra != null) return extraCharacters.y(extra);
        return psychScene == null ? 0 : psychScene.midpointY(role);
    }
    public void psychLuaSetHealth(double value) {
        float roleHealth = (float) Math.max(0, Math.min(2, value));
        health = myChartSideIsPlayer || playBoth ? roleHealth : 2f - roleHealth;
    }
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
        String extra = resolvedExtraCharacterTarget(role);
        if (extra != null) return extraCharacters.x(extra);
        return psychScene == null ? 0 : psychScene.defaultX(role);
    }
    public double psychLuaDefaultCharacterY(String role) {
        String extra = resolvedExtraCharacterTarget(role);
        if (extra != null) return extraCharacters.y(extra);
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
        String role = note != null && note.gfNote ? "gf"
                : note == null ? fallbackRole
                : note.playerSide ? "boyfriend" : "dad";
        boolean hey = !miss && note != null && "Hey!".equalsIgnoreCase(note.noteType);
        String gfExtra = note != null && note.gfNote ? gfReplacementTag() : null;
        if (gfExtra != null) {
            String suffix = note.animSuffix == null ? "" : note.animSuffix;
            if (hey) extraCharacters.hey(gfExtra, 0.6);
            else extraCharacters.sing(gfExtra, DIR_NAMES[lane], miss, suffix);
        }
        if (psychScene != null) {
            if (hey) psychScene.hey(role, 0.6);
            else psychScene.sing(role, lane, miss, note == null ? "" : note.animSuffix);
        }
        // Drive the solo opponent bot character when the note is on its side.
        if (opponentBot != null && opponentBot.role().equals(botRoleFor(role))) {
            String suffix = note == null || note.animSuffix == null ? "" : note.animSuffix;
            float[] cameraOffset = opponentBot.playWithCameraOffset(
                    hey ? "hey" : miss ? "miss" + suffix : DIR_NAMES[lane] + suffix);
            if (!miss && cameraOffset != null) {
                GameplayCamera.sing("boyfriend".equals(role), cameraOffset[0], cameraOffset[1]);
            }
        }
    }

    /** Maps a Psych role ("dad"/"boyfriend") to the animation role ("opponent"/"player"). */
    private static String botRoleFor(String psychRole) {
        return "dad".equals(psychRole) ? "opponent"
                : "boyfriend".equals(psychRole) ? "player" : psychRole;
    }

    private void animatePsychHold(SongChart.Note note, int lane, String fallbackRole) {
        String role = note != null && note.gfNote ? "gf"
                : note == null ? fallbackRole
                : note.playerSide ? "boyfriend" : "dad";
        String suffix = note == null || note.animSuffix == null ? "" : note.animSuffix;
        String gfExtra = note != null && note.gfNote ? gfReplacementTag() : null;
        if (gfExtra != null) extraCharacters.sing(gfExtra, DIR_NAMES[lane], false, suffix);
        if (psychScene != null) psychScene.hold(role, lane, suffix);
        if (opponentBot != null && opponentBot.role().equals(botRoleFor(role))) {
            opponentBot.hold(DIR_NAMES[lane] + suffix, BBS_HOLD_LOOP_MS);
        }
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
        ChunkPointProperty chunkPoint = chunkPointProperty(path);
        if (chunkPoint != null && minecraft.level != null) {
            com.fnfmod.block.ChunkLoaderPointBlockEntity point =
                    com.fnfmod.world.ChunkLoaderPointService.findLoaded(minecraft.level, chunkPoint.tag());
            if (point == null) return null;
            return switch (chunkPoint.property().toLowerCase(java.util.Locale.ROOT)) {
                case "enabled", "active", "on" -> point.enabled();
                case "radius" -> point.radius();
                case "tag", "id" -> point.pointTag();
                case "x" -> point.getBlockPos().getX();
                case "y" -> point.getBlockPos().getY();
                case "z" -> point.getBlockPos().getZ();
                default -> null;
            };
        }
        switch (path) {
            case "rating.visible", "combo.visible" -> { return showRatingPopups; }
            // Blockified's draggable Rating Position is exposed in the same
            // 1280x720 canvas used by Lua HUD sprites. Custom rating scripts can
            // therefore follow the player's setting at every GUI scale.
            case "rating.x" -> { return ratingPopupCanvasX(); }
            case "rating.y" -> { return ratingPopupCanvasY(); }
            case "timeBar.visible", "timeBarBG.visible" -> { return showTimeBar; }
            case "timeTxt.visible", "timeText.visible" -> { return showTimeText; }
            default -> { }
        }
        int extraDot = path.indexOf('.');
        String extraTag = extraDot > 0
                ? resolvedExtraCharacterTarget(path.substring(0, extraDot)) : null;
        if (extraTag != null) {
            String tag = extraTag;
            String property = path.substring(extraDot + 1);
            return switch (property) {
                case "x" -> extraCharacters.x(tag);
                case "y" -> extraCharacters.y(tag);
                case "z" -> extraCharacters.z(tag);
                case "rotation.x", "rotationX", "angleX" -> extraCharacters.rotationX(tag);
                case "angle", "rotation", "rotation.y", "rotationY", "angleY" ->
                        extraCharacters.rotation(tag);
                case "rotation.z", "rotationZ", "angleZ" -> extraCharacters.rotationZ(tag);
                case "visible" -> extraCharacters.visible(tag);
                case "grav", "gravity" -> extraCharacters.gravity(tag);
                case "collision", "collisions", "solid" -> extraCharacters.collision(tag);
                case "shadow", "shadows" -> extraCharacters.shadow(tag);
                case "irlightsShadow", "irlightsShadows", "projectedShadow", "projectedShadows" ->
                        extraCharacters.irlightsShadows(tag);
                case "alpha" -> extraCharacters.alpha(tag);
                case "color" -> extraCharacters.color(tag);
                case "scale.x", "scaleX" -> extraCharacters.scaleX(tag);
                case "scale.y", "scaleY" -> extraCharacters.scaleY(tag);
                case "scale.z", "scaleZ" -> extraCharacters.scaleZ(tag);
                case "flipx", "flipX", "flip_x" -> extraCharacters.flipX(tag);
                case "billboard", "worldBillboard", "alwaysFaceCamera" -> extraCharacters.billboard(tag);
                case "lighting", "worldLighting", "affectedByLighting" -> extraCharacters.lighting(tag);
                case "fullbright", "fullBright", "unlit", "flat", "flatShading", "flatshading" ->
                        !extraCharacters.lighting(tag);
                case "seethrough", "seeThrough", "worldSeeThrough", "throughWalls", "noDepth" -> extraCharacters.seeThrough(tag);
                case "antialiasing" -> extraCharacters.antialiasing(tag);
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
        if (extraDot > 0 && isFullbrightProperty(path.substring(extraDot + 1))) {
            Entity performer = performerEntityForTag(path.substring(0, extraDot));
            if (performer instanceof net.minecraft.world.entity.player.Player player) {
                return CharacterAnimations.isLightingForced(player);
            }
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
                    case "rotation.x", "rotationX", "angleX" -> performer.rotationX;
                    case "angle", "rotation", "rotation.y", "rotationY", "angleY" -> performer.rotation;
                    case "rotation.z", "rotationZ", "angleZ" -> performer.rotationZ;
                    case "scale.x", "scaleX" -> performer.scaleX;
                    case "scale.y", "scaleY" -> performer.scaleY;
                    case "scale.z", "scaleZ" -> performer.scaleZ;
                    default -> null;
                };
            }
        }
        String hudStyle = effectiveHudStyle();
        if ("fnf".equals(hudStyle)) {
            if (path.equals("healthBar.leftBar.color")) return fnfOpponentBarColor();
            if (path.equals("healthBar.rightBar.color")) return fnfPlayerBarColor();
            Object hudValue = fnfHud.property(path,
                    Math.max(0, Math.min(100, psychLuaHealth() * 50.0)), fnfScoreText());
            if (hudValue != null) return hudValue;
        } else {
            Object hudValue = textHud.property(path, defaultHudBarX(), defaultHudBarY(),
                    defaultHudScoreX(), defaultHudScoreY(), TEXT_HUD_DEFAULT_COLOR,
                    generatedHudText(hudStyle));
            if (hudValue != null) return hudValue;
        }
        return switch (path) {
            case "health" -> psychLuaHealth();
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
            case "curMeterBeat" -> psychLuaMeterBeat();
            case "curBeatInMeasure" -> psychLuaBeatInMeasure();
            case "curMeasure" -> psychLuaMeasure();
            case "timeSignatureNumerator" -> psychLuaTimeSignatureNumerator();
            case "timeSignatureDenominator" -> psychLuaTimeSignatureDenominator();
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
        ChunkPointProperty chunkPoint = chunkPointProperty(path);
        if (chunkPoint != null) {
            String property = chunkPoint.property().toLowerCase(java.util.Locale.ROOT);
            if (!java.util.Set.of("enabled", "active", "on", "radius", "tag", "id").contains(property)) {
                return false;
            }
            PacketDistributor.sendToServer(new FnfPayloads.ChunkLoaderPropertyC2S(
                    machinePos, chunkPoint.tag(), property, String.valueOf(value)));
            return true;
        }
        switch (path) {
            case "rating.visible" -> { showRatingPopups = noteBool(value, true); return true; }
            case "combo.visible" -> { showRatingPopups = noteBool(value, true); return true; }
            case "timeBar.visible", "timeBarBG.visible" -> { showTimeBar = noteBool(value, true); return true; }
            case "timeTxt.visible", "timeText.visible" -> { showTimeText = noteBool(value, true); return true; }
            default -> { }
        }
        int extraDot = path.indexOf('.');
        String extraTag = extraDot > 0
                ? resolvedExtraCharacterTarget(path.substring(0, extraDot)) : null;
        if (extraTag != null) {
            String tag = extraTag;
            String property = path.substring(extraDot + 1);
            return switch (property) {
                case "x" -> extraCharacters.setX(tag, noteNumber(value, extraCharacters.x(tag)));
                case "y" -> extraCharacters.setY(tag, noteNumber(value, extraCharacters.y(tag)));
                case "z" -> extraCharacters.setZ(tag, noteNumber(value, extraCharacters.z(tag)));
                case "rotation.x", "rotationX", "angleX" ->
                        extraCharacters.setRotationX(tag, noteNumber(value, extraCharacters.rotationX(tag)));
                case "angle", "rotation", "rotation.y", "rotationY", "angleY" ->
                        extraCharacters.setRotation(tag, noteNumber(value, extraCharacters.rotation(tag)));
                case "rotation.z", "rotationZ", "angleZ" ->
                        extraCharacters.setRotationZ(tag, noteNumber(value, extraCharacters.rotationZ(tag)));
                case "visible" -> extraCharacters.setVisible(tag, noteBool(value, true));
                case "grav", "gravity" -> extraCharacters.setGravity(tag, noteBool(value, true));
                case "collision", "collisions", "solid" ->
                        extraCharacters.setCollision(tag, noteBool(value, true));
                case "shadow", "shadows" -> extraCharacters.setShadow(tag, noteBool(value, true));
                case "irlightsShadow", "irlightsShadows", "projectedShadow", "projectedShadows" ->
                        extraCharacters.setIrlightsShadows(tag, noteBool(value, true));
                // 2D visuals plus BBS XYZ render scale.
                case "alpha" -> extraCharacters.setAlpha(tag, noteNumber(value, extraCharacters.alpha(tag)));
                case "color" -> extraCharacters.setColor(tag, (int) (long) noteNumber(value, 0xFFFFFF));
                case "scale.x", "scaleX" -> extraCharacters.setScaleX(tag, noteNumber(value, extraCharacters.scaleX(tag)));
                case "scale.y", "scaleY" -> extraCharacters.setScaleY(tag, noteNumber(value, extraCharacters.scaleY(tag)));
                case "scale.z", "scaleZ" -> extraCharacters.setScaleZ(tag, noteNumber(value, extraCharacters.scaleZ(tag)));
                case "flipx", "flipX", "flip_x" -> extraCharacters.setFlipX(tag, noteBool(value, false));
                case "billboard", "worldBillboard", "alwaysFaceCamera" ->
                        extraCharacters.setBillboard(tag, noteBool(value, true));
                case "lighting", "worldLighting", "affectedByLighting" ->
                        extraCharacters.setLighting(tag, noteBool(value, true));
                case "fullbright", "fullBright", "unlit", "flat", "flatShading", "flatshading" ->
                        extraCharacters.setLighting(tag, !noteBool(value, false));
                case "seethrough", "seeThrough", "worldSeeThrough", "throughWalls", "noDepth" ->
                        extraCharacters.setSeeThrough(tag, noteBool(value, false));
                case "antialiasing" -> extraCharacters.setAntialiasing(tag, noteBool(value, true));
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
        if (extraDot > 0 && isFullbrightProperty(path.substring(extraDot + 1))) {
            Entity performer = performerEntityForTag(path.substring(0, extraDot));
            if (performer instanceof net.minecraft.world.entity.player.Player player) {
                // Full-bright = form lighting 0; normal = 1.
                CharacterAnimations.setLighting(player, noteBool(value, false) ? 0f : 1f);
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
                    case "rotation.x", "rotationX", "angleX" ->
                            performer.setOffsetRotationX(noteNumber(value, performer.rotationX));
                    case "angle", "rotation", "rotation.y", "rotationY", "angleY" ->
                            performer.setOffsetRotation(noteNumber(value, performer.rotation));
                    case "rotation.z", "rotationZ", "angleZ" ->
                            performer.setOffsetRotationZ(noteNumber(value, performer.rotationZ));
                    case "scale.x", "scaleX" -> performer.setScaleX(noteNumber(value, performer.scaleX));
                    case "scale.y", "scaleY" -> performer.setScaleY(noteNumber(value, performer.scaleY));
                    case "scale.z", "scaleZ" -> performer.setScaleZ(noteNumber(value, performer.scaleZ));
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
        String extra = resolvedExtraCharacterTarget(target);
        if (extra != null && focusExtraCharacter(extra, "smooth", 500)) {
            cameraFocusOverride = extra;
            if (luaRuntime != null) luaRuntime.onMoveCamera(extra);
            return;
        }
        String role = target.equalsIgnoreCase("gf") || target.equalsIgnoreCase("girlfriend")
                || target.equalsIgnoreCase("speakers") ? "gf"
                : target.equalsIgnoreCase("dad") || target.equalsIgnoreCase("opponent")
                ? "dad" : "boyfriend";
        cameraFocusOverride = role;
        GameplayCamera.focus(!role.equals("dad"), "smooth", 500);
        if (psychScene != null) psychScene.focus(role);
        if (luaRuntime != null) luaRuntime.onMoveCamera(role);
    }

    public void psychLuaCameraOrbit(boolean pinned, double pivotX, double pivotY, double pivotZ,
                                    double durationSeconds, String easing) {
        GameplayCamera.orbit(pinned, pivotX, pivotY, pivotZ, durationSeconds, easing);
    }

    public boolean psychLuaPlayCharacterAnimation(String role, String animation, boolean force) {
        String extra = resolvedExtraCharacterTarget(role);
        if (extra != null) return extraCharacters.play(extra, animation);
        boolean psychPlayed = psychScene != null && psychScene.playAnimation(role, animation, force);
        return playMinecraftCharacterAnimation(role, animation) || psychPlayed;
    }

    public boolean psychLuaCharacterDance(String role) {
        String extra = resolvedExtraCharacterTarget(role);
        if (extra != null) return extraCharacters.dance(extra);
        return psychScene != null && psychScene.dance(role);
    }

    public double psychLuaCharacterX(String role) {
        String extra = resolvedExtraCharacterTarget(role);
        if (extra != null) return extraCharacters.x(extra);
        return psychScene == null ? 0 : psychScene.characterX(role);
    }

    public double psychLuaCharacterY(String role) {
        String extra = resolvedExtraCharacterTarget(role);
        if (extra != null) return extraCharacters.y(extra);
        return psychScene == null ? 0 : psychScene.characterY(role);
    }

    public boolean psychLuaSetCharacterX(String role, double value) {
        String extra = resolvedExtraCharacterTarget(role);
        if (extra != null) return extraCharacters.setX(extra, value);
        return psychScene != null && psychScene.setCharacterX(role, value);
    }

    public boolean psychLuaSetCharacterY(String role, double value) {
        String extra = resolvedExtraCharacterTarget(role);
        if (extra != null) return extraCharacters.setY(extra, value);
        return psychScene != null && psychScene.setCharacterY(role, value);
    }

    public boolean psychLuaExtraCharacterExists(String tag) {
        return explicitExtraCharacterTarget(tag) != null;
    }

    public boolean psychLuaAddCharacter(String tag, String definition, double x, double y, double z,
                                        double rotation, String animation, String role) {
        return extraCharacters.create(tag, definition, x, y, z, (float) rotation, animation, role);
    }

    public boolean psychLuaRemoveCharacter(String tag) {
        String extra = explicitExtraCharacterTarget(tag);
        return extra != null && extraCharacters.remove(extra);
    }

    public boolean psychLuaSetCharacterPosition(String tag, double x, double y, double z) {
        String extra = explicitExtraCharacterTarget(tag);
        return extra != null && extraCharacters.setPosition(extra, x, y, z);
    }

    public boolean psychLuaSetCharacterZ(String tag, double value) {
        String extra = explicitExtraCharacterTarget(tag);
        return extra != null && extraCharacters.setZ(extra, value);
    }

    public double psychLuaCharacterZ(String tag) {
        String extra = explicitExtraCharacterTarget(tag);
        return extra == null ? 0 : extraCharacters.z(extra);
    }

    public boolean psychLuaSetCharacterRotation(String tag, double value) {
        String extra = explicitExtraCharacterTarget(tag);
        return extra != null && extraCharacters.setRotation(extra, value);
    }

    public double psychLuaCharacterRotation(String tag) {
        String extra = explicitExtraCharacterTarget(tag);
        return extra == null ? 0 : extraCharacters.rotation(extra);
    }

    public boolean psychLuaSetCharacterVisible(String tag, boolean visible) {
        String extra = explicitExtraCharacterTarget(tag);
        return extra != null && extraCharacters.setVisible(extra, visible);
    }

    public boolean psychLuaChangeExtraCharacter(String tag, String definition, String role) {
        String extra = explicitExtraCharacterTarget(tag);
        return extra != null && extraCharacters.changeDefinition(extra, definition, role);
    }

    private record ChunkPointProperty(String tag, String property) {}

    private static ChunkPointProperty chunkPointProperty(String path) {
        String rest;
        if (path.startsWith("chunkLoadPoints.")) rest = path.substring("chunkLoadPoints.".length());
        else if (path.startsWith("chunkLoaderPoints.")) rest = path.substring("chunkLoaderPoints.".length());
        else return null;
        int dot = rest.indexOf('.');
        if (dot <= 0 || dot == rest.length() - 1) return null;
        return new ChunkPointProperty(rest.substring(0, dot), rest.substring(dot + 1));
    }

    public void psychLuaEndSong() { finishSong(false); }
    public void psychLuaRestartSong() { restart(); }
    public void psychLuaExitSong() { exit(); }

    public void reloadLuaFonts() {
        if (luaRuntime != null) luaRuntime.reloadFonts();
    }

    @Override
    public void tick() {
        super.tick();
        // Spawn/move/remove 3D-character preview performers off the render pass.
        if (freeCam) freeCamObjects.syncPerformers(machinePos);
    }

    /** Called from the level render pass so Lua world sprites have real depth and lighting. */
    public void renderLuaWorld(PoseStack poseStack, Camera camera) {
        if (minecraft.level == null) return;
        if (luaRuntime != null) {
            luaRuntime.renderWorld(poseStack, camera, machinePos, StageOrientation.facing());
        }
        // Runtime 2D characters (addBlockifiedCharacter with a Psych def) render in world space.
        extraCharacters.render2D(poseStack, camera, machinePos, StageOrientation.facing());
        // Free-cam editor objects share the same world space and renderer.
        if (freeCam) {
            freeCamObjects.render(poseStack, camera, machinePos, StageOrientation.facing());
            if (freeCamShowEntryCamera && freeCamEntryCameraPos != null) {
                FreeCamCameraMarker.render(poseStack, camera, freeCamEntryCameraPos,
                        freeCamEntryCameraLeft, freeCamEntryCameraUp, freeCamEntryCameraLook);
            }
        }
    }

    private float noteY(double timeMs, boolean mine, int lane, GameNote note) {
        double speed = note == null ? 1.0 : Math.max(0.01, note.data.multSpeed);
        double dist = (timeMs - songPos) * pxPerMs() * speed;
        float receptor = laneY(mine, lane);
        return (float) (laneDown(mine, lane) ? receptor - dist : receptor + dist);
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        updateGameplayCursor();
        logic();
        // Hide-GUI: draw only the free-cam menu; notes/HUD and the vanilla hotbar (via
        // hideGui) are gone, while the world and placed objects still render in the level pass.
        if (freeCam && freeCamHideGui) {
            renderFreeCamOverlay(gui, mouseX, mouseY);
            return;
        }
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
            float strumX = laneX(true, lane), strumY = laneY(true, lane);
            long sustainFrame = customSustainStrumFrame(true, lane);
            NoteStyle.setDrawAlpha((float) luaStrumAlpha[(myChartSideIsPlayer || playBoth ? 4 : 0) + lane]);
            boolean custom = !NoteStyle.songSkinActive() && sustainFrame >= 0
                    && customNoteTextures.drawSustainReceptor(
                    gui, chartDefaultNoteTexture(), lane, sustainFrame, strumX, strumY, noteSize);
            if (!custom && !NoteStyle.songSkinActive()) custom = customNoteTextures.drawReceptor(gui,
                    chartDefaultNoteTexture(), lane, myState, strumX, strumY, noteSize);
            if (!custom) {
                NoteStyle.drawReceptor(gui, lane, strumX, strumY, noteSize, myState);
            }
        }
        NoteStyle.setDrawAlpha(1f);
        if (!playBoth) {
            if (fadeOpponent) NoteStyle.setDrawAlpha(0.6f);
            for (int lane = 0; lane < 4; lane++) {
                int otherState = otherStrumFlash[lane] > 0 ? 2 : 0;
                float strumX = laneX(false, lane), strumY = laneY(false, lane);
                long sustainFrame = customSustainStrumFrame(false, lane);
                int side = myChartSideIsPlayer ? 0 : 4;
                NoteStyle.setDrawAlpha((float) (luaStrumAlpha[side + lane] * (fadeOpponent ? 0.6 : 1)));
                boolean custom = !NoteStyle.songSkinActive() && sustainFrame >= 0
                        && customNoteTextures.drawSustainReceptor(
                        gui, chartDefaultNoteTexture(), lane, sustainFrame, strumX, strumY, noteSize);
                if (!custom && !NoteStyle.songSkinActive()) custom = customNoteTextures.drawReceptor(gui,
                        chartDefaultNoteTexture(), lane, otherState, strumX, strumY, noteSize);
                if (!custom) {
                    NoteStyle.drawReceptor(gui, lane, strumX, strumY, noteSize, otherState);
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

        // Outside free cam this remains a playtest aid. While free cam is active,
        // renderFreeCamOverlay owns it so it follows menu visibility.
        if (editorPlaytest && !freeCam && ClientOptions.get().editorShowAxisGizmo) renderAxisGizmo(gui);
        if (freeCam) renderFreeCamOverlay(gui, mouseX, mouseY);
        // Psych debugPrint trace lines ride on top of everything, top-left.
        if (luaRuntime != null) luaRuntime.renderDebugOverlay(gui);
    }

    /** Free-camera HUD: control menu on the left, live readout on the right. */
    private void renderFreeCamOverlay(GuiGraphics gui, int mouseX, int mouseY) {
        // HUD-visible mode keeps only a faint button for restoring the editor menu.
        if (freeCamMenuHidden) {
            renderFreeCamPanel(gui, mouseX, mouseY);
            return;
        }
        // Modal help: cover the world and menu so nothing shows through it.
        if (freeCamHelp) {
            gui.fill(0, 0, width, height, 0xF00A0A12);
            renderFreeCamHelp(gui);
            return;
        }
        // Colour picker / text entry are modal but keep the world visible for live preview.
        if (freeCamColorPicker) { renderColorPicker(gui, mouseX, mouseY); return; }
        if (freeCamTextEntry) { renderTextEntry(gui, mouseX, mouseY); return; }

        // Drive a running transform from the live cursor before drawing.
        if (freeCamObjects.isTransforming()) {
            freeCamObjects.updateTransform(minecraft.gameRenderer.getMainCamera(),
                    machinePos, StageOrientation.facing(), mouseX, mouseY,
                    width / 2.0, height / 2.0,
                    minecraft.options.fov().get() * GameplayCamera.fovScale(),
                    hasShiftDown(), hasControlDown());
            wrapTransformCursor(mouseX, mouseY);
            String status = freeCamObjects.transformStatus();
            drawOutlined(gui, status, (width - font.width(status)) / 2, height / 2 + 16, 0xFFFFEE66);
        }
        // Face-snap: drag the selection's cube; it snaps to the block face under the cursor.
        if (freeCamObjects.isSnapDragging()) {
            freeCamObjects.snapToFace(raycastFromCursor(mouseX, mouseY),
                    machinePos, StageOrientation.facing());
        }

        renderFreeCamPanel(gui, mouseX, mouseY);
        if (freeCamShowReadout) renderFreeCamReadout(gui);
        if (ClientOptions.get().editorShowAxisGizmo) renderAxisGizmo(gui);

        // Bottom-left help hint and any transient status message above it.
        drawOutlined(gui, "F1 for help", 8, height - 14, 0xFFFFEE66);
        if (System.currentTimeMillis() < freeCamMessageUntil) {
            drawOutlined(gui, freeCamMessage, 8, height - 28, 0xFF66FF88);
        }
    }

    /** Live Position / Rotation / Zoom values, right-aligned against the screen edge. */
    private void renderFreeCamReadout(GuiGraphics gui) {
        int right = width - 8;
        int y = 8;
        double[] follow = freeCamFollowValues();
        drawRight(gui, "Position", right, y, 0xFF7ABF4A);
        y += 11;
        drawRight(gui, String.format(java.util.Locale.ROOT,
                "X %.2f   Y %.2f   Z %.2f", follow[0], follow[1], follow[2]), right, y, 0xFFE0E0E0);
        y += 13;
        drawRight(gui, "Rotation", right, y, 0xFF4A8FE0);
        y += 11;
        drawRight(gui, String.format(java.util.Locale.ROOT,
                "Pitch %.2f   Yaw %.2f   Roll %.2f",
                GameplayCamera.freePitch(), GameplayCamera.freeYaw(), GameplayCamera.freeRoll()),
                right, y, 0xFFE0E0E0);
        y += 13;
        drawRight(gui, "Zoom", right, y, 0xFFE0A24A);
        y += 11;
        drawRight(gui, String.format(java.util.Locale.ROOT, "%.2f", GameplayCamera.freeZoom()),
                right, y, 0xFFE0E0E0);
        if (freeCamOrbitShot) {
            double[] local = freeCamOrbitPivotValues();
            y += 13;
            drawRight(gui, "Orbit " + (freeCamOrbitPinned ? "Pinned" : "Follow"),
                    right, y, 0xFFC879FF);
            y += 11;
            drawRight(gui, String.format(java.util.Locale.ROOT,
                    "%s %.2f, %.2f, %.2f", freeCamOrbitPinned ? "Position" : "Offset",
                    local[0], local[1], local[2]),
                    right, y, 0xFFE0E0E0);
        }
        if (freeCamEntryCameraPos != null) {
            y += 13;
            drawRight(gui, "Entry camera (world)", right, y, 0xFFFFC440);
            y += 11;
            drawRight(gui, String.format(java.util.Locale.ROOT,
                    "X %.2f   Y %.2f   Z %.2f", freeCamEntryCameraPos.x,
                    freeCamEntryCameraPos.y, freeCamEntryCameraPos.z), right, y, 0xFFE0E0E0);
            y += 11;
            drawRight(gui, String.format(java.util.Locale.ROOT,
                    "Pitch %.2f   Yaw %.2f   Roll %.2f", freeCamEntryCameraPitch,
                    freeCamEntryCameraYaw, freeCamEntryCameraRoll), right, y, 0xFFE0E0E0);
        }
    }

    private int pickSqX, pickSqY, pickHueBarX, pickHueBarY, pickHueBarW;

    /** In-game colour picker (HSB square + hue bar + hex), styled like Note Colors. */
    private void renderColorPicker(GuiGraphics gui, int mouseX, int mouseY) {
        int boxW = Math.min(260, Math.max(220, width - 24)), boxH = 176;
        int x0 = (width - boxW) / 2, y0 = Math.max(8, (height - boxH) / 2);
        gui.fill(0, 0, width, height, 0xD00A0A10);
        gui.fill(x0, y0, x0 + boxW, y0 + boxH, 0xFF101018);
        gui.renderOutline(x0, y0, boxW, boxH, 0xFF6A70FF);
        String title = "border".equals(freeCamColorTarget) ? "Text border color" : "Graph color";
        gui.drawCenteredString(font, title, x0 + boxW / 2, y0 + 9, 0xFFFFFFFF);

        int sx = x0 + 12, sy = y0 + 28, squareSize = 80;
        pickSqX = sx; pickSqY = sy;
        for (int col = 0; col < squareSize; col++) {
            int c = java.awt.Color.HSBtoRGB(pickHue, col / (squareSize - 1f), 1f);
            gui.fill(sx + col, sy, sx + col + 1, sy + squareSize, 0xFF000000 | (c & 0xFFFFFF));
        }
        gui.fillGradient(sx, sy, sx + squareSize, sy + squareSize, 0x00000000, 0xFF000000);
        int cx = sx + Math.round(pickSat * (squareSize - 1));
        int cy = sy + Math.round((1 - pickBri) * (squareSize - 1));
        int cur = java.awt.Color.HSBtoRGB(pickHue, pickSat, pickBri) & 0xFFFFFF;
        gui.fill(cx - 2, cy - 2, cx + 3, cy + 3, 0xFFFFFFFF);
        gui.fill(cx - 1, cy - 1, cx + 2, cy + 2, 0xFF000000 | cur);

        int rightX = sx + squareSize + 14;
        int rightWidth = x0 + boxW - 12 - rightX;
        gui.fill(rightX, sy, rightX + rightWidth, sy + 36, 0xFF000000 | cur);
        gui.renderOutline(rightX, sy, rightWidth, 36, 0xFF6A7080);
        gui.drawString(font, "Hex", rightX, sy + 45, 0xFFBBBBBB, false);
        if (freeCamColorHexBox != null) {
            freeCamColorHexBox.setX(rightX);
            freeCamColorHexBox.setY(sy + 56);
            freeCamColorHexBox.setWidth(rightWidth);
            freeCamColorHexBox.render(gui, mouseX, mouseY, 0);
        }

        int hy = y0 + 120;
        pickHueBarX = sx; pickHueBarY = hy; pickHueBarW = boxW - 24;
        for (int col = 0; col < pickHueBarW; col++) {
            int c = java.awt.Color.HSBtoRGB(col / (pickHueBarW - 1f), 1f, 1f);
            gui.fill(sx + col, hy, sx + col + 1, hy + 10, 0xFF000000 | (c & 0xFFFFFF));
        }
        int hx = sx + (int) (pickHue * (pickHueBarW - 1));
        gui.fill(hx - 1, hy - 1, hx + 2, hy + 11, 0xFFFFFFFF);

        int buttonY = y0 + boxH - 26;
        int buttonWidth = (boxW - 28) / 2;
        colorCancelX = x0 + 10; colorCancelY = buttonY; colorCancelW = buttonWidth; colorCancelH = 18;
        colorDoneX = x0 + 18 + buttonWidth; colorDoneY = buttonY; colorDoneW = buttonWidth; colorDoneH = 18;
        boolean cancelHover = mouseX >= colorCancelX && mouseX < colorCancelX + colorCancelW
                && mouseY >= colorCancelY && mouseY < colorCancelY + colorCancelH;
        boolean doneHover = mouseX >= colorDoneX && mouseX < colorDoneX + colorDoneW
                && mouseY >= colorDoneY && mouseY < colorDoneY + colorDoneH;
        gui.fill(colorCancelX, colorCancelY, colorCancelX + colorCancelW, colorCancelY + colorCancelH,
                cancelHover ? 0xFF505675 : 0xFF303442);
        gui.renderOutline(colorCancelX, colorCancelY, colorCancelW, colorCancelH, 0xFF6A7080);
        gui.drawCenteredString(font, "Cancel", colorCancelX + colorCancelW / 2, colorCancelY + 5, 0xFFFFFFFF);
        gui.fill(colorDoneX, colorDoneY, colorDoneX + colorDoneW, colorDoneY + colorDoneH,
                doneHover ? 0xFF505675 : 0xFF303442);
        gui.renderOutline(colorDoneX, colorDoneY, colorDoneW, colorDoneH, 0xFF6A7080);
        gui.drawCenteredString(font, "Done", colorDoneX + colorDoneW / 2, colorDoneY + 5, 0xFFFFFFFF);
    }

    /** In-game text field for renaming / fps / text, drawn as a small modal. */
    private void renderTextEntry(GuiGraphics gui, int mouseX, int mouseY) {
        int boxW = 220, boxH = 56;
        int x0 = (width - boxW) / 2, y0 = (height - boxH) / 2;
        gui.fill(x0, y0, x0 + boxW, y0 + boxH, 0xF0101018);
        gui.renderOutline(x0, y0, boxW, boxH, 0xFFFFEE66);
        String label = switch (freeCamTextTarget) {
            case "name" -> "Object name"; case "fps" -> "Animation FPS";
            case "text" -> "Text";
            case "pivotx" -> "Pivot X (right / left)";
            case "pivoty" -> "Pivot Y (up / down)";
            case "pivotz" -> "Pivot Z (forward / back)";
            default -> "Edit";
        };
        gui.drawString(font, label, x0 + 10, y0 + 8, 0xFFFFEE66, false);
        if (freeCamTextBox != null) {
            freeCamTextBox.setX(x0 + 10);
            freeCamTextBox.setY(y0 + 22);
            freeCamTextBox.render(gui, mouseX, mouseY, 0f);
        }
        gui.drawString(font, "Enter = OK   ·   Esc = Cancel", x0 + 10, y0 + boxH - 11, 0xFF9AA4B2, false);
    }

    /** Right-aligns outlined text so its right edge sits at {@code right}. */
    private void drawRight(GuiGraphics gui, String text, int right, int y, int color) {
        drawOutlined(gui, text, right - font.width(text), y, color);
    }

    /** F1 help overlay: the full key/keybind list in two aligned columns, centered. */
    private void renderFreeCamHelp(GuiGraphics gui) {
        String title = "FREE CAMERA — KEYS";
        String[][] rows = {
                {"Ctrl+Shift+Space", "Exit free cam"},
                {"Hold LMB", "Fly / look (empty viewport space)"},
                {"Shift+LMB", "Start fly / look through objects; then release Shift"},
                {"MMB", "Orbit around viewport pivot"},
                {"Shift+MMB", "Pan view"},
                {"Ctrl+MMB", "Dolly in / out"},
                {"WASD", "Move   ·   E up   ·   Q down"},
                {"Mouse", "Look around (while fly/look is on)"},
                {"Left / Right", "Roll"},
                {"Up / Down", "Camera Zoom event"},
                {"R", "Reset roll + Camera Zoom"},
                {"Wheel", "Dolly; fly speed while cursor grabbed"},
                {"Shift / Ctrl", "Slower / faster"},
                {"M", "Movement: override / attached"},
                {"F", "Frame: machine / camera"},
                {"Object / Camera", "Switch free-cam menu tabs"},
                {"Camera mode", "Normal pose / pivot orbit when copied"},
                {"Orbit + Rotation", "Rotation 3D animates around the pivot"},
                {"Ctrl+C", "Copy selected object Lua; otherwise camera shot"},
                {"", ""},
                {"Click", "Select object   ·   Del: delete"},
                {"Numpad .", "Focus camera on the selected object"},
                {"Drag cube", "Snap object flat onto the block face under the cursor"},
                {"Ctrl+V", "Parse and duplicate supported object Lua"},
                {"G / R / S", "Move / Rotate / Scale selected (R twice = trackball)"},
                {"Type number", "Exact blocks / degrees / scale; Backspace edits"},
                {"Alt+G / R / S", "Reset position / rotation / scale"},
                {"X / Y / Z", "Lock axis   ·   Shift+axis: plane (all but that axis)"},
                {"Shift / Ctrl", "Slow motion / snap (1 block · 15° · 0.1)"},
                {"LMB / Enter", "Confirm transform   ·   RMB / Esc: cancel"},
                {"Ctrl+Z / Y", "Undo / redo (Ctrl+Shift+Z also redoes)"},
                {"F1", "Close this help"},
        };
        int pad = 10;
        int colGap = 16;
        int keyCol = 0;
        int descCol = 0;
        for (String[] row : rows) {
            keyCol = Math.max(keyCol, font.width(row[0]));
            descCol = Math.max(descCol, font.width(row[1]));
        }
        int content = Math.max(font.width(title), keyCol + colGap + descCol);
        int boxW = content + pad * 2;
        int boxH = (rows.length + 2) * 11 + pad * 2;
        int x0 = (width - boxW) / 2;
        int y0 = (height - boxH) / 2;
        gui.fill(x0, y0, x0 + boxW, y0 + boxH, 0xE0101018);
        gui.renderOutline(x0, y0, boxW, boxH, 0xFFFFEE66);

        int keyX = x0 + pad;
        int descX = keyX + keyCol + colGap;
        int y = y0 + pad;
        gui.drawString(font, title, keyX, y, 0xFFFFEE66, false);
        y += 22;
        for (String[] row : rows) {
            gui.drawString(font, row[0], keyX, y, 0xFFFFD24A, false);
            gui.drawString(font, row[1], descX, y, 0xFFE0E0E0, false);
            y += 11;
        }
    }

    /**
     * On-screen free-cam control menu (left side). Buttons are live only when the
     * cursor is released (move/look off), since move mode captures the mouse. The
     * full key list lives behind F1; the remaining actions stay on their keys too.
     */
    private void renderFreeCamPanel(GuiGraphics gui, int mouseX, int mouseY) {
        freeCamButtons.clear();
        boolean canClick = !freeCamMove;
        int bw = 154, bh = 16, gap = 4;
        int bx = 8;
        if (freeCamMenuHidden) {
            addFreeCamFadedBtn(gui, bx, 30, bw, bh, "Show Menu",
                    mouseX, mouseY, !freeCamMove, this::toggleFreeCamMenu);
            freeCamPanelMaxScroll = 0;
            return;
        }
        gui.drawString(font, "FREE CAM", bx, 18, 0xFFFFEE66, false);
        addFreeCamBtn(gui, bx, 30, bw, bh, "Hide Menu",
                mouseX, mouseY, canClick, this::toggleFreeCamMenu);
        int tabW = (bw - gap) / 2;
        addFreeCamBtn(gui, bx, 50, tabW, bh, freeCamCameraTab ? "Objects" : "[ Objects ]",
                mouseX, mouseY, canClick, () -> switchFreeCamPanelTab(false));
        addFreeCamBtn(gui, bx + tabW + gap, 50, bw - tabW - gap, bh,
                freeCamCameraTab ? "[ Camera ]" : "Camera",
                mouseX, mouseY, canClick, () -> switchFreeCamPanelTab(true));

        int top = 72 - freeCamPanelScroll;
        int by = top;
        gui.enableScissor(0, 70, width, height);

        if (!freeCamCameraTab) {
        // Add-object dropdown.
        addFreeCamBtn(gui, bx, by, bw, bh, (freeCamAddMenu ? "Add object  ▲" : "Add object  ▼"),
                mouseX, mouseY, canClick, () -> freeCamAddMenu = !freeCamAddMenu);
        by += bh + gap;
        if (freeCamAddMenu) {
            Direction facing = StageOrientation.facing();
            for (com.fnfmod.client.gameplay.FreeCamObjects.Type type
                    : com.fnfmod.client.gameplay.FreeCamObjects.Type.values()) {
                addFreeCamBtn(gui, bx + 10, by, bw - 10, bh, "  " + type.label,
                        mouseX, mouseY, canClick, () -> {
                            freeCamObjects.add(type, minecraft.gameRenderer.getMainCamera(),
                                    machinePos, facing);
                            freeCamAddMenu = false;
                        });
                by += bh + gap;
            }
            by += 2;
        }

        int linkedCount = freeCamObjects.linkedCount();
        addFreeCamBtn(gui, bx, by, bw, bh,
                "Existing objects (" + linkedCount + ") " + (freeCamExistingMenu ? "▲" : "▼"),
                mouseX, mouseY, canClick, () -> freeCamExistingMenu = !freeCamExistingMenu);
        by += bh + gap;
        if (freeCamExistingMenu) {
            for (FreeCamObjects.Obj linked : freeCamObjects.linkedObjects()) {
                String sourceKey = linked.sourceKey();
                String source = linked.source == FreeCamObjects.Source.LUA_RUNTIME ? "Lua" : "Character";
                addFreeCamBtn(gui, bx + 10, by, bw - 10, bh,
                        trimTo(linked.tag, 14) + " · " + source,
                        mouseX, mouseY, canClick, () -> {
                            freeCamObjects.selectLinked(sourceKey);
                            freeCamExistingMenu = false;
                        });
                by += bh + gap;
            }
            addFreeCamBtn(gui, bx + 10, by, bw - 10, bh, "Refresh list",
                    mouseX, mouseY, canClick, this::refreshExistingFreeCamObjects);
            by += bh + gap + 2;
        }
        }

        if (freeCamCameraTab) {
        addFreeCamBtn(gui, bx, by, bw, bh,
                "Movement: " + (freeCamOverride ? "Override" : "Attached"),
                mouseX, mouseY, canClick, () -> freeCamOverride = !freeCamOverride);
        by += bh + gap;
        addFreeCamBtn(gui, bx, by, bw, bh,
                "Frame: " + (freeCamCameraFrame ? "Camera" : "Machine"),
                mouseX, mouseY, canClick, () -> freeCamCameraFrame = !freeCamCameraFrame);
        by += bh + gap;

        addFreeCamBtn(gui, bx, by, bw, bh,
                "Camera mode: " + (freeCamOrbitShot ? "Orbit" : "Normal"),
                mouseX, mouseY, canClick, this::toggleFreeCamShotMode);
        by += bh + gap;
        if (freeCamOrbitShot) {
            double[] pivot = freeCamOrbitPivotValues();
            addFreeCamBtn(gui, bx, by, bw, bh,
                    "Pivot: " + (freeCamOrbitPinned ? "Pinned" : "Follow focus"),
                    mouseX, mouseY, canClick, this::toggleFreeCamOrbitPinned);
            by += bh + gap;
            addFreeCamBtn(gui, bx, by, bw, bh,
                    "Pivot X: " + num(round2(pivot[0])),
                    mouseX, mouseY, canClick,
                    () -> beginTextEntry("pivotx", num(freeCamOrbitPivotValues()[0])));
            by += bh + gap;
            addFreeCamBtn(gui, bx, by, bw, bh,
                    "Pivot Y: " + num(round2(pivot[1])),
                    mouseX, mouseY, canClick,
                    () -> beginTextEntry("pivoty", num(freeCamOrbitPivotValues()[1])));
            by += bh + gap;
            addFreeCamBtn(gui, bx, by, bw, bh,
                    "Pivot Z: " + num(round2(pivot[2])),
                    mouseX, mouseY, canClick,
                    () -> beginTextEntry("pivotz", num(freeCamOrbitPivotValues()[2])));
            by += bh + gap;
        }

        gui.drawString(font, String.format(java.util.Locale.ROOT, "Fly speed %.1f (grabbed wheel)", freeCamSpeed),
                bx, by + 4, 0xFFBFC7D5, false);
        by += 20;

        addFreeCamBtn(gui, bx, by, bw, bh, "Readout: " + (freeCamShowReadout ? "On" : "Off"),
                mouseX, mouseY, true, () -> freeCamShowReadout = !freeCamShowReadout);
        by += bh + gap;
        addFreeCamBtn(gui, bx, by, bw, bh,
                "Entry camera: " + (freeCamShowEntryCamera ? "Visible" : "Hidden"),
                mouseX, mouseY, true, () -> freeCamShowEntryCamera = !freeCamShowEntryCamera);
        by += bh + gap;
        }

        if (!freeCamCameraTab) {
        // Selected-object row with a Delete button.
        gui.drawString(font, "Selected: " + freeCamObjects.selectionLabel(), bx, by, 0xFFBFC7D5, false);
        by += 12;
        if (freeCamObjects.hasSelection()) {
            FreeCamObjects.Obj sel = freeCamObjects.selected();
            String assetLabel = switch (sel.type) {
                case SPRITE, SPRITESHEET -> "Pick image...";
                case GRAPH -> "Pick colour...";
                case CHARACTER_2D, CHARACTER_3D -> "Pick character...";
                case TEXT -> "Edit text...";
            };
            addFreeCamBtn(gui, bx, by, bw, bh, assetLabel, mouseX, mouseY, canClick, this::openAssetPicker);
            by += bh + gap;
            String val = switch (sel.type) {
                case TEXT -> "\"" + sel.text + "\"";
                case GRAPH -> String.format("#%06X", sel.color & 0xFFFFFF);
                case CHARACTER_2D, CHARACTER_3D -> sel.characterDef.isBlank() ? "(no character)" : sel.characterDef;
                default -> sel.texturePath.isBlank() ? "(no image)" : sel.texturePath;
            };
            gui.drawString(font, trimTo(val, 26), bx, by, 0xFF9AA4B2, false);
            by += 12;

            // Runtime tags stay stable so song Lua continues to target the same object.
            addFreeCamBtn(gui, bx, by, bw, bh,
                    (sel.linked() ? "Runtime name: " : "Name: ") + trimTo(sel.tag, 17),
                    mouseX, mouseY, canClick && !sel.linked(), this::renameSelectedObject);
            by += bh + gap;

            // Text styling: border/outline, alignment, italic.
            if (sel.type == FreeCamObjects.Type.TEXT) {
                addFreeCamBtn(gui, bx, by, bw, bh, "Border: " + sel.borderStyle,
                        mouseX, mouseY, canClick, freeCamObjects::cycleBorderStyle);
                by += bh + gap;
                if (!"none".equals(sel.borderStyle)) {
                    int half = (bw - gap) / 2;
                    addFreeCamBtn(gui, bx, by, half, bh,
                            "Size " + String.format(java.util.Locale.ROOT, "%.0f", sel.borderSize),
                            mouseX, mouseY, canClick,
                            () -> beginTextEntry("bordersize", String.format(java.util.Locale.ROOT, "%.0f", sel.borderSize)));
                    addFreeCamBtn(gui, bx + half + gap, by, bw - half - gap, bh, "Colour",
                            mouseX, mouseY, canClick, () -> beginColorPicker("border", sel.borderColor));
                    by += bh + gap;
                }
                addFreeCamBtn(gui, bx, by, bw, bh, "Align: " + sel.textAlign,
                        mouseX, mouseY, canClick, freeCamObjects::cycleTextAlign);
                by += bh + gap;
                addFreeCamBtn(gui, bx, by, bw, bh, "Italic: " + (sel.italic ? "On" : "Off"),
                        mouseX, mouseY, canClick, freeCamObjects::toggleItalic);
                by += bh + gap;
            }

            // Animated-sprite playback (the addAnimationByPrefix fps + loop).
            if (sel.type == FreeCamObjects.Type.SPRITESHEET) {
                addFreeCamBtn(gui, bx, by, bw, bh, "FPS: " + sel.fps,
                        mouseX, mouseY, canClick, this::editSelectedFps);
                by += bh + gap;
                addFreeCamBtn(gui, bx, by, bw, bh, "Loop: " + (sel.loop ? "On" : "Off"),
                        mouseX, mouseY, canClick, freeCamObjects::toggleLoop);
                by += bh + gap;
            }

            // 2D-character animation player (names come from the character JSON).
            java.util.List<String> animNames = freeCamObjects.characterAnimations();
            if (!animNames.isEmpty()) {
                addFreeCamBtn(gui, bx, by, bw, bh,
                        "Anim: " + trimTo(freeCamObjects.characterCurrentAnim(), 14)
                                + (freeCamAnimMenu ? "  ▲" : "  ▼"),
                        mouseX, mouseY, canClick, () -> freeCamAnimMenu = !freeCamAnimMenu);
                by += bh + gap;
                if (freeCamAnimMenu) {
                    for (String an : animNames) {
                        String name = an;
                        addFreeCamBtn(gui, bx + 10, by, bw - 10, bh, "  " + trimTo(name, 22),
                                mouseX, mouseY, canClick, () -> freeCamObjects.playCharacterAnim(name));
                        by += bh + gap;
                    }
                }
            }

            // Per-object world modifiers.
            addFreeCamBtn(gui, bx, by, bw, bh, freeCamModMenu ? "Modifiers  ▲" : "Modifiers  ▼",
                    mouseX, mouseY, canClick, () -> freeCamModMenu = !freeCamModMenu);
            by += bh + gap;
            if (freeCamModMenu) {
                addFreeCamBtn(gui, bx + 10, by, bw - 10, bh, checkLabel("Billboard", sel.billboard),
                        mouseX, mouseY, canClick, freeCamObjects::toggleBillboard);
                by += bh + gap;
                addFreeCamBtn(gui, bx + 10, by, bw - 10, bh, checkLabel("Lighting / shadows", sel.lighting),
                        mouseX, mouseY, canClick, freeCamObjects::toggleLighting);
                by += bh + gap;
                addFreeCamBtn(gui, bx + 10, by, bw - 10, bh, checkLabel("See-through", sel.seeThrough),
                        mouseX, mouseY, canClick, freeCamObjects::toggleSeeThrough);
                by += bh + gap;
                addFreeCamBtn(gui, bx + 10, by, bw - 10, bh, checkLabel("Antialiasing", sel.antialiasing),
                        mouseX, mouseY, canClick, freeCamObjects::toggleAntialiasing);
                by += bh + gap;
            }

            addFreeCamBtn(gui, bx, by, bw, bh,
                    sel.linked() ? "Stop editing (Del)" : "Delete object (Del)",
                    mouseX, mouseY, canClick, freeCamObjects::deleteSelected);
            by += bh + gap;

        }
        }

        // Extra gap separating Exit from the rest.
        by += 10;
        addFreeCamBtn(gui, bx, by, bw, bh, "Exit free cam",
                mouseX, mouseY, true, this::exitFreeCam);
        gui.disableScissor();
        // Content is clipped below the fixed tabs. Do not leave clipped rows
        // clickable through the header or outside the screen.
        freeCamButtons.removeIf(button -> (button.y() < 70
                && button.y() != 30 && button.y() != 50) || button.y() >= height);

        // General scrolling: if the panel is taller than the screen, let the wheel scroll it.
        int contentHeight = (by + bh) - top;
        int visibleHeight = height - 72 - 8;
        freeCamPanelMaxScroll = Math.max(0, contentHeight - visibleHeight);
        freeCamPanelScroll = Math.max(0, Math.min(freeCamPanelScroll, freeCamPanelMaxScroll));
        if (freeCamPanelMaxScroll > 0) {
            drawOutlined(gui, "scroll ↕", bx + bw - font.width("scroll ↕"), height - 14, 0xFF888F9C);
        }
    }

    private static String checkLabel(String name, boolean on) {
        return (on ? "[x] " : "[ ] ") + name;
    }

    private static String trimTo(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /** Starts with editor menu visible and every gameplay HUD layer hidden. */
    private void beginFreeCamPresentation() {
        savedHideGui = minecraft.options.hideGui;
        freeCamHudOverrideActive = true;
        freeCamMenuHidden = false;
        freeCamHideGui = true;
        minecraft.options.hideGui = true;
    }

    /** Switches exclusively between editor menu/no HUD and HUD/no editor menu. */
    private void toggleFreeCamMenu() {
        freeCamMenuHidden = !freeCamMenuHidden;
        freeCamHideGui = !freeCamMenuHidden;
        minecraft.options.hideGui = freeCamHideGui;
        freeCamHelp = false;
        freeCamPanelScroll = 0;
    }

    private void switchFreeCamPanelTab(boolean cameraTab) {
        if (freeCamCameraTab == cameraTab) return;
        freeCamCameraTab = cameraTab;
        freeCamPanelScroll = 0;
        freeCamAddMenu = false;
        freeCamExistingMenu = false;
        freeCamModMenu = false;
        freeCamAnimMenu = false;
    }

    private void restoreHideGui() {
        if (freeCamHudOverrideActive) {
            minecraft.options.hideGui = savedHideGui;
            freeCamHudOverrideActive = false;
        }
        freeCamHideGui = false;
        freeCamMenuHidden = false;
    }

    private void renameSelectedObject() {
        FreeCamObjects.Obj o = freeCamObjects.selected();
        if (o != null) beginTextEntry("name", o.tag);
    }

    private void editSelectedFps() {
        FreeCamObjects.Obj o = freeCamObjects.selected();
        if (o != null) beginTextEntry("fps", String.valueOf(o.fps));
    }

    private void beginTextEntry(String target, String initial) {
        freeCamTextEntry = true;
        freeCamTextTarget = target;
        int boxW = 220, fw = boxW - 20;
        int x0 = (width - boxW) / 2, y0 = (height - 56) / 2;
        // A real EditBox gives all the standard text controls (word nav, selection,
        // Ctrl+Backspace, clipboard) for free.
        freeCamTextBox = new net.minecraft.client.gui.components.EditBox(
                font, x0 + 10, y0 + 22, fw, 16, Component.literal(target));
        freeCamTextBox.setMaxLength(256);
        freeCamTextBox.setValue(initial == null ? "" : initial);
        freeCamTextBox.setFocused(true);
        int end = freeCamTextBox.getValue().length();
        freeCamTextBox.setCursorPosition(end);
        freeCamTextBox.setHighlightPos(end);
    }

    private void commitTextEntry() {
        String raw = freeCamTextBox == null ? "" : freeCamTextBox.getValue();
        if ("name".equals(freeCamTextTarget)) {
            if (!raw.trim().isEmpty()) freeCamObjects.setObjectName(raw.trim());
        } else if ("fps".equals(freeCamTextTarget)) {
            try { freeCamObjects.setFps(Integer.parseInt(raw.trim())); } catch (NumberFormatException ignored) { }
        } else if ("text".equals(freeCamTextTarget)) {
            freeCamObjects.setText(raw);
        } else if ("bordersize".equals(freeCamTextTarget)) {
            try { freeCamObjects.setBorderSize(Double.parseDouble(raw.trim())); } catch (NumberFormatException ignored) { }
        } else if (freeCamTextTarget.startsWith("pivot")) {
            try {
                double value = Double.parseDouble(raw.trim());
                int axis = freeCamTextTarget.equals("pivotx") ? 0
                        : freeCamTextTarget.equals("pivoty") ? 1 : 2;
                setFreeCamOrbitPivotValue(axis, value);
            } catch (NumberFormatException ignored) { }
        }
        freeCamTextEntry = false;
        freeCamTextBox = null;
    }

    /** Blender-style "view selected": move the camera so the object centres in the view. */
    private void focusSelectedObject() {
        if (!freeCamObjects.hasSelection()) return;
        Camera camera = minecraft.gameRenderer.getMainCamera();
        Vec3 obj = freeCamObjects.selectedWorldOrigin(machinePos, StageOrientation.facing());
        if (obj == null) return;
        Vec3 look = new Vec3(camera.getLookVector()).normalize();
        // Distance so the object fills a comfortable share of the view for its size.
        double half = freeCamObjects.selectedSizeBlocks() * 0.5;
        double fov = Math.toRadians(Math.max(30, minecraft.options.fov().get()));
        double dist = Math.max(2.5, Math.min(40.0, half / Math.tan(fov / 2.0) / 0.6));
        Vec3 targetCam = obj.subtract(look.scale(dist));   // sit back along the current view
        freeCamFocusFrom = new double[]{GameplayCamera.freeX(), GameplayCamera.freeY(), GameplayCamera.freeZ()};
        freeCamFocusTo = GameplayCamera.worldToFreeOffset(targetCam);
        freeCamFocusStart = System.nanoTime();
        freeCamFocusDur = 300_000_000L;   // 0.3s
        freeCamFocusPoint = obj;
        freeCamFocusing = true;
    }

    /** Starts Blender-style MMB navigation around the existing focus or view centre. */
    private void beginFreeCamViewportDrag() {
        Camera camera = minecraft.gameRenderer.getMainCamera();
        freeCamFocusing = false;
        freeCamViewportPivot = viewportPivot(camera);
        freeCamFocusPoint = freeCamViewportPivot;
        freeCamViewportIgnoreWarpMotion = false;
        freeCamViewportDragging = true;
    }

    /** MMB orbits, Shift+MMB pans, and Ctrl+MMB dollies. */
    private void updateFreeCamViewportDrag(double dragX, double dragY) {
        if (freeCamViewportPivot == null) return;
        long window = minecraft.getWindow().getWindow();
        boolean shift = keyDown(window, GLFW.GLFW_KEY_LEFT_SHIFT) == 1
                || keyDown(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == 1;
        boolean ctrl = keyDown(window, GLFW.GLFW_KEY_LEFT_CONTROL) == 1
                || keyDown(window, GLFW.GLFW_KEY_RIGHT_CONTROL) == 1;
        Camera camera = minecraft.gameRenderer.getMainCamera();

        if (shift) {
            Vec3 right = new Vec3(camera.getLeftVector()).scale(-1).normalize();
            Vec3 up = new Vec3(camera.getUpVector()).normalize();
            double distance = Math.max(0.1, camera.getPosition().distanceTo(freeCamViewportPivot));
            double worldPerPixel = 2.0 * distance
                    * Math.tan(Math.toRadians(minecraft.options.fov().get()) * 0.5)
                    / Math.max(1, height);
            Vec3 delta = right.scale(-dragX * worldPerPixel).add(up.scale(dragY * worldPerPixel));
            setFreeCamWorldPosition(camera.getPosition().add(delta));
            freeCamViewportPivot = freeCamViewportPivot.add(delta);
            freeCamFocusPoint = freeCamViewportPivot;
        } else if (ctrl) {
            dollyFreeCamAround(freeCamViewportPivot, -dragY * 0.06);
        } else {
            Vec3 offset = camera.getPosition().subtract(freeCamViewportPivot);
            if (offset.lengthSqr() < 1.0e-8) return;
            // Blender-style drag direction, at 1.5x the original orbit sensitivity.
            double yawDegrees = dragX * 0.375;
            double pitchDegrees = Mth.clamp(camera.getXRot() - (float) (dragY * 0.375), -89.0f, 89.0f)
                    - camera.getXRot();
            Vec3 yawed = rotateAroundAxis(offset, new Vec3(0, 1, 0), Math.toRadians(-yawDegrees));
            Vec3 right = new Vec3(camera.getLeftVector()).scale(-1).normalize();
            right = rotateAroundAxis(right, new Vec3(0, 1, 0), Math.toRadians(-yawDegrees));
            Vec3 orbited = rotateAroundAxis(yawed, right, Math.toRadians(pitchDegrees));
            setFreeCamWorldPosition(freeCamViewportPivot.add(orbited));
            GameplayCamera.aimFreeCamAt(freeCamViewportPivot, camera.getYRot(), camera.getXRot());
        }
        clampFreeCamToLoadedWorld();
    }

    /** Wheel dolly uses exponential distance, matching Blender viewport zoom feel. */
    private void dollyFreeCam(double wheelSteps) {
        if (wheelSteps == 0) return;
        Camera camera = minecraft.gameRenderer.getMainCamera();
        Vec3 pivot = viewportPivot(camera);
        freeCamFocusPoint = pivot;
        dollyFreeCamAround(pivot, wheelSteps);
        clampFreeCamToLoadedWorld();
    }

    private void dollyFreeCamAround(Vec3 pivot, double steps) {
        Vec3 cameraPos = GameplayCamera.freeCamWorldPos();
        Vec3 offset = cameraPos.subtract(pivot);
        double distance = offset.length();
        if (distance < 1.0e-6) return;
        double newDistance = Mth.clamp(distance * Math.pow(0.82, steps), 0.10, 512.0);
        setFreeCamWorldPosition(pivot.add(offset.scale(newDistance / distance)));
    }

    private Vec3 viewportPivot(Camera camera) {
        if (freeCamFocusPoint != null) return freeCamFocusPoint;
        return camera.getPosition().add(new Vec3(camera.getLookVector()).normalize().scale(6.0));
    }

    private static Vec3 rotateAroundAxis(Vec3 vector, Vec3 axis, double radians) {
        Vec3 unit = axis.normalize();
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return vector.scale(cos)
                .add(unit.cross(vector).scale(sin))
                .add(unit.scale(unit.dot(vector) * (1.0 - cos)));
    }

    private static void setFreeCamWorldPosition(Vec3 worldPosition) {
        double[] offset = GameplayCamera.worldToFreeOffset(worldPosition);
        GameplayCamera.setFreeCamOffset(offset[0], offset[1], offset[2]);
    }

    /** Edge-wraps an active MMB navigation drag without feeding warp distance into it. */
    private void wrapFreeCamViewportCursor(double mouseX, double mouseY) {
        int margin = 2;
        double nx = mouseX, ny = mouseY;
        if (mouseX <= margin) nx = width - margin - 2;
        else if (mouseX >= width - margin) nx = margin + 2;
        if (mouseY <= margin) ny = height - margin - 2;
        else if (mouseY >= height - margin) ny = margin + 2;
        if (nx == mouseX && ny == mouseY) return;

        freeCamViewportIgnoreWarpMotion = true;
        double guiScale = minecraft.getWindow().getGuiScale();
        GLFW.glfwSetCursorPos(minecraft.getWindow().getWindow(), nx * guiScale, ny * guiScale);
    }

    /** Blender-style edge wrap: warp the cursor to the opposite side while transforming,
     *  shifting the transform origin so the object keeps moving without a jump. */
    private void wrapTransformCursor(int mouseX, int mouseY) {
        int margin = 2;
        double nx = mouseX, ny = mouseY;
        if (mouseX <= margin) nx = width - margin - 2;
        else if (mouseX >= width - margin) nx = margin + 2;
        if (mouseY <= margin) ny = height - margin - 2;
        else if (mouseY >= height - margin) ny = margin + 2;
        if (nx == mouseX && ny == mouseY) return;
        freeCamObjects.shiftTransformStart(nx - mouseX, ny - mouseY);
        double gs = minecraft.getWindow().getGuiScale();
        GLFW.glfwSetCursorPos(minecraft.getWindow().getWindow(), nx * gs, ny * gs);
    }

    private void updateFreeCamFocus() {
        if (!freeCamFocusing) return;
        double t = Math.min(1.0, (System.nanoTime() - freeCamFocusStart) / (double) freeCamFocusDur);
        double f = com.fnfmod.client.math.Easing.apply("expoOut", t);
        GameplayCamera.setFreeCamOffset(
                freeCamFocusFrom[0] + (freeCamFocusTo[0] - freeCamFocusFrom[0]) * f,
                freeCamFocusFrom[1] + (freeCamFocusTo[1] - freeCamFocusFrom[1]) * f,
                freeCamFocusFrom[2] + (freeCamFocusTo[2] - freeCamFocusFrom[2]) * f);
        if (t >= 1.0) {
            // Clamping can move the camera off its original approach line. Aim
            // again from the final legal position so the origin square stays centred.
            clampFreeCamToLoadedWorld();
            Camera camera = minecraft.gameRenderer.getMainCamera();
            GameplayCamera.aimFreeCamAt(freeCamFocusPoint, camera.getYRot(), camera.getXRot());
            freeCamFocusing = false;
        }
    }

    private void beginColorPicker(String target, int initialColor) {
        freeCamColorTarget = target;
        int c = initialColor & 0xFFFFFF;
        freeCamColorOriginal = c;
        float[] hsb = java.awt.Color.RGBtoHSB((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF, null);
        pickHue = hsb[0]; pickSat = hsb[1]; pickBri = hsb[2];
        pickDrag = 0;
        freeCamColorHexBox = new net.minecraft.client.gui.components.EditBox(
                font, 0, 0, 80, 16, Component.literal("Hex color"));
        freeCamColorHexBox.setMaxLength(6);
        freeCamColorHexBox.setFilter(value -> value.matches("[0-9a-fA-F]{0,6}"));
        freeCamColorHexBox.setResponder(value -> {
            if (updatingFreeCamColorHex || !value.matches("[0-9a-fA-F]{6}")) return;
            int rgb = Integer.parseInt(value, 16);
            float[] next = java.awt.Color.RGBtoHSB((rgb >> 16) & 255,
                    (rgb >> 8) & 255, rgb & 255, null);
            pickHue = next[0]; pickSat = next[1]; pickBri = next[2];
            applyPickedColor();
        });
        updateFreeCamColorHex();
        freeCamObjects.beginColorEdit();
        freeCamColorPicker = true;
        setFocused(freeCamColorHexBox);
        freeCamColorHexBox.setFocused(true);
        // The field opens already full (six of six characters). Selecting it
        // lets typing immediately replace the value instead of appearing broken.
        freeCamColorHexBox.setCursorPosition(6);
        freeCamColorHexBox.setHighlightPos(0);
    }

    private void updateFreeCamColorHex() {
        if (freeCamColorHexBox == null) return;
        int rgb = java.awt.Color.HSBtoRGB(pickHue, pickSat, pickBri) & 0xFFFFFF;
        updatingFreeCamColorHex = true;
        freeCamColorHexBox.setValue(String.format(java.util.Locale.ROOT, "%06X", rgb));
        updatingFreeCamColorHex = false;
    }

    private void closeFreeCamColorPicker(boolean save) {
        if (!save) {
            if ("border".equals(freeCamColorTarget)) freeCamObjects.setBorderColorLive(freeCamColorOriginal);
            else freeCamObjects.setColorLive(freeCamColorOriginal);
        }
        freeCamColorPicker = false;
        pickDrag = 0;
        if (getFocused() == freeCamColorHexBox) setFocused(null);
        if (freeCamColorHexBox != null) freeCamColorHexBox.setFocused(false);
        freeCamColorHexBox = null;
    }

    private void applyPickedColor() {
        int rgb = java.awt.Color.HSBtoRGB(pickHue, pickSat, pickBri) & 0xFFFFFF;
        if ("border".equals(freeCamColorTarget)) freeCamObjects.setBorderColorLive(rgb);
        else freeCamObjects.setColorLive(rgb);
    }

    /** Opens the native OS picker matching the selected object's type. */
    private void openAssetPicker() {
        FreeCamObjects.Obj o = freeCamObjects.selected();
        if (o == null) return;
        switch (o.type) {
            case SPRITE, SPRITESHEET -> NativeFilePicker.openFile(
                    "Pick image", new String[]{"*.png"}, "PNG image")
                    .ifPresent(freeCamObjects::setSpriteImage);
            case GRAPH -> beginColorPicker("graph", o.color);
            case CHARACTER_2D, CHARACTER_3D -> NativeFilePicker.openFile(
                    "Pick character JSON", new String[]{"*.json"}, "Character definition")
                    .ifPresent(freeCamObjects::setCharacterDefFile);
            case TEXT -> beginTextEntry("text", o.text);
        }
    }

    /** Copies complete selected-object Lua; all transform data is always included. */
    private void copySelectedLua() {
        String lua = freeCamObjects.toLua(true, true, true);
        if (lua.isBlank()) return;
        minecraft.keyboardHandler.setClipboard(lua);
        freeCamMessage = "Copied Lua for " + freeCamObjects.selectionLabel();
        freeCamMessageUntil = System.currentTimeMillis() + 3000;
    }

    private void resetSelectedTransform(String label, java.util.function.BooleanSupplier reset) {
        boolean changed = reset.getAsBoolean();
        freeCamMessage = changed ? label + " reset" : freeCamObjects.hasSelection()
                ? label + " already at default" : "No object selected";
        freeCamMessageUntil = System.currentTimeMillis() + 2000;
    }

    /** Recreates an editor object from supported Lua, gated by its first-line comment. */
    private void pasteFreeCamObject() {
        String clipboard = minecraft.keyboardHandler.getClipboard();
        boolean marked = FreeCamObjects.hasPasteHeader(clipboard);
        boolean pasted = freeCamObjects.pasteLua(clipboard,
                path -> assetResolver == null ? null : assetResolver.image(path),
                definition -> assetResolver == null ? null : assetResolver.character(definition));
        freeCamMessage = pasted ? "Pasted " + freeCamObjects.selectionLabel()
                : marked ? "Invalid or unsupported object Lua"
                : "Paste ignored: missing world-camera object comment";
        freeCamMessageUntil = System.currentTimeMillis() + 3000;
    }

    /** Draws one panel button and, when enabled, registers its click hitbox. */
    private void addFreeCamBtn(GuiGraphics gui, int x, int y, int w, int h, String label,
                               int mouseX, int mouseY, boolean enabled, Runnable action) {
        // Cull buttons scrolled outside the visible area (below the title, above the bottom).
        if (y + h < 24 || y > height - 2) return;
        boolean hover = enabled && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        int bg = !enabled ? 0x99202024 : hover ? 0xF0505A6E : 0xE0303442;
        gui.fill(x, y, x + w, y + h, bg);
        gui.renderOutline(x, y, w, h, hover ? 0xFFFFEE66 : 0xFF6A7080);
        gui.drawCenteredString(font, label, x + w / 2, y + (h - 8) / 2,
                enabled ? 0xFFFFFFFF : 0xFF808080);
        if (enabled) freeCamButtons.add(new FreeCamButton(x, y, w, h, action));
    }

    /** Persistent menu-restoration button rendered at exactly 30% opacity. */
    private void addFreeCamFadedBtn(GuiGraphics gui, int x, int y, int w, int h, String label,
                                    int mouseX, int mouseY, boolean enabled, Runnable action) {
        gui.fill(x, y, x + w, y + h, 0x4D303442);
        gui.renderOutline(x, y, w, h, 0x4D6A7080);
        gui.drawCenteredString(font, label, x + w / 2, y + (h - 8) / 2, 0x4DFFFFFF);
        if (enabled) freeCamButtons.add(new FreeCamButton(x, y, w, h, action));
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

    /**
     * Lazily gives the solo opponent bot a real BBS character (a client-only RemotePlayer
     * that follows the server armor stand and hides it). Falls back to the plain stand when
     * the character has no BBS form. No-op in duet or when there is no bot stand.
     * BOTH still uses its combined gameplay layout, but keeps this ordinary
     * opponent performer so Dad's existing note/hold/idle animation path works.
     */
    private void updateOpponentBot() {
        if (duet || botEntityId < 0 || opponentBotSet == null
                || !(minecraft.level instanceof net.minecraft.client.multiplayer.ClientLevel level)) return;
        net.minecraft.world.entity.Entity stand = level.getEntity(botEntityId);
        if (stand == null) return;
        if (opponentBot == null) {
            opponentBot = com.fnfmod.client.gameplay.OpponentBotCharacter.create(
                    level, opponentBotSet, opponentBotRole, stand,
                    CharacterAnimations.configuredSkin(true));
            if (opponentBot != null) applyPerformerObjectBorder(false);
        }
        if (opponentBot != null) opponentBot.follow(stand);
    }

    /**
     * Resolves the song's arrowSkin into NoteStyle so the "default" skin renders it with
     * RGB colours and proper sustains. Called whenever the note-texture cache is (re)built.
     */
    private void applySongNoteSkin() {
        String tex = chart.noteTexture == null ? "" : chart.noteTexture.trim();
        java.nio.file.Path[] files = tex.isEmpty() ? null : customNoteTextures.resolveSkinFiles(tex);
        if (files != null) NoteStyle.useSongSkin(files[0], files[1], files[2]);
        else NoteStyle.useSongSkin(null, null, null);
    }

    /**
     * Whether a note should draw through the raw custom-texture path instead of NoteStyle.
     * Once the song's arrowSkin is loaded into NoteStyle, only a genuine per-note texture
     * override (different from the chart default) still uses the custom path.
     */
    private boolean useCustomNoteTexture(String noteTexture) {
        if (!NoteStyle.songSkinActive()) return true;
        String def = chart.noteTexture == null ? "" : chart.noteTexture.trim();
        String tex = noteTexture == null ? "" : noteTexture.trim();
        return !tex.isEmpty() && !tex.equalsIgnoreCase(def);
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
                    boolean custom = useCustomNoteTexture(n.data.texture)
                            && customNoteTextures.drawHold(gui, n.data.texture, lane, drawX,
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
                        if (!useCustomNoteTexture(n.data.texture)
                                || !customNoteTextures.drawNote(gui, n.data.texture, lane, 0, 0, noteSize)) {
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

        float frac = Math.min(1f, Math.max(0f, (float) (psychLuaHealth() / 2.0)));
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

        // healthBarBG is the black outline/backing of healthBar. Keep its own
        // script controls, but never let it remain visible or more opaque than
        // the bar it belongs to.
        double effectiveBackgroundAlpha = Math.min(fnfHud.backgroundAlpha, fnfHud.barAlpha);
        if (fnfHud.barVisible && fnfHud.backgroundVisible && effectiveBackgroundAlpha > 0) {
            int bx = (int) Math.round(fnfHud.backgroundX);
            int by = (int) Math.round(fnfHud.backgroundY);
            gui.fill(bx, by, bx + Math.max(1, (int) Math.round(fnfHud.backgroundWidth)),
                    by + Math.max(1, (int) Math.round(fnfHud.backgroundHeight)),
                    argb(0x000000, effectiveBackgroundAlpha));
        }
        if (fnfHud.barVisible && fnfHud.barAlpha > 0) {
            gui.fill(barX, barY, split, barY + barH, argb(oppColor, fnfHud.barAlpha));
            gui.fill(split, barY, barX + barW, barY + barH, argb(plColor, fnfHud.barAlpha));
        }

        // Health icons bop on the written meter pulse (eighths in 6/8,
        // half-notes in 2/2) instead of assuming every beat is a quarter note.
        float beatFrac = (float) currentMeter().pulseProgress;
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
        float baseX = ratingPopupLayoutX();
        float baseY = ratingPopupLayoutY();
        long now = System.currentTimeMillis();
        popups.removeIf(p -> now - p.bornMs > 700);
        // A script drawing its own rating can hide Blockified's popups.
        if (!showRatingPopups) return;
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

    private float ratingPopupLayoutX() {
        int layoutWidth = hudLayoutWidth();
        var opts = ClientOptions.get();
        return opts.ratingX >= 0 ? (float) (opts.ratingX * layoutWidth)
                : (playBoth || opts.middlescroll ? layoutWidth * 0.75f
                : myStrumsCenterX() * layoutWidth / HUD_WIDTH);
    }

    private float ratingPopupLayoutY() {
        int layoutHeight = hudLayoutHeight();
        var opts = ClientOptions.get();
        return opts.ratingY >= 0 ? (float) (opts.ratingY * layoutHeight)
                : layoutHeight * 0.4f;
    }

    private double ratingPopupCanvasX() {
        double x = ratingPopupLayoutX();
        return "fnf".equals(effectiveHudStyle()) ? x : screenToCanvasX(x);
    }

    private double ratingPopupCanvasY() {
        double y = ratingPopupLayoutY();
        return "fnf".equals(effectiveHudStyle()) ? y : screenToCanvasY(y);
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
        if (showTimeBar && songPlayer.isStarted() && songPlayer.durationMs() > 0) {
            float frac = (float) Math.min(1, Math.max(0, songPos / songPlayer.durationMs()));
            gui.fill(0, progY, (int) (layoutWidth * frac), progY + 2, 0xFFDD44AA);
        }
        if (showTimeText) gui.drawCenteredString(font, chart.title, layoutWidth / 2, titleY, 0x99FFFFFF);
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
    public boolean isTextInputActive() {
        return freeCam && (freeCamTextEntry
                || freeCamColorPicker && freeCamColorHexBox != null && freeCamColorHexBox.isFocused());
    }

    @Override
    public void removed() {
        if (minecraft != null) gameplayCursorFade.restore(minecraft.getWindow().getWindow());
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
        com.fnfmod.client.render.ObjectBorderRegistry.clearAll();
        com.fnfmod.client.render.DirectionalShadingControl.restore();
        RenderDistanceControl.restore();
        FieldOfViewControl.restore();
        StageChunkLoader.unload();
        StageOrientation.clear();
        WarningFlag.clear();
        if (rawInput != null) { rawInput.stop(); rawInput = null; }
        if (luaRuntime != null) luaRuntime.close();
        PerformerRotation.clear(performerVisualPlayer(true));
        PerformerRotation.clear(performerVisualPlayer(false));
        extraCharacters.close();
        if (opponentBot != null) {
            opponentBot.remove(minecraft.level == null ? null : minecraft.level.getEntity(botEntityId));
            opponentBot = null;
        }
        customNoteTextures.close();
        // Release the song's arrowSkin from the note pipeline so menus/other songs reset.
        NoteStyle.useSongSkin(null, null, null);
        if (psychScene != null) psychScene.close();
        if (freeCamMove) setFreeCamMove(false);
        gameplayCursorFade.close();
        freeCam = false;
        restoreHideGui();
        freeCamObjects.clear();
        GameplayCamera.endFreeCam();
        GameplayCamera.end();
        if (disposeAudio) songPlayer.dispose();
        restoreVanillaMusic();
    }

    /** Active presentation mode may override the user's normal HUD preference. */
    public String effectiveHudStyle() {
        if (playbackPolicy != null && playbackPolicy.forcesFnfHud()) return "fnf";
        if (hudStyleOverride != null) return hudStyleOverride;
        return ClientOptions.effectiveHudStyle();
    }

    // ----------------------------------------------------------- Lua HUD control

    private static final java.util.Set<String> HUD_STYLES = java.util.Set.of(
            "fnf", "vanilla", "default", "abbreviated", "numbers", "none");

    public void psychLuaSetHudStyle(String style) {
        String normalized = style == null ? "" : style.trim().toLowerCase(java.util.Locale.ROOT);
        hudStyleOverride = HUD_STYLES.contains(normalized) ? normalized : null;
    }

    public String psychLuaGetHudStyle() {
        return effectiveHudStyle();
    }

    /** Convenience: hide/show both the time bar fill and the song title at once. */
    public void psychLuaShowTimeBar(boolean visible) {
        showTimeBar = visible;
        showTimeText = visible;
    }
}
