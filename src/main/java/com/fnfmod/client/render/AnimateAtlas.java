package com.fnfmod.client.render;

import com.fnfmod.FnfMod;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Adobe Animate / Better Texture Atlas loader used by modern FNF (V-Slice).
 *
 * <p>The format stores a character as nested timelines rather than one rectangle per
 * frame. This class resolves those timelines on demand into one reusable GPU texture;
 * it therefore supports the format without permanently expanding every animation
 * frame in memory.</p>
 */
public final class AnimateAtlas extends SparrowAtlas {
    private static final AtomicInteger NEXT_ID = new AtomicInteger();
    private static final int MAX_SHEETS = 32;
    private static final int MAX_TEXTURE_SIDE = 8192;
    private static final int MAX_OUTPUT_SIDE = 4096;
    private static final int MAX_TIMELINE_FRAMES = 20_000;
    private static final int MAX_RECURSION = 64;

    private record Matrix(double a, double b, double c, double d, double tx, double ty) {
        static final Matrix IDENTITY = new Matrix(1, 0, 0, 1, 0, 0);

        /** Applies child first, then parent (the same order as Flash Matrix.concat). */
        static Matrix childThenParent(Matrix child, Matrix parent) {
            return new Matrix(
                    parent.a * child.a + parent.c * child.b,
                    parent.b * child.a + parent.d * child.b,
                    parent.a * child.c + parent.c * child.d,
                    parent.b * child.c + parent.d * child.d,
                    parent.a * child.tx + parent.c * child.ty + parent.tx,
                    parent.b * child.tx + parent.d * child.ty + parent.ty);
        }

        double x(double x, double y) { return a * x + c * y + tx; }
        double y(double x, double y) { return b * x + d * y + ty; }
    }

    private record Sprite(String name, BufferedImage image) {
        int width() { return image.getWidth(); }
        int height() { return image.getHeight(); }
    }

    private sealed interface Element permits AtlasElement, SymbolElement {}
    private record AtlasElement(String name, Matrix matrix) implements Element {}
    private record SymbolElement(String name, int firstFrame, String loop, String type,
                                 Matrix matrix, double alpha) implements Element {}
    private record KeyFrame(int index, int duration, String name, List<Element> elements) {}
    private record Layer(List<KeyFrame> frames) {}
    private record Timeline(String name, List<Layer> layers, int frameCount) {}
    private record Part(Sprite sprite, Matrix matrix, double alpha) {}
    private record FrameRef(Timeline timeline, int index) {}
    private record Bounds(double minX, double minY, double maxX, double maxY) {
        int width() { return Math.max(1, (int) Math.ceil(maxX) - (int) Math.floor(minX)); }
        int height() { return Math.max(1, (int) Math.ceil(maxY) - (int) Math.floor(minY)); }
    }

    private final ResourceLocation textureId;
    private final NativeImage pixels;
    private DynamicTexture texture;
    private final BufferedImage canvas;
    private final Timeline main;
    private final Map<String, Timeline> symbols;
    private final Map<String, Sprite> sprites;
    private final IdentityHashMap<Frame, FrameRef> frameRefs = new IdentityHashMap<>();
    private final List<Frame> allFrames = new ArrayList<>();
    private final Map<String, List<Frame>> labels = new LinkedHashMap<>();
    private final int width;
    private final int height;
    private final double originX;
    private final double originY;
    private boolean antialiasing = true;
    private FrameRef prepared;
    private ResourceLocation maskId;
    private NativeImage maskPixels;
    private DynamicTexture maskTexture;
    private FrameRef maskPrepared;
    private boolean closed;

    private AnimateAtlas(ResourceLocation id, int width, int height, double originX, double originY,
                         NativeImage pixels, DynamicTexture texture, Timeline main,
                         Map<String, Timeline> symbols, Map<String, Sprite> sprites) {
        super(id, width, height);
        this.textureId = id;
        this.width = width;
        this.height = height;
        this.originX = originX;
        this.originY = originY;
        this.pixels = pixels;
        this.texture = texture;
        this.canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        this.main = main;
        this.symbols = symbols;
        this.sprites = sprites;
        buildFrames();
    }

