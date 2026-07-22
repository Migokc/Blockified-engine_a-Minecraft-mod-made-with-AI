package com.fnfmod.character;

import com.fnfmod.song.SongLibrary;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/** Shared, safe lookup rules for Blockified BBS character definition JSON files. */
public final class CharacterDefinitionPaths {
    public static final String NONE = "none";
    public static final String DEFAULT = "default";
    public static final String MOD_PREFIX = "@mod:";

    private CharacterDefinitionPaths() {}

    /**
     * User-facing definitions. Only JSON files beneath config/fnfmod/animations
     * are exposed; installed mod definitions are deliberately absent.
     */
    public static List<String> selectableGlobalNames() {
        Path root = SongLibrary.animationsDir().toAbsolutePath().normalize();
        Map<String, String> names = new LinkedHashMap<>();
        if (!Files.isDirectory(root)) return List.of();

        try (Stream<Path> files = Files.list(root)) {
            files.filter(Files::isRegularFile)
                    .filter(CharacterDefinitionPaths::isPrimaryJson)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString(),
                            String.CASE_INSENSITIVE_ORDER))
                    .forEach(path -> {
                        String stem = stem(path);
                        if (stem.equalsIgnoreCase(DEFAULT)) return;
                        names.putIfAbsent(normalizeName(stem), stem);
                    });
        } catch (Exception ignored) {}

        // Compatibility: old animations/<name>/character.json layouts remain
        // selectable, but new definitions are saved as animations/<name>.json.
        try (Stream<Path> directories = Files.list(root)) {
            directories.filter(Files::isDirectory)
                    .filter(path -> Files.isRegularFile(path.resolve("character.json")))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString(),
                            String.CASE_INSENSITIVE_ORDER))
                    .forEach(path -> {
                        String name = path.getFileName().toString();
                        if (!name.equalsIgnoreCase(DEFAULT)) names.putIfAbsent(normalizeName(name), name);
                    });
        } catch (Exception ignored) {}
        return List.copyOf(names.values());
    }

    /** Marks a definition name as private to the currently playing mod. */
    public static String modScoped(String name) {
        String normalized = normalizeName(name);
        return MOD_PREFIX + (DEFAULT.equals(normalized) ? "" : normalized);
    }

    public static boolean isModScoped(String name) {
        return name != null && name.trim().toLowerCase(Locale.ROOT).startsWith(MOD_PREFIX);
    }

    public static String unscopedName(String name) {
        String clean = name == null ? "" : name.trim();
        if (isModScoped(clean)) clean = clean.substring(MOD_PREFIX.length());
        return normalizeName(clean);
    }

    /** Global config definition, with old folder layout fallback. */
    public static Path globalCharacterJson(String setName, boolean opponent) {
        return definitionJson(SongLibrary.animationsDir(), setName, opponent, true);
    }

    /** Same lookup without sharing the normal file when an opponent file is absent. */
    public static Path globalCharacterJsonExact(String setName, boolean opponent) {
        return definitionJson(SongLibrary.animationsDir(), setName, opponent, false);
    }

    /** Private definition from one complete Blockified/Psych mod root. */
    public static Path modCharacterJson(Path modRoot, String setName, boolean opponent) {
        Path root = child(modRoot, "animations");
        return definitionJson(root, setName, opponent, true);
    }

    public static Path modCharacterJsonExact(Path modRoot, String setName, boolean opponent) {
        Path root = child(modRoot, "animations");
        return definitionJson(root, setName, opponent, false);
    }

    /**
     * Server-side transform lookup. Ordinary setting names always resolve from
     * the global animations folder. Default and @mod names may use the active mod.
     */
    public static Path characterJson(String setName, Path modRoot, boolean opponent) {
        return characterJson(setName, modRoot, opponent, null);
    }

    public static Path characterJson(String setName, Path modRoot, boolean opponent,
                                     String defaultCharacter) {
        String selected = unscopedName(setName);
        if (isModScoped(setName)) {
            Path local = modCharacterJson(modRoot, selected, opponent);
            return local != null ? local : globalCharacterJson(DEFAULT, opponent);
        }
        if (DEFAULT.equals(selected)) {
            if (defaultCharacter != null && !defaultCharacter.isBlank()) {
                Path character = modCharacterJson(modRoot, defaultCharacter, opponent);
                if (character != null) return character;
            }
            Path localDefault = modCharacterJson(modRoot, DEFAULT, opponent);
            if (localDefault != null) return localDefault;
        }
        return globalCharacterJson(selected, opponent);
    }

    public static String normalizeName(String name) {
        if (name == null || name.isBlank()) return DEFAULT;
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private static Path definitionJson(Path root, String rawName, boolean opponent,
                                       boolean fallbackOpponent) {
        Path normalizedRoot = normalize(root);
        if (normalizedRoot == null || !Files.isDirectory(normalizedRoot)) return null;
        String name = safeFileStem(unscopedName(rawName));
        if (name == null) return null;

        List<Path> candidates = new ArrayList<>();
        if (DEFAULT.equals(name)) {
            candidates.add(normalizedRoot.resolve(opponent ? "default-opp.json" : "default.json"));
            candidates.add(normalizedRoot.resolve(opponent ? "character-opp.json" : "character.json"));
            Path legacy = normalizedRoot.resolve(DEFAULT);
            candidates.add(legacy.resolve(opponent ? "character-opp.json" : "character.json"));
        } else {
            candidates.add(normalizedRoot.resolve(name + (opponent ? "-opp" : "") + ".json"));
            Path legacy = normalizedRoot.resolve(name);
            candidates.add(legacy.resolve(opponent ? "character-opp.json" : "character.json"));
        }
        if (opponent && fallbackOpponent) {
            // Opponent-specific data is optional; the normal definition is shared.
            if (DEFAULT.equals(name)) {
                candidates.add(normalizedRoot.resolve("default.json"));
                candidates.add(normalizedRoot.resolve("character.json"));
                candidates.add(normalizedRoot.resolve(DEFAULT).resolve("character.json"));
            } else {
                candidates.add(normalizedRoot.resolve(name + ".json"));
                candidates.add(normalizedRoot.resolve(name).resolve("character.json"));
            }
        }

        for (Path candidate : candidates) {
            Path safe = candidate.normalize();
            if (!safe.startsWith(normalizedRoot)) continue;
            Path existing = caseInsensitiveFile(normalizedRoot, normalizedRoot.relativize(safe));
            if (existing != null) return existing;
        }
        return null;
    }

    private static Path caseInsensitiveFile(Path root, Path relative) {
        Path current = root;
        for (Path part : relative) {
            Path exact = current.resolve(part.toString());
            if (Files.exists(exact)) {
                current = exact;
                continue;
            }
            if (!Files.isDirectory(current)) return null;
            try (Stream<Path> children = Files.list(current)) {
                Path match = children.filter(path -> path.getFileName().toString()
                                .equalsIgnoreCase(part.toString()))
                        .findFirst().orElse(null);
                if (match == null) return null;
                current = match;
            } catch (Exception ignored) {
                return null;
            }
        }
        return Files.isRegularFile(current) ? current : null;
    }

    private static boolean isPrimaryJson(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".json") && !name.endsWith("-opp.json")
                && !name.equals("character-opp.json") && !name.equals("mapping.json");
    }

    private static String stem(Path path) {
        String name = path.getFileName().toString();
        return name.substring(0, name.length() - ".json".length());
    }

    private static String safeFileStem(String value) {
        if (value == null || value.isBlank() || value.equals(".") || value.equals("..")
                || value.contains("/") || value.contains("\\")) return null;
        return value;
    }

    private static Path child(Path root, String part) {
        Path normalized = normalize(root);
        if (normalized == null) return null;
        Path candidate = normalized.resolve(part).normalize();
        return candidate.startsWith(normalized) ? candidate : null;
    }

    private static Path normalize(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
    }
}
