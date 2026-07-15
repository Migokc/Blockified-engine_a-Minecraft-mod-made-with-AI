package com.fnfmod.client.anim;

import com.fnfmod.FnfMod;
import com.fnfmod.character.CharacterDefinitionPaths;
import com.fnfmod.song.SongLibrary;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kosmx.playerAnim.api.TransformType;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.api.layered.KeyframeAnimationPlayer;
import dev.kosmx.playerAnim.api.layered.ModifierLayer;
import dev.kosmx.playerAnim.api.layered.modifier.AbstractFadeModifier;
import dev.kosmx.playerAnim.core.util.Ease;
import dev.kosmx.playerAnim.core.util.Vec3f;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationAccess;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationFactory;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * playerAnimator integration with selectable animation sets.
 *
 * Layout of config/fnfmod/animations/:
 *  - loose .json animation files (+ optional mapping.json / character.json) = the "default" set
 *  - each subfolder = a named set the player can pick in the Funkin' Machine menu
 *
 * A set's character.json configures the player side. character-opp.json uses
 * the same schema and overrides it for the opponent side:
 * {
 *   "rotation": 0,                                  // added to the default body rotation in degrees
 *   "cameraOffset": [0.0, 0.0],                      // base camera center for this character (blocks: x=right, y=up)
 *   "animations": {
 *     "idle":  "my_idle",
 *     "left":  { "anim": "my_left", "cameraOffset": [-1.0, 0.0] },
 *     "down":  { "anim": "my_down", "cameraOffset": [0.0, -1.0] },
 *     "up":    { "anim": "my_up",   "cameraOffset": [0.0,  1.0] },
 *     "right": { "anim": "my_right","cameraOffset": [1.0,  0.0] },
 *     "miss":  "my_miss"
 *   }
 * }
 *
 * Without character.json, animations named fnf_idle/fnf_left/... (or just idle/left/...)
 * are picked up automatically. The legacy mapping.json ("player"/"opponent" roles)
 * still works for the default set.
 */
public final class CharacterAnimations {

    public static final ResourceLocation LAYER_ID = FnfMod.id("gameplay");
    /** idle2 is optional: when present it alternates with idle every beat (FNF danceLeft/danceRight). */
    public static final String[] ACTIONS = {"idle", "idle2", "left", "down", "up", "right", "miss"};
    public static final String NONE_SET = CharacterDefinitionPaths.NONE;
    public static final String DEFAULT_SET = CharacterDefinitionPaths.DEFAULT;

    public static final class AnimEntry {
        public KeyframeAnimation anim;
        public float camX, camY;
    }

    private static final class AnimSet {
        final Map<String, AnimEntry> actions = new HashMap<>(); // "left" or "player.left"
        float baseCamX, baseCamY;
        boolean hasBaseCamera;
        String icon = "";
        float opponentBaseCamX, opponentBaseCamY;
        boolean hasOpponentBaseCamera;
        String opponentIcon = "";

        AnimEntry resolve(String role, String action) {
            AnimEntry e = actions.get(role + "." + action);
            if (e == null) e = actions.get(action);
            return e;
        }

        boolean configured() {
            return !actions.isEmpty() || hasBaseCamera || hasOpponentBaseCamera
                    || !icon.isBlank() || !opponentIcon.isBlank();
        }

        AnimSet copy() {
            AnimSet copy = new AnimSet();
            copy.actions.putAll(actions);
            copy.baseCamX = baseCamX;
            copy.baseCamY = baseCamY;
            copy.hasBaseCamera = hasBaseCamera;
            copy.icon = icon;
            copy.opponentBaseCamX = opponentBaseCamX;
            copy.opponentBaseCamY = opponentBaseCamY;
            copy.hasOpponentBaseCamera = hasOpponentBaseCamera;
            copy.opponentIcon = opponentIcon;
            return copy;
        }

        void overlay(AnimSet higherPriority) {
            if (higherPriority == null) return;
            actions.putAll(higherPriority.actions);
            if (higherPriority.hasBaseCamera) {
                baseCamX = higherPriority.baseCamX;
                baseCamY = higherPriority.baseCamY;
                hasBaseCamera = true;
            }
            if (!higherPriority.icon.isBlank()) icon = higherPriority.icon;
            if (higherPriority.hasOpponentBaseCamera) {
                opponentBaseCamX = higherPriority.opponentBaseCamX;
                opponentBaseCamY = higherPriority.opponentBaseCamY;
                hasOpponentBaseCamera = true;
            }
            if (!higherPriority.opponentIcon.isBlank()) opponentIcon = higherPriority.opponentIcon;
        }
    }

