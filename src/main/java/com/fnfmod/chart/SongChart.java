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
    /**
     * Song audio offset in ms. Positive delays the song so it sounds later than
     * the chart (silent lead-in); negative plays it earlier, seeking past the
     * intro so nothing is heard before the chart starts.
     */
    public double offsetMs = 0.0;
    public boolean needsVoices = true;
    public String player1 = "bf";
    public String player2 = "dad";
    /** Psych girlfriend/speakers character id (gfVersion). */
    public String player3 = "gf";
    /** Psych stage id used to discover stages/<stage>.lua. */
    public String stage = "stage";
    /** Psych arrowSkin: Sparrow atlas used by receptors, notes, and sustains. */
    public String noteTexture = "";
    /** Psych splashSkin: Sparrow atlas used by note splashes. */
    public String noteSplashTexture = "";

    public final List<BpmChange> bpmChanges = new ArrayList<>();
    /** All notes, sorted by time. */
    public final List<Note> notes = new ArrayList<>();
    /** Timeline events loaded from embedded Psych data or a separate events.json. */
    public final List<Event> events = new ArrayList<>();
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

        // Psych Engine runtime note properties. These are intentionally not
        // serialized into the chart; custom_notetypes scripts/configs rebuild
        // them whenever the song starts.
        public String texture = "";
        public String animSuffix = "";
        public String hitsound = "hitsound";
        public String noteSplashTexture = "";
        public boolean ignoreNote;
        public boolean hitCausesMiss;
        public boolean noAnimation;
        public boolean noMissAnimation;
        public boolean blockHit;
        public boolean gfNote;
        public boolean lowPriority;
        public boolean visible = true;
        public boolean ratingDisabled;
        public boolean hitsoundDisabled;
        public boolean noteSplashDisabled;
        public double hitHealth = 0.023;
        public double missHealth = 0.0475;
        public double multAlpha = 1.0;
        public double multSpeed = 1.0;
        public double alpha = 1.0;
        public double angle;
        public double offsetX;
        public double offsetY;
        public double offsetAngle;
        public double scaleX = 1.0;
        public double scaleY = 1.0;
        public double earlyHitMult = 1.0;
        public double lateHitMult = 1.0;
        public double hitsoundVolume = 1.0;
        public double noteSplashAlpha = 1.0;

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
            n.texture = texture;
            n.animSuffix = animSuffix;
            n.hitsound = hitsound;
            n.noteSplashTexture = noteSplashTexture;
            n.ignoreNote = ignoreNote;
            n.hitCausesMiss = hitCausesMiss;
            n.noAnimation = noAnimation;
            n.noMissAnimation = noMissAnimation;
            n.blockHit = blockHit;
            n.gfNote = gfNote;
            n.lowPriority = lowPriority;
            n.visible = visible;
            n.ratingDisabled = ratingDisabled;
            n.hitsoundDisabled = hitsoundDisabled;
            n.noteSplashDisabled = noteSplashDisabled;
            n.hitHealth = hitHealth;
            n.missHealth = missHealth;
            n.multAlpha = multAlpha;
            n.multSpeed = multSpeed;
            n.alpha = alpha;
            n.angle = angle;
            n.offsetX = offsetX;
            n.offsetY = offsetY;
            n.offsetAngle = offsetAngle;
            n.scaleX = scaleX;
            n.scaleY = scaleY;
            n.earlyHitMult = earlyHitMult;
            n.lateHitMult = lateHitMult;
            n.hitsoundVolume = hitsoundVolume;
            n.noteSplashAlpha = noteSplashAlpha;
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
    }

    public static class Event {
        public double timeMs;
        public String name = "";
        public String value1 = "";
        public String value2 = "";
        /** Blockified extension fields. Psych-compatible events leave these empty. */
        public String value3 = "";
        public String value4 = "";
        public String value5 = "";
        public String value6 = "";
        public String value7 = "";
        /** Runs once while the gameplay screen is loaded, before audio starts. */
        public boolean beforeSong;

        public Event(double timeMs, String name, String value1, String value2) {
            this(timeMs, name, value1, value2, false);
        }

        public Event(double timeMs, String name, String value1, String value2, boolean beforeSong) {
            this(timeMs, name, value1, value2, "", "", "", beforeSong);
        }

        public Event(double timeMs, String name, String value1, String value2,
                     String value3, String value4, String value5, boolean beforeSong) {
            this(timeMs, name, value1, value2, value3, value4, value5, "", beforeSong);
        }

        public Event(double timeMs, String name, String value1, String value2,
                     String value3, String value4, String value5, String value6, boolean beforeSong) {
            this(timeMs, name, value1, value2, value3, value4, value5, value6, "", beforeSong);
        }

        public Event(double timeMs, String name, String value1, String value2,
                     String value3, String value4, String value5, String value6, String value7,
                     boolean beforeSong) {
            this.timeMs = timeMs;
            this.name = name == null ? "" : name;
            this.value1 = value1 == null ? "" : value1;
            this.value2 = value2 == null ? "" : value2;
            this.value3 = value3 == null ? "" : value3;
            this.value4 = value4 == null ? "" : value4;
            this.value5 = value5 == null ? "" : value5;
            this.value6 = value6 == null ? "" : value6;
            this.value7 = value7 == null ? "" : value7;
            this.beforeSong = beforeSong;
        }

        public Event copy() {
            return new Event(timeMs, name, value1, value2, value3, value4, value5, value6, value7,
                    beforeSong);
        }
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

    public void sortEvents() {
        events.sort(Comparator.comparing((Event e) -> !e.beforeSong)
                .thenComparingDouble(e -> e.timeMs));
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
        for (Event e : events) t = Math.max(t, e.timeMs);
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
