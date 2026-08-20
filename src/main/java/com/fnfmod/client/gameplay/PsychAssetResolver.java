package com.fnfmod.client.gameplay;

import com.fnfmod.gameplay.PlaybackPolicy;
import com.fnfmod.gameplay.PlaybackMode;
import com.fnfmod.song.SongEntry;
import com.fnfmod.song.SongLibrary;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Ordered Psych resource lookup: current song/mod, then first configured directory. */
public final class PsychAssetResolver {
    private final Path songFolder;
    private final SongEntry entry;
    private final PlaybackPolicy policy;
    private final String library;

    public PsychAssetResolver(Path songFolder, SongEntry entry, PlaybackPolicy policy) {
        this(songFolder, entry, policy, "");
    }

    public PsychAssetResolver(Path songFolder, SongEntry entry, PlaybackPolicy policy, String stageId) {
        this.songFolder = normalize(songFolder);
        this.entry = entry;
        this.policy = policy;
        this.library = stageLibrary(stageId);
    }

    public List<Path> roots(SongLibrary.ExternalContent content) {
        if (policy == null) return List.of();
        // Auxiliary sounds are assets, not the chart's required Inst/Voices.
        if (content == SongLibrary.ExternalContent.AUDIO && !policy.songAssets()) return List.of();
        List<Path> roots = new ArrayList<>();
        // Current song/mod obeys its source directory's checklist.
        if (policy.allows(entry, content)) {
            addSongRoot(roots, songFolder, content);
            if (entry != null) {
                addSongRoot(roots, entry.modRoot, content);
                addSongRoot(roots, entry.folder, content);
                if (content == SongLibrary.ExternalContent.CHARACTERS
                        || content == SongLibrary.ExternalContent.ICONS) {
                    addSongRoot(roots, entry.characterRoot, content);
                }
            }
        }
        // Naked global mod (config/fnfmod/mods root): trusted installed content is
        // independent from the current song's source. In particular, a global Lua
        // script may create a world character from global characters/ while playing
        // an external/lightweight song in Minecraft mode. The source checklist still
        // applies. Dedicated-server sessions advertise luaAllowed=false and must not
        // regain local rich assets here; bundled mod worlds return no global root.
        Path global = SongLibrary.globalSharedAssetRoot();
        if (policy.luaAllowed() && global != null
                && SongLibrary.getExternalFolderContent(
                global.toAbsolutePath().normalize().toString()).contains(content)) {
            add(roots, global);
        }
        // Shared Psych assets obey the first configured directory's checklist,
        // independently from whichever directory supplied this song/chart.
        if (policy.songAssets() && policy.mode() != PlaybackMode.MINECRAFT) {
            add(roots, SongLibrary.primaryExternalAssetRoot(content));
        }
        return List.copyOf(roots);
    }

    /**
     * Custom-note skins often refer to an engine-bundled atlas (for example
     * HURTNOTE_assets) rather than a file copied into the mod. Keep the user's
     * first configured asset directory as the shared priority, then fall back
     * to the assets folder belonging to this song's own Psych-style mods tree.
     */
    public List<Path> customNoteRoots() {
        List<Path> roots = new ArrayList<>(roots(SongLibrary.ExternalContent.IMAGES));
        if (policy != null && policy.mode() != PlaybackMode.MINECRAFT
                && policy.allows(entry, SongLibrary.ExternalContent.IMAGES)) {
            Path sourceAssets = sourceEngineAssets();
            add(roots, sourceAssets);
        }
        return List.copyOf(roots);
    }

    public Path image(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String file = withExtension(raw, ".png");
        List<Path> roots = roots(SongLibrary.ExternalContent.IMAGES);
        Path direct = find(roots, file,
                "images", "assets/images", "shared/images", "assets/shared/images", "");
        if (direct != null || library.isBlank()) return direct;
        return find(roots, file, library + "/images", "assets/" + library + "/images");
    }

    /** Finds an Adobe Animate atlas folder instead of a single PNG. */
    public Path animateFolder(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim().replace('\\', '/');
        String assetLibrary = "";
        int colon = value.indexOf(':');
        if (colon > 0) {
            assetLibrary = value.substring(0, colon);
            value = value.substring(colon + 1);
        }
        while (value.startsWith("/")) value = value.substring(1);
        List<String> prefixes = new ArrayList<>();
        prefixes.add("");
        prefixes.add("images");
        prefixes.add("assets/images");
        prefixes.add("shared/images");
        prefixes.add("assets/shared/images");
        if (!assetLibrary.isBlank()) {
            prefixes.add(assetLibrary + "/images");
            prefixes.add("assets/" + assetLibrary + "/images");
        }
        for (Path root : roots(SongLibrary.ExternalContent.IMAGES)) {
            for (String prefix : prefixes) {
                Path base = prefix.isBlank() ? root : root.resolve(prefix);
                Path candidate = base.resolve(value).normalize();
                if (candidate.startsWith(root) && Files.isDirectory(candidate)
                        && Files.isRegularFile(candidate.resolve("Animation.json"))) return candidate;
            }
        }
        return null;
    }

