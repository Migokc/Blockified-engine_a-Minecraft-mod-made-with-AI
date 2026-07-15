package com.fnfmod.character;

import com.fnfmod.song.SongLibrary;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Safe, shared lookup rules for Blockified playerAnimator character definitions. */
public final class CharacterDefinitionPaths {
    public static final String NONE = "none";
    public static final String DEFAULT = "default";

    private CharacterDefinitionPaths() {}

    /** Low-to-high priority directories whose files form one character set. */
    public static List<Path> directories(String setName, Path songFolder) {
        String selected = normalizeName(setName);
        if (NONE.equals(selected)) return List.of();
        List<Path> result = new ArrayList<>();
        Path globalRoot = SongLibrary.animationsDir().toAbsolutePath().normalize();
        if (DEFAULT.equals(selected)) {
            addDirectory(result, globalRoot);
            addDirectory(result, normalize(songFolder));
            addDirectory(result, child(songFolder, "animations"));
        } else {
            addDirectory(result, safeChild(globalRoot, selected));
            addDirectory(result, child(songFolder, "characters", selected));
            addDirectory(result, child(songFolder, "animations", selected));
        }
        return List.copyOf(result);
    }

    /** Highest-priority character.json for server-side rotation loading. */
    public static Path characterJson(String setName, Path songFolder) {
        return characterJson(setName, songFolder, false);
    }

    /** Opponent uses character-opp.json, falling back to character.json. */
    public static Path characterJson(String setName, Path songFolder, boolean opponent) {
        List<Path> directories = directories(setName, songFolder);
        for (int i = directories.size() - 1; i >= 0; i--) {
            Path file = directories.get(i).resolve(opponent ? "character-opp.json" : "character.json");
            if (Files.isRegularFile(file)) return file;
        }
        return opponent ? characterJson(setName, songFolder, false) : null;
    }

    public static String normalizeName(String name) {
        if (name == null || name.isBlank()) return DEFAULT;
        return name.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static Path child(Path root, String... parts) {
        Path normalized = normalize(root);
        if (normalized == null) return null;
        Path candidate = normalized;
        for (String part : parts) candidate = candidate.resolve(part);
        candidate = candidate.normalize();
        return candidate.startsWith(normalized) ? candidate : null;
    }

    private static Path safeChild(Path root, String name) {
        if (root == null || name == null || name.isBlank()) return null;
        Path child = root.resolve(name).normalize();
        return child.startsWith(root) ? child : null;
    }

    private static Path normalize(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
    }

    private static void addDirectory(List<Path> out, Path path) {
        if (path != null && Files.isDirectory(path) && !out.contains(path)) out.add(path);
    }
}
