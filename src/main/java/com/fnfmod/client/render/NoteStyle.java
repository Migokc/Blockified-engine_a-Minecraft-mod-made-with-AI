package com.fnfmod.client.render;

import com.fnfmod.FnfMod;
import com.fnfmod.song.SongLibrary;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.nio.file.Path;

/**
 * Renders notes/receptors/holds. If config/fnfmod/skins/default/NOTE_assets.png
 * + NOTE_assets.xml exist (straight out of an FNF mod), those are used;
 * otherwise a built-in procedurally generated arrow is drawn.
 */
public final class NoteStyle {

    /** lane colors: left purple, down blue, up green, right red */
    public static final int[] LANE_COLORS = {0xFFC24B99, 0xFF00FFFF, 0xFF12FA05, 0xFFF9393F};
    private static final float[] LANE_ROTATION = {-90f, 180f, 0f, 90f}; // arrow texture points up

    private static ResourceLocation arrowTexture;
    private static ResourceLocation arrowOutlineTexture;
    /** atlas holding the scrolling notes (classic NOTE_assets or V-Slice notes.png) */
    private static SparrowAtlas noteAtlas;
    /** atlas holding the receptors (classic NOTE_assets or V-Slice noteStrumline.png) */
    private static SparrowAtlas strumAtlas;
    private static String[] noteAnims;      // per lane
    private static String[] receptorAnims;  // per lane (static)
    private static String[] pressAnims;     // per lane
    private static String[] confirmAnims;   // per lane
    private static String[] holdAnims;      // per lane
    private static String[] holdEndAnims;   // per lane
    /** hit splash atlas (noteSplashes.png/xml) and its per-lane animation variants */
    private static SparrowAtlas splashAtlas;
    @SuppressWarnings("unchecked")
    private static java.util.List<String>[] splashAnims = new java.util.List[4];

    /** a fixed sub-rectangle of some texture, used for sustain trail pieces/ends */
    private record HoldSprite(ResourceLocation tex, int texW, int texH, int x, int y, int w, int h) {}

    private static final HoldSprite[] holdPieces = new HoldSprite[4];
    private static final HoldSprite[] holdEnds = new HoldSprite[4];
    /** V-Slice hold cover effect atlases (holdCoverPurple.png/xml etc.) */
    private static final SparrowAtlas[] coverAtlases = new SparrowAtlas[4];
    private static final String[] coverAnims = new String[4];
    private static final String[] coverEndAnims = new String[4];
    /** trimmed art size (px) of a plain note / static receptor — the scale reference */
    private static float noteRefPx = 155;
    private static float strumRefPx = 155;

    /**
     * Psych-style RGB template support: sheets authored with pure red (base),
     * green (highlight) and blue (outline) get remapped into one recolored
     * texture per lane using the user's note colors.
     */
    private static final class RGBSet {
        NativeImage src;
        boolean template;
        final com.mojang.blaze3d.platform.NativeImage[] pixels = new NativeImage[4];
        final net.minecraft.client.renderer.texture.DynamicTexture[] dyn =
                new net.minecraft.client.renderer.texture.DynamicTexture[4];
        final ResourceLocation[] id = new ResourceLocation[4];
    }

    private static RGBSet noteRGB, strumRGB, splashRGB, holdRGB;
    private static boolean holdsFromStrumAtlas;
    /** the note skin folder ships its own noteSplashes files */
    private static boolean skinOwnSplash;
    /** per-skin size multipliers from skin.json */
    private static float noteScale = 1f, receptorScale = 1f, holdWidthScale = 1f,
            splashScale = 1f, holdCoverScale = 1f;
    /** per-skin opacity multipliers from skin.json (default fully opaque) */
    private static float noteAlpha = 1f, sustainAlpha = 1f, receptorAlpha = 1f,
            splashAlpha = 1f, holdCoverAlpha = 1f;
    /** per-part GUI-pixel position offsets from skin.json */
    private static float noteX, noteY, receptorX, receptorY, sustainX, sustainY,
            splashX, splashY, holdCoverX, holdCoverY;
    /** external alpha multiplier for everything drawn (middlescroll opponent fade) */
    private static float extAlpha = 1f;
    private static float drawAlpha = 1f;
    private static boolean loaded;

    /** External fade (e.g. middlescroll opponent); combines with per-skin alphas. */
    public static void setDrawAlpha(float alpha) {
        extAlpha = alpha;
    }

    /** Applies a per-element alpha for the next draw (skin alpha x external fade x missed). */
    private static void applyAlpha(float skinAlpha) {
        drawAlpha = skinAlpha * extAlpha * missedFactor;
        SparrowAtlas.globalAlpha = drawAlpha;
    }

