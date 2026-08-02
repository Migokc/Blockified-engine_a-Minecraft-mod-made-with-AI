package com.fnfmod.client.gameplay;

import com.fnfmod.FnfMod;
import com.fnfmod.client.render.SparrowAtlas;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A real Psych/FNF 2D character rendered in world space. This mirrors the FNF-mode
 * {@code PsychGameplayScene.CharacterSprite} behaviour — animations from the character
 * JSON, per-animation offsets, {@code scale}, {@code flip_x}, {@code sing_duration},
 * {@code dance_every}, gf-style dance-from-sing, and the sing → hold → dance loop — but
 * exposes the current frame/offset so a world renderer (or the free-cam editor) can draw
 * it as a billboarded sprite instead of on the 2D HUD canvas.
 *
 * <p>So a "2D character in 3D space" is a genuine character (Play Animation, dance, sing),
 * not a plain Lua sprite. Drive it with {@link #update} every frame and {@link #beat} on
 * the beat; play named animations with {@link #play(String, boolean)}.
 */
public final class WorldCharacter implements AutoCloseable {

    private record Animation(List<SparrowAtlas.Frame> frames, double fps, boolean loop,
                             double offsetX, double offsetY, String prefix, List<Integer> indices) {}

    /** Export view of one animation, for generating addAnimationBy... Lua. */
    public record AnimExport(String name, String prefix, int fps, boolean loop,
                             double offsetX, double offsetY, List<Integer> indices) {}

    private SparrowAtlas atlas;
    private String image = "";
    private final Map<String, Animation> animations = new LinkedHashMap<>();
    private final List<String> order = new ArrayList<>();
    private double scaleX = 1, scaleY = 1;
    private double alpha = 1;
    private int color = 0xFFFFFF;
    private boolean flipX;
    private boolean antialiasing = true;
    private boolean billboard = true;
    private boolean lighting = true;
    private boolean seeThrough = false;
    private double singDuration = 4;
    private int danceEvery = 2;
    private boolean danceFromSing;
    private int refW = 1, refH = 1;

    private String animation = "idle";
    private String idleSuffix = "";
    private int frame;
    private double elapsed;
    private double holdTimer;
    private double heyTimer;
    private boolean danced = true;
    private boolean specialAnim;
    private boolean animationFinished;

    private WorldCharacter() {}

    /** Loads a Psych character JSON and its Sparrow atlas, or null on failure. */
    public static WorldCharacter load(Path json) {
        try {
            JsonObject data = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
            String image = str(data, "image");
            if (image.isEmpty()) return null;

            Path base = json.getParent();
            while (base != null && !Files.isDirectory(base.resolve("images"))) base = base.getParent();
            if (base == null) base = json.getParent();
            Path png = base.resolve("images").resolve(image + ".png");
            Path xml = base.resolve("images").resolve(image + ".xml");
            SparrowAtlas atlas = SparrowAtlas.load(png, xml);
            if (atlas == null) return null;

            WorldCharacter c = new WorldCharacter();
            c.atlas = atlas;
            c.image = image;
            c.scaleX = c.scaleY = num(data, "scale", 1);
            c.flipX = boolAttr(data, "flip_x", false);
            c.antialiasing = antialiasingFlag(data);
            c.singDuration = Math.max(0.1, num(data, "sing_duration", 4));
            c.danceEvery = (int) num(data, "dance_every", 0);
            String id = stripExt(json.getFileName().toString()).toLowerCase(Locale.ROOT);
            c.danceFromSing = id.equals("gf") || id.startsWith("gf-") || id.startsWith("gf_");

            JsonArray list = data.has("animations") && data.get("animations").isJsonArray()
                    ? data.getAsJsonArray("animations") : new JsonArray();
            for (JsonElement element : list) {
                if (!element.isJsonObject()) continue;
                JsonObject a = element.getAsJsonObject();
                String prefix = str(a, "name");
                String trigger = str(a, "anim");
                if (trigger.isEmpty()) trigger = prefix;
                if (trigger.isEmpty()) continue;
                List<Integer> indices = readIndices(a);
                List<SparrowAtlas.Frame> frames = frames(atlas, prefix, indices);
                if (frames.isEmpty()) continue;
                double[] off = pair(a, "offsets");
                c.animations.put(trigger, new Animation(frames, num(a, "fps", 24) <= 0 ? 24 : num(a, "fps", 24),
                        boolAttr(a, "loop", false), off[0], off[1], prefix, indices));
                c.order.add(trigger);
            }
            if (c.animations.isEmpty()) { atlas.close(); return null; }
            atlas.setAntialiasing(c.antialiasing);
            if (c.danceEvery <= 0) c.danceEvery = has(c, "danceLeft") && has(c, "danceRight") ? 1 : 2;

            SparrowAtlas.Frame f0 = atlas.allFrames().isEmpty() ? c.animations.values().iterator().next().frames().get(0)
                    : atlas.allFrames().get(0);
            c.refW = Math.max(1, f0.frameW);
            c.refH = Math.max(1, f0.frameH);
            c.play(c.findExact(c, "idle") != null ? "idle" : c.order.get(0), true);
            return c;
        } catch (Exception error) {
            FnfMod.LOGGER.warn("Failed to load world character {}: {}", json, error.toString());
            return null;
        }
    }

    // --- playback / behaviour (mirrors CharacterSprite) ---

    public boolean play(String requested, boolean force) { return play(requested, force, false); }

    public boolean play(String requested, boolean force, boolean special) {
        String match = find(requested);
        if (match == null || (!force && match.equals(animation) && !animationFinished)) return false;
        animation = match;
        frame = 0;
        elapsed = 0;
        animationFinished = false;
        holdTimer = 0;
        specialAnim = special;
        if (danceFromSing) {
            if (match.equalsIgnoreCase("singLEFT")) danced = true;
            else if (match.equalsIgnoreCase("singRIGHT")) danced = false;
            else if (match.equalsIgnoreCase("singUP") || match.equalsIgnoreCase("singDOWN")) danced = !danced;
        }
        return true;
    }

    public void dance(boolean force) {
        if (specialAnim) return;
        String left = findExact(this, "danceLeft" + idleSuffix);
        String right = findExact(this, "danceRight" + idleSuffix);
        if (left == null || right == null) {
            left = findExact(this, "danceLeft");
            right = findExact(this, "danceRight");
        }
        if (left != null && right != null) {
            danced = !danced;
            play(danced ? right : left, force, false);
        } else {
            String idle = findExact(this, "idle" + idleSuffix);
            play(idle == null ? "idle" : idle, force, false);
        }
    }

    public void beat(int beat, int speed) {
        if (beat >= 0 && beat % Math.max(1, danceEvery * Math.max(1, speed)) == 0
                && !specialAnim && !animation.toLowerCase(Locale.ROOT).startsWith("sing")) {
            dance(true);
        }
    }

    public void update(double seconds, double stepMs, double playbackRate) {
        Animation current = animations.get(animation);
        if (current == null) return;
        advance(current, seconds * Math.max(0.01, playbackRate));

        if (heyTimer > 0) {
            heyTimer -= seconds * Math.max(0.01, playbackRate);
            if (heyTimer <= 0 && specialAnim
                    && (animation.equalsIgnoreCase("hey") || animation.equalsIgnoreCase("cheer"))) {
                specialAnim = false;
                dance(true);
            }
        } else if (animationFinished && findExact(this, animation + "-loop") != null) {
            play(findExact(this, animation + "-loop"), true, specialAnim);
        } else if (specialAnim && animationFinished) {
            specialAnim = false;
            dance(true);
        } else if (animation.toLowerCase(Locale.ROOT).endsWith("miss") && animationFinished) {
            dance(true);
        } else if (animationFinished && !isIdle(animation)
                && !animation.toLowerCase(Locale.ROOT).startsWith("sing")) {
            dance(true);
        }

        if (animation.toLowerCase(Locale.ROOT).startsWith("sing")) {
            holdTimer += seconds * Math.max(0.01, playbackRate);
            double singSeconds = Math.max(0.001, stepMs) * 0.0011 * singDuration;
            if (!specialAnim && holdTimer >= singSeconds) dance(true);
        }
    }

    private void advance(Animation current, double seconds) {
        if (current.fps() <= 0 || (animationFinished && !current.loop())) return;
        elapsed += seconds;
        double frameTime = 1.0 / current.fps();
        while (elapsed >= frameTime) {
            elapsed -= frameTime;
            if (frame + 1 < current.frames().size()) frame++;
            else if (current.loop()) frame = 0;
            else { animationFinished = true; break; }
        }
    }

    public void playHey(double seconds) {
        if (find("hey") != null) { play("hey", true, true); heyTimer = Math.max(0.1, seconds); }
    }

    // --- render data ---

    public SparrowAtlas.Frame currentFrame() {
        Animation a = animations.get(animation);
        if (a == null || a.frames().isEmpty()) return null;
        return a.frames().get(Math.max(0, Math.min(frame, a.frames().size() - 1)));
    }

    public double[] currentOffset() {
        Animation a = animations.get(animation);
        return a == null ? new double[]{0, 0} : new double[]{a.offsetX(), a.offsetY()};
    }

    public ResourceLocation texture() { return atlas.texture(); }
    public int texWidth() { return atlas.width(); }
    public int texHeight() { return atlas.height(); }
    public int refW() { return refW; }
    public int refH() { return refH; }
    public double scaleX() { return scaleX; }
    public double scaleY() { return scaleY; }
    public double alpha() { return alpha; }
    public int color() { return color; }
    public boolean flipX() { return flipX; }

    public boolean billboard() { return billboard; }
    public boolean lighting() { return lighting; }
    public boolean seeThrough() { return seeThrough; }
    public boolean antialiasing() { return antialiasing; }

    public void setScaleX(double v) { scaleX = v; }
    public void setScaleY(double v) { scaleY = v; }
    public void setAlpha(double v) { alpha = Math.max(0, Math.min(1, v)); }
    public void setColor(int rgb) { color = rgb & 0xFFFFFF; }
    public void setFlipX(boolean v) { flipX = v; }
    public void setBillboard(boolean v) { billboard = v; }
    public void setLighting(boolean v) { lighting = v; }
    public void setSeeThrough(boolean v) { seeThrough = v; }
    public String image() { return image; }
    public String current() { return animation; }
    public List<String> animationNames() { return order; }

    public void setAntialiasing(boolean enabled) {
        antialiasing = enabled;
        if (atlas != null) atlas.setAntialiasing(enabled);
    }

    public List<AnimExport> exportData() {
        List<AnimExport> out = new ArrayList<>();
        for (Map.Entry<String, Animation> e : animations.entrySet()) {
            Animation a = e.getValue();
            out.add(new AnimExport(e.getKey(), a.prefix(), (int) Math.round(a.fps()),
                    a.loop(), a.offsetX(), a.offsetY(), a.indices()));
        }
        return out;
    }

    @Override
    public void close() {
        if (atlas != null) { atlas.close(); atlas = null; }
    }

    // --- helpers ---

    private String find(String requested) {
        if (requested == null || requested.isBlank()) return null;
        String exact = findExact(this, requested);
        if (exact != null) return exact;
        int dash = requested.indexOf('-');
        return dash > 0 ? find(requested.substring(0, dash)) : null;
    }

    private static String findExact(WorldCharacter c, String requested) {
        if (c.animations.containsKey(requested)) return requested;
        for (String key : c.animations.keySet()) if (key.equalsIgnoreCase(requested)) return key;
        return null;
    }

    private static boolean has(WorldCharacter c, String name) { return findExact(c, name) != null; }

    private boolean isIdle(String name) {
        return name.equalsIgnoreCase("idle" + idleSuffix) || name.equalsIgnoreCase("danceLeft" + idleSuffix)
                || name.equalsIgnoreCase("danceRight" + idleSuffix) || name.equalsIgnoreCase("idle")
                || name.equalsIgnoreCase("danceLeft") || name.equalsIgnoreCase("danceRight");
    }

    private static List<SparrowAtlas.Frame> frames(SparrowAtlas atlas, String prefix, List<Integer> indices) {
        if (indices != null && !indices.isEmpty()) {
            List<SparrowAtlas.Frame> all = atlas.framesByPrefix(prefix);
            List<SparrowAtlas.Frame> picked = new ArrayList<>();
            for (int i : indices) if (i >= 0 && i < all.size()) picked.add(all.get(i));
            if (!picked.isEmpty()) return picked;
        }
        return new ArrayList<>(atlas.framesByPrefix(prefix));
    }

    private static List<Integer> readIndices(JsonObject a) {
        if (a.has("indices") && a.get("indices").isJsonArray() && a.getAsJsonArray("indices").size() > 0) {
            List<Integer> list = new ArrayList<>();
            for (JsonElement ix : a.getAsJsonArray("indices")) {
                try { list.add(ix.getAsInt()); } catch (Exception ignored) {}
            }
            return list.isEmpty() ? null : list;
        }
        return null;
    }

    private static String str(JsonObject o, String key) {
        try { return o.has(key) ? o.get(key).getAsString().trim() : ""; } catch (Exception e) { return ""; }
    }

    private static double num(JsonObject o, String key, double fallback) {
        try { return o.has(key) ? o.get(key).getAsDouble() : fallback; } catch (Exception e) { return fallback; }
    }

    private static boolean boolAttr(JsonObject o, String key, boolean fallback) {
        try { return o.has(key) ? o.get(key).getAsBoolean() : fallback; } catch (Exception e) { return fallback; }
    }

    private static boolean antialiasingFlag(JsonObject o) {
        if (o.has("no_antialiasing")) return !boolAttr(o, "no_antialiasing", false);
        if (o.has("noAntialiasing")) return !boolAttr(o, "noAntialiasing", false);
        return boolAttr(o, "antialiasing", true);
    }

    private static double[] pair(JsonObject o, String key) {
        try {
            JsonArray arr = o.getAsJsonArray(key);
            return new double[]{arr.get(0).getAsDouble(), arr.size() > 1 ? arr.get(1).getAsDouble() : 0};
        } catch (Exception ignored) { return new double[]{0, 0}; }
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }
}
