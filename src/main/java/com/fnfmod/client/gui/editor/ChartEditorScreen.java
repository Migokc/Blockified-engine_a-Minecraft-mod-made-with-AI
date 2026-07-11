package com.fnfmod.client.gui.editor;

import com.fnfmod.FnfMod;
import com.fnfmod.chart.Conductor;
import com.fnfmod.chart.PsychChartWriter;
import com.fnfmod.chart.SongChart;
import com.fnfmod.client.ClientOptions;
import com.fnfmod.client.audio.SongPlayer;
import com.fnfmod.client.camera.GameplayCamera;
import com.fnfmod.client.render.NoteStyle;
import com.fnfmod.song.SongEntry;
import com.fnfmod.song.SongLibrary;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Psych-inspired chart editor for the normalized FNF chart format.
 *
 * The playhead remains fixed while the chart moves beneath it. Events are
 * represented in the interface but intentionally disabled until the mod has
 * an event model and a stable cross-engine event format.
 */
public final class ChartEditorScreen extends Screen {

    private static final int[] SNAPS = {4, 8, 12, 16, 24, 32, 64};
    private static final double PIXELS_PER_BEAT = 64.0;
    private static final int TAB_Y = 24;
    private static final int CONTROL_TOP = 40;

    private enum EditorTab { CHARTING, DATA, EVENTS, NOTE, SECTION, SONG }
    private enum TopMenu { NONE, FILE, EDIT, VIEW }

    private record UiLabel(String text, int x, int y, int color, boolean centered) {}

    private final String requestedSongId;
    private final String requestedDifficulty;
    private final SongChart suppliedChart;
    private final Path suppliedSongFolder;
    private final Path suppliedOriginalDirectory;
    private final List<UiLabel> labels = new ArrayList<>();
    private final List<SongChart.Note> sectionClipboard = new ArrayList<>();

    private SongChart chart;
    private Conductor conductor;
    private SongEntry entry;
    private SongPlayer audio;
    private String songId;
    private String saveId;
    private String loadedDifficulty = "normal";
    private String defaultNoteType = "";
    private Path originalDirectory;

    private double viewPositionMs;
    private int snapIndex = 3;
    private int shownSection = -1;
    private int hitsoundIndex;
    private boolean vortex;
    private SongChart.Note selectedNote;

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

    public ChartEditorScreen(String songId) {
        this(songId, null, null, null, null);
    }

    /** Opens the exact chart currently held by gameplay, including its active difficulty. */
    public ChartEditorScreen(String songId, String difficulty, SongChart chart, Path songFolder) {
        this(songId, difficulty, chart, songFolder, songFolder);
    }