    /** Gray + 30% translucent styling for a totally-missed long note. */
    public static void setMissed(boolean m) {
        if (m) {
            SparrowAtlas.tintR = 0.45f;
            SparrowAtlas.tintG = 0.45f;
            SparrowAtlas.tintB = 0.5f;
            missedTint = true;
            missedFactor = 0.3f;
        } else {
            SparrowAtlas.tintR = SparrowAtlas.tintG = SparrowAtlas.tintB = 1f;
            missedTint = false;
            missedFactor = 1f;
        }
    }

    private static boolean missedTint = false;
    private static float missedFactor = 1f;

    private NoteStyle() {}

    public static void reload() {
        loaded = false;
        noteAtlas = null;
        strumAtlas = null;
        splashAtlas = null;
        noteRGB = null;
        strumRGB = null;
        splashRGB = null;
        holdRGB = null;
        holdSheetImage = null;
        for (int i = 0; i < 4; i++) {
            holdPieces[i] = null;
            holdEnds[i] = null;
            coverAtlases[i] = null;
            coverAnims[i] = null;
            coverEndAnims[i] = null;
        }
        load();
    }

    /** Selectable skin folders (subfolders of config/fnfmod/skins containing NOTE_assets.png). */
    public static java.util.List<String> listSkins() {
        java.util.List<String> out = new java.util.ArrayList<>();
        out.add("default");
        try (var dirs = java.nio.file.Files.list(SongLibrary.skinsDir())) {
            dirs.filter(java.nio.file.Files::isDirectory)
                    .filter(d -> java.nio.file.Files.isRegularFile(d.resolve("NOTE_assets.png"))
                            || java.nio.file.Files.isRegularFile(d.resolve("notes.png"))
                            || java.nio.file.Files.isRegularFile(d.resolve("noteStrumline.png")))
                    .map(d -> d.getFileName().toString())
                    .sorted()
                    .forEach(name -> {
                        if (!out.contains(name)) out.add(name);
                    });
        } catch (Exception ignored) {}
        return out;
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        if (arrowTexture == null) {
            arrowTexture = registerGenerated("gen/arrow", makeArrow(false));
            arrowOutlineTexture = registerGenerated("gen/arrow_outline", makeArrow(true));
        }
        Path skinDir = SongLibrary.skinsDir().resolve(com.fnfmod.client.ClientOptions.get().noteSkin);

        // optional per-skin size/opacity/position values: skins/<name>/skin.json
        noteScale = receptorScale = holdWidthScale = splashScale = holdCoverScale = 1f;
        noteAlpha = sustainAlpha = receptorAlpha = splashAlpha = holdCoverAlpha = 1f;
        noteX = noteY = receptorX = receptorY = sustainX = sustainY = 0f;
        splashX = splashY = holdCoverX = holdCoverY = 0f;
        Path skinCfg = skinDir.resolve("skin.json");
        if (java.nio.file.Files.isRegularFile(skinCfg)) {
            try {
                var o = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(skinCfg)).getAsJsonObject();
                noteScale = optF(o, "noteScale");
                receptorScale = optF(o, "receptorScale");
                holdWidthScale = optF(o, "holdWidthScale");
                splashScale = optF(o, "splashScale");
                holdCoverScale = optF(o, "holdCoverScale");
                noteAlpha = optA(o, "noteAlpha");
                sustainAlpha = optA(o, "sustainAlpha");
                receptorAlpha = optA(o, "receptorAlpha");
                splashAlpha = optA(o, "splashAlpha");
                holdCoverAlpha = optA(o, "holdCoverAlpha");
                noteX = optP(o, "noteX");
                noteY = optP(o, "noteY");
                receptorX = optP(o, "receptorX");
                receptorY = optP(o, "receptorY");
                sustainX = optP(o, "sustainX");
                sustainY = optP(o, "sustainY");
                splashX = optP(o, "splashX");
                splashY = optP(o, "splashY");
                holdCoverX = optP(o, "holdCoverX");
                holdCoverY = optP(o, "holdCoverY");
            } catch (Exception e) {
                FnfMod.LOGGER.warn("Bad skin.json in {}: {}", skinDir, e.toString());
            }
        }

        // classic single atlas (base game / Psych)
        SparrowAtlas classic = SparrowAtlas.load(skinDir.resolve("NOTE_assets.png"), skinDir.resolve("NOTE_assets.xml"));
        // V-Slice split atlases
        SparrowAtlas vsNotes = SparrowAtlas.load(skinDir.resolve("notes.png"), skinDir.resolve("notes.xml"));
        SparrowAtlas vsStrums = SparrowAtlas.load(skinDir.resolve("noteStrumline.png"), skinDir.resolve("noteStrumline.xml"));
        noteAtlas = classic != null ? classic : vsNotes;
        strumAtlas = classic != null ? classic : vsStrums;
        if (noteAtlas == null && strumAtlas == null) return;

