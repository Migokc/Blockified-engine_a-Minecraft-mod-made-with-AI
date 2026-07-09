package com.fnfmod.chart;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Normalized in-memory chart, produced from any supported format
 * (legacy FNF, Psych Engine, V-Slice).
 */
public class SongChart {
    public String title = "Unknown";
    public double startBpm = 120.0;
    public double speed = 1.0;
    /** Extra audio offset in ms (positive = notes later relative to audio). */
    public double offsetMs = 0.0;
    public boolean needsVoices = true;
    public String player1 = "bf";
    public String player2 = "dad";

    public final List<BpmChange> bpmChanges = new ArrayList<>();
    /** All notes, sorted by time. */
    public final List<Note> notes = new ArrayList<>();
    /** Sections, kept for editor round-trips and legacy saving. */
    public final List<Section> sections = new ArrayList<>();

    public static class Note {
        public double timeMs;
        /** 0=left 1=down 2=up 3=right */
        public int lane;
        /** true = player (BF) side, false = opponent side */
        public boolean playerSide;
        public double sustainMs;
        public String noteType = "";
        public boolean altAnim;

        public Note() {}

        public Note(double timeMs, int lane, boolean playerSide, double sustainMs, String noteType) {
            this.timeMs = timeMs;
            this.lane = lane;
            this.playerSide = playerSide;
            this.sustainMs = sustainMs;
            this.noteType = noteType == null ? "" : noteType;
        }

        public Note copy() {
            Note n = new Note(timeMs, lane, playerSide, sustainMs, noteType);
            n.altAnim = altAnim;
            return n;
        }
    }

    public static class Section {
        public boolean mustHit = true;
        public boolean altAnim = false;
        public boolean gfSection = false;
        public double sectionBeats = 4.0;
        public boolean changeBPM = false;
        public double bpm = 0.0;
        /** Camera easing used when focus changes into this section: smooth/expo/linear/snap. */
        public String camEase = "smooth";
    }

    public static class BpmChange {
        public double timeMs;
        public double bpm;

        public BpmChange(double timeMs, double bpm) {
            this.timeMs = timeMs;
            this.bpm = bpm;
        }
    }

    public void sortNotes() {
        notes.sort(Comparator.comparingDouble(n -> n.timeMs));
    }

    /** Rebuilds bpmChanges from startBpm + section changeBPM flags. */
    public void rebuildBpmMap() {
        bpmChanges.clear();
        bpmChanges.add(new BpmChange(0, startBpm));
        double time = 0;
        double bpm = startBpm;
        for (Section s : sections) {
            if (s.changeBPM && s.bpm > 0 && s.bpm != bpm) {
                bpm = s.bpm;
                bpmChanges.add(new BpmChange(time, bpm));
            }
            time += s.sectionBeats * (60000.0 / bpm);
        }
    }

    /** Absolute start time in ms of a section index (based on current bpm map / sections). */
    public double sectionStartMs(int index) {
        double time = 0;
        double bpm = startBpm;
        for (int i = 0; i < index && i < sections.size(); i++) {
            Section s = sections.get(i);
            if (s.changeBPM && s.bpm > 0) bpm = s.bpm;
            time += s.sectionBeats * (60000.0 / bpm);
        }
        if (index >= sections.size()) {
            // extrapolate with last known bpm
            for (int i = sections.size(); i < index; i++) {
                time += 4.0 * (60000.0 / bpm);
            }
        }
        return time;
    }

    public double bpmForSection(int index) {
        double bpm = startBpm;
        for (int i = 0; i <= index && i < sections.size(); i++) {
            Section s = sections.get(i);
            if (s.changeBPM && s.bpm > 0) bpm = s.bpm;
        }
        return bpm;
    }

    public double lastNoteTimeMs() {
        double t = 0;
        for (Note n : notes) t = Math.max(t, n.timeMs + Math.max(0, n.sustainMs));
        return t;
    }

    /** Ensure there are enough sections to cover all notes (for the editor). */
    public void ensureSectionsCoverNotes() {
        double end = lastNoteTimeMs();
        while (sectionStartMs(sections.size()) <= end + 1) {
            sections.add(new Section());
            if (sections.size() > 4096) break;
        }
        if (sections.isEmpty()) sections.add(new Section());
    }
}
