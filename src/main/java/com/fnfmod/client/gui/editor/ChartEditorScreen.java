package com.fnfmod.client.gui.editor;

import com.fnfmod.FnfMod;
import com.fnfmod.chart.Conductor;
import com.fnfmod.chart.PsychChartWriter;
import com.fnfmod.chart.SongChart;
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
 * Psych Engine style chart editor. The playhead (red line) is fixed at the
 * center of the grid; the chart scrolls underneath it. Columns 0-3 = opponent,
 * 4-7 = player.
 *
 * Controls: mouse wheel scrubs (playback follows), Space play/pause,
 * click = place/remove, right-click = remove, Shift+click = select,
 * E/Q = sustain +/-, Del = delete selection, A/D = jump a section,
 * V = vortex mode (keys 1-8 place notes at the playhead).
 */
public class ChartEditorScreen extends Screen {

    private static final int[] SNAPS = {4, 8, 12, 16, 24, 32, 64};
    private static final double PX_PER_BEAT = 64;

    private SongChart chart;
    private Conductor conductor;
    private String songId;
    private SongEntry entry;
    private SongPlayer audio;

    /** song time (ms) at the centered playhead */
    private double viewPosMs = 0;
    private int snapIndex = 3; // 16ths
    private boolean vortex = false;
    private SongChart.Note selectedNote;
    private String status = "";
    private long statusUntil;
    /** next chart note to hitsound-tick during playback */
    private int tickIndex = 0;
    /** section the panel widgets currently show */
    private int widgetSection = -1;

    private enum EditorTab { CHARTING, DATA, EVENTS, NOTE, SECTION, SONG }
    private enum TopMenu { NONE, FILE, EDIT, VIEW }
    private EditorTab activeTab = EditorTab.SONG;
    private TopMenu topMenu = TopMenu.NONE;
    private int infoPanelX = -1;
    private int infoPanelY = -1;
    private boolean draggingInfoPanel;
    private double infoDragOffsetX;
    private double infoDragOffsetY;

    // widgets
    private EditBox titleBox, bpmBox, speedBox, noteTypeBox, sectionBpmBox, sectionBeatsBox, saveNameBox;
    private EditBox sustainBox, hitTimeBox;
    private List<SongChart.Note> clipboard = new ArrayList<>();
    // label rows recorded while building so text can't drift from the widgets
    private int ySong, yBpm, ySecBpm, yBeats, yType, yFile;

    public ChartEditorScreen(String songId) {
        super(Component.literal("FNF Chart Editor"));
        this.songId = songId;
    }

    @Override
    protected void init() {
        if (chart == null) {
            loadInitial();
        }
        int infoW = infoWidth();
        int infoH = infoHeight();
        if (infoPanelX < 0 || infoPanelY < 0) {
            infoPanelX = 16;
            infoPanelY = 34;
        } else if (infoW > 0) {
            infoPanelX = Mth.clamp(infoPanelX, 0, Math.max(0, width - infoW));
            infoPanelY = Mth.clamp(infoPanelY, 22, Math.max(22, height - infoH - 12));
        }
        buildWidgets();
    }

    private void loadInitial() {
        SongLibrary.rescan();
        String diff = "normal";
        if (songId != null) {
            entry = SongLibrary.get(songId);
            if (entry != null) {
                try {
                    diff = entry.difficulties.contains("normal") ? "normal" : entry.difficulties.get(0);
                    chart = SongLibrary.loadChart(entry, diff);
                } catch (Exception e) {
                    FnfMod.LOGGER.warn("Editor: failed to load {}: {}", songId, e.toString());
                }
            }
        }
        if (chart == null) {
            chart = new SongChart();
            chart.title = songId != null ? songId : "new-song";
            chart.bpmChanges.add(new SongChart.BpmChange(0, chart.startBpm));
            songId = null;
        }
        chart.ensureSectionsCoverNotes();
        for (int i = 0; i < 8; i++) chart.sections.add(new SongChart.Section());
        conductor = new Conductor(chart);

        if (entry != null && entry.instFor(diff) != null) {
            try {
                audio = new SongPlayer();
                audio.load(entry.instFor(diff), entry.voicesFor(diff),
                        entry.voicesPlayerFor(diff), entry.voicesOpponentFor(diff));
            } catch (Exception e) {
                audio = null;
                FnfMod.LOGGER.warn("Editor: no audio playback: {}", e.toString());
            }
        }
    }