    private static final Map<String, AnimSet> GLOBAL_SETS = new LinkedHashMap<>();
    private static final Map<String, AnimSet> SONG_SETS = new LinkedHashMap<>();
    private static Path activeSongFolder;
    private static boolean available = false;

    private CharacterAnimations() {}

    public static void init() {
        try {
            PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(LAYER_ID, 1000,
                    player -> new ModifierLayer<>());
            available = true;
        } catch (Throwable t) {
            FnfMod.LOGGER.error("playerAnimator not available, character animations disabled", t);
        }
        reload();
    }

    public static synchronized void reload() {
        GLOBAL_SETS.clear();
        Path dir = SongLibrary.animationsDir();
        if (!Files.isDirectory(dir)) {
            GLOBAL_SETS.put(DEFAULT_SET, new AnimSet());
            reloadSongSets();
            return;
        }
        GLOBAL_SETS.put(DEFAULT_SET, loadSet(dir, true, true));
        try (Stream<Path> subdirs = Files.list(dir)) {
            subdirs.filter(Files::isDirectory).sorted().forEach(sub ->
                    GLOBAL_SETS.put(key(sub.getFileName().toString()), loadSet(sub, false, true)));
        } catch (Exception e) {
            FnfMod.LOGGER.warn("Failed to scan animation sets: {}", e.toString());
        }
        reloadSongSets();
        int total = GLOBAL_SETS.values().stream().mapToInt(s -> s.actions.size()).sum()
                + SONG_SETS.values().stream().mapToInt(s -> s.actions.size()).sum();
        if (total > 0) {
            FnfMod.LOGGER.info("Loaded {} global and {} song FNF animation set(s) ({} mapped actions)",
                    GLOBAL_SETS.size(), SONG_SETS.size(), total);
        }
    }

    /** Selects/reloads definitions belonging to the song currently being played. */
    public static synchronized void useSongFolder(Path songFolder) {
        activeSongFolder = songFolder == null ? null : songFolder.toAbsolutePath().normalize();
        reloadSongSets();
    }

    /** Names of all selectable sets (None, Default, then installed/song sets). */
    public static synchronized List<String> listSets() {
        List<String> names = new ArrayList<>();
        names.add(NONE_SET);
        names.add(DEFAULT_SET);
        for (String name : GLOBAL_SETS.keySet()) if (!DEFAULT_SET.equals(name)) names.add(name);
        for (String name : SONG_SETS.keySet()) if (!DEFAULT_SET.equals(name) && !names.contains(name)) names.add(name);
        return names;
    }

    public static synchronized boolean hasAction(String setName, String action) {
        AnimSet set = resolveSet(setName);
        return set != null && (set.resolve("player", action) != null || set.resolve("opponent", action) != null);
    }

    public static synchronized boolean isDisabled(String setName) {
        return NONE_SET.equals(key(setName));
    }

    /** Health-icon key declared by Blockified character.json, or empty. */
    public static synchronized String icon(String setName) {
        return icon(setName, "player");
    }

    /** Role-aware icon; character-opp.json overrides the opponent icon. */
    public static synchronized String icon(String setName, String role) {
        AnimSet set = resolveSet(setName);
        if (set == null) return "";
        return "opponent".equals(role) && !set.opponentIcon.isBlank()
                ? set.opponentIcon : set.icon;
    }

    private static void reloadSongSets() {
        SONG_SETS.clear();
        if (activeSongFolder == null || !Files.isDirectory(activeSongFolder)) return;

        AnimSet localDefault = new AnimSet();
        localDefault.overlay(loadSet(activeSongFolder, true, false));
        Path animationRoot = activeSongFolder.resolve("animations");
        if (Files.isDirectory(animationRoot)) {
            localDefault.overlay(loadSet(animationRoot, true, false));
        }
        if (localDefault.configured()) {
            AnimSet combined = GLOBAL_SETS.getOrDefault(DEFAULT_SET, new AnimSet()).copy();
            combined.overlay(localDefault);
            SONG_SETS.put(DEFAULT_SET, combined);
        }

        loadSongNamedSets(activeSongFolder.resolve("characters"));
        loadSongNamedSets(animationRoot); // animations/<name> has highest priority
    }

