package com.fnfmod.client.anim;

import com.fnfmod.FnfMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read/write model for Blockified's BBS character editor. */
public final class CharacterDefinitionFile {
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();

    public static final class Action {
        public String state = "";
        public float cameraX;
        public float cameraY;

        Action copy() {
            Action copy = new Action();
            copy.state = state;
            copy.cameraX = cameraX;
            copy.cameraY = cameraY;
            return copy;
        }
    }

    private final Path file;
    private final JsonObject source;
    private final Set<String> removedAnimationNames = new LinkedHashSet<>();
    public String form = "";
    public String icon = "";
    /** Stem name used by Voices-&lt;value&gt;.ogg; Psych-family alias: vocals_file. */
    public String vocalsFile = "";
    /** Psych-compatible healthbar_colors RGB, packed 0xRRGGBB. */
    public int healthColor = -1;
    public float rotation;
    public float cameraX;
    public float cameraY;
    /** Use the performer's live Minecraft skin on compatible built-in BBS player forms. */
    public boolean usePlayerSkin;
    /** Whether players may override the authored BBS skin/model in Settings. */
    public boolean allowPlayerSkinSelection = true;
    public boolean loopIdle;
    public final Map<String, Action> actions = new LinkedHashMap<>();

    private CharacterDefinitionFile(Path file, JsonObject source) {
        this.file = file;
        this.source = source;
        for (String action : CharacterAnimations.ACTIONS) actions.put(action, new Action());
        read();
    }

    public static CharacterDefinitionFile load(Path directory, boolean opponent) {
        return load(directory.resolve(opponent ? "character-opp.json" : "character.json"));
    }

    public static CharacterDefinitionFile load(Path file) {
        JsonObject source = new JsonObject();
        if (Files.isRegularFile(file)) {
            try {
                source = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            } catch (Exception error) {
                FnfMod.LOGGER.warn("Could not read character editor file {}: {}", file, error.toString());
            }
        }
        return new CharacterDefinitionFile(file, source);
    }

    public Path file() {
        return file;
    }

    public Action action(String name) {
        String existing = animationName(name);
        if (existing != null) return actions.get(existing);
        String clean = cleanAnimationName(name);
        return actions.computeIfAbsent(clean, ignored -> new Action());
    }

    public Action findAction(String name) {
        String existing = animationName(name);
        return existing == null ? null : actions.get(existing);
    }

    public List<String> animationNames() {
        return List.copyOf(actions.keySet());
    }

    public boolean isPreset(String name) {
        if (name == null) return false;
        for (String preset : CharacterAnimations.ACTIONS) {
            if (preset.equalsIgnoreCase(name.trim())) return true;
        }
        return false;
    }

    public String addAnimation(String requestedName) {
        String base = cleanAnimationName(requestedName);
        if (base.isBlank() || isPreset(base)) base = "custom-animation";
        String candidate = base;
        int suffix = 2;
        while (animationName(candidate) != null) candidate = base + "-" + suffix++;
        actions.put(candidate, new Action());
        return candidate;
    }

    public String renameAnimation(String oldName, String requestedName) {
        String existing = animationName(oldName);
        if (existing == null || isPreset(existing)) return existing;
        String renamed = cleanAnimationName(requestedName);
        if (renamed.isBlank() || isPreset(renamed)) return existing;
        String collision = animationName(renamed);
        if (collision != null && !collision.equals(existing)) return existing;
        if (renamed.equals(existing)) return existing;

        LinkedHashMap<String, Action> reordered = new LinkedHashMap<>();
        for (Map.Entry<String, Action> entry : actions.entrySet()) {
            reordered.put(entry.getKey().equals(existing) ? renamed : entry.getKey(), entry.getValue());
        }
        actions.clear();
        actions.putAll(reordered);
        removedAnimationNames.add(existing);
        return renamed;
    }