    private void buildWidgets() {
        clearWidgets();
        widgetSection = sectionIndexAt(viewPosMs);
        titleBox = bpmBox = speedBox = noteTypeBox = sectionBpmBox = sectionBeatsBox = saveNameBox = null;
        sustainBox = hitTimeBox = null;

        int menuX = 16;
        String[] menuNames = {"File", "Edit", "View"};
        TopMenu[] menus = {TopMenu.FILE, TopMenu.EDIT, TopMenu.VIEW};
        for (int i = 0; i < menuNames.length; i++) {
            final TopMenu menu = menus[i];
            addRenderableWidget(Button.builder(Component.literal(menuNames[i]), b -> {
                topMenu = topMenu == menu ? TopMenu.NONE : menu;
                buildWidgets();
            }).bounds(menuX + i * 62, 8, 62, 14).build());
        }

        int px = panelX(), pw = panelWidth();
        EditorTab[] tabs = EditorTab.values();
        for (int i = 0; i < tabs.length; i++) {
            final EditorTab tab = tabs[i];
            String label = pw < 240 ? switch (tab) {
                case CHARTING -> "Chart"; case EVENTS -> "Event"; case SECTION -> "Sect";
                default -> titleCase(tab.name());
            } : titleCase(tab.name());
            int x0 = px + i * pw / tabs.length;
            int x1 = px + (i + 1) * pw / tabs.length;
            Button button = addRenderableWidget(Button.builder(Component.literal(label), b -> {
                applyBoxes();
                activeTab = tab;
                topMenu = TopMenu.NONE;
                buildWidgets();
            }).bounds(x0, 24, Math.max(1, x1 - x0), 14).build());
            button.active = tab != activeTab;
        }

        switch (activeTab) {
            case CHARTING -> buildChartingTab(px, pw);
            case DATA -> buildDataTab(px, pw);
            case EVENTS -> buildEventsTab(px, pw);
            case NOTE -> buildNoteTab(px, pw);
            case SECTION -> buildSectionTab(px, pw);
            case SONG -> buildSongTab(px, pw);
        }
        buildTopMenu(menuX);
    }

    private static String titleCase(String s) {
        return s.substring(0, 1) + s.substring(1).toLowerCase(Locale.ROOT);
    }

    private EditBox box(int x, int y, int w, String value, String hint) {
        EditBox b = addRenderableWidget(new EditBox(font, x, y, w, 14, Component.literal(hint)));
        b.setValue(value == null ? "" : value);
        if (!hint.isEmpty()) b.setHint(Component.literal(hint));
        return b;
    }

    private Button button(int x, int y, int w, String text, Button.OnPress action) {
        return addRenderableWidget(Button.builder(Component.literal(text), action).bounds(x, y, w, 14).build());
    }

    private void buildSongTab(int x, int w) {
        int y = 54;
        titleBox = box(x + 8, y, w - 16, chart.title, "Song Name");
        y += 20;
        bpmBox = box(x + 8, y, (w - 24) / 2, trim(chart.startBpm), "BPM");
        speedBox = box(x + 16 + (w - 24) / 2, y, (w - 24) / 2, trim(chart.speed), "Scroll Speed");
        y += 20;
        button(x + 8, y, w - 16, voicesLabel().getString(), b -> {
            chart.needsVoices = !chart.needsVoices;
            b.setMessage(voicesLabel());
        });
    }

    private void buildSectionTab(int x, int w) {
        int y = 50, half = (w - 20) / 2;
        button(x + 8, y, half, flagLabel("Must Hit", sec().mustHit).getString(), b -> {
            sec().mustHit = !sec().mustHit; b.setMessage(flagLabel("Must Hit", sec().mustHit));
        });
        button(x + 12 + half, y, half, flagLabel("GF Section", sec().gfSection).getString(), b -> {
            sec().gfSection = !sec().gfSection; b.setMessage(flagLabel("GF Section", sec().gfSection));
        });
        y += 18;
        button(x + 8, y, half, flagLabel("Alt Anim", sec().altAnim).getString(), b -> {
            sec().altAnim = !sec().altAnim; b.setMessage(flagLabel("Alt Anim", sec().altAnim));
        });
        button(x + 12 + half, y, half, flagLabel("Change BPM", sec().changeBPM).getString(), b -> {
            sec().changeBPM = !sec().changeBPM;
            if (sec().changeBPM && sec().bpm <= 0) sec().bpm = chart.bpmForSection(widgetSection);
            chart.rebuildBpmMap(); buildWidgets();
        });
        y += 20;
        sectionBpmBox = box(x + 8, y, half, sec().changeBPM ? trim(sec().bpm) : "", "Section BPM");
        sectionBeatsBox = box(x + 12 + half, y, half, trim(sec().sectionBeats), "Beats per Section");
        y += 20;
        button(x + 8, y, half, "Copy Section", b -> copySection());
        button(x + 12 + half, y, half, "Paste Section", b -> pasteSection());
        y += 18;
        button(x + 8, y, half, "Clear", b -> clearSection());
        Button events = button(x + 12 + half, y, half, "Events: OFF", b -> {});
        events.active = false;
        y += 18;
        button(x + 8, y, half, "Swap Section", b -> swapSection());
        button(x + 12 + half, y, half, camEaseLabel().getString(), b -> {
            String[] eases = GameplayCamera.EASES;
            int i = 0;
            for (int k = 0; k < eases.length; k++) if (eases[k].equals(sec().camEase)) i = k;
            sec().camEase = eases[(i + 1) % eases.length]; b.setMessage(camEaseLabel());
        });
    }

