package com.fnfmod.song;

import com.fnfmod.FnfMod;
import com.fnfmod.net.FnfPayloads;
import com.fnfmod.world.ModContentScope;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** Psych-compatible {@code weeks/*.json} discovery used by Story Mode and menu Lua. */
public final class WeekLibrary {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static int generation = Integer.MIN_VALUE;
    private static List<WeekDefinition> weeks = List.of();

    private WeekLibrary() {}

    public static synchronized List<WeekDefinition> all() {
        if (generation != SongLibrary.rescanGeneration()) rescan();
        return weeks;
    }

    public static synchronized void rescan() {
        generation = SongLibrary.rescanGeneration();
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        ModContentScope.activeMod().map(ModContentScope.ActiveMod::root).ifPresent(roots::add);
        for (SongEntry entry : SongLibrary.getSongs().values()) {
            if (entry.modRoot != null) roots.add(entry.modRoot);
            if (entry.chartOriginRoot != null) roots.add(entry.chartOriginRoot);
        }
        Path global = SongLibrary.globalSharedAssetRoot();
        if (global != null) roots.add(global);

        LinkedHashMap<String, WeekDefinition> found = new LinkedHashMap<>();
        for (Path root : roots) scanRoot(root, found);
        weeks = List.copyOf(found.values());
    }

    /** Keeps raw week metadata available while restricting Story Mode to server-listed songs. */
    public static List<WeekDefinition> storyWeeks(Collection<FnfPayloads.SongInfo> availableSongs) {
        Set<String> available = new java.util.HashSet<>();
        if (availableSongs != null) for (FnfPayloads.SongInfo song : availableSongs) {
            available.add(normalize(song.id()));
            available.add(normalize(song.name()));
        }
        List<WeekDefinition> result = new ArrayList<>();
        for (WeekDefinition week : all()) {
            if (week.hideStoryMode()) continue;
            // A Story week is an ordered playlist, not a bag of whichever songs
            // happen to be available. Hide incomplete weeks so playback always
            // starts at entry 1 and can finish at the declared last entry.
            boolean playable = !week.songs().isEmpty() && week.songs().stream()
                    .allMatch(song -> available.contains(normalize(song.id())));
            if (playable) result.add(week);
        }
        return result;
    }

    public static WeekDefinition find(String id) {
        String wanted = normalize(id);
        return all().stream().filter(week -> normalize(week.id()).equals(wanted)).findFirst().orElse(null);
    }

    private static void scanRoot(Path rawRoot, Map<String, WeekDefinition> found) {
        if (rawRoot == null) return;
        Path root = rawRoot.toAbsolutePath().normalize();
        Path directory = root.resolve("weeks");
        if (!Files.isDirectory(directory)) return;
        Map<String, Path> jsons = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(Files::isRegularFile).filter(path ->
                    path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .sorted().forEach(path -> jsons.put(stem(path), path));
        } catch (Exception ignored) {}
        List<String> order = readOrder(directory);
        for (String id : order) {
            Path json = removeIgnoreCase(jsons, id);
            if (json != null) add(root, json, found);
        }
        for (Path json : jsons.values()) add(root, json, found);
    }

    private static List<String> readOrder(Path directory) {
        for (String name : List.of("weekList.txt", "weeklist.txt")) {
            Path file = directory.resolve(name);
            if (!Files.isRegularFile(file)) continue;
            try {
                return Files.readAllLines(file).stream().map(String::trim)
                        .filter(line -> !line.isBlank() && !line.startsWith("#")).toList();
            } catch (Exception ignored) {}
        }
        return List.of();
    }

    private static Path removeIgnoreCase(Map<String, Path> values, String id) {
        String wanted = normalize(id);
        String key = values.keySet().stream().filter(value -> normalize(value).equals(wanted))
                .findFirst().orElse(null);
        return key == null ? null : values.remove(key);
    }

    private static void add(Path root, Path json, Map<String, WeekDefinition> found) {
        WeekDefinition week = read(root, json);
        if (week != null) found.putIfAbsent(normalize(week.id()), week);
    }

    public static WeekDefinition read(Path root, Path json) {
        try {
            JsonObject object = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
            String id = stem(json);
            List<WeekDefinition.Song> songs = new ArrayList<>();
            JsonArray array = array(object, "songs");
            if (array != null) for (JsonElement element : array) {
                if (!element.isJsonArray()) continue;
                JsonArray song = element.getAsJsonArray();
                if (song.isEmpty()) continue;
                String songId = string(song.get(0), "");
                String icon = song.size() > 1 ? string(song.get(1), "dad") : "dad";
                int color = song.size() > 2 ? color(song.get(2), 0x9271FD) : 0x9271FD;
                if (!songId.isBlank()) songs.add(new WeekDefinition.Song(songId, icon, color));
            }
            if (songs.isEmpty()) return null;
            List<String> difficulties = split(string(object, "difficulties", "normal"));
            if (difficulties.isEmpty()) difficulties = List.of("normal");
            List<String> characters = strings(array(object, "weekCharacters"));
            while (characters.size() < 3) characters.add("");
            String storyName = string(object, "storyName", string(object, "weekName", id));
            String weekName = string(object, "weekName", storyName);
            String background = string(object, "weekBackground", "stage");
            String weekBefore = string(object, "weekBefore", "");
            Path image = resolveImage(root, id, weekName, background);
            return new WeekDefinition(id, storyName, weekName, List.copyOf(songs),
                    List.copyOf(difficulties), List.copyOf(characters), background, weekBefore,
                    bool(object, "startUnlocked", true), bool(object, "hiddenUntilUnlocked", false),
                    bool(object, "hideStoryMode", false), bool(object, "hideFreeplay", false),
                    root, json, image);
        } catch (Exception error) {
            FnfMod.LOGGER.warn("Could not read Psych week {}: {}", json, error.toString());
            return null;
        }
    }

    public static boolean write(Path file, WeekDefinition week) {
        try {
            JsonObject object = new JsonObject();
            object.addProperty("storyName", week.storyName());
            object.addProperty("weekName", week.weekName());
            object.addProperty("difficulties", String.join(", ", week.difficulties()));
            object.addProperty("weekBackground", week.background());
            object.addProperty("weekBefore", week.weekBefore());
            object.addProperty("startUnlocked", week.startUnlocked());
            object.addProperty("hiddenUntilUnlocked", week.hiddenUntilUnlocked());
            object.addProperty("hideStoryMode", week.hideStoryMode());
            object.addProperty("hideFreeplay", week.hideFreeplay());
            JsonArray characters = new JsonArray();
            for (String character : week.characters()) characters.add(character);
            object.add("weekCharacters", characters);
            JsonArray songs = new JsonArray();
            for (WeekDefinition.Song value : week.songs()) {
                JsonArray song = new JsonArray();
                song.add(value.id());
                song.add(value.icon());
                JsonArray rgb = new JsonArray();
                rgb.add(value.color() >> 16 & 255);
                rgb.add(value.color() >> 8 & 255);
                rgb.add(value.color() & 255);
                song.add(rgb);
                songs.add(song);
            }
            object.add("songs", songs);
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(object));
            return true;
        } catch (Exception error) {
            FnfMod.LOGGER.warn("Could not save week {}: {}", file, error.toString());
            return false;
        }
    }

    private static Path resolveImage(Path root, String id, String weekName, String background) {
        Path story = root.resolve("images").resolve("storymenu");
        for (String name : List.of(id, weekName, "week-" + id)) {
            Path file = findPng(story, name);
            if (file != null) return file;
        }
        return findPng(root.resolve("images"), background);
    }

    private static Path findPng(Path directory, String stem) {
        if (!Files.isDirectory(directory) || stem == null || stem.isBlank()) return null;
        String wanted = normalize(stem);
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png"))
                    .filter(path -> normalize(stem(path)).equals(wanted))
                    .findFirst().orElse(null);
        } catch (Exception ignored) { return null; }
    }

    private static String stem(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 1 ? name : name.substring(0, dot);
    }
    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
    }
    private static List<String> split(String raw) {
        if (raw == null) return List.of();
        return Stream.of(raw.split(",")).map(String::trim).filter(value -> !value.isBlank()).toList();
    }
    private static List<String> strings(JsonArray array) {
        List<String> result = new ArrayList<>();
        if (array != null) for (JsonElement value : array) result.add(string(value, ""));
        return result;
    }
    private static JsonArray array(JsonObject object, String key) {
        try { return object.has(key) && object.get(key).isJsonArray() ? object.getAsJsonArray(key) : null; }
        catch (Exception ignored) { return null; }
    }
    private static String string(JsonObject object, String key, String fallback) {
        try { return object.has(key) ? object.get(key).getAsString() : fallback; }
        catch (Exception ignored) { return fallback; }
    }
    private static String string(JsonElement value, String fallback) {
        try { return value == null ? fallback : value.getAsString(); }
        catch (Exception ignored) { return fallback; }
    }
    private static boolean bool(JsonObject object, String key, boolean fallback) {
        try { return object.has(key) ? object.get(key).getAsBoolean() : fallback; }
        catch (Exception ignored) { return fallback; }
    }
    private static int color(JsonElement value, int fallback) {
        try {
            JsonArray rgb = value.getAsJsonArray();
            if (rgb.size() < 3) return fallback;
            return clamp(rgb.get(0).getAsInt()) << 16 | clamp(rgb.get(1).getAsInt()) << 8
                    | clamp(rgb.get(2).getAsInt());
        } catch (Exception ignored) { return fallback; }
    }
    private static int clamp(int value) { return Math.max(0, Math.min(255, value)); }
}