    private static void loadSongNamedSets(Path parent) {
        if (!Files.isDirectory(parent)) return;
        try (Stream<Path> subdirs = Files.list(parent)) {
            for (Path directory : subdirs.filter(Files::isDirectory).sorted().toList()) {
                String name = key(directory.getFileName().toString());
                AnimSet local = loadSet(directory, false, true);
                if (!local.configured()) continue;
                AnimSet combined = SONG_SETS.containsKey(name)
                        ? SONG_SETS.get(name).copy()
                        : GLOBAL_SETS.getOrDefault(name, new AnimSet()).copy();
                combined.overlay(local);
                SONG_SETS.put(name, combined);
            }
        } catch (Exception error) {
            FnfMod.LOGGER.warn("Failed to scan song animation sets in {}: {}", parent, error.toString());
        }
    }

    private static AnimSet resolveSet(String setName) {
        String selected = key(setName);
        if (NONE_SET.equals(selected)) return null;
        AnimSet song = SONG_SETS.get(selected);
        if (song != null) return song;
        AnimSet global = GLOBAL_SETS.get(selected);
        if (global != null) return global;
        return SONG_SETS.getOrDefault(DEFAULT_SET, GLOBAL_SETS.get(DEFAULT_SET));
    }

    private static String key(String name) {
        return CharacterDefinitionPaths.normalizeName(name);
    }

    // ------------------------------------------------------------------ loading

