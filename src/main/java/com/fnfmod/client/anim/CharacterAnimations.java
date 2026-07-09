package com.fnfmod.client.anim;

import com.fnfmod.FnfMod;
import com.fnfmod.song.SongLibrary;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.api.layered.KeyframeAnimationPlayer;
import dev.kosmx.playerAnim.api.layered.ModifierLayer;
import dev.kosmx.playerAnim.api.layered.modifier.AbstractFadeModifier;
import dev.kosmx.playerAnim.api.layered.modifier.MirrorModifier;
import dev.kosmx.playerAnim.core.util.Ease;
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
 * A set's character.json configures the camera and which animation plays per action:
 * {
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
    public static final String DEFAULT_SET = "default";

    public static final class AnimEntry {
        public KeyframeAnimation anim;
        public float camX, camY;
    }

    private static final class AnimSet {
        final Map<String, AnimEntry> actions = new HashMap<>(); // "left" or "player.left"
        float baseCamX, baseCamY;

        AnimEntry resolve(String role, String action) {
            AnimEntry e = actions.get(role + "." + action);
            if (e == null) e = actions.get(action);
            return e;
        }
    }

    private static final Map<String, AnimSet> SETS = new LinkedHashMap<>();
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
        SETS.clear();
        Path dir = SongLibrary.animationsDir();
        if (!Files.isDirectory(dir)) {
            SETS.put(DEFAULT_SET, new AnimSet());
            return;
        }
        SETS.put(DEFAULT_SET, loadSet(dir, true));
        try (Stream<Path> subdirs = Files.list(dir)) {
            subdirs.filter(Files::isDirectory).sorted().forEach(sub ->
                    SETS.put(sub.getFileName().toString(), loadSet(sub, false)));
        } catch (Exception e) {
            FnfMod.LOGGER.warn("Failed to scan animation sets: {}", e.toString());
        }
        int total = SETS.values().stream().mapToInt(s -> s.actions.size()).sum();
        if (total > 0) {
            FnfMod.LOGGER.info("Loaded {} FNF animation set(s) ({} mapped actions)", SETS.size(), total);
        }
    }

    /** Names of all selectable sets ("default" first). */
    public static synchronized List<String> listSets() {
        return new ArrayList<>(SETS.keySet());
    }

    // ------------------------------------------------------------------ loading

    private static AnimSet loadSet(Path dir, boolean isDefaultSet) {
        AnimSet set = new AnimSet();
        Map<String, KeyframeAnimation> anims = new HashMap<>();
        JsonObject characterJson = null;
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
                } else if (name.equals("mapping.json")) {
                    try {
                        legacyMapping = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
                    } catch (Exception e) {
                        FnfMod.LOGGER.warn("Bad {}: {}", f, e.toString());
                    }
                } else {
                    loadAnimFile(f, anims);
                }
            }
        } catch (Exception e) {
            FnfMod.LOGGER.warn("Failed to read animation folder {}: {}", dir, e.toString());
        }

        // base camera offset
        if (characterJson != null && characterJson.has("cameraOffset")) {
            float[] off = readVec2(characterJson.get("cameraOffset"));
            set.baseCamX = off[0];
            set.baseCamY = off[1];
        }

        // action mapping from character.json
        JsonObject animMap = characterJson != null && characterJson.has("animations")
                && characterJson.get("animations").isJsonObject()
                ? characterJson.getAsJsonObject("animations") : null;

        for (String action : ACTIONS) {
            AnimEntry entry = new AnimEntry();
            String animName = null;
            if (animMap != null && animMap.has(action)) {
                JsonElement el = animMap.get(action);
                if (el.isJsonPrimitive()) {
                    animName = el.getAsString();
                } else if (el.isJsonObject()) {
                    JsonObject o = el.getAsJsonObject();
                    if (o.has("anim")) animName = o.get("anim").getAsString();
                    if (o.has("cameraOffset")) {
                        float[] off = readVec2(o.get("cameraOffset"));
                        entry.camX = off[0];
                        entry.camY = off[1];
                    }
                }
            }
            // danceleft/danceright are accepted as FNF-style aliases for idle/idle2
            entry.anim = pick(anims, animName, "fnf_" + action, action,
                    action.equals("idle") ? "danceleft" : null,
                    action.equals("idle2") ? "danceright" : null);
            if (entry.anim != null) set.actions.put(action, entry);
        }

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
        return set;
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

    private static void loadAnimFile(Path file, Map<String, KeyframeAnimation> out) {
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
            FnfMod.LOGGER.warn("Failed to load animation {}: {}", file, t.toString());
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
        AnimSet set = SETS.getOrDefault(setName, SETS.get(DEFAULT_SET));
        return set == null ? new float[]{0, 0} : new float[]{set.baseCamX, set.baseCamY};
    }

    /**
     * Plays an animation on the player entity. When the character is the
     * opponent side and the animation isn't a dedicated opponent one, it is
     * mirrored left&lt;-&gt;right so side-angled poses face the right way.
     * Returns the animation's camera offset [x, y] (x flipped when mirrored),
     * or null if no animation was found/played.
     */
    public static synchronized float[] play(Player player, String setName, String role, String action) {
        if (!available || !(player instanceof AbstractClientPlayer clientPlayer)) return null;
        try {
            AnimSet set = SETS.getOrDefault(setName == null ? DEFAULT_SET : setName, SETS.get(DEFAULT_SET));
            if (set == null) return null;
            boolean roleSpecific = set.actions.containsKey(role + "." + action);
            AnimEntry entry = set.resolve(role, action);
            if (entry == null && !DEFAULT_SET.equals(setName)) {
                AnimSet def = SETS.get(DEFAULT_SET);
                if (def != null) {
                    roleSpecific = def.actions.containsKey(role + "." + action);
                    entry = def.resolve(role, action);
                }
            }
            if (entry == null || entry.anim == null) return null;
            var data = PlayerAnimationAccess.getPlayerAssociatedData(clientPlayer);
            @SuppressWarnings("unchecked")
            ModifierLayer<IAnimation> layer = (ModifierLayer<IAnimation>) data.get(LAYER_ID);
            if (layer == null) return null;

            boolean mirror = "opponent".equals(role) && !roleSpecific;
            boolean isIdle = action.startsWith("idle");
            // a looping idle that's already playing keeps looping — restarting it
            // would blend through the vanilla rest pose every beat
            if (isIdle && unwrapPlayer(layer.getAnimation()) instanceof KeyframeAnimationPlayer current
                    && current.getData() == entry.anim && current.isActive() && entry.anim.isInfinite) {
                return new float[]{mirror ? -entry.camX : entry.camX, entry.camY};
            }
            IAnimation next = mirror
                    ? new ModifierLayer<>(new KeyframeAnimationPlayer(entry.anim), new MirrorModifier())
                    : new KeyframeAnimationPlayer(entry.anim);
            // short fade from the current pose instead of snapping through the rest pose
            layer.replaceAnimationWithFade(
                    AbstractFadeModifier.standardFadeIn(isIdle ? 4 : 2, Ease.INOUTSINE), next);
            return new float[]{mirror ? -entry.camX : entry.camX, entry.camY};
        } catch (Throwable t) {
            return null; // never let animation problems break gameplay
        }
    }

    private static IAnimation unwrapPlayer(IAnimation anim) {
        return anim instanceof ModifierLayer<?> wrapped ? wrapped.getAnimation() : anim;
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
