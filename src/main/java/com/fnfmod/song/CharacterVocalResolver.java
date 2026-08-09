package com.fnfmod.song;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/** Resolves character-defined vocal stems across Psych-family and Blockified layouts. */
public final class CharacterVocalResolver {
    private static final String[] PREFIX_KEYS = {
            "vocals_file", "vocalsFile", "vocal_file", "vocalFile",
            "vocals_prefix", "vocalsPrefix", "vocal_prefix", "vocalPrefix"
    };

    private CharacterVocalResolver() {}

    public static Path resolve(Path audioDirectory, Path definitionRoot, String characterId,
                               boolean opponent, String variationSuffix) {
        if (audioDirectory == null || !Files.isDirectory(audioDirectory)) return null;
        Map<String, Path> audio = indexAudio(audioDirectory);
        String prefix = explicitPrefix(definitionRoot, characterId, opponent);
        if (prefix == null || prefix.isBlank()) prefix = characterId;
        return resolve(audio, prefix, variationSuffix);
    }

    static Path resolve(Map<String, Path> audio, Path definitionRoot, String characterId,
                        boolean opponent, String variationSuffix) {
        String prefix = explicitPrefix(definitionRoot, characterId, opponent);
        if (prefix == null || prefix.isBlank()) prefix = characterId;
        return resolve(audio, prefix, variationSuffix);
    }

    private static Path resolve(Map<String, Path> audio, String rawPrefix, String variationSuffix) {
        if (rawPrefix == null || rawPrefix.isBlank()) return null;
        String value = rawPrefix.trim().replace('\\', '/');
        int slash = value.lastIndexOf('/');
        if (slash >= 0) value = value.substring(slash + 1);
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".ogg")) value = value.substring(0, value.length() - 4);
        boolean alreadyNamed = lower.startsWith("voices") || lower.startsWith("vocals");
        String base = alreadyNamed ? value : "Voices-" + value;
        String suffix = variationSuffix == null ? "" : variationSuffix.trim();
        List<String> candidates = new ArrayList<>();
        if (!suffix.isBlank()) candidates.add(base + suffix + ".ogg");
        candidates.add(base + ".ogg");
        if (!alreadyNamed) {
            if (!suffix.isBlank()) candidates.add("Vocals-" + value + suffix + ".ogg");
            candidates.add("Vocals-" + value + ".ogg");
        }
        for (String candidate : candidates) {
            Path found = audio.get(candidate.toLowerCase(Locale.ROOT));
            if (found != null) return found;
        }
        return null;
    }

    public static String explicitPrefix(Path root, String characterId, boolean opponent) {
        if (characterId == null || characterId.isBlank()) return "";
        for (Path file : definitionCandidates(root, characterId, opponent)) {
            String value = readPrefix(file);
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private static List<Path> definitionCandidates(Path root, String id, boolean opponent) {
        List<Path> files = new ArrayList<>();
        if (root != null) {
            addRootCandidates(files, root, id, opponent);
            Path parent = root.getParent();
            if (parent != null && root.getFileName() != null
                    && root.getFileName().toString().equalsIgnoreCase("data")) {
                addRootCandidates(files, parent, id, opponent);
            }
        }
        addRootCandidates(files, SongLibrary.root(), id, opponent);
        return files;
    }

    private static void addRootCandidates(List<Path> files, Path root, String id, boolean opponent) {
        String roleSuffix = opponent ? "-opp" : "";
        files.add(root.resolve("characters").resolve(id + ".json"));
        files.add(root.resolve("data").resolve("characters").resolve(id + ".json"));
        files.add(root.resolve("animations").resolve(id + roleSuffix + ".json"));
        files.add(root.resolve("animations").resolve(id)
                .resolve(opponent ? "character-opp.json" : "character.json"));
        if (opponent) {
            // Blockified also allows an opponent chart ID to select a normal
            // named definition, then applies that definition in opponent role.
            files.add(root.resolve("animations").resolve(id + ".json"));
            files.add(root.resolve("animations").resolve(id).resolve("character.json"));
        }
    }

    private static String readPrefix(Path file) {
        if (file == null || !Files.isRegularFile(file)) return "";
        try {
            JsonObject object = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            for (String key : PREFIX_KEYS) {
                JsonElement value = object.get(key);
                if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                    String text = value.getAsString().trim();
                    if (!text.isBlank()) return text;
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    static Map<String, Path> indexAudio(Path directory) {
        Map<String, Path> result = new LinkedHashMap<>();
        if (directory == null || !Files.isDirectory(directory)) return result;
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(Files::isRegularFile).forEach(file -> {
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.endsWith(".ogg")) result.put(name, file);
            });
        } catch (Exception ignored) {}
        return result;
    }
}