        String[] dirs = {"left", "down", "up", "right"};
        String[] caps = {"Left", "Down", "Up", "Right"};
        String[] colors = {"purple", "blue", "green", "red"};
        String[] arrows = {"arrowLEFT", "arrowDOWN", "arrowUP", "arrowRIGHT"};
        noteAnims = new String[4];
        receptorAnims = new String[4];
        pressAnims = new String[4];
        confirmAnims = new String[4];
        holdAnims = new String[4];
        holdEndAnims = new String[4];
        for (int i = 0; i < 4; i++) {
            if (noteAtlas != null) {
                noteAnims[i] = noteAtlas.findAnimation(colors[i], "note" + caps[i],
                        colors[i] + " alone", dirs[i] + " note", "note" + dirs[i].toUpperCase());
            }
            if (strumAtlas != null) {
                receptorAnims[i] = strumAtlas.findAnimation(arrows[i], "static" + caps[i],
                        "static" + dirs[i].toUpperCase(), dirs[i] + " static");
                pressAnims[i] = strumAtlas.findAnimation(dirs[i] + " press", "press" + caps[i]);
                confirmAnims[i] = strumAtlas.findAnimation(dirs[i] + " confirm", "confirm" + caps[i]);
                holdAnims[i] = strumAtlas.findAnimation(colors[i] + " hold piece", colors[i] + " hold",
                        dirs[i] + " hold piece");
                // "pruple end hold" is a real typo in Psych Engine's NOTE_assets.xml
                holdEndAnims[i] = strumAtlas.findAnimation(colors[i] + " hold end", colors[i] + " end hold",
                        i == 0 ? "pruple end hold" : colors[i] + " hold end");
            }
        }
        // reference art sizes: notes and receptors are sized by their art pixels,
        // because press/confirm frames carry big glow padding in their logical boxes
        noteRefPx = refSize(noteAtlas, noteAnims, 155);
        strumRefPx = refSize(strumAtlas, receptorAnims, 155);

        // sustain trail sprites: classic atlas frames first...
        holdsFromStrumAtlas = false;
        if (strumAtlas != null) {
            for (int i = 0; i < 4; i++) {
                holdPieces[i] = toSprite(strumAtlas, holdAnims[i]);
                holdEnds[i] = toSprite(strumAtlas, holdEndAnims[i]);
            }
            holdsFromStrumAtlas = holdPieces[0] != null || holdPieces[1] != null
                    || holdPieces[2] != null || holdPieces[3] != null;
        }
        // ...else the V-Slice NOTE_hold_assets.png strip (8 columns: 4 pieces + 4 end caps)
        if (!holdsFromStrumAtlas) {
            loadVSliceHoldAssets(skinDir.resolve("NOTE_hold_assets.png"));
        }

        // hold cover effects (V-Slice: one atlas per color)
        String[] coverColors = {"Purple", "Blue", "Green", "Red"};
        for (int i = 0; i < 4; i++) {
            SparrowAtlas cover = SparrowAtlas.load(
                    skinDir.resolve("holdCover" + coverColors[i] + ".png"),
                    skinDir.resolve("holdCover" + coverColors[i] + ".xml"));
            if (cover != null) {
                coverAtlases[i] = cover;
                coverAnims[i] = cover.findAnimation("holdCover" + coverColors[i], "holdCover");
                coverEndAnims[i] = cover.findAnimation("holdCoverEnd" + coverColors[i], "holdCoverEnd");
            }
        }

        // hit splashes: the skin's own file wins; otherwise the user-selected pair
        // from config/fnfmod/splashes/ (colored with the note colors like everything else)
        splashAtlas = SparrowAtlas.load(skinDir.resolve("noteSplashes.png"), skinDir.resolve("noteSplashes.xml"));
        skinOwnSplash = splashAtlas != null;
        if (splashAtlas == null) {
            String sel = com.fnfmod.client.ClientOptions.get().splashSkin;
            if (sel != null && !sel.isEmpty()) {
                Path dir = SongLibrary.splashesDir();
                splashAtlas = SparrowAtlas.load(dir.resolve(sel + ".png"), dir.resolve(sel + ".xml"));
            }
        }
        splashAnims = new java.util.List[4];
        if (splashAtlas != null) {
            for (int i = 0; i < 4; i++) {
                java.util.List<String> variants = new java.util.ArrayList<>();
                for (String name : splashAtlas.animationNames()) {
                    String lower = name.toLowerCase(java.util.Locale.ROOT);
                    boolean splashy = lower.contains("impact") || lower.contains("splash");
                    boolean laneMatch = lower.contains(colors[i]) || lower.contains(dirs[i]);
                    if (splashy && laneMatch) variants.add(name);
                }
                java.util.Collections.sort(variants);
                splashAnims[i] = variants;
            }
        }