    /** Opens gameplay's chart while retaining the original mod root used for inherited assets. */
    public ChartEditorScreen(String songId, String difficulty, SongChart chart,
                             Path songFolder, Path originalDirectory) {
        super(Component.literal("FNF Chart Editor"));
        this.requestedSongId = songId;
        this.requestedDifficulty = difficulty;
        this.suppliedChart = chart;
        this.suppliedSongFolder = songFolder;
        this.suppliedOriginalDirectory = originalDirectory;
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
        if (suppliedSongFolder != null) {
            entry = SongLibrary.scanSongDir(suppliedSongFolder);
        }
        if (entry == null && requestedSongId != null) {
            entry = SongLibrary.get(requestedSongId);
        }
        originalDirectory = suppliedOriginalDirectory;
        if (originalDirectory == null && entry != null) {
            originalDirectory = entry.modRoot != null ? entry.modRoot : entry.folder;
        }
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
        Button events = button(x + half + 4, y, half, "Events: OFF", b -> {});
        events.active = false;
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

        Button rate = button(x, y, w, "Playback Rate: 1.0", b -> {});
        rate.active = false;
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
        label("Events", x, 43, 0xFFDDDDDD, false);
        EditBox eventName = labeledBox("Event", x, y, w - 62, "", "event name");
        eventName.active = false;
        Button minus = button(x + w - 56, y + 10, 26, "-", b -> {});
        Button plus = button(x + w - 26, y + 10, 26, "+", b -> {});
        minus.active = plus.active = false;
        int half = (w - 4) / 2;
        EditBox value1 = labeledBox("Value 1", x, y + 38, half, "", "value 1");
        EditBox value2 = labeledBox("Value 2", x + half + 4, y + 38, half, "", "value 2");
        value1.active = value2.active = false;
        label("Events are visible by design, but disabled until their format is decided.",
                x, y + 78, 0xFF999999, false);
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
            Button saveEvents = button(x + 4, y, w - 8, "Save Events...", b -> {}); saveEvents.active = false; y += 16;
            button(x + 4, y, w - 8, "Exit", b -> onClose());
        } else if (openMenu == TopMenu.EDIT) {
            Button undo = button(x + 4, y, w - 8, "Undo", b -> {}); undo.active = false; y += 16;
            Button redo = button(x + 4, y, w - 8, "Redo", b -> {}); redo.active = false; y += 16;
            button(x + 4, y, w - 8, "Copy Section", b -> copySection(shownSection)); y += 16;
            button(x + 4, y, w - 8, "Paste Section", b -> pasteSection(shownSection)); y += 16;
            button(x + 4, y, w - 8, "Clear All Notes", b -> {
                chart.notes.clear();
                selectedNote = null;
                setStatus("Cleared all notes");
            }); y += 16;
            Button clearEvents = button(x + 4, y, w - 8, "Clear All Events", b -> {}); clearEvents.active = false;
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

    private void toggleNote(int column, double timeMs, boolean removeOnly) {
        commitVisibleFields();
        SongChart.Note existing = findNote(column, timeMs, stepMs() / 2.0);
        if (existing != null) {
            chart.notes.remove(existing);
            if (selectedNote == existing) selectedNote = null;
            setStatus("Removed note");
        } else if (!removeOnly) {
            SongChart.Note note = new SongChart.Note(timeMs, column % 4, column >= 4, 0, defaultNoteType);
            chart.notes.add(note);
            chart.sortNotes();
            selectedNote = note;
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
        chart.notes.removeAll(removed);
        if (removed.contains(selectedNote)) selectedNote = null;
        setStatus("Cleared " + removed.size() + " notes");
    }

    private void swapSection(int sectionIndex) {
        commitVisibleFields();
        for (SongChart.Note note : notesInSection(sectionIndex)) note.playerSide = !note.playerSide;
        setStatus("Swapped section sides");
    }

    private void mirrorSection(int sectionIndex) {
        commitVisibleFields();
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
        chart.notes.addAll(copies);
        chart.sortNotes();
        setStatus("Added " + copies.size() + " duet notes");
    }

    private void adjustSustain(double deltaMs) {
        if (selectedNote == null) return;
        selectedNote.sustainMs = Math.max(0, selectedNote.sustainMs + deltaMs);
        setStatus("Sustain: " + (int) selectedNote.sustainMs + "ms");
        if (activeTab == EditorTab.NOTE) rebuildUi();
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
            if (originalDirectory != null) {
                Files.writeString(directory.resolve(SongLibrary.ORIGINAL_DIRECTORY_FILE),
                        originalDirectory.toAbsolutePath().normalize().toString());
            }
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

    private void seek(double timeMs) {
        viewPositionMs = Math.max(0, timeMs);
        if (audio != null && audio.isStarted()) audio.seekMs(viewPositionMs);
        resetHitsoundIndex(viewPositionMs);
    }

    private void scrub(double steps) {
        commitVisibleFields();
        double beat = conductor.beatAt(Math.max(0, viewPositionMs));
        seek(conductor.timeOfBeat(Math.max(0, beat - steps * snapStepBeats())));
    }

    private void changeSection(int delta) {
        commitVisibleFields();
        int target = Math.max(0, sectionIndexAt(viewPositionMs) + delta);
        ensureSection(target);
        selectedNote = null;
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

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (getFocused() instanceof EditBox) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                commitVisibleFields();
                setFocused(null);
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        if (hasControlDown() && keyCode == GLFW.GLFW_KEY_S) {
            saveChart();
            return true;
        }
        if (vortex && keyCode >= GLFW.GLFW_KEY_1 && keyCode <= GLFW.GLFW_KEY_8) {
            int column = keyCode - GLFW.GLFW_KEY_1;
            double beat = conductor.beatAt(Math.max(0, viewPositionMs));
            double snapped = Math.max(0, Math.round(beat / snapStepBeats()) * snapStepBeats());
            toggleNote(column, conductor.timeOfBeat(snapped), false);
            return true;
        }

        return switch (keyCode) {
            case GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_LEFT -> { changeSection(-1); yield true; }
            case GLFW.GLFW_KEY_D, GLFW.GLFW_KEY_RIGHT -> { changeSection(1); yield true; }
            case GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_UP -> { scrub(1); yield true; }
            case GLFW.GLFW_KEY_S, GLFW.GLFW_KEY_DOWN -> { scrub(-1); yield true; }
            case GLFW.GLFW_KEY_SPACE -> { togglePlayback(); yield true; }
            case GLFW.GLFW_KEY_V -> {
                vortex = !vortex;
                if (vortex && !isPlaying()) snapPlayheadToGrid();
                rebuildUi();
                yield true;
            }
            case GLFW.GLFW_KEY_E -> { adjustSustain(stepMs()); yield true; }
            case GLFW.GLFW_KEY_Q -> { adjustSustain(-stepMs()); yield true; }
            case GLFW.GLFW_KEY_DELETE -> {
                if (selectedNote != null) {
                    chart.notes.remove(selectedNote);
                    selectedNote = null;
                    if (activeTab == EditorTab.NOTE) rebuildUi();
                }
                yield true;
            }
            default -> super.keyPressed(keyCode, scanCode, modifiers);
        };
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
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
        if (mouseX >= gx && mouseX < gx + 8 * cw && mouseY >= gridTop() && mouseY < gridBottom()) {
            int column = (int) ((mouseX - gx) / cw);
            double beat = yToBeat(mouseY);
            double snapped = Math.max(0, Math.floor(beat / snapStepBeats() + 1.0e-6) * snapStepBeats());
            double time = conductor.timeOfBeat(snapped);
            if (button == 0 && hasShiftDown()) {
                SongChart.Note found = findNote(column, time, stepMs() / 2.0);
                if (found != null) {
                    selectedNote = found;
                    defaultNoteType = found.noteType == null ? "" : found.noteType;
                    setStatus("Selected note (E/Q sustain, Delete removes)");
                    if (activeTab == EditorTab.NOTE) rebuildUi();
                }
            } else if (button == 0 || button == 1) {
                toggleNote(column, time, button == 1);
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
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (draggingInfo && button == 0) {
            draggingInfo = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (insideInfo(mouseX, mouseY)) return true;
        if (mouseX >= gridX() && mouseX < gridX() + 8 * cellWidth()
                && mouseY >= gridTop() && mouseY < gridBottom()) {
            scrub(scrollY);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
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

        String help = "Wheel/W/S scrub | Space play | Click place | Shift+Click select | E/Q sustain | A/D section | Ctrl+S save";
        draw(gui, help, 8, height - 12, 0xFFAAAAAA);
        if (System.currentTimeMillis() < statusUntil) draw(gui, status, 8, height - 24, 0xFFFFFF66);

        for (var renderable : renderables) renderable.render(gui, mouseX, mouseY, partialTick);
        renderInfoWindow(gui);
    }

    private void renderGrid(GuiGraphics gui) {
        NoteStyle.setDrawAlpha(1f);
        NoteStyle.setMissed(false);
        int gx = gridX();
        int cw = cellWidth();
        int top = gridTop();
        int bottom = gridBottom();
        int gridWidth = cw * 8;
        float noteSize = Math.min(cw - 3, 24);
        double step = snapStepBeats();

        gui.fill(gx, top - cw, gx + gridWidth, top, 0xFFE8E8E8);
        gui.fill(gx, top, gx + gridWidth, bottom, 0xFFE0E0E0);
        gui.enableScissor(gx, top, gx + gridWidth, bottom);

        double topBeat = yToBeat(top);
        double bottomBeat = yToBeat(bottom);
        long firstBand = (long) Math.floor(topBeat / step);
        for (long band = firstBand; band * step <= bottomBeat; band++) {
            int y0 = (int) beatToY(band * step);
            int y1 = (int) beatToY((band + 1) * step);
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
            gui.fill(gx, y, gx + gridWidth, y + 1, wholeBeat ? 0x66444444 : 0x22444444);
        }

        double bottomTime = conductor.timeOfBeat(Math.max(0, bottomBeat));
        int firstSection = Math.max(0, sectionIndexAt(conductor.timeOfBeat(Math.max(0, topBeat))) - 1);
        for (int section = firstSection; section < 4096; section++) {
            double time = chart.sectionStartMs(section);
            if (time > bottomTime) break;
            int y = (int) beatToY(conductor.beatAt(time));
            if (y >= top - 1 && y <= bottom + 1) gui.fill(gx, y, gx + gridWidth, y + 1, 0xFF9D3D3D);
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
                if (note == selectedNote) {
                    int radius = (int) noteSize / 2 + 2;
                    gui.renderOutline(x + cw / 2 - radius, noteY - radius, radius * 2, radius * 2, 0xFFFFFFFF);
                }
                if (note.noteType != null && !note.noteType.isEmpty()) draw(gui, "*", x + cw - 6, noteY - 4, 0xFF111111);
            }
        }
        gui.disableScissor();

        gui.fill(gx + 4 * cw - 1, top - cw, gx + 4 * cw + 1, bottom, 0xFF222222);
        String[] glyphs = {"<", "v", "^", ">"};
        for (int column = 0; column < 8; column++) {
            drawCentered(gui, glyphs[column % 4], gx + column * cw + cw / 2,
                    top - cw / 2 - font.lineHeight / 2, 0xFF8A8A8A);
        }

        int playheadY = centerY();
        gui.fill(gx, playheadY, gx + gridWidth, playheadY + 1, 0xFFFF3333);
        gui.fill(gx - 10, top, gx - 8, bottom, 0xFF222222);
        gui.fill(gx - 16, playheadY - 3, gx - 10, playheadY + 3, 0xFFFFB000);

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
            case EDIT -> 104;
            case VIEW -> 56;
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
        draw(gui, "Selected: " + (selectedNote == null ? 0 : 1), infoX + 8, y, 0xFFFFFFFF);
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
        return (float) (centerY() + (beat - viewBeat) * PIXELS_PER_BEAT);
    }

    private double yToBeat(double y) {
        double viewBeat = conductor.beatAt(Math.max(0, viewPositionMs));
        return viewBeat + (y - centerY()) / PIXELS_PER_BEAT;
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
