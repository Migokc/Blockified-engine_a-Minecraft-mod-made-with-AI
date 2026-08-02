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
import java.util.Map;

/**
 * A Psych-style 2D character loaded for the free-cam editor: its Sparrow atlas plus the
 * animations declared in its JSON, so the placed object shows the real art and can play
 * any of its named animations as a live preview.
 */
public final class FreeCam2DCharacter implements AutoCloseable {

    private record AnimDef(List<SparrowAtlas.Frame> frames, double fps, boolean loop,
                           double offsetX, double offsetY, String prefix, List<Integer> indices) {}

    /** Export view of one animation, for generating Lua addAnimationBy... calls. */
    public record AnimExport(String name, String prefix, int fps, boolean loop,
                             double offsetX, double offsetY, List<Integer> indices) {}

    private SparrowAtlas atlas;
    private String image = "";
    private final Map<String, AnimDef> anims = new LinkedHashMap<>();
    private final List<String> order = new ArrayList<>();
    private String current = "";
    private long startNano;
    private int refW = 1, refH = 1;
    private Integer fpsOverride;
    private Boolean loopOverride;

    private FreeCam2DCharacter() {}

    /** Loads the character JSON (Psych format) and its Sparrow atlas, or null on failure. */
    public static FreeCam2DCharacter load(Path json) {
        try {
            JsonObject root = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
            String image = str(root, "image");
            if (image.isEmpty()) return null;

            // Resolve <mod>/images/<image>.png|.xml by walking up to the folder holding images/.
            Path base = json.getParent();
            while (base != null && !Files.isDirectory(base.resolve("images"))) base = base.getParent();
            if (base == null) base = json.getParent();
            Path png = base.resolve("images").resolve(image + ".png");
            Path xml = base.resolve("images").resolve(image + ".xml");

            SparrowAtlas atlas = SparrowAtlas.load(png, xml);
            if (atlas == null) return null;

            FreeCam2DCharacter c = new FreeCam2DCharacter();
            c.atlas = atlas;
            c.image = image;
            JsonArray list = root.has("animations") && root.get("animations").isJsonArray()
                    ? root.getAsJsonArray("animations") : new JsonArray();
            for (JsonElement element : list) {
                if (!element.isJsonObject()) continue;
                JsonObject a = element.getAsJsonObject();
                String prefix = str(a, "name");
                String trigger = str(a, "anim");
                if (trigger.isEmpty()) trigger = prefix;
                if (trigger.isEmpty()) continue;
                double fps = a.has("fps") ? a.get("fps").getAsDouble() : 24;
                boolean loop = a.has("loop") && a.get("loop").getAsBoolean();
                double[] off = readOffsets(a);
                List<Integer> indices = readIndices(a);
                List<SparrowAtlas.Frame> frames = resolveFrames(atlas, prefix, indices);
                if (frames.isEmpty()) continue;
                c.anims.put(trigger, new AnimDef(frames, fps <= 0 ? 24 : fps, loop,
                        off[0], off[1], prefix, indices));
                c.order.add(trigger);
            }
            if (c.anims.isEmpty()) { atlas.close(); return null; }

            SparrowAtlas.Frame f0 = c.anims.values().iterator().next().frames().get(0);
            c.refW = Math.max(1, f0.frameW);
            c.refH = Math.max(1, f0.frameH);
            c.play(c.anims.containsKey("idle") ? "idle" : c.order.get(0));
            return c;
        } catch (Exception error) {
            FnfMod.LOGGER.warn("Failed to load 2D character {}: {}", json, error.toString());
            return null;
        }
    }

