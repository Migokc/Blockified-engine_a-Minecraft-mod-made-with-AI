package com.fnfmod.client.render;

import com.fnfmod.FnfMod;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Loads a Friday Night Funkin' style Sparrow atlas: a PNG sprite sheet plus
 * the Adobe Animate XML (&lt;TextureAtlas&gt;&lt;SubTexture .../&gt;) that FNF mods ship.
 */
public class SparrowAtlas implements AutoCloseable {

    public static class Frame {
        public int x, y, w, h;
        public int frameX, frameY, frameW, frameH;
        /** sprite is stored rotated 90° clockwise in the sheet (Adobe Animate packing) */
        public boolean rotated;
        public String name;
    }

    private static final AtomicInteger NEXT_ID = new AtomicInteger();

    private final ResourceLocation textureId;
    private final int texWidth;
    private final int texHeight;
    /** animation prefix -> ordered frames ("purple0000", "purple0001" -> key "purple") */
    private Map<String, List<Frame>> animations = new LinkedHashMap<>();
    /** Every XML frame in source order, used by Psych's prefix/indices APIs. */
    private List<Frame> allFrames = new ArrayList<>();

    /** kept for CPU recoloring (owned by the DynamicTexture, read-only here) */
    private NativeImage image;
    private DynamicTexture dynamicTexture;
    private static final class MaskHolder {
        ResourceLocation id;
        DynamicTexture texture;
    }
    /** Shared by cached atlas views; created only when an object actually has a border. */
    private MaskHolder mask = new MaskHolder();
    /** Shared-cache views release a reference instead of destroying the backing texture. */
    private Runnable sharedRelease;
    private boolean closed;

    protected SparrowAtlas(ResourceLocation textureId, int w, int h) {
        this.textureId = textureId;
        this.texWidth = w;
        this.texHeight = h;
    }

    public NativeImage image() {
        return image;
    }

    /**
     * Background-safe decode result: the PNG pixels and parsed XML frames, with no
     * GL objects yet. Pass to {@link #finish(Decoded)} on the render thread to upload.
     */
    public static final class Decoded {
        private final NativeImage image;
        private final List<Frame> allFrames;
        private final Map<String, List<Frame>> animations;

        private Decoded(NativeImage image, List<Frame> allFrames, Map<String, List<Frame>> animations) {
            this.image = image;
            this.allFrames = allFrames;
            this.animations = animations;
        }

        /** Frees the decoded pixels if this atlas is never finished (e.g. cancelled). */
        public void close() {
            try { image.close(); } catch (Exception ignored) {}
        }

        public long estimatedBytes() {
            return Math.max(1L, image.getWidth()) * Math.max(1L, image.getHeight()) * 4L;
        }
    }

    /** Returns null on any failure (missing files, bad xml). */
    public static SparrowAtlas load(Path png, Path xml) {
        return finish(decode(png, xml));
    }

    /**
     * Reads and parses the atlas (PNG decode + XML parse) with no GL work, so it can
     * run on a worker thread. Returns null on failure.
     */
    public static Decoded decode(Path png, Path xml) {
        NativeImage image = null;
        try {
            if (png == null || xml == null || !Files.isRegularFile(png) || !Files.isRegularFile(xml)) return null;
            try (InputStream in = Files.newInputStream(png)) {
                image = NativeImage.read(in);
            }
            List<Frame> allFrames = new ArrayList<>();
            Map<String, List<Frame>> animations = new LinkedHashMap<>();

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc;
            try (InputStream in = Files.newInputStream(xml)) {
                doc = factory.newDocumentBuilder().parse(in);
            }
            NodeList subTextures = doc.getElementsByTagName("SubTexture");
            for (int i = 0; i < subTextures.getLength(); i++) {
                Element el = (Element) subTextures.item(i);
                Frame f = new Frame();
                f.name = el.getAttribute("name");
                f.x = intAttr(el, "x");
                f.y = intAttr(el, "y");
                f.w = intAttr(el, "width");
                f.h = intAttr(el, "height");
                f.frameX = intAttr(el, "frameX");
                f.frameY = intAttr(el, "frameY");
                f.rotated = "true".equalsIgnoreCase(el.getAttribute("rotated"));
                f.frameW = el.hasAttribute("frameWidth") ? intAttr(el, "frameWidth") : f.w;
                f.frameH = el.hasAttribute("frameHeight") ? intAttr(el, "frameHeight") : f.h;
                String prefix = stripFrameNumber(f.name);
                allFrames.add(f);
                animations.computeIfAbsent(prefix, k -> new ArrayList<>()).add(f);
            }
            return new Decoded(image, allFrames, animations);
        } catch (Exception e) {
            if (image != null) image.close();
            FnfMod.LOGGER.warn("Failed to decode sparrow atlas {} / {}: {}", png, xml, e.toString());
            return null;
        }
    }

