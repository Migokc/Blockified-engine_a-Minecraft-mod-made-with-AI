package com.fnfmod.client.gui.editor;

import com.fnfmod.FnfMod;
import com.fnfmod.chart.Conductor;
import com.fnfmod.chart.PsychChartWriter;
import com.fnfmod.chart.SongChart;
import com.fnfmod.client.ClientOptions;
import com.fnfmod.client.audio.SongPlayer;
import com.fnfmod.client.camera.GameplayCamera;
import com.fnfmod.client.gui.GameplayScreen;
import com.fnfmod.client.render.NoteStyle;
import com.fnfmod.song.SongEntry;
import com.fnfmod.song.SongLibrary;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Psych-inspired chart editor for the normalized FNF chart format.
 *
 * The playhead remains fixed while the chart moves beneath it. The event lane
 * supports Minecraft command events alongside imported engine events.
 */
public final class ChartEditorScreen extends Screen {

    private static final int[] SNAPS = {4, 8, 12, 16, 24, 32, 64};
    private static final double DEFAULT_PIXELS_PER_BEAT = 64.0;
    private static final int TAB_Y = 24;
    private static final int CONTROL_TOP = 40;
    private static final String MINECRAFT_COMMAND_EVENT = "Minecraft Command";
    private static final String CAMERA_ZOOM_EVENT = "Camera Zoom";
    private static final String CAMERA_FOCUS_EVENT = "Camera Focus";
    private static final List<String> BUILTIN_EVENT_TYPES = List.of(
            MINECRAFT_COMMAND_EVENT, CAMERA_ZOOM_EVENT, CAMERA_FOCUS_EVENT);
    private static final String[] HELP_LINES = {
            "W/S/Mouse Wheel - Move Conductor's Time",
            "A/D - Change Sections",
            "Q/E - Decrease/Increase Note Sustain Length",
            "Hold Shift/Alt to Increase/Decrease move by 4x",
            "F12 - Preview Chart",
            "Enter - Playtest Chart",
            "Space - Stop/Resume song",
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
    private final List<UiLabel> labels = new ArrayList<>();
    private final List<SongChart.Note> sectionClipboard = new ArrayList<>();
    private final List<SongChart.Note> noteClipboard = new ArrayList<>();
    private final Set<SongChart.Note> selectedNotes = new LinkedHashSet<>();
    private final Set<SongChart.Event> selectedEvents = new LinkedHashSet<>();
    private final Deque<List<SongChart.Note>> undoHistory = new ArrayDeque<>();
    private final Deque<List<SongChart.Note>> redoHistory = new ArrayDeque<>();

    private SongChart chart;
    private Conductor conductor;
    private SongEntry entry;
    private SongPlayer audio;
    private String songId;
    private String saveId;
    private String loadedDifficulty = "normal";
    private String defaultNoteType = "";
    private Path originalDirectory;
    private String originalChartName;
    private Path eventDefinitionRoot;
    private final List<String> eventTypes = new ArrayList<>(BUILTIN_EVENT_TYPES);

    private double viewPositionMs;
    private int snapIndex = 3;
    private int shownSection = -1;
    private int hitsoundIndex;
    private boolean vortex;
    private SongChart.Note selectedNote;
    private SongChart.Event selectedEvent;
    private boolean eventDropdownOpen;
    private int eventDropdownScroll;
    private boolean eventDraftInitialized;
    private String eventTypeDraft = MINECRAFT_COMMAND_EVENT;
    private String eventValue1Draft = "";
    private String eventValue2Draft = "player";
    private double eventTimeDraft;
    private boolean eventBeforeSongDraft;
    private double pixelsPerBeat = DEFAULT_PIXELS_PER_BEAT;
    private float playbackRate = 1.0f;
    private boolean helpVisible;
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
    private EditBox playerField;
    private EditBox opponentField;
    private EditBox sectionBpmField;
    private EditBox sectionBeatsField;
    private EditBox noteTimeField;
    private EditBox sustainField;
    private EditBox noteTypeField;
    private EditBox saveIdField;
    private EditBox eventTimeField;
    private EditBox eventValue1Field;
    private EditBox eventValue2Field;

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
        super(Component.literal("FNF Chart Editor"));
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
        clampOrInitializeInfoWindow();
        rebuildUi();
    }

