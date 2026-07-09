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
public class SparrowAtlas {

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
    private final Map<String, List<Frame>> animations = new LinkedHashMap<>();

    /** kept for CPU recoloring (owned by the DynamicTexture, read-only here) */
    private NativeImage image;

    private SparrowAtlas(ResourceLocation textureId, int w, int h) {
        this.textureId = textureId;
        this.texWidth = w;
        this.texHeight = h;
    }

    public NativeImage image() {
        return image;
    }

    /** Returns null on any failure (missing files, bad xml). */
    public static SparrowAtlas load(Path png, Path xml) {
        try {
            if (!Files.isRegularFile(png) || !Files.isRegularFile(xml)) return null;
            NativeImage image;
            try (InputStream in = Files.newInputStream(png)) {
                image = NativeImage.read(in);
            }
            ResourceLocation id = FnfMod.id("atlas/" + NEXT_ID.incrementAndGet());
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(image));

            SparrowAtlas atlas = new SparrowAtlas(id, image.getWidth(), image.getHeight());
            atlas.image = image;

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
                atlas.animations.computeIfAbsent(prefix, k -> new ArrayList<>()).add(f);
            }
            return atlas;
        } catch (Exception e) {
            FnfMod.LOGGER.warn("Failed to load sparrow atlas {} / {}: {}", png, xml, e.toString());
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

    public Frame frame(String prefix, int index) {
        List<Frame> list = animations.get(prefix);
        if (list == null || list.isEmpty()) return null;
        return list.get(Math.floorMod(index, list.size()));
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
        if (tinted) gui.setColor(tintR, tintG, tintB, globalAlpha);
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
        if (tinted) gui.setColor(1f, 1f, 1f, 1f);
    }
}
