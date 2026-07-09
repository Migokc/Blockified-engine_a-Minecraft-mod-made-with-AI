package com.fnfmod.song;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SongEntry {
    public enum Format { LEGACY, VSLICE, CODENAME }

    public String id;
    public String displayName;
    public Path folder;
    public Format format = Format.LEGACY;
    /** Codename Engine: the song's meta.json (bpm/needsVoices/icon live there, not in the chart). */
    public Path metaFile;
    /** Opponent icon name (fallback lookup). */
    public String opponentIcon = "";
    /** Resolved opponent icon png from THIS song's own mod (avoids cross-mod name clashes). */
    public Path opponentIconFile;
    /** Folder to resolve this song's icons/characters from (its default variation's mod). */
    public transient Path modRoot;
    public final List<String> difficulties = new ArrayList<>();

    /** difficulty -> chart file (legacy/psych) */
    public final Map<String, Path> legacyChartFiles = new LinkedHashMap<>();

    /** One V-Slice variation: its chart+metadata pair and its own audio. */
    public static class VSliceVariation {
        public Path chartFile;
        public Path metadataFile;
        public Path instFile;
        public Path voicesFile;
        public Path voicesPlayerFile;
        public Path voicesOpponentFile;
    }

    /** V-Slice: difficulty key -> variation (default, erect, pico...). Each has its own audio. */
    public final Map<String, VSliceVariation> vsliceVariations = new LinkedHashMap<>();
    /** V-Slice: display difficulty key (e.g. "normal (pico)") -> real chart difficulty ("normal"). */
    public final Map<String, String> vsliceRealDiff = new LinkedHashMap<>();

    /** A variation gathered during scanning (before difficulty keys are assigned). */
    public static class RawVar {
        public String variation;       // "", "erect", "pico", "spookymod"...
        public VSliceVariation files;
        public List<String> diffs = new ArrayList<>();
    }

    /** Raw variations from every scanned folder for this song; finalized into keys after scanning. */
    public final List<RawVar> rawVars = new ArrayList<>();

    /** The actual chart difficulty name for a (possibly variation-suffixed) difficulty key. */
    public String realDifficulty(String key) {
        if (vsliceRealDiff.containsKey(key)) return vsliceRealDiff.get(key);
        // downloaded cache folders hold one variation, so keys arrive un-suffixed;
        // strip a " (variation)" tail to recover the real difficulty name
        int p = key.lastIndexOf(" (");
        if (p > 0 && key.endsWith(")")) return key.substring(0, p);
        return key;
    }

    // legacy/Psych share one audio set across all difficulties
    public Path instFile;
    public Path voicesFile;
    public Path voicesPlayerFile;
    public Path voicesOpponentFile;

    public boolean isVslice() {
        return format == Format.VSLICE;
    }

    public VSliceVariation variationFor(String difficulty) {
        VSliceVariation v = vsliceVariations.get(difficulty);
        if (v == null && !vsliceVariations.isEmpty()) v = vsliceVariations.values().iterator().next();
        return v;
    }

    public Path instFor(String difficulty) {
        if (isVslice()) {
            VSliceVariation v = variationFor(difficulty);
            return v == null ? null : v.instFile;
        }
        return instFile;
    }

    public Path voicesFor(String difficulty) {
        if (isVslice()) {
            VSliceVariation v = variationFor(difficulty);
            return v == null ? null : v.voicesFile;
        }
        return voicesFile;
    }

    public Path voicesPlayerFor(String difficulty) {
        if (isVslice()) {
            VSliceVariation v = variationFor(difficulty);
            return v == null ? null : v.voicesPlayerFile;
        }
        return voicesPlayerFile;
    }

    public Path voicesOpponentFor(String difficulty) {
        if (isVslice()) {
            VSliceVariation v = variationFor(difficulty);
            return v == null ? null : v.voicesOpponentFile;
        }
        return voicesOpponentFile;
    }

    /** Files a client needs to play one specific difficulty. */
    public List<Path> transferFiles(String difficulty) {
        List<Path> out = new ArrayList<>();
        if (isVslice()) {
            VSliceVariation v = variationFor(difficulty);
            if (v != null) {
                addIf(out, v.chartFile);
                addIf(out, v.metadataFile);
                addIf(out, v.instFile);
                addIf(out, v.voicesFile);
                addIf(out, v.voicesPlayerFile);
                addIf(out, v.voicesOpponentFile);
            }
        } else {
            Path chart = legacyChartFiles.get(difficulty);
            if (chart == null && !legacyChartFiles.isEmpty()) chart = legacyChartFiles.values().iterator().next();
            addIf(out, chart);
            addIf(out, metaFile); // Codename: needed to load the chart (null for legacy/Psych)
            addIf(out, instFile);
            addIf(out, voicesFile);
            addIf(out, voicesPlayerFile);
            addIf(out, voicesOpponentFile);
        }
        return out;
    }

    /** All files across every difficulty (used when a whole song must be transferred). */
    public List<Path> allTransferFiles() {
        List<Path> out = new ArrayList<>();
        for (String d : difficulties) {
            for (Path p : transferFiles(d)) {
                if (!out.contains(p)) out.add(p);
            }
        }
        return out;
    }

    private static void addIf(List<Path> list, Path p) {
        if (p != null && !list.contains(p)) list.add(p);
    }
}