    public Path sound(String raw) {
        if (raw == null || raw.isBlank()) return null;
        boolean music = raw.startsWith("@music/");
        String value = music ? raw.substring(7) : raw;
        String file = withExtension(value, ".ogg");
        List<Path> roots = roots(SongLibrary.ExternalContent.AUDIO);
        Path direct;
        if (music) direct = find(roots, file,
                "music", "shared/music", "assets/music", "assets/shared/music",
                "sounds", "shared/sounds", "assets/sounds", "assets/shared/sounds", "");
        else direct = find(roots, file,
                    "sounds", "shared/sounds", "assets/sounds", "assets/shared/sounds",
                    "music", "shared/music", "assets/music", "assets/shared/music", "");
        if (direct != null || library.isBlank()) return direct;
        return music
                ? find(roots, file, library + "/music", "assets/" + library + "/music",
                library + "/sounds", "assets/" + library + "/sounds")
                : find(roots, file, library + "/sounds", "assets/" + library + "/sounds",
                library + "/music", "assets/" + library + "/music");
    }

    public Path stage(String id) {
        if (id == null || id.isBlank()) return null;
        return find(roots(SongLibrary.ExternalContent.IMAGES), withExtension(id, ".json"),
                "stages", "shared/stages", "data/stages", "assets/stages", "assets/shared/stages");
    }

    public Path character(String id) {
        if (id == null || id.isBlank()) return null;
        return find(roots(SongLibrary.ExternalContent.CHARACTERS), withExtension(id, ".json"),
                "characters", "shared/characters", "data/characters",
                "assets/characters", "assets/shared/characters");
    }

    public String characterIcon(String characterId) {
        Path json = character(characterId);
        if (json == null) return characterId == null ? "" : characterId;
        try {
            JsonObject object = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
            if (object.has("healthIcon")) {
                var value = object.get("healthIcon");
                if (value.isJsonObject() && value.getAsJsonObject().has("id")) {
                    return value.getAsJsonObject().get("id").getAsString();
                }
                if (value.isJsonPrimitive()) return value.getAsString();
            }
            if (object.has("healthicon")) return object.get("healthicon").getAsString();
        } catch (Exception ignored) {}
        return characterId == null ? "" : characterId;
    }

    public static Path find(List<Path> roots, String relative, String... prefixes) {
        String safe = relative.replace('\\', '/');
        for (Path root : roots) {
            for (String prefix : prefixes) {
                Path base = prefix.isBlank() ? root : root.resolve(prefix);
                Path candidate = base.resolve(safe).normalize();
                if (candidate.startsWith(root) && Files.isRegularFile(candidate)) return candidate;
            }
        }
        return null;
    }

    private static void add(List<Path> roots, Path path) {
        Path normalized = normalize(path);
        if (normalized != null && Files.isDirectory(normalized) && !roots.contains(normalized)) {
            roots.add(normalized);
        }
    }

    /** Minecraft rich resources may not escape the selected installed mod/cache root. */
    private void addSongRoot(List<Path> roots, Path path, SongLibrary.ExternalContent content) {
        if (policy != null && policy.mode() == PlaybackMode.MINECRAFT && isRichAsset(content)
                && entry != null && entry.modRoot != null) {
            Path candidate = normalize(path);
            Path owner = normalize(entry.modRoot);
            if (candidate == null || owner == null || !candidate.startsWith(owner)) return;
        }
        add(roots, path);
    }

    private static boolean isRichAsset(SongLibrary.ExternalContent content) {
        return content != SongLibrary.ExternalContent.CHARTS
                && content != SongLibrary.ExternalContent.EVENTS
                && content != SongLibrary.ExternalContent.LUA;
    }

    private static Path normalize(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
    }

    private Path sourceEngineAssets() {
        if (entry == null || entry.modRoot == null) return null;
        Path mod = normalize(entry.modRoot);
        Path mods = mod == null ? null : mod.getParent();
        if (mods == null || mods.getFileName() == null
                || !mods.getFileName().toString().equalsIgnoreCase("mods")) return null;
        Path engine = mods.getParent();
        if (engine == null) return null;
        Path assets = engine.resolve("assets").normalize();
        return Files.isDirectory(assets) ? assets : null;
    }

    private String stageLibrary(String stageId) {
        if (stageId == null || stageId.isBlank()) return "";
        Path json = find(roots(SongLibrary.ExternalContent.IMAGES), withExtension(stageId, ".json"),
                "stages", "shared/stages", "data/stages", "assets/stages", "assets/shared/stages");
        if (json == null) return "";
        try {
            JsonObject object = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
            if (object.has("directory")) return object.get("directory").getAsString().trim();
        } catch (Exception ignored) {}
        return "";
    }

    private static String withExtension(String value, String extension) {
        String normalized = value.trim().replace('\\', '/');
        return normalized.toLowerCase(Locale.ROOT).endsWith(extension) ? normalized : normalized + extension;
    }
}
