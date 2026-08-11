package com.fnfmod.client.gui.editor;

import com.fnfmod.FnfMod;
import com.fnfmod.chart.CameraShotClipboard;
import com.fnfmod.chart.Conductor;
import com.fnfmod.chart.ChartEventTypes;
import com.fnfmod.chart.PsychChartWriter;
import com.fnfmod.chart.SongChart;
import com.fnfmod.client.ClientOptions;
import com.fnfmod.client.audio.SongPlayer;
import com.fnfmod.client.camera.GameplayCamera;
import com.fnfmod.client.gameplay.PsychAssetResolver;
import com.fnfmod.client.gameplay.NativeFilePicker;
import com.fnfmod.client.anim.CharacterAnimations;
import com.fnfmod.client.anim.CharacterDefinitionFile;
import com.fnfmod.client.gui.GameplayScreen;
import com.fnfmod.client.gui.TextInputAwareScreen;
import com.fnfmod.client.math.Easing;
import com.fnfmod.client.render.NoteStyle;
import com.fnfmod.client.render.PsychNoteTextureCache;
import com.fnfmod.song.SongEntry;
import com.fnfmod.song.SongImportService;
import com.fnfmod.song.SongLibrary;
import com.fnfmod.gameplay.PlaybackMode;
import com.fnfmod.gameplay.PlaybackPolicy;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import com.fnfmod.block.FunkinMachineBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Psych-inspired chart editor for the normalized FNF chart format.
 *
 * The playhead remains fixed while the chart moves beneath it. The event lane
 * supports Minecraft command events alongside imported engine events.
 */
public final class ChartEditorScreen extends Screen implements TextInputAwareScreen {

    private static final int[] SNAPS = {4, 8, 12, 16, 24, 32, 64};
    /** Default zoom uses four rows per beat, matching 16/16 Beat Snap. */
    private static final double BASE_GRID_ZOOM_ROWS = 4.0;
    // 120 makes one 16th-note row exactly as tall as a lane cell is wide at the
    // reference layout, so the default grid reads as squares instead of being
    // vertically squashed (which previously forced a manual Z zoom to correct).
    /** Zoom adds grid rows per beat; Beat Snap alone scales their physical height. */
    private static final int[] GRID_ROWS_PER_BEAT = {1, 2, 3, 4, 6, 8, 12, 16};
    private static final int TAB_Y = 24;
    private static final int CONTROL_TOP = 40;
    private static final List<String> BUILTIN_EVENT_TYPES = ChartEventTypes.builtinNames();
    private static final String[] HELP_LINES = {
            "W/S/Mouse Wheel - Move Conductor's Time",
            "A/D - Change Sections",
            "Q/E - Decrease/Increase Note Sustain Length",
            "Hold Shift/Alt to Increase/Decrease move by 4x",
            "F12 - Preview Chart (always available)",
            "Enter - Playtest Chart (needs a Funkin' Machine)",
            "Shift + Enter - Playtest from Conductor's Time",
            "Space - Stop/Resume song",
            "M - Toggle editor metronome",
            "I/O - Set loop in/out; L - Toggle loop",
            "T - Tap tempo; B - Apply detected/tapped BPM",
            "Alt + Click - Select Note(s)",
            "Shift + Click - Select/Unselect Note(s)",
            "Right Click - Selection Box",
            "R - Reset Section",
            "Shift + R - Go Back to the Start of the Song",
            "Z/X - Zoom in/out",
            "Left/Right - Change Snap",
            "Left Bracket / Right Bracket - Change Song Playback Rate",
            "Alt + Left Bracket / Right Bracket - Reset Song Playback Rate",
            "Ctrl + Z - Undo",
            "Ctrl + Y - Redo",
            "Ctrl + X - Cut Selected Notes",
            "Ctrl + C - Copy Selected Notes",
            "Ctrl + V - Paste Copied Notes",
            "Ctrl + A - Select all in current Section",
            "Ctrl + S - Quicksave"
    };

    private enum EditorTab { CHARTING, DATA, EVENTS, NOTE, SECTION, SONG }
    private enum TopMenu { NONE, FILE, EDIT, VIEW }

    private record UiLabel(String text, int x, int y, int color, boolean centered) {}

    private final String requestedSongId;
    private final String requestedDifficulty;
    private final SongChart suppliedChart;
    private final Path suppliedSongFolder;
    private final Path suppliedOriginalDirectory;
    private final BlockPos sourceMachinePos;
    /** Look the chart was opened with; null when it was not opened from a live song. */
    private final PlaybackPolicy sourcePolicy;
    private final List<UiLabel> labels = new ArrayList<>();
    private final List<SongChart.Note> sectionClipboard = new ArrayList<>();
    private final List<SongChart.Note> noteClipboard = new ArrayList<>();
    private final List<SongChart.Event> eventClipboard = new ArrayList<>();
    private boolean seededCameraShot;
    /** Check a stored saving folder only once instead of repeating on GUI resizes. */
    private boolean saveFolderValidated;
    /** Missing assigned folder shown as a true foreground modal on editor open. */
    private Path missingSavingFolderWarning;
    private final Set<SongChart.Note> selectedNotes = new LinkedHashSet<>();
    private final Set<SongChart.Event> selectedEvents = new LinkedHashSet<>();
    private record EditorState(List<SongChart.Note> notes, List<SongChart.Event> events) {}
    private record MeterSegment(double startBeat, SongChart.TimeSignature signature) {}
    private final Deque<EditorState> undoHistory = new ArrayDeque<>();
    private final Deque<EditorState> redoHistory = new ArrayDeque<>();

    private SongChart chart;
    private Conductor conductor;
    private SongEntry entry;
    private Path pendingAudioFolder;
    private final List<Path> temporaryAudioFolders = new ArrayList<>();
    private boolean audioImportRunning;
    private SongPlayer audio;
    private com.fnfmod.client.input.EditorTickScheduler tickScheduler;
    private boolean schedulerActive;
    private PsychNoteTextureCache noteTextures;
    private String songId;
    private String saveId;
    private String importModName;
    private String loadedDifficulty = "normal";
    private String defaultNoteType = "";
    private Path originalDirectory;
    private String originalChartName;
    private Path eventDefinitionRoot;
    private final List<String> eventTypes = new ArrayList<>(BUILTIN_EVENT_TYPES);

    private double viewPositionMs;
    /** Visual playhead center; seeks ease here while audio/timing jump immediately. */
    private double displayPositionMs = Double.NaN;
    private double viewportTweenFromMs;
    private long viewportTweenStartNano;
    private boolean viewportTweenActive;
    private static final long VIEWPORT_TWEEN_NANOS = 200_000_000L;
    // Silent lead-in for a positive offset: the playhead advances on the wall clock
    // through the pre-song gap before the audio starts, instead of skipping it.
    private long leadInStartNano = -1;
    private double leadInBaseView;
    // F12 note-only preview inside the editor (no characters/camera/HUD).
    private boolean previewMode;
    private final java.util.Set<SongChart.Note> previewHitNotes = new java.util.HashSet<>();
    private final long[] previewFlashUntil = new long[8];
    private int snapIndex = 3;
    private int shownSection = -1;
    /** Playback may cross sections while a field/slider owns input; rebuild after release instead. */
    private boolean deferredSectionRefresh;
    private boolean controlSliderDragging;
    private int hitsoundIndex;
    private boolean vortex;
    private SongChart.Note selectedNote;
    private SongChart.Event selectedEvent;
    private boolean eventDropdownOpen;
    private int eventDropdownScroll;
    private boolean eventDraftInitialized;
    private String eventTypeDraft = ChartEventTypes.MINECRAFT_COMMAND;
    private String eventValue1Draft = "";
    private String eventValue2Draft = "player";
    private String eventValue3Draft = "";
    private String eventValue4Draft = "";
    private String eventValue5Draft = "";
    private String eventValue6Draft = "";
    private String eventValue7Draft = "";
    private double eventTimeDraft;
    private boolean eventBeforeSongDraft;
    private int gridZoomIndex = 3;
    private float playbackRate = 1.0f;
    private Path chartSaveFile;
    private volatile EditorWaveform.Data instWaveform;
    private volatile EditorWaveform.Data playerWaveform;
    private volatile EditorWaveform.Data opponentWaveform;
    private CompletableFuture<?> waveformLoad;
    private boolean loopEnabled;
    private double loopStartMs = -1;
    private double loopEndMs = -1;
    private int preRollBeats = 1;
    private final Deque<Long> tempoTaps = new ArrayDeque<>();
    private double tappedBpm;
    /** 0 normal, 1 mute, 2 solo. */
    private int instMix, playerMix, opponentMix;
    private String cachedPlayerColorKey = "";
    private String cachedOpponentColorKey = "";
    private int cachedPlayerColor = -1;
    private int cachedOpponentColor = -1;
    private boolean instColorPickerOpen;
    private int instColorPickerOriginal;
    private float instColorHue, instColorSat, instColorBri;
    /** 0 none, 1 saturation/brightness square, 2 hue strip. */
    private int instColorDrag;
    private EditBox instColorHexField;
    private boolean updatingInstColorHex;
    private boolean bookmarkTextDialogOpen;
    private int bookmarkTextStage;
    private double bookmarkDraftTime;
    private String bookmarkDraftName = "";
    private EditBox bookmarkTextField;
    private boolean helpVisible;
    private boolean eventDocumentationVisible;
    private int eventDocumentationScroll;
    // Cache of custom-event <name>.txt documentation, keyed by lowercase event
    // name. Sentinel "" marks a scanned-but-missing file so render loops do not
    // re-hit the disk every frame. Cleared on reload/import.
    private final Map<String, String> customEventDocCache = new HashMap<>();
    private boolean selectingBox;
    private double selectionStartX;
    /** Vertical selection bounds are chart beats so scrolling cannot move the anchor. */
    private double selectionStartBeat;
    private double selectionEndX;
    private double selectionEndBeat;

    private EditorTab activeTab = EditorTab.SONG;
    private TopMenu openMenu = TopMenu.NONE;

    private String status = "";
    private long statusUntil;

    private int infoX = -1;
    private int infoY = -1;
    private boolean draggingInfo;
    private double dragOffsetX;
    private double dragOffsetY;

    private EditBox songNameField;
    private EditBox songBpmField;
    private EditBox songSpeedField;
    private EditBox songOffsetField;
    private EditBox songTimeNumeratorField;
    private EditBox songTimeDenominatorField;
    private EditBox playerField;
    private EditBox opponentField;
    private EditBox sectionBpmField;
    private EditBox sectionBeatsField;
    private EditBox sectionTimeNumeratorField;
    private EditBox sectionTimeDenominatorField;
    private EditBox noteTimeField;
    private EditBox sustainField;
    private EditBox noteTypeField;
    private EditBox saveIdField;
    private EditBox importModNameField;
    private EditBox eventTimeField;
    private EditBox eventValue1Field;
    private EditBox eventValue2Field;
    private EditBox eventValue3Field;
    private EditBox eventValue4Field;
    private EditBox eventValue5Field;
    private EditBox eventValue7Field;
    private EditBox noteTextureField;
    private EditBox noteSplashTextureField;

    public ChartEditorScreen(String songId) {
        this(songId, null, null, null, null, null);
    }

    /** Opens the exact chart currently held by gameplay, including its active difficulty. */
    public ChartEditorScreen(String songId, String difficulty, SongChart chart, Path songFolder) {
        this(songId, difficulty, chart, songFolder, songFolder, null);
    }

    /** Opens gameplay's chart while retaining its chart/audio origin reference. */
    public ChartEditorScreen(String songId, String difficulty, SongChart chart,
                             Path songFolder, Path originalDirectory) {
        this(songId, difficulty, chart, songFolder, originalDirectory, null);
    }

    public ChartEditorScreen(String songId, String difficulty, SongChart chart,
                             Path songFolder, Path originalDirectory, BlockPos machinePos) {
        this(songId, difficulty, chart, songFolder, originalDirectory, machinePos, null);
    }

    /**
     * {@code sourcePolicy} is the look the chart was playing with when the editor
     * opened. Opening the editor leaves the session, so this is captured here or
     * a playtest would fall back to a different presentation than the real song.
     */
    public ChartEditorScreen(String songId, String difficulty, SongChart chart,
                             Path songFolder, Path originalDirectory, BlockPos machinePos,
                             PlaybackPolicy sourcePolicy) {
        super(Component.literal("FNF Chart Editor"));
        this.sourcePolicy = sourcePolicy;
        this.requestedSongId = songId;
        this.requestedDifficulty = difficulty;
        this.suppliedChart = chart;
        this.suppliedSongFolder = songFolder;
        this.suppliedOriginalDirectory = originalDirectory;
        this.sourceMachinePos = machinePos;
        this.songId = songId;
    }

    @Override
    protected void init() {
        if (chart == null) loadChart();
        ensureAudioLoaded();
        clampOrInitializeInfoWindow();
        // Camera events copied from a playtest's free camera land here so Ctrl+V
        // pastes them. Consuming the shared clipboard into the normal event
        // clipboard means a later in-editor copy still overrides them as usual.
        if (!seededCameraShot && !CameraShotClipboard.isEmpty()) {
            noteClipboard.clear();
            eventClipboard.clear();
            eventClipboard.addAll(CameraShotClipboard.get());
            CameraShotClipboard.clear();
            seededCameraShot = true;
            setStatus("Camera shot ready to paste (Ctrl+V)");
        }
        rebuildUi();
        validateSavingFolderOnOpen();
    }

    private void loadChart() {
        SongLibrary.rescan();
        String difficulty = requestedDifficulty == null || requestedDifficulty.isBlank()
                ? "normal" : requestedDifficulty;
        SongEntry libraryEntry = requestedSongId == null ? null : SongLibrary.get(requestedSongId);
        if (libraryEntry == null && requestedSongId != null) {
            libraryEntry = SongLibrary.getSongs().values().stream()
                    .filter(song -> requestedSongId.equalsIgnoreCase(song.id)).findFirst().orElse(null);
        }
        if (libraryEntry != null && libraryEntry.fullModLayout) {
            eventDefinitionRoot = libraryEntry.runtimeRoot();
        }
        if (libraryEntry != null && libraryEntry.fullModLayout) {
            entry = libraryEntry;
        } else if (suppliedSongFolder != null) {
            entry = SongLibrary.scanSongDir(suppliedSongFolder);
        }
        if (entry == null && requestedSongId != null) {
            entry = SongLibrary.get(requestedSongId);
        }
        originalDirectory = suppliedOriginalDirectory;
        if (originalDirectory == null && entry != null) {
            originalDirectory = entry.chartOriginRoot != null ? entry.chartOriginRoot
                    : (entry.modRoot != null ? entry.modRoot : entry.folder);
        }
        originalChartName = entry != null && entry.id != null ? entry.id : requestedSongId;
        if (suppliedChart != null) {
            chart = suppliedChart;
        }
        if (chart == null && entry != null) {
            try {
                if (!entry.difficulties.contains(difficulty)) {
                    difficulty = entry.difficulties.contains("normal") ? "normal" : entry.difficulties.get(0);
                }
                chart = SongLibrary.loadChart(entry, difficulty);
                chartSaveFile = entry.chartOverrideFor(difficulty) != null
                        ? entry.chartOverrideFor(difficulty) : entry.legacyChartFor(difficulty);
            } catch (Exception e) {
                FnfMod.LOGGER.warn("Editor: failed to load {}: {}", requestedSongId, e.toString());
            }
        }

        if (chart == null) {
            chart = new SongChart();
            chart.title = requestedSongId == null ? "new-song" : requestedSongId;
            chart.bpmChanges.add(new SongChart.BpmChange(0, chart.startBpm));
            songId = null;
        }
        chart.ensureSectionsCoverNotes();
        ensureSection(chart.sections.size() + 7);
        chart.rebuildBpmMap();
        conductor = new Conductor(chart);
        saveId = songId == null ? sanitizeId(chart.title) : songId;
        importModName = sanitizeModFolder(saveId + "-mod");
        loadedDifficulty = difficulty;
        discoverEventTypes();
        reloadNoteTextures();

        ensureAudioLoaded();
    }

    private void reloadNoteTextures() {
        if (noteTextures != null) noteTextures.close();
        Path folder = entry != null && entry.folder != null ? entry.folder : suppliedSongFolder;
        PlaybackPolicy policy = PlaybackPolicy.resolve(PlaybackMode.LEGACY, entry);
        PsychAssetResolver resolver = new PsychAssetResolver(folder, entry, policy, chart.stage);
        boolean enabled = entry == null || policy.allows(entry, SongLibrary.ExternalContent.IMAGES);
        List<Path> roots = entry == null
                ? java.util.Arrays.asList(folder,
                SongLibrary.primaryExternalAssetRoot(SongLibrary.ExternalContent.IMAGES))
                : resolver.customNoteRoots();
        noteTextures = new PsychNoteTextureCache(
                roots, enabled);
    }

    private void ensureAudioLoaded() {
        if (audio != null || entry == null || entry.instFor(loadedDifficulty) == null) return;
        SongPlayer candidate = new SongPlayer();
        try {
            candidate.load(entry.instFor(loadedDifficulty), entry.voicesFor(loadedDifficulty),
                    entry.voicesPlayerFor(loadedDifficulty), entry.voicesOpponentFor(loadedDifficulty));
            audio = candidate;
            applyStemMix();
            loadWaveforms();
        } catch (Exception e) {
            candidate.dispose();
            FnfMod.LOGGER.warn("Editor: audio unavailable: {}", e.toString());
        }
    }

    private void disposeAudio() {
        if (audio == null) return;
        audio.dispose();
        audio = null;
    }

    private void loadWaveforms() {
        instWaveform = playerWaveform = opponentWaveform = null;
        if (entry == null) return;
        Path inst = entry.instFor(loadedDifficulty);
        Path combined = entry.voicesFor(loadedDifficulty);
        Path player = entry.voicesPlayerFor(loadedDifficulty);
        Path opponent = entry.voicesOpponentFor(loadedDifficulty);
        CompletableFuture<EditorWaveform.Data> instFuture = EditorWaveform.decode(inst);
        CompletableFuture<EditorWaveform.Data> playerFuture = EditorWaveform.decode(player != null ? player : combined);
        CompletableFuture<EditorWaveform.Data> opponentFuture = EditorWaveform.decode(opponent);
        waveformLoad = CompletableFuture.allOf(instFuture, playerFuture, opponentFuture).thenRun(() -> {
            instWaveform = instFuture.getNow(null);
            playerWaveform = playerFuture.getNow(null);
            opponentWaveform = opponentFuture.getNow(null);
        });
    }

    // --------------------------------------------------------------------- UI construction

    private void rebuildUi() {
        clearWidgets();
        labels.clear();
        clearFieldReferences();
        shownSection = sectionIndexAt(viewPositionMs);

        buildTopBar();
        buildTabs();
        switch (activeTab) {
            case CHARTING -> buildChartingTab();
            case DATA -> buildDataTab();
            case EVENTS -> buildEventsTab();
            case NOTE -> buildNoteTab();
            case SECTION -> buildSectionTab();
            case SONG -> buildSongTab();
        }
        buildOpenMenu();
    }

    private void clearFieldReferences() {
        songNameField = songBpmField = songSpeedField = songOffsetField = null;
        songTimeNumeratorField = songTimeDenominatorField = null;
        playerField = opponentField = sectionBpmField = sectionBeatsField = null;
        sectionTimeNumeratorField = sectionTimeDenominatorField = null;
        noteTimeField = sustainField = noteTypeField = saveIdField = importModNameField = null;
        eventTimeField = eventValue1Field = eventValue2Field = null;
        eventValue3Field = eventValue4Field = eventValue5Field = eventValue7Field = null;
        noteTextureField = noteSplashTextureField = null;
    }

    private void buildTopBar() {
        String[] names = {"File", "Edit", "View"};
        TopMenu[] menus = {TopMenu.FILE, TopMenu.EDIT, TopMenu.VIEW};
        for (int i = 0; i < names.length; i++) {
            final TopMenu menu = menus[i];
            button(16 + i * 72, 8, 70, names[i], b -> {
                openMenu = openMenu == menu ? TopMenu.NONE : menu;
                rebuildUi();
            });
        }
    }

    private void buildTabs() {
        int x = controlX();
        int w = controlWidth();
        EditorTab[] tabs = EditorTab.values();
        for (int i = 0; i < tabs.length; i++) {
            EditorTab tab = tabs[i];
            int x0 = x + i * w / tabs.length;
            int x1 = x + (i + 1) * w / tabs.length;
            String text = w < 260 ? shortTabName(tab) : titleCase(tab.name());
            Button tabButton = button(x0, TAB_Y, x1 - x0, text, b -> switchTab(tab));
            tabButton.active = tab != activeTab;
        }
    }

    private void switchTab(EditorTab tab) {
        commitVisibleFields();
        eventDocumentationVisible = false;
        activeTab = tab;
        openMenu = TopMenu.NONE;
        rebuildUi();
    }

    private void buildSongTab() {
        int x = controlX() + 10;
        int w = controlWidth() - 20;
        int y = 52;
        songNameField = labeledBox("Song Name", x, y, w, chart.title, "song name");
        y += 31;
        int third = (w - 8) / 3;
        songBpmField = numberBox("BPM", x, y, third, trim(chart.startBpm), "e.g. 128.5");
        songSpeedField = numberBox("Scroll Speed", x + third + 4, y, third, trim(chart.speed), "speed");
        songOffsetField = numberBox("Offset (ms)", x + (third + 4) * 2, y, third, trim(chart.offsetMs), "+later −earlier");
        y += 31;
        int half = (w - 4) / 2;
        songTimeNumeratorField = numberBox("Meter beats", x, y, half,
                String.valueOf(chart.startingTimeSignature().numerator()), "e.g. 3");
        songTimeDenominatorField = numberBox("Beat unit", x + half + 4, y, half,
                String.valueOf(chart.startingTimeSignature().denominator()), "1, 2, 4, 8, 16, 32");
        y += 31;
        playerField = labeledBox("Player", x, y, half, chart.player1, "player");
        opponentField = labeledBox("Opponent", x + half + 4, y, half, chart.player2, "opponent");
        y += 31;
        button(x, y, w, "Allow Vocals: " + onOff(chart.needsVoices), b -> {
            chart.needsVoices = !chart.needsVoices;
            b.setMessage(Component.literal("Allow Vocals: " + onOff(chart.needsVoices)));
        });
        y += 18;
        button(x, y, w, entry == null || entry.instFor(loadedDifficulty) == null
                ? "Choose Exact Song Audio..." : "Change Song Audio...", b -> chooseSongAudio());
    }