    private void loadChart() {
        SongLibrary.rescan();
        String difficulty = requestedDifficulty == null || requestedDifficulty.isBlank()
                ? "normal" : requestedDifficulty;
        SongEntry libraryEntry = requestedSongId == null ? null : SongLibrary.get(requestedSongId);
        if (libraryEntry != null) {
            eventDefinitionRoot = libraryEntry.modRoot != null ? libraryEntry.modRoot : libraryEntry.folder;
        }
        if (suppliedSongFolder != null) {
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
        loadedDifficulty = difficulty;
        discoverEventTypes();

        if (entry != null && entry.instFor(difficulty) != null) {
            try {
                audio = new SongPlayer();
                audio.load(entry.instFor(difficulty), entry.voicesFor(difficulty),
                        entry.voicesPlayerFor(difficulty), entry.voicesOpponentFor(difficulty));
            } catch (Exception e) {
                audio = null;
                FnfMod.LOGGER.warn("Editor: audio unavailable: {}", e.toString());
            }
        }
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
        playerField = opponentField = sectionBpmField = sectionBeatsField = null;
        noteTimeField = sustainField = noteTypeField = saveIdField = null;
        eventTimeField = eventValue1Field = eventValue2Field = null;
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
        songBpmField = labeledBox("BPM", x, y, third, trim(chart.startBpm), "bpm");
        songSpeedField = labeledBox("Scroll Speed", x + third + 4, y, third, trim(chart.speed), "speed");
        songOffsetField = labeledBox("Offset (ms)", x + (third + 4) * 2, y, third, trim(chart.offsetMs), "offset");
        y += 31;
        int half = (w - 4) / 2;
        playerField = labeledBox("Player", x, y, half, chart.player1, "player");
        opponentField = labeledBox("Opponent", x + half + 4, y, half, chart.player2, "opponent");
        y += 31;
        button(x, y, w, "Allow Vocals: " + onOff(chart.needsVoices), b -> {
            chart.needsVoices = !chart.needsVoices;
            b.setMessage(Component.literal("Allow Vocals: " + onOff(chart.needsVoices)));
        });
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
        sectionBpmField = labeledBox("Section BPM", x, y, half,
                section.changeBPM ? trim(section.bpm) : "", "bpm");
        sectionBpmField.active = section.changeBPM;
        sectionBeatsField = labeledBox("Beats per Section", x + half + 4, y, half,
                trim(section.sectionBeats), "beats");
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
        Button importSong = button(x, y + 111, w, "Import Complete Song", b -> importSongFiles());
        importSong.setTooltip(Tooltip.create(Component.literal(
                "Saves locally, then imports audio, other difficulties, and the song icon")));
    }

    private void buildChartingTab() {
        int x = controlX() + 10;
        int w = controlWidth() - 20;
        int y = 54;
        label("Editor-only playback options", controlX() + controlWidth() / 2, 43, 0xFFDDDDDD, true);

        button(x, y, w, String.format(Locale.ROOT, "Playback Rate: %.1f", playbackRate), b -> {
            setPlaybackRate(playbackRate >= 5f ? 0.1f : playbackRate + 0.1f);
            b.setMessage(Component.literal(String.format(Locale.ROOT, "Playback Rate: %.1f", playbackRate)));
        });
        y += 22;
        button(x, y, w, "Beat Snap: " + snapText(), b -> cycleSnap());
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
    }

    private void buildDataTab() {
        int x = controlX() + 10;
        int w = controlWidth() - 20;
        int y = 52;
        label("Reserved Psych song-data fields", controlX() + controlWidth() / 2, 43, 0xFFDDDDDD, true);
        for (String name : List.of("Game Over Character", "Death Sound", "Loop Music", "Retry Music",
                "Note Texture", "Note Splash Texture")) {
            EditBox field = labeledBox(name, x, y, w, "", "not supported yet");
            field.active = false;
            y += 27;
        }
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
        boolean cameraZoom = isCameraZoomType(eventTypeDraft);
        boolean cameraFocus = isCameraFocusType(eventTypeDraft);
        if (isMinecraftCommandType(eventTypeDraft)) {
            label("Value 1", x, y + 72, 0xFFDDDDDD, false);
            String preview = eventValue1Draft.isBlank() ? "Click to edit command..." : eventValue1Draft;
            button(x, y + 82, w, font.plainSubstrByWidth(preview, Math.max(8, w - 12)),
                    b -> openCommandEditor());
        } else if (cameraFocus) {
            label("Value 1", x, y + 72, 0xFFDDDDDD, false);
            String target = eventValue1Draft.isBlank() ? "Normal (Must Hit)" : eventValue1Draft;
            button(x, y + 82, w, "Target: " + target, b -> cycleCameraFocusTarget());
        } else {
            eventValue1Field = labeledBox("Value 1", x, y + 72, w, eventValue1Draft,
                    cameraZoom ? "zoom amount" : "value 1");
            eventValue1Field.setMaxLength(Integer.MAX_VALUE);
        }
        if (cameraZoom || cameraFocus) {
            label("Value 2", x, y + 99, 0xFFDDDDDD, false);
            Button easing = button(x, y + 109, w,
                    cameraFocus && eventValue1Draft.isBlank()
                            ? "Easing: (empty)" : "Easing: " + normalizedEase(eventValue2Draft),
                    b -> cycleEventEase());
            easing.active = !cameraFocus || !eventValue1Draft.isBlank();
        } else {
            eventValue2Field = labeledBox("Value 2", x, y + 99, w,
                    eventValue2Draft, isMinecraftCommandType(eventTypeDraft) ? "player or server" : "value 2");
            eventValue2Field.setMaxLength(Integer.MAX_VALUE);
        }
        int half = (w - 4) / 2;
        button(x, y + 126, half, "Add Event", b -> addEventAtFieldTime());
        Button apply = button(x + half + 4, y + 126, half, "Apply Selected", b -> {
            commitVisibleFields();
            setStatus("Event updated");
            rebuildUi();
        });
        apply.active = selectedEvent != null;
        Button remove = button(x, y + 144, w, "Delete Selected Event", b -> deleteSelectedEvent());
        remove.active = selectedEvent != null;
        label(cameraZoom
                        ? "Camera Zoom: Value 1 = amount, Value 2 = easing (500ms)"
                        : cameraFocus
                        ? "Camera Focus: target + easing; empty values restore Must Hit"
                        : isMinecraftCommandType(eventTypeDraft)
                        ? "Minecraft Command: Value 1 = command, Value 2 = player/server"
                        : "Custom event: Value 1 and Value 2 are passed to its Lua callback",
                x, y + 165, 0xFFBBBBBB, false);
    }

    private void discoverEventTypes() {
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
            button(x + 4, y, w - 8, "Save", b -> saveChart()); y += 16;
            Button openEvents = button(x + 4, y, w - 8, "Open Events...", b -> {}); openEvents.active = false; y += 16;
            button(x + 4, y, w - 8, "Save Events...", b -> saveEventsOnly()); y += 16;
            button(x + 4, y, w - 8, "Exit", b -> onClose());
        } else if (openMenu == TopMenu.EDIT) {
            Button undo = button(x + 4, y, w - 8, "Undo", b -> { undoNotes(); rebuildUi(); });
            undo.active = !undoHistory.isEmpty(); y += 16;
            Button redo = button(x + 4, y, w - 8, "Redo", b -> { redoNotes(); rebuildUi(); });
            redo.active = !redoHistory.isEmpty(); y += 16;
            button(x + 4, y, w - 8, "Copy Section", b -> copySection(shownSection)); y += 16;
            button(x + 4, y, w - 8, "Paste Section", b -> pasteSection(shownSection)); y += 16;
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
                chart.events.clear();
                selectedEvents.clear();
                selectedEvent = null;
                setStatus("Cleared all events");
                rebuildUi();
            });
            clearEvents.active = !chart.events.isEmpty();
        } else {
            button(x + 4, y, w - 8, "Beat Snap: " + snapText(), b -> cycleSnap()); y += 16;
            button(x + 4, y, w - 8, "Vortex Editor: " + onOff(vortex), b -> {
                vortex = !vortex;
                if (vortex && !isPlaying()) snapPlayheadToGrid();
                rebuildUi();
            }); y += 16;
            Button waveform = button(x + 4, y, w - 8, "Waveform...", b -> {}); waveform.active = false;
        }
    }

    private EditBox labeledBox(String label, int x, int y, int w, String value, String hint) {
        label(label, x, y, 0xFFDDDDDD, false);
        return box(x, y + 10, w, value, hint);
    }

    private EditBox box(int x, int y, int w, String value, String hint) {
        EditBox field = addRenderableWidget(new EditBox(font, x, y, Math.max(8, w), 14, Component.literal(hint)));
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
        if (playerField != null && !playerField.getValue().isBlank()) chart.player1 = playerField.getValue().trim();
        if (opponentField != null && !opponentField.getValue().isBlank()) chart.player2 = opponentField.getValue().trim();

        if (sectionBpmField != null || sectionBeatsField != null) {
            SongChart.Section section = sectionAt(shownSection);
            if (section.changeBPM) section.bpm = parsePositive(sectionBpmField,
                    section.bpm > 0 ? section.bpm : chart.bpmForSection(shownSection));
            section.sectionBeats = Mth.clamp(parsePositive(sectionBeatsField, section.sectionBeats), 0.25, 64.0);
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

        if (eventTimeField != null) eventTimeDraft = Math.max(0, parseNumber(eventTimeField, eventTimeDraft));
        if (eventValue1Field != null) eventValue1Draft = eventValue1Field.getValue();
        if (eventValue2Field != null) eventValue2Draft = eventValue2Field.getValue().trim();
        if (selectedEvent != null && eventDraftInitialized) {
            selectedEvent.timeMs = eventTimeDraft;
            selectedEvent.name = eventTypeDraft;
            selectedEvent.value1 = eventValue1Draft;
            selectedEvent.value2 = eventValue2Draft;
            selectedEvent.beforeSong = eventBeforeSongDraft;
            chart.sortEvents();
        }

        if (saveIdField != null && !saveIdField.getValue().isBlank()) saveId = sanitizeId(saveIdField.getValue());
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

    private List<SongChart.Note> noteSnapshot() {
        List<SongChart.Note> snapshot = new ArrayList<>(chart.notes.size());
        for (SongChart.Note note : chart.notes) snapshot.add(note.copy());
        return snapshot;
    }

    private void recordNoteChange() {
        undoHistory.push(noteSnapshot());
        while (undoHistory.size() > 100) undoHistory.removeLast();
        redoHistory.clear();
    }

    private void restoreNotes(List<SongChart.Note> snapshot) {
        chart.notes.clear();
        for (SongChart.Note note : snapshot) chart.notes.add(note.copy());
        chart.sortNotes();
        clearSelection();
        if (activeTab == EditorTab.NOTE) rebuildUi();
    }

    private void undoNotes() {
        if (undoHistory.isEmpty()) {
            setStatus("Nothing to undo");
            return;
        }
        redoHistory.push(noteSnapshot());
        restoreNotes(undoHistory.pop());
        setStatus("Undo");
    }

    private void redoNotes() {
        if (redoHistory.isEmpty()) {
            setStatus("Nothing to redo");
            return;
        }
        undoHistory.push(noteSnapshot());
        restoreNotes(redoHistory.pop());
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
        if (selectedNotes.isEmpty()) return;
        List<SongChart.Note> ordered = new ArrayList<>(selectedNotes);
        ordered.sort(java.util.Comparator.comparingDouble(n -> n.timeMs));
        double first = ordered.get(0).timeMs;
        for (SongChart.Note note : ordered) {
            SongChart.Note copy = note.copy();
            copy.timeMs -= first;
            noteClipboard.add(copy);
        }
        setStatus("Copied " + noteClipboard.size() + " selected note(s)");
    }

    private void deleteSelectedNotes() {
        if (selectedNotes.isEmpty()) return;
        recordNoteChange();
        int count = selectedNotes.size();
        chart.notes.removeAll(selectedNotes);
        clearSelection();
        setStatus("Deleted " + count + " note(s)");
        if (activeTab == EditorTab.NOTE) rebuildUi();
    }

    private void pasteSelectedNotes() {
        if (noteClipboard.isEmpty()) return;
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
        chart.sortNotes();
        setStatus("Pasted " + noteClipboard.size() + " note(s)");
        if (activeTab == EditorTab.NOTE) rebuildUi();
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

    private void saveChart() {
        commitVisibleFields();
        String id = sanitizeId(saveId == null || saveId.isBlank() ? chart.title : saveId);
        if (id.isBlank()) id = "unnamed";
        try {
            Path directory = SongLibrary.songsDir().resolve(id);
            Files.createDirectories(directory);
            String difficultySuffix = loadedDifficulty.equalsIgnoreCase("normal")
                    ? "" : "-" + sanitizeId(loadedDifficulty);
            Path file = directory.resolve(id + difficultySuffix + ".json");
            Files.writeString(file, PsychChartWriter.write(chart));
            Files.writeString(directory.resolve("events.json"), PsychChartWriter.writeEvents(chart));
            writeOriginalReference(directory, id);
            songId = saveId = id;
            SongLibrary.rescan();
            setStatus("Saved " + file.getFileName());
        } catch (Exception e) {
            setStatus("Save failed: " + e.getMessage());
            FnfMod.LOGGER.error("Chart save failed", e);
        }
    }

    // --------------------------------------------------------------------- Playback

    private boolean isPlaying() {
        return audio != null && audio.isStarted() && !audio.isPaused();
    }

    private void setPlaybackRate(float rate) {
        playbackRate = Math.round(Mth.clamp(rate, 0.1f, 5.0f) * 10f) / 10f;
        if (audio != null) audio.setPlaybackRate(playbackRate);
        setStatus(String.format(Locale.ROOT, "Playback rate: %.1fx", playbackRate));
    }

    private void togglePlayback() {
        commitVisibleFields();
        if (audio == null) {
            setStatus("No Inst.ogg is available for playback");
            return;
        }
        if (isPlaying()) {
            viewPositionMs = audio.positionMs();
            audio.pause();
            if (vortex) snapPlayheadToGrid();
        } else if (audio.isPaused()) {
            audio.seekMs(viewPositionMs);
            audio.resume();
            resetHitsoundIndex(viewPositionMs);
        } else {
            audio.start();
            audio.seekMs(viewPositionMs);
            resetHitsoundIndex(viewPositionMs);
        }
    }

    private void launchPlaytest(boolean previewFromCurrentTime) {
        commitVisibleFields();
        if (entry == null || entry.instFor(loadedDifficulty) == null) {
            setStatus("A valid Inst.ogg is required to playtest");
            return;
        }
        try {
            SongPlayer playtestAudio = new SongPlayer();
            playtestAudio.load(entry.instFor(loadedDifficulty),
                    chart.needsVoices ? entry.voicesFor(loadedDifficulty) : null,
                    chart.needsVoices ? entry.voicesPlayerFor(loadedDifficulty) : null,
                    chart.needsVoices ? entry.voicesOpponentFor(loadedDifficulty) : null);
            if (previewFromCurrentTime) playtestAudio.setPlaybackRate(playbackRate);
            double startMs = previewFromCurrentTime ? viewPositionMs : 0;
            BlockPos machine = sourceMachinePos != null ? sourceMachinePos
                    : (minecraft.player == null ? BlockPos.ZERO : minecraft.player.blockPosition());
            minecraft.setScreen(GameplayScreen.editorPlaytest(machine, chart, playtestAudio, startMs,
                    previewFromCurrentTime,
                    () -> recreateEditor(startMs)));
        } catch (Exception e) {
            setStatus("Playtest failed: " + e.getMessage());
            FnfMod.LOGGER.warn("Editor playtest failed: {}", e.toString());
        }
    }

    private ChartEditorScreen recreateEditor(double positionMs) {
        ChartEditorScreen editor = new ChartEditorScreen(requestedSongId, loadedDifficulty, chart,
                suppliedSongFolder, originalDirectory, sourceMachinePos);
        editor.viewPositionMs = Math.max(0, positionMs);
        editor.snapIndex = snapIndex;
        editor.pixelsPerBeat = pixelsPerBeat;
        editor.playbackRate = playbackRate;
        editor.vortex = vortex;
        return editor;
    }

    private void seek(double timeMs) {
        viewPositionMs = Math.max(0, timeMs);
        if (audio != null && audio.isStarted()) audio.seekMs(viewPositionMs);
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

    // --------------------------------------------------------------------- Input

    private boolean shiftDown() {
        return hasShiftDown();
    }

    private void importSongFiles() {
        commitVisibleFields();
        String id = sanitizeId(saveId == null || saveId.isBlank() ? chart.title : saveId);
        if (id.isBlank()) id = "unnamed";
        Path target = SongLibrary.songsDir().resolve(id).toAbsolutePath().normalize();
        Path source = songImportRoot(target);
        if (source == null && entry == null) {
            setStatus("Could not find the song's source mod");
            return;
        }

        // Saving first guarantees that the imported files belong to a playable
        // local override and that its original chart/audio reference is present.
        saveChart();
        try {
            Files.createDirectories(target);
            int copied = copySongAudio(target);
            copied += copyOtherDifficultyCharts(target, id);
            copied += copySongIcon(source, target);
            SongLibrary.rescan();
            entry = SongLibrary.get(id);
            String sourceName = source == null ? "song source"
                    : (source.getFileName() == null ? source.toString() : source.getFileName().toString());
            setStatus("Imported complete song: " + copied + " file(s) from " + sourceName);
        } catch (Exception error) {
            setStatus("Song import failed: " + error.getMessage());
            FnfMod.LOGGER.error("Could not import complete song from {}", source, error);
        }
    }

    private Path songImportRoot(Path target) {
        LinkedHashSet<Path> candidates = new LinkedHashSet<>();
        if (originalDirectory != null) candidates.add(originalDirectory);
        if (entry != null) {
            if (entry.chartOriginRoot != null) candidates.add(entry.chartOriginRoot);
            if (entry.modRoot != null) candidates.add(entry.modRoot);
            if (entry.folder != null) candidates.add(entry.folder);
        }
        if (eventDefinitionRoot != null) candidates.add(eventDefinitionRoot);
        for (Path candidate : candidates) {
            Path normalized = candidate.toAbsolutePath().normalize();
            if (!normalized.equals(target) && Files.isDirectory(normalized)) return normalized;
        }
        return null;
    }

    private int copySongAudio(Path target) throws Exception {
        if (entry == null) return 0;
        LinkedHashSet<Path> files = new LinkedHashSet<>();
        addImportFile(files, entry.instFile);
        addImportFile(files, entry.voicesFile);
        addImportFile(files, entry.voicesPlayerFile);
        addImportFile(files, entry.voicesOpponentFile);
        for (SongEntry.VSliceVariation variation : entry.vsliceVariations.values()) {
            addImportFile(files, variation.instFile);
            addImportFile(files, variation.voicesFile);
            addImportFile(files, variation.voicesPlayerFile);
            addImportFile(files, variation.voicesOpponentFile);
        }
        int copied = 0;
        for (Path file : files) copied += copyFile(file, target.resolve(file.getFileName()));
        return copied;
    }

    private int copySongIcon(Path sourceRoot, Path target) throws Exception {
        Path icon = entry == null ? null : entry.opponentIconFile;
        if (icon == null && sourceRoot != null) {
            LinkedHashSet<String> names = new LinkedHashSet<>();
            if (entry != null && entry.opponentIcon != null && !entry.opponentIcon.isBlank()) {
                names.add(entry.opponentIcon);
            }
            if (chart.player2 != null && !chart.player2.isBlank()) names.add(chart.player2);
            for (String name : names) {
                for (String folder : List.of("images/icons", "icons", "images/characters", "")) {
                    Path directory = folder.isEmpty() ? sourceRoot : sourceRoot.resolve(folder);
                    for (String filename : List.of("icon-" + name + ".png", name + ".png")) {
                        Path candidate = directory.resolve(filename);
                        if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                            icon = candidate;
                            break;
                        }
                    }
                    if (icon != null) break;
                }
                if (icon != null) break;
            }
        }
        if (icon == null) return 0;
        return copyFile(icon, target.resolve("images").resolve("icons").resolve(icon.getFileName()));
    }

    private int copyOtherDifficultyCharts(Path target, String id) throws Exception {
        if (entry == null || entry.format != SongEntry.Format.LEGACY) return 0;
        int copied = 0;
        for (var chartFile : entry.legacyChartFiles.entrySet()) {
            String difficulty = chartFile.getKey();
            if (sameDifficulty(difficulty, loadedDifficulty)) continue;
            Path source = entry.chartOverrides.getOrDefault(difficulty, chartFile.getValue());
            String suffix = difficulty.equalsIgnoreCase("normal") ? "" : "-" + sanitizeId(difficulty);
            copied += copyFile(source, target.resolve(id + suffix + ".json"));
        }
        return copied;
    }

    private static boolean sameDifficulty(String first, String second) {
        if (first == null || second == null) return false;
        return first.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-")
                .equals(second.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-"));
    }

    private static void addImportFile(Set<Path> files, Path file) {
        if (file != null && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) files.add(file);
    }

    private static int copyFile(Path source, Path destination) throws Exception {
        if (source == null || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) return 0;
        Path normalizedSource = source.toAbsolutePath().normalize();
        Path normalizedDestination = destination.toAbsolutePath().normalize();
        if (normalizedSource.equals(normalizedDestination)) return 0;
        Files.createDirectories(normalizedDestination.getParent());
        Files.copy(normalizedSource, normalizedDestination, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES);
        return 1;
    }

    private boolean altDown() {
        return hasAltDown();
    }

    private boolean controlDown() {
        return hasControlDown();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
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
        if (keyCode == GLFW.GLFW_KEY_F12) { launchPlaytest(true); return true; }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            launchPlaytest(false);
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
            case GLFW.GLFW_KEY_R -> {
                if (isPlaying()) audio.pause();
                selectedNote = null;
                selectedNotes.clear();
                seek(shiftDown() ? 0 : chart.sectionStartMs(sectionIndexAt(viewPositionMs)));
                rebuildUi();
                yield true;
            }
            case GLFW.GLFW_KEY_Z -> {
                pixelsPerBeat = Math.min(192, pixelsPerBeat * 1.25);
                setStatus("Zoom: " + Math.round(pixelsPerBeat / DEFAULT_PIXELS_PER_BEAT * 100) + "%");
                yield true;
            }
            case GLFW.GLFW_KEY_X -> {
                pixelsPerBeat = Math.max(20, pixelsPerBeat / 1.25);
                setStatus("Zoom: " + Math.round(pixelsPerBeat / DEFAULT_PIXELS_PER_BEAT * 100) + "%");
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
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (helpVisible) return true;
        if (insideInfo(mouseX, mouseY)) {
            if (button == 0 && mouseY < infoY + 16) {
                draggingInfo = true;
                dragOffsetX = mouseX - infoX;
                dragOffsetY = mouseY - infoY;
            }
            return true;
        }
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
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
            double clickedBeat = yToBeat(mouseY - cw / 2.0);
            SongChart.Event closest = null;
            double closestPixels = Double.MAX_VALUE;
            for (SongChart.Event event : chart.events) {
                double pixels = Math.abs(conductor.beatAt(event.timeMs) - clickedBeat) * pixelsPerBeat;
                if (pixels < closestPixels) {
                    closestPixels = pixels;
                    closest = event;
                }
            }
            boolean hitEvent = closest != null && closestPixels <= Math.max(7, cw / 2.0);
            if (hitEvent) {
                if (!shiftDown() && !altDown()) {
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
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
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

        if (isPlaying()) {
            viewPositionMs = audio.positionMs();
            playCrossedHitsounds(viewPositionMs);
        }
        int sectionNow = sectionIndexAt(viewPositionMs);
        if (sectionNow != shownSection) rebuildUi();

        renderGrid(gui);
        renderControlPanel(gui);
        renderTopMenuBackground(gui);
        renderLabels(gui);

        draw(gui, "press F1 for help", 8, height - 12, 0xFFFFFFFF);
        if (System.currentTimeMillis() < statusUntil) draw(gui, status, 8, height - 24, 0xFFFFFF66);

        for (var renderable : renderables) renderable.render(gui, mouseX, mouseY, partialTick);
        renderInfoWindow(gui);
        if (helpVisible) renderHelpScreen(gui);
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
        double step = snapStepBeats();

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
            int eventY = (int) beatToY(beat) + cw / 2;
            int color = selectedEvents.contains(event) || event == selectedEvent
                    ? 0xFFFFFF44 : event.beforeSong ? 0xFF55DDFF : 0xFFFFA000;
            gui.fill(eventX + 3, eventY - 4, gx - 3, eventY + 4, color);
            drawCentered(gui, "E", eventX + cw / 2, eventY - font.lineHeight / 2, 0xFF201000);
        }

        for (SongChart.Note note : chart.notes) {
            double beat = conductor.beatAt(note.timeMs);
            if (beat < topBeat - 8 || beat > bottomBeat + 1) continue;
            int timeLineY = (int) beatToY(beat);
            int noteY = timeLineY + cw / 2;
            int column = (note.playerSide ? 4 : 0) + note.lane;
            int x = gx + column * cw;
            if (note.sustainMs > 0) {
                int endY = (int) beatToY(conductor.beatAt(note.timeMs + note.sustainMs)) + cw / 2;
                NoteStyle.drawHoldPiece(gui, note.lane, x + cw / 2f,
                        Math.min(noteY, endY), Math.max(noteY, endY), noteSize, endY < noteY);
            }
            if (noteY + noteSize / 2 >= top && noteY - noteSize / 2 <= bottom) {
                NoteStyle.drawNote(gui, note.lane, x + cw / 2f, noteY, noteSize);
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
            drawCentered(gui, glyphs[column % 4], gx + column * cw + cw / 2,
                    top - cw / 2 - font.lineHeight / 2, 0xFF8A8A8A);
        }

        int playheadY = centerY();
        gui.fill(eventX, playheadY, gx + gridWidth, playheadY + 1, 0xFFFF3333);

        if (vortex) {
            draw(gui, "VORTEX", gx, top - 10, 0xFFFF66FF);
            int previewY = playheadY + cw / 2;
            gui.fill(gx, playheadY, gx + gridWidth, playheadY + cw, 0x22FFFFFF);
            for (int column = 0; column < 8; column++) {
                int centerX = gx + column * cw + cw / 2;
                NoteStyle.drawNote(gui, column % 4, centerX, previewY, noteSize);
                draw(gui, String.valueOf(column + 1), gx + column * cw + 2,
                        previewY + cw / 2 - font.lineHeight, 0xFFFFFFFF);
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

    private void renderControlPanel(GuiGraphics gui) {
        int x = controlX();
        int bottom = Math.min(height - 26, 238);
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
            case FILE -> 88;
            case EDIT -> 136;
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
        draw(gui, "Beat Snap: " + snapText(), infoX + 8, y, 0xFFFFFFFF); y += 11;
        draw(gui, "Selected: " + selectedNotes.size() + " N / " + selectedEvents.size() + " E",
                infoX + 8, y, 0xFFFFFFFF);
    }

    private void renderHelpScreen(GuiGraphics gui) {
        gui.fill(0, 0, width, height, 0xEE08080A);
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

    private double snapStepBeats() { return 4.0 / SNAPS[snapIndex]; }

    private float beatToY(double beat) {
        double viewBeat = conductor.beatAt(Math.max(0, viewPositionMs));
        return (float) (centerY() + (beat - viewBeat) * pixelsPerBeat);
    }

    private double yToBeat(double y) {
        double viewBeat = conductor.beatAt(Math.max(0, viewPositionMs));
        return viewBeat + (y - centerY()) / pixelsPerBeat;
    }

    private void saveEventsOnly() {
        commitVisibleFields();
        String id = sanitizeId(saveId == null || saveId.isBlank() ? chart.title : saveId);
        if (id.isBlank()) id = "unnamed";
        try {
            Path directory = SongLibrary.songsDir().resolve(id);
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("events.json"), PsychChartWriter.writeEvents(chart));
            writeOriginalReference(directory, id);
            setStatus("Saved events.json");
            SongLibrary.rescan();
        } catch (Exception e) {
            setStatus("Event save failed: " + e.getMessage());
            FnfMod.LOGGER.error("Event save failed", e);
        }
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
        SongChart.Event added;
        if (selectedEvent != null) {
            added = selectedEvent.copy();
        } else {
            String value2 = eventValue2Draft.isBlank()
                    ? defaultEventValue2(eventTypeDraft, eventValue1Draft) : eventValue2Draft;
            added = new SongChart.Event(Math.max(0, eventTimeDraft), eventTypeDraft,
                    eventValue1Draft, value2, eventBeforeSongDraft);
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
        String value2 = eventValue2Draft.isBlank()
                ? defaultEventValue2(eventTypeDraft, eventValue1Draft) : eventValue2Draft;
        selectedEvent = new SongChart.Event(Math.max(0, time), eventTypeDraft,
                eventValue1Draft, value2, eventBeforeSongDraft);
        setEventDraft(selectedEvent);
        chart.events.add(selectedEvent);
        chart.sortEvents();
        activeTab = EditorTab.EVENTS;
        setStatus("Added event");
        rebuildUi();
    }

    private void setEventDraft(SongChart.Event event) {
        eventTypeDraft = event == null || event.name.isBlank() ? MINECRAFT_COMMAND_EVENT : event.name;
        eventValue1Draft = event == null ? "" : event.value1;
        eventValue2Draft = event == null || event.value2.isBlank()
                ? defaultEventValue2(eventTypeDraft, eventValue1Draft) : event.value2;
        eventTimeDraft = event == null ? Math.max(0, viewPositionMs) : event.timeMs;
        eventBeforeSongDraft = event != null && event.beforeSong;
        eventDraftInitialized = true;
        eventDropdownOpen = false;
    }

    private static boolean isMinecraftCommandType(String name) {
        return name != null && (name.equalsIgnoreCase(MINECRAFT_COMMAND_EVENT)
                || name.equalsIgnoreCase("Run Minecraft Command"));
    }

    private void selectEventType(String type) {
        boolean changed = !type.equals(eventTypeDraft);
        eventTypeDraft = type;
        eventDropdownOpen = false;
        if (changed && isCameraZoomType(type)) {
            eventValue1Draft = "0";
            eventValue2Draft = "smooth";
        } else if (changed && isCameraFocusType(type)) {
            eventValue1Draft = "player";
            eventValue2Draft = "smooth";
        } else if (changed && isMinecraftCommandType(type)) {
            eventValue1Draft = "";
            eventValue2Draft = "player";
        }
        if (selectedEvent != null) {
            selectedEvent.name = type;
            selectedEvent.value1 = eventValue1Draft;
            selectedEvent.value2 = eventValue2Draft;
        }
        rebuildUi();
    }

    private static boolean isCameraZoomType(String name) {
        return name != null && name.equalsIgnoreCase(CAMERA_ZOOM_EVENT);
    }

    private static boolean isCameraFocusType(String name) {
        return name != null && name.equalsIgnoreCase(CAMERA_FOCUS_EVENT);
    }

    private static String defaultEventValue2(String eventType, String value1) {
        if (isCameraZoomType(eventType)) return "smooth";
        if (isCameraFocusType(eventType)) return value1 == null || value1.isBlank() ? "" : "smooth";
        return "player";
    }

    private static String normalizedEase(String value) {
        if (value != null) {
            for (String candidate : GameplayCamera.EASES) {
                if (candidate.equalsIgnoreCase(value.trim())) return candidate;
            }
        }
        return "smooth";
    }

    private void cycleEventEase() {
        commitVisibleFields();
        String current = normalizedEase(eventValue2Draft);
        int index = 0;
        for (int i = 0; i < GameplayCamera.EASES.length; i++) {
            if (GameplayCamera.EASES[i].equals(current)) {
                index = i;
                break;
            }
        }
        eventValue2Draft = GameplayCamera.EASES[(index + 1) % GameplayCamera.EASES.length];
        if (selectedEvent != null) selectedEvent.value2 = eventValue2Draft;
        rebuildUi();
    }

    private void cycleCameraFocusTarget() {
        commitVisibleFields();
        String current = eventValue1Draft == null ? "" : eventValue1Draft.trim().toLowerCase(Locale.ROOT);
        eventValue1Draft = switch (current) {
            case "player" -> "opponent";
            case "opponent" -> "";
            default -> "player";
        };
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
        return yToBeat(screenY - cellWidth() / 2.0);
    }

    private double selectionScreenY(double beat) {
        return beatToY(beat) + cellWidth() / 2.0;
    }

    private double stepMs() {
        double beat = conductor.beatAt(Math.max(0, viewPositionMs));
        return conductor.timeOfBeat(beat + snapStepBeats()) - conductor.timeOfBeat(beat);
    }

    private void cycleSnap() {
        commitVisibleFields();
        snapIndex = (snapIndex + 1) % SNAPS.length;
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
        if (audio != null) audio.dispose();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
