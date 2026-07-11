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

    // widgets
    private EditBox titleBox, bpmBox, speedBox, noteTypeBox, sectionBpmBox, sectionBeatsBox, saveNameBox;
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
        int x = panelX();
        int w = 96;
        int y = 18;

        ySong = y + 2;
        titleBox = addRenderableWidget(new EditBox(font, x + 34, y, w - 34, 12, Component.literal("title")));
        titleBox.setValue(chart.title);
        y += 16;
        yBpm = y + 2;
        bpmBox = addRenderableWidget(new EditBox(font, x + 34, y, 40, 12, Component.literal("bpm")));
        bpmBox.setValue(trim(chart.startBpm));
        speedBox = addRenderableWidget(new EditBox(font, x + 34 + 56, y, 30, 12, Component.literal("speed")));
        speedBox.setValue(trim(chart.speed));
        y += 16;
        addRenderableWidget(Button.builder(voicesLabel(), b -> {
            chart.needsVoices = !chart.needsVoices;
            b.setMessage(voicesLabel());
        }).bounds(x, y, w, 14).build());
        y += 20;

        // section flags (for the section at the playhead)
        addRenderableWidget(Button.builder(flagLabel("MustHit", sec().mustHit), b -> {
            sec().mustHit = !sec().mustHit;
            b.setMessage(flagLabel("MustHit", sec().mustHit));
        }).bounds(x, y, w, 14).build());
        y += 16;
        addRenderableWidget(Button.builder(flagLabel("AltAnim", sec().altAnim), b -> {
            sec().altAnim = !sec().altAnim;
            b.setMessage(flagLabel("AltAnim", sec().altAnim));
        }).bounds(x, y, w, 14).build());
        y += 16;
        addRenderableWidget(Button.builder(flagLabel("GF Section", sec().gfSection), b -> {
            sec().gfSection = !sec().gfSection;
            b.setMessage(flagLabel("GF Section", sec().gfSection));
        }).bounds(x, y, w, 14).build());
        y += 16;
        addRenderableWidget(Button.builder(flagLabel("ChangeBPM", sec().changeBPM), b -> {
            sec().changeBPM = !sec().changeBPM;
            if (sec().changeBPM && sec().bpm <= 0) sec().bpm = chart.bpmForSection(widgetSection);
            sectionBpmBox.setValue(trim(sec().bpm));
            chart.rebuildBpmMap();
            b.setMessage(flagLabel("ChangeBPM", sec().changeBPM));
        }).bounds(x, y, w, 14).build());
        y += 16;
        ySecBpm = y + 2;
        sectionBpmBox = addRenderableWidget(new EditBox(font, x + 34, y, 40, 12, Component.literal("secBpm")));
        sectionBpmBox.setValue(sec().changeBPM ? trim(sec().bpm) : "");
        y += 16;
        yBeats = y + 2;
        sectionBeatsBox = addRenderableWidget(new EditBox(font, x + 34, y, 40, 12, Component.literal("beats")));
        sectionBeatsBox.setValue(trim(sec().sectionBeats));
        y += 16;
        addRenderableWidget(Button.builder(camEaseLabel(), b -> {
            String[] eases = GameplayCamera.EASES;
            int i = 0;
            for (int k = 0; k < eases.length; k++) {
                if (eases[k].equals(sec().camEase)) {
                    i = k;
                    break;
                }
            }
            sec().camEase = eases[(i + 1) % eases.length];
            b.setMessage(camEaseLabel());
        }).bounds(x, y, w, 14).build());
        y += 18;

        addRenderableWidget(Button.builder(Component.literal("Copy Section"), b -> copySection())
                .bounds(x, y, w, 14).build());
        y += 16;
        addRenderableWidget(Button.builder(Component.literal("Paste Section"), b -> pasteSection())
                .bounds(x, y, w, 14).build());
        y += 16;
        addRenderableWidget(Button.builder(Component.literal("Clear Section"), b -> clearSection())
                .bounds(x, y, w, 14).build());
        y += 16;
        addRenderableWidget(Button.builder(Component.literal("Swap Sides"), b -> swapSection())
                .bounds(x, y, w, 14).build());
        y += 20;

        yType = y + 2;
        noteTypeBox = addRenderableWidget(new EditBox(font, x + 34, y, w - 34, 12, Component.literal("type")));
        noteTypeBox.setHint(Component.literal("note type"));
        y += 16;
        addRenderableWidget(Button.builder(Component.literal("Snap: " + SNAPS[snapIndex]), b -> {
            snapIndex = (snapIndex + 1) % SNAPS.length;
            if (vortex && !isPlaying()) snapPlayheadToGrid();
            b.setMessage(Component.literal("Snap: " + SNAPS[snapIndex]));
        }).bounds(x, y, w, 14).build());
        y += 16;

        var opts = com.fnfmod.client.ClientOptions.get();
        Button vortexBtn = addRenderableWidget(Button.builder(
                Component.literal("Vortex: " + (vortex ? "ON" : "OFF")), b -> {
                    vortex = !vortex;
                    if (vortex && !isPlaying()) snapPlayheadToGrid();
                    b.setMessage(Component.literal("Vortex: " + (vortex ? "ON" : "OFF")));
                }).bounds(x, y, w, 14).build());
        vortexBtn.setTooltip(Tooltip.create(Component.literal("Keys 1-8 place notes at the playhead (V toggles)")));
        y += 16;

        Button hsPlayer = addRenderableWidget(Button.builder(
                Component.literal("P:" + (opts.editorHitsoundPlayer ? "ON" : "OFF")), b -> {
                    opts.editorHitsoundPlayer = !opts.editorHitsoundPlayer;
                    com.fnfmod.client.ClientOptions.save();
                    b.setMessage(Component.literal("P:" + (opts.editorHitsoundPlayer ? "ON" : "OFF")));
                }).bounds(x, y, w / 2 - 2, 14).build());
        hsPlayer.setTooltip(Tooltip.create(Component.literal("Player note hitsounds during playback")));
        Button hsOpp = addRenderableWidget(Button.builder(
                Component.literal("O:" + (opts.editorHitsoundOpponent ? "ON" : "OFF")), b -> {
                    opts.editorHitsoundOpponent = !opts.editorHitsoundOpponent;
                    com.fnfmod.client.ClientOptions.save();
                    b.setMessage(Component.literal("O:" + (opts.editorHitsoundOpponent ? "ON" : "OFF")));
                }).bounds(x + w / 2 + 2, y, w / 2 - 2, 14).build());
        hsOpp.setTooltip(Tooltip.create(Component.literal("Opponent note hitsounds during playback")));
        y += 20;

        yFile = y + 2;
        saveNameBox = addRenderableWidget(new EditBox(font, x + 34, y, w - 34, 12, Component.literal("file")));
        saveNameBox.setValue(songId != null ? songId : sanitizeId(chart.title));
        y += 16;
        addRenderableWidget(Button.builder(Component.literal("Save Chart"), b -> save())
                .bounds(x, y, w, 14).build());
        y += 16;
        addRenderableWidget(Button.builder(Component.literal("Exit"), b -> onClose())
                .bounds(x, y, w, 14).build());
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

    private int gridX() { return 24; }
    private int cellW() { return 24; }
    private int gridTop() { return 24; }
    private int gridBottom() { return height - 30; }
    private int centerY() { return (gridTop() + gridBottom()) / 2; }
    private int panelX() { return gridX() + 8 * cellW() + 40; }

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
        chart.title = titleBox.getValue().isBlank() ? chart.title : titleBox.getValue();
        try { chart.startBpm = Double.parseDouble(bpmBox.getValue()); } catch (NumberFormatException ignored) {}
        try { chart.speed = Double.parseDouble(speedBox.getValue()); } catch (NumberFormatException ignored) {}
        try {
            double b = Double.parseDouble(sectionBpmBox.getValue());
            if (sec().changeBPM && b > 0) sec().bpm = b;
        } catch (NumberFormatException ignored) {}
        try {
            double beats = Double.parseDouble(sectionBeatsBox.getValue());
            if (beats > 0 && beats <= 64) sec().sectionBeats = beats;
        } catch (NumberFormatException ignored) {}
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
        SongChart.Note n = new SongChart.Note(timeMs, col % 4, col >= 4, 0, noteTypeBox.getValue().trim());
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
        String id = sanitizeId(saveNameBox.getValue().isBlank() ? chart.title : saveNameBox.getValue());
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
        int gx = gridX(), cw = cellW();
        if (mouseX >= gx && mouseX < gx + 8 * cw && mouseY >= gridTop() && mouseY < gridBottom()) {
            int col = (int) ((mouseX - gx) / cw);
            double beat = yToBeat(mouseY);
            double snapped = Math.max(0, Math.round(beat / lineStepBeats()) * lineStepBeats());
            double t = conductor.timeOfBeat(snapped);
            if (button == 0 && hasShiftDown()) {
                SongChart.Note n = findNoteAt(col, t, stepMs() / 2);
                if (n != null) {
                    selectedNote = n;
                    noteTypeBox.setValue(n.noteType == null ? "" : n.noteType);
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

        // column backgrounds
        for (int c = 0; c < 8; c++) {
            int color = (c % 2 == 0) ? 0xAA202020 : 0xAA2A2A2A;
            if (c >= 4) color += 0x000A0A00;
            gui.fill(gx + c * cw, top, gx + (c + 1) * cw, bottom, color);
        }

        gui.enableScissor(gx, top, gx + gridW, bottom);

        // snap/beat lines
        double topBeat = yToBeat(top);
        double bottomBeat = yToBeat(bottom);
        long firstLine = (long) Math.ceil(Math.max(0, topBeat) / lineStep - 1.0e-6);
        for (long k = firstLine; k * lineStep <= bottomBeat; k++) {
            double beat = k * lineStep;
            int y = (int) beatToY(beat);
            boolean wholeBeat = Math.abs(beat - Math.round(beat)) < 1.0e-6;
            gui.fill(gx, y, gx + gridW, y + 1, wholeBeat ? 0x77FFFFFF : 0x2AFFFFFF);
        }

        // section boundaries + labels
        double bottomTime = conductor.timeOfBeat(Math.max(0, bottomBeat));
        int secIdx = Math.max(0, sectionIndexAt(conductor.timeOfBeat(Math.max(0, topBeat))) - 1);
        for (int i = secIdx; i < 4096; i++) {
            double start = chart.sectionStartMs(i);
            if (start > bottomTime) break;
            int y = (int) beatToY(conductor.beatAt(start));
            if (y >= top - 12 && y <= bottom + 12) {
                gui.fill(gx, y, gx + gridW, y + 2, 0xCCFFFFFF);
                boolean mustHit = i < chart.sections.size() ? chart.sections.get(i).mustHit : true;
                gui.drawString(font, "S" + i + (mustHit ? " >P" : " >O"), gx + gridW + 4, y - 3, 0xFFAAAAAA);
            }
        }

        // notes
        int noteH = 14;
        for (SongChart.Note n : chart.notes) {
            double nBeat = conductor.beatAt(n.timeMs);
            if (nBeat < topBeat - 8 || nBeat > bottomBeat + 1) continue;
            int y = (int) beatToY(nBeat);
            int col = (n.playerSide ? 4 : 0) + n.lane;
            int x = gx + col * cw;
            int color = NoteStyle.LANE_COLORS[n.lane];
            if (n.sustainMs > 0) {
                int y2 = (int) beatToY(conductor.beatAt(n.timeMs + n.sustainMs));
                gui.fill(x + cw / 2 - 2, y, x + cw / 2 + 2, Math.max(y2, y + noteH), 0xAA000000 | (color & 0xFFFFFF));
            }
            if (y2InRange(y, top, bottom, noteH)) {
                gui.fill(x + 1, y, x + cw - 1, y + noteH, color);
                if (n == selectedNote) {
                    gui.fill(x, y - 1, x + cw, y, 0xFFFFFFFF);
                    gui.fill(x, y + noteH, x + cw, y + noteH + 1, 0xFFFFFFFF);
                    gui.fill(x, y, x + 1, y + noteH, 0xFFFFFFFF);
                    gui.fill(x + cw - 1, y, x + cw, y + noteH, 0xFFFFFFFF);
                }
                if (n.noteType != null && !n.noteType.isEmpty()) {
                    gui.drawString(font, "*", x + cw - 6, y + 2, 0xFF000000);
                }
            }
        }

        gui.disableScissor();

        // side divider + centered playhead (always in the middle)
        gui.fill(gx + 4 * cw - 1, top, gx + 4 * cw + 1, bottom, 0xCCFFFFFF);
        int py = centerY();
        gui.fill(gx - 6, py - 1, gx + gridW + 6, py + 1, 0xFFFF3333);

        // vortex hints
        if (vortex) {
            gui.drawString(font, "VORTEX", gx, top - 10, 0xFFFF66FF);
            for (int c = 0; c < 8; c++) {
                gui.drawCenteredString(font, String.valueOf(c + 1), gx + c * cw + cw / 2, py + 4, 0xAAFFFFFF);
            }
        } else {
            gui.drawString(font, "OPPONENT", gx, top - 10, 0xFFDD8888);
            gui.drawString(font, "PLAYER", gx + 4 * cw + 4, top - 10, 0xFF88DD88);
        }

        // header info
        int curSection = sectionIndexAt(viewPosMs);
        String mh = sec().mustHit ? " (cam: player)" : " (cam: opponent)";
        gui.drawString(font, String.format("%.2fs  Section %d%s", viewPosMs / 1000.0, curSection, mh),
                gx + gridW + 40, top - 10, 0xFFFFFFFF);

        // panel labels
        int px = panelX();
        gui.drawString(font, "Song:", px, ySong, 0xFFFFFF);
        gui.drawString(font, "BPM:", px, yBpm, 0xFFFFFF);
        gui.drawString(font, "Spd:", px + 34 + 42, yBpm, 0xFFFFFF);
        gui.drawString(font, "BPM:", px, ySecBpm, 0xFFFFFF);
        gui.drawString(font, "Beats:", px, yBeats, 0xFFFFFF);
        gui.drawString(font, "Type:", px, yType, 0xFFFFFF);
        gui.drawString(font, "File:", px, yFile, 0xFFFFFF);

        // footer
        String help = "Wheel/W/S: scrub | Space: play | V+1-8: vortex | Click: place | Shift+Click: select | E/Q: sustain | A/D: section";
        gui.drawString(font, help, 8, height - 12, 0xFF999999);
        if (System.currentTimeMillis() < statusUntil) {
            gui.drawString(font, status, 8, height - 24, 0xFFFFFF66);
        }

        for (var renderable : renderables) {
            renderable.render(gui, mouseX, mouseY, partialTick);
        }
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