    private void buildNoteTab(int x, int w) {
        int y = 54;
        sustainBox = box(x + 8, y, w - 16, selectedNote == null ? "" : trim(selectedNote.sustainMs), "Sustain length (ms)");
        y += 20;
        hitTimeBox = box(x + 8, y, w - 16, selectedNote == null ? "" : trim(selectedNote.timeMs), "Note hit time (ms)");
        y += 20;
        noteTypeBox = box(x + 8, y, w - 16,
                selectedNote == null || selectedNote.noteType == null ? "" : selectedNote.noteType, "Note Type");
    }

    private void buildChartingTab(int x, int w) {
        int y = 54;
        Button rate = button(x + 8, y, w - 16, "Playback Rate: 1.0", b -> {});
        rate.active = false;
        y += 20;
        button(x + 8, y, w - 16, "Beat Snap: " + SNAPS[snapIndex] + " / " + SNAPS[snapIndex], b -> {
            snapIndex = (snapIndex + 1) % SNAPS.length;
            if (vortex && !isPlaying()) snapPlayheadToGrid();
            b.setMessage(Component.literal("Beat Snap: " + SNAPS[snapIndex] + " / " + SNAPS[snapIndex]));
        });
        y += 18;
        Button vortexBtn = button(x + 8, y, w - 16, "Vortex Editor: " + (vortex ? "ON" : "OFF"), b -> {
            vortex = !vortex;
            if (vortex && !isPlaying()) snapPlayheadToGrid();
            b.setMessage(Component.literal("Vortex Editor: " + (vortex ? "ON" : "OFF")));
        });
        vortexBtn.setTooltip(Tooltip.create(Component.literal("Keys 1-8 place notes at the playhead")));
        y += 22;
        var opts = com.fnfmod.client.ClientOptions.get();
        int half = (w - 20) / 2;
        button(x + 8, y, half, "Hitsound P: " + (opts.editorHitsoundPlayer ? "ON" : "OFF"), b -> {
            opts.editorHitsoundPlayer = !opts.editorHitsoundPlayer; com.fnfmod.client.ClientOptions.save(); buildWidgets();
        });
        button(x + 12 + half, y, half, "Hitsound O: " + (opts.editorHitsoundOpponent ? "ON" : "OFF"), b -> {
            opts.editorHitsoundOpponent = !opts.editorHitsoundOpponent; com.fnfmod.client.ClientOptions.save(); buildWidgets();
        });
    }

    private void buildDataTab(int x, int w) {
        int y = 54;
        String[] hints = {"Game Over Character", "Death Sound", "Loop Music", "Retry Music", "Note Texture", "Note Splashes Texture"};
        for (String hint : hints) {
            EditBox b = box(x + 8, y, w - 16, "", hint);
            b.active = false;
            y += 20;
        }
    }

    private void buildEventsTab(int x, int w) {
        int y = 54;
        EditBox event = box(x + 8, y, w - 80, "", "Event"); event.active = false;
        Button minus = button(x + w - 66, y, 26, "-", b -> {}); minus.active = false;
        Button plus = button(x + w - 36, y, 26, "+", b -> {}); plus.active = false;
        y += 26;
        int half = (w - 20) / 2;
        EditBox v1 = box(x + 8, y, half, "", "Value 1"); v1.active = false;
        EditBox v2 = box(x + 12 + half, y, half, "", "Value 2"); v2.active = false;
    }

    private void buildTopMenu(int x) {
        if (topMenu == TopMenu.NONE) return;
        int y = 24, w = 124;
        if (topMenu == TopMenu.FILE) {
            saveNameBox = box(x, y, w, songId != null ? songId : sanitizeId(chart.title), "Chart filename");
            y += 18;
            button(x, y, w, "Save", b -> save()); y += 16;
            Button eventOpen = button(x, y, w, "Open Events...", b -> {}); eventOpen.active = false; y += 16;
            Button eventSave = button(x, y, w, "Save Events...", b -> {}); eventSave.active = false; y += 16;
            button(x, y, w, "Exit", b -> onClose());
        } else if (topMenu == TopMenu.EDIT) {
            button(x, y, w, "Copy Section", b -> copySection()); y += 16;
            button(x, y, w, "Paste Section", b -> pasteSection()); y += 16;
            button(x, y, w, "Clear All Notes", b -> { chart.notes.clear(); selectedNote = null; }); y += 16;
            Button clearEvents = button(x, y, w, "Clear All Events", b -> {}); clearEvents.active = false;
        } else {
            button(x, y, w, "Beat Snap: " + SNAPS[snapIndex], b -> { snapIndex = (snapIndex + 1) % SNAPS.length; buildWidgets(); }); y += 16;
            button(x, y, w, "Vortex Editor " + (vortex ? "ON" : "OFF"), b -> { vortex = !vortex; buildWidgets(); }); y += 16;
            Button wave = button(x, y, w, "Waveform...", b -> {}); wave.active = false;
        }
    }

