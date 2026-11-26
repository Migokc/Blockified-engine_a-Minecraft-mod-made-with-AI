package com.fnfmod.song;

import com.fnfmod.FnfMod;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Persists ordered external mod directories and each directory's resource filter. */
public final class ExternalDirectoryConfig {
    private static final String PACK_SEPARATOR = "::blockified-pack::";
    private static final String MINECRAFT_VISUALS = "minecraftVisuals";
    private static final Object FILTERS_LOCK = new Object();
    private final Path root;

    public ExternalDirectoryConfig(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    private Path foldersFile() {
        return root.resolve("external_folders.txt");
    }

    private Path filtersFile() {
        return root.resolve("external_folder_filters.json");
    }

    public List<String> folders() {
        List<String> result = new ArrayList<>();
        try {
            if (Files.isRegularFile(foldersFile())) {
                for (String line : Files.readAllLines(foldersFile())) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) result.add(trimmed);
                }
            }
        } catch (IOException error) {
            FnfMod.LOGGER.warn("Could not read external_folders.txt: {}", error.toString());
        }
        return result;
    }

    public void setFolders(List<String> folders) {
        try {
            Files.createDirectories(root);
            Files.write(foldersFile(), folders);
        } catch (IOException error) {
            FnfMod.LOGGER.warn("Could not save external_folders.txt: {}", error.toString());
        }
    }

    /** A missing filter means every resource group, preserving old installations. */
    public EnumSet<SongLibrary.ExternalContent> content(String folder) {
        EnumSet<SongLibrary.ExternalContent> saved = contentOrNull(folder);
        return saved == null ? SongLibrary.allExternalContent() : EnumSet.copyOf(saved);
    }

    /** Null means the target inherits its containing source's checklist. */
    public EnumSet<SongLibrary.ExternalContent> contentOrNull(String key) {
        EnumSet<SongLibrary.ExternalContent> saved = readFilters().get(key);
        return saved == null ? null : EnumSet.copyOf(saved);
    }

    public static String packKey(String source, Path pack) {
        return source + PACK_SEPARATOR + pack.toAbsolutePath().normalize();
    }

    public void setContent(String folder, SongLibrary.ExternalContent content, boolean enabled) {
        synchronized (FILTERS_LOCK) {
            Map<String, EnumSet<SongLibrary.ExternalContent>> filters = readFilters();
            EnumSet<SongLibrary.ExternalContent> selected = filters.containsKey(folder)
                    ? EnumSet.copyOf(filters.get(folder)) : SongLibrary.allExternalContent();
            if (enabled) selected.add(content); else selected.remove(content);
            filters.put(folder, selected);
            writeFilters(filters);
        }
    }

    public void setContent(String key, EnumSet<SongLibrary.ExternalContent> selected) {
        synchronized (FILTERS_LOCK) {
            Map<String, EnumSet<SongLibrary.ExternalContent>> filters = readFilters();
            filters.put(key, EnumSet.copyOf(selected));
            writeFilters(filters);
        }
    }

    /** Per-pack visual switches share the filter file but not the filter-array namespace. */
    public Map<String, Boolean> minecraftVisualOverrides() {
        synchronized (FILTERS_LOCK) {
            Map<String, Boolean> result = new LinkedHashMap<>();
            JsonObject json = readFilterJson();
            if (!json.has(MINECRAFT_VISUALS) || !json.get(MINECRAFT_VISUALS).isJsonObject()) return result;
            for (var entry : json.getAsJsonObject(MINECRAFT_VISUALS).entrySet()) {
                if (entry.getValue().isJsonPrimitive()
                        && entry.getValue().getAsJsonPrimitive().isBoolean()) {
                    result.put(entry.getKey(), entry.getValue().getAsBoolean());
                }
            }
            return result;
        }
    }

    public void setMinecraftVisualOverrides(Map<String, Boolean> overrides) throws IOException {
        synchronized (FILTERS_LOCK) {
            JsonObject json = readFilterJson();
            JsonObject visuals = new JsonObject();
            overrides.forEach(visuals::addProperty);
            json.add(MINECRAFT_VISUALS, visuals);
            writeFilterJson(json);
        }
    }

    private Map<String, EnumSet<SongLibrary.ExternalContent>> readFilters() {
        Map<String, EnumSet<SongLibrary.ExternalContent>> result = new LinkedHashMap<>();
        synchronized (FILTERS_LOCK) {
            JsonObject json = readFilterJson();
            for (var entry : json.entrySet()) {
                if (!entry.getValue().isJsonArray()) continue;
                EnumSet<SongLibrary.ExternalContent> selected =
                        EnumSet.noneOf(SongLibrary.ExternalContent.class);
                for (JsonElement value : entry.getValue().getAsJsonArray()) {
                    try {
                        selected.add(SongLibrary.ExternalContent.valueOf(
                                value.getAsString().toUpperCase(Locale.ROOT)));
                    } catch (Exception ignored) {}
                }
                result.put(entry.getKey(), selected);
            }
            return result;
        }
    }

    private void writeFilters(Map<String, EnumSet<SongLibrary.ExternalContent>> filters) {
        try {
            JsonObject json = readFilterJson();
            for (var entry : filters.entrySet()) {
                JsonArray values = new JsonArray();
                for (SongLibrary.ExternalContent content : SongLibrary.ExternalContent.values()) {
                    if (entry.getValue().contains(content)) {
                        values.add(content.name().toLowerCase(Locale.ROOT));
                    }
                }
                json.add(entry.getKey(), values);
            }
            writeFilterJson(json);
        } catch (IOException error) {
            FnfMod.LOGGER.warn("Could not save external_folder_filters.json: {}", error.toString());
        }
    }

    private JsonObject readFilterJson() {
        try {
            if (Files.isRegularFile(filtersFile())) {
                return JsonParser.parseString(Files.readString(filtersFile())).getAsJsonObject();
            }
        } catch (Exception error) {
            FnfMod.LOGGER.warn("Could not read external_folder_filters.json: {}", error.toString());
        }
        return new JsonObject();
    }

    private void writeFilterJson(JsonObject json) throws IOException {
        Files.createDirectories(root);
        Files.writeString(filtersFile(), new GsonBuilder().setPrettyPrinting().create().toJson(json));
    }
}