    private void buildSectionTab() {
        int x = controlX() + 10;
        int w = controlWidth() - 20;
        int half = (w - 4) / 2;
        int y = 52;
        SongChart.Section section = sectionAt(shownSection);

        button(x, y, half, "Must Hit: " + onOff(section.mustHit), b -> {
            section.mustHit = !section.mustHit;
            b.setMessage(Component.literal("Must Hit: " + onOff(section.mustHit)));
        });
        button(x + half + 4, y, half, "GF Section: " + onOff(section.gfSection), b -> {
            section.gfSection = !section.gfSection;
            b.setMessage(Component.literal("GF Section: " + onOff(section.gfSection)));
        });
        y += 18;
        button(x, y, half, "Alt Anim: " + onOff(section.altAnim), b -> {
            section.altAnim = !section.altAnim;
            b.setMessage(Component.literal("Alt Anim: " + onOff(section.altAnim)));
        });
        button(x + half + 4, y, half, "Change BPM: " + onOff(section.changeBPM), b -> {
            commitVisibleFields();
            section.changeBPM = !section.changeBPM;
            if (section.changeBPM && section.bpm <= 0) section.bpm = chart.bpmForSection(shownSection);
            chart.rebuildBpmMap();
            conductor = new Conductor(chart);
            rebuildUi();
        });
        y += 27;
        sectionBpmField = numberBox("Section BPM", x, y, half,
                section.changeBPM ? trim(section.bpm) : "", "e.g. 128.5");
        sectionBpmField.active = section.changeBPM;
        sectionBeatsField = numberBox("Beats per Section", x + half + 4, y, half,
                trim(section.sectionBeats), "beats");
        y += 31;
        SongChart.TimeSignature meter = chart.timeSignatureForSection(shownSection);
        button(x, y, w, "Change Meter: " + onOff(section.changeTimeSignature), b -> {
            commitVisibleFields();
            section.changeTimeSignature = !section.changeTimeSignature;
            if (section.changeTimeSignature) {
                section.timeSignatureNumerator = meter.numerator();
                section.timeSignatureDenominator = meter.denominator();
            }
            rebuildUi();
        });
        y += 22;
        sectionTimeNumeratorField = numberBox("Meter beats", x, y, half,
                String.valueOf(meter.numerator()), "e.g. 7");
        sectionTimeDenominatorField = numberBox("Beat unit", x + half + 4, y, half,
                String.valueOf(meter.denominator()), "1, 2, 4, 8, 16, 32");
        sectionTimeNumeratorField.active = section.changeTimeSignature;
        sectionTimeDenominatorField.active = section.changeTimeSignature;
        y += 31;
        button(x, y, half, "Copy Section", b -> copySection(shownSection));
        button(x + half + 4, y, half, "Paste Section", b -> pasteSection(shownSection));
        y += 18;
        button(x, y, half, "Copy Previous", b -> copyPreviousSection(shownSection));
        button(x + half + 4, y, half, "Clear Section", b -> clearSection(shownSection));
        y += 18;
        button(x, y, half, "Swap Sides", b -> swapSection(shownSection));
        button(x + half + 4, y, half, "Mirror Notes", b -> mirrorSection(shownSection));
        y += 18;
        button(x, y, half, "Duet Section", b -> duetSection(shownSection));
        button(x + half + 4, y, half, "Events: " + chart.events.size(), b -> switchTab(EditorTab.EVENTS));
    }

    private void buildNoteTab() {
        int x = controlX() + 10;
        int w = controlWidth() - 20;
        int y = 52;
        String time = selectedNote == null ? "" : trim(selectedNote.timeMs);
        String sustain = selectedNote == null ? "" : trim(selectedNote.sustainMs);
        String type = selectedNote == null ? defaultNoteType : selectedNote.noteType;

        noteTimeField = labeledBox("Note Hit Time (ms)", x, y, w, time, "select a note");
        sustainField = labeledBox("Sustain Length (ms)", x, y + 31, w, sustain, "sustain");
        noteTypeField = labeledBox("Note Type", x, y + 62, w, type, "note type");
        noteTimeField.active = selectedNote != null;
        sustainField.active = selectedNote != null;
        button(x, y + 93, w, selectedNote == null ? "Default type applies to new notes" : "Apply Selected Note", b -> {
            commitVisibleFields();
            setStatus(selectedNote == null ? "Default note type updated" : "Selected note updated");
            rebuildUi();
        });
    }

    private void buildChartingTab() {
        int x = controlX() + 10;
        int w = controlWidth() - 20;
        int y = 54;
        label("Editor-only playback options", controlX() + controlWidth() / 2, 43, 0xFFDDDDDD, true);

        addRenderableWidget(new net.minecraft.client.gui.components.AbstractSliderButton(
                x, y, w, 14, Component.empty(), (playbackRate - 0.1) / 4.9) {
            @Override protected void updateMessage() {
                setMessage(Component.literal(String.format(Locale.ROOT,
                        "Playback Rate: %.1fx", playbackRate)));
            }
            @Override protected void applyValue() {
                setPlaybackRate((float) (0.1 + value * 4.9));
                updateMessage();
            }
        });
        y += 22;
        button(x, y, w, "Beat Snap: " + snapText(), b -> cycleSnap(hasShiftDown() ? -1 : 1));
        y += 18;
        Button vortexButton = button(x, y, w, "Vortex Editor: " + onOff(vortex), b -> {
            vortex = !vortex;
            if (vortex && !isPlaying()) snapPlayheadToGrid();
            b.setMessage(Component.literal("Vortex Editor: " + onOff(vortex)));
        });
        vortexButton.setTooltip(Tooltip.create(Component.literal("Keys 1-8 toggle notes at the fixed playhead")));
        y += 24;

        ClientOptions options = ClientOptions.get();
        int half = (w - 4) / 2;
        button(x, y, half, "Hitsound P: " + onOff(options.editorHitsoundPlayer), b -> {
            options.editorHitsoundPlayer = !options.editorHitsoundPlayer;
            ClientOptions.save();
            b.setMessage(Component.literal("Hitsound P: " + onOff(options.editorHitsoundPlayer)));
        });
        button(x + half + 4, y, half, "Hitsound O: " + onOff(options.editorHitsoundOpponent), b -> {
            options.editorHitsoundOpponent = !options.editorHitsoundOpponent;
            ClientOptions.save();
            b.setMessage(Component.literal("Hitsound O: " + onOff(options.editorHitsoundOpponent)));
        });
        y += 22;
        button(x, y, w, "Metronome: " + onOff(options.editorMetronome), b -> {
            setMetronomeEnabled(!options.editorMetronome);
            b.setMessage(Component.literal("Metronome: " + onOff(options.editorMetronome)));
        }).setTooltip(Tooltip.create(Component.literal(
                "Editor only: accented bar click plus regular meter-beat clicks.")));
        y += 18;
        addRenderableWidget(new net.minecraft.client.gui.components.AbstractSliderButton(
                x, y, w, 14, Component.empty(), options.editorMetronomeVolume) {
            @Override protected void updateMessage() {
                setMessage(Component.literal("Metronome Volume: "
                        + Math.round(options.editorMetronomeVolume * 100) + "%"));
            }
            @Override protected void applyValue() {
                options.editorMetronomeVolume = Mth.clamp(value, 0.0, 1.0);
                ClientOptions.save();
                updateMessage();
            }
        });
        y += 22;
        button(x, y, half, "Loop In: " + timeLabel(loopStartMs), b -> {
            loopStartMs = viewPositionMs;
            if (loopEndMs >= 0 && loopEndMs <= loopStartMs) loopEndMs = -1;
            rebuildUi();
        });
        button(x + half + 4, y, half, "Loop Out: " + timeLabel(loopEndMs), b -> {
            loopEndMs = viewPositionMs;
            if (loopStartMs >= 0 && loopEndMs <= loopStartMs) loopStartMs = -1;
            rebuildUi();
        });
        y += 18;
        button(x, y, half, "Loop: " + onOff(loopEnabled), b -> {
            loopEnabled = !loopEnabled;
            rebuildUi();
        });
        button(x + half + 4, y, half, "Pre-roll: " + preRollBeats + " beat(s)", b -> {
            preRollBeats = Math.floorMod(preRollBeats + (hasShiftDown() ? -1 : 1), 9);
            rebuildUi();
        });
        y += 20;
        String detected = !tempoTaps.isEmpty() ? tempoTaps.size() + "/3"
                : tappedBpm > 0 ? String.format(Locale.ROOT, "%.2f", tappedBpm)
                : instWaveform != null && instWaveform.estimatedBpm() > 0
                ? String.format(Locale.ROOT, "%.2f", instWaveform.estimatedBpm()) : "--";
        button(x, y, half, "Tap BPM: " + detected, b -> { tapTempo(); rebuildUi(); });
        button(x + half + 4, y, half, "Apply BPM", b -> applySuggestedBpm());
        y += 20;
        int third = (w - 8) / 3;
        button(x, y, third, "Inst " + mixLabel(instMix), b -> { instMix = (instMix + 1) % 3; applyStemMix(); rebuildUi(); });
        button(x + third + 4, y, third, "Player " + mixLabel(playerMix), b -> { playerMix = (playerMix + 1) % 3; applyStemMix(); rebuildUi(); });
        button(x + (third + 4) * 2, y, third, "Opp " + mixLabel(opponentMix), b -> { opponentMix = (opponentMix + 1) % 3; applyStemMix(); rebuildUi(); });
        y += 20;
        button(x, y, third, "+ Bookmark", b -> addBookmark());
        button(x + third + 4, y, third, "Previous", b -> seekBookmark(-1));
        button(x + (third + 4) * 2, y, third, "Next", b -> seekBookmark(1));
        y += 18;
        button(x, y, w, "Delete nearest bookmark (" + chart.bookmarks.size() + ")", b -> deleteNearestBookmark());
    }

    private void buildDataTab() {
        int x = controlX() + 10;
        int w = controlWidth() - 20;
        int y = 52;
        label("Reserved Psych song-data fields", controlX() + controlWidth() / 2, 43, 0xFFDDDDDD, true);
        for (String name : List.of("Game Over Character", "Death Sound", "Loop Music", "Retry Music")) {
            EditBox field = labeledBox(name, x, y, w, "", "not supported yet");
            field.active = false;
            y += 27;
        }
        noteTextureField = labeledBox("Note Texture", x, y, w,
                chart.noteTexture == null ? "" : chart.noteTexture, "images/NOTE_assets");
        noteTextureField.setMaxLength(Integer.MAX_VALUE);
        noteTextureField.setResponder(value -> chart.noteTexture = value.trim());
        y += 27;
        noteSplashTextureField = labeledBox("Note Splash Texture", x, y, w,
                chart.noteSplashTexture == null ? "" : chart.noteSplashTexture,
                "images/noteSplashes/noteSplashes");
        noteSplashTextureField.setMaxLength(Integer.MAX_VALUE);
        noteSplashTextureField.setResponder(value -> chart.noteSplashTexture = value.trim());
    }

    private void buildEventsTab() {
        int x = controlX() + 10;
        int w = controlWidth() - 20;
        int y = 52;
        if (!eventDraftInitialized) setEventDraft(selectedEvent);
        List<SongChart.Event> pointEvents = eventsAtSelectedPoint();
        int pointIndex = selectedEvent == null ? -1 : pointEvents.indexOf(selectedEvent);
        label("Event", x, 43, 0xFFDDDDDD, false);
        label(selectedEvent == null ? "Event" : "Event " + (pointIndex + 1) + " / " + pointEvents.size(),
                x, y, 0xFFDDDDDD, false);
        int small = 22;
        int gap = 2;
        int typeWidth = Math.max(40, w - 4 * (small + gap));
        button(x, y + 10, typeWidth, eventTypeDraft + "  v", b -> {
            commitVisibleFields();
            eventDropdownOpen = !eventDropdownOpen;
            if (eventDropdownOpen) eventDropdownScroll = 0;
            rebuildUi();
        });
        int actionX = x + typeWidth + gap;
        Button removePointEvent = button(actionX, y + 10, small, "-", b -> removeEventFromPoint());
        removePointEvent.active = selectedEvent != null;
        actionX += small + gap;
        button(actionX, y + 10, small, "+", b -> addEventToPoint());
        actionX += small + gap;
        Button previousPointEvent = button(actionX, y + 10, small, "<", b -> cyclePointEvent(-1));
        previousPointEvent.active = pointEvents.size() > 1;
        actionX += small + gap;
        Button nextPointEvent = button(actionX, y + 10, small, ">", b -> cyclePointEvent(1));
        nextPointEvent.active = pointEvents.size() > 1;
        if (eventDropdownOpen) {
            int visible = Math.max(3, (height - (y + 24) - 8) / 14);
            visible = Math.min(visible, eventTypes.size());
            eventDropdownScroll = Mth.clamp(eventDropdownScroll, 0,
                    Math.max(0, eventTypes.size() - visible));
            for (int row = 0; row < visible; row++) {
                int i = eventDropdownScroll + row;
                String type = eventTypes.get(i);
                button(x, y + 24 + row * 14, typeWidth, type, b -> selectEventType(type));
            }
            if (eventTypes.size() > visible) {
                label((eventDropdownScroll + 1) + "-" + (eventDropdownScroll + visible)
                                + " / " + eventTypes.size() + "  (scroll)",
                        x + typeWidth / 2, y + 27 + visible * 14, 0xFFBBBBBB, true);
            }
            return;
        }
        button(x, y + 27, w, "Trigger: " + (eventBeforeSongDraft ? "Before Song" : "Timeline"), b -> {
            commitVisibleFields();
            eventBeforeSongDraft = !eventBeforeSongDraft;
            if (selectedEvent != null) selectedEvent.beforeSong = eventBeforeSongDraft;
            rebuildUi();
        });
        eventTimeField = labeledBox("Time (ms)", x, y + 45, w,
                trim(eventTimeDraft), "event time");
        eventTimeField.active = !eventBeforeSongDraft;
        boolean cameraZoom = ChartEventTypes.isCameraZoom(eventTypeDraft);
        boolean cameraFocus = ChartEventTypes.isCameraFocus(eventTypeDraft);
        boolean cameraBehavior = ChartEventTypes.isCameraBehavior(eventTypeDraft);
        boolean cameraFollow = ChartEventTypes.isCameraFollowPos(eventTypeDraft);
        boolean cameraRotation = ChartEventTypes.isCameraRotation3d(eventTypeDraft);
        boolean addCharacter = ChartEventTypes.is(eventTypeDraft, ChartEventTypes.ADD_CHARACTER);
        boolean tweenCharacter = ChartEventTypes.is(eventTypeDraft, ChartEventTypes.TWEEN_CHARACTER);
        boolean extendedCamera = cameraFollow || cameraRotation;
        int actionsY;
        if (extendedCamera || addCharacter || tweenCharacter) {
            int halfValue = (w - 4) / 2;
            eventValue1Field = eventValueBox("Value 1", x, y + 72, halfValue,
                    eventValue1Draft, ChartEventTypes.value1Hint(eventTypeDraft));
            eventValue2Field = eventValueBox("Value 2", x + halfValue + 4, y + 72, halfValue,
                    eventValue2Draft, ChartEventTypes.value2Hint(eventTypeDraft));
            eventValue3Field = eventValueBox("Value 3", x, y + 99, w,
                    eventValue3Draft, ChartEventTypes.value3Hint(eventTypeDraft));
            if (addCharacter) {
                eventValue4Field = eventValueBox("Value 4", x, y + 126, w,
                        eventValue4Draft, ChartEventTypes.value4Hint(eventTypeDraft));
                eventValue5Field = eventValueBox("Value 5", x, y + 153, w,
                        eventValue5Draft, ChartEventTypes.value5Hint(eventTypeDraft));
            } else {
                buildEasingControl(x, y + 126, w, 4, true, true);
            }
            if (cameraFollow) {
                label("Value 5", x, y + 153, 0xFFDDDDDD, false);
                button(x, y + 163, w,
                        eventValue5Draft.isBlank() || eventValue5Draft.equalsIgnoreCase("default")
                                ? "Movement: Default" : "Movement: Override Lua + default",
                        b -> cycleCameraMovementOverride());
                label("Value 6", x, y + 180, 0xFFDDDDDD, false);
                button(x, y + 190, w,
                        cameraFollowUsesCameraFrame(eventValue6Draft)
                                ? "Frame: Camera Rotation" : "Frame: Machine Facing",
                        b -> cycleCameraFollowFrame());
                eventValue7Field = eventValueBox("Value 7", x, y + 207, w,
                        eventValue7Draft, ChartEventTypes.value7Hint(eventTypeDraft));
                actionsY = y + 234;
            } else if (tweenCharacter || cameraRotation) {
                eventValue5Field = eventValueBox("Value 5", x, y + 153, w,
                        eventValue5Draft, ChartEventTypes.value5Hint(eventTypeDraft));
                actionsY = y + 180;
            } else {
                actionsY = y + 180;
            }
        } else {
            if (ChartEventTypes.isMinecraftCommand(eventTypeDraft)) {
                label("Value 1", x, y + 72, 0xFFDDDDDD, false);
                String preview = eventValue1Draft.isBlank() ? "Click to edit command..." : eventValue1Draft;
                button(x, y + 82, w, font.plainSubstrByWidth(preview, Math.max(8, w - 12)),
                        b -> openCommandEditor());
            } else if (cameraFocus) {
                label("Value 1", x, y + 72, 0xFFDDDDDD, false);
                String target = eventValue1Draft.isBlank() ? "Normal (Must Hit)" : eventValue1Draft;
                button(x, y + 82, w, "Target: " + target,
                        b -> cycleCameraFocusTarget(hasShiftDown() ? -1 : 1));
            } else {
                eventValue1Field = eventValueBox("Value 1", x, y + 72, w, eventValue1Draft,
                        ChartEventTypes.value1Hint(eventTypeDraft));
            }
            if (cameraFocus || cameraBehavior) {
                buildEasingControl(x, y + 99, w, 2, cameraBehavior,
                        !cameraFocus || !eventValue1Draft.isBlank());
                actionsY = y + 126;
            } else if (cameraZoom) {
                // Value 2 = duration (seconds), Value 3 = easing.
                eventValue2Field = eventValueBox("Value 2", x, y + 99, w,
                        eventValue2Draft, ChartEventTypes.value2Hint(eventTypeDraft));
                buildEasingControl(x, y + 126, w, 3, false, true);
                actionsY = y + 153;
            } else {
                eventValue2Field = eventValueBox("Value 2", x, y + 99, w,
                        eventValue2Draft, ChartEventTypes.value2Hint(eventTypeDraft));
                actionsY = y + 126;
            }
        }
        int half = (w - 4) / 2;
        button(x, actionsY, half, "Add Event", b -> addEventAtFieldTime());
        Button apply = button(x + half + 4, actionsY, half, "Apply Selected", b -> {
            commitVisibleFields();
            setStatus("Event updated");
            rebuildUi();
        });
        apply.active = selectedEvent != null;
        Button remove = button(x, actionsY + 18, half, "Delete Event", b -> deleteSelectedEvent());
        remove.active = selectedEvent != null;
        button(x + half + 4, actionsY + 18, half, "Event Help...", b -> openEventDocumentation());
    }

    private void openEventDocumentation() {
        commitVisibleFields();
        helpVisible = false;
        eventDocumentationScroll = 0;
        eventDocumentationVisible = true;
    }

    private void discoverEventTypes() {
        customEventDocCache.clear();
        LinkedHashSet<String> discovered = new LinkedHashSet<>(BUILTIN_EVENT_TYPES);
        if (chart != null) {
            chart.events.stream().map(event -> event.name)
                    .filter(name -> name != null && !name.isBlank())
                    .forEach(discovered::add);
        }
        if (entry == null || entry.allows(SongLibrary.ExternalContent.LUA)) {
            addCustomEventFiles(eventDefinitionRoot, discovered);
            if (entry != null) addCustomEventFiles(entry.folder, discovered);
        }
        eventTypes.clear();
        eventTypes.addAll(BUILTIN_EVENT_TYPES);
        discovered.stream().filter(name -> !BUILTIN_EVENT_TYPES.contains(name))
                .sorted(String.CASE_INSENSITIVE_ORDER).forEach(eventTypes::add);
    }

    private static void addCustomEventFiles(Path root, Set<String> names) {
        if (root == null) return;
        Path directory = root.resolve("custom_events");
        if (!Files.isDirectory(directory)) return;
        try (var files = Files.list(directory)) {
            files.filter(Files::isRegularFile).map(path -> path.getFileName().toString())
                    .filter(name -> {
                        String lower = name.toLowerCase(Locale.ROOT);
                        return lower.endsWith(".lua") || lower.endsWith(".txt");
                    })
                    .map(name -> name.substring(0, name.lastIndexOf('.')))
                    .filter(name -> !name.isBlank() && !name.equalsIgnoreCase("readme"))
                    .forEach(names::add);
        } catch (Exception e) {
            FnfMod.LOGGER.warn("Could not scan custom events in {}: {}", directory, e.toString());
        }
    }

    /**
     * Documentation shown for an event type. Custom events ship a
     * {@code custom_events/<name>.txt} beside their {@code <name>.lua}; when that
     * file exists its contents are used as the help text. Built-in events and
     * custom events without a txt fall back to {@link ChartEventTypes#help}.
     */
    private String eventHelpText(String type) {
        String custom = customEventDoc(type);
        return custom != null ? custom : ChartEventTypes.help(type);
    }

    /** Returns the cached {@code <name>.txt} for a custom event, or null if none. */
    private String customEventDoc(String type) {
        if (type == null || type.isBlank() || ChartEventTypes.definition(type) != null) return null;
        String key = type.toLowerCase(Locale.ROOT);
        String cached = customEventDocCache.get(key);
        if (cached != null) return cached.isEmpty() ? null : cached;

        String text = readCustomEventDoc(type);
        customEventDocCache.put(key, text == null ? "" : text);
        return text;
    }

    /** Reads {@code custom_events/<name>.txt}, mod folder first, then the shared root. */
    private String readCustomEventDoc(String type) {
        String text = readCustomEventDocFrom(entry == null ? null : entry.folder, type);
        if (text == null) text = readCustomEventDocFrom(eventDefinitionRoot, type);
        return text;
    }

    private static String readCustomEventDocFrom(Path root, String type) {
        if (root == null) return null;
        Path txt = root.resolve("custom_events").resolve(type + ".txt");
        try {
            if (Files.isRegularFile(txt)) {
                String content = Files.readString(txt).strip();
                if (!content.isBlank()) return content;
            }
        } catch (Exception e) {
            FnfMod.LOGGER.warn("Could not read custom event doc {}: {}", txt, e.toString());
        }
        return null;
    }

