package com.fnfmod.client.gameplay;

import com.fnfmod.FnfMod;
import com.fnfmod.client.render.SparrowAtlas;
import com.fnfmod.client.render.AnimateAtlas;
import com.fnfmod.client.render.AnimateAtlasCache;
import com.fnfmod.client.render.SpriteAtlasCache;
import com.fnfmod.song.SongLibrary;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
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

    /** JSON scale remains authoritative; world rendering applies this extra size multiplier. */
    public static final double WORLD_RENDER_SCALE = 0.23;
    /** Position coordinates still use Blockified's established 64-pixel world grid. */
    public static final double WORLD_PIXELS_PER_BLOCK = 64.0;

    private record Animation(List<SparrowAtlas.Frame> frames, double fps, boolean loop,
                             double offsetX, double offsetY, String prefix, List<Integer> indices,
                             SparrowAtlas sheet) {}

    /** Export view of one animation, for generating addAnimationBy... Lua. */
    public record AnimExport(String name, String prefix, int fps, boolean loop,
                             double offsetX, double offsetY, List<Integer> indices) {}

    private SparrowAtlas atlas;
    private String image = "";
    private final Map<String, Animation> animations = new LinkedHashMap<>();
    private final List<String> order = new ArrayList<>();
    private double scaleX = 1, scaleY = 1;
    private double initialScale = 1;
    private double alpha = 1;
    private int color = 0xFFFFFF;
    private boolean flipX;
    private boolean antialiasing = true;
    private boolean initialFlipX;
    private boolean initialAntialiasing = true;
    private boolean billboard = true;
    private boolean lighting = true;
    private String renderMode = "auto";
    private boolean seeThrough = false;
    private double singDuration = 4;
    private int danceEvery = 2;
    private boolean danceFromSing;
    private int refW = 1, refH = 1;
    private double originX = Double.NaN, originY = Double.NaN;

    private String animation = "idle";
    private String idleSuffix = "";
    private int frame;
    private double elapsed;
    private double holdTimer;
    private double heyTimer;
    private boolean danced = true;
    private boolean specialAnim;
    private boolean animationFinished;
    private boolean sustainActive;

    private WorldCharacter() {}

    /** Loads a Psych character JSON and its Sparrow atlas, or null on failure. */
    public static WorldCharacter load(Path json) {
        try {
            JsonObject data = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
            String image = str(data, "image");
            String assetPath = str(data, "assetPath");
            String renderType = str(data, "renderType").toLowerCase(Locale.ROOT);
            if (image.isEmpty()) image = assetPath;
            SparrowAtlas atlas = loadAssetSheet(json, image, renderType,
                    antialiasingFlag(data));
            if (atlas == null) {
                FnfMod.LOGGER.warn("Could not resolve {} character atlas '{}' for {}",
                        renderType.isBlank() ? "2D" : renderType, image, json);
                return null;
            }

            WorldCharacter c = new WorldCharacter();
            c.atlas = atlas;
            c.image = image;
            c.scaleX = c.scaleY = num(data, "scale", 1);
            c.flipX = data.has("flipX") ? boolAttr(data, "flipX", false) : boolAttr(data, "flip_x", false);
            c.antialiasing = antialiasingFlag(data);
            c.initialScale = c.scaleX;
            c.initialFlipX = c.flipX;
            c.initialAntialiasing = c.antialiasing;
            c.singDuration = Math.max(0.1, data.has("singTime")
                    ? num(data, "singTime", 4) : num(data, "sing_duration", 4));
            c.danceEvery = (int) (data.has("danceEvery")
                    ? num(data, "danceEvery", 0) : num(data, "dance_every", 0));
            String id = stripExt(json.getFileName().toString()).toLowerCase(Locale.ROOT);
            c.danceFromSing = id.equals("gf") || id.startsWith("gf-") || id.startsWith("gf_");

            JsonArray list = data.has("animations") && data.get("animations").isJsonArray()
                    ? data.getAsJsonArray("animations") : new JsonArray();
            Map<String, SparrowAtlas> sheets = new LinkedHashMap<>();
            sheets.put(assetPath, atlas);
            for (JsonElement element : list) {
                if (!element.isJsonObject()) continue;
                JsonObject a = element.getAsJsonObject();
                String prefix = str(a, "prefix");
                if (prefix.isEmpty()) prefix = str(a, "name");
                String trigger = str(a, "anim");
                if (trigger.isEmpty()) trigger = str(a, "name");
                if (trigger.isEmpty()) trigger = prefix;
                if (trigger.isEmpty()) continue;
                SparrowAtlas sheet = atlas;
                String animationAsset = str(a, "assetPath");
                if (!animationAsset.isEmpty() && !animationAsset.equals(assetPath)) {
                    sheet = sheets.get(animationAsset);
                    if (sheet == null) {
                        // Multi-atlas V-Slice characters can freely mix Animate,
                        // Sparrow, and Packer sheets. The animation's renderType,
                        // when present, overrides the character's base type.
                        String animationType = str(a, "renderType");
                        sheet = loadAssetSheet(json, animationAsset, animationType,
                                antialiasingFlag(data));
                        if (sheet != null) sheets.put(animationAsset, sheet);
                    }
                    if (sheet == null) continue;
                }
                List<Integer> indices = readIndices(a);
                List<SparrowAtlas.Frame> frames = str(a, "animType").equalsIgnoreCase("symbol")
                        ? new ArrayList<>(sheet.framesBySymbol(prefix)) : frames(sheet, prefix, indices);
                if (frames.isEmpty()) continue;
                double[] off = pair(a, "offsets");
                double fps = a.has("fps") ? num(a, "fps", 24) : num(a, "frameRate", 24);
                c.animations.put(trigger, new Animation(frames, fps <= 0 ? 24 : fps,
                        a.has("looped") ? boolAttr(a, "looped", false) : boolAttr(a, "loop", false),
                        off[0], off[1], prefix, indices, sheet));
                c.order.add(trigger);
            }
            if (c.animations.isEmpty()) {
                FnfMod.LOGGER.warn("Character {} loaded atlas '{}' but none of its animations resolved",
                        json, image);
                atlas.close();
                return null;
            }
            for (SparrowAtlas sheet : c.animationSheets()) sheet.setAntialiasing(c.antialiasing);
            if (c.danceEvery <= 0) c.danceEvery = has(c, "danceLeft") && has(c, "danceRight") ? 1 : 2;

            SparrowAtlas.Frame f0 = atlas.allFrames().isEmpty() ? c.animations.values().iterator().next().frames().get(0)
                    : atlas.allFrames().get(0);
            c.refW = Math.max(1, f0.frameW);
            c.refH = Math.max(1, f0.frameH);
            c.play(c.findExact(c, "idle") != null ? "idle" : c.order.get(0), true);
            return c;
        } catch (Throwable error) {
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
        sustainActive = false;
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

    /** Psych-compatible alternate-idle suffix used by the Alt Idle Animation event. */
    public void setIdleSuffix(String suffix) {
        idleSuffix = suffix == null ? "" : suffix;
    }

    /** Restores JSON defaults before a cached character is assigned to a new role. */
    public void resetForReuse() {
        scaleX = scaleY = initialScale;
        alpha = 1;
        color = 0xFFFFFF;
        flipX = initialFlipX;
        billboard = true;
        lighting = true;
        renderMode = "auto";
        seeThrough = false;
        originX = originY = Double.NaN;
        idleSuffix = "";
        holdTimer = heyTimer = 0;
        danced = true;
        specialAnim = false;
        sustainActive = false;
        setAntialiasing(initialAntialiasing);
        play(findExact(this, "idle") != null ? "idle" : order.get(0), true);
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
            if (!specialAnim && !sustainActive && holdTimer >= singSeconds) dance(true);
        }
    }

    private void advance(Animation current, double seconds) {
        if (current.fps() <= 0 || (animationFinished && !current.loop())) return;
        elapsed += seconds;
        double frameTime = 1.0 / current.fps();
        while (elapsed >= frameTime) {
            elapsed -= frameTime;
            int frameLimit = sustainActive && animation.toLowerCase(Locale.ROOT).startsWith("sing")
                    ? Math.min(2, current.frames().size()) : current.frames().size();
            if (frame + 1 < frameLimit) frame++;
            else if (sustainActive && frameLimit > 0) { frame = 0; animationFinished = false; }
            else if (current.loop()) frame = 0;
            else { animationFinished = true; break; }
        }
    }

    /** Starts/continues a held sing without restarting its clock every frame. */
    public boolean hold(String requested) {
        String match = find(requested);
        if (match == null) return false;
        if (!match.equals(animation)) play(match, true);
        sustainActive = true;
        holdTimer = 0;
        return true;
    }

    public void endHold() {
        if (!sustainActive) return;
        sustainActive = false;
        // A released sustain behaves like a freshly hit normal note: keep the
        // sing pose, then let the ordinary sing-duration timer return to idle.
        holdTimer = 0;
        animationFinished = false;
    }

    public void playHey(double seconds) {
        if (find("hey") != null) { play("hey", true, true); heyTimer = Math.max(0.1, seconds); }
    }

    // --- render data ---

    public SparrowAtlas.Frame currentFrame() {
        Animation a = animations.get(animation);
        if (a == null || a.frames().isEmpty()) return null;
        SparrowAtlas.Frame result = a.frames().get(Math.max(0, Math.min(frame, a.frames().size() - 1)));
        a.sheet().prepareFrame(result);
        return result;
    }

    public double[] currentOffset() {
        Animation a = animations.get(animation);
        return a == null ? new double[]{0, 0} : new double[]{a.offsetX(), a.offsetY()};
    }

    private SparrowAtlas currentSheet() {
        Animation current = animations.get(animation);
        return current == null ? atlas : current.sheet();
    }
    public ResourceLocation texture() { return currentSheet().texture(); }
    public int texWidth() { return currentSheet().width(); }
    public int texHeight() { return currentSheet().height(); }
    public int refW() { return refW; }
    public int refH() { return refH; }
    public double frameWidth() {
        SparrowAtlas.Frame value = currentFrame();
        return value == null ? refW : value.frameW;
    }
    public double frameHeight() {
        SparrowAtlas.Frame value = currentFrame();
        return value == null ? refH : value.frameH;
    }
    public double originX() { return Double.isFinite(originX) ? originX : refW * 0.5; }
    public double originY() { return Double.isFinite(originY) ? originY : refH * 0.5; }
    public double scaleX() { return scaleX; }
    public double scaleY() { return scaleY; }
    public double alpha() { return alpha; }
    public int color() { return color; }
    public boolean flipX() { return flipX; }

    public boolean billboard() { return billboard; }
    public boolean lighting() { return lighting; }
    public String renderMode() { return renderMode; }
    public boolean seeThrough() { return seeThrough; }
    public boolean antialiasing() { return antialiasing; }

    public void setScaleX(double v) { scaleX = v; }
    public void setScaleY(double v) { scaleY = v; }
    public void setAlpha(double v) { alpha = Math.max(0, Math.min(1, v)); }
    public void setColor(int rgb) { color = rgb & 0xFFFFFF; }
    public void setFlipX(boolean v) { flipX = v; }
    public void setOriginX(double v) { if (Double.isFinite(v)) originX = v; }
    public void setOriginY(double v) { if (Double.isFinite(v)) originY = v; }
    public void setBillboard(boolean v) { billboard = v; }
    public void setLighting(boolean v) { lighting = v; renderMode = "auto"; }
    public void setRenderMode(String value) {
        if (value == null || value.isBlank() || value.equalsIgnoreCase("auto")) {
            renderMode = "auto";
            return;
        }
        renderMode = com.fnfmod.client.render.LuaWorldObject.RenderMode.resolve(value, lighting).luaName();
        lighting = renderMode.equals("lit");
    }
    public void setSeeThrough(boolean v) { seeThrough = v; }
    public String image() { return image; }
    public String current() { return animation; }
    public List<String> animationNames() { return order; }

    /** Initial visible bottom relative to the sprite origin, converted to world blocks. */
    public double initialBottomBlocks() {
        Animation current = animations.get(animation);
        SparrowAtlas.Frame value = currentFrame();
        if (current == null || value == null) {
            return refH * Math.abs(scaleY) * WORLD_RENDER_SCALE / (WORLD_PIXELS_PER_BLOCK * 2.0);
        }
        double top = -originY() - value.frameY - current.offsetY();
        double visibleHeight = value.rotated ? value.w : value.h;
        return (top + visibleHeight) * scaleY * WORLD_RENDER_SCALE / WORLD_PIXELS_PER_BLOCK;
    }

    /** Lightweight picker preview using the same current atlas/frame as gameplay. */
    public void drawPreview(GuiGraphics gui, float centerX, float centerY, float scale) {
        SparrowAtlas.Frame value = currentFrame();
        if (value == null || gui == null) return;
        float oldAlpha = SparrowAtlas.globalAlpha;
        float oldR = SparrowAtlas.tintR, oldG = SparrowAtlas.tintG, oldB = SparrowAtlas.tintB;
        float sx = Math.max(0.01f, scale * (float) Math.abs(scaleX));
        float sy = Math.max(0.01f, scale * (float) Math.abs(scaleY));
        gui.flush();
        SparrowAtlas.globalAlpha = (float) alpha;
        SparrowAtlas.tintR = ((color >> 16) & 255) / 255f;
        SparrowAtlas.tintG = ((color >> 8) & 255) / 255f;
        SparrowAtlas.tintB = (color & 255) / 255f;
        double[] offset = currentOffset();
        gui.pose().pushPose();
        gui.pose().translate(centerX - offset[0] * sx, centerY - offset[1] * sy, 0);
        gui.pose().scale(flipX ? -1f : 1f, sy / sx, 1f);
        if (flipX) {
            gui.flush();
            RenderSystem.disableCull();
        }
        currentSheet().drawScaled(gui, value, 0, 0, sx, null);
        if (flipX) {
            gui.flush();
            RenderSystem.enableCull();
        }
        gui.pose().popPose();
        gui.flush();
        SparrowAtlas.globalAlpha = oldAlpha;
        SparrowAtlas.tintR = oldR;
        SparrowAtlas.tintG = oldG;
        SparrowAtlas.tintB = oldB;
    }

    public void setAntialiasing(boolean enabled) {
        antialiasing = enabled;
        for (SparrowAtlas sheet : animationSheets()) sheet.setAntialiasing(enabled);
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
        for (SparrowAtlas sheet : animationSheets()) sheet.close();
        atlas = null;
    }

    private java.util.Set<SparrowAtlas> animationSheets() {
        java.util.Set<SparrowAtlas> sheets = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        if (atlas != null) sheets.add(atlas);
        for (Animation animation : animations.values()) sheets.add(animation.sheet());
        return sheets;
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
            return new ArrayList<>(atlas.framesByIndices(prefix, indices));
        }
        return new ArrayList<>(atlas.framesByPrefix(prefix));
    }

    private static List<Integer> readIndices(JsonObject a) {
        String key = a.has("indices") ? "indices" : a.has("frameIndices") ? "frameIndices" : "";
        if (!key.isEmpty() && a.get(key).isJsonArray() && a.getAsJsonArray(key).size() > 0) {
            List<Integer> list = new ArrayList<>();
            for (JsonElement ix : a.getAsJsonArray(key)) {
                try { list.add(ix.getAsInt()); } catch (Exception ignored) {}
            }
            return list.isEmpty() ? null : list;
        }
        return null;
    }

    /** Resolves and loads every atlas format currently used by V-Slice character JSONs. */
    private static SparrowAtlas loadAssetSheet(Path definitionJson, String raw, String renderType,
                                                boolean antialiasing) {
        if (raw == null || raw.isBlank()) return null;
        String type = renderType == null ? "" : renderType.trim().toLowerCase(Locale.ROOT);
        Path animateFolder = AnimateAtlas.resolveFolder(definitionJson, raw);
        if (type.contains("animateatlas") || animateFolder != null) {
            SparrowAtlas animate = AnimateAtlasCache.acquire(animateFolder, antialiasing);
            if (animate != null || type.contains("animateatlas")) return animate;
        }

        Path png = resolveSparrowAsset(definitionJson, raw, ".png");
        if (png == null) return null;
        if (type.contains("packer")) {
            Path txt = resolveSparrowAsset(definitionJson, raw, ".txt");
            return SparrowAtlas.loadPacker(png, txt, antialiasing);
        }
        Path xml = resolveSparrowAsset(definitionJson, raw, ".xml");
        if (xml != null) return SpriteAtlasCache.acquire(png, xml, antialiasing);
        Path txt = resolveSparrowAsset(definitionJson, raw, ".txt");
        return txt == null ? null : SparrowAtlas.loadPacker(png, txt, antialiasing);
    }

    /** Resolves Psych and V-Slice PNG/XML paths without assuming one engine layout. */
    private static Path resolveSparrowAsset(Path definitionJson, String raw, String extension) {
        if (definitionJson == null || raw == null || raw.isBlank()) return null;
        String value = raw.trim().replace('\\', '/');
        String library = "";
        int colon = value.indexOf(':');
        if (colon > 0) {
            library = value.substring(0, colon);
            value = value.substring(colon + 1);
        }
        while (value.startsWith("/")) value = value.substring(1);
        if (value.toLowerCase(Locale.ROOT).endsWith(extension)) {
            value = value.substring(0, value.length() - extension.length());
        }
        List<Path> candidates = new ArrayList<>();
        Path ancestor = definitionJson.toAbsolutePath().normalize().getParent();
        for (int depth = 0; ancestor != null && depth < 8;
             depth++, ancestor = ancestor.getParent()) {
            if (!library.isBlank()) {
                candidates.add(ancestor.resolve(library).resolve("images").resolve(value + extension));
                candidates.add(ancestor.resolve("assets").resolve(library)
                        .resolve("images").resolve(value + extension));
            }
            candidates.add(ancestor.resolve("images").resolve(value + extension));
            candidates.add(ancestor.resolve("shared/images").resolve(value + extension));
            candidates.add(ancestor.resolve("assets/images").resolve(value + extension));
            candidates.add(ancestor.resolve("assets/shared/images").resolve(value + extension));
        }
        // V-Slice mods may define a character locally while reusing its atlas
        // from the selected base assets directory. Mirror Polymod's merged
        // filesystem after exhausting the definition-local candidates.
        for (Path root : SongLibrary.orderedPickerContentRoots(SongLibrary.ExternalContent.IMAGES)) {
            if (root == null) continue;
            if (!library.isBlank()) {
                candidates.add(root.resolve(library).resolve("images").resolve(value + extension));
                candidates.add(root.resolve("assets").resolve(library)
                        .resolve("images").resolve(value + extension));
            }
            candidates.add(root.resolve("images").resolve(value + extension));
            candidates.add(root.resolve("shared/images").resolve(value + extension));
            candidates.add(root.resolve("assets/images").resolve(value + extension));
            candidates.add(root.resolve("assets/shared/images").resolve(value + extension));
            candidates.add(root.resolve(value + extension));
        }
        for (Path candidate : candidates) {
            Path normalized = candidate.normalize();
            if (Files.isRegularFile(normalized)) return normalized;
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
        if (o.has("isPixel")) return !boolAttr(o, "isPixel", false);
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