    public boolean removeAnimation(String name) {
        String existing = animationName(name);
        if (existing == null || isPreset(existing)) return false;
        actions.remove(existing);
        removedAnimationNames.add(existing);
        return true;
    }

    public void save() throws Exception {
        saveTo(file);
    }

    public void saveAs(Path directory, boolean opponent) throws Exception {
        saveTo(directory.resolve(opponent ? "character-opp.json" : "character.json"));
    }

    public void saveAs(Path outputFile) throws Exception {
        saveTo(outputFile);
    }

    private void saveTo(Path outputFile) throws Exception {
        putString("bbsForm", form);
        putString("icon", icon);
        putString("vocals_file", vocalsFile);
        if (healthColor >= 0) {
            JsonArray color = new JsonArray();
            color.add((healthColor >> 16) & 255);
            color.add((healthColor >> 8) & 255);
            color.add(healthColor & 255);
            source.add("healthbar_colors", color);
        } else source.remove("healthbar_colors");
        source.addProperty("rotation", rotation);
        source.addProperty("usePlayerSkin", usePlayerSkin);
        source.addProperty("allowPlayerSkinSelection", allowPlayerSkinSelection);
        source.addProperty("loopIdle", loopIdle);
        source.add("cameraOffset", vec2(cameraX, cameraY));

        JsonObject animationJson = source.has("animations") && source.get("animations").isJsonObject()
                ? source.getAsJsonObject("animations") : new JsonObject();
        for (String removed : removedAnimationNames) removeIgnoreCase(animationJson, removed);
        for (Map.Entry<String, Action> animation : actions.entrySet()) {
            String name = animation.getKey();
            Action action = animation.getValue();
            if (action.state == null || action.state.isBlank()) {
                removeIgnoreCase(animationJson, name);
                continue;
            }

            String originalName = jsonName(animationJson, name);
            JsonElement original = originalName == null ? null : animationJson.get(originalName);
            JsonObject entry = original != null && original.isJsonObject()
                    ? original.getAsJsonObject().deepCopy() : null;
            boolean hasExtraValues = entry != null && entry.entrySet().stream().anyMatch(value ->
                    !value.getKey().equals("state") && !value.getKey().equals("anim")
                            && !value.getKey().equals("cameraOffset"));
            if (originalName != null && !originalName.equals(name)) animationJson.remove(originalName);

            if (action.cameraX == 0 && action.cameraY == 0 && !hasExtraValues) {
                animationJson.addProperty(name, action.state.trim());
            } else {
                if (entry == null) entry = new JsonObject();
                entry.addProperty("state", action.state.trim());
                entry.remove("anim");
                if (action.cameraX == 0 && action.cameraY == 0) entry.remove("cameraOffset");
                else entry.add("cameraOffset", vec2(action.cameraX, action.cameraY));
                animationJson.add(name, entry);
            }
        }
        source.add("animations", animationJson);

        Files.createDirectories(outputFile.getParent());
        Path temporary = outputFile.resolveSibling(outputFile.getFileName() + ".tmp");
        Files.writeString(temporary, PRETTY.toJson(source) + System.lineSeparator());
        try {
            Files.move(temporary, outputFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception unsupportedAtomicMove) {
            Files.move(temporary, outputFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void read() {
        form = string(source, "bbsForm");
        if (form.isBlank()) form = string(source, "form");
        icon = string(source, "icon");
        vocalsFile = firstString(source, "vocals_file", "vocalsFile", "vocal_file", "vocalFile",
                "vocals_prefix", "vocalsPrefix", "vocal_prefix", "vocalPrefix");
        healthColor = color(source);
        rotation = number(source.get("rotation"));
        usePlayerSkin = bool(source, "usePlayerSkin");
        allowPlayerSkinSelection = firstBool(source, true,
                "allowPlayerSkinSelection", "allowPlayerSkinChange",
                "allowSkinSelection", "allowSkinOverride");
        loopIdle = bool(source, "loopIdle") || bool(source, "loopAnimation");
        float[] camera = vec2(source.get("cameraOffset"));
        cameraX = camera[0];
        cameraY = camera[1];

        JsonObject animationJson = source.has("animations") && source.get("animations").isJsonObject()
                ? source.getAsJsonObject("animations") : null;
        if (animationJson == null) return;
        for (Map.Entry<String, JsonElement> animation : animationJson.entrySet()) {
            String name = cleanAnimationName(animation.getKey());
            if (name.isBlank()) continue;
            JsonElement element = animation.getValue();
            Action action = action(name);
            try {
                if (element.isJsonPrimitive()) {
                    action.state = element.getAsString();
                } else if (element.isJsonObject()) {
                    JsonObject entry = element.getAsJsonObject();
                    action.state = string(entry, "state");
                    if (action.state.isBlank()) action.state = string(entry, "anim");
                    if (action.state.isBlank()) action.state = name;
                    float[] offset = vec2(entry.get("cameraOffset"));
                    action.cameraX = offset[0];
                    action.cameraY = offset[1];
                }
            } catch (Exception ignored) {}
        }
    }

    private void putString(String key, String value) {
        if (value == null || value.isBlank()) source.remove(key);
        else source.addProperty(key, value.trim());
    }

    private static String string(JsonObject object, String key) {
        try {
            return object.has(key) ? object.get(key).getAsString().trim() : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String firstString(JsonObject object, String... keys) {
        for (String key : keys) {
            String value = string(object, key);
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private static boolean bool(JsonObject object, String key) {
        try {
            if (!object.has(key)) return false;
            JsonElement value = object.get(key);
            if (value.getAsJsonPrimitive().isBoolean()) return value.getAsBoolean();
            String text = value.getAsString().trim().toLowerCase(java.util.Locale.ROOT);
            return text.equals("true") || text.equals("1") || text.equals("yes") || text.equals("on");
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean firstBool(JsonObject object, boolean fallback, String... keys) {
        for (String key : keys) {
            if (object.has(key)) return bool(object, key);
        }
        return fallback;
    }

    private static float number(JsonElement value) {
        try {
            return value == null ? 0 : value.getAsFloat();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static int color(JsonObject object) {
        for (String key : new String[]{"healthbar_colors", "health_bar_colors", "healthBarColors"}) {
            try {
                if (!object.has(key) || !object.get(key).isJsonArray()) continue;
                JsonArray array = object.getAsJsonArray(key);
                if (array.size() < 3) continue;
                int r = Math.max(0, Math.min(255, array.get(0).getAsInt()));
                int g = Math.max(0, Math.min(255, array.get(1).getAsInt()));
                int b = Math.max(0, Math.min(255, array.get(2).getAsInt()));
                return (r << 16) | (g << 8) | b;
            } catch (Exception ignored) {}
        }
        return -1;
    }

    private static float[] vec2(JsonElement value) {
        try {
            if (value != null && value.isJsonArray()) {
                JsonArray array = value.getAsJsonArray();
                return new float[]{number(array.get(0)), array.size() > 1 ? number(array.get(1)) : 0};
            }
        } catch (Exception ignored) {}
        return new float[]{0, 0};
    }

    private static JsonArray vec2(float x, float y) {
        JsonArray array = new JsonArray();
        array.add(x);
        array.add(y);
        return array;
    }

    private String animationName(String requested) {
        if (requested == null) return null;
        String clean = requested.trim();
        for (String name : actions.keySet()) {
            if (name.equalsIgnoreCase(clean)) return name;
        }
        return null;
    }

    private static String cleanAnimationName(String name) {
        return name == null ? "" : name.trim();
    }

    private static String jsonName(JsonObject object, String requested) {
        for (String name : object.keySet()) {
            if (name.equalsIgnoreCase(requested)) return name;
        }
        return null;
    }

    private static void removeIgnoreCase(JsonObject object, String requested) {
        String name = jsonName(object, requested);
        if (name != null) object.remove(name);
    }
}