    private static AnimSet loadSet(Path dir, boolean isDefaultSet, boolean warnInvalidAnimations) {
        AnimSet set = new AnimSet();
        Map<String, KeyframeAnimation> anims = new HashMap<>();
        JsonObject characterJson = null;
        JsonObject opponentJson = null;
        JsonObject legacyMapping = null;

        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                String name = f.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!name.endsWith(".json")) continue;
                if (name.equals("character.json")) {
                    try {
                        characterJson = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
                    } catch (Exception e) {
                        FnfMod.LOGGER.warn("Bad {}: {}", f, e.toString());
                    }
                } else if (name.equals("character-opp.json")) {
                    try {
                        opponentJson = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
                    } catch (Exception e) {
                        FnfMod.LOGGER.warn("Bad {}: {}", f, e.toString());
                    }
                } else if (name.equals("mapping.json")) {
                    try {
                        legacyMapping = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
                    } catch (Exception e) {
                        FnfMod.LOGGER.warn("Bad {}: {}", f, e.toString());
                    }
                } else {
                    loadAnimFile(f, anims, warnInvalidAnimations);
                }
            }
        } catch (Exception e) {
            FnfMod.LOGGER.warn("Failed to read animation folder {}: {}", dir, e.toString());
        }

        applyCharacterDefinition(set, anims, characterJson, false);

        // legacy role-aware mapping / prefixes (default set keeps old behavior)
        if (isDefaultSet) {
            for (String role : new String[]{"player", "opponent"}) {
                JsonObject roleMap = legacyMapping != null && legacyMapping.has(role)
                        && legacyMapping.get(role).isJsonObject()
                        ? legacyMapping.getAsJsonObject(role) : null;
                for (String action : ACTIONS) {
                    String mapped = roleMap != null && roleMap.has(action)
                            ? roleMap.get(action).getAsString() : null;
                    KeyframeAnimation anim = pick(anims, mapped, role + "_fnf_" + action, null);
                    if (anim != null) {
                        AnimEntry entry = new AnimEntry();
                        entry.anim = anim;
                        AnimEntry plain = set.actions.get(action);
                        if (plain != null) {
                            entry.camX = plain.camX;
                            entry.camY = plain.camY;
                        }
                        set.actions.put(role + "." + action, entry);
                    }
                }
            }
        }
        // Explicit opponent data has priority over legacy opponent mappings.
        applyCharacterDefinition(set, anims, opponentJson, true);
        return set;
    }

    private static void applyCharacterDefinition(AnimSet set,
                                                 Map<String, KeyframeAnimation> anims,
                                                 JsonObject json, boolean opponent) {
        if (json == null && opponent) return;
        if (json != null && json.has("cameraOffset")) {
            float[] off = readVec2(json.get("cameraOffset"));
            if (opponent) {
                set.opponentBaseCamX = off[0];
                set.opponentBaseCamY = off[1];
                set.hasOpponentBaseCamera = true;
            } else {
                set.baseCamX = off[0];
                set.baseCamY = off[1];
                set.hasBaseCamera = true;
            }
        }
        if (json != null && json.has("icon")) {
            try {
                String icon = json.get("icon").getAsString().trim();
                if (opponent) set.opponentIcon = icon;
                else set.icon = icon;
            } catch (Exception ignored) {}
        }

        JsonObject animMap = json != null && json.has("animations")
                && json.get("animations").isJsonObject()
                ? json.getAsJsonObject("animations") : null;
        for (String action : ACTIONS) {
            AnimEntry entry = new AnimEntry();
            String animName = null;
            if (animMap != null && animMap.has(action)) {
                JsonElement el = animMap.get(action);
                if (el.isJsonPrimitive()) {
                    animName = el.getAsString();
                } else if (el.isJsonObject()) {
                    JsonObject object = el.getAsJsonObject();
                    if (object.has("anim")) animName = object.get("anim").getAsString();
                    if (object.has("cameraOffset")) {
                        float[] off = readVec2(object.get("cameraOffset"));
                        entry.camX = off[0];
                        entry.camY = off[1];
                    }
                }
            }
            entry.anim = opponent
                    ? pick(anims, animName, "opponent_fnf_" + action,
                    "opp_fnf_" + action, "opponent_" + action, "opp_" + action)
                    : pick(anims, animName, "fnf_" + action, action,
                    action.equals("idle") ? "danceleft" : null,
                    action.equals("idle2") ? "danceright" : null);
            if (entry.anim != null) {
                set.actions.put((opponent ? "opponent." : "") + action, entry);
            }
        }
    }

    private static KeyframeAnimation pick(Map<String, KeyframeAnimation> anims, String... candidates) {
        for (String c : candidates) {
            if (c == null) continue;
            KeyframeAnimation a = anims.get(c.toLowerCase(Locale.ROOT));
            if (a != null) return a;
        }
        return null;
    }

    private static float[] readVec2(JsonElement el) {
        try {
            if (el.isJsonArray()) {
                JsonArray a = el.getAsJsonArray();
                return new float[]{a.get(0).getAsFloat(), a.size() > 1 ? a.get(1).getAsFloat() : 0};
            }
        } catch (Exception ignored) {}
        return new float[]{0, 0};
    }

    private static void loadAnimFile(Path file, Map<String, KeyframeAnimation> out,
                                     boolean warnInvalid) {
        String base = file.getFileName().toString();
        base = base.substring(0, base.length() - 5).toLowerCase(Locale.ROOT);
        try (InputStream in = Files.newInputStream(file)) {
            List<KeyframeAnimation> anims =
                    dev.kosmx.playerAnim.core.data.gson.AnimationSerializing.deserializeAnimation(in);
            int i = 0;
            for (KeyframeAnimation anim : anims) {
                String name = base;
                Object extraName = anim.extraData.get("name");
                if (extraName instanceof String s && !s.isBlank()) {
                    name = cleanName(s);
                } else if (i > 0) {
                    name = base + "_" + i;
                }
                out.put(name.toLowerCase(Locale.ROOT), anim);
                // filename always works as a key too
                out.putIfAbsent(base, anim);
                i++;
            }
        } catch (Throwable t) {
            if (warnInvalid) FnfMod.LOGGER.warn("Failed to load animation {}: {}", file, t.toString());
        }
    }

    private static String cleanName(String raw) {
        String s = raw.trim();
        if (s.startsWith("{")) {
            try {
                JsonObject o = JsonParser.parseString(s).getAsJsonObject();
                if (o.has("text")) return o.get("text").getAsString();
            } catch (Exception ignored) {}
        }
        return s.replace("\"", "");
    }

    // ------------------------------------------------------------------ playback

    /** Base camera offset (x=right, y=up, in blocks) of a set. */
    public static synchronized float[] baseCameraOffset(String setName) {
        return baseCameraOffset(setName, "player");
    }

    /** Role-aware base camera offset; character-opp.json overrides the opponent side. */
    public static synchronized float[] baseCameraOffset(String setName, String role) {
        AnimSet set = resolveSet(setName);
        if (set == null) return new float[]{0, 0};
        if ("opponent".equals(role) && set.hasOpponentBaseCamera) {
            return new float[]{set.opponentBaseCamX, set.opponentBaseCamY};
        }
        return new float[]{set.baseCamX, set.baseCamY};
    }

    /**
     * Plays an animation on the player entity. Opponents use character-opp.json
     * mappings when present and otherwise fall back to character.json unchanged.
     * Returns the animation's camera offset [x, y], or null when unavailable.
     */
    public static synchronized float[] play(Player player, String setName, String role, String action) {
        if (!available || !(player instanceof AbstractClientPlayer clientPlayer)) return null;
        try {
            AnimSet set = resolveSet(setName);
            if (set == null) return null;
            AnimEntry entry = set.resolve(role, action);
            if (entry == null && !DEFAULT_SET.equals(key(setName))) {
                AnimSet def = resolveSet(DEFAULT_SET);
                if (def != null) {
                    entry = def.resolve(role, action);
                }
            }
            if (entry == null || entry.anim == null) return null;
            var data = PlayerAnimationAccess.getPlayerAssociatedData(clientPlayer);
            @SuppressWarnings("unchecked")
            ModifierLayer<IAnimation> layer = (ModifierLayer<IAnimation>) data.get(LAYER_ID);
            if (layer == null) return null;

            boolean isIdle = action.startsWith("idle");
            // a looping idle that's already playing keeps looping — restarting it
            // would blend through the vanilla rest pose every beat
            if (isIdle && unwrapPlayer(layer.getAnimation()) instanceof KeyframeAnimationPlayer current
                    && current.getData() == entry.anim && current.isActive() && entry.anim.isInfinite) {
                return new float[]{entry.camX, entry.camY};
            }
            // A non-looping idle that finishes before the next beat (low BPM) would ease
            // back to the vanilla rest pose and "just end"; hold its last frame instead.
            IAnimation base = isIdle && !entry.anim.isInfinite
                    ? new HoldLastFrame(entry.anim)
                    : new KeyframeAnimationPlayer(entry.anim);
            // short fade from the current pose instead of snapping through the rest pose
            layer.replaceAnimationWithFade(
                    AbstractFadeModifier.standardFadeIn(isIdle ? 4 : 2, Ease.INOUTSINE), base);
            return new float[]{entry.camX, entry.camY};
        } catch (Throwable t) {
            return null; // never let animation problems break gameplay
        }
    }

    private static IAnimation unwrapPlayer(IAnimation anim) {
        return anim instanceof ModifierLayer<?> wrapped ? wrapped.getAnimation() : anim;
    }

    /**
     * Plays a one-shot idle but, once it reaches its last content frame, freezes
     * there instead of easing back to the vanilla rest pose. At a low BPM the idle
     * bop finishes before the next beat re-triggers it; without this the character
     * would snap to a standing pose in the gap. The next beat replaces it normally
     * (and {@link #stop} clears it), so at higher BPM it never actually freezes.
     */
    private static final class HoldLastFrame implements IAnimation {
        private final KeyframeAnimationPlayer player;
        private final int holdTick;
        private boolean holding;

        HoldLastFrame(KeyframeAnimation anim) {
            this.player = new KeyframeAnimationPlayer(anim);
            // endTick = end of the main body, before any ease-out to rest; fall back
            // to stopTick for animations that have no separate outro region.
            int hold = anim.endTick > 0 ? anim.endTick : anim.stopTick;
            this.holdTick = Math.max(1, hold);
        }

        @Override
        public void tick() {
            if (holding) return;
            if (player.getCurrentTick() >= holdTick) {
                holding = true; // reached the last frame — stop advancing and hold it
                return;
            }
            player.tick();
        }

        @Override
        public boolean isActive() {
            return true; // never auto-ends; the next beat (or stop()) replaces it
        }

        @Override
        public void setupAnim(float tickDelta) {
            player.setupAnim(holding ? 0f : tickDelta);
        }

        @Override
        public Vec3f get3DTransform(String modelName, TransformType type, float tickDelta, Vec3f value0) {
            return player.get3DTransform(modelName, type, holding ? 0f : tickDelta, value0);
        }
    }

    public static void stop(Player player) {
        if (!available || !(player instanceof AbstractClientPlayer clientPlayer)) return;
        try {
            var data = PlayerAnimationAccess.getPlayerAssociatedData(clientPlayer);
            @SuppressWarnings("unchecked")
            ModifierLayer<IAnimation> layer = (ModifierLayer<IAnimation>) data.get(LAYER_ID);
            if (layer != null) layer.setAnimation(null);
        } catch (Throwable t) {
            // ignore
        }
    }
}