    private void buildOpenMenu() {
        if (openMenu == TopMenu.NONE) return;
        int x = switch (openMenu) {
            case FILE -> 16;
            case EDIT -> 88;
            case VIEW -> 160;
            default -> 16;
        };
        int y = 25;
        int w = 144;
        if (openMenu == TopMenu.FILE) {
            saveIdField = box(x + 4, y + 3, w - 8, saveId, "chart filename");
            y += 21;
            button(x + 4, y, w - 8, "Save", b -> saveChart(false)); y += 16;
            button(x + 4, y, w - 8, "Save As...", b -> saveChart(true)); y += 16;
            button(x + 4, y, w - 8, "Choose Song Audio...", b -> chooseSongAudio()); y += 16;
            Button openEvents = button(x + 4, y, w - 8, "Open Events...", b -> {}); openEvents.active = false; y += 16;
            button(x + 4, y, w - 8, "Save Events...", b -> saveEventsOnly()); y += 16;
            importModNameField = box(x + 4, y + 2, w - 8, importModName, "mod folder (My-Mod)");
            y += 20;
            Button importSong = button(x + 4, y, w - 8, "Import Song to Mod", b -> importSongFiles());
            importSong.setTooltip(Tooltip.create(Component.literal(
                    "Creates a mod template and imports only charts, events, metadata, and OGG audio"))); y += 16;
            button(x + 4, y, w - 8, "Exit", b -> onClose());
        } else if (openMenu == TopMenu.EDIT) {
            Button undo = button(x + 4, y, w - 8, "Undo", b -> { undoNotes(); rebuildUi(); });
            undo.active = !undoHistory.isEmpty(); y += 16;
            Button redo = button(x + 4, y, w - 8, "Redo", b -> { redoNotes(); rebuildUi(); });
            redo.active = !redoHistory.isEmpty(); y += 16;
            Path savingFolder = configuredSavingFolder();
            Button chooseSavingFolder = button(x + 4, y, w - 8,
                    savingFolder == null ? "Choose Saving Folder..." : "Change Saving Folder...",
                    b -> chooseSavingFolder());
            chooseSavingFolder.setTooltip(Tooltip.create(Component.literal(savingFolder == null
                    ? "No folder assigned. Ctrl+S saves to the default song folder."
                    : "Current: " + savingFolder))); y += 16;
            Button clearSavingFolder = button(x + 4, y, w - 8, "Clear Saving Folder",
                    b -> clearSavingFolder());
            clearSavingFolder.active = savingFolder != null;
            clearSavingFolder.setTooltip(Tooltip.create(Component.literal(
                    "Removes this song's assigned folder; Ctrl+S uses the default song folder."))); y += 16;
            button(x + 4, y, w - 8, "Copy Section", b -> copySection(shownSection)); y += 16;
            button(x + 4, y, w - 8, "Paste Section", b -> pasteSection(shownSection)); y += 16;
            Button snapNotes = button(x + 4, y, w - 8, "Snap Notes to Grid", b -> snapNotesToGrid());
            snapNotes.setTooltip(Tooltip.create(Component.literal(
                    "Realigns notes (and hold ends) to the current Beat Snap. Snaps the "
                            + "selection if any, otherwise every note. Use after changing the BPM."))); y += 16;
            Button snapEvents = button(x + 4, y, w - 8, "Snap Events to Grid", b -> snapEventsToGrid());
            snapEvents.setTooltip(Tooltip.create(Component.literal(
                    "Realigns events to the current Beat Snap. Snaps the "
                            + "selection if any, otherwise every event. Use after changing the BPM."))); y += 16;
            button(x + 4, y, w - 8, "Clear All Notes", b -> {
                if (chart.notes.isEmpty()) return;
                recordNoteChange();
                chart.notes.clear();
                clearSelection();
                setStatus("Cleared all notes");
            }); y += 16;
            Button removeAllCustom = button(x + 4, y, w - 8, "Remove All Custom Notes", b -> removeAllCustomNotes());
            removeAllCustom.active = chart.notes.stream().anyMatch(this::isCustomNote); y += 16;
            Button removeSelectedTypes = button(x + 4, y, w - 8, "Remove Selected Types", b -> removeSelectedCustomNoteTypes());
            removeSelectedTypes.active = selectedNotes.stream().anyMatch(this::isCustomNote); y += 16;
            Button clearEvents = button(x + 4, y, w - 8, "Clear All Events", b -> {
                if (chart.events.isEmpty()) return;
                recordNoteChange();
                chart.events.clear();
                selectedEvents.clear();
                selectedEvent = null;
                setStatus("Cleared all events");
                rebuildUi();
            });
            clearEvents.active = !chart.events.isEmpty();
        } else {
            button(x + 4, y, w - 8, "Beat Snap: " + snapText(),
                    b -> cycleSnap(hasShiftDown() ? -1 : 1)); y += 16;
            button(x + 4, y, w - 8, "Vortex Editor: " + onOff(vortex), b -> {
                vortex = !vortex;
                if (vortex && !isPlaying()) snapPlayheadToGrid();
                rebuildUi();
            }); y += 16;
            ClientOptions options = ClientOptions.get();
            Button axisGizmo = button(x + 4, y, w - 8,
                    "Playtest Axis Gizmo: " + onOff(options.editorShowAxisGizmo), b -> {
                        options.editorShowAxisGizmo = !options.editorShowAxisGizmo;
                        ClientOptions.save();
                        rebuildUi();
                    });
            axisGizmo.setTooltip(Tooltip.create(Component.literal(
                    "Shows a world XYZ axis gizmo in the bottom-right while playtesting. "
                            + "Y points up."))); y += 16;
            button(x + 4, y, w - 8, "Waveforms: " + onOff(options.editorWaveforms), b -> {
                options.editorWaveforms = !options.editorWaveforms;
                ClientOptions.save();
                rebuildUi();
            }); y += 16;
            button(x + 4, y, w - 8, "Onset Markers: " + onOff(options.editorOnsetMarkers), b -> {
                options.editorOnsetMarkers = !options.editorOnsetMarkers;
                ClientOptions.save();
                rebuildUi();
            }); y += 16;
            button(x + 4, y, w - 8,
                    String.format(Locale.ROOT, "Inst Color: #%06X", options.editorInstWaveformColor & 0xFFFFFF),
                    b -> openInstColorPicker());
        }
    }

    /** Accepts decimals and a leading sign, plus intermediate states while typing. */
    private static final java.util.function.Predicate<String> NUMERIC_FILTER =
            value -> value.isEmpty() || value.matches("-?\\d*\\.?\\d*");

    /** Minecraft's own EditBox default, kept for fields that never needed more. */
    private static final int VANILLA_BOX_MAX_LENGTH = 32;

    /**
     * Event Value fields hold command text and property paths, so they get twice
     * the vanilla allowance.
     */
    private static final int EVENT_VALUE_MAX_LENGTH = VANILLA_BOX_MAX_LENGTH * 2;

    private EditBox labeledBox(String label, int x, int y, int w, String value, String hint) {
        label(label, x, y, 0xFFDDDDDD, false);
        return box(x, y + 10, w, value, hint);
    }

    /** Labeled field sized for event values, which allow longer text than a plain box. */
    private EditBox eventValueBox(String label, int x, int y, int w, String value, String hint) {
        label(label, x, y, 0xFFDDDDDD, false);
        return box(x, y + 10, w, value, hint, EVENT_VALUE_MAX_LENGTH);
    }

    /** Labeled field constrained to numeric input, so decimal BPM/offset stick. */
    private EditBox numberBox(String label, int x, int y, int w, String value, String hint) {
        EditBox field = labeledBox(label, x, y, w, value, hint);
        field.setFilter(NUMERIC_FILTER);
        return field;
    }

    private EditBox box(int x, int y, int w, String value, String hint) {
        return box(x, y, w, value, hint, VANILLA_BOX_MAX_LENGTH);
    }

    /**
     * The max length must be applied before the value: {@link EditBox#setValue}
     * truncates against whatever limit is set at that moment, so assigning it
     * afterwards silently cuts the existing text to the vanilla default.
     */
    private EditBox box(int x, int y, int w, String value, String hint, int maxLength) {
        EditBox field = addRenderableWidget(new EditBox(font, x, y, Math.max(8, w), 14, Component.literal(hint)));
        field.setMaxLength(maxLength);
        field.setValue(value == null ? "" : value);
        if (!hint.isEmpty()) field.setHint(Component.literal(hint));
        return field;
    }

    private Button button(int x, int y, int w, String text, Button.OnPress press) {
        return addRenderableWidget(Button.builder(Component.literal(text), press)
                .bounds(x, y, Math.max(1, w), 14).build());
    }

    private void label(String text, int x, int y, int color, boolean centered) {
        labels.add(new UiLabel(text, x, y, color, centered));
    }

    // --------------------------------------------------------------------- State commits

    private void commitVisibleFields() {
        if (songNameField != null && !songNameField.getValue().isBlank()) chart.title = songNameField.getValue();
        chart.startBpm = parsePositive(songBpmField, chart.startBpm);
        chart.speed = parsePositive(songSpeedField, chart.speed);
        chart.offsetMs = parseNumber(songOffsetField, chart.offsetMs);
        SongChart.TimeSignature startingMeter = parseTimeSignature(
                songTimeNumeratorField, songTimeDenominatorField, chart.startingTimeSignature());
        chart.timeSignatureNumerator = startingMeter.numerator();
        chart.timeSignatureDenominator = startingMeter.denominator();
        if (playerField != null && !playerField.getValue().isBlank()) chart.player1 = playerField.getValue().trim();
        if (opponentField != null && !opponentField.getValue().isBlank()) chart.player2 = opponentField.getValue().trim();

        if (sectionBpmField != null || sectionBeatsField != null) {
            SongChart.Section section = sectionAt(shownSection);
            if (section.changeBPM) section.bpm = parsePositive(sectionBpmField,
                    section.bpm > 0 ? section.bpm : chart.bpmForSection(shownSection));
            section.sectionBeats = Mth.clamp(parsePositive(sectionBeatsField, section.sectionBeats), 0.25, 64.0);
            if (section.changeTimeSignature) {
                SongChart.TimeSignature meter = parseTimeSignature(sectionTimeNumeratorField,
                        sectionTimeDenominatorField, chart.timeSignatureForSection(shownSection));
                section.timeSignatureNumerator = meter.numerator();
                section.timeSignatureDenominator = meter.denominator();
            }
        }

        if (noteTypeField != null) {
            defaultNoteType = noteTypeField.getValue().trim();
            if (selectedNote != null) {
                selectedNote.noteType = defaultNoteType;
                selectedNote.timeMs = Math.max(0, parseNumber(noteTimeField, selectedNote.timeMs));
                selectedNote.sustainMs = Math.max(0, parseNumber(sustainField, selectedNote.sustainMs));
                chart.sortNotes();
            }
        }
        if (noteTextureField != null) chart.noteTexture = noteTextureField.getValue().trim();
        if (noteSplashTextureField != null) {
            chart.noteSplashTexture = noteSplashTextureField.getValue().trim();
        }

        if (eventTimeField != null) eventTimeDraft = Math.max(0, parseNumber(eventTimeField, eventTimeDraft));
        if (eventValue1Field != null) eventValue1Draft = eventValue1Field.getValue();
        if (eventValue2Field != null) eventValue2Draft = eventValue2Field.getValue().trim();
        if (eventValue3Field != null) eventValue3Draft = eventValue3Field.getValue().trim();
        if (eventValue4Field != null) eventValue4Draft = eventValue4Field.getValue().trim();
        if (eventValue5Field != null) eventValue5Draft = eventValue5Field.getValue().trim();
        if (eventValue7Field != null) eventValue7Draft = eventValue7Field.getValue().trim();
        if (selectedEvent != null && eventDraftInitialized) {
            selectedEvent.timeMs = eventTimeDraft;
            selectedEvent.name = eventTypeDraft;
            selectedEvent.value1 = eventValue1Draft;
            selectedEvent.value2 = eventValue2Draft;
            selectedEvent.value3 = eventValue3Draft;
            selectedEvent.value4 = eventValue4Draft;
            selectedEvent.value5 = eventValue5Draft;
            selectedEvent.value6 = eventValue6Draft;
            selectedEvent.value7 = eventValue7Draft;
            selectedEvent.beforeSong = eventBeforeSongDraft;
            chart.sortEvents();
        }

        if (saveIdField != null && !saveIdField.getValue().isBlank()) saveId = sanitizeId(saveIdField.getValue());
        if (importModNameField != null && !importModNameField.getValue().isBlank()) {
            importModName = sanitizeModFolder(importModNameField.getValue());
        }
        chart.rebuildBpmMap();
        conductor = new Conductor(chart);
    }

    private static double parsePositive(EditBox field, double fallback) {
        double value = parseNumber(field, fallback);
        return value > 0 ? value : fallback;
    }