    /** Loads a folder containing Animation.json and spritemapN.json/png files. */
    public static AnimateAtlas load(Path folder) {
        if (folder == null) return null;
        Path normalized = folder.toAbsolutePath().normalize();
        Path animationJson = normalized.resolve("Animation.json");
        if (!Files.isRegularFile(animationJson)) return null;
        ResourceLocation id = null;
        DynamicTexture texture = null;
        NativeImage pixels = null;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(animationJson)).getAsJsonObject();
            Map<String, Sprite> sprites = loadSprites(normalized);
            if (sprites.isEmpty()) return null;

            JsonObject animation = object(root, "AN");
            Timeline main = parseTimeline(string(animation, "SN", string(animation, "N", "main")),
                    object(animation, "TL"));
            if (main.frameCount <= 0) return null;

            Map<String, Timeline> symbols = new LinkedHashMap<>();
            JsonObject dictionary = object(root, "SD");
            for (JsonElement entry : array(dictionary, "S")) {
                if (!entry.isJsonObject()) continue;
                JsonObject symbol = entry.getAsJsonObject();
                String name = string(symbol, "SN", "");
                if (!name.isBlank()) symbols.put(name, parseTimeline(name, object(symbol, "TL")));
            }

            Bounds bounds = wholeBounds(main, symbols, sprites);
            int width = bounds.width();
            int height = bounds.height();
            if (width > MAX_OUTPUT_SIDE || height > MAX_OUTPUT_SIDE) {
                throw new IllegalArgumentException("composed frame is too large: " + width + "x" + height);
            }
            pixels = new NativeImage(width, height, true);
            texture = new DynamicTexture(pixels);
            texture.setFilter(true, false);
            id = FnfMod.id("animate_atlas/" + NEXT_ID.incrementAndGet());
            Minecraft.getInstance().getTextureManager().register(id, texture);
            return new AnimateAtlas(id, width, height, Math.floor(bounds.minX), Math.floor(bounds.minY),
                    pixels, texture, main, symbols, sprites);
        } catch (Exception error) {
            if (id != null) Minecraft.getInstance().getTextureManager().release(id);
            else if (texture != null) texture.close();
            else if (pixels != null) pixels.close();
            FnfMod.LOGGER.warn("Failed to load Animate atlas {}: {}", normalized, error.toString());
            return null;
        }
    }

    /** Resolves V-Slice library paths such as {@code shared:characters/bf}. */
    public static Path resolveFolder(Path definitionJson, String rawAssetPath) {
        if (definitionJson == null || rawAssetPath == null || rawAssetPath.isBlank()) return null;
        String value = rawAssetPath.trim().replace('\\', '/');
        String library = "";
        int colon = value.indexOf(':');
        if (colon > 0) {
            library = value.substring(0, colon);
            value = value.substring(colon + 1);
        }
        while (value.startsWith("/")) value = value.substring(1);
        Path cursor = definitionJson.toAbsolutePath().normalize().getParent();
        Path assets = null;
        while (cursor != null) {
            if (cursor.getFileName() != null && cursor.getFileName().toString().equalsIgnoreCase("assets")) {
                assets = cursor;
                break;
            }
            cursor = cursor.getParent();
        }
        List<Path> candidates = new ArrayList<>();
        if (assets != null) {
            if (!library.isBlank()) candidates.add(assets.resolve(library).resolve("images").resolve(value));
            candidates.add(assets.resolve("shared/images").resolve(value));
            candidates.add(assets.resolve("images").resolve(value));
            candidates.add(assets.resolve(value));
        }
        Path parent = definitionJson.getParent();
        if (parent != null) {
            candidates.add(parent.resolve(value));
            candidates.add(parent.resolve("images").resolve(value));
            Path mod = parent.getParent();
            if (mod != null) candidates.add(mod.resolve("images").resolve(value));
        }
        for (Path candidate : candidates) {
            Path normalized = candidate.normalize();
            if (Files.isDirectory(normalized) && Files.isRegularFile(normalized.resolve("Animation.json"))) {
                return normalized;
            }
        }
        return null;
    }

    private static Map<String, Sprite> loadSprites(Path folder) throws Exception {
        List<Path> maps;
        try (var stream = Files.list(folder)) {
            maps = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).matches("spritemap\\d*\\.json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .limit(MAX_SHEETS).toList();
        }
        Map<String, Sprite> result = new LinkedHashMap<>();
        for (Path mapPath : maps) {
            JsonObject atlas = object(JsonParser.parseString(Files.readString(mapPath)).getAsJsonObject(), "ATLAS");
            JsonObject meta = object(atlas, "meta");
            String imageName = string(meta, "image", "");
            if (imageName.isBlank()) continue;
            Path imagePath = folder.resolve(imageName).normalize();
            if (!imagePath.startsWith(folder) || !Files.isRegularFile(imagePath)) continue;
            BufferedImage sheet = ImageIO.read(imagePath.toFile());
            if (sheet == null || sheet.getWidth() > MAX_TEXTURE_SIDE || sheet.getHeight() > MAX_TEXTURE_SIDE) {
                throw new IllegalArgumentException("invalid spritemap image " + imageName);
            }
            for (JsonElement item : array(atlas, "SPRITES")) {
                if (!item.isJsonObject()) continue;
                JsonObject sprite = object(item.getAsJsonObject(), "SPRITE");
                String name = string(sprite, "name", "");
                int x = integer(sprite, "x", -1), y = integer(sprite, "y", -1);
                int w = integer(sprite, "w", 0), h = integer(sprite, "h", 0);
                if (name.isBlank() || x < 0 || y < 0 || w <= 0 || h <= 0
                        || x + w > sheet.getWidth() || y + h > sheet.getHeight()) continue;
                BufferedImage source = sheet.getSubimage(x, y, w, h);
                if (bool(sprite, "rotated", false)) source = unrotate(source);
                result.putIfAbsent(name, new Sprite(name, source));
            }
        }
        return result;
    }

    private static BufferedImage unrotate(BufferedImage source) {
        BufferedImage result = new BufferedImage(source.getHeight(), source.getWidth(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = result.createGraphics();
        graphics.translate(0, source.getWidth());
        graphics.rotate(-Math.PI / 2);
        graphics.drawImage(source, 0, 0, null);
        graphics.dispose();
        return result;
    }

    private static Timeline parseTimeline(String name, JsonObject object) {
        List<Layer> layers = new ArrayList<>();
        int count = 0;
        for (JsonElement layerElement : array(object, "L")) {
            if (!layerElement.isJsonObject()) continue;
            List<KeyFrame> frames = new ArrayList<>();
            for (JsonElement frameElement : array(layerElement.getAsJsonObject(), "FR")) {
                if (!frameElement.isJsonObject()) continue;
                JsonObject frame = frameElement.getAsJsonObject();
                int index = Math.max(0, integer(frame, "I", 0));
                int duration = Math.max(1, integer(frame, "DU", 1));
                count = Math.max(count, index + duration);
                List<Element> elements = new ArrayList<>();
                for (JsonElement element : array(frame, "E")) {
                    if (!element.isJsonObject()) continue;
                    JsonObject wrapper = element.getAsJsonObject();
                    if (wrapper.has("ASI") && wrapper.get("ASI").isJsonObject()) {
                        JsonObject atlas = wrapper.getAsJsonObject("ASI");
                        elements.add(new AtlasElement(string(atlas, "N", ""), matrix(atlas)));
                    } else if (wrapper.has("SI") && wrapper.get("SI").isJsonObject()) {
                        JsonObject symbol = wrapper.getAsJsonObject("SI");
                        elements.add(new SymbolElement(string(symbol, "SN", ""),
                                integer(symbol, "FF", 0), string(symbol, "LP", "LP"),
                                string(symbol, "ST", "G"), matrix(symbol), alpha(symbol)));
                    }
                }
                frames.add(new KeyFrame(index, duration, string(frame, "N", ""), List.copyOf(elements)));
            }
            frames.sort(Comparator.comparingInt(KeyFrame::index));
            layers.add(new Layer(List.copyOf(frames)));
        }
        return new Timeline(name, List.copyOf(layers), Math.min(MAX_TIMELINE_FRAMES, count));
    }

    private static Bounds wholeBounds(Timeline main, Map<String, Timeline> symbols,
                                      Map<String, Sprite> sprites) {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        List<Part> parts = new ArrayList<>();
        for (int i = 0; i < main.frameCount; i++) {
            parts.clear();
            collect(main, i, Matrix.IDENTITY, 1, symbols, sprites, parts, 0);
            for (Part part : parts) {
                Sprite s = part.sprite;
                Matrix m = part.matrix;
                double[] xs = {m.x(0, 0), m.x(s.width(), 0), m.x(s.width(), s.height()), m.x(0, s.height())};
                double[] ys = {m.y(0, 0), m.y(s.width(), 0), m.y(s.width(), s.height()), m.y(0, s.height())};
                for (double x : xs) { minX = Math.min(minX, x); maxX = Math.max(maxX, x); }
                for (double y : ys) { minY = Math.min(minY, y); maxY = Math.max(maxY, y); }
            }
        }
        if (!Double.isFinite(minX)) return new Bounds(0, 0, 1, 1);
        return new Bounds(minX, minY, maxX, maxY);
    }

    private static void collect(Timeline timeline, int index, Matrix parent, double parentAlpha,
                                Map<String, Timeline> symbols, Map<String, Sprite> sprites,
                                List<Part> output, int depth) {
        if (timeline == null || depth >= MAX_RECURSION || timeline.frameCount <= 0) return;
        int current = Math.max(0, Math.min(index, timeline.frameCount - 1));
        // Animate draws bottom layers first: the JSON layer list is front-to-back.
        for (int layerIndex = timeline.layers.size() - 1; layerIndex >= 0; layerIndex--) {
            KeyFrame key = active(timeline.layers.get(layerIndex), current);
            if (key == null) continue;
            for (Element element : key.elements) {
                if (element instanceof AtlasElement atlas) {
                    Sprite sprite = sprites.get(atlas.name);
                    if (sprite != null) output.add(new Part(sprite,
                            Matrix.childThenParent(atlas.matrix, parent), parentAlpha));
                } else if (element instanceof SymbolElement symbol) {
                    Timeline child = symbols.get(symbol.name);
                    if (child == null || child.frameCount <= 0 || symbol.alpha <= 0) continue;
                    int relative = current - key.index;
                    int childIndex;
                    if (symbol.type.equalsIgnoreCase("MC") || symbol.type.equalsIgnoreCase("movieclip")) {
                        // FNF's atlas settings use Animate-editor mode: movie clips show frame zero.
                        childIndex = 0;
                    } else if (symbol.loop.equalsIgnoreCase("SF") || symbol.loop.equalsIgnoreCase("singleframe")) {
                        childIndex = symbol.firstFrame;
                    } else if (symbol.loop.equalsIgnoreCase("PO") || symbol.loop.equalsIgnoreCase("playonce")) {
                        childIndex = Math.min(symbol.firstFrame + relative, child.frameCount - 1);
                    } else {
                        childIndex = Math.floorMod(symbol.firstFrame + relative, child.frameCount);
                    }
                    collect(child, childIndex, Matrix.childThenParent(symbol.matrix, parent),
                            parentAlpha * symbol.alpha, symbols, sprites, output, depth + 1);
                }
            }
        }
    }

    private static KeyFrame active(Layer layer, int index) {
        KeyFrame found = null;
        for (KeyFrame frame : layer.frames) {
            if (frame.index > index) break;
            if (index < frame.index + frame.duration) found = frame;
        }
        return found;
    }

    private void buildFrames() {
        for (int i = 0; i < main.frameCount; i++) allFrames.add(makeFrame(main, i, main.name + i));
        for (Layer layer : main.layers) {
            for (KeyFrame key : layer.frames) {
                if (key.name.isBlank()) continue;
                List<Frame> frames = new ArrayList<>();
                int end = Math.min(main.frameCount, key.index + key.duration);
                for (int i = key.index; i < end; i++) frames.add(makeFrame(main, i, key.name + (i - key.index)));
                labels.putIfAbsent(key.name, List.copyOf(frames));
            }
        }
    }

    private Frame makeFrame(Timeline timeline, int index, String name) {
        Frame frame = new Frame();
        frame.name = name;
        frame.x = frame.y = 0;
        frame.w = frame.frameW = width;
        frame.h = frame.frameH = height;
        frame.frameX = (int) Math.round(-originX);
        frame.frameY = (int) Math.round(-originY);
        frameRefs.put(frame, new FrameRef(timeline, index));
        return frame;
    }

    @Override public void prepareFrame(Frame frame) {
        FrameRef ref = frameRefs.get(frame);
        if (ref == null || ref.equals(prepared) || closed) return;
        List<Part> parts = new ArrayList<>();
        collect(ref.timeline, ref.index, Matrix.IDENTITY, 1, symbols, sprites, parts, 0);
        Graphics2D graphics = canvas.createGraphics();
        graphics.setComposite(AlphaComposite.Clear);
        graphics.fillRect(0, 0, width, height);
        graphics.setComposite(AlphaComposite.SrcOver);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                antialiasing ? RenderingHints.VALUE_INTERPOLATION_BILINEAR
                        : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        for (Part part : parts) {
            Matrix m = part.matrix;
            AffineTransform transform = new AffineTransform(m.a, m.b, m.c, m.d,
                    m.tx - originX, m.ty - originY);
            graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,
                    (float) Math.max(0, Math.min(1, part.alpha))));
            graphics.drawImage(part.sprite.image, transform, null);
        }
        graphics.dispose();
        copyCanvas();
        texture.upload();
        prepared = ref;
        maskPrepared = null;
    }

    private void copyCanvas() {
        int[] argb = canvas.getRGB(0, 0, width, height, null, 0, width);
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int pixel = argb[row + x];
                int a = pixel >>> 24, r = pixel >> 16 & 255, g = pixel >> 8 & 255, b = pixel & 255;
                pixels.setPixelRGBA(x, y, a << 24 | b << 16 | g << 8 | r);
            }
        }
    }

    @Override public ResourceLocation silhouetteTexture() {
        if (prepared == null) return textureId;
        try {
            if (maskTexture == null) {
                maskPixels = new NativeImage(width, height, true);
                maskTexture = new DynamicTexture(maskPixels);
                maskTexture.setFilter(antialiasing, false);
                maskId = FnfMod.id("animate_outline/" + NEXT_ID.incrementAndGet());
                Minecraft.getInstance().getTextureManager().register(maskId, maskTexture);
            }
            if (!prepared.equals(maskPrepared)) {
                for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                    int alpha = pixels.getPixelRGBA(x, y) >>> 24;
                    maskPixels.setPixelRGBA(x, y, alpha << 24 | 0xFFFFFF);
                }
                maskTexture.upload();
                maskPrepared = prepared;
            }
            return maskId;
        } catch (Throwable ignored) {
            return textureId;
        }
    }

    @Override public void setAntialiasing(boolean enabled) {
        antialiasing = enabled;
        if (texture != null) texture.setFilter(enabled, false);
        if (maskTexture != null) maskTexture.setFilter(enabled, false);
    }

    @Override public ResourceLocation texture() { return textureId; }
    @Override public int width() { return width; }
    @Override public int height() { return height; }
    @Override public NativeImage image() { return pixels; }
    @Override public long estimatedBytes() { return (long) width * height * 8L; }
    @Override public List<Frame> allFrames() { return List.copyOf(allFrames); }
    @Override public java.util.Set<String> animationNames() { return labels.keySet(); }
    @Override public boolean hasAnimation(String prefix) { return !framesByPrefix(prefix).isEmpty(); }
    @Override public List<Frame> frames(String prefix) { return labels.getOrDefault(prefix, List.of()); }

    @Override public List<Frame> framesByPrefix(String prefix) {
        if (prefix == null) return List.of();
        for (Map.Entry<String, List<Frame>> entry : labels.entrySet()) {
            if (entry.getKey().startsWith(prefix)) return entry.getValue();
        }
        return List.of();
    }

    @Override public List<Frame> framesBySymbol(String symbol) {
        if (symbol == null) return List.of();
        Timeline timeline = symbols.get(symbol);
        if (timeline == null) {
            for (Map.Entry<String, Timeline> entry : symbols.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(symbol)) { timeline = entry.getValue(); break; }
            }
        }
        if (timeline == null) return framesByPrefix(symbol);
        List<Frame> result = new ArrayList<>(timeline.frameCount);
        for (int i = 0; i < timeline.frameCount; i++) result.add(makeFrame(timeline, i, symbol + i));
        return List.copyOf(result);
    }

    @Override public List<Frame> framesByIndices(String prefix, List<Integer> indices) {
        List<Frame> source = framesByPrefix(prefix);
        if (source.isEmpty() || indices == null) return List.of();
        List<Frame> result = new ArrayList<>();
        for (Integer index : indices) if (index != null && index >= 0 && index < source.size()) result.add(source.get(index));
        return List.copyOf(result);
    }

    @Override public void drawScaled(net.minecraft.client.gui.GuiGraphics gui, Frame frame,
                                     float centerX, float centerY, float scale,
                                     ResourceLocation textureOverride) {
        prepareFrame(frame);
        super.drawScaled(gui, frame, centerX, centerY, scale,
                textureOverride == null ? textureId : textureOverride);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        if (maskId != null) Minecraft.getInstance().getTextureManager().release(maskId);
        if (textureId != null) Minecraft.getInstance().getTextureManager().release(textureId);
        maskTexture = null;
        texture = null;
    }

    private static Matrix matrix(JsonObject object) {
        JsonArray values = array(object, "MX");
        if (values.size() < 6) return Matrix.IDENTITY;
        try {
            return new Matrix(values.get(0).getAsDouble(), values.get(1).getAsDouble(),
                    values.get(2).getAsDouble(), values.get(3).getAsDouble(),
                    values.get(4).getAsDouble(), values.get(5).getAsDouble());
        } catch (Exception ignored) { return Matrix.IDENTITY; }
    }

    private static double alpha(JsonObject symbol) {
        JsonObject color = object(symbol, "C");
        if (color.size() == 0) return 1;
        String mode = string(color, "M", "");
        if (mode.equalsIgnoreCase("CA") || mode.equalsIgnoreCase("Alpha")
                || mode.equalsIgnoreCase("AD") || mode.equalsIgnoreCase("Advanced")) {
            return Math.max(0, Math.min(1, number(color, "AM", 1)));
        }
        return 1;
    }

    private static JsonObject object(JsonObject parent, String key) {
        return parent != null && parent.has(key) && parent.get(key).isJsonObject()
                ? parent.getAsJsonObject(key) : new JsonObject();
    }

    private static JsonArray array(JsonObject parent, String key) {
        return parent != null && parent.has(key) && parent.get(key).isJsonArray()
                ? parent.getAsJsonArray(key) : new JsonArray();
    }

    private static String string(JsonObject object, String key, String fallback) {
        try { return object.has(key) ? object.get(key).getAsString() : fallback; }
        catch (Exception ignored) { return fallback; }
    }

    private static int integer(JsonObject object, String key, int fallback) {
        try { return object.has(key) ? object.get(key).getAsInt() : fallback; }
        catch (Exception ignored) { return fallback; }
    }

    private static double number(JsonObject object, String key, double fallback) {
        try { return object.has(key) ? object.get(key).getAsDouble() : fallback; }
        catch (Exception ignored) { return fallback; }
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        try { return object.has(key) ? object.get(key).getAsBoolean() : fallback; }
        catch (Exception ignored) { return fallback; }
    }
}