    private Component voicesLabel() {
        return Component.literal("Voices: " + (chart.needsVoices ? "ON" : "OFF"));
    }

    private Component flagLabel(String name, boolean v) {
        return Component.literal(name + ": " + (v ? "ON" : "OFF"));
    }

    private Component camEaseLabel() {
        return Component.literal("Cam Ease: " + sec().camEase);
    }

    private static String trim(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static String sanitizeId(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
    }

    // ------------------------------------------------------------------ sections

    private int sectionIndexAt(double ms) {
        int i = 0;
        while (i < 4095 && chart.sectionStartMs(i + 1) <= ms + 0.01) i++;
        return i;
    }

    private SongChart.Section sec() {
        int idx = sectionIndexAt(viewPosMs);
        while (idx >= chart.sections.size()) chart.sections.add(new SongChart.Section());
        return chart.sections.get(idx);
    }

    // ------------------------------------------------------------------ layout

    private int panelWidth() { return Mth.clamp(width / 3, 180, 300); }
    private int panelX() { return width - panelWidth() - 12; }
    private int infoWidth() { return width >= 560 ? Mth.clamp(width / 6, 105, 150) : 0; }
    private int infoHeight() { return Math.min(150, height - 64); }
    private int cellW() {
        int room = Math.min(width / 2 - infoWidth() - 22, panelX() - width / 2 - 18) * 2;
        return Mth.clamp(room / 8, 14, 30);
    }
    private int gridX() { return width / 2 - 4 * cellW(); }
    private int gridTop() { return 58; }
    private int gridBottom() { return height - 24; }
    private int centerY() { return (gridTop() + gridBottom()) / 2; }

    private double lineStepBeats() {
        return 4.0 / SNAPS[snapIndex];
    }

    private float beatToY(double beat) {
        double viewBeat = conductor.beatAt(Math.max(0, viewPosMs));
        return (float) (centerY() + (beat - viewBeat) * PX_PER_BEAT);
    }

    private double yToBeat(double y) {
        double viewBeat = conductor.beatAt(Math.max(0, viewPosMs));
        return viewBeat + (y - centerY()) / PX_PER_BEAT;
    }

    /** snap step length in ms around the playhead */
    private double stepMs() {
        double beat = conductor.beatAt(Math.max(0, viewPosMs));
        return conductor.timeOfBeat(beat + lineStepBeats()) - conductor.timeOfBeat(beat);
    }

    // ------------------------------------------------------------------ edit ops

    private void applyBoxes() {
        if (titleBox != null && !titleBox.getValue().isBlank()) chart.title = titleBox.getValue();
        try { if (bpmBox != null) chart.startBpm = Double.parseDouble(bpmBox.getValue()); } catch (NumberFormatException ignored) {}
        try { if (speedBox != null) chart.speed = Double.parseDouble(speedBox.getValue()); } catch (NumberFormatException ignored) {}
        try {
            if (sectionBpmBox != null) {
                double b = Double.parseDouble(sectionBpmBox.getValue());
                if (sec().changeBPM && b > 0) sec().bpm = b;
            }
        } catch (NumberFormatException ignored) {}
        try {
            if (sectionBeatsBox != null) {
                double beats = Double.parseDouble(sectionBeatsBox.getValue());
                if (beats > 0 && beats <= 64) sec().sectionBeats = beats;
            }
        } catch (NumberFormatException ignored) {}
        if (selectedNote != null) {
            try { if (sustainBox != null) selectedNote.sustainMs = Math.max(0, Double.parseDouble(sustainBox.getValue())); }
            catch (NumberFormatException ignored) {}
            try { if (hitTimeBox != null) selectedNote.timeMs = Math.max(0, Double.parseDouble(hitTimeBox.getValue())); }
            catch (NumberFormatException ignored) {}
            if (noteTypeBox != null) selectedNote.noteType = noteTypeBox.getValue().trim();
            chart.sortNotes();
        }
        chart.rebuildBpmMap();
    }

    private List<SongChart.Note> notesInSection(int sectionIdx) {
        double start = chart.sectionStartMs(sectionIdx);
        double end = chart.sectionStartMs(sectionIdx + 1);
        List<SongChart.Note> out = new ArrayList<>();
        for (SongChart.Note n : chart.notes) {
            if (n.timeMs >= start - 0.01 && n.timeMs < end - 0.01) out.add(n);
        }
        return out;
    }

    private SongChart.Note findNoteAt(int col, double timeMs, double toleranceMs) {
        int lane = col % 4;
        boolean playerSide = col >= 4;
        for (SongChart.Note n : chart.notes) {
            if (n.lane == lane && n.playerSide == playerSide && Math.abs(n.timeMs - timeMs) < toleranceMs) {
                return n;
            }
        }
        return null;
    }

    private void placeOrRemoveAt(int col, double timeMs, boolean removeOnly) {
        applyBoxes();
        SongChart.Note existing = findNoteAt(col, timeMs, stepMs() / 2);
        if (existing != null) {
            chart.notes.remove(existing);
            if (selectedNote == existing) selectedNote = null;
            setStatus("Removed note");
            return;
        }
        if (removeOnly) return;
        String noteType = noteTypeBox == null ? "" : noteTypeBox.getValue().trim();
        SongChart.Note n = new SongChart.Note(timeMs, col % 4, col >= 4, 0, noteType);
        chart.notes.add(n);
        chart.sortNotes();
        selectedNote = n;
        setStatus("Placed note @ " + (int) timeMs + "ms");
    }

    private void copySection() {
        int idx = sectionIndexAt(viewPosMs);
        clipboard = new ArrayList<>();
        double start = chart.sectionStartMs(idx);
        for (SongChart.Note n : notesInSection(idx)) {
            SongChart.Note c = n.copy();
            c.timeMs -= start;
            clipboard.add(c);
        }
        setStatus("Copied " + clipboard.size() + " notes");
    }

    private void pasteSection() {
        double start = chart.sectionStartMs(sectionIndexAt(viewPosMs));
        for (SongChart.Note c : clipboard) {
            SongChart.Note n = c.copy();
            n.timeMs += start;
            chart.notes.add(n);
        }
        chart.sortNotes();
        setStatus("Pasted " + clipboard.size() + " notes");
    }

    private void clearSection() {
        List<SongChart.Note> del = notesInSection(sectionIndexAt(viewPosMs));
        chart.notes.removeAll(del);
        if (del.contains(selectedNote)) selectedNote = null;
        setStatus("Cleared " + del.size() + " notes");
    }

    private void swapSection() {
        for (SongChart.Note n : notesInSection(sectionIndexAt(viewPosMs))) {
            n.playerSide = !n.playerSide;
        }
        setStatus("Swapped sides");
    }

    private void save() {
        applyBoxes();
        String requested = saveNameBox == null || saveNameBox.getValue().isBlank() ? chart.title : saveNameBox.getValue();
        String id = sanitizeId(requested);
        if (id.isBlank()) id = "unnamed";
        try {
            Path dir = SongLibrary.songsDir().resolve(id);
            Files.createDirectories(dir);
            Path file = dir.resolve(id + ".json");
            Files.writeString(file, PsychChartWriter.write(chart));
            songId = id;
            SongLibrary.rescan();
            setStatus("Saved to " + file.getFileName() + " (drop Inst.ogg/Voices.ogg next to it)");
        } catch (Exception e) {
            setStatus("Save failed: " + e.getMessage());
            FnfMod.LOGGER.error("Chart save failed", e);
        }
    }

    private void setStatus(String s) {
        status = s;
        statusUntil = System.currentTimeMillis() + 4000;
    }

    // ------------------------------------------------------------------ playback

    private boolean isPlaying() {
        return audio != null && audio.isStarted() && !audio.isPaused();
    }

    private void togglePlay() {
        if (audio == null) {
            setStatus("No Inst.ogg for this song - no playback");
            return;
        }
        if (isPlaying()) {
            viewPosMs = audio.positionMs();
            audio.pause();
            if (vortex) snapPlayheadToGrid();
        } else if (audio.isPaused()) {
            audio.seekMs(viewPosMs);
            audio.resume();
            resetTickIndex(viewPosMs);
        } else {
            audio.start();
            audio.seekMs(viewPosMs);
            resetTickIndex(viewPosMs);
        }
    }

    /** Aligns the fixed playhead to the active snap division, including across BPM changes. */
    private void snapPlayheadToGrid() {
        double beat = conductor.beatAt(Math.max(0, viewPosMs));
        double snappedBeat = Math.max(0, Math.round(beat / lineStepBeats()) * lineStepBeats());
        seekTo(conductor.timeOfBeat(snappedBeat));
    }

    /** Moves the playhead; running playback continues from there. */
    private void seekTo(double ms) {
        applyBoxes();
        viewPosMs = Math.max(0, ms);
        if (audio != null && audio.isStarted()) {
            audio.seekMs(viewPosMs);
        }
        resetTickIndex(viewPosMs);
    }

    private void changeSection(int delta) {
        int idx = Math.max(0, sectionIndexAt(viewPosMs) + delta);
        while (idx >= chart.sections.size()) chart.sections.add(new SongChart.Section());
        selectedNote = null;
        seekTo(chart.sectionStartMs(idx));
        buildWidgets();
    }

    private void resetTickIndex(double posMs) {
        chart.sortNotes();
        tickIndex = 0;
        while (tickIndex < chart.notes.size() && chart.notes.get(tickIndex).timeMs <= posMs) {
            tickIndex++;
        }
    }

    private void tickHitsounds(double posMs) {
        var opts = com.fnfmod.client.ClientOptions.get();
        if (!opts.editorHitsoundPlayer && !opts.editorHitsoundOpponent) {
            resetTickIndex(posMs);
            return;
        }
        int played = 0;
        while (tickIndex < chart.notes.size() && chart.notes.get(tickIndex).timeMs <= posMs) {
            SongChart.Note n = chart.notes.get(tickIndex++);
            boolean enabled = n.playerSide ? opts.editorHitsoundPlayer : opts.editorHitsoundOpponent;
            if (enabled && played < 4) {
                playTick();
                played++;
            }
        }
    }

    private void playTick() {
        if (!com.fnfmod.client.ClientOptions.get().hitsound.isEmpty()) {
            com.fnfmod.client.audio.HitsoundPlayer.play();
        } else {
            minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                    net.minecraft.sounds.SoundEvents.NOTE_BLOCK_HAT.value(), 1.8f));
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (getFocused() instanceof EditBox) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                setFocused(null);
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        // vortex: number keys place notes at the playhead
        if (vortex && keyCode >= GLFW.GLFW_KEY_1 && keyCode <= GLFW.GLFW_KEY_8) {
            int col = keyCode - GLFW.GLFW_KEY_1;
            double beat = conductor.beatAt(Math.max(0, viewPosMs));
            double snapped = Math.max(0, Math.round(beat / lineStepBeats()) * lineStepBeats());
            placeOrRemoveAt(col, conductor.timeOfBeat(snapped), false);
            return true;
        }
        switch (keyCode) {
            case GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_LEFT -> { changeSection(-1); return true; }
            case GLFW.GLFW_KEY_D, GLFW.GLFW_KEY_RIGHT -> { changeSection(1); return true; }
            case GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_UP -> { scrub(1); return true; }
            case GLFW.GLFW_KEY_S, GLFW.GLFW_KEY_DOWN -> { scrub(-1); return true; }
            case GLFW.GLFW_KEY_SPACE -> { togglePlay(); return true; }
            case GLFW.GLFW_KEY_V -> {
                vortex = !vortex;
                if (vortex && !isPlaying()) snapPlayheadToGrid();
                buildWidgets();
                return true;
            }
            case GLFW.GLFW_KEY_E -> { adjustSustain(stepMs()); return true; }
            case GLFW.GLFW_KEY_Q -> { adjustSustain(-stepMs()); return true; }
            case GLFW.GLFW_KEY_DELETE -> {
                if (selectedNote != null) {
                    chart.notes.remove(selectedNote);
                    selectedNote = null;
                }
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** Moves the playhead by snap steps (wheel up / W = earlier). */
    private void scrub(double steps) {
        double beat = conductor.beatAt(Math.max(0, viewPosMs));
        double target = Math.max(0, beat - steps * lineStepBeats());
        seekTo(conductor.timeOfBeat(target));
    }

    private void adjustSustain(double delta) {
        if (selectedNote == null) return;
        selectedNote.sustainMs = Math.max(0, selectedNote.sustainMs + delta);
        setStatus("Sustain: " + (int) selectedNote.sustainMs + "ms");
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int infoW = infoWidth();
        int infoH = infoHeight();
        if (infoW > 0 && mouseX >= infoPanelX && mouseX < infoPanelX + infoW
                && mouseY >= infoPanelY && mouseY < infoPanelY + infoH) {
            if (button == 0 && mouseY < infoPanelY + 16) {
                draggingInfoPanel = true;
                infoDragOffsetX = mouseX - infoPanelX;
                infoDragOffsetY = mouseY - infoPanelY;
            }
            return true;
        }
        int gx = gridX(), cw = cellW();
        if (mouseX >= gx && mouseX < gx + 8 * cw && mouseY >= gridTop() && mouseY < gridBottom()) {
            int col = (int) ((mouseX - gx) / cw);
            double beat = yToBeat(mouseY);
            // Grid lines are cell boundaries. Rounding sends clicks in the lower
            // half of a box into the next box; floor selects the box under cursor.
            double snapped = Math.max(0,
                    Math.floor(beat / lineStepBeats() + 1.0e-6) * lineStepBeats());
            double t = conductor.timeOfBeat(snapped);
            if (button == 0 && hasShiftDown()) {
                SongChart.Note n = findNoteAt(col, t, stepMs() / 2);
                if (n != null) {
                    selectedNote = n;
                    if (noteTypeBox != null) noteTypeBox.setValue(n.noteType == null ? "" : n.noteType);
                    setStatus("Selected note (E/Q sustain, Del remove)");
                }
            } else {
                placeOrRemoveAt(col, t, button == 1);
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingInfoPanel && button == 0) {
            int w = infoWidth();
            int h = infoHeight();
            infoPanelX = Mth.clamp((int) Math.round(mouseX - infoDragOffsetX), 0, Math.max(0, width - w));
            infoPanelY = Mth.clamp((int) Math.round(mouseY - infoDragOffsetY), 22, Math.max(22, height - h - 12));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && draggingInfoPanel) {
            draggingInfoPanel = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scrub(scrollY);
        return true;
    }

    // ------------------------------------------------------------------ render

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        renderBackground(gui, mouseX, mouseY, partialTick);

        // follow the audio while playing; grid scrolls under the fixed playhead
        if (isPlaying()) {
            viewPosMs = audio.positionMs();
            tickHitsounds(viewPosMs);
        }
        // panel follows the section at the playhead
        if (sectionIndexAt(viewPosMs) != widgetSection) {
            buildWidgets();
        }

        int gx = gridX(), cw = cellW();
        int top = gridTop(), bottom = gridBottom();
        int gridW = 8 * cw;
        double lineStep = lineStepBeats();

        // Psych-style white receptor strip and checkerboard chart grid.
        gui.fill(gx, top - cw, gx + gridW, top, 0xFFE8E8E8);
        gui.fill(gx, top, gx + gridW, bottom, 0xFFE0E0E0);

        gui.enableScissor(gx, top, gx + gridW, bottom);

        // snap/beat lines
        double topBeat = yToBeat(top);
        double bottomBeat = yToBeat(bottom);
        long firstLine = (long) Math.ceil(Math.max(0, topBeat) / lineStep - 1.0e-6);
        long firstBand = (long) Math.floor(topBeat / lineStep);
        for (long k = firstBand; k * lineStep <= bottomBeat; k++) {
            int y0 = (int) beatToY(k * lineStep);
            int y1 = (int) beatToY((k + 1) * lineStep);
            for (int c = 0; c < 8; c++) {
                int color = ((c + k) & 1) == 0 ? 0xFFCBCBCB : 0xFFE4E4E4;
                gui.fill(gx + c * cw, Math.max(top, y0), gx + (c + 1) * cw, Math.min(bottom, y1), color);
            }
        }
        for (long k = firstLine; k * lineStep <= bottomBeat; k++) {
            double beat = k * lineStep;
            int y = (int) beatToY(beat);
            boolean wholeBeat = Math.abs(beat - Math.round(beat)) < 1.0e-6;
            gui.fill(gx, y, gx + gridW, y + 1, wholeBeat ? 0x66444444 : 0x22444444);
        }

        // section boundaries + labels
        double bottomTime = conductor.timeOfBeat(Math.max(0, bottomBeat));
        int secIdx = Math.max(0, sectionIndexAt(conductor.timeOfBeat(Math.max(0, topBeat))) - 1);
        for (int i = secIdx; i < 4096; i++) {
            double start = chart.sectionStartMs(i);
            if (start > bottomTime) break;
            int y = (int) beatToY(conductor.beatAt(start));
            if (y >= top - 12 && y <= bottom + 12) {
                gui.fill(gx, y, gx + gridW, y + 1, 0xFF9D3D3D);
            }
        }

        // Placed chart notes use the user's currently selected skin.
        float noteSize = Math.min(cw - 3, 24);
        for (SongChart.Note n : chart.notes) {
            double nBeat = conductor.beatAt(n.timeMs);
            if (nBeat < topBeat - 8 || nBeat > bottomBeat + 1) continue;
            int y = (int) beatToY(nBeat);
            int col = (n.playerSide ? 4 : 0) + n.lane;
            int x = gx + col * cw;
            if (n.sustainMs > 0) {
                int y2 = (int) beatToY(conductor.beatAt(n.timeMs + n.sustainMs));
                NoteStyle.drawHoldPiece(gui, n.lane, x + cw / 2f, Math.min(y, y2), Math.max(y, y2), noteSize, y2 < y);
            }
            if (y2InRange(y - (int) noteSize / 2, top, bottom, (int) noteSize)) {
                NoteStyle.drawNote(gui, n.lane, x + cw / 2f, y, noteSize);
                if (n == selectedNote) {
                    int r = (int) noteSize / 2 + 2;
                    gui.renderOutline(x + cw / 2 - r, y - r, r * 2, r * 2, 0xFFFFFFFF);
                }
                if (n.noteType != null && !n.noteType.isEmpty()) {
                    gui.drawString(font, "*", x + cw - 6, y - 4, 0xFF000000);
                }
            }
        }

        gui.disableScissor();

        // Neutral editor receptors, side divider, and fixed playhead. The
        // selected skin is previewed at the playhead only in Vortex mode.
        gui.fill(gx + 4 * cw - 1, top - cw, gx + 4 * cw + 1, bottom, 0xFF222222);
        String[] receptorGlyphs = {"<", "v", "^", ">"};
        for (int c = 0; c < 8; c++) {
            gui.drawCenteredString(font, receptorGlyphs[c % 4], gx + c * cw + cw / 2,
                    top - cw / 2 - font.lineHeight / 2, 0xFF9A9A9A);
        }
        int py = centerY();
        gui.fill(gx, py, gx + gridW, py + 1, 0xFF9D3D3D);

        // Reserved event lane. It is deliberately display-only for now.
        gui.fill(gx - 10, top, gx - 8, bottom, 0xFF222222);
        gui.fill(gx - 16, py - 3, gx - 10, py + 3, 0xFFFFB000);

        // vortex hints
        if (vortex) {
            gui.drawString(font, "VORTEX", gx, top - 10, 0xFFFF66FF);
            gui.fill(gx, py - cw / 2, gx + gridW, py + cw / 2, 0x22FFFFFF);
            for (int c = 0; c < 8; c++) {
                int centerX = gx + c * cw + cw / 2;
                NoteStyle.drawNote(gui, c % 4, centerX, py, noteSize);
                gui.drawString(font, String.valueOf(c + 1), gx + c * cw + 2,
                        py + cw / 2 - font.lineHeight, 0xFFFFFFFF);
            }
        }

        renderRightPanel(gui);
        if (topMenu != TopMenu.NONE) gui.fill(14, 22, 142, Math.min(height - 16, 126), 0xEE090909);

        // footer
        String help = "Wheel/W/S scrub | Space play | Click place | Shift+Click select | E/Q sustain | A/D section";
        gui.drawString(font, help, 8, height - 12, 0xFF999999);
        if (System.currentTimeMillis() < statusUntil) {
            gui.drawString(font, status, 8, height - 24, 0xFFFFFF66);
        }

        for (var renderable : renderables) {
            renderable.render(gui, mouseX, mouseY, partialTick);
        }
        // Movable window renders last so it stays above the grid and controls.
        renderInformationPanel(gui);
    }

    private void renderInformationPanel(GuiGraphics gui) {
        int w = infoWidth();
        if (w <= 0) return;
        int x = infoPanelX, y = infoPanelY, h = infoHeight();
        gui.fill(x, y, x + w, y + h, 0xDD080808);
        gui.fill(x, y, x + w, y + 16, 0xFFF1F1F1);
        String title = "Information";
        gui.drawString(font, title, x + (w - font.width(title)) / 2, y + 4, 0xFF111111, false);

        int ty = y + 24;
        double duration = audio == null ? 0 : audio.durationMs();
        gui.drawString(font, formatTime(viewPosMs) + " / " + formatTime(duration), x + 8, ty, 0xFFFFFFFF, false);
        ty += 24;
        double beat = conductor.beatAt(Math.max(0, viewPosMs));
        gui.drawString(font, "Section: " + sectionIndexAt(viewPosMs), x + 8, ty, 0xFFFFFFFF, false); ty += 11;
        gui.drawString(font, "Beat: " + (int) Math.floor(beat), x + 8, ty, 0xFFFFFFFF, false); ty += 11;
        gui.drawString(font, "Step: " + (int) Math.floor(beat * 4), x + 8, ty, 0xFFFFFFFF, false); ty += 22;
        gui.drawString(font, "Beat Snap: " + SNAPS[snapIndex] + " / " + SNAPS[snapIndex], x + 8, ty, 0xFFFFFFFF, false); ty += 11;
        gui.drawString(font, "Selected: " + (selectedNote == null ? 0 : 1), x + 8, ty, 0xFFFFFFFF, false);
    }

    private void renderRightPanel(GuiGraphics gui) {
        int x = panelX(), w = panelWidth();
        int bottom = Math.min(height - 24, 220);
        gui.fill(x, 40, x + w, bottom, 0xE60A0A0A);
        int color = 0xFFDDDDDD;
        switch (activeTab) {
            case CHARTING -> {
                gui.drawCenteredString(font, "Editor-only playback options", x + w / 2, 42, color);
                gui.drawString(font, "Hitsounds", x + 8, 126, 0xFFAAAAAA);
            }
            case DATA -> gui.drawString(font, "Song data overrides", x + 8, 42, color);
            case EVENTS -> {
                gui.drawString(font, "Events", x + 8, 42, color);
                gui.drawString(font, "Events are not enabled yet.", x + 8, 106, 0xFF888888);
            }
            case NOTE -> gui.drawString(font, selectedNote == null ? "Select a note to edit it" : "Selected note", x + 8, 42, color);
            case SECTION -> gui.drawString(font, "Section " + sectionIndexAt(viewPosMs), x + 8, 42, color);
            case SONG -> gui.drawString(font, "Song", x + 8, 42, color);
        }
    }

    private static String formatTime(double ms) {
        if (ms <= 0) return "0:00.00";
        long total = (long) ms;
        long minutes = total / 60000;
        long seconds = (total / 1000) % 60;
        long hundredths = (total % 1000) / 10;
        return String.format(Locale.ROOT, "%d:%02d.%02d", minutes, seconds, hundredths);
    }

    private static boolean y2InRange(int y, int top, int bottom, int h) {
        return y + h >= top && y <= bottom;
    }

    /** Flat background — vanilla's blurred menu background would smear the note grid. */
    @Override
    public void renderBackground(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        gui.fill(0, 0, width, height, 0xE8101014);
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