    private static double parseNumber(EditBox field, double fallback) {
        if (field == null) return fallback;
        try {
            return Double.parseDouble(field.getValue().trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static SongChart.TimeSignature parseTimeSignature(EditBox numeratorField,
                                                               EditBox denominatorField,
                                                               SongChart.TimeSignature fallback) {
        int numerator = parseInteger(numeratorField, fallback.numerator());
        int denominator = parseInteger(denominatorField, fallback.denominator());
        return new SongChart.TimeSignature(numerator, denominator);
    }

    private static int parseInteger(EditBox field, int fallback) {
        if (field == null) return fallback;
        try {
            return (int) Math.round(Double.parseDouble(field.getValue().trim()));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    // --------------------------------------------------------------------- Chart editing

    private int sectionIndexAt(double ms) {
        int index = 0;
        while (index < 4095 && chart.sectionStartMs(index + 1) <= ms + 0.01) index++;
        return index;
    }

    private void ensureSection(int index) {
        while (index >= chart.sections.size()) chart.sections.add(new SongChart.Section());
    }

    private SongChart.Section sectionAt(int index) {
        ensureSection(Math.max(0, index));
        return chart.sections.get(Math.max(0, index));
    }

    private List<SongChart.Note> notesInSection(int sectionIndex) {
        double start = chart.sectionStartMs(sectionIndex);
        double end = chart.sectionStartMs(sectionIndex + 1);
        List<SongChart.Note> result = new ArrayList<>();
        for (SongChart.Note note : chart.notes) {
            if (note.timeMs >= start - 0.01 && note.timeMs < end - 0.01) result.add(note);
        }
        return result;
    }

    private SongChart.Note findNote(int column, double timeMs, double toleranceMs) {
        int lane = column % 4;
        boolean player = column >= 4;
        for (SongChart.Note note : chart.notes) {
            if (note.lane == lane && note.playerSide == player && Math.abs(note.timeMs - timeMs) < toleranceMs) {
                return note;
            }
        }
        return null;
    }

    /**
     * Re-aligns notes to the current Beat Snap. Notes keep their millisecond time
     * when the BPM changes, so after an edit they land off-grid; this rounds each
     * note (and its hold end) to the nearest snap step on the current timing map.
     */
    private void snapNotesToGrid() {
        commitVisibleFields();
        boolean onlySelected = !selectedNotes.isEmpty();
        List<SongChart.Note> targets = onlySelected
                ? new ArrayList<>(selectedNotes) : chart.notes;
        if (targets.isEmpty()) {
            setStatus("No notes to snap");
            return;
        }
        recordNoteChange();
        double step = snapStepBeats();
        int moved = 0;
        for (SongChart.Note note : targets) {
            double headBeat = conductor.beatAt(note.timeMs);
            double snappedHead = Math.max(0, Math.round(headBeat / step) * step);
            double newHead = conductor.timeOfBeat(snappedHead);
            if (note.sustainMs > 0) {
                double endBeat = conductor.beatAt(note.timeMs + note.sustainMs);
                double snappedEnd = Math.max(snappedHead, Math.round(endBeat / step) * step);
                note.sustainMs = Math.max(0, conductor.timeOfBeat(snappedEnd) - newHead);
            }
            if (Math.abs(newHead - note.timeMs) > 0.01) moved++;
            note.timeMs = newHead;
        }
        chart.sortNotes();
        setStatus("Snapped " + moved + (onlySelected ? " selected" : "") + " note(s) to grid");
        rebuildUi();
    }

    /**
     * Re-aligns events to the current Beat Snap. Like {@link #snapNotesToGrid()},
     * events keep their millisecond time across a BPM change and can drift off the
     * grid; this rounds each event to the nearest snap step on the current timing
     * map. Events flagged {@code beforeSong} keep their pre-song placement.
     */
    private void snapEventsToGrid() {
        commitVisibleFields();
        boolean onlySelected = !selectedEvents.isEmpty();
        List<SongChart.Event> targets = onlySelected
                ? new ArrayList<>(selectedEvents) : chart.events;
        if (targets.isEmpty()) {
            setStatus("No events to snap");
            return;
        }
        recordNoteChange();
        double step = snapStepBeats();
        int moved = 0;
        for (SongChart.Event event : targets) {
            if (event.beforeSong) continue;
            double beat = conductor.beatAt(event.timeMs);
            double snappedBeat = Math.max(0, Math.round(beat / step) * step);
            double newTime = conductor.timeOfBeat(snappedBeat);
            if (Math.abs(newTime - event.timeMs) > 0.01) moved++;
            event.timeMs = newTime;
        }
        chart.sortEvents();
        setStatus("Snapped " + moved + (onlySelected ? " selected" : "") + " event(s) to grid");
        rebuildUi();
    }

    private EditorState snapshot() {
        List<SongChart.Note> notes = new ArrayList<>(chart.notes.size());
        for (SongChart.Note note : chart.notes) notes.add(note.copy());
        List<SongChart.Event> events = new ArrayList<>(chart.events.size());
        for (SongChart.Event event : chart.events) events.add(event.copy());
        return new EditorState(notes, events);
    }

    /** Records a restore point covering both notes and events before an edit. */
    private void recordNoteChange() {
        undoHistory.push(snapshot());
        while (undoHistory.size() > 100) undoHistory.removeLast();
        redoHistory.clear();
    }

    private void restoreState(EditorState state) {
        chart.notes.clear();
        for (SongChart.Note note : state.notes()) chart.notes.add(note.copy());
        chart.sortNotes();
        chart.events.clear();
        for (SongChart.Event event : state.events()) chart.events.add(event.copy());
        chart.sortEvents();
        clearSelection();
        rebuildUi();
    }

    private void undoNotes() {
        if (undoHistory.isEmpty()) {
            setStatus("Nothing to undo");
            return;
        }
        redoHistory.push(snapshot());
        restoreState(undoHistory.pop());
        setStatus("Undo");
    }

    private void redoNotes() {
        if (redoHistory.isEmpty()) {
            setStatus("Nothing to redo");
            return;
        }
        undoHistory.push(snapshot());
        restoreState(redoHistory.pop());
        setStatus("Redo");
    }

    private void clearSelection() {
        selectedNotes.clear();
        selectedNote = null;
        selectedEvents.clear();
        selectedEvent = null;
    }

    private void selectOnly(SongChart.Note note) {
        selectedNotes.clear();
        if (note != null) selectedNotes.add(note);
        selectedNote = note;
        if (note != null) defaultNoteType = note.noteType == null ? "" : note.noteType;
    }

    private void addSelection(SongChart.Note note) {
        if (note == null) return;
        selectedNotes.add(note);
        selectedNote = note;
        defaultNoteType = note.noteType == null ? "" : note.noteType;
    }

    private void toggleSelection(SongChart.Note note) {
        if (note == null) return;
        if (!selectedNotes.remove(note)) selectedNotes.add(note);
        selectedNote = selectedNotes.isEmpty() ? null : selectedNotes.stream().reduce((a, b) -> b).orElse(null);
        if (selectedNote != null) defaultNoteType = selectedNote.noteType == null ? "" : selectedNote.noteType;
    }

    private void toggleNote(int column, double timeMs, boolean removeOnly) {
        commitVisibleFields();
        SongChart.Note existing = findNote(column, timeMs, stepMs() / 2.0);
        if (existing != null) {
            recordNoteChange();
            chart.notes.remove(existing);
            selectedNotes.remove(existing);
            if (selectedNote == existing) selectedNote = selectedNotes.stream().findFirst().orElse(null);
            setStatus("Removed note");
        } else if (!removeOnly) {
            recordNoteChange();
            SongChart.Note note = new SongChart.Note(timeMs, column % 4, column >= 4, 0, defaultNoteType);
            chart.notes.add(note);
            chart.sortNotes();
            selectOnly(note);
            setStatus("Placed note @ " + (int) timeMs + "ms");
        }
        if (activeTab == EditorTab.NOTE) rebuildUi();
    }

    private void copySection(int sectionIndex) {
        commitVisibleFields();
        sectionClipboard.clear();
        double start = chart.sectionStartMs(sectionIndex);
        for (SongChart.Note note : notesInSection(sectionIndex)) {
            SongChart.Note copy = note.copy();
            copy.timeMs -= start;
            sectionClipboard.add(copy);
        }
        setStatus("Copied " + sectionClipboard.size() + " notes");
    }

    private void pasteSection(int sectionIndex) {
        commitVisibleFields();
        if (sectionClipboard.isEmpty()) return;
        recordNoteChange();
        double start = chart.sectionStartMs(sectionIndex);
        for (SongChart.Note stored : sectionClipboard) {
            SongChart.Note copy = stored.copy();
            copy.timeMs += start;
            chart.notes.add(copy);
        }
        chart.sortNotes();
        setStatus("Pasted " + sectionClipboard.size() + " notes");
    }

    private void copyPreviousSection(int sectionIndex) {
        if (sectionIndex <= 0) {
            setStatus("There is no previous section");
            return;
        }
        copySection(sectionIndex - 1);
        pasteSection(sectionIndex);
    }

    private void clearSection(int sectionIndex) {
        commitVisibleFields();
        List<SongChart.Note> removed = notesInSection(sectionIndex);
        if (removed.isEmpty()) return;
        recordNoteChange();
        chart.notes.removeAll(removed);
        selectedNotes.removeAll(removed);
        if (removed.contains(selectedNote)) selectedNote = selectedNotes.stream().findFirst().orElse(null);
        setStatus("Cleared " + removed.size() + " notes");
    }

    private void swapSection(int sectionIndex) {
        commitVisibleFields();
        recordNoteChange();
        for (SongChart.Note note : notesInSection(sectionIndex)) note.playerSide = !note.playerSide;
        setStatus("Swapped section sides");
    }

    private void mirrorSection(int sectionIndex) {
        commitVisibleFields();
        recordNoteChange();
        for (SongChart.Note note : notesInSection(sectionIndex)) note.lane = 3 - note.lane;
        setStatus("Mirrored section notes");
    }

    private void duetSection(int sectionIndex) {
        commitVisibleFields();
        List<SongChart.Note> copies = new ArrayList<>();
        for (SongChart.Note note : notesInSection(sectionIndex)) {
            SongChart.Note copy = note.copy();
            copy.playerSide = !copy.playerSide;
            if (findNote((copy.playerSide ? 4 : 0) + copy.lane, copy.timeMs, 0.01) == null) copies.add(copy);
        }
        if (copies.isEmpty()) return;
        recordNoteChange();
        chart.notes.addAll(copies);
        chart.sortNotes();
        setStatus("Added " + copies.size() + " duet notes");
    }

    private void adjustSustain(double deltaMs) {
        if (selectedNotes.isEmpty()) return;
        recordNoteChange();
        for (SongChart.Note note : selectedNotes) note.sustainMs = Math.max(0, note.sustainMs + deltaMs);
        setStatus("Sustain changed on " + selectedNotes.size() + " note(s)");
        if (activeTab == EditorTab.NOTE) rebuildUi();
    }

    private void copySelectedNotes() {
        noteClipboard.clear();
        eventClipboard.clear();
        if (selectedNotes.isEmpty() && selectedEvents.isEmpty()) return;
        // Shared time origin so notes and events keep their relative offsets.
        double first = Double.MAX_VALUE;
        for (SongChart.Note note : selectedNotes) first = Math.min(first, note.timeMs);
        for (SongChart.Event event : selectedEvents) first = Math.min(first, event.timeMs);

        List<SongChart.Note> notes = new ArrayList<>(selectedNotes);
        notes.sort(java.util.Comparator.comparingDouble(n -> n.timeMs));
        for (SongChart.Note note : notes) {
            SongChart.Note copy = note.copy();
            copy.timeMs -= first;
            noteClipboard.add(copy);
        }
        List<SongChart.Event> events = new ArrayList<>(selectedEvents);
        events.sort(java.util.Comparator.comparingDouble(e -> e.timeMs));
        for (SongChart.Event event : events) {
            SongChart.Event copy = event.copy();
            copy.timeMs -= first;
            eventClipboard.add(copy);
        }
        setStatus("Copied " + noteClipboard.size() + " note(s), " + eventClipboard.size() + " event(s)");
    }

    private void deleteSelectedNotes() {
        if (selectedNotes.isEmpty() && selectedEvents.isEmpty()) return;
        recordNoteChange();
        int notes = selectedNotes.size();
        int events = selectedEvents.size();
        chart.notes.removeAll(selectedNotes);
        chart.events.removeAll(selectedEvents);
        clearSelection();
        setStatus("Deleted " + notes + " note(s), " + events + " event(s)");
        rebuildUi();
    }

    private void pasteSelectedNotes() {
        if (noteClipboard.isEmpty() && eventClipboard.isEmpty()) return;
        recordNoteChange();
        double beat = conductor.beatAt(Math.max(0, viewPositionMs));
        double anchor = conductor.timeOfBeat(Math.max(0, Math.round(beat / snapStepBeats()) * snapStepBeats()));
        clearSelection();
        for (SongChart.Note stored : noteClipboard) {
            SongChart.Note copy = stored.copy();
            copy.timeMs += anchor;
            chart.notes.add(copy);
            selectedNotes.add(copy);
            selectedNote = copy;
        }
        for (SongChart.Event stored : eventClipboard) {
            SongChart.Event copy = stored.copy();
            copy.timeMs = Math.max(0, copy.timeMs + anchor);
            chart.events.add(copy);
            selectedEvents.add(copy);
            selectedEvent = copy;
        }
        chart.sortNotes();
        chart.sortEvents();
        if (selectedEvent != null) setEventDraft(selectedEvent);
        setStatus("Pasted " + noteClipboard.size() + " note(s), " + eventClipboard.size() + " event(s)");
        rebuildUi();
    }

    private void selectSectionNotes() {
        clearSelection();
        selectedNotes.addAll(notesInSection(sectionIndexAt(viewPositionMs)));
        selectedNote = selectedNotes.stream().findFirst().orElse(null);
        setStatus("Selected " + selectedNotes.size() + " note(s)");
        if (activeTab == EditorTab.NOTE) rebuildUi();
    }

    private boolean isCustomNote(SongChart.Note note) {
        return note != null && note.noteType != null && !note.noteType.isBlank();
    }

    private void removeAllCustomNotes() {
        List<SongChart.Note> removed = chart.notes.stream().filter(this::isCustomNote).toList();
        removeCustomNotes(removed, "all custom note types");
    }

    private void removeSelectedCustomNoteTypes() {
        Set<String> selectedTypes = selectedNotes.stream()
                .filter(this::isCustomNote)
                .map(note -> note.noteType.trim())
                .collect(java.util.stream.Collectors.toSet());
        if (selectedTypes.isEmpty()) {
            setStatus("Select at least one custom note type first");
            return;
        }

        List<SongChart.Note> removed = chart.notes.stream()
                .filter(note -> isCustomNote(note) && selectedTypes.contains(note.noteType.trim()))
                .toList();
        removeCustomNotes(removed, selectedTypes.size() + " selected custom type(s)");
    }

    private void removeCustomNotes(List<SongChart.Note> removed, String description) {
        if (removed.isEmpty()) {
            setStatus("No matching custom notes found");
            return;
        }
        recordNoteChange();
        chart.notes.removeAll(removed);
        selectedNotes.removeAll(removed);
        if (removed.contains(selectedNote)) {
            selectedNote = selectedNotes.stream().reduce((a, b) -> b).orElse(null);
        }
        setStatus("Removed " + removed.size() + " note(s) from " + description);
        rebuildUi();
    }

    private void saveChart() { saveChart(false); }

    private void saveChart(boolean forceDialog) {
        commitVisibleFields();
        if (audioImportRunning) {
            setStatus("Wait for song audio conversion to finish before saving");
            return;
        }
        String id = sanitizeId(saveId == null || saveId.isBlank() ? chart.title : saveId);
        if (id.isBlank()) id = "unnamed";
        try {
            Path directory;
            boolean useDefaultFolder = false;
            boolean assignmentSaved = true;
            if (forceDialog) {
                var selected = NativeFilePicker.selectFolder("Save chart for " + chart.title + " in folder");
                if (selected.isEmpty()) {
                    setStatus("Save cancelled");
                    return;
                }
                directory = selected.get().toAbsolutePath().normalize();
                assignmentSaved = ChartSaveFolderStore.set(chart.title, directory);
            } else {
                Path assigned = configuredSavingFolder();
                useDefaultFolder = assigned == null;
                directory = useDefaultFolder ? SongLibrary.songsDir().resolve(id) : assigned;
            }
            String fileName = useDefaultFolder ? defaultChartFileName(id) : chartFileName(id);
            Path file = directory.resolve(fileName);
            chartSaveFile = file;
            Files.createDirectories(directory);
            int importedAudio = savePendingAudio(directory);
            Files.writeString(file, PsychChartWriter.write(chart));
            Files.writeString(directory.resolve("events.json"), PsychChartWriter.writeEvents(chart));
            if (directory.startsWith(SongLibrary.songsDir())) {
                writeOriginalReference(directory, id);
            }
            songId = saveId = id;
            SongLibrary.rescan();
            setStatus("Saved chart + events to " + directory.toAbsolutePath()
                    + (importedAudio == 0 ? "" : " (and " + importedAudio + " audio stem(s))")
                    + (assignmentSaved ? "" : " (folder assignment could not be remembered)"));
        } catch (Exception e) {
            setStatus("Save failed: " + e.getMessage());
            FnfMod.LOGGER.error("Chart save failed", e);
        }
    }

    private String chartFileName(String id) {
        if (chartSaveFile != null && chartSaveFile.getFileName() != null) {
            String existing = chartSaveFile.getFileName().toString();
            if (existing.toLowerCase(Locale.ROOT).endsWith(".json")
                    && !existing.equalsIgnoreCase("events.json")) return existing;
        }
        return defaultChartFileName(id);
    }

    private String defaultChartFileName(String id) {
        String difficultySuffix = loadedDifficulty.equalsIgnoreCase("normal")
                ? "" : "-" + sanitizeId(loadedDifficulty);
        return id + difficultySuffix + ".json";
    }

    private Path configuredSavingFolder() {
        return ChartSaveFolderStore.get(chart == null ? "" : chart.title)
                .filter(Files::isDirectory)
                .orElse(null);
    }

    private void validateSavingFolderOnOpen() {
        if (saveFolderValidated || chart == null) return;
        saveFolderValidated = true;
        var configured = ChartSaveFolderStore.get(chart.title);
        if (configured.isEmpty() || Files.isDirectory(configured.get())) return;

        Path missing = configured.get();
        boolean cleared = ChartSaveFolderStore.clear(chart.title);
        missingSavingFolderWarning = missing;
        setStatus("Saving folder no longer exists: " + missing
                + (cleared ? "; Ctrl+S will use the default song folder"
                : "; use Save As to replace it"));
        rebuildUi();
    }

    private void dismissMissingSavingFolderWarning() {
        missingSavingFolderWarning = null;
    }

    private void chooseSavingFolder() {
        commitVisibleFields();
        NativeFilePicker.selectFolder("Choose saving folder for " + chart.title).ifPresent(folder -> {
            if (ChartSaveFolderStore.set(chart.title, folder)) {
                setStatus("Saving folder: " + folder.toAbsolutePath().normalize());
            } else {
                setStatus("Could not save the folder setting");
            }
            openMenu = TopMenu.NONE;
            rebuildUi();
        });
    }

    private void clearSavingFolder() {
        commitVisibleFields();
        if (ChartSaveFolderStore.clear(chart.title)) {
            setStatus("Saving folder cleared; Ctrl+S will use the default song folder");
        } else {
            setStatus("Could not clear the folder setting");
        }
        openMenu = TopMenu.NONE;
        rebuildUi();
    }

    private static Path ensureJsonExtension(Path file) {
        String name = file.getFileName().toString();
        if (name.toLowerCase(Locale.ROOT).endsWith(".json")) return file;
        return file.resolveSibling(name + ".json");
    }

    private void chooseSongAudio() {
        if (audioImportRunning) {
            setStatus("Song audio is already being converted");
            return;
        }
        List<Path> selected = NativeFilePicker.openFiles("Choose Inst and vocal stems",
                new String[]{"*.ogg", "*.oga", "*.wav", "*.mp3", "*.flac", "*.m4a", "*.aac",
                        "*.wma", "*.aif", "*.aiff", "*.opus"},
                "Supported song audio");
        if (selected.isEmpty()) return;

        audioImportRunning = true;
        setStatus("Importing " + selected.size() + " audio file(s)...");
        var client = minecraft;
        CompletableFuture.supplyAsync(() -> {
            try {
                return ChartAudioImport.prepare(selected, chart.player1, chart.player2);
            } catch (Exception error) {
                throw new java.util.concurrent.CompletionException(error);
            }
        }).whenComplete((result, failure) -> {
            if (client == null) {
                if (result != null) ChartAudioImport.delete(result.stagingFolder());
                return;
            }
            client.execute(() -> finishSongAudioImport(result, failure));
        });
    }

    private void finishSongAudioImport(ChartAudioImport.Result result, Throwable failure) {
        audioImportRunning = false;
        if (minecraft == null || minecraft.screen != this) {
            if (result != null) ChartAudioImport.delete(result.stagingFolder());
            return;
        }
        if (failure != null || result == null) {
            Throwable cause = failure;
            while (cause != null && cause.getCause() != null) cause = cause.getCause();
            setStatus("Audio import failed: " + (cause == null || cause.getMessage() == null
                    ? "unknown conversion error" : cause.getMessage()));
            rebuildUi();
            return;
        }

        disposeAudio();
        if (entry == null) {
            entry = new SongEntry();
            entry.id = sanitizeId(chart.title);
            if (entry.id.isBlank()) entry.id = "new-song";
            entry.displayName = chart.title;
            entry.folder = result.stagingFolder();
            entry.difficulties.add(loadedDifficulty);
        }
        for (ChartAudioImport.Stem stem : ChartAudioImport.Stem.values()) {
            Path file = result.stem(stem);
            if (file != null) setEntryAudioStem(stem, file);
        }
        pendingAudioFolder = result.stagingFolder();
        temporaryAudioFolders.add(result.stagingFolder());
        ensureAudioLoaded();

        String details = result.convertedCount() == 0 ? ""
                : "; converted " + result.convertedCount() + " to OGG";
        if (result.discardedCount() > 0) details += "; discarded " + result.discardedCount();
        if (entry.instFor(loadedDifficulty) == null) details += "; no Inst stem selected";
        setStatus("Prepared " + result.stems().size() + " audio stem(s)" + details
                + ". They will be copied with the chart on Save");
        rebuildUi();
    }

    private void setEntryAudioStem(ChartAudioImport.Stem stem, Path file) {
        SongEntry.VSliceVariation variation = null;
        if (entry.isVslice()) {
            variation = entry.variationFor(loadedDifficulty);
            if (variation == null) {
                variation = new SongEntry.VSliceVariation();
                entry.vsliceVariations.put(loadedDifficulty, variation);
            }
        }
        if (variation != null) {
            switch (stem) {
                case INST -> variation.instFile = file;
                case VOICES -> variation.voicesFile = file;
                case PLAYER -> variation.voicesPlayerFile = file;
                case OPPONENT -> variation.voicesOpponentFile = file;
            }
            return;
        }
        switch (stem) {
            case INST -> entry.instFile = file;
            case VOICES -> entry.voicesFile = file;
            case PLAYER -> entry.voicesPlayerFile = file;
            case OPPONENT -> entry.voicesOpponentFile = file;
        }
    }

    private int savePendingAudio(Path directory) throws Exception {
        if (pendingAudioFolder == null || entry == null) return 0;
        int saved = 0;
        for (ChartAudioImport.Stem stem : ChartAudioImport.Stem.values()) {
            Path source = entryAudioStem(stem);
            if (!isTemporaryAudio(source)) continue;
            Path destination = directory.resolve(stem.fileName);
            Files.copy(source, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            setEntryAudioStem(stem, destination);
            saved++;
        }
        if (isTemporaryAudio(entry.folder)) entry.folder = directory;
        pendingAudioFolder = null;
        return saved;
    }

    private boolean isTemporaryAudio(Path file) {
        if (file == null) return false;
        Path normalized = file.toAbsolutePath().normalize();
        return temporaryAudioFolders.stream().anyMatch(folder ->
                normalized.startsWith(folder.toAbsolutePath().normalize()));
    }

    private Path entryAudioStem(ChartAudioImport.Stem stem) {
        return switch (stem) {
            case INST -> entry.instFor(loadedDifficulty);
            case VOICES -> entry.voicesFor(loadedDifficulty);
            case PLAYER -> entry.voicesPlayerFor(loadedDifficulty);
            case OPPONENT -> entry.voicesOpponentFor(loadedDifficulty);
        };
    }

    // --------------------------------------------------------------------- Playback

    private boolean isPlaying() {
        return leadInStartNano >= 0
                || (audio != null && audio.isStarted() && !audio.isPaused());
    }

    private void setPlaybackRate(float rate) {
        playbackRate = Math.round(Mth.clamp(rate, 0.1f, 5.0f) * 10f) / 10f;
        if (audio != null) audio.setPlaybackRate(playbackRate);
        setStatus(String.format(Locale.ROOT, "Playback rate: %.1fx", playbackRate));
    }

    private void setMetronomeEnabled(boolean enabled) {
        ClientOptions options = ClientOptions.get();
        options.editorMetronome = enabled;
        ClientOptions.save();
        snapshotSchedulerNotes();
        tickScheduler().seek(viewPositionMs);
        setStatus("Metronome: " + onOff(enabled));
    }

    private static String mixLabel(int state) {
        return state == 1 ? "MUTE" : state == 2 ? "SOLO" : "ON";
    }

    private static String timeLabel(double timeMs) {
        if (timeMs < 0) return "--";
        int total = (int) Math.round(timeMs / 1000.0);
        return String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60);
    }

    private void applyStemMix() {
        if (audio == null) return;
        boolean anySolo = instMix == 2 || playerMix == 2 || opponentMix == 2;
        audio.setVolume(SongPlayer.Role.INST, anySolo ? (instMix == 2 ? 1 : 0) : (instMix == 1 ? 0 : 1));
        float player = anySolo ? (playerMix == 2 ? 1 : 0) : (playerMix == 1 ? 0 : 1);
        float opponent = anySolo ? (opponentMix == 2 ? 1 : 0) : (opponentMix == 1 ? 0 : 1);
        audio.setVolume(SongPlayer.Role.VOICES, player);
        audio.setVolume(SongPlayer.Role.VOICES_PLAYER, player);
        audio.setVolume(SongPlayer.Role.VOICES_OPPONENT, opponent);
    }

    private void openInstColorPicker() {
        ClientOptions options = ClientOptions.get();
        instColorPickerOriginal = options.editorInstWaveformColor & 0xFFFFFF;
        float[] hsb = java.awt.Color.RGBtoHSB((instColorPickerOriginal >> 16) & 255,
                (instColorPickerOriginal >> 8) & 255, instColorPickerOriginal & 255, null);
        instColorHue = hsb[0];
        instColorSat = hsb[1];
        instColorBri = hsb[2];
        instColorDrag = 0;
        instColorHexField = new EditBox(font, 0, 0, 80, 16, Component.literal("Hex color"));
        instColorHexField.setMaxLength(6);
        instColorHexField.setFilter(value -> value.matches("[0-9a-fA-F]{0,6}"));
        instColorHexField.setResponder(value -> {
            if (updatingInstColorHex || !value.matches("[0-9a-fA-F]{6}")) return;
            int rgb = Integer.parseInt(value, 16);
            options.editorInstWaveformColor = rgb;
            float[] next = java.awt.Color.RGBtoHSB((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, null);
            instColorHue = next[0]; instColorSat = next[1]; instColorBri = next[2];
        });
        updateInstColorHex(instColorPickerOriginal);
        openMenu = TopMenu.NONE;
        instColorPickerOpen = true;
        setFocused(instColorHexField);
        instColorHexField.setFocused(true);
    }

    private void updateInstColorHex(int rgb) {
        if (instColorHexField == null) return;
        updatingInstColorHex = true;
        instColorHexField.setValue(String.format(Locale.ROOT, "%06X", rgb & 0xFFFFFF));
        updatingInstColorHex = false;
    }

    private void updateInstColorPicker(double mouseX, double mouseY) {
        int x0 = instPickerX(), y0 = instPickerY();
        int squareX = x0 + 12, squareY = y0 + 28, squareSize = 80;
        int hueX = x0 + 12, hueY = y0 + 120, hueWidth = instPickerWidth() - 24;
        if (instColorDrag == 1) {
            instColorSat = (float) Mth.clamp((mouseX - squareX) / (squareSize - 1.0), 0, 1);
            instColorBri = 1f - (float) Mth.clamp((mouseY - squareY) / (squareSize - 1.0), 0, 1);
        } else if (instColorDrag == 2) {
            instColorHue = (float) Mth.clamp((mouseX - hueX) / (hueWidth - 1.0), 0, 1);
        }
        int rgb = java.awt.Color.HSBtoRGB(instColorHue, instColorSat, instColorBri) & 0xFFFFFF;
        ClientOptions.get().editorInstWaveformColor = rgb;
        updateInstColorHex(rgb);
    }

    private void closeInstColorPicker(boolean save) {
        if (!save) ClientOptions.get().editorInstWaveformColor = instColorPickerOriginal;
        else ClientOptions.save();
        instColorPickerOpen = false;
        instColorDrag = 0;
        instColorHexField = null;
        rebuildUi();
    }

    private int instPickerWidth() { return Math.min(260, Math.max(220, width - 24)); }
    private int instPickerHeight() { return 176; }
    private int instPickerX() { return (width - instPickerWidth()) / 2; }
    private int instPickerY() { return Math.max(8, (height - instPickerHeight()) / 2); }

    private void renderInstColorPicker(GuiGraphics gui, int mouseX, int mouseY) {
        gui.fill(0, 0, width, height, 0xF20A0A10);
        int boxW = instPickerWidth(), boxH = instPickerHeight();
        int x0 = instPickerX(), y0 = instPickerY();
        gui.fill(x0, y0, x0 + boxW, y0 + boxH, 0xFF101018);
        gui.renderOutline(x0, y0, boxW, boxH, 0xFF6A70FF);
        drawCentered(gui, "Instrumental waveform color", x0 + boxW / 2, y0 + 9, 0xFFFFFFFF);

        int squareX = x0 + 12, squareY = y0 + 28, squareSize = 80;
        for (int column = 0; column < squareSize; column++) {
            int color = java.awt.Color.HSBtoRGB(instColorHue, column / (squareSize - 1f), 1f);
            gui.fill(squareX + column, squareY, squareX + column + 1, squareY + squareSize,
                    0xFF000000 | (color & 0xFFFFFF));
        }
        gui.fillGradient(squareX, squareY, squareX + squareSize, squareY + squareSize,
                0x00000000, 0xFF000000);
        int cursorX = squareX + Math.round(instColorSat * (squareSize - 1));
        int cursorY = squareY + Math.round((1 - instColorBri) * (squareSize - 1));
        int rgb = ClientOptions.get().editorInstWaveformColor & 0xFFFFFF;
        gui.fill(cursorX - 2, cursorY - 2, cursorX + 3, cursorY + 3, 0xFFFFFFFF);
        gui.fill(cursorX - 1, cursorY - 1, cursorX + 2, cursorY + 2, 0xFF000000 | rgb);

        int rightX = squareX + squareSize + 14;
        int rightWidth = x0 + boxW - 12 - rightX;
        gui.fill(rightX, squareY, rightX + rightWidth, squareY + 36, 0xFF000000 | rgb);
        gui.renderOutline(rightX, squareY, rightWidth, 36, 0xFF6A7080);
        draw(gui, "Hex", rightX, squareY + 45, 0xFFBBBBBB);
        if (instColorHexField != null) {
            instColorHexField.setX(rightX);
            instColorHexField.setY(squareY + 56);
            instColorHexField.setWidth(rightWidth);
            instColorHexField.render(gui, mouseX, mouseY, 0);
        }

        int hueX = x0 + 12, hueY = y0 + 120, hueWidth = boxW - 24;
        for (int column = 0; column < hueWidth; column++) {
            int color = java.awt.Color.HSBtoRGB(column / (hueWidth - 1f), 1f, 1f);
            gui.fill(hueX + column, hueY, hueX + column + 1, hueY + 10,
                    0xFF000000 | (color & 0xFFFFFF));
        }
        int hueCursor = hueX + Math.round(instColorHue * (hueWidth - 1));
        gui.fill(hueCursor - 1, hueY - 1, hueCursor + 2, hueY + 11, 0xFFFFFFFF);

        int buttonY = y0 + boxH - 26;
        int buttonWidth = (boxW - 28) / 2;
        renderModalButton(gui, x0 + 10, buttonY, buttonWidth, "Cancel", mouseX, mouseY);
        renderModalButton(gui, x0 + 18 + buttonWidth, buttonY, buttonWidth, "Done", mouseX, mouseY);
    }

    private void renderModalButton(GuiGraphics gui, int x, int y, int width, String text,
                                   int mouseX, int mouseY) {
        boolean hover = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + 18;
        gui.fill(x, y, x + width, y + 18, hover ? 0xFF505675 : 0xFF303442);
        gui.renderOutline(x, y, width, 18, 0xFF6A7080);
        drawCentered(gui, text, x + width / 2, y + 5, 0xFFFFFFFF);
    }

    private void tapTempo() {
        long now = System.nanoTime();
        if (!tempoTaps.isEmpty() && now - tempoTaps.peekLast() > 2_000_000_000L) tempoTaps.clear();
        tempoTaps.addLast(now);
        if (tempoTaps.size() < 3) {
            setStatus("Tap BPM: " + tempoTaps.size() + "/3");
            return;
        }
        long first = tempoTaps.peekFirst();
        long last = tempoTaps.peekLast();
        double measured = 60_000_000_000.0 * (tempoTaps.size() - 1) / (last - first);
        tappedBpm = Math.round(measured * 100.0) / 100.0;
        tempoTaps.clear();
        setStatus(String.format(Locale.ROOT, "Tapped BPM: %.2f", tappedBpm));
    }

    private void applySuggestedBpm() {
        if (!tempoTaps.isEmpty() && tempoTaps.size() < 3) {
            setStatus("Tap BPM needs 3 taps (" + tempoTaps.size() + "/3)");
            return;
        }
        double bpm = tappedBpm > 0 ? tappedBpm
                : instWaveform == null ? 0 : instWaveform.estimatedBpm();
        if (bpm <= 0) { setStatus("Need more taps or decoded onset markers"); return; }
        chart.startBpm = Math.round(bpm * 100.0) / 100.0;
        chart.rebuildBpmMap();
        conductor = new Conductor(chart);
        setStatus("Applied BPM " + trim(chart.startBpm));
        rebuildUi();
    }

    private void addBookmark() {
        bookmarkDraftTime = viewPositionMs;
        bookmarkDraftName = "";
        beginBookmarkText(1, "Bookmark " + (chart.bookmarks.size() + 1));
    }

    private void beginBookmarkText(int stage, String initial) {
        bookmarkTextStage = stage;
        bookmarkTextDialogOpen = true;
        int boxW = Math.min(220, Math.max(160, width - 24));
        int x0 = (width - boxW) / 2;
        int y0 = (height - 56) / 2;
        bookmarkTextField = new EditBox(font, x0 + 10, y0 + 22,
                boxW - 20, 16, Component.literal(stage == 1 ? "Bookmark name" : "Bookmark comment"));
        bookmarkTextField.setMaxLength(256);
        bookmarkTextField.setValue(initial == null ? "" : initial);
        setFocused(bookmarkTextField);
        bookmarkTextField.setFocused(true);
        int end = bookmarkTextField.getValue().length();
        bookmarkTextField.setCursorPosition(end);
        bookmarkTextField.setHighlightPos(end);
        openMenu = TopMenu.NONE;
    }

    private void commitBookmarkText() {
        String raw = bookmarkTextField == null ? "" : bookmarkTextField.getValue();
        if (bookmarkTextStage == 1) {
            bookmarkDraftName = raw.trim();
            if (bookmarkDraftName.isBlank()) bookmarkDraftName = "Bookmark";
            beginBookmarkText(2, "");
            return;
        }
        chart.bookmarks.add(new SongChart.Bookmark(
                bookmarkDraftTime, bookmarkDraftName, raw.trim()));
        bookmarkTextDialogOpen = false;
        bookmarkTextField = null;
        setStatus("Bookmark added at " + timeLabel(bookmarkDraftTime));
        rebuildUi();
    }

    private void cancelBookmarkText() {
        bookmarkTextDialogOpen = false;
        bookmarkTextField = null;
        bookmarkDraftName = "";
        rebuildUi();
    }

    private void renderBookmarkTextDialog(GuiGraphics gui, int mouseX, int mouseY) {
        gui.fill(0, 0, width, height, 0xF20A0A10);
        int boxW = Math.min(220, Math.max(160, width - 24));
        int boxH = 56;
        int x0 = (width - boxW) / 2;
        int y0 = (height - boxH) / 2;
        gui.fill(x0, y0, x0 + boxW, y0 + boxH, 0xF0101018);
        gui.renderOutline(x0, y0, boxW, boxH, 0xFFFFEE66);
        String label = bookmarkTextStage == 1 ? "Bookmark name" : "Bookmark comment (optional)";
        draw(gui, label, x0 + 10, y0 + 8, 0xFFFFEE66);
        if (bookmarkTextField != null) {
            bookmarkTextField.setX(x0 + 10);
            bookmarkTextField.setY(y0 + 22);
            bookmarkTextField.setWidth(boxW - 20);
            bookmarkTextField.render(gui, mouseX, mouseY, 0);
        }
        draw(gui, "Enter = OK   ·   Esc = Cancel", x0 + 10, y0 + boxH - 11, 0xFF9AA4B2);
    }

    private void seekBookmark(int direction) {
        if (chart.bookmarks.isEmpty()) { setStatus("No bookmarks"); return; }
        List<SongChart.Bookmark> ordered = chart.bookmarks.stream()
                .sorted(java.util.Comparator.comparingDouble(mark -> mark.timeMs)).toList();
        SongChart.Bookmark target = direction > 0
                ? ordered.stream().filter(mark -> mark.timeMs > viewPositionMs + 1).findFirst().orElse(ordered.get(0))
                : ordered.reversed().stream().filter(mark -> mark.timeMs < viewPositionMs - 1).findFirst().orElse(ordered.get(ordered.size() - 1));
        seek(target.timeMs);
        setStatus(target.name + (target.comment.isBlank() ? "" : ": " + target.comment));
    }

    private void deleteNearestBookmark() {
        SongChart.Bookmark nearest = chart.bookmarks.stream().min(java.util.Comparator.comparingDouble(
                mark -> Math.abs(mark.timeMs - viewPositionMs))).orElse(null);
        if (nearest == null) { setStatus("No bookmarks"); return; }
        chart.bookmarks.remove(nearest);
        setStatus("Deleted bookmark: " + nearest.name);
        rebuildUi();
    }

    private void handleLoopPlayback() {
        if (!loopEnabled || loopStartMs < 0 || loopEndMs <= loopStartMs || !isPlaying()
                || viewPositionMs < loopEndMs) return;
        double startBeat = conductor.beatAt(loopStartMs);
        double restartBeat = Math.max(0, startBeat - preRollBeats);
        seek(conductor.timeOfBeat(restartBeat));
    }

    private double visualPositionMs() {
        return Double.isNaN(displayPositionMs) ? viewPositionMs : displayPositionMs;
    }

    private void updateViewportTween() {
        if (Double.isNaN(displayPositionMs)) displayPositionMs = viewPositionMs;
        if (!viewportTweenActive) {
            displayPositionMs = viewPositionMs;
            return;
        }
        double progress = (System.nanoTime() - viewportTweenStartNano) / (double) VIEWPORT_TWEEN_NANOS;
        if (progress >= 1) {
            displayPositionMs = viewPositionMs;
            viewportTweenActive = false;
            return;
        }
        double eased = Easing.apply("expoOut", progress);
        displayPositionMs = viewportTweenFromMs + (viewPositionMs - viewportTweenFromMs) * eased;
    }

    private void beginViewportTween() {
        if (Double.isNaN(displayPositionMs)) displayPositionMs = viewPositionMs;
        viewportTweenFromMs = displayPositionMs;
        viewportTweenStartNano = System.nanoTime();
        viewportTweenActive = Math.abs(viewPositionMs - viewportTweenFromMs) > 0.01;
        if (!viewportTweenActive) displayPositionMs = viewPositionMs;
    }

    private void togglePlayback() {
        commitVisibleFields();
        if (audio == null) {
            setStatus("No Inst.ogg is available for playback");
            return;
        }
        if (isPlaying()) {
            if (leadInStartNano >= 0) {
                leadInStartNano = -1;
            } else {
                viewPositionMs = audioToView(audio.positionMs());
                audio.pause();
            }
            if (vortex) snapPlayheadToGrid();
        } else {
            beginPlayback();
        }
    }

    private void beginPlayback() {
        resetHitsoundIndex(viewPositionMs);
        snapshotSchedulerNotes();
        tickScheduler().seek(viewPositionMs);
        schedulerActive = false;
        if (viewToAudio(viewPositionMs) < 0) {
            // Positive offset: the song has not started yet, so play silence and
            // let the wall clock carry the playhead until the audio catches up.
            if (audio.isStarted() && !audio.isPaused()) audio.pause();
            leadInBaseView = viewPositionMs;
            leadInStartNano = System.nanoTime();
        } else {
            startAudioAt(viewToAudio(viewPositionMs));
        }
    }

    private void startAudioAt(double audioMs) {
        if (!audio.isStarted()) audio.start();
        else if (audio.isPaused()) audio.resume();
        audio.seekMs(audioMs);
    }

    /**
     * Full playtest is only meaningful for a chart opened from a Funkin' Machine.
     * The machine supplies the stage anchor and facing that place the performers
     * and the camera; the block is re-checked because it can be broken or replaced
     * while the editor is open, and a missing one would silently build the stage
     * facing north around the wrong spot.
     */
    private boolean canPlaytest() {
        if (sourceMachinePos == null || minecraft == null || minecraft.level == null) return false;
        return minecraft.level.getBlockState(sourceMachinePos)
                .hasProperty(FunkinMachineBlock.FACING);
    }

    /** F12: play the chart's notes inside the editor, starting at the playhead. */
    private void enterPreview() {
        if (audio == null) {
            setStatus("No Inst.ogg is available to preview");
            return;
        }
        commitVisibleFields();
        setFocused(null);
        openMenu = TopMenu.NONE;
        previewMode = true;
        previewHitNotes.clear();
        java.util.Arrays.fill(previewFlashUntil, 0);
        if (!isPlaying()) beginPlayback();
    }

    /** Registers a manual hit on the nearest unhit player note in this lane. */
    private void previewHitLane(int lane) {
        previewFlashUntil[4 + lane] = System.currentTimeMillis() + 120;
        double now = viewPositionMs;
        SongChart.Note best = null;
        double bestDist = 180; // ms hit window
        for (SongChart.Note note : chart.notes) {
            if (!note.playerSide || note.lane != lane || previewHitNotes.contains(note)) continue;
            double dist = Math.abs(note.timeMs - now);
            if (dist < bestDist) {
                bestDist = dist;
                best = note;
            }
        }
        if (best != null) previewHitNotes.add(best);
    }

    private void exitPreview() {
        if (!previewMode) return;
        previewMode = false;
        previewHitNotes.clear();
        if (isPlaying()) {
            if (leadInStartNano >= 0) {
                leadInStartNano = -1;
            } else if (audio != null) {
                viewPositionMs = audioToView(audio.positionMs());
                audio.pause();
            }
        }
        rebuildUi();
    }

    private void launchPlaytest(boolean fromCurrentTime, boolean preview) {
        commitVisibleFields();
        if (!canPlaytest()) {
            setStatus("Playtest needs a Funkin' Machine — use F12 to preview the notes");
            return;
        }
        if (entry == null || entry.instFor(loadedDifficulty) == null) {
            setStatus("A valid Inst.ogg is required to playtest");
            return;
        }
        SongPlayer playtestAudio = new SongPlayer();
        try {
            playtestAudio.load(entry.instFor(loadedDifficulty),
                    chart.needsVoices ? entry.voicesFor(loadedDifficulty) : null,
                    chart.needsVoices ? entry.voicesPlayerFor(loadedDifficulty) : null,
                    chart.needsVoices ? entry.voicesOpponentFor(loadedDifficulty) : null);
            if (fromCurrentTime) playtestAudio.setPlaybackRate(playbackRate);
            double startMs = fromCurrentTime ? viewPositionMs : 0;
            // Guarded by canPlaytest above. Falling back to the player's own
            // position used to put the stage anchor on top of the performer,
            // which is what broke the camera and the player/opponent placement.
            BlockPos machine = sourceMachinePos;
            String resourceSongId = entry.id == null || entry.id.isBlank()
                    ? (requestedSongId == null ? chart.title : requestedSongId) : entry.id;
            Path resourceFolder = entry.folder != null ? entry.folder : suppliedSongFolder;
            minecraft.setScreen(GameplayScreen.editorPlaytest(machine, chart, playtestAudio, startMs,
                    preview, resourceSongId, resourceFolder, entry,
                    () -> recreateEditor(startMs), sourcePolicy));
            playtestAudio = null; // GameplayScreen owns it now.
        } catch (Exception e) {
            if (playtestAudio != null) playtestAudio.dispose();
            setStatus("Playtest failed: " + e.getMessage());
            FnfMod.LOGGER.warn("Editor playtest failed: {}", e.toString());
        }
    }

    private ChartEditorScreen recreateEditor(double positionMs) {
        ChartEditorScreen editor = new ChartEditorScreen(requestedSongId, loadedDifficulty, chart,
                suppliedSongFolder, originalDirectory, sourceMachinePos, sourcePolicy);
        editor.viewPositionMs = Math.max(0, positionMs);
        editor.snapIndex = snapIndex;
        editor.gridZoomIndex = gridZoomIndex;
        editor.playbackRate = playbackRate;
        editor.vortex = vortex;
        return editor;
    }

    /**
     * The chart offset shifts the song against the notes, so applying it here lets
     * the editor hear the alignment it will play with. view = note-grid time;
     * audio buffer = note-grid time − offset (same relationship as gameplay).
     */
    private double audioToView(double audioPositionMs) {
        return audioPositionMs + chart.offsetMs;
    }

    private double viewToAudio(double viewMs) {
        return viewMs - chart.offsetMs;
    }

    private void seek(double timeMs) {
        updateViewportTween();
        leadInStartNano = -1; // scrubbing leaves any silent lead-in
        viewPositionMs = Math.max(0, timeMs);
        beginViewportTween();
        if (tickScheduler != null) tickScheduler.seek(viewPositionMs); // don't replay skipped notes
        if (audio != null && audio.isStarted()) audio.seekMs(viewToAudio(viewPositionMs));
        resetHitsoundIndex(viewPositionMs);
    }

    private void scrub(double steps) {
        commitVisibleFields();
        if (isPlaying()) audio.pause();
        double beat = conductor.beatAt(Math.max(0, viewPositionMs));
        seek(conductor.timeOfBeat(Math.max(0, beat - steps * snapStepBeats())));
    }

    private void changeSection(int delta) {
        commitVisibleFields();
        if (isPlaying()) audio.pause();
        int target = Math.max(0, sectionIndexAt(viewPositionMs) + delta);
        ensureSection(target);
        clearSelection();
        seek(chart.sectionStartMs(target));
        rebuildUi();
    }

    private void snapPlayheadToGrid() {
        double beat = conductor.beatAt(Math.max(0, viewPositionMs));
        double snapped = Math.max(0, Math.round(beat / snapStepBeats()) * snapStepBeats());
        seek(conductor.timeOfBeat(snapped));
    }

    private void resetHitsoundIndex(double timeMs) {
        chart.sortNotes();
        hitsoundIndex = 0;
        while (hitsoundIndex < chart.notes.size() && chart.notes.get(hitsoundIndex).timeMs <= timeMs) hitsoundIndex++;
    }

    private void playCrossedHitsounds(double timeMs) {
        ClientOptions options = ClientOptions.get();
        if (!options.editorHitsoundPlayer && !options.editorHitsoundOpponent) {
            resetHitsoundIndex(timeMs);
            return;
        }
        int played = 0;
        while (hitsoundIndex < chart.notes.size() && chart.notes.get(hitsoundIndex).timeMs <= timeMs) {
            SongChart.Note note = chart.notes.get(hitsoundIndex++);
            if ((note.playerSide ? options.editorHitsoundPlayer : options.editorHitsoundOpponent) && played++ < 4) playTick();
        }
    }

    private void playTick() {
        if (!ClientOptions.get().hitsound.isEmpty()) {
            com.fnfmod.client.audio.HitsoundPlayer.play();
        } else {
            minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                    net.minecraft.sounds.SoundEvents.NOTE_BLOCK_HAT.value(), 1.8f));
        }
    }

    /**
     * Precise playback ticks: a background thread plays each note's hitsound the
     * moment the audio clock reaches it, instead of at the next frame. Used only
     * with a configured hitsound (its playback is thread-safe); the no-hitsound
     * NOTE_BLOCK_HAT fallback stays on the frame path since it needs the main-thread
     * sound engine.
     */
    private com.fnfmod.client.input.EditorTickScheduler tickScheduler() {
        if (tickScheduler == null) {
            tickScheduler = new com.fnfmod.client.input.EditorTickScheduler(
                    () -> {
                        if (leadInStartNano >= 0) {
                            return leadInBaseView + (System.nanoTime() - leadInStartNano)
                                    / 1_000_000.0 * playbackRate;
                        }
                        SongPlayer a = audio;
                        return a == null ? viewPositionMs
                                : a.positionMsAt(System.nanoTime()) + chart.offsetMs;
                    },
                    playerSide -> {
                        ClientOptions o = ClientOptions.get();
                        if (playerSide ? o.editorHitsoundPlayer : o.editorHitsoundOpponent) {
                            com.fnfmod.client.audio.HitsoundPlayer.play();
                        }
                    });
        }
        return tickScheduler;
    }

    /** Feeds the scheduler the current notes in chart (time-sorted) order. */
    private void snapshotSchedulerNotes() {
        List<SongChart.Note> notes = chart.notes;
        double[] times = new double[notes.size()];
        boolean[] sides = new boolean[notes.size()];
        for (int i = 0; i < notes.size(); i++) {
            times[i] = notes.get(i).timeMs;
            sides[i] = notes.get(i).playerSide;
        }
        tickScheduler().setNotes(times, sides);
        snapshotMetronomeTicks();
    }

    private void snapshotMetronomeTicks() {
        if (!ClientOptions.get().editorMetronome) {
            tickScheduler().setMetronome(new double[0], new boolean[0], null);
            return;
        }
        double endTime = Math.max(chart.lastNoteTimeMs(), chart.sectionStartMs(chart.sections.size()));
        if (audio != null) endTime = Math.max(endTime, audioToView(audio.durationMs()));
        double endBeat = Math.max(0, conductor.beatAt(Math.max(0, endTime))) + 1.0e-6;
        List<Double> times = new ArrayList<>();
        List<Boolean> accents = new ArrayList<>();
        List<MeterSegment> segments = meterSegments();
        for (int i = 0; i < segments.size(); i++) {
            MeterSegment segment = segments.get(i);
            double segmentEnd = i + 1 < segments.size()
                    ? Math.min(endBeat, segments.get(i + 1).startBeat()) : endBeat;
            double pulse = segment.signature().pulseBeats();
            long first = Math.max(0, (long) Math.ceil(-1.0e-7 / pulse));
            for (long index = first; ; index++) {
                double beat = segment.startBeat() + index * pulse;
                if (beat >= segmentEnd - 1.0e-7 || beat > endBeat) break;
                if (beat < -1.0e-7) continue;
                times.add(conductor.timeOfBeat(Math.max(0, beat)));
                accents.add(index % segment.signature().numerator() == 0);
            }
        }
        double[] tickTimes = new double[times.size()];
        boolean[] tickAccents = new boolean[accents.size()];
        for (int i = 0; i < times.size(); i++) {
            tickTimes[i] = times.get(i);
            tickAccents[i] = accents.get(i);
        }
        tickScheduler().setMetronome(tickTimes, tickAccents, accent -> {
            ClientOptions options = ClientOptions.get();
            if (options.editorMetronome) {
                com.fnfmod.client.audio.HitsoundPlayer.playMetronome(accent,
                        (float) Mth.clamp(options.editorMetronomeVolume, 0.0, 1.0));
            }
        });
    }

    private List<MeterSegment> meterSegments() {
        List<MeterSegment> out = new ArrayList<>();
        SongChart.TimeSignature signature = chart.startingTimeSignature();
        out.add(new MeterSegment(0, signature));
        double beat = 0;
        for (SongChart.Section section : chart.sections) {
            if (section.changeTimeSignature) {
                signature = new SongChart.TimeSignature(section.timeSignatureNumerator,
                        section.timeSignatureDenominator);
                if (Math.abs(beat - out.get(out.size() - 1).startBeat()) < 1.0e-7) {
                    out.set(out.size() - 1, new MeterSegment(beat, signature));
                } else {
                    out.add(new MeterSegment(beat, signature));
                }
            }
            beat += Math.max(0.25, section.sectionBeats);
        }
        return out;
    }

    private SongChart.TimeSignature meterAtBeat(double beat) {
        SongChart.TimeSignature signature = chart.startingTimeSignature();
        for (MeterSegment segment : meterSegments()) {
            if (segment.startBeat() > beat + 1.0e-7) break;
            signature = segment.signature();
        }
        return signature;
    }

    /** Pauses the precise scheduler; safe to call when it was never created. */
    private void deactivateScheduler() {
        if (schedulerActive && tickScheduler != null) tickScheduler.setActive(false);
        schedulerActive = false;
    }

    // --------------------------------------------------------------------- Input

    private boolean shiftDown() {
        return hasShiftDown();
    }

    private void importSongFiles() {
        commitVisibleFields();
        String id = sanitizeId(saveId == null || saveId.isBlank() ? chart.title : saveId);
        if (id.isBlank()) id = "unnamed";
        String pack = sanitizeModFolder(importModName == null || importModName.isBlank()
                ? id + "-mod" : importModName);
        if (pack.isBlank()) pack = id + "-mod";
        Path target = SongLibrary.modsDir().resolve(pack).toAbsolutePath().normalize();
        SongImportService.Request request = new SongImportService.Request(id, loadedDifficulty,
                chart, entry, originalDirectory, eventDefinitionRoot, target);
        if (!SongImportService.canImport(request)) {
            setStatus("Could not find the song's source mod");
            return;
        }
        try {
            SongImportService.Result result = SongImportService.importCompleteSong(request);
            SongLibrary.rescan();
            entry = SongLibrary.get(id);
            eventDefinitionRoot = result.targetModDirectory();
            reloadNoteTextures();
            setStatus("Imported " + result.copiedFiles() + " basic file(s) into mods/"
                    + result.targetModDirectory().getFileName());
        } catch (Exception error) {
            setStatus("Song import failed: " + error.getMessage());
            FnfMod.LOGGER.error("Could not import complete song {}", id, error);
        }
    }

    private boolean altDown() {
        return hasAltDown();
    }

    private boolean controlDown() {
        return hasControlDown();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (missingSavingFolderWarning != null) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_ENTER
                    || keyCode == GLFW.GLFW_KEY_KP_ENTER || keyCode == GLFW.GLFW_KEY_SPACE) {
                dismissMissingSavingFolderWarning();
            }
            return true;
        }
        if (instColorPickerOpen) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) closeInstColorPicker(false);
            else if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                closeInstColorPicker(true);
            } else if (instColorHexField != null) {
                instColorHexField.keyPressed(keyCode, scanCode, modifiers);
            }
            return true;
        }
        if (bookmarkTextDialogOpen) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) commitBookmarkText();
            else if (keyCode == GLFW.GLFW_KEY_ESCAPE) cancelBookmarkText();
            else if (bookmarkTextField != null) bookmarkTextField.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        if (eventDocumentationVisible) {
            int step = keyCode == GLFW.GLFW_KEY_PAGE_UP || keyCode == GLFW.GLFW_KEY_PAGE_DOWN ? 8 : 1;
            if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_F1
                    || keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                eventDocumentationVisible = false;
            } else if (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_PAGE_UP) {
                eventDocumentationScroll = Math.max(0, eventDocumentationScroll - step);
            } else if (keyCode == GLFW.GLFW_KEY_DOWN || keyCode == GLFW.GLFW_KEY_PAGE_DOWN) {
                eventDocumentationScroll = Math.min(eventDocumentationMaxScroll(), eventDocumentationScroll + step);
            }
            return true;
        }
        if (previewMode) {
            int previewLane = com.fnfmod.client.FnfKeys.laneForKey(keyCode, scanCode);
            if (previewLane >= 0) {
                previewHitLane(previewLane);
                return true;
            }
            switch (keyCode) {
                case GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER,
                        GLFW.GLFW_KEY_F12 -> exitPreview();
                case GLFW.GLFW_KEY_SPACE -> togglePlayback();
                case GLFW.GLFW_KEY_M -> setMetronomeEnabled(!ClientOptions.get().editorMetronome);
                case GLFW.GLFW_KEY_LEFT_BRACKET ->
                        setPlaybackRate(altDown() ? 1.0f : playbackRate - 0.1f);
                case GLFW.GLFW_KEY_RIGHT_BRACKET ->
                        setPlaybackRate(altDown() ? 1.0f : playbackRate + 0.1f);
                default -> { }
            }
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_F1) {
            helpVisible = !helpVisible;
            return true;
        }
        if (helpVisible) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) helpVisible = false;
            return true;
        }
        if (getFocused() instanceof EditBox) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                commitVisibleFields();
                setFocused(null);
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        if (controlDown()) {
            switch (keyCode) {
                case GLFW.GLFW_KEY_Z -> undoNotes();
                case GLFW.GLFW_KEY_Y -> redoNotes();
                case GLFW.GLFW_KEY_X -> { copySelectedNotes(); deleteSelectedNotes(); }
                case GLFW.GLFW_KEY_C -> copySelectedNotes();
                case GLFW.GLFW_KEY_V -> pasteSelectedNotes();
                case GLFW.GLFW_KEY_A -> selectSectionNotes();
                case GLFW.GLFW_KEY_S -> saveChart();
                default -> { return super.keyPressed(keyCode, scanCode, modifiers); }
            }
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_F12) { enterPreview(); return true; }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            if (canPlaytest()) launchPlaytest(shiftDown(), false);
            else setStatus("Playtest needs a Funkin' Machine — use F12 to preview the notes");
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_LEFT_BRACKET || keyCode == GLFW.GLFW_KEY_RIGHT_BRACKET) {
            if (altDown()) setPlaybackRate(1.0f);
            else setPlaybackRate(playbackRate + (keyCode == GLFW.GLFW_KEY_RIGHT_BRACKET ? 0.1f : -0.1f));
            if (activeTab == EditorTab.CHARTING) rebuildUi();
            return true;
        }
        if (vortex && keyCode >= GLFW.GLFW_KEY_1 && keyCode <= GLFW.GLFW_KEY_8) {
            int column = keyCode - GLFW.GLFW_KEY_1;
            double beat = conductor.beatAt(Math.max(0, viewPositionMs));
            double snapped = Math.max(0, Math.round(beat / snapStepBeats()) * snapStepBeats());
            toggleNote(column, conductor.timeOfBeat(snapped), false);
            return true;
        }

        double moveMultiplier = (shiftDown() ? 4.0 : 1.0) / (altDown() ? 4.0 : 1.0);
        return switch (keyCode) {
            case GLFW.GLFW_KEY_A -> { changeSection(-(shiftDown() ? 4 : 1)); yield true; }
            case GLFW.GLFW_KEY_D -> { changeSection(shiftDown() ? 4 : 1); yield true; }
            case GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_UP -> { scrub(moveMultiplier); yield true; }
            case GLFW.GLFW_KEY_S, GLFW.GLFW_KEY_DOWN -> { scrub(-moveMultiplier); yield true; }
            case GLFW.GLFW_KEY_LEFT -> { changeSnap(-1); yield true; }
            case GLFW.GLFW_KEY_RIGHT -> { changeSnap(1); yield true; }
            case GLFW.GLFW_KEY_SPACE -> { togglePlayback(); yield true; }
            case GLFW.GLFW_KEY_M -> {
                setMetronomeEnabled(!ClientOptions.get().editorMetronome);
                if (activeTab == EditorTab.CHARTING) rebuildUi();
                yield true;
            }
            case GLFW.GLFW_KEY_I -> { loopStartMs = viewPositionMs; rebuildUi(); yield true; }
            case GLFW.GLFW_KEY_O -> { loopEndMs = viewPositionMs; rebuildUi(); yield true; }
            case GLFW.GLFW_KEY_L -> { loopEnabled = !loopEnabled; rebuildUi(); yield true; }
            case GLFW.GLFW_KEY_T -> { tapTempo(); if (activeTab == EditorTab.CHARTING) rebuildUi(); yield true; }
            case GLFW.GLFW_KEY_B -> { applySuggestedBpm(); yield true; }
            case GLFW.GLFW_KEY_R -> {
                if (isPlaying()) audio.pause();
                selectedNote = null;
                selectedNotes.clear();
                seek(shiftDown() ? 0 : chart.sectionStartMs(sectionIndexAt(viewPositionMs)));
                rebuildUi();
                yield true;
            }
            case GLFW.GLFW_KEY_Z -> {
                gridZoomIndex = Math.min(GRID_ROWS_PER_BEAT.length - 1, gridZoomIndex + 1);
                setStatus("Grid zoom: " + gridBoxesText() + " boxes/beat");
                yield true;
            }
            case GLFW.GLFW_KEY_X -> {
                gridZoomIndex = Math.max(0, gridZoomIndex - 1);
                setStatus("Grid zoom: " + gridBoxesText() + " boxes/beat");
                yield true;
            }
            case GLFW.GLFW_KEY_V -> {
                vortex = !vortex;
                if (vortex && !isPlaying()) snapPlayheadToGrid();
                rebuildUi();
                yield true;
            }
            case GLFW.GLFW_KEY_E -> { adjustSustain(stepMs() * moveMultiplier); yield true; }
            case GLFW.GLFW_KEY_Q -> { adjustSustain(-stepMs() * moveMultiplier); yield true; }
            case GLFW.GLFW_KEY_DELETE, GLFW.GLFW_KEY_BACKSPACE -> {
                if (activeTab == EditorTab.EVENTS && selectedEvent != null) deleteSelectedEvent();
                else deleteSelectedNotes();
                yield true;
            }
            default -> super.keyPressed(keyCode, scanCode, modifiers);
        };
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (missingSavingFolderWarning != null) return true;
        if (instColorPickerOpen) {
            if (instColorHexField != null) instColorHexField.charTyped(codePoint, modifiers);
            return true;
        }
        if (bookmarkTextDialogOpen) {
            if (bookmarkTextField != null) bookmarkTextField.charTyped(codePoint, modifiers);
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (missingSavingFolderWarning != null) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && warningButtonHovered(mouseX, mouseY)) {
                dismissMissingSavingFolderWarning();
            }
            return true;
        }
        if (instColorPickerOpen) {
            if (instColorHexField != null
                    && instColorHexField.mouseClicked(mouseX, mouseY, button)) {
                setFocused(instColorHexField);
            }
            if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return true;
            int x0 = instPickerX(), y0 = instPickerY(), boxW = instPickerWidth();
            int squareX = x0 + 12, squareY = y0 + 28;
            int hueX = x0 + 12, hueY = y0 + 120, hueWidth = boxW - 24;
            int buttonY = y0 + instPickerHeight() - 26;
            int buttonWidth = (boxW - 28) / 2;
            if (mouseX >= squareX && mouseX < squareX + 80
                    && mouseY >= squareY && mouseY < squareY + 80) {
                instColorDrag = 1;
                updateInstColorPicker(mouseX, mouseY);
            } else if (mouseX >= hueX && mouseX < hueX + hueWidth
                    && mouseY >= hueY && mouseY < hueY + 10) {
                instColorDrag = 2;
                updateInstColorPicker(mouseX, mouseY);
            } else if (mouseY >= buttonY && mouseY < buttonY + 18
                    && mouseX >= x0 + 10 && mouseX < x0 + 10 + buttonWidth) {
                closeInstColorPicker(false);
            } else if (mouseY >= buttonY && mouseY < buttonY + 18
                    && mouseX >= x0 + 18 + buttonWidth
                    && mouseX < x0 + 18 + buttonWidth * 2) {
                closeInstColorPicker(true);
            }
            return true;
        }
        if (bookmarkTextDialogOpen) {
            if (bookmarkTextField != null
                    && bookmarkTextField.mouseClicked(mouseX, mouseY, button)) {
                setFocused(bookmarkTextField);
            }
            return true;
        }
        if (previewMode) return true;
        if (eventDocumentationVisible) {
            if (button == 0) eventDocumentationVisible = false;
            return true;
        }
        if (helpVisible) return true;
        if (insideInfo(mouseX, mouseY)) {
            if (button == 0 && mouseY < infoY + 16) {
                draggingInfo = true;
                dragOffsetX = mouseX - infoX;
                dragOffsetY = mouseY - infoY;
            }
            return true;
        }
        if (super.mouseClicked(mouseX, mouseY, button)) {
            // Minecraft focuses the clicked opener after its callback. Modal
            // fields created by that callback must reclaim focus afterwards.
            if (instColorPickerOpen && instColorHexField != null) {
                setFocused(instColorHexField);
                instColorHexField.setFocused(true);
            } else if (bookmarkTextDialogOpen && bookmarkTextField != null) {
                setFocused(bookmarkTextField);
                bookmarkTextField.setFocused(true);
            }
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT
                    && getFocused() instanceof net.minecraft.client.gui.components.AbstractSliderButton) {
                controlSliderDragging = true;
            }
            return true;
        }
        if (openMenu != TopMenu.NONE) {
            openMenu = TopMenu.NONE;
            rebuildUi();
        }

        int gx = gridX();
        int cw = cellWidth();
        int eventX = gx - cw;
        if ((button == 0 || button == 1) && mouseX >= eventX && mouseX < gx
                && mouseY >= gridTop() && mouseY < gridBottom()) {
            if (button == 1) {
                selectingBox = true;
                selectionStartX = selectionEndX = mouseX;
                selectionStartBeat = selectionEndBeat = selectionBeatAtScreenY(mouseY);
                return true;
            }
            double clickedBeat = yToBeat(mouseY - gridCellHeight() / 2.0);
            SongChart.Event closest = null;
            double closestPixels = Double.MAX_VALUE;
            for (SongChart.Event event : chart.events) {
                double pixels = Math.abs(conductor.beatAt(event.timeMs) - clickedBeat)
                        * effectivePixelsPerBeat();
                if (pixels < closestPixels) {
                    closestPixels = pixels;
                    closest = event;
                }
            }
            boolean hitEvent = closest != null && closestPixels <= Math.max(7, cw / 2.0);
            if (hitEvent) {
                if (!shiftDown() && !altDown()) {
                    recordNoteChange();
                    chart.events.remove(closest);
                    selectedEvents.remove(closest);
                    if (selectedEvent == closest) {
                        selectedEvent = selectedEvents.stream().reduce((a, b) -> b).orElse(null);
                        if (selectedEvent != null) setEventDraft(selectedEvent);
                    }
                    setStatus("Removed event");
                    rebuildUi();
                    return true;
                }
                if (shiftDown()) {
                    if (!selectedEvents.add(closest)) selectedEvents.remove(closest);
                } else {
                    selectedEvents.add(closest);
                }
                selectedEvent = selectedEvents.stream().reduce((a, b) -> b).orElse(null);
                if (selectedEvent != null) setEventDraft(selectedEvent);
                activeTab = EditorTab.EVENTS;
                openMenu = TopMenu.NONE;
                setStatus("Selected event at " + trim(closest.timeMs) + " ms");
                rebuildUi();
            } else {
                double beat = yToBeat(mouseY);
                double snapped = Math.max(0, Math.floor(beat / snapStepBeats() + 1.0e-6) * snapStepBeats());
                addEventAt(conductor.timeOfBeat(snapped));
            }
            return true;
        }
        if (mouseX >= gx && mouseX < gx + 8 * cw && mouseY >= gridTop() && mouseY < gridBottom()) {
            if (button == 1) {
                selectingBox = true;
                selectionStartX = selectionEndX = mouseX;
                selectionStartBeat = selectionEndBeat = selectionBeatAtScreenY(mouseY);
                return true;
            }
            int column = (int) ((mouseX - gx) / cw);
            double beat = yToBeat(mouseY);
            double snapped = Math.max(0, Math.floor(beat / snapStepBeats() + 1.0e-6) * snapStepBeats());
            double time = conductor.timeOfBeat(snapped);
            if (button == 0 && (shiftDown() || altDown())) {
                SongChart.Note found = findNote(column, time, stepMs() / 2.0);
                if (found != null) {
                    if (shiftDown()) toggleSelection(found);
                    else addSelection(found);
                    setStatus("Selected " + selectedNotes.size() + " note(s)");
                    if (activeTab == EditorTab.NOTE) rebuildUi();
                }
            } else if (button == 0) {
                toggleNote(column, time, false);
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (missingSavingFolderWarning != null) return true;
        if (instColorPickerOpen) {
            if (instColorDrag != 0) updateInstColorPicker(mouseX, mouseY);
            return true;
        }
        if (bookmarkTextDialogOpen) {
            if (bookmarkTextField != null) bookmarkTextField.mouseDragged(mouseX, mouseY, button, dragX, dragY);
            return true;
        }
        if (draggingInfo && button == 0) {
            infoX = Mth.clamp((int) Math.round(mouseX - dragOffsetX), 0, Math.max(0, width - infoWidth()));
            infoY = Mth.clamp((int) Math.round(mouseY - dragOffsetY), 22, Math.max(22, height - infoHeight() - 12));
            return true;
        }
        if (selectingBox && button == 1) {
            selectionEndX = Mth.clamp(mouseX, gridX() - cellWidth(), gridX() + 8 * cellWidth());
            selectionEndBeat = selectionBeatAtScreenY(Mth.clamp(mouseY, gridTop(), gridBottom()));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (missingSavingFolderWarning != null) return true;
        if (instColorPickerOpen) {
            instColorDrag = 0;
            return true;
        }
        if (bookmarkTextDialogOpen) return true;
        if (draggingInfo && button == 0) {
            draggingInfo = false;
            return true;
        }
        if (selectingBox && button == 1) {
            selectionEndX = Mth.clamp(mouseX, gridX() - cellWidth(), gridX() + 8 * cellWidth());
            selectionEndBeat = selectionBeatAtScreenY(Mth.clamp(mouseY, gridTop(), gridBottom()));
            finishBoxSelection();
            selectingBox = false;
            return true;
        }
        boolean handled = super.mouseReleased(mouseX, mouseY, button);
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) controlSliderDragging = false;
        return handled;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (missingSavingFolderWarning != null) return true;
        if (instColorPickerOpen) return true;
        if (bookmarkTextDialogOpen) return true;
        if (previewMode) return true;
        if (eventDocumentationVisible) {
            int direction = scrollY > 0 ? -1 : scrollY < 0 ? 1 : 0;
            eventDocumentationScroll = Mth.clamp(eventDocumentationScroll + direction,
                    0, eventDocumentationMaxScroll());
            return true;
        }
        if (helpVisible) return true;
        if (eventDropdownOpen) {
            int direction = scrollY > 0 ? -1 : scrollY < 0 ? 1 : 0;
            if (direction != 0) {
                eventDropdownScroll = Mth.clamp(eventDropdownScroll + direction,
                        0, Math.max(0, eventTypes.size() - 1));
                rebuildUi();
            }
            return true;
        }
        if (insideInfo(mouseX, mouseY)) return true;
        if (selectingBox && mouseX >= gridX() - cellWidth() && mouseX < gridX() + 8 * cellWidth()) {
            double multiplier = (shiftDown() ? 4.0 : 1.0) / (altDown() ? 4.0 : 1.0);
            scrub(scrollY * multiplier);
            selectionEndX = Mth.clamp(mouseX, gridX() - cellWidth(), gridX() + 8 * cellWidth());
            selectionEndBeat = selectionBeatAtScreenY(Mth.clamp(mouseY, gridTop(), gridBottom()));
            return true;
        }
        if (mouseX >= gridX() - cellWidth() && mouseX < gridX() + 8 * cellWidth()
                && mouseY >= gridTop() && mouseY < gridBottom()) {
            double multiplier = (shiftDown() ? 4.0 : 1.0) / (altDown() ? 4.0 : 1.0);
            scrub(scrollY * multiplier);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void finishBoxSelection() {
        double left = Math.min(selectionStartX, selectionEndX);
        double right = Math.max(selectionStartX, selectionEndX);
        double firstBeat = Math.min(selectionStartBeat, selectionEndBeat);
        double lastBeat = Math.max(selectionStartBeat, selectionEndBeat);
        if (!shiftDown() && !altDown()) clearSelection();
        int gx = gridX();
        int cw = cellWidth();
        for (SongChart.Note note : chart.notes) {
            int column = (note.playerSide ? 4 : 0) + note.lane;
            double x = gx + column * cw + cw / 2.0;
            double noteBeat = conductor.beatAt(note.timeMs);
            if (x >= left && x <= right && noteBeat >= firstBeat && noteBeat <= lastBeat) {
                if (altDown()) selectedNotes.remove(note);
                else selectedNotes.add(note);
            }
        }
        double eventCenterX = gx - cw / 2.0;
        if (eventCenterX >= left && eventCenterX <= right) {
            for (SongChart.Event event : chart.events) {
                double eventBeat = conductor.beatAt(event.timeMs);
                if (eventBeat < firstBeat || eventBeat > lastBeat) continue;
                if (altDown()) selectedEvents.remove(event);
                else selectedEvents.add(event);
            }
        }
        selectedNote = selectedNotes.stream().reduce((a, b) -> b).orElse(null);
        selectedEvent = selectedEvents.stream().reduce((a, b) -> b).orElse(null);
        if (selectedEvent != null) setEventDraft(selectedEvent);
        setStatus("Selected " + selectedNotes.size() + " note(s), "
                + selectedEvents.size() + " event(s)");
        if (activeTab == EditorTab.NOTE || activeTab == EditorTab.EVENTS) rebuildUi();
    }

    // --------------------------------------------------------------------- Rendering

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        renderBackground(gui, mouseX, mouseY, partialTick);

        if (missingSavingFolderWarning != null) {
            gui.pose().pushPose();
            gui.pose().translate(0, 0, 1000);
            renderMissingSavingFolderWarning(gui, mouseX, mouseY);
            gui.pose().popPose();
            return;
        }

        boolean preciseHitsounds = !ClientOptions.get().hitsound.isEmpty();
        boolean scheduledTicks = preciseHitsounds || ClientOptions.get().editorMetronome;
        if (leadInStartNano >= 0) {
            double elapsed = (System.nanoTime() - leadInStartNano) / 1_000_000.0 * playbackRate;
            viewPositionMs = Math.max(0, leadInBaseView + elapsed);
            if (scheduledTicks) {
                if (!schedulerActive) tickScheduler().seek(viewPositionMs);
                tickScheduler().setActive(true);
                schedulerActive = true;
            } else {
                deactivateScheduler();
            }
            if (!preciseHitsounds) playCrossedHitsounds(viewPositionMs);
            if (viewToAudio(viewPositionMs) >= 0) {
                leadInStartNano = -1;
                startAudioAt(viewToAudio(viewPositionMs));
            }
        } else if (isPlaying()) {
            viewPositionMs = audioToView(audio.positionMs());
            // Metronome and configured hitsounds use the high-rate audio-clock
            // scheduler. Vanilla note-tick fallback remains frame based.
            if (scheduledTicks) {
                if (!schedulerActive) tickScheduler().seek(viewPositionMs);
                tickScheduler().setActive(true);
                schedulerActive = true;
            } else {
                deactivateScheduler();
            }
            if (!preciseHitsounds) playCrossedHitsounds(viewPositionMs);
        } else {
            deactivateScheduler();
        }
        handleLoopPlayback();
        updateViewportTween();
        if (instColorPickerOpen) {
            renderInstColorPicker(gui, mouseX, mouseY);
            return;
        }
        if (bookmarkTextDialogOpen) {
            renderBookmarkTextDialog(gui, mouseX, mouseY);
            return;
        }
        if (previewMode) {
            renderPreview(gui);
            return;
        }

        // Event Help is fully modal: skip the whole chart editor so nothing
        // shows through behind the documentation. The chart returns when closed.
        if (eventDocumentationVisible) {
            renderEventDocumentation(gui);
            return;
        }

        // F1 help is modal the same way: hide the chart behind it, game stays
        // faintly visible via renderBackground, and it returns when closed.
        if (helpVisible) {
            renderHelpScreen(gui);
            return;
        }

        refreshSectionUiWhenInputIsIdle();

        renderGrid(gui);
        renderControlPanel(gui);
        renderTopMenuBackground(gui);
        renderLabels(gui);
        renderInlineEventDocumentation(gui);

        draw(gui, "press F1 for help", 8, height - 12, 0xFFFFFFFF);
        if (System.currentTimeMillis() < statusUntil) draw(gui, status, 8, height - 24, 0xFFFFFF66);

        for (var renderable : renderables) renderable.render(gui, mouseX, mouseY, partialTick);
        renderInfoWindow(gui);
    }

    private void renderMissingSavingFolderWarning(GuiGraphics gui, int mouseX, int mouseY) {
        gui.fill(0, 0, width, height, 0xF20A0A10);
        int boxW = Math.min(440, Math.max(240, width - 32));
        int textW = boxW - 24;
        List<FormattedCharSequence> missingLines = font.split(
                Component.literal(missingSavingFolderWarning.toAbsolutePath().normalize().toString()), textW);
        String id = sanitizeId(saveId == null || saveId.isBlank() ? chart.title : saveId);
        if (id.isBlank()) id = "unnamed";
        Path fallback = SongLibrary.songsDir().resolve(id).toAbsolutePath().normalize();
        List<FormattedCharSequence> fallbackLines = font.split(Component.literal(fallback.toString()), textW);
        int boxH = 91 + (missingLines.size() + fallbackLines.size()) * 10;
        int x0 = (width - boxW) / 2;
        int y0 = Math.max(8, (height - boxH) / 2);

        gui.fill(x0, y0, x0 + boxW, y0 + boxH, 0xFF15151D);
        gui.renderOutline(x0, y0, boxW, boxH, 0xFFFFA43A);
        gui.drawCenteredString(font, "Saving folder missing", x0 + boxW / 2, y0 + 11, 0xFFFFB34F);
        int textY = y0 + 30;
        gui.drawString(font, "The assigned chart folder no longer exists:", x0 + 12, textY,
                0xFFE0E0E0, false);
        textY += 12;
        for (FormattedCharSequence line : missingLines) {
            gui.drawString(font, line, x0 + 12, textY, 0xFFFF8C8C, false);
            textY += 10;
        }
        textY += 3;
        gui.drawString(font, "Ctrl+S will save without a dialog to:", x0 + 12, textY,
                0xFFE0E0E0, false);
        textY += 12;
        for (FormattedCharSequence line : fallbackLines) {
            gui.drawString(font, line, x0 + 12, textY, 0xFF9FD3FF, false);
            textY += 10;
        }

        int buttonX = x0 + (boxW - 120) / 2;
        int buttonY = y0 + boxH - 27;
        boolean hovered = warningButtonHovered(mouseX, mouseY);
        gui.fill(buttonX, buttonY, buttonX + 120, buttonY + 18,
                hovered ? 0xFF5B607E : 0xFF353948);
        gui.renderOutline(buttonX, buttonY, 120, 18, 0xFF7C82A5);
        gui.drawCenteredString(font, "Continue", buttonX + 60, buttonY + 5, 0xFFFFFFFF);
    }

    private boolean warningButtonHovered(double mouseX, double mouseY) {
        int boxW = Math.min(440, Math.max(240, width - 32));
        int textW = boxW - 24;
        int missingLines = font.split(Component.literal(
                missingSavingFolderWarning.toAbsolutePath().normalize().toString()), textW).size();
        String id = sanitizeId(saveId == null || saveId.isBlank() ? chart.title : saveId);
        if (id.isBlank()) id = "unnamed";
        int fallbackLines = font.split(Component.literal(SongLibrary.songsDir().resolve(id)
                .toAbsolutePath().normalize().toString()), textW).size();
        int boxH = 91 + (missingLines + fallbackLines) * 10;
        int x0 = (width - boxW) / 2;
        int y0 = Math.max(8, (height - boxH) / 2);
        int buttonX = x0 + (boxW - 120) / 2;
        int buttonY = y0 + boxH - 27;
        return mouseX >= buttonX && mouseX < buttonX + 120
                && mouseY >= buttonY && mouseY < buttonY + 18;
    }

    /**
     * Crossing a section normally rebuilds section-dependent controls. Replacing
     * the widget tree during input drops EditBox focus and a slider's captured
     * drag, so playback defers that rebuild until the current interaction ends.
     */
    private void refreshSectionUiWhenInputIsIdle() {
        int sectionNow = sectionIndexAt(viewPositionMs);
        boolean textEditing = getFocused() instanceof EditBox edit && edit.isFocused();
        if ((textEditing || controlSliderDragging)
                && (sectionNow != shownSection || deferredSectionRefresh)) {
            deferredSectionRefresh = true;
            return;
        }
        if (!deferredSectionRefresh && sectionNow == shownSection) return;

        if (deferredSectionRefresh) commitVisibleFields();
        deferredSectionRefresh = false;
        if (sectionNow != shownSection) rebuildUi();
    }

    /** Notes-only playback preview: a strumline highway at the current playhead. */
    private void renderPreview(GuiGraphics gui) {
        NoteStyle.setDrawAlpha(1f);
        NoteStyle.setMissed(false);
        double now = viewPositionMs;
        // Match gameplay's scroll speed exactly (the inputted Scroll Speed × the
        // player's multiplier, or constant mode) so the preview reads true.
        var opts = ClientOptions.get();
        double effSpeed = opts.constantScrollSpeed ? opts.scrollSpeedMult
                : Math.max(0.01, chart.speed) * opts.scrollSpeedMult;
        double px = 0.45 * effSpeed;
        boolean down = opts.downscroll;
        float size = Math.min(30f, width / 12f);
        int gap = (int) (size + 8);
        int totalW = gap * 8;
        int baseX = width / 2 - totalW / 2 + gap / 2;
        int receptorY = down ? height - 90 : 90;

        // divider between opponent (left four) and player (right four)
        int divX = baseX + 4 * gap - gap / 2 - 4;
        gui.fill(divX, 0, divX + 1, height, 0x33FFFFFF);

        long nowMs = System.currentTimeMillis();
        for (int col = 0; col < 8; col++) {
            int lane = col % 4;
            float cx = baseX + col * gap;
            int state = previewFlashUntil[col] > nowMs ? 2 : 0;
            boolean custom = noteTextures != null && noteTextures.drawReceptor(gui,
                    editorChartNoteTexture(), lane, state, cx, receptorY, size);
            if (!custom) NoteStyle.drawReceptor(gui, lane, cx, receptorY, size, state);
        }

        for (SongChart.Note note : chart.notes) {
            if (previewHitNotes.contains(note)) continue;
            double distHead = (note.timeMs - now) * px;
            double distEnd = (note.timeMs + note.sustainMs - now) * px;
            float y = (float) (down ? receptorY - distHead : receptorY + distHead);
            float yEnd = (float) (down ? receptorY - distEnd : receptorY + distEnd);
            float lo = Math.min(y, yEnd), hi = Math.max(y, yEnd);
            if (hi < -size || lo > height + size) continue;
            int col = (note.playerSide ? 4 : 0) + note.lane;
            float cx = baseX + col * gap;
            if (note.sustainMs > 0) {
                boolean customHold = noteTextures != null && noteTextures.drawHold(gui,
                        editorNoteTexture(note), note.lane, cx, lo, hi, size, yEnd < y);
                if (!customHold) NoteStyle.drawHoldPiece(gui, note.lane, cx, lo, hi, size, yEnd < y);
            }
            if (y > -size && y < height + size) {
                boolean customNote = noteTextures != null && noteTextures.drawNote(gui,
                        editorNoteTexture(note), note.lane, cx, y, size);
                if (!customNote) NoteStyle.drawNote(gui, note.lane, cx, y, size);
            }
        }

        drawCentered(gui, "PREVIEW  —  play your lane keys  •  Esc/Enter exit  •  Space pause",
                width / 2, height - 14, 0xFFFFFF66);
        drawCentered(gui, String.format(Locale.ROOT, "%.1fx", playbackRate),
                width / 2, 8, 0xFFAAAAAA);
    }

    private void renderGrid(GuiGraphics gui) {
        NoteStyle.setDrawAlpha(1f);
        NoteStyle.setMissed(false);
        int gx = gridX();
        int cw = cellWidth();
        int top = gridTop();
        int bottom = gridBottom();
        int gridWidth = cw * 8;
        int eventX = gx - cw;
        float noteSize = Math.min(cw - 3, 24);
        double step = gridStepBeats();

        gui.fill(eventX, top - cw, gx, top, 0xFFD0D0D0);
        gui.fill(eventX, top, gx, bottom, 0xFFB8B8B8);
        gui.fill(gx, top - cw, gx + gridWidth, top, 0xFFE8E8E8);
        gui.fill(gx, top, gx + gridWidth, bottom, 0xFFE0E0E0);
        gui.enableScissor(eventX, top, gx + gridWidth, bottom);

        double topBeat = yToBeat(top);
        double bottomBeat = yToBeat(bottom);
        long firstBand = (long) Math.floor(topBeat / step);
        for (long band = firstBand; band * step <= bottomBeat; band++) {
            int y0 = (int) beatToY(band * step);
            int y1 = (int) beatToY((band + 1) * step);
            int eventColor = (band & 1) == 0 ? 0xFFCBCBCB : 0xFFE4E4E4;
            gui.fill(eventX, Math.max(top, y0), gx, Math.min(bottom, y1), eventColor);
            for (int column = 0; column < 8; column++) {
                int color = ((column + band) & 1) == 0 ? 0xFFCBCBCB : 0xFFE4E4E4;
                gui.fill(gx + column * cw, Math.max(top, y0), gx + (column + 1) * cw, Math.min(bottom, y1), color);
            }
        }

        long firstLine = (long) Math.ceil(Math.max(0, topBeat) / step - 1.0e-6);
        for (long line = firstLine; line * step <= bottomBeat; line++) {
            double beat = line * step;
            int y = (int) beatToY(beat);
            boolean wholeBeat = Math.abs(beat - Math.round(beat)) < 1.0e-6;
            gui.fill(eventX, y, gx + gridWidth, y + 1, wholeBeat ? 0x66444444 : 0x22444444);
        }
        renderMeterLines(gui, eventX, gx + gridWidth, top, bottom, topBeat, bottomBeat);
        renderWaveforms(gui, gx, cw, top, bottom, topBeat, bottomBeat);
        renderEditorMarkers(gui, eventX, gx + gridWidth, top, bottom, topBeat, bottomBeat);

        double bottomTime = conductor.timeOfBeat(Math.max(0, bottomBeat));
        int firstSection = Math.max(0, sectionIndexAt(conductor.timeOfBeat(Math.max(0, topBeat))) - 1);
        for (int section = firstSection; section < 4096; section++) {
            double time = chart.sectionStartMs(section);
            if (time > bottomTime) break;
            int y = (int) beatToY(conductor.beatAt(time));
            if (y >= top - 1 && y <= bottom + 1) gui.fill(eventX, y, gx + gridWidth, y + 1, 0xFF9D3D3D);
        }

        for (SongChart.Event event : chart.events) {
            double beat = conductor.beatAt(event.timeMs);
            if (beat < topBeat - 1 || beat > bottomBeat + 1) continue;
            int eventY = (int) Math.round(beatToY(beat) + gridCellHeight() / 2.0);
            int color = selectedEvents.contains(event) || event == selectedEvent
                    ? 0xFFFFFF44 : event.beforeSong ? 0xFF55DDFF : 0xFFFFA000;
            gui.fill(eventX + 3, eventY - 4, gx - 3, eventY + 4, color);
            drawCentered(gui, "E", eventX + cw / 2, eventY - font.lineHeight / 2, 0xFF201000);
        }

        for (SongChart.Note note : chart.notes) {
            double beat = conductor.beatAt(note.timeMs);
            if (beat < topBeat - 8 || beat > bottomBeat + 1) continue;
            int timeLineY = (int) beatToY(beat);
            int noteY = (int) Math.round(timeLineY + gridCellHeight() / 2.0);
            int column = (note.playerSide ? 4 : 0) + note.lane;
            int x = gx + column * cw;
            if (note.sustainMs > 0) {
                int endY = (int) Math.round(beatToY(conductor.beatAt(
                        note.timeMs + note.sustainMs)) + gridCellHeight() / 2.0);
                boolean customHold = noteTextures != null && noteTextures.drawHold(gui,
                        editorNoteTexture(note), note.lane, x + cw / 2f,
                        Math.min(noteY, endY), Math.max(noteY, endY), noteSize, endY < noteY);
                if (!customHold) {
                    NoteStyle.drawHoldPiece(gui, note.lane, x + cw / 2f,
                            Math.min(noteY, endY), Math.max(noteY, endY), noteSize, endY < noteY);
                }
            }
            if (noteY + noteSize / 2 >= top && noteY - noteSize / 2 <= bottom) {
                boolean customNote = noteTextures != null && noteTextures.drawNote(gui,
                        editorNoteTexture(note), note.lane, x + cw / 2f, noteY, noteSize);
                if (!customNote) NoteStyle.drawNote(gui, note.lane, x + cw / 2f, noteY, noteSize);
                if (selectedNotes.contains(note)) {
                    int radius = (int) noteSize / 2 + 2;
                    gui.renderOutline(x + cw / 2 - radius, noteY - radius, radius * 2, radius * 2, 0xFFFFFFFF);
                }
                if (note.noteType != null && !note.noteType.isEmpty()) draw(gui, "*", x + cw - 6, noteY - 4, 0xFF111111);
            }
        }
        gui.disableScissor();

        gui.fill(eventX - 1, top - cw, eventX + 1, bottom, 0xFF222222);
        gui.fill(gx - 1, top - cw, gx + 1, bottom, 0xFF222222);
        gui.fill(gx + 4 * cw - 1, top - cw, gx + 4 * cw + 1, bottom, 0xFF222222);
        drawCentered(gui, "EV", eventX + cw / 2, top - cw / 2 - font.lineHeight / 2, 0xFF555555);
        String[] glyphs = {"<", "v", "^", ">"};
        for (int column = 0; column < 8; column++) {
            int lane = column % 4;
            float centerX = gx + column * cw + cw / 2f;
            float centerY = top - cw / 2f;
            boolean custom = noteTextures != null && noteTextures.drawReceptor(gui,
                    editorChartNoteTexture(), lane, 0, centerX, centerY, noteSize);
            if (!custom) {
                drawCentered(gui, glyphs[lane], (int) centerX,
                        top - cw / 2 - font.lineHeight / 2, 0xFF8A8A8A);
            }
        }

        int playheadY = centerY();
        gui.fill(eventX, playheadY, gx + gridWidth, playheadY + 1, 0xFFFF3333);

        if (vortex) {
            draw(gui, "VORTEX", gx, top - 10, 0xFFFF66FF);
            int previewY = (int) Math.round(playheadY + gridCellHeight() / 2.0);
            int previewBottom = (int) Math.round(playheadY + gridCellHeight());
            gui.fill(gx, playheadY, gx + gridWidth, previewBottom, 0x22FFFFFF);
            for (int column = 0; column < 8; column++) {
                int centerX = gx + column * cw + cw / 2;
                int lane = column % 4;
                boolean custom = noteTextures != null && noteTextures.drawNote(gui,
                        editorChartNoteTexture(), lane, centerX, previewY, noteSize);
                if (!custom) NoteStyle.drawNote(gui, lane, centerX, previewY, noteSize);
                draw(gui, String.valueOf(column + 1), gx + column * cw + 2,
                        previewBottom - font.lineHeight, 0xFFFFFFFF);
            }
        }
        if (selectingBox) {
            int left = (int) Math.min(selectionStartX, selectionEndX);
            int right = (int) Math.max(selectionStartX, selectionEndX);
            int startY = (int) selectionScreenY(selectionStartBeat);
            int endY = (int) selectionScreenY(selectionEndBeat);
            int topY = Mth.clamp(Math.min(startY, endY), top, bottom);
            int bottomY = Mth.clamp(Math.max(startY, endY), top, bottom);
            gui.fill(left, topY, right, bottomY, 0x5533CCFF);
            gui.renderOutline(left, topY, Math.max(1, right - left), Math.max(1, bottomY - topY), 0xFF66DDFF);
        }
    }

    private void renderMeterLines(GuiGraphics gui, int left, int right, int top, int bottom,
                                  double topBeat, double bottomBeat) {
        List<MeterSegment> segments = meterSegments();
        for (int i = 0; i < segments.size(); i++) {
            MeterSegment segment = segments.get(i);
            double segmentEnd = i + 1 < segments.size()
                    ? segments.get(i + 1).startBeat() : bottomBeat + 1;
            double visibleStart = Math.max(topBeat, segment.startBeat());
            double visibleEnd = Math.min(bottomBeat, segmentEnd);
            if (visibleEnd < visibleStart) continue;
            double pulse = segment.signature().pulseBeats();
            long first = Math.max(0, (long) Math.ceil(
                    (visibleStart - segment.startBeat()) / pulse - 1.0e-7));
            for (long index = first; ; index++) {
                double beat = segment.startBeat() + index * pulse;
                if (beat > visibleEnd + 1.0e-7 || beat >= segmentEnd - 1.0e-7) break;
                int y = (int) beatToY(beat);
                boolean bar = index % segment.signature().numerator() == 0;
                gui.fill(left, y, right, y + (bar ? 2 : 1),
                        bar ? 0xCC8A3FB5 : 0x556E4B82);
            }
        }
    }

    private void renderWaveforms(GuiGraphics gui, int gx, int cw, int top, int bottom,
                                 double topBeat, double bottomBeat) {
        ClientOptions options = ClientOptions.get();
        if (!options.editorWaveforms) return;
        int playerColor = characterColor(chart.player1, false);
        int opponentColor = characterColor(chart.player2, true);
        renderWaveform(gui, instWaveform, gx + 4 * cw, Math.max(3, cw), top, bottom,
                options.editorInstWaveformColor, options.editorOnsetMarkers);
        renderWaveform(gui, opponentWaveform, gx + 2 * cw, Math.max(4, 2 * cw - 3), top, bottom,
                opponentColor, options.editorOnsetMarkers);
        renderWaveform(gui, playerWaveform, gx + 6 * cw, Math.max(4, 2 * cw - 3), top, bottom,
                playerColor, options.editorOnsetMarkers);
    }

    private void renderWaveform(GuiGraphics gui, EditorWaveform.Data data, int centerX, int radius,
                                int top, int bottom, int rgb, boolean markers) {
        if (data == null) return;
        for (int y = top; y < bottom; y += 2) {
            double viewTime = conductor.timeOfBeat(Math.max(0, yToBeat(y)));
            float peak = data.peakAt(viewToAudio(viewTime));
            int half = Math.max(1, Math.round(peak * radius));
            gui.fill(centerX - half, y, centerX + half + 1, Math.min(bottom, y + 2),
                    0x52000000 | (rgb & 0xFFFFFF));
        }
        if (!markers) return;
        double topAudio = viewToAudio(conductor.timeOfBeat(Math.max(0, yToBeat(top))));
        double bottomAudio = viewToAudio(conductor.timeOfBeat(Math.max(0, yToBeat(bottom))));
        double[] onsets = data.onsetsMs();
        int onsetIndex = java.util.Arrays.binarySearch(onsets, topAudio);
        if (onsetIndex < 0) onsetIndex = -onsetIndex - 1;
        for (int i = onsetIndex; i < onsets.length; i++) {
            double onset = onsets[i];
            if (onset > bottomAudio) break;
            int y = (int) beatToY(conductor.beatAt(audioToView(onset)));
            gui.fill(centerX - radius, y, centerX + radius + 1, y + 1,
                    0xA0000000 | (rgb & 0xFFFFFF));
        }
    }

    private int characterColor(String character, boolean opponent) {
        String key = (character == null ? "" : character) + "|"
                + (entry == null || entry.folder == null ? "" : entry.folder.toAbsolutePath());
        if (opponent && key.equals(cachedOpponentColorKey) && cachedOpponentColor >= 0) return cachedOpponentColor;
        if (!opponent && key.equals(cachedPlayerColorKey) && cachedPlayerColor >= 0) return cachedPlayerColor;
        int resolved = resolveCharacterColor(character, opponent);
        if (opponent) { cachedOpponentColorKey = key; cachedOpponentColor = resolved; }
        else { cachedPlayerColorKey = key; cachedPlayerColor = resolved; }
        return resolved;
    }

    private int resolveCharacterColor(String character, boolean opponent) {
        if (entry != null) {
            for (Path root : java.util.Arrays.asList(entry.runtimeRoot(), entry.animationRoot(), entry.folder)) {
                if (root == null) continue;
                for (Path candidate : List.of(root.resolve("characters").resolve(character + ".json"),
                        root.resolve("animations").resolve(character + ".json"))) {
                    if (!Files.isRegularFile(candidate)) continue;
                    int color = CharacterDefinitionFile.load(candidate).healthColor;
                    if (color >= 0) return color;
                }
            }
        }
        int blockified = CharacterAnimations.color(CharacterAnimations.modSet(character),
                opponent ? "opponent" : "player");
        return blockified >= 0 ? blockified : opponent ? 0xAF66CE : 0x31B0D1;
    }

    private void renderEditorMarkers(GuiGraphics gui, int left, int right, int top, int bottom,
                                     double topBeat, double bottomBeat) {
        if (loopStartMs >= 0) renderTimelineLine(gui, left, right, top, bottom, loopStartMs, 0xFF44DD66, "IN");
        if (loopEndMs >= 0) renderTimelineLine(gui, left, right, top, bottom, loopEndMs, 0xFFFF6655, "OUT");
        for (SongChart.Bookmark mark : chart.bookmarks) {
            double beat = conductor.beatAt(mark.timeMs);
            if (beat < topBeat || beat > bottomBeat) continue;
            int y = (int) beatToY(beat);
            gui.fill(left, y, right, y + 1, 0xCCFFD54A);
            draw(gui, mark.name, left + 3, y + 2, 0xFFFFE88A);
        }
    }

    private void renderTimelineLine(GuiGraphics gui, int left, int right, int top, int bottom,
                                    double timeMs, int color, String label) {
        int y = (int) beatToY(conductor.beatAt(timeMs));
        if (y < top || y > bottom) return;
        gui.fill(left, y, right, y + 2, color);
        draw(gui, label, right - font.width(label) - 2, y + 2, color);
    }

    private void renderControlPanel(GuiGraphics gui) {
        int x = controlX();
        int bottom = activeTab == EditorTab.EVENTS ? height - 26
                : Math.min(height - 26, activeTab == EditorTab.CHARTING ? 310
                : activeTab == EditorTab.SECTION ? 270 : 238);
        gui.fill(x, CONTROL_TOP, x + controlWidth(), bottom, 0xE6080808);
    }

    private void renderTopMenuBackground(GuiGraphics gui) {
        if (openMenu == TopMenu.NONE) return;
        int x = switch (openMenu) {
            case FILE -> 16;
            case EDIT -> 88;
            case VIEW -> 160;
            default -> 16;
        };
        int height = switch (openMenu) {
            case FILE -> 154;
            case EDIT -> 196;
            case VIEW -> 120;
            default -> 0;
        };
        gui.fill(x, 23, x + 144, 23 + height, 0xF00A0A0A);
    }

    private void renderLabels(GuiGraphics gui) {
        for (UiLabel label : labels) {
            if (label.centered) drawCentered(gui, label.text, label.x, label.y, label.color);
            else draw(gui, label.text, label.x, label.y, label.color);
        }
    }

    /** Bounded preview below the event controls; never draws beyond the panel. */
    private void renderInlineEventDocumentation(GuiGraphics gui) {
        if (activeTab != EditorTab.EVENTS || eventDropdownOpen || eventDocumentationVisible) return;
        int x = controlX() + 10;
        int w = controlWidth() - 20;
        // Camera Follow Pos carries an extra Value 6 row, so its controls end
        // lower than the other extended events; start the preview below them.
        int top;
        if (ChartEventTypes.isCameraFollowPos(eventTypeDraft)) {
            top = 330; // extra Value 7 (duration) field pushes the preview down
        } else if (ChartEventTypes.isCameraRotation3d(eventTypeDraft)
                || ChartEventTypes.is(eventTypeDraft, ChartEventTypes.ADD_CHARACTER)
                || ChartEventTypes.is(eventTypeDraft, ChartEventTypes.TWEEN_CHARACTER)) {
            top = 276;
        } else if (ChartEventTypes.isCameraZoom(eventTypeDraft)) {
            // Value 2 duration box + Value 3 easing push the controls lower.
            top = 249;
        } else {
            top = 219;
        }
        int bottom = height - 30;
        int lineStep = font.lineHeight + 2;
        int available = Math.max(0, (bottom - top) / lineStep);
        if (available <= 0) return;

        draw(gui, ChartEventTypes.documentationSource(eventTypeDraft), x, top, 0xFFFFFFFF);
        if (available == 1) return;
        List<FormattedCharSequence> lines = wrappedEventDocumentation(w);
        int documentationSlots = available - 1;
        boolean truncated = lines.size() > documentationSlots;
        int shown = truncated ? Math.max(0, documentationSlots - 1)
                : Math.min(lines.size(), documentationSlots);
        for (int i = 0; i < shown; i++) {
            gui.drawString(font, lines.get(i), x, top + (i + 1) * lineStep, 0xFFBBBBBB, false);
        }
        if (truncated && documentationSlots > 0) {
            String more = "...open Event Help for the full description";
            draw(gui, font.plainSubstrByWidth(more, w), x,
                    top + (shown + 1) * lineStep, 0xFFCCCC77);
        }
    }

    private void renderEventDocumentation(GuiGraphics gui) {
        int left = Math.max(12, width / 8);
        int right = Math.min(width - 12, width - width / 8);
        int top = 18;
        int bottom = height - 18;
        int bodyTop = top + 38;
        int bodyBottom = bottom - 18;
        int bodyWidth = Math.max(40, right - left - 24);
        int lineStep = font.lineHeight + 3;
        List<FormattedCharSequence> lines = wrappedEventDocumentation(bodyWidth);
        int visible = eventDocumentationVisibleLines();
        eventDocumentationScroll = Mth.clamp(eventDocumentationScroll, 0,
                Math.max(0, lines.size() - visible));

        // renderBackground already drew the editor's dim backdrop, so the game
        // stays faintly visible behind (same as the chart editor). The chart
        // itself is skipped this frame, so nothing overlaps the documentation.
        gui.fill(left, top, right, bottom, 0xFF111118);
        gui.renderOutline(left, top, right - left, bottom - top, 0xFFEEEEEE);
        String titleText = eventTypeDraft + " — " + ChartEventTypes.documentationSource(eventTypeDraft);
        drawCentered(gui, font.plainSubstrByWidth(titleText, right - left - 24),
                (left + right) / 2, top + 9, 0xFFFFFFFF);
        drawCentered(gui, "Values documentation", (left + right) / 2, top + 23, 0xFFBBBBBB);

        gui.enableScissor(left + 8, bodyTop, right - 8, Math.max(bodyTop + 1, bodyBottom));
        for (int row = 0; row < visible; row++) {
            int index = eventDocumentationScroll + row;
            if (index >= lines.size()) break;
            gui.drawString(font, lines.get(index), left + 12, bodyTop + row * lineStep,
                    0xFFE0E0E0, false);
        }
        gui.disableScissor();

        String footer = lines.size() > visible
                ? "Wheel/arrows scroll • " + (eventDocumentationScroll + 1)
                + "-" + Math.min(lines.size(), eventDocumentationScroll + visible)
                + "/" + lines.size() + " • click or Esc closes"
                : "Click, Enter, F1, or Esc to close";
        drawCentered(gui, font.plainSubstrByWidth(footer, right - left - 16),
                (left + right) / 2, bottom - 13, 0xFFAAAAAA);
    }

    private List<FormattedCharSequence> wrappedEventDocumentation(int maxWidth) {
        List<FormattedCharSequence> lines = new ArrayList<>();
        for (String paragraph : eventHelpText(eventTypeDraft).split("\\R", -1)) {
            if (paragraph.isBlank()) lines.add(Component.empty().getVisualOrderText());
            else lines.addAll(font.split(Component.literal(paragraph), Math.max(20, maxWidth)));
        }
        return lines;
    }

    private int eventDocumentationVisibleLines() {
        return Math.max(1, (height - 92) / (font.lineHeight + 3));
    }

    private int eventDocumentationMaxScroll() {
        int left = Math.max(12, width / 8);
        int right = Math.min(width - 12, width - width / 8);
        int bodyWidth = Math.max(40, right - left - 24);
        return Math.max(0, wrappedEventDocumentation(bodyWidth).size()
                - eventDocumentationVisibleLines());
    }

    private void renderInfoWindow(GuiGraphics gui) {
        int w = infoWidth();
        if (w <= 0) return;
        int h = infoHeight();
        gui.fill(infoX, infoY, infoX + w, infoY + h, 0xE6080808);
        gui.fill(infoX, infoY, infoX + w, infoY + 16, 0xFFF0F0F0);
        drawCentered(gui, "Information", infoX + w / 2, infoY + 4, 0xFF111111);

        int y = infoY + 25;
        double duration = audio == null ? 0 : audio.durationMs();
        draw(gui, formatTime(viewPositionMs) + " / " + formatTime(duration), infoX + 8, y, 0xFFFFFFFF);
        y += 25;
        double beat = conductor.beatAt(Math.max(0, viewPositionMs));
        draw(gui, "Section: " + sectionIndexAt(viewPositionMs), infoX + 8, y, 0xFFFFFFFF); y += 11;
        draw(gui, "Beat: " + (int) Math.floor(beat), infoX + 8, y, 0xFFFFFFFF); y += 11;
        draw(gui, "Step: " + (int) Math.floor(beat * 4), infoX + 8, y, 0xFFFFFFFF); y += 23;
        SongChart.TimeSignature meter = meterAtBeat(beat);
        draw(gui, "Meter: " + meter.numerator() + "/" + meter.denominator(),
                infoX + 8, y, 0xFFFFFFFF); y += 11;
        draw(gui, "Beat Snap: " + snapText(), infoX + 8, y, 0xFFFFFFFF); y += 11;
        draw(gui, "Selected: " + selectedNotes.size() + " N / " + selectedEvents.size() + " E",
                infoX + 8, y, 0xFFFFFFFF);
    }

    private void renderHelpScreen(GuiGraphics gui) {
        // renderBackground already drew the editor's dim backdrop, so the game
        // stays faintly visible behind (same as the chart editor). The chart is
        // skipped this frame, so nothing overlaps the help panel.
        int lineHeight = font.lineHeight + 2;
        int panelWidth = Math.min(width - 24, 520);
        int panelHeight = HELP_LINES.length * lineHeight + 38;
        int x = (width - panelWidth) / 2;
        int y = Math.max(8, (height - panelHeight) / 2);
        gui.fill(x, y, x + panelWidth, Math.min(height - 8, y + panelHeight), 0xF018181C);
        gui.renderOutline(x, y, panelWidth, Math.min(panelHeight, height - y - 8), 0xFFFFFFFF);
        drawCentered(gui, "CHART EDITOR HELP", width / 2, y + 10, 0xFFFFFFFF);
        int textY = y + 28;
        for (String line : HELP_LINES) {
            draw(gui, line, x + 12, textY, 0xFFFFFFFF);
            textY += lineHeight;
        }
        drawCentered(gui, "F1 or Esc - Close Help", width / 2,
                Math.min(height - 18, textY + 4), 0xFFBBBBBB);
    }

    private void draw(GuiGraphics gui, String text, int x, int y, int color) {
        gui.drawString(font, text, x, y, color, false);
    }

    private void drawCentered(GuiGraphics gui, String text, int centerX, int y, int color) {
        draw(gui, text, centerX - font.width(text) / 2, y, color);
    }

    // --------------------------------------------------------------------- Geometry and helpers

    private int controlWidth() { return Mth.clamp(width / 3, 220, 320); }
    private int controlX() { return width - controlWidth() - 16; }
    private int infoWidth() { return width >= 520 ? Mth.clamp(width / 6, 120, 170) : 0; }
    private int infoHeight() { return Math.max(100, Math.min(160, height - 64)); }

    private int cellWidth() {
        int leftRoom = width / 2 - (infoWidth() > 0 ? infoWidth() + 30 : 18);
        int rightRoom = controlX() - width / 2 - 18;
        int total = Math.max(8 * 14, 2 * Math.min(leftRoom, rightRoom));
        return Mth.clamp(total / 8, 14, 30);
    }

    private int gridX() { return width / 2 - 4 * cellWidth(); }
    private int gridTop() { return 64; }
    private int gridBottom() { return Math.max(gridTop() + 32, height - 26); }
    private int centerY() { return (gridTop() + gridBottom()) / 2; }

    /** Grid Zoom changes beat height; Beat Snap must never change that dimension. */
    private double effectivePixelsPerBeat() {
        return GRID_ROWS_PER_BEAT[gridZoomIndex] * (double) cellWidth();
    }

    /**
     * Snap changes subdivision size inside the fixed beat height. Zoom multiplies
     * both beat height and subdivision count, so it adds boxes without stretching
     * them; changing snap alone makes those boxes smaller/larger in-place.
     */
    private double gridStepBeats() {
        return beatSnapStepBeats() * BASE_GRID_ZOOM_ROWS / GRID_ROWS_PER_BEAT[gridZoomIndex];
    }

    private String gridBoxesText() { return trim(1.0 / gridStepBeats()); }

    /** Placement, selection, sustain edits, and the rendered boxes share one step. */
    private double snapStepBeats() { return gridStepBeats(); }

    private double beatSnapStepBeats() { return 4.0 / SNAPS[snapIndex]; }

    private double gridCellHeight() { return effectivePixelsPerBeat() * gridStepBeats(); }

    private float beatToY(double beat) {
        double viewBeat = conductor.beatAt(Math.max(0, visualPositionMs()));
        return (float) (centerY() + (beat - viewBeat) * effectivePixelsPerBeat());
    }

    private double yToBeat(double y) {
        double viewBeat = conductor.beatAt(Math.max(0, visualPositionMs()));
        return viewBeat + (y - centerY()) / effectivePixelsPerBeat();
    }

    private void saveEventsOnly() {
        commitVisibleFields();
        String id = sanitizeId(saveId == null || saveId.isBlank() ? chart.title : saveId);
        if (id.isBlank()) id = "unnamed";
        try {
            Path directory = configuredSavingFolder();
            if (directory == null) {
                Path suggested = chartSaveFile != null && chartSaveFile.getParent() != null
                        ? chartSaveFile.getParent().resolve("events.json")
                        : SongLibrary.songsDir().resolve(id).resolve("events.json");
                var selected = NativeFilePicker.saveFile("Save Psych events",
                        suggested, new String[]{"*.json"}, "FNF events JSON");
                if (selected.isEmpty()) {
                    setStatus("Event save cancelled");
                    return;
                }
                Path eventFile = ensureJsonExtension(selected.get());
                directory = eventFile.getParent();
                if (directory == null) throw new IllegalArgumentException("Choose a folder for events.json");
            }
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("events.json"), PsychChartWriter.writeEvents(chart));
            if (directory.startsWith(SongLibrary.songsDir())) writeOriginalReference(directory, id);
            setStatus("Saved " + directory.resolve("events.json").toAbsolutePath());
            SongLibrary.rescan();
        } catch (Exception e) {
            setStatus("Event save failed: " + e.getMessage());
            FnfMod.LOGGER.error("Event save failed", e);
        }
    }

    private String editorNoteTexture(SongChart.Note note) {
        return note.texture == null || note.texture.isBlank() ? editorChartNoteTexture() : note.texture;
    }

    private String editorChartNoteTexture() {
        return ClientOptions.NOTE_SKIN_DEFAULT.equalsIgnoreCase(ClientOptions.get().noteSkin)
                ? chart.noteTexture : "";
    }

    private void writeOriginalReference(Path directory, String fallbackId) throws Exception {
        if (originalDirectory == null) return;
        String chartName = originalChartName == null || originalChartName.isBlank()
                ? (requestedSongId == null ? fallbackId : requestedSongId) : originalChartName;
        Files.writeString(directory.resolve(SongLibrary.ORIGINAL_DIRECTORY_FILE),
                originalDirectory.toAbsolutePath().normalize() + System.lineSeparator() + "chart=" + chartName);
    }

    private void addEventAtFieldTime() {
        commitVisibleFields();
        addEventAt(eventTimeDraft);
    }

    private List<SongChart.Event> eventsAtSelectedPoint() {
        if (selectedEvent == null) return List.of();
        double time = selectedEvent.timeMs;
        List<SongChart.Event> result = new ArrayList<>();
        for (SongChart.Event event : chart.events) {
            if (Math.abs(event.timeMs - time) < 0.001) result.add(event);
        }
        return result;
    }

    private void selectPointEvent(SongChart.Event event) {
        selectedEvents.clear();
        selectedEvent = event;
        if (event != null) {
            selectedEvents.add(event);
            setEventDraft(event);
            eventTimeDraft = event.timeMs;
        }
        activeTab = EditorTab.EVENTS;
    }

    private void addEventToPoint() {
        commitVisibleFields();
        recordNoteChange();
        SongChart.Event added;
        if (selectedEvent != null) {
            added = selectedEvent.copy();
        } else {
            String value2 = eventValue2Draft.isBlank()
                    ? defaultEventValue2(eventTypeDraft, eventValue1Draft) : eventValue2Draft;
            added = new SongChart.Event(Math.max(0, eventTimeDraft), eventTypeDraft,
                    eventValue1Draft, value2, eventValue3Draft, eventValue4Draft,
                    eventValue5Draft, eventValue6Draft, eventValue7Draft, eventBeforeSongDraft);
        }
        chart.events.add(added);
        chart.sortEvents();
        selectPointEvent(added);
        setStatus("Added event at same point");
        rebuildUi();
    }

    private void removeEventFromPoint() {
        if (selectedEvent == null) return;
        commitVisibleFields();
        recordNoteChange();
        List<SongChart.Event> group = eventsAtSelectedPoint();
        int index = group.indexOf(selectedEvent);
        SongChart.Event removed = selectedEvent;
        chart.events.remove(removed);
        group.remove(removed);
        SongChart.Event next = group.isEmpty() ? null : group.get(Math.min(index, group.size() - 1));
        selectPointEvent(next);
        setStatus("Removed event from point");
        rebuildUi();
    }

    private void cyclePointEvent(int direction) {
        if (selectedEvent == null) return;
        commitVisibleFields();
        List<SongChart.Event> group = eventsAtSelectedPoint();
        if (group.size() < 2) return;
        int index = group.indexOf(selectedEvent);
        selectPointEvent(group.get(Math.floorMod(index + direction, group.size())));
        setStatus("Selected event " + (Math.floorMod(index + direction, group.size()) + 1)
                + " / " + group.size());
        rebuildUi();
    }

    private void addEventAt(double time) {
        commitVisibleFields();
        recordNoteChange();
        String value2 = eventValue2Draft.isBlank()
                ? defaultEventValue2(eventTypeDraft, eventValue1Draft) : eventValue2Draft;
        selectedEvent = new SongChart.Event(Math.max(0, time), eventTypeDraft,
                eventValue1Draft, value2, eventValue3Draft, eventValue4Draft,
                eventValue5Draft, eventValue6Draft, eventValue7Draft, eventBeforeSongDraft);
        setEventDraft(selectedEvent);
        chart.events.add(selectedEvent);
        chart.sortEvents();
        activeTab = EditorTab.EVENTS;
        setStatus("Added event");
        rebuildUi();
    }

    private void setEventDraft(SongChart.Event event) {
        eventTypeDraft = event == null || event.name.isBlank() ? ChartEventTypes.MINECRAFT_COMMAND : event.name;
        eventValue1Draft = event == null ? "" : event.value1;
        eventValue2Draft = event == null
                ? defaultEventValue2(eventTypeDraft, eventValue1Draft) : event.value2;
        eventValue3Draft = event == null ? ChartEventTypes.defaultValue3(eventTypeDraft) : event.value3;
        eventValue4Draft = event == null ? ChartEventTypes.defaultValue4(eventTypeDraft) : event.value4;
        eventValue5Draft = event == null ? ChartEventTypes.defaultValue5(eventTypeDraft) : event.value5;
        eventValue6Draft = event == null ? ChartEventTypes.defaultValue6(eventTypeDraft) : event.value6;
        eventValue7Draft = event == null ? "" : event.value7;
        eventTimeDraft = event == null ? Math.max(0, viewPositionMs) : event.timeMs;
        eventBeforeSongDraft = event != null && event.beforeSong;
        eventDraftInitialized = true;
        eventDropdownOpen = false;
    }

    private void selectEventType(String type) {
        boolean changed = !type.equals(eventTypeDraft);
        eventTypeDraft = type;
        eventDropdownOpen = false;
        eventDocumentationScroll = 0;
        if (changed) {
            // Reset drafts to the new type's defaults. Custom events (no built-in
            // definition) default every value to empty, so switching to one no
            // longer keeps the previous type's Value 2 such as "player".
            eventValue1Draft = ChartEventTypes.defaultValue1(type);
            eventValue2Draft = ChartEventTypes.defaultValue2(type, eventValue1Draft);
            eventValue3Draft = ChartEventTypes.defaultValue3(type);
            eventValue4Draft = ChartEventTypes.defaultValue4(type);
            eventValue5Draft = ChartEventTypes.defaultValue5(type);
            eventValue6Draft = ChartEventTypes.defaultValue6(type);
            eventValue7Draft = "";
        }
        if (selectedEvent != null) {
            selectedEvent.name = type;
            selectedEvent.value1 = eventValue1Draft;
            selectedEvent.value2 = eventValue2Draft;
            selectedEvent.value3 = eventValue3Draft;
            selectedEvent.value4 = eventValue4Draft;
            selectedEvent.value5 = eventValue5Draft;
            selectedEvent.value6 = eventValue6Draft;
            selectedEvent.value7 = eventValue7Draft;
        }
        rebuildUi();
    }

    private static String defaultEventValue2(String eventType, String value1) {
        return ChartEventTypes.defaultValue2(eventType, value1);
    }

    private record EaseChoice(String base, String direction, boolean defaultValue) {}

    private void buildEasingControl(int x, int y, int width, int valueIndex,
                                    boolean allowDefault, boolean active) {
        label("Value " + valueIndex, x, y, 0xFFDDDDDD, false);
        EaseChoice choice = easeChoice(eventValue(valueIndex));
        int directionWidth = Math.min(58, Math.max(40, width / 3));
        int easingWidth = width - directionWidth - 2;
        String easingText = choice.defaultValue ? "Easing: Default"
                : "Easing: " + easeTitle(choice.base);
        // Shift-click reverses, matching the other cycling controls (Beat Snap,
        // Camera Focus target). The button consumes the click before any grid
        // logic runs, so holding Shift over it never triggers a grid selection.
        Button easing = button(x, y + 10, easingWidth, easingText,
                b -> cycleEventEase(valueIndex, allowDefault, hasShiftDown() ? -1 : 1));
        Button direction = button(x + easingWidth + 2, y + 10, directionWidth,
                choice.defaultValue || !directionalEase(choice.base) ? "-" : choice.direction,
                b -> cycleEventEaseDirection(valueIndex, hasShiftDown() ? -1 : 1));
        easing.active = active;
        direction.active = active && !choice.defaultValue && directionalEase(choice.base);
    }

    private void cycleEventEase(int valueIndex, boolean allowDefault, int direction) {
        commitVisibleFields();
        int back = direction < 0 ? -1 : 1;
        int count = GameplayCamera.EASES.length;
        EaseChoice current = easeChoice(eventValue(valueIndex));
        if (current.defaultValue) {
            // Leaving Default steps into the first ease forward, the last backward.
            setEventValue(valueIndex, GameplayCamera.EASES[back < 0 ? count - 1 : 0]);
            rebuildUi();
            return;
        }
        int index = 0;
        for (int i = 0; i < count; i++) {
            if (GameplayCamera.EASES[i].equalsIgnoreCase(current.base)) {
                index = i;
                break;
            }
        }
        // With Default in the loop the order is ease0..easeN-1, Default, wrapping.
        if (allowDefault && (back > 0 ? index == count - 1 : index == 0)) {
            setEventValue(valueIndex, "");
        } else {
            String next = GameplayCamera.EASES[Math.floorMod(index + back, count)];
            setEventValue(valueIndex, composeEase(next, current.direction));
        }
        rebuildUi();
    }

    private void cycleEventEaseDirection(int valueIndex, int direction) {
        commitVisibleFields();
        EaseChoice choice = easeChoice(eventValue(valueIndex));
        if (choice.defaultValue || !directionalEase(choice.base)) return;
        int index = 0;
        for (int i = 0; i < Easing.DIRECTIONS.length; i++) {
            if (Easing.DIRECTIONS[i].equalsIgnoreCase(choice.direction)) index = i;
        }
        int step = direction < 0 ? -1 : 1;
        String next = Easing.DIRECTIONS[Math.floorMod(index + step, Easing.DIRECTIONS.length)];
        setEventValue(valueIndex, composeEase(choice.base, next));
        rebuildUi();
    }

    private static EaseChoice easeChoice(String raw) {
        if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("default")) {
            return new EaseChoice("smooth", "inOut", true);
        }
        String normalized = Easing.normalize(raw);
        if (normalized.equals("snap")) normalized = "constant";
        String direction = normalized.endsWith("inout") ? "inOut"
                : normalized.endsWith("out") ? "out"
                : normalized.endsWith("in") ? "in" : "inOut";
        String base = normalized;
        if (base.endsWith("inout")) base = base.substring(0, base.length() - 5);
        else if (base.endsWith("out")) base = base.substring(0, base.length() - 3);
        else if (base.endsWith("in")) base = base.substring(0, base.length() - 2);
        boolean known = false;
        for (String candidate : GameplayCamera.EASES) {
            if (candidate.equalsIgnoreCase(base)) {
                base = candidate;
                known = true;
                break;
            }
        }
        if (!known) base = "smooth";
        // Unqualified expo kept its historic camera ease-out behavior.
        if (normalized.equals("expo")) direction = "out";
        return new EaseChoice(base, direction, false);
    }

    private static boolean directionalEase(String base) {
        return !base.equalsIgnoreCase("smooth") && !base.equalsIgnoreCase("linear")
                && !base.equalsIgnoreCase("constant");
    }

    private static String composeEase(String base, String direction) {
        if (!directionalEase(base)) return base;
        String suffix = direction.equalsIgnoreCase("inOut") ? "InOut"
                : direction.equalsIgnoreCase("out") ? "Out" : "In";
        return base + suffix;
    }

    private static String easeTitle(String value) {
        return value == null || value.isBlank() ? "Default"
                : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private String eventValue(int index) {
        return switch (index) {
            case 1 -> eventValue1Draft;
            case 2 -> eventValue2Draft;
            case 3 -> eventValue3Draft;
            case 4 -> eventValue4Draft;
            case 5 -> eventValue5Draft;
            default -> "";
        };
    }

    private void setEventValue(int index, String value) {
        String clean = value == null ? "" : value;
        switch (index) {
            case 1 -> eventValue1Draft = clean;
            case 2 -> eventValue2Draft = clean;
            case 3 -> eventValue3Draft = clean;
            case 4 -> eventValue4Draft = clean;
            case 5 -> eventValue5Draft = clean;
            case 6 -> eventValue6Draft = clean;
            case 7 -> eventValue7Draft = clean;
            default -> { return; }
        }
        if (selectedEvent == null) return;
        switch (index) {
            case 1 -> selectedEvent.value1 = clean;
            case 2 -> selectedEvent.value2 = clean;
            case 3 -> selectedEvent.value3 = clean;
            case 4 -> selectedEvent.value4 = clean;
            case 5 -> selectedEvent.value5 = clean;
            case 6 -> selectedEvent.value6 = clean;
            case 7 -> selectedEvent.value7 = clean;
        }
    }

    private void cycleCameraMovementOverride() {
        commitVisibleFields();
        setEventValue(5, eventValue5Draft.isBlank()
                || eventValue5Draft.equalsIgnoreCase("default") ? "override" : "");
        rebuildUi();
    }

    private void cycleCameraFollowFrame() {
        commitVisibleFields();
        // Machine facing is the default; the toggle only sets the camera keyword.
        setEventValue(6, cameraFollowUsesCameraFrame(eventValue6Draft) ? "" : "camera");
        rebuildUi();
    }

    private static boolean cameraFollowUsesCameraFrame(String value6) {
        String value = value6 == null ? "" : value6.trim().toLowerCase(Locale.ROOT);
        return value.equals("camera") || value.equals("cam") || value.equals("rotation")
                || value.equals("rotated") || value.equals("camerarotation")
                || value.equals("view") || value.equals("screen");
    }

    private void cycleCameraFocusTarget() {
        cycleCameraFocusTarget(1);
    }

    private void cycleCameraFocusTarget(int direction) {
        commitVisibleFields();
        String[] order = {"", "player", "opponent", "gf"};
        String current = eventValue1Draft == null ? "" : eventValue1Draft.trim().toLowerCase(Locale.ROOT);
        int index = 0;
        for (int i = 0; i < order.length; i++) {
            if (order[i].equals(current)) { index = i; break; }
        }
        eventValue1Draft = order[Math.floorMod(index + (direction < 0 ? -1 : 1), order.length)];
        if (eventValue1Draft.isBlank()) eventValue2Draft = "";
        else if (eventValue2Draft.isBlank()) eventValue2Draft = "smooth";
        if (selectedEvent != null) {
            selectedEvent.value1 = eventValue1Draft;
            selectedEvent.value2 = eventValue2Draft;
        }
        rebuildUi();
    }

    private void openCommandEditor() {
        commitVisibleFields();
        minecraft.setScreen(new CommandEventEditorScreen(this, eventValue1Draft, command -> {
            eventValue1Draft = command;
            if (selectedEvent != null) selectedEvent.value1 = command;
        }));
    }

    private void deleteSelectedEvent() {
        if (selectedEvent == null && selectedEvents.isEmpty()) return;
        recordNoteChange();
        int removed = selectedEvents.isEmpty() ? 1 : selectedEvents.size();
        if (selectedEvents.isEmpty()) chart.events.remove(selectedEvent);
        else chart.events.removeAll(selectedEvents);
        selectedEvents.clear();
        selectedEvent = null;
        setStatus("Deleted " + removed + " event(s)");
        rebuildUi();
    }

    /** Converts the visual center of a note cell into its stable chart beat. */
    private double selectionBeatAtScreenY(double screenY) {
        return yToBeat(screenY - gridCellHeight() / 2.0);
    }

    private double selectionScreenY(double beat) {
        return beatToY(beat) + gridCellHeight() / 2.0;
    }

    private double stepMs() {
        double beat = conductor.beatAt(Math.max(0, viewPositionMs));
        return conductor.timeOfBeat(beat + snapStepBeats()) - conductor.timeOfBeat(beat);
    }

    private void cycleSnap() {
        cycleSnap(1);
    }

    private void cycleSnap(int direction) {
        commitVisibleFields();
        snapIndex = Math.floorMod(snapIndex + (direction < 0 ? -1 : 1), SNAPS.length);
        if (vortex && !isPlaying()) snapPlayheadToGrid();
        rebuildUi();
    }

    private void changeSnap(int direction) {
        commitVisibleFields();
        int changed = Mth.clamp(snapIndex + Integer.signum(direction), 0, SNAPS.length - 1);
        if (changed == snapIndex) return;
        snapIndex = changed;
        if (vortex && !isPlaying()) snapPlayheadToGrid();
        setStatus("Beat snap: " + snapText());
        rebuildUi();
    }

    private void clampOrInitializeInfoWindow() {
        if (infoX < 0 || infoY < 0) {
            infoX = 16;
            infoY = 34;
        }
        if (infoWidth() > 0) {
            infoX = Mth.clamp(infoX, 0, Math.max(0, width - infoWidth()));
            infoY = Mth.clamp(infoY, 22, Math.max(22, height - infoHeight() - 12));
        }
    }

    private boolean insideInfo(double mouseX, double mouseY) {
        return infoWidth() > 0 && mouseX >= infoX && mouseX < infoX + infoWidth()
                && mouseY >= infoY && mouseY < infoY + infoHeight();
    }

    private void setStatus(String message) {
        status = message;
        statusUntil = System.currentTimeMillis() + 4000;
    }

    private String snapText() { return SNAPS[snapIndex] + " / " + SNAPS[snapIndex]; }
    private static String onOff(boolean value) { return value ? "ON" : "OFF"; }

    private static String shortTabName(EditorTab tab) {
        return switch (tab) {
            case CHARTING -> "Chart";
            case EVENTS -> "Event";
            case SECTION -> "Sect";
            default -> titleCase(tab.name());
        };
    }

    private static String titleCase(String value) {
        return value.substring(0, 1) + value.substring(1).toLowerCase(Locale.ROOT);
    }

    private static String trim(double value) {
        return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private static String sanitizeId(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
    }

    private static String sanitizeModFolder(String value) {
        if (value == null) return "";
        String safe = value.trim().replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]", "-")
                .replaceAll("[. ]+$", "");
        return safe.equals(".") || safe.equals("..") ? "" : safe;
    }

    private static String formatTime(double ms) {
        if (ms <= 0) return "0:00.00";
        long total = (long) ms;
        return String.format(Locale.ROOT, "%d:%02d.%02d",
                total / 60000, (total / 1000) % 60, (total % 1000) / 10);
    }

    @Override
    public void renderBackground(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        gui.fill(0, 0, width, height, 0xF0101014);
    }

    @Override
    public void onClose() {
        disposeScheduler();
        disposeAudio();
        disposeNoteTextures();
        for (Path folder : temporaryAudioFolders) ChartAudioImport.delete(folder);
        temporaryAudioFolders.clear();
        pendingAudioFolder = null;
        super.onClose();
    }

    @Override
    public void removed() {
        disposeScheduler();
        disposeAudio();
        disposeNoteTextures();
        super.removed();
    }

    private void disposeScheduler() {
        if (tickScheduler != null) {
            tickScheduler.stop();
            tickScheduler = null;
        }
        schedulerActive = false;
    }

    private void disposeNoteTextures() {
        if (noteTextures == null) return;
        noteTextures.close();
        noteTextures = null;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean isTextInputActive() {
        return missingSavingFolderWarning != null || instColorPickerOpen || bookmarkTextDialogOpen
                || (getFocused() instanceof EditBox edit && edit.isFocused());
    }
}