        // Psych RGB template detection + per-lane recolored textures
        noteRGB = makeRGBSet("note", noteAtlas == null ? null : noteAtlas.image(), null);
        strumRGB = strumAtlas == noteAtlas ? noteRGB
                : makeRGBSet("strum", strumAtlas == null ? null : strumAtlas.image(), null);
        // Splash templates have no blue channel and pre-colored splashes can contain
        // pure red/green, so pixel stats can't tell them apart. A skin's own splash
        // follows the skin's template status; external packs get a relaxed check.
        Boolean splashTemplate = skinOwnSplash
                ? Boolean.valueOf(noteRGB != null && noteRGB.template)
                : (splashAtlas == null ? Boolean.FALSE : Boolean.valueOf(detectSplashTemplate(splashAtlas.image())));
        splashRGB = makeRGBSet("splash", splashAtlas == null ? null : splashAtlas.image(), splashTemplate);
        holdRGB = holdsFromStrumAtlas ? strumRGB : makeRGBSet("hold", holdSheetImage, null);

        FnfMod.LOGGER.info("Loaded FNF note skin from {} ({}{}{})", skinDir,
                classic != null ? "classic NOTE_assets" : "V-Slice notes/noteStrumline",
                splashAtlas != null ? ", with splashes" : "",
                noteRGB != null && noteRGB.template ? ", RGB colorable" : "");
    }

    // ------------------------------------------------------------------ RGB note colors

    private static RGBSet makeRGBSet(String name, NativeImage src, Boolean templateOverride) {
        if (src == null) return null;
        RGBSet set = new RGBSet();
        set.src = src;
        set.template = templateOverride != null ? templateOverride : detectTemplate(src);
        if (set.template) {
            for (int lane = 0; lane < 4; lane++) {
                set.id[lane] = FnfMod.id("gen/rgb/" + name + "/" + lane);
                remapLane(set, lane);
            }
        }
        return set;
    }

    /**
     * Relaxed detection for splash sheets: soft feathered art (low alpha), lots of
     * pure red (base) plus some pure green (highlight); no blue/outline channel.
     */
    private static boolean detectSplashTemplate(NativeImage img) {
        long counted = 0, red = 0, green = 0;
        int step = Math.max(1, Math.min(img.getWidth(), img.getHeight()) / 256);
        for (int y = 0; y < img.getHeight(); y += step) {
            for (int x = 0; x < img.getWidth(); x += step) {
                int abgr = img.getPixelRGBA(x, y);
                int a = (abgr >>> 24) & 0xFF;
                if (a < 60) continue;
                counted++;
                int b = (abgr >> 16) & 0xFF;
                int g = (abgr >> 8) & 0xFF;
                int r = abgr & 0xFF;
                if (r > 220 && g < 60 && b < 60) red++;
                else if (g > 220 && r < 60 && b < 60) green++;
            }
        }
        return counted > 100 && red > counted * 0.05 && green > counted * 0.01;
    }

    /** A sheet is a Psych RGB template when it contains pure red, green and blue regions. */
    private static boolean detectTemplate(NativeImage img) {
        long opaque = 0, red = 0, green = 0, blue = 0;
        int step = Math.max(1, Math.min(img.getWidth(), img.getHeight()) / 256);
        for (int y = 0; y < img.getHeight(); y += step) {
            for (int x = 0; x < img.getWidth(); x += step) {
                int abgr = img.getPixelRGBA(x, y);
                int a = (abgr >>> 24) & 0xFF;
                if (a < 200) continue;
                opaque++;
                int b = (abgr >> 16) & 0xFF;
                int g = (abgr >> 8) & 0xFF;
                int r = abgr & 0xFF;
                if (r > 240 && g < 30 && b < 30) red++;
                else if (g > 240 && r < 30 && b < 30) green++;
                else if (b > 240 && r < 30 && g < 30) blue++;
            }
        }
        return opaque > 200
                && red > opaque * 0.04
                && blue > opaque * 0.005
                && green > opaque * 0.002;
    }

    /** Rewrites one lane's recolored copy: out = r*base + g*highlight + b*outline. */
    private static void remapLane(RGBSet set, int lane) {
        var opts = com.fnfmod.client.ClientOptions.get();
        int base = opts.noteColorBase[lane];
        int outline = opts.noteColorOutline[lane];
        int baseR = (base >> 16) & 0xFF, baseG = (base >> 8) & 0xFF, baseB = base & 0xFF;
        int outR = (outline >> 16) & 0xFF, outG = (outline >> 8) & 0xFF, outB = outline & 0xFF;

        NativeImage dst = set.pixels[lane];
        if (dst == null || dst.getWidth() != set.src.getWidth() || dst.getHeight() != set.src.getHeight()) {
            dst = new NativeImage(set.src.getWidth(), set.src.getHeight(), true);
            set.pixels[lane] = dst;
            if (set.dyn[lane] != null) set.dyn[lane].close();
            set.dyn[lane] = new net.minecraft.client.renderer.texture.DynamicTexture(dst);
            Minecraft.getInstance().getTextureManager().register(set.id[lane], set.dyn[lane]);
            Textures.smooth(set.dyn[lane]); // antialias recolored (RGB template) skin notes
        }
        for (int y = 0; y < set.src.getHeight(); y++) {
            for (int x = 0; x < set.src.getWidth(); x++) {
                int abgr = set.src.getPixelRGBA(x, y);
                int a = (abgr >>> 24) & 0xFF;
                int b = (abgr >> 16) & 0xFF;
                int g = (abgr >> 8) & 0xFF;
                int r = abgr & 0xFF;
                // highlight (green channel) maps to white
                int nr = Math.min(255, (r * baseR + g * 255 + b * outR) / 255);
                int ng = Math.min(255, (r * baseG + g * 255 + b * outG) / 255);
                int nb = Math.min(255, (r * baseB + g * 255 + b * outB) / 255);
                dst.setPixelRGBA(x, y, (a << 24) | (nb << 16) | (ng << 8) | nr);
            }
        }
        set.dyn[lane].upload();
    }

    /** Re-applies the user colors for one lane across all colorable sheets. */
    public static void rebuildLaneColors(int lane) {
        load();
        for (RGBSet set : new RGBSet[]{noteRGB, strumRGB, splashRGB, holdRGB}) {
            if (set != null && set.template) remapLane(set, lane);
        }
    }

    /** True when the current skin supports RGB recoloring at all. */
    public static boolean skinIsColorable() {
        load();
        return noteRGB != null && noteRGB.template;
    }

    /** True when the note skin ships its own splashes (splash selection is then disabled). */
    public static boolean skinHasOwnSplash() {
        load();
        return skinOwnSplash;
    }

    /** Selectable splash pairs (<name>.png + <name>.xml) in config/fnfmod/splashes/. */
    public static java.util.List<String> listSplashes() {
        java.util.List<String> out = new java.util.ArrayList<>();
        try (var files = java.nio.file.Files.list(SongLibrary.splashesDir())) {
            files.filter(f -> f.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".png"))
                    .map(f -> {
                        String n = f.getFileName().toString();
                        return n.substring(0, n.length() - 4);
                    })
                    .filter(n -> java.nio.file.Files.isRegularFile(SongLibrary.splashesDir().resolve(n + ".xml")))
                    .sorted()
                    .forEach(out::add);
        } catch (Exception ignored) {}
        return out;
    }

    private static ResourceLocation laneTex(RGBSet set, int lane) {
        if (set == null || !set.template) return null;
        if (!com.fnfmod.client.ClientOptions.get().noteColorsEnabled) return null;
        return set.id[lane];
    }

    private static float refSize(SparrowAtlas atlas, String[] anims, float fallback) {
        if (atlas == null) return fallback;
        for (String anim : anims) {
            SparrowAtlas.Frame f = anim == null ? null : atlas.frame(anim, 0);
            if (f != null) return Math.max(1, Math.max(f.w, f.h));
        }
        return fallback;
    }

    private static float optF(com.google.gson.JsonObject o, String key) {
        try {
            if (o.has(key) && o.get(key).isJsonPrimitive()) {
                float v = o.get(key).getAsFloat();
                if (v > 0.05f && v < 20f) return v;
            }
        } catch (Exception ignored) {}
        return 1f;
    }

    private static float optA(com.google.gson.JsonObject o, String key) {
        try {
            if (o.has(key) && o.get(key).isJsonPrimitive()) {
                return Math.max(0f, Math.min(1f, o.get(key).getAsFloat()));
            }
        } catch (Exception ignored) {}
        return 1f;
    }

    private static float optP(com.google.gson.JsonObject o, String key) {
        try {
            if (o.has(key) && o.get(key).isJsonPrimitive()) {
                float value = o.get(key).getAsFloat();
                if (Float.isFinite(value)) return Math.max(-4096f, Math.min(4096f, value));
            }
        } catch (Exception ignored) {}
        return 0f;
    }

    private static HoldSprite toSprite(SparrowAtlas atlas, String anim) {
        if (atlas == null || anim == null) return null;
        SparrowAtlas.Frame f = atlas.frame(anim, 0);
        if (f == null) return null;
        return new HoldSprite(atlas.texture(), atlas.width(), atlas.height(), f.x, f.y, f.w, f.h);
    }

    /** source pixels of the V-Slice hold sheet, kept for RGB recoloring */
    private static NativeImage holdSheetImage;

    private static void loadVSliceHoldAssets(Path png) {
        holdSheetImage = null;
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(png)) {
            NativeImage img = NativeImage.read(in);
            holdSheetImage = img;
            ResourceLocation id = registerGenerated("gen/hold/" + System.nanoTime(), img, true);
            int colW = img.getWidth() / 8;
            if (colW <= 0) return;
            // layout is interleaved per color: [piece, end, piece, end, ...]
            for (int i = 0; i < 4; i++) {
                holdPieces[i] = new HoldSprite(id, img.getWidth(), img.getHeight(), (i * 2) * colW, 0, colW, img.getHeight());
                holdEnds[i] = new HoldSprite(id, img.getWidth(), img.getHeight(), (i * 2 + 1) * colW, 0, colW, img.getHeight());
            }
        } catch (Exception ignored) {
            // no V-Slice hold sheet — procedural bars will be used
        }
    }

    private static void blitSprite(GuiGraphics gui, HoldSprite s, float x, float y, float w, float h,
                                   boolean flipY, ResourceLocation texOverride) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        boolean tinted = drawAlpha < 1f || missedTint;
        if (tinted) gui.setColor(SparrowAtlas.tintR, SparrowAtlas.tintG, SparrowAtlas.tintB, drawAlpha);
        gui.pose().pushPose();
        gui.pose().translate(x, y, 0);
        gui.pose().scale(w / s.w, h / s.h, 1);
        // vertical flip via source V mirroring (negative geometry scale gets the quad culled)
        float vOff = flipY ? s.y + s.h : s.y;
        int vH = flipY ? -s.h : s.h;
        gui.blit(texOverride != null ? texOverride : s.tex, 0, 0, s.w, s.h, s.x, vOff, s.w, vH, s.texW, s.texH);
        gui.pose().popPose();
        if (tinted) gui.setColor(1f, 1f, 1f, 1f);
    }

    // ------------------------------------------------------------------ hold covers

    public static boolean hasHoldCover(int lane) {
        load();
        return coverAtlases[lane] != null && coverAnims[lane] != null;
    }

    public static int holdCoverFrames(int lane) {
        return hasHoldCover(lane) ? coverAtlases[lane].frames(coverAnims[lane]).size() : 0;
    }

    /** receptorSize = the receptor's on-screen size; cover placement derives from it. */
    public static void drawHoldCover(GuiGraphics gui, int lane, long frameIndex, float x, float y, float receptorSize) {
        if (!hasHoldCover(lane)) return;
        var frames = coverAtlases[lane].frames(coverAnims[lane]);
        if (frames.isEmpty()) return;
        int frame = (int) Math.floorMod(frameIndex, (long) frames.size());
        drawCoverFrame(gui, lane, frames.get(frame), x, y, receptorSize);
    }

    public static int holdCoverEndFrames(int lane) {
        load();
        if (coverAtlases[lane] == null || coverEndAnims[lane] == null) return 0;
        return coverAtlases[lane].frames(coverEndAnims[lane]).size();
    }

    public static void drawHoldCoverEnd(GuiGraphics gui, int lane, int frameIndex, float x, float y, float receptorSize) {
        if (coverAtlases[lane] == null || coverEndAnims[lane] == null) return;
        var frames = coverAtlases[lane].frames(coverEndAnims[lane]);
        if (frameIndex < 0 || frameIndex >= frames.size()) return;
        drawCoverFrame(gui, lane, frames.get(frameIndex), x, y, receptorSize);
    }

    /**
     * Funkin (Strumline.hx) places covers with fixed pixel offsets from the
     * receptor: strums are 104px, covers drawn at 0.7 world scale, box shifted
     * (-12, -96) plus INITIAL_OFFSET. Converted to a center-based draw that's
     * (-12, +15.4) receptor-pixels off the receptor center.
     */
    private static void drawCoverFrame(GuiGraphics gui, int lane, SparrowAtlas.Frame f,
                                       float receptorX, float receptorY, float receptorSize) {
        applyAlpha(holdCoverAlpha);
        float g = receptorSize / 104f;
        float pixelScale = 0.7f * g * holdCoverScale;
        coverAtlases[lane].drawScaled(gui, f,
                receptorX - 12f * g + holdCoverX,
                receptorY + 15.4f * g + holdCoverY, pixelScale);
    }

    /** Number of splash animation variants for a lane (0 = no splashes available). */
    public static int splashVariants(int lane) {
        load();
        return splashAnims[lane] == null ? 0 : splashAnims[lane].size();
    }

    public static int splashFrameCount(int lane, int variant) {
        if (splashAtlas == null || splashAnims[lane] == null || splashAnims[lane].isEmpty()) return 0;
        return splashAtlas.frames(splashAnims[lane].get(variant % splashAnims[lane].size())).size();
    }

    public static void drawSplash(GuiGraphics gui, int lane, int variant, int frameIndex,
                                  float centerX, float centerY, float size) {
        if (splashAtlas == null || splashAnims[lane] == null || splashAnims[lane].isEmpty()) return;
        String anim = splashAnims[lane].get(variant % splashAnims[lane].size());
        var frames = splashAtlas.frames(anim);
        if (frameIndex < 0 || frameIndex >= frames.size()) return;
        applyAlpha(splashAlpha);
        SparrowAtlas.Frame f = frames.get(frameIndex);
        splashAtlas.drawScaled(gui, f, centerX + splashX, centerY + splashY,
                size * splashScale / Math.max(1, Math.max(f.frameW, f.frameH)), laneTex(splashRGB, lane));
    }

    private static ResourceLocation registerGenerated(String path, NativeImage image) {
        return registerGenerated(path, image, false);
    }

    private static ResourceLocation registerGenerated(String path, NativeImage image, boolean smooth) {
        ResourceLocation id = FnfMod.id(path);
        DynamicTexture tex = new DynamicTexture(image);
        Minecraft.getInstance().getTextureManager().register(id, tex);
        if (smooth) Textures.smooth(tex); // custom skin art (hold sheet) — arrows stay crisp
        return id;
    }

    /** 32x32 white arrow pointing up. outline=true draws only the border (receptor look). */
    private static NativeImage makeArrow(boolean outline) {
        int size = 32;
        NativeImage img = new NativeImage(size, size, true);
        boolean[][] mask = new boolean[size][size];
        // triangle head rows 2..17, stem rows 17..30
        for (int y = 2; y <= 17; y++) {
            int half = (y - 2);
            for (int x = 15 - half; x <= 16 + half; x++) {
                if (x >= 0 && x < size) mask[y][x] = true;
            }
        }
        for (int y = 17; y <= 29; y++) {
            for (int x = 11; x <= 20; x++) {
                mask[y][x] = true;
            }
        }
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (!mask[y][x]) continue;
                boolean edge = y == 0 || y == size - 1 || x == 0 || x == size - 1
                        || !mask[y - 1][x] || !mask[y + 1][x] || !mask[y][x - 1] || !mask[y][x + 1];
                int abgr;
                if (edge) {
                    abgr = 0xFF000000 | (0x30 << 16) | (0x30 << 8) | 0x30; // dark border
                } else {
                    abgr = outline ? 0x50FFFFFF : 0xFFFFFFFF;
                }
                img.setPixelRGBA(x, y, abgr);
            }
        }
        return img;
    }

    // ------------------------------------------------------------------

    public static void drawNote(GuiGraphics gui, int lane, float centerX, float centerY, float size) {
        load();
        applyAlpha(noteAlpha);
        if (noteAtlas != null && noteAnims[lane] != null) {
            noteAtlas.drawScaled(gui, noteAtlas.frame(noteAnims[lane], 0),
                    centerX + noteX, centerY + noteY,
                    size * noteScale / noteRefPx, laneTex(noteRGB, lane));
            return;
        }
        drawArrow(gui, arrowTexture, lane, centerX + noteX, centerY + noteY, size * noteScale,
                missedTint ? 0xFF808080 : LANE_COLORS[lane]);
    }

    /** state: 0 = static, 1 = pressed (no note), 2 = confirm (hit) */
    public static void drawReceptor(GuiGraphics gui, int lane, float centerX, float centerY, float size, int state) {
        load();
        applyAlpha(receptorAlpha);
        if (strumAtlas != null) {
            String anim = switch (state) {
                case 1 -> pressAnims[lane] != null ? pressAnims[lane] : receptorAnims[lane];
                case 2 -> confirmAnims[lane] != null ? confirmAnims[lane] : receptorAnims[lane];
                default -> receptorAnims[lane];
            };
            if (anim != null) {
                // Psych applies the RGB palette to press/confirm but leaves the static frame raw
                ResourceLocation rgb = state == 0 ? null : laneTex(strumRGB, lane);
                strumAtlas.drawScaled(gui, strumAtlas.frame(anim, state == 0 ? 0 : 1),
                        centerX + receptorX, centerY + receptorY,
                        size * receptorScale / strumRefPx, rgb);
                return;
            }
        }
        int color = switch (state) {
            case 1 -> 0xFF808080;
            case 2 -> LANE_COLORS[lane];
            default -> 0xFFB0B0B0;
        };
        drawArrow(gui, state == 0 ? arrowOutlineTexture : arrowTexture, lane,
                centerX + receptorX, centerY + receptorY,
                size * (state == 2 ? 1.1f : 1f), color);
    }

    /** tailAtTop = downscroll (the sustain's far end points up). */
    public static void drawHoldPiece(GuiGraphics gui, int lane, float centerX, float yTop, float yBottom,
                                     float size, boolean tailAtTop) {
        load();
        if (yBottom <= yTop) return;
        applyAlpha(sustainAlpha);
        centerX += sustainX;
        yTop += sustainY;
        yBottom += sustainY;

        HoldSprite piece = holdPieces[lane];
        if (piece == null) {
            float w = size * 0.36f * holdWidthScale;
            int alpha = (int) (255 * drawAlpha) << 24;
            int baseCol = missedTint ? 0x808080 : LANE_COLORS[lane];
            int color = alpha | (baseCol & 0xFFFFFF);
            int dim = alpha | (dimColor(baseCol) & 0xFFFFFF);
            gui.fill((int) (centerX - w / 2), (int) yTop, (int) (centerX + w / 2), (int) yBottom, dim);
            gui.fill((int) (centerX - w / 2) + 1, (int) yTop, (int) (centerX + w / 2) - 1, (int) yBottom, color);
            return;
        }

        HoldSprite end = holdEnds[lane];
        float w = size * 0.34f * holdWidthScale;
        float x = centerX - w / 2;
        float endH = end != null ? end.h() * (w / end.w()) : 0;
        float tileH = Math.max(1, piece.h() * (w / piece.w()));
        ResourceLocation rgbTex = laneTex(holdRGB, lane);

        RenderSystem.enableBlend();
        gui.enableScissor((int) x - 1, (int) yTop, (int) (x + w) + 1, (int) yBottom + 1);
        float bodyTop = tailAtTop ? yTop + endH : yTop;
        float bodyBottom = tailAtTop ? yBottom : yBottom - endH;
        // anchor the tile pattern to the tail end so the texture scrolls with the
        // chart instead of looking frozen while the trail shrinks into the receptor
        if (tailAtTop) {
            for (float y = bodyTop; y < bodyBottom; y += tileH) {
                blitSprite(gui, piece, x, y, w, tileH + 0.75f, true, rgbTex);
            }
        } else {
            for (float y = bodyBottom; y > bodyTop - tileH; y -= tileH) {
                blitSprite(gui, piece, x, y - tileH, w, tileH + 0.75f, false, rgbTex);
            }
        }
        if (end != null) {
            if (tailAtTop) {
                blitSprite(gui, end, x, yTop, w, endH, true, rgbTex);
            } else {
                blitSprite(gui, end, x, bodyBottom, w, endH, false, rgbTex);
            }
        }
        gui.disableScissor();
    }

    private static int dimColor(int argb) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        return 0xFF000000 | ((r / 2) << 16) | ((g / 2) << 8) | (b / 2);
    }

    private static void drawArrow(GuiGraphics gui, ResourceLocation tex, int lane,
                                  float centerX, float centerY, float size, int argb) {
        float r = ((argb >> 16) & 0xFF) / 255f;
        float g = ((argb >> 8) & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;
        float a = ((argb >>> 24) & 0xFF) / 255f * drawAlpha;

        gui.pose().pushPose();
        gui.pose().translate(centerX, centerY, 0);
        gui.pose().mulPose(Axis.ZP.rotationDegrees(LANE_ROTATION[lane]));
        float scale = size / 32f;
        gui.pose().scale(scale, scale, 1);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        gui.setColor(r, g, b, a);
        gui.blit(tex, -16, -16, 0, 0, 32, 32, 32, 32);
        gui.setColor(1f, 1f, 1f, 1f);
        gui.pose().popPose();
    }
}
