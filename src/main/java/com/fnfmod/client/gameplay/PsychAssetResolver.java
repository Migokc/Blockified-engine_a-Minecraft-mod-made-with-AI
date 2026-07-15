package com.fnfmod.client.gameplay;

import com.fnfmod.gameplay.PlaybackPolicy;
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
            add(roots, songFolder);
            if (entry != null) {
                add(roots, entry.modRoot);
                add(roots, entry.folder);
                if (content == SongLibrary.ExternalContent.CHARACTERS
                        || content == SongLibrary.ExternalContent.ICONS) {
                    add(roots, entry.characterRoot);
                }
            }
        }
        // Shared Psych assets obey the first configured directory's checklist,
        // independently from whichever directory supplied this song/chart.
        if (policy.songAssets()) add(roots, SongLibrary.primaryExternalAssetRoot(content));
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

    private static Path normalize(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
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