    /** Uploads a decoded atlas to a GL texture. Must run on the render thread. */
    public static SparrowAtlas finish(Decoded decoded) {
        if (decoded == null) return null;
        NativeImage image = decoded.image;
        DynamicTexture texture = null;
        ResourceLocation id = null;
        boolean registered = false;
        try {
            id = FnfMod.id("atlas/" + NEXT_ID.incrementAndGet());
            texture = new DynamicTexture(image);
            Minecraft.getInstance().getTextureManager().register(id, texture);
            registered = true;
            Textures.smooth(texture); // antialias custom skin art (default skin = procedural arrows, untouched)

            SparrowAtlas atlas = new SparrowAtlas(id, image.getWidth(), image.getHeight());
            atlas.image = image;
            atlas.dynamicTexture = texture;
            atlas.allFrames.addAll(decoded.allFrames);
            atlas.animations.putAll(decoded.animations);
            return atlas;
        } catch (Exception e) {
            if (registered && id != null) {
                Minecraft.getInstance().getTextureManager().release(id);
            } else if (texture != null) {
                texture.close();
            } else {
                image.close();
            }
            FnfMod.LOGGER.warn("Failed to upload sparrow atlas: {}", e.toString());
            return null;
        }
    }

    private static int intAttr(Element el, String name) {
        try {
            String v = el.getAttribute(name);
            return v.isEmpty() ? 0 : (int) Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String stripFrameNumber(String name) {
        int digits = 0;
        int i = name.length();
        while (i > 0 && Character.isDigit(name.charAt(i - 1))) {
            i--;
            digits++;
        }
        // Adobe Animate frame index is a 4-digit suffix; keep any leading digits
        // (e.g. a variant number) so "note splash red 10000" -> "note splash red 1"
        int strip = digits >= 4 ? 4 : digits;
        String prefix = name.substring(0, name.length() - strip);
        return prefix.replaceAll("\\s+", " ").trim();
    }

    /** All animation prefixes in this atlas. */
    public java.util.Set<String> animationNames() {
        return animations.keySet();
    }

    public ResourceLocation texture() {
        return textureId;
    }

    public int width() {
        return texWidth;
    }

    public int height() {
        return texHeight;
    }

    public long estimatedBytes() {
        return Math.max(1L, texWidth) * Math.max(1L, texHeight) * 4L;
    }

    /** White RGB with the atlas' original alpha, suitable for arbitrary-colour outlines. */
    public ResourceLocation silhouetteTexture() {
        if (mask.id != null) return mask.id;
        if (image == null) return textureId;
        try {
            NativeImage silhouette = new NativeImage(texWidth, texHeight, true);
            for (int y = 0; y < texHeight; y++) {
                for (int x = 0; x < texWidth; x++) {
                    int alpha = image.getPixelRGBA(x, y) >>> 24;
                    silhouette.setPixelRGBA(x, y, alpha << 24 | 0xFFFFFF);
                }
            }
            mask.texture = new DynamicTexture(silhouette);
            mask.id = FnfMod.id("sprite_outline/" + NEXT_ID.incrementAndGet());
            Minecraft.getInstance().getTextureManager().register(mask.id, mask.texture);
        } catch (Throwable error) {
            mask.id = textureId;
            mask.texture = null;
        }
        return mask.id;
    }

    /** Creates an independently closeable view over one cache-owned atlas. */
    SparrowAtlas sharedView(Runnable release) {
        SparrowAtlas view = new SparrowAtlas(textureId, texWidth, texHeight);
        view.image = image;
        view.dynamicTexture = dynamicTexture;
        view.mask = mask;
        view.animations = animations;
        view.allFrames = allFrames;
        view.sharedRelease = release;
        return view;
    }

    /** Applies Psych's per-sprite antialiasing flag to this atlas. */
    public void setAntialiasing(boolean enabled) {
        if (dynamicTexture == null) return;
        try {
            dynamicTexture.setFilter(enabled, false);
            if (mask.texture != null) mask.texture.setFilter(enabled, false);
        } catch (Throwable ignored) {
            // Filtering must never make a character fail to load.
        }
    }

    public boolean hasAnimation(String prefix) {
        return animations.containsKey(prefix);
    }

    /** Finds the first existing animation among candidates, else null. */
    public String findAnimation(String... candidates) {
        for (String c : candidates) {
            if (animations.containsKey(c)) return c;
        }
        return null;
    }

    public List<Frame> frames(String prefix) {
        return animations.getOrDefault(prefix, List.of());
    }

    /** Psych/Flixel addByPrefix matches the complete XML frame name, not only our normalized key. */
    public List<Frame> framesByPrefix(String prefix) {
        if (prefix == null) return List.of();
        return allFrames.stream().filter(frame -> frame.name.startsWith(prefix)).toList();
    }

    /** Adobe Animate atlases override this to expose a named library symbol. */
    public List<Frame> framesBySymbol(String symbol) {
        return framesByPrefix(symbol);
    }

    /** Dynamic atlas implementations may upload the selected frame here. */
    public void prepareFrame(Frame frame) {
    }

    /**
     * Resolves HaxeFlixel/Psych {@code addByIndices}: each value names the numeric
     * suffix immediately after the prefix. It is not an offset into every broad
     * prefix match. Missing numbers are deliberately skipped, matching Flixel.
     */
    public List<Frame> framesByIndices(String prefix, List<Integer> indices) {
        if (prefix == null || indices == null || indices.isEmpty()) return List.of();
        Map<Integer, Frame> numbered = new LinkedHashMap<>();
        for (Frame frame : allFrames) {
            if (frame.name == null || !frame.name.startsWith(prefix)) continue;
            int at = prefix.length();
            if (at >= frame.name.length() || !Character.isDigit(frame.name.charAt(at))) continue;
            long value = 0;
            while (at < frame.name.length() && Character.isDigit(frame.name.charAt(at))) {
                value = value * 10 + frame.name.charAt(at++) - '0';
                if (value > Integer.MAX_VALUE) break;
            }
            if (value <= Integer.MAX_VALUE) numbered.putIfAbsent((int) value, frame);
        }
        List<Frame> result = new ArrayList<>(indices.size());
        for (Integer index : indices) {
            if (index == null) continue;
            Frame frame = numbered.get(index);
            if (frame != null) result.add(frame);
        }
        return List.copyOf(result);
    }

    public List<Frame> allFrames() {
        return List.copyOf(allFrames);
    }

    public Frame frame(String prefix, int index) {
        List<Frame> list = animations.get(prefix);
        if (list == null || list.isEmpty()) return null;
        return list.get(Math.floorMod(index, list.size()));
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (sharedRelease != null) {
            Runnable release = sharedRelease;
            sharedRelease = null;
            image = null;
            dynamicTexture = null;
            release.run();
            return;
        }
        if (dynamicTexture != null) {
            if (mask.texture != null && mask.id != null && !mask.id.equals(textureId)) {
                Minecraft.getInstance().getTextureManager().release(mask.id);
                mask.texture = null;
                mask.id = null;
            }
            Minecraft.getInstance().getTextureManager().release(textureId);
            dynamicTexture = null;
        }
        image = null;
    }

    /**
     * Draws a frame with its logical size scaled so frameWidth == targetSize.
     * Sparrow frameX/frameY trimming offsets are applied.
     */
    public void draw(GuiGraphics gui, Frame f, float centerX, float centerY, float targetSize) {
        if (f == null) return;
        drawScaled(gui, f, centerX, centerY,
                targetSize / Math.max(1, Math.max(f.frameW, f.frameH)));
    }

    /**
     * Draws a frame at a fixed pixel scale (gui units per texture pixel), so
     * frames with different trim padding keep consistent sizes relative to
     * each other — FNF sizes sprites by art pixels, not by their logical boxes.
     */
    public void drawScaled(GuiGraphics gui, Frame f, float centerX, float centerY, float scale) {
        drawScaled(gui, f, centerX, centerY, scale, null);
    }

    /** Global alpha multiplier for atlas draws (used to fade opponent notes in middlescroll). */
    public static float globalAlpha = 1f;
    /** Global RGB tint (1,1,1 = none). Used to gray out missed long notes. */
    public static float tintR = 1f, tintG = 1f, tintB = 1f;

    /** textureOverride: draw the same frame from a recolored copy of this sheet. */
    public void drawScaled(GuiGraphics gui, Frame f, float centerX, float centerY, float scale,
                           ResourceLocation textureOverride) {
        if (f == null) return;
        float logicalW = Math.max(1, f.frameW);
        float logicalH = Math.max(1, f.frameH);

        // top-left of the logical box centered, then the trim offset
        float drawX = centerX - logicalW * scale / 2f + (-f.frameX) * scale;
        float drawY = centerY - logicalH * scale / 2f + (-f.frameY) * scale;

        // vanilla fill() batches disable blending when they flush — force it back on
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        boolean tinted = globalAlpha < 1f || tintR < 1f || tintG < 1f || tintB < 1f;
        if (tinted) {
            // setColor is shader state, while blit may be buffered. Flush on both
            // sides so a later reset cannot turn a fractional character alpha
            // back into fully opaque rendering before its vertices are drawn.
            gui.flush();
            gui.setColor(tintR, tintG, tintB, globalAlpha);
        }
        gui.pose().pushPose();
        gui.pose().translate(drawX, drawY, 0);
        gui.pose().scale(scale, scale, 1);
        if (f.rotated) {
            // stored rotated 90° clockwise: un-rotate so the region lands in a (h x w) box
            gui.pose().translate(0, f.w, 0);
            gui.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(-90));
        }
        gui.blit(textureOverride != null ? textureOverride : textureId,
                0, 0, f.x, f.y, f.w, f.h, texWidth, texHeight);
        gui.pose().popPose();
        if (tinted) {
            gui.flush();
            gui.setColor(1f, 1f, 1f, 1f);
        }
    }
}
