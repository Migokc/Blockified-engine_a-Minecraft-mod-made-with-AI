package com.fnfmod.client.gameplay;

import com.fnfmod.chart.ChartEventTypes;
import com.fnfmod.chart.SongChart;
import com.fnfmod.client.render.SpriteAtlasCache;
import com.fnfmod.client.render.SpriteImageCache;
import com.fnfmod.gameplay.PlaybackPolicy;
import com.fnfmod.song.SongEntry;
import com.fnfmod.song.SongLibrary;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Discovers and preloads first-use gameplay graphics before the server starts the song. */
public final class GameplayAssetPreloader {
    private record Atlas(Path png, Path xml, boolean antialiasing) {}
    private record Image(Path png, boolean antialiasing) {}

    private static final String LUA_LITERAL = "(\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*')";
    private static final Pattern LUA_IMAGE_SECOND = Pattern.compile(
            "(?:makeLuaSprite|makeAnimatedLuaSprite|loadGraphic|loadFrames)\\s*\\(\\s*"
                    + LUA_LITERAL + "\\s*,\\s*" + LUA_LITERAL);
    private static final Pattern LUA_IMAGE_FIRST = Pattern.compile(
            "precacheImage\\s*\\(\\s*" + LUA_LITERAL);
    private static final Pattern LUA_ANY_LITERAL = Pattern.compile(LUA_LITERAL);

    private final LinkedHashSet<Atlas> atlases = new LinkedHashSet<>();
    private final LinkedHashSet<Image> images = new LinkedHashSet<>();

    private GameplayAssetPreloader() {}

    /** Background-safe discovery, JSON/XML parsing, and PNG decoding. */
    public static GameplayAssetPreloader prepare(SongChart chart, String songId, Path songFolder,
                                                  SongEntry entry, PlaybackPolicy policy) {
        GameplayAssetPreloader plan = new GameplayAssetPreloader();
        if (chart == null || policy == null) return plan;
        PsychAssetResolver assets = new PsychAssetResolver(songFolder, entry, policy, chart.stage);

        if (policy.usesPsychCamera()) {
            LinkedHashSet<String> characters = new LinkedHashSet<>();
            add(characters, chart.player1);
            add(characters, chart.player2);
            add(characters, chart.player3);
            for (SongChart.Event event : chart.events) {
                if (ChartEventTypes.is(event.name, ChartEventTypes.CHANGE_CHARACTER)
                        || ChartEventTypes.is(event.name, ChartEventTypes.ADD_CHARACTER)) {
                    add(characters, event.value2);
                }
            }
            for (String character : characters) plan.addCharacter(assets, character);
            plan.addStage(assets, chart.stage);
        }

        for (Path script : scriptFiles(chart, songId, songFolder, entry, policy)) {
            try {
                String source = Files.readString(script);
                for (String image : literalImages(source)) plan.addImage(assets, image, true);
            } catch (Exception ignored) {}
        }

        for (Atlas atlas : plan.atlases) {
            SpriteAtlasCache.prefetch(atlas.png(), atlas.xml(), atlas.antialiasing());
        }
        for (Image image : plan.images) {
            SpriteImageCache.prefetch(image.png(), image.antialiasing());
        }
        return plan;
    }

    /** Render-thread GPU upload, completed while the waiting screen is still visible. */
    public void warm() {
        for (Atlas atlas : atlases) {
            SpriteAtlasCache.warm(atlas.png(), atlas.xml(), atlas.antialiasing());
        }
        for (Image image : images) {
            SpriteImageCache.warm(image.png(), image.antialiasing());
        }
    }

    private void addCharacter(PsychAssetResolver assets, String character) {
        Path json = assets.character(character);
        if (json == null) return;
        try {
            JsonObject data = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
            String image = string(data, "image");
            if (image.isBlank()) return;
            boolean antialiasing = data.has("no_antialiasing")
                    ? !bool(data, "no_antialiasing", false)
                    : data.has("noAntialiasing")
                    ? !bool(data, "noAntialiasing", false)
                    : bool(data, "antialiasing", true);
            addImage(assets, image, antialiasing);
        } catch (Exception ignored) {}
    }

    private void addStage(PsychAssetResolver assets, String stageId) {
        Path stage = assets.stage(stageId);
        if (stage == null) return;
        try {
            JsonObject data = JsonParser.parseString(Files.readString(stage)).getAsJsonObject();
            if (!data.has("objects") || !data.get("objects").isJsonArray()) return;
            for (JsonElement raw : data.getAsJsonArray("objects")) {
                if (!raw.isJsonObject()) continue;
                JsonObject object = raw.getAsJsonObject();
                if (!string(object, "type").isBlank()
                        && !string(object, "type").equalsIgnoreCase("sprite")) continue;
                addImage(assets, string(object, "image"), bool(object, "antialiasing", true));
            }
        } catch (Exception ignored) {}
    }

