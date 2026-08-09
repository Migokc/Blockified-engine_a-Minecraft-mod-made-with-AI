package com.fnfmod.client.gui.editor;

import com.fnfmod.FnfMod;
import com.fnfmod.song.SongLibrary;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Persistent song-name to chart-editor output-folder mappings. */
final class ChartSaveFolderStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Map<String, String> folders;

    private ChartSaveFolderStore() {}

    static synchronized Optional<Path> get(String songName) {
        String key = matchingKey(songName);
        if (key == null) return Optional.empty();
        String raw = map().get(key);
        if (raw == null || raw.isBlank()) return Optional.empty();
        try {
            return Optional.of(Path.of(raw).toAbsolutePath().normalize());
        } catch (Exception error) {
            FnfMod.LOGGER.warn("Invalid chart save folder for {}: {}", songName, raw);
            return Optional.empty();
        }
    }

    static synchronized boolean set(String songName, Path folder) {
        String name = cleanName(songName);
        if (name.isEmpty() || folder == null) return false;
        String oldKey = matchingKey(name);
        String oldValue = oldKey == null ? null : map().remove(oldKey);
        map().put(name, folder.toAbsolutePath().normalize().toString());
        if (save()) return true;
        map().remove(name);
        if (oldKey != null) map().put(oldKey, oldValue);
        return false;
    }

    static synchronized boolean clear(String songName) {
        String key = matchingKey(songName);
        if (key == null) return true;
        String oldValue = map().remove(key);
        if (save()) return true;
        map().put(key, oldValue);
        return false;
    }

    private static Path file() {
        return SongLibrary.root().resolve("chart_editor_save_folders.json");
    }

    private static Map<String, String> map() {
        if (folders != null) return folders;
        folders = new LinkedHashMap<>();
        Path file = file();
        if (!Files.isRegularFile(file)) return folders;
        try {
            Map<String, String> loaded = GSON.fromJson(Files.readString(file),
                    new TypeToken<LinkedHashMap<String, String>>() {}.getType());
            if (loaded != null) folders.putAll(loaded);
        } catch (Exception error) {
            FnfMod.LOGGER.warn("Could not read {}: {}", file.getFileName(), error.toString());
        }
        return folders;
    }

    private static String matchingKey(String songName) {
        String wanted = cleanName(songName);
        if (wanted.isEmpty()) return null;
        for (String key : map().keySet()) {
            if (key.equalsIgnoreCase(wanted)) return key;
        }
        return null;
    }

    private static String cleanName(String songName) {
        return songName == null ? "" : songName.trim();
    }

    private static boolean save() {
        Path file = file();
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(temporary, GSON.toJson(map()));
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception error) {
            FnfMod.LOGGER.warn("Could not save {}: {}", file.getFileName(), error.toString());
            try { Files.deleteIfExists(temporary); } catch (Exception ignored) {}
            return false;
        }
    }
}
