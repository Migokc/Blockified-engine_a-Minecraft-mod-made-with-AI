package com.fnfmod.song;

import com.fnfmod.chart.SongChart;
import com.fnfmod.gameplay.PlaybackMode;
import com.fnfmod.gameplay.PlaybackPolicy;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.EnumSet;
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
    /** Optional separate Psych/Codename event timeline. */
    public Path eventsFile;
    /** True when eventsFile belongs to the local edited-song override. */
    public boolean eventsOverride;
    /** Opponent icon name (fallback lookup). */
    public String opponentIcon = "";
    /** Resolved opponent icon png from THIS song's own mod (avoids cross-mod name clashes). */
    public Path opponentIconFile;
    /** Folder to resolve this song's icons/characters from (its default variation's mod). */
    public transient Path modRoot;
    /** True when modRoot is a complete fnfmod/mods or external engine-style pack. */
    public transient boolean fullModLayout;
    /** Original pack used only for inherited difficulties/audio after an editor save. */
    public transient Path chartOriginRoot;
    /** Optional origin used only to resolve character definitions and health icons. */
    public transient Path characterRoot;
    /** Resource groups enabled for the external directory that supplied this entry. */
    public transient EnumSet<SongLibrary.ExternalContent> externalContent = SongLibrary.allExternalContent();
    public final List<String> difficulties = new ArrayList<>();
    /** Top-level Lua scripts beside this song's chart; scoped to this song. */
    public final List<Path> luaFiles = new ArrayList<>();

    /** difficulty -> chart file (legacy/psych) */
    public final Map<String, Path> legacyChartFiles = new LinkedHashMap<>();
    /** Local Psych charts that replace only their matching original difficulty. */
    public final Map<String, Path> chartOverrides = new LinkedHashMap<>();

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

    public boolean allows(SongLibrary.ExternalContent content) {
        return externalContent == null || externalContent.contains(content);
    }

    /** Rich resources belong to a complete mod, never a lightweight songs/&lt;song&gt; folder. */
    public Path runtimeRoot() {
        return fullModLayout ? modRoot : null;
    }

    public VSliceVariation variationFor(String difficulty) {
        return difficultyValue(vsliceVariations, difficulty);
    }

    public Path legacyChartFor(String difficulty) {
        return difficultyValue(legacyChartFiles, difficulty);
    }

    public Path chartOverrideFor(String difficulty) {
        return difficultyValue(chartOverrides, difficulty);
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
        return transferFiles(difficulty, PlaybackPolicy.resolve(PlaybackMode.LEGACY, this));
    }

    /** Files allowed by the selected presentation/resource policy. */
    public List<Path> transferFiles(String difficulty, PlaybackPolicy policy) {
        List<Path> out = new ArrayList<>();
        Path override = chartOverrideFor(difficulty);
        if (isVslice()) {
            VSliceVariation v = variationFor(difficulty);
            if (v != null) {
                addIf(out, override != null ? override : v.chartFile);
                addIf(out, v.metadataFile);
                addIf(out, v.instFile);
                addIf(out, v.voicesFile);
                addIf(out, v.voicesPlayerFile);
                addIf(out, v.voicesOpponentFile);
            }
        } else {
            Path chart = override != null ? override : legacyChartFor(difficulty);
            addIf(out, chart);
            addIf(out, metaFile); // Codename: needed to load the chart (null for legacy/Psych)
            addIf(out, instFile);
            addIf(out, voicesFile);
            addIf(out, voicesPlayerFile);
            addIf(out, voicesOpponentFile);
        }
        if (policy.allows(this, SongLibrary.ExternalContent.EVENTS)) addIf(out, eventsFile);
        if (policy.allows(this, SongLibrary.ExternalContent.LUA)) {
            for (Path lua : luaFiles) addIf(out, lua);
        }
        if (fullModLayout && policy.allows(this, SongLibrary.ExternalContent.FONTS)) {
            addFonts(out, modRoot);
            if (folder != null && !folder.equals(modRoot)) addFonts(out, folder);
        }
        Path runtimeRoot = runtimeRoot();
        if (runtimeRoot != null) {
            if (policy.allows(this, SongLibrary.ExternalContent.IMAGES)) {
                addChartNoteTextureFiles(out, difficulty, runtimeRoot);
            }
            boolean packageRuntimeAssets = isInstalledModRoot(runtimeRoot)
                    || policy.mode() == PlaybackMode.FNF;
            if (policy.allows(this, SongLibrary.ExternalContent.LUA)) {
                if (policy.songAssets() && packageRuntimeAssets) {
                    addTree(out, runtimeRoot.resolve("custom_notetypes"));
                    addTree(out, runtimeRoot.resolve("scripts"));
                    addTree(out, runtimeRoot.resolve("custom_events"));
                    addScriptTree(out, runtimeRoot.resolve("stages"));
                    addLuaTree(out, folder);
                    addLuaTree(out, runtimeRoot.resolve("data").resolve(id));
                } else {
                    addScriptTree(out, runtimeRoot.resolve("custom_notetypes"));
                    addScriptTree(out, runtimeRoot.resolve("scripts"));
                    addScriptTree(out, runtimeRoot.resolve("custom_events"));
                    addScriptTree(out, runtimeRoot.resolve("stages"));
                    addLuaTree(out, folder);
                    addLuaTree(out, runtimeRoot.resolve("data").resolve(id));
                }
            }
            if (policy.songAssets() && packageRuntimeAssets) {
                addTree(out, runtimeRoot.resolve("sounds"));
                if (policy.allows(this, SongLibrary.ExternalContent.IMAGES)) {
                    addTree(out, runtimeRoot.resolve("images"));
                }
                if (policy.allows(this, SongLibrary.ExternalContent.CHARACTERS)) {
                    addTree(out, runtimeRoot.resolve("characters"));
                    addTree(out, runtimeRoot.resolve("animations"));
                }
            }
        }
        return out;
    }

    /** Transfers chart-selected note atlases even when the whole image tree is not packaged. */
    private void addChartNoteTextureFiles(List<Path> out, String difficulty, Path runtimeRoot) {
        try {
            SongChart chart = SongLibrary.loadChart(this, difficulty);
            addTexturePair(out, runtimeRoot, chart.noteTexture);
            addTexturePair(out, runtimeRoot, chart.noteSplashTexture);
        } catch (Exception ignored) {}
    }

    private static void addTexturePair(List<Path> out, Path root, String rawTexture) {
        if (root == null || rawTexture == null || rawTexture.isBlank()) return;
        String texture = rawTexture.trim().replace('\\', '/');
        String lower = texture.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".png") || lower.endsWith(".xml")) {
            texture = texture.substring(0, texture.length() - 4);
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        for (String extension : new String[]{".png", ".xml"}) {
            for (String prefix : new String[]{"images", "assets/images", ""}) {
                Path base = prefix.isBlank() ? normalizedRoot : normalizedRoot.resolve(prefix);
                Path candidate = base.resolve(texture + extension).normalize();
                if (candidate.startsWith(normalizedRoot) && Files.isRegularFile(candidate)) {
                    addIf(out, candidate);
                    break;
                }
            }
        }
    }

    /** Stable transfer path: local imported resources keep their folder hierarchy. */
    public String transferName(Path file) {
        Path runtimeRoot = runtimeRoot();
        if (runtimeRoot != null && file != null) {
            Path root = runtimeRoot.toAbsolutePath().normalize();
            Path normalized = file.toAbsolutePath().normalize();
            if (normalized.startsWith(root)) {
                Path relative = root.relativize(normalized);
                if (isStructuredRuntimeResource(relative)) {
                    return relative.toString().replace('\\', '/');
                }
            }
        }
        return file == null ? "" : file.getFileName().toString();
    }

    /** All files across every difficulty (used when a whole song must be transferred). */
    public List<Path> allTransferFiles() {
        return allTransferFiles(PlaybackPolicy.resolve(PlaybackMode.LEGACY, this));
    }

    public List<Path> allTransferFiles(PlaybackPolicy policy) {
        List<Path> out = new ArrayList<>();
        for (String d : difficulties) {
            for (Path p : transferFiles(d, policy)) {
                if (!out.contains(p)) out.add(p);
            }
        }
        return out;
    }

    private static void addIf(List<Path> list, Path p) {
        if (p != null && !list.contains(p)) list.add(p);
    }

    /** Never choose an arbitrary chart when several difficulties are present. */
    private static <T> T difficultyValue(Map<String, T> values, String requested) {
        if (values == null || values.isEmpty()) return null;
        T exact = values.get(requested);
        if (exact != null) return exact;
        String normalized = normalizeDifficulty(requested);
        for (var entry : values.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(requested == null ? "" : requested)
                    || normalizeDifficulty(entry.getKey()).equals(normalized)) {
                return entry.getValue();
            }
        }
        return values.size() == 1 ? values.values().iterator().next() : null;
    }

    private static String normalizeDifficulty(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9_-]", "-");
    }

    private static void addFonts(List<Path> list, Path root) {
        if (root == null) return;
        Path fonts = root.resolve("fonts");
        if (!Files.isDirectory(fonts)) return;
        try (var files = Files.list(fonts)) {
            files.filter(Files::isRegularFile).filter(path -> {
                String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                return name.endsWith(".ttf") || name.endsWith(".otf");
            }).sorted().forEach(path -> addIf(list, path));
        } catch (Exception ignored) {}
    }

    private static boolean isInstalledModRoot(Path root) {
        if (root == null) return false;
        Path mods = SongLibrary.modsDir().toAbsolutePath().normalize();
        return root.toAbsolutePath().normalize().startsWith(mods);
    }

    private static boolean isStructuredRuntimeResource(Path relative) {
        if (relative == null || relative.getNameCount() < 2) return false;
        String first = relative.getName(0).toString().toLowerCase(java.util.Locale.ROOT);
        return first.equals("custom_notetypes") || first.equals("custom_events")
                || first.equals("scripts") || first.equals("images") || first.equals("fonts")
                || first.equals("sounds") || first.equals("characters") || first.equals("stages")
                || first.equals("animations")
                || (first.equals("data") || first.equals("songs")) && relative.getFileName().toString()
                .toLowerCase(java.util.Locale.ROOT).endsWith(".lua");
    }

    private static void addTree(List<Path> list, Path root) {
        if (!Files.isDirectory(root)) return;
        try (var files = Files.walk(root)) {
            files.filter(Files::isRegularFile).sorted().forEach(path -> addIf(list, path));
        } catch (Exception ignored) {}
    }

    /** Lua/config files only; texture/audio siblings remain unavailable in restricted Minecraft mode. */
    private static void addScriptTree(List<Path> list, Path root) {
        if (!Files.isDirectory(root)) return;
        try (var files = Files.walk(root)) {
            files.filter(Files::isRegularFile).filter(path -> {
                String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                return name.endsWith(".lua") || name.endsWith(".txt") || name.endsWith(".json");
            }).sorted().forEach(path -> addIf(list, path));
        } catch (Exception ignored) {}
    }

    private static void addLuaTree(List<Path> list, Path root) {
        if (!Files.isDirectory(root)) return;
        try (var files = Files.walk(root)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT)
                            .endsWith(".lua"))
                    .sorted().forEach(path -> addIf(list, path));
        } catch (Exception ignored) {}
    }
}