    private void addImage(PsychAssetResolver assets, String name, boolean antialiasing) {
        Path png = assets.image(name);
        if (png == null) return;
        String filename = png.getFileName().toString();
        int dot = filename.lastIndexOf('.');
        Path xml = png.resolveSibling((dot < 0 ? filename : filename.substring(0, dot)) + ".xml");
        if (Files.isRegularFile(xml)) atlases.add(new Atlas(png, xml, antialiasing));
        else images.add(new Image(png, antialiasing));
    }

    private static Set<String> literalImages(String source) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Matcher second = LUA_IMAGE_SECOND.matcher(source == null ? "" : source);
        while (second.find()) add(result, unquote(second.group(2)));
        Matcher first = LUA_IMAGE_FIRST.matcher(source == null ? "" : source);
        while (first.find()) add(result, unquote(first.group(1)));
        // Also resolve standalone string constants. This catches the common Lua
        // pattern `local art = 'path'; makeLuaSprite(tag, art, ...)` without
        // executing the script on a worker thread. Non-image strings are harmless:
        // PsychAssetResolver rejects them because no matching PNG exists.
        Matcher any = LUA_ANY_LITERAL.matcher(source == null ? "" : source);
        while (any.find()) add(result, unquote(any.group(1)));
        return result;
    }

    private static List<Path> scriptFiles(SongChart chart, String songId, Path songFolder,
                                          SongEntry entry, PlaybackPolicy policy) {
        LinkedHashSet<Path> files = new LinkedHashSet<>();
        if (!policy.allows(entry, SongLibrary.ExternalContent.LUA)) return List.of();
        addLuaFiles(SongLibrary.scriptsDir(), files);
        Path modRoot = entry == null ? null : entry.modRoot;
        boolean stageFound = false;
        if (modRoot != null) {
            addLuaFiles(modRoot.resolve("scripts"), files);
            stageFound = addStageLua(modRoot, chart.stage, files);
            for (String type : chart.notes.stream().map(note -> note.noteType)
                    .filter(value -> value != null && !value.isBlank()).distinct().toList()) {
                addFile(modRoot.resolve("custom_notetypes").resolve(type + ".lua"), files);
            }
            for (String type : chart.events.stream().map(event -> event.name)
                    .filter(value -> value != null && !value.isBlank()).distinct().toList()) {
                addFile(modRoot.resolve("custom_events").resolve(type + ".lua"), files);
            }
            addLuaFiles(modRoot.resolve("data").resolve(songId), files);
            addLuaFiles(modRoot.resolve("songs").resolve(songId), files);
        }
        if (!stageFound) addStageLua(SongLibrary.primaryExternalAssetRoot(
                SongLibrary.ExternalContent.LUA), chart.stage, files);
        if (entry != null) addLuaFiles(entry.folder, files);
        addLuaFiles(songFolder, files);
        return List.copyOf(files);
    }

    private static boolean addStageLua(Path root, String stage, Set<Path> files) {
        if (root == null || stage == null || stage.isBlank()) return false;
        for (String folder : new String[]{"stages", "scripts/stages", "data/stages", "assets/stages"}) {
            Path file = root.resolve(folder).resolve(stage.trim() + ".lua");
            if (Files.isRegularFile(file)) {
                addFile(file, files);
                return true;
            }
        }
        return false;
    }

    private static void addLuaFiles(Path directory, Set<Path> files) {
        if (directory == null || !Files.isDirectory(directory)) return;
        try (Stream<Path> stream = Files.list(directory)) {
            stream.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".lua"))
                    .sorted().forEach(file -> addFile(file, files));
        } catch (Exception ignored) {}
    }

    private static void addFile(Path file, Set<Path> files) {
        if (file != null && Files.isRegularFile(file)) files.add(file.toAbsolutePath().normalize());
    }

    private static String unquote(String quoted) {
        if (quoted == null || quoted.length() < 2) return "";
        return quoted.substring(1, quoted.length() - 1)
                .replace("\\\\", "\\").replace("\\\"", "\"").replace("\\'", "'");
    }

    private static void add(Set<String> values, String value) {
        if (value != null && !value.isBlank()) values.add(value.trim());
    }

    private static String string(JsonObject object, String key) {
        try { return object.has(key) ? object.get(key).getAsString().trim() : ""; }
        catch (Exception ignored) { return ""; }
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        try { return object.has(key) ? object.get(key).getAsBoolean() : fallback; }
        catch (Exception ignored) { return fallback; }
    }
}
