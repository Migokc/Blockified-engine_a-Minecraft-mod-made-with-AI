package com.fnfmod.song;

import com.fnfmod.FnfMod;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Persistent metadata-only song index. The index never stores audio, textures,
 * or chart contents; it stores the already-resolved paths and menu metadata.
 * A cheap size/mtime fingerprint invalidates it whenever relevant source files
 * change, avoiding repeated parsing of thousands of charts on normal startup.
 */
final class SongLibraryIndex {
    // Schema 2 rebuilds cached menu metadata so Blockified animation-defined
    // health icons participate in Freeplay resolution.
    private static final int SCHEMA = 2;
    private static final String FILE_NAME = "song-library-index-v1.json";

    record Snapshot(Map<String, SongEntry> songs, Map<String, Path> icons) {}
    private record FileStamp(String path, long size, long modified) {}

    private SongLibraryIndex() {}

    static Path file(Path cacheDirectory) {
        return cacheDirectory.resolve(FILE_NAME);
    }

    static String fingerprint(String configuration, List<Path> roots) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, "schema=" + SCHEMA + "\n" + configuration + "\n");
            for (Path suppliedRoot : roots) {
                if (suppliedRoot == null) continue;
                Path root = suppliedRoot.toAbsolutePath().normalize();
                update(digest, "root=" + root + "\n");
                if (!Files.isDirectory(root)) {
                    update(digest, "missing\n");
                    continue;
                }
                List<FileStamp> files = new ArrayList<>();
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (!dir.equals(root) && shouldSkipDirectory(dir.getFileName().toString())) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (attrs.isRegularFile() && relevant(root, file)) {
                            files.add(new FileStamp(root.relativize(file).toString().replace('\\', '/'),
                                    attrs.size(), attrs.lastModifiedTime().toMillis()));
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException error) {
                        return FileVisitResult.CONTINUE;
                    }
                });
                files.sort(Comparator.comparing(FileStamp::path));
                for (FileStamp file : files) {
                    update(digest, file.path + "\0" + file.size + "\0" + file.modified + "\n");
                }
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (Exception error) {
            FnfMod.LOGGER.warn("Could not fingerprint FNF song sources: {}", error.toString());
            return null;
        }
    }

    private static boolean shouldSkipDirectory(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.equals("worlds") || lower.equals(".git") || lower.equals(".gradle")
                || lower.equals("build") || lower.equals("logs") || lower.equals("crash-reports");
    }

    private static boolean relevant(Path root, Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".json") || name.endsWith(".ogg")) return true;
        if (name.equals(SongLibrary.ORIGINAL_DIRECTORY_FILE)) return true;
        if (!name.endsWith(".png")) return false;
        String relative = "/" + root.relativize(file).toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        return relative.contains("/icons/") || relative.contains("/images/characters/");
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }

    static Snapshot load(Path indexFile, String expectedFingerprint) {
        if (expectedFingerprint == null || !Files.isRegularFile(indexFile)) return null;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(indexFile)).getAsJsonObject();
            if (integer(root, "schema", -1) != SCHEMA
                    || !expectedFingerprint.equals(string(root, "fingerprint", ""))) return null;
            Map<String, SongEntry> songs = new LinkedHashMap<>();
            JsonArray entries = root.has("songs") && root.get("songs").isJsonArray()
                    ? root.getAsJsonArray("songs") : new JsonArray();
            for (JsonElement element : entries) {
                if (!element.isJsonObject()) continue;
                SongEntry entry = readEntry(element.getAsJsonObject());
                if (entry == null || entry.id == null || entry.id.isBlank()) return null;
                songs.put(entry.id, entry);
            }
            Map<String, Path> icons = readPathMap(root.get("icons"));
            return new Snapshot(songs, icons);
        } catch (Exception error) {
            FnfMod.LOGGER.debug("Ignoring unusable FNF song index: {}", error.toString());
            return null;
        }
    }

    static void save(Path indexFile, String fingerprint, Map<String, SongEntry> songs,
                     Map<String, Path> icons) {
        if (fingerprint == null) return;
        try {
            Files.createDirectories(indexFile.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("schema", SCHEMA);
            root.addProperty("fingerprint", fingerprint);
            JsonArray entries = new JsonArray();
            for (SongEntry entry : songs.values()) entries.add(writeEntry(entry));
            root.add("songs", entries);
            root.add("icons", writePathMap(icons));
            Path temporary = indexFile.resolveSibling(indexFile.getFileName() + ".tmp");
            Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(root));
            try {
                Files.move(temporary, indexFile, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException unsupportedAtomicMove) {
                Files.move(temporary, indexFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception error) {
            FnfMod.LOGGER.warn("Could not save FNF song index: {}", error.toString());
        }
    }

    private static JsonObject writeEntry(SongEntry entry) {
        JsonObject json = new JsonObject();
        property(json, "id", entry.id);
        property(json, "displayName", entry.displayName);
        path(json, "folder", entry.folder);
        property(json, "format", entry.format == null ? null : entry.format.name());
        path(json, "metaFile", entry.metaFile);
        path(json, "eventsFile", entry.eventsFile);
        json.addProperty("eventsOverride", entry.eventsOverride);
        property(json, "opponentIcon", entry.opponentIcon);
        path(json, "opponentIconFile", entry.opponentIconFile);
        path(json, "modRoot", entry.modRoot);
        json.addProperty("fullModLayout", entry.fullModLayout);
        path(json, "chartOriginRoot", entry.chartOriginRoot);
        path(json, "characterRoot", entry.characterRoot);
        JsonArray content = new JsonArray();
        EnumSet<SongLibrary.ExternalContent> enabled = entry.externalContent == null
                ? SongLibrary.allExternalContent() : entry.externalContent;
        for (SongLibrary.ExternalContent value : enabled) content.add(value.name());
        json.add("externalContent", content);
        json.add("difficulties", strings(entry.difficulties));
        json.add("luaFiles", paths(entry.luaFiles));
        json.add("legacyChartFiles", writePathMap(entry.legacyChartFiles));
        json.add("chartOverrides", writePathMap(entry.chartOverrides));
        json.add("vsliceRealDiff", writeStringMap(entry.vsliceRealDiff));

        JsonObject variations = new JsonObject();
        for (var item : entry.vsliceVariations.entrySet()) {
            SongEntry.VSliceVariation value = item.getValue();
            JsonObject variation = new JsonObject();
            path(variation, "chartFile", value.chartFile);
            path(variation, "metadataFile", value.metadataFile);
            path(variation, "instFile", value.instFile);
            path(variation, "voicesFile", value.voicesFile);
            path(variation, "voicesPlayerFile", value.voicesPlayerFile);
            path(variation, "voicesOpponentFile", value.voicesOpponentFile);
            variations.add(item.getKey(), variation);
        }
        json.add("vsliceVariations", variations);
        path(json, "instFile", entry.instFile);
        path(json, "voicesFile", entry.voicesFile);
        path(json, "voicesPlayerFile", entry.voicesPlayerFile);
        path(json, "voicesOpponentFile", entry.voicesOpponentFile);
        return json;
    }

    private static SongEntry readEntry(JsonObject json) {
        SongEntry entry = new SongEntry();
        entry.id = string(json, "id", null);
        entry.displayName = string(json, "displayName", entry.id);
        entry.folder = path(json, "folder");
        try {
            entry.format = SongEntry.Format.valueOf(string(json, "format", "LEGACY"));
        } catch (Exception ignored) {
            entry.format = SongEntry.Format.LEGACY;
        }
        entry.metaFile = path(json, "metaFile");
        entry.eventsFile = path(json, "eventsFile");
        entry.eventsOverride = bool(json, "eventsOverride");
        entry.opponentIcon = string(json, "opponentIcon", "");
        entry.opponentIconFile = path(json, "opponentIconFile");
        entry.modRoot = path(json, "modRoot");
        entry.fullModLayout = bool(json, "fullModLayout");
        entry.chartOriginRoot = path(json, "chartOriginRoot");
        entry.characterRoot = path(json, "characterRoot");
        entry.externalContent = EnumSet.noneOf(SongLibrary.ExternalContent.class);
        JsonElement enabled = json.get("externalContent");
        if (enabled != null && enabled.isJsonArray()) {
            for (JsonElement value : enabled.getAsJsonArray()) {
                try {
                    entry.externalContent.add(SongLibrary.ExternalContent.valueOf(value.getAsString()));
                } catch (Exception ignored) {}
            }
        } else {
            entry.externalContent = SongLibrary.allExternalContent();
        }
        readStrings(json.get("difficulties"), entry.difficulties);
        readPaths(json.get("luaFiles"), entry.luaFiles);
        entry.legacyChartFiles.putAll(readPathMap(json.get("legacyChartFiles")));
        entry.chartOverrides.putAll(readPathMap(json.get("chartOverrides")));
        entry.vsliceRealDiff.putAll(readStringMap(json.get("vsliceRealDiff")));
        JsonElement variations = json.get("vsliceVariations");
        if (variations != null && variations.isJsonObject()) {
            for (var item : variations.getAsJsonObject().entrySet()) {
                if (!item.getValue().isJsonObject()) continue;
                JsonObject value = item.getValue().getAsJsonObject();
                SongEntry.VSliceVariation variation = new SongEntry.VSliceVariation();
                variation.chartFile = path(value, "chartFile");
                variation.metadataFile = path(value, "metadataFile");
                variation.instFile = path(value, "instFile");
                variation.voicesFile = path(value, "voicesFile");
                variation.voicesPlayerFile = path(value, "voicesPlayerFile");
                variation.voicesOpponentFile = path(value, "voicesOpponentFile");
                entry.vsliceVariations.put(item.getKey(), variation);
            }
        }
        entry.instFile = path(json, "instFile");
        entry.voicesFile = path(json, "voicesFile");
        entry.voicesPlayerFile = path(json, "voicesPlayerFile");
        entry.voicesOpponentFile = path(json, "voicesOpponentFile");
        return entry;
    }

    private static JsonArray strings(List<String> values) {
        JsonArray json = new JsonArray();
        for (String value : values) json.add(value);
        return json;
    }

    private static void readStrings(JsonElement json, List<String> output) {
        if (json == null || !json.isJsonArray()) return;
        for (JsonElement value : json.getAsJsonArray()) output.add(value.getAsString());
    }

    private static JsonArray paths(List<Path> values) {
        JsonArray json = new JsonArray();
        for (Path value : values) json.add(value.toAbsolutePath().normalize().toString());
        return json;
    }

    private static void readPaths(JsonElement json, List<Path> output) {
        if (json == null || !json.isJsonArray()) return;
        for (JsonElement value : json.getAsJsonArray()) output.add(Path.of(value.getAsString()));
    }

    private static JsonObject writePathMap(Map<String, Path> values) {
        JsonObject json = new JsonObject();
        for (var value : values.entrySet()) {
            if (value.getValue() != null) json.addProperty(value.getKey(),
                    value.getValue().toAbsolutePath().normalize().toString());
        }
        return json;
    }

    private static Map<String, Path> readPathMap(JsonElement json) {
        Map<String, Path> output = new LinkedHashMap<>();
        if (json == null || !json.isJsonObject()) return output;
        for (var value : json.getAsJsonObject().entrySet()) {
            output.put(value.getKey(), Path.of(value.getValue().getAsString()));
        }
        return output;
    }

    private static JsonObject writeStringMap(Map<String, String> values) {
        JsonObject json = new JsonObject();
        for (var value : values.entrySet()) json.addProperty(value.getKey(), value.getValue());
        return json;
    }

    private static Map<String, String> readStringMap(JsonElement json) {
        Map<String, String> output = new LinkedHashMap<>();
        if (json == null || !json.isJsonObject()) return output;
        for (var value : json.getAsJsonObject().entrySet()) {
            output.put(value.getKey(), value.getValue().getAsString());
        }
        return output;
    }

    private static void property(JsonObject json, String key, String value) {
        if (value != null) json.addProperty(key, value);
    }

    private static void path(JsonObject json, String key, Path value) {
        if (value != null) json.addProperty(key, value.toAbsolutePath().normalize().toString());
    }

    private static Path path(JsonObject json, String key) {
        String value = string(json, key, null);
        return value == null || value.isBlank() ? null : Path.of(value);
    }

    private static String string(JsonObject json, String key, String fallback) {
        try {
            return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static int integer(JsonObject json, String key, int fallback) {
        try {
            return json.has(key) ? json.get(key).getAsInt() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static boolean bool(JsonObject json, String key) {
        try {
            return json.has(key) && json.get(key).getAsBoolean();
        } catch (Exception ignored) {
            return false;
        }
    }
}
