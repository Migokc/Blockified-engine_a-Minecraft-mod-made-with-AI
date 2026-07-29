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
            Map<String, EnumSet<SongLibrary.ExternalContent>> filters = readFilters();
            if (filters.keySet().retainAll(folders)) writeFilters(filters);
        } catch (IOException error) {
            FnfMod.LOGGER.warn("Could not save external_folders.txt: {}", error.toString());
        }
    }

    /** A missing filter means every resource group, preserving old installations. */
    public EnumSet<SongLibrary.ExternalContent> content(String folder) {
        EnumSet<SongLibrary.ExternalContent> saved = readFilters().get(folder);
        return saved == null ? SongLibrary.allExternalContent() : EnumSet.copyOf(saved);
    }

    public void setContent(String folder, SongLibrary.ExternalContent content, boolean enabled) {
        Map<String, EnumSet<SongLibrary.ExternalContent>> filters = readFilters();
        EnumSet<SongLibrary.ExternalContent> selected = filters.containsKey(folder)
                ? EnumSet.copyOf(filters.get(folder)) : SongLibrary.allExternalContent();
        if (enabled) selected.add(content); else selected.remove(content);
        filters.put(folder, selected);
        writeFilters(filters);
    }

    private Map<String, EnumSet<SongLibrary.ExternalContent>> readFilters() {
        Map<String, EnumSet<SongLibrary.ExternalContent>> result = new LinkedHashMap<>();
        try {
            if (!Files.isRegularFile(filtersFile())) return result;
            JsonObject json = JsonParser.parseString(Files.readString(filtersFile())).getAsJsonObject();
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
        } catch (Exception error) {
            FnfMod.LOGGER.warn("Could not read external_folder_filters.json: {}", error.toString());
        }
        return result;
    }

    private void writeFilters(Map<String, EnumSet<SongLibrary.ExternalContent>> filters) {
        try {
            Files.createDirectories(root);
            JsonObject json = new JsonObject();
            for (var entry : filters.entrySet()) {
                JsonArray values = new JsonArray();
                for (SongLibrary.ExternalContent content : SongLibrary.ExternalContent.values()) {
                    if (entry.getValue().contains(content)) {
                        values.add(content.name().toLowerCase(Locale.ROOT));
                    }
                }
                json.add(entry.getKey(), values);
            }
            Files.writeString(filtersFile(), new GsonBuilder().setPrettyPrinting().create().toJson(json));
        } catch (IOException error) {
            FnfMod.LOGGER.warn("Could not save external_folder_filters.json: {}", error.toString());
        }
    }
}