    /** Builds a preview straight from a Sparrow PNG+XML, one animation per atlas prefix. */
    public static FreeCam2DCharacter fromAtlas(Path png, Path xml) {
        SparrowAtlas atlas = SparrowAtlas.load(png, xml);
        if (atlas == null) return null;
        FreeCam2DCharacter c = new FreeCam2DCharacter();
        c.atlas = atlas;
        for (String prefix : atlas.animationNames()) {
            List<SparrowAtlas.Frame> frames = atlas.frames(prefix);
            if (frames.isEmpty()) continue;
            c.anims.put(prefix, new AnimDef(frames, 24, true, 0, 0, prefix, null));
            c.order.add(prefix);
        }
        if (c.anims.isEmpty()) { atlas.close(); return null; }
        SparrowAtlas.Frame f0 = c.anims.values().iterator().next().frames().get(0);
        c.refW = Math.max(1, f0.frameW);
        c.refH = Math.max(1, f0.frameH);
        c.play(c.anims.containsKey("idle") ? "idle" : c.order.get(0));
        return c;
    }

    private static List<SparrowAtlas.Frame> resolveFrames(SparrowAtlas atlas, String prefix, List<Integer> indices) {
        if (indices != null && !indices.isEmpty()) {
            List<SparrowAtlas.Frame> all = atlas.framesByPrefix(prefix);
            List<SparrowAtlas.Frame> picked = new ArrayList<>();
            for (int i : indices) if (i >= 0 && i < all.size()) picked.add(all.get(i));
            if (!picked.isEmpty()) return picked;
        }
        return atlas.framesByPrefix(prefix);
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

    private static double[] readOffsets(JsonObject a) {
        if (a.has("offsets") && a.get("offsets").isJsonArray()) {
            JsonArray o = a.getAsJsonArray("offsets");
            return new double[]{o.size() > 0 ? o.get(0).getAsDouble() : 0,
                    o.size() > 1 ? o.get(1).getAsDouble() : 0};
        }
        return new double[]{0, 0};
    }

    private static String str(JsonObject o, String key) {
        try { return o.has(key) ? o.get(key).getAsString().trim() : ""; }
        catch (Exception ignored) { return ""; }
    }

    public List<String> animationNames() { return order; }
    public String current() { return current; }
    public String image() { return image; }

    /** All animations as export data for building the Lua sprite. */
    public List<AnimExport> exportData() {
        List<AnimExport> out = new ArrayList<>();
        for (Map.Entry<String, AnimDef> e : anims.entrySet()) {
            AnimDef a = e.getValue();
            out.add(new AnimExport(e.getKey(), a.prefix(), (int) Math.round(a.fps()),
                    a.loop(), a.offsetX(), a.offsetY(), a.indices()));
        }
        return out;
    }

    public void play(String name) {
        if (anims.containsKey(name)) { current = name; startNano = System.nanoTime(); }
    }

    /** Overrides fps/loop for every animation (used by spritesheet objects). */
    public void setPlayback(int fps, boolean loop) {
        fpsOverride = fps;
        loopOverride = loop;
    }

    /** The Sparrow frame to show right now, advancing by the animation's fps. */
    public SparrowAtlas.Frame currentFrame() {
        AnimDef a = anims.get(current);
        if (a == null) return null;
        double fps = fpsOverride != null ? fpsOverride : a.fps();
        boolean loop = loopOverride != null ? loopOverride : a.loop();
        double elapsed = (System.nanoTime() - startNano) / 1.0e9;
        int n = a.frames().size();
        int idx = (int) Math.floor(elapsed * Math.max(0.1, fps));
        idx = loop ? Math.floorMod(idx, n) : Math.min(idx, n - 1);
        return a.frames().get(idx);
    }

    public double[] currentOffset() {
        AnimDef a = anims.get(current);
        return a == null ? new double[]{0, 0} : new double[]{a.offsetX(), a.offsetY()};
    }

    public void setAntialiasing(boolean enabled) {
        if (atlas != null) atlas.setAntialiasing(enabled);
    }

    public ResourceLocation texture() { return atlas.texture(); }
    public int texWidth() { return atlas.width(); }
    public int texHeight() { return atlas.height(); }
    public int refW() { return refW; }
    public int refH() { return refH; }

    @Override
    public void close() {
        if (atlas != null) { atlas.close(); atlas = null; }
    }
}
