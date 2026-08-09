package com.fnfmod.client.render;

import com.fnfmod.FnfMod;
import com.fnfmod.song.SongLibrary;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * FNF health icons. An entry is either a character json (Psych/V-Slice
 * &lt;name&gt;.json, whose "healthicon" names the separate icon png and whose
 * "healthbar_colors" gives the bar color) or a bare icon png. The icon png is a
 * horizontal strip of square frames: frame 0 = normal, frame 1 = losing.
 */
public final class IconLibrary {

    /** A selectable entry: which png to draw and (optionally) a bar color. */
    private static final class Source {
        Path png;
        int barColor = -1;
    }

    private static final class Icon {
        ResourceLocation tex;
        int texW, texH, frameSize, frames;
        int barColor = -1;
    }

    private static final Map<String, Source> sources = new LinkedHashMap<>();
    private static final Map<String, Icon> loaded = new LinkedHashMap<>();
    private static int lastGen = -1;

    private IconLibrary() {}

    /** Refreshes from the song library if it has rescanned since we last synced. */
    private static synchronized void syncIfStale() {
        if (SongLibrary.rescanGeneration() != lastGen) rescan();
    }

    public static synchronized void rescan() {
        lastGen = SongLibrary.rescanGeneration();
        Map<String, Source> nextSources = new LinkedHashMap<>();

        Path dir = SongLibrary.iconsDir();
        Map<String, Path> pngs = new LinkedHashMap<>();   // stem -> png
        List<Path> jsons = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(Files::isRegularFile).forEach(f -> {
                    String n = f.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (n.endsWith(".png")) pngs.put(pngStem(f), f);
                    else if (n.endsWith(".json")) jsons.add(f);
                });
            } catch (Exception e) {
                FnfMod.LOGGER.warn("Failed to scan icons dir: {}", e.toString());
            }
        }
        // song/mod folders can contribute icon pngs too
        for (var e : SongLibrary.getExtraIcons().entrySet()) {
            pngs.putIfAbsent(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
        }

        // character jsons: entry named by the json, icon + color from inside it.
        // remember each png's color so the bare png entry gets it too.
        Map<Path, Integer> pngColor = new LinkedHashMap<>();
        for (Path json : jsons) {
            try {
                JsonObject o = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
                String iconName = optStr(o, "healthicon");
                if (iconName == null) continue;
                Path png = resolvePng(iconName, pngs, json.getParent());
                if (png == null) continue;
                int color = readColor(o);
                Source s = new Source();
                s.png = png;
                s.barColor = color;
                String entry = stem(json.getFileName().toString()).toLowerCase(Locale.ROOT);
                nextSources.put(entry, s);
                if (color >= 0) pngColor.put(png, color);
            } catch (Exception ignored) {}
        }

        // remaining pngs become bare entries, inheriting any color a character json gave them
        for (var e : pngs.entrySet()) {
            if (!nextSources.containsKey(e.getKey())) {
                Source s = new Source();
                s.png = e.getValue();
                s.barColor = pngColor.getOrDefault(e.getValue(), -1);
                nextSources.put(e.getKey(), s);
            }
        }
        if (sameSources(sources, nextSources)) return;
        releaseLoadedTextures();
        sources.clear();
        sources.putAll(nextSources);
        loaded.clear();
        byPath.clear();
    }

    private static boolean sameSources(Map<String, Source> left, Map<String, Source> right) {
        if (!left.keySet().equals(right.keySet())) return false;
        for (var entry : left.entrySet()) {
            Source other = right.get(entry.getKey());
            if (other == null || !java.util.Objects.equals(entry.getValue().png, other.png)
                    || entry.getValue().barColor != other.barColor) return false;
        }
        return true;
    }

    private static void releaseLoadedTextures() {
        var textureManager = Minecraft.getInstance().getTextureManager();
        var textureIds = new HashSet<ResourceLocation>();
        for (Icon icon : loaded.values()) {
            if (icon != null && icon.tex != null) textureIds.add(icon.tex);
        }
        for (Icon icon : byPath.values()) {
            if (icon != null && icon.tex != null) textureIds.add(icon.tex);
        }
        for (ResourceLocation id : textureIds) textureManager.release(id);
    }

    private static String pngStem(Path png) {
        String n = png.getFileName().toString();
        n = n.substring(0, n.length() - 4);
        if (n.toLowerCase(Locale.ROOT).startsWith("icon-")) n = n.substring(5);
        return n.toLowerCase(Locale.ROOT);
    }

    private static String stem(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return (dot > 0 ? fileName.substring(0, dot) : fileName);
    }

    /** Resolves a healthicon name to a png (icons index, then next to the json). */
    private static Path resolvePng(String iconName, Map<String, Path> pngs, Path jsonDir) {
        String key = iconName.toLowerCase(Locale.ROOT);
        if (key.startsWith("icon-")) key = key.substring(5);
        Path p = pngs.get(key);
        if (p != null) return p;
        if (jsonDir != null) {
            for (String cand : new String[]{iconName + ".png", "icon-" + iconName + ".png"}) {
                Path near = jsonDir.resolve(cand);
                if (Files.isRegularFile(near)) return near;
            }
        }
        return null;
    }

    private static int readColor(JsonObject o) {
        for (String key : new String[]{"healthbar_colors", "health_bar_colors", "healthBarColors"}) {
            if (o.has(key) && o.get(key).isJsonArray()) {
                var a = o.getAsJsonArray(key);
                if (a.size() >= 3) {
                    try {
                        return (clamp(a.get(0).getAsInt()) << 16) | (clamp(a.get(1).getAsInt()) << 8) | clamp(a.get(2).getAsInt());
                    } catch (Exception ignored) {}
                }
            }
        }
        for (String key : new String[]{"healthBarColor", "healthbar_color", "iconColor"}) {
            if (o.has(key) && o.get(key).isJsonPrimitive()) {
                String s = o.get(key).getAsString().replace("#", "").trim();
                if (s.matches("[0-9a-fA-F]{6}")) return Integer.parseInt(s, 16);
            }
        }
        return -1;
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static String optStr(JsonObject o, String key) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static synchronized List<String> list() {
        syncIfStale();
        return new ArrayList<>(sources.keySet());
    }

    private static synchronized Icon get(String name) {
        if (name == null || name.isEmpty()) return null;
        syncIfStale();
        String key = name.toLowerCase(Locale.ROOT);
        if (loaded.containsKey(key)) return loaded.get(key);
        Source src = sources.get(key);
        if (src == null) {
            loaded.put(key, null);
            return null;
        }
        NativeImage img = null;
        DynamicTexture texture = null;
        ResourceLocation id = null;
        boolean registered = false;
        try (InputStream in = Files.newInputStream(src.png)) {
            img = NativeImage.read(in);
            id = FnfMod.id("icon/" + key.replaceAll("[^a-z0-9_]", "_"));
            texture = new DynamicTexture(img);
            Minecraft.getInstance().getTextureManager().register(id, texture);
            registered = true;
            Icon icon = new Icon();
            icon.tex = id;
            icon.texW = img.getWidth();
            icon.texH = img.getHeight();
            icon.frameSize = img.getHeight();
            icon.frames = Math.max(1, Math.round((float) img.getWidth() / img.getHeight()));
            icon.barColor = src.barColor;
            loaded.put(key, icon);
            return icon;
        } catch (Exception e) {
            if (registered && id != null) Minecraft.getInstance().getTextureManager().release(id);
            else if (texture != null) texture.close();
            else if (img != null) img.close();
            FnfMod.LOGGER.warn("Failed to load icon {}: {}", name, e.toString());
            loaded.put(key, null);
            return null;
        }
    }

    public static boolean has(String name) {
        return get(name) != null;
    }

    /** Icons loaded directly from an absolute file path (per-mod, avoids name clashes). */
    private static final Map<String, Icon> byPath = new LinkedHashMap<>();

    private static synchronized Icon getByPath(String path) {
        if (path == null || path.isEmpty()) return null;
        if (byPath.containsKey(path)) return byPath.get(path);
        Path p = Path.of(path);
        Icon icon = null;
        if (Files.isRegularFile(p)) {
            NativeImage img = null;
            DynamicTexture texture = null;
            ResourceLocation id = null;
            boolean registered = false;
            try (InputStream in = Files.newInputStream(p)) {
                img = NativeImage.read(in);
                id = FnfMod.id("iconpath/" + Integer.toHexString(path.hashCode()));
                texture = new DynamicTexture(img);
                Minecraft.getInstance().getTextureManager().register(id, texture);
                registered = true;
                icon = new Icon();
                icon.tex = id;
                icon.texW = img.getWidth();
                icon.texH = img.getHeight();
                icon.frameSize = img.getHeight();
                icon.frames = Math.max(1, Math.round((float) img.getWidth() / img.getHeight()));
            } catch (Exception e) {
                if (registered && id != null) Minecraft.getInstance().getTextureManager().release(id);
                else if (texture != null) texture.close();
                else if (img != null) img.close();
                FnfMod.LOGGER.warn("Failed to load icon file {}: {}", path, e.toString());
            }
        }
        byPath.put(path, icon);
        return icon;
    }

    public static boolean hasFile(String path) {
        return getByPath(path) != null;
    }

    public static void drawFile(GuiGraphics gui, String path, int frame, float cx, float cy, float size, boolean flipX) {
        drawIcon(gui, getByPath(path), frame, cx, cy, size, flipX);
    }

    /** Health bar color from the character json, or -1 if none. */
    public static int barColor(String name) {
        Icon icon = get(name);
        return icon == null ? -1 : icon.barColor;
    }

    public static void draw(GuiGraphics gui, String name, int frame, float cx, float cy, float size) {
        draw(gui, name, frame, cx, cy, size, false);
    }

    /**
     * Draws an icon centered at (cx, cy). flipX mirrors it horizontally by
     * flipping the sampled UV region (negative geometry scale would get the
     * quad culled and vanish).
     */
    public static void draw(GuiGraphics gui, String name, int frame, float cx, float cy, float size, boolean flipX) {
        drawIcon(gui, get(name), frame, cx, cy, size, flipX);
    }

    private static void drawIcon(GuiGraphics gui, Icon icon, int frame, float cx, float cy, float size, boolean flipX) {
        if (icon == null) return;
        int f = Math.min(frame, icon.frames - 1);
        int fs = icon.frameSize;
        int drawSize = Math.max(1, Math.round(size));
        int x = Math.round(cx - drawSize / 2f);
        int y = Math.round(cy - drawSize / 2f);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        float uOffset = flipX ? (f + 1) * fs : f * fs;
        int uWidth = flipX ? -fs : fs;
        gui.blit(icon.tex, x, y, drawSize, drawSize, uOffset, 0f, uWidth, fs, icon.texW, icon.texH);
    }
}
