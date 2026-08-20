package com.fnfmod.client.render;

import com.fnfmod.FnfMod;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Shared, RAM-only cache for ordinary gameplay/Lua PNG textures. */
public final class SpriteImageCache {
    private static final long MAX_BYTES = 128L * 1024L * 1024L;
    private static final AtomicInteger NEXT_ID = new AtomicInteger();

    private record Key(Path png, long size, long modified, boolean antialiasing) {}
    private static final class Loaded {
        final ResourceLocation id;
        final DynamicTexture texture;
        final int width;
        final int height;
        final long bytes;
        ResourceLocation maskId;
        DynamicTexture maskTexture;
        int references;

        Loaded(ResourceLocation id, DynamicTexture texture, int width, int height) {
            this.id = id;
            this.texture = texture;
            this.width = width;
            this.height = height;
            this.bytes = Math.max(1L, width) * Math.max(1L, height) * 4L;
        }
    }

    public static final class Handle implements AutoCloseable {
        private final Key key;
        private Loaded value;

        private Handle(Key key, Loaded value) {
            this.key = key;
            this.value = value;
        }

        public ResourceLocation textureId() { return value == null ? null : value.id; }
        public DynamicTexture dynamicTexture() { return value == null ? null : value.texture; }
        public int width() { return value == null ? 0 : value.width; }
        public int height() { return value == null ? 0 : value.height; }
        public ResourceLocation silhouetteTexture() {
            return value == null ? null : SpriteImageCache.silhouette(value, key.antialiasing());
        }

        @Override public void close() {
            if (value == null) return;
            Loaded released = value;
            value = null;
            SpriteImageCache.release(key, released);
        }
    }

    private static final Map<Key, Loaded> loaded = new LinkedHashMap<>(16, 0.75f, true);
    private static final Map<Key, NativeImage> decoded = new LinkedHashMap<>(16, 0.75f, true);
    private static final Set<Key> decoding = new HashSet<>();
    private static final Set<Key> failed = new HashSet<>();
    private static long loadedBytes;
    private static long decodedBytes;
    private static int generation;

    private SpriteImageCache() {}

    /** Background-safe PNG decode. */
    public static void prefetch(Path png, boolean antialiasing) {
        Key key = key(png, antialiasing);
        if (key == null) return;
        int decodeGeneration;
        synchronized (SpriteImageCache.class) {
            if (loaded.containsKey(key) || decoded.containsKey(key) || decoding.contains(key) || failed.contains(key)) {
                return;
            }
            decoding.add(key);
            decodeGeneration = generation;
        }
        NativeImage image = null;
        try (var input = Files.newInputStream(key.png())) {
            image = NativeImage.read(input);
        } catch (Exception ignored) {}
        synchronized (SpriteImageCache.class) {
            decoding.remove(key);
            if (decodeGeneration != generation) {
                if (image != null) image.close();
                return;
            }
            if (image == null) {
                failed.add(key);
            } else if (loaded.containsKey(key) || decoded.containsKey(key)) {
                image.close();
            } else {
                decoded.put(key, image);
                decodedBytes += bytes(image);
                trimDecoded();
            }
        }
    }

    /** Render-thread acquire. */
    public static synchronized Handle acquire(Path png, boolean antialiasing) {
        Key key = key(png, antialiasing);
        if (key == null) return null;
        Loaded value = loaded.get(key);
        if (value == null) {
            NativeImage image = decoded.remove(key);
            if (image != null) decodedBytes -= bytes(image);
            DynamicTexture createdTexture = null;
            ResourceLocation createdId = null;
            boolean registered = false;
            try {
                if (image == null) {
                    try (var input = Files.newInputStream(key.png())) {
                        image = NativeImage.read(input);
                    }
                }
                int width = image.getWidth();
                int height = image.getHeight();
                createdTexture = new DynamicTexture(image);
                image = null; // DynamicTexture owns the pixels from here onward.
                createdTexture.setFilter(key.antialiasing(), false);
                createdId = FnfMod.id("sprite_image/" + NEXT_ID.incrementAndGet());
                Minecraft.getInstance().getTextureManager().register(createdId, createdTexture);
                registered = true;
                value = new Loaded(createdId, createdTexture, width, height);
                loaded.put(key, value);
                loadedBytes += value.bytes;
            } catch (Exception error) {
                if (registered && createdId != null) {
                    Minecraft.getInstance().getTextureManager().release(createdId);
                } else if (createdTexture != null) {
                    createdTexture.close();
                } else if (image != null) {
                    image.close();
                }
                failed.add(key);
                return null;
            }
        }
        value.references++;
        return new Handle(key, value);
    }

    public static void warm(Path png, boolean antialiasing) {
        Handle handle = acquire(png, antialiasing);
        if (handle != null) handle.close();
    }

    private static Key key(Path png, boolean antialiasing) {
        if (png == null) return null;
        try {
            Path path = png.toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)) return null;
            return new Key(path, Files.size(path), Files.getLastModifiedTime(path).toMillis(), antialiasing);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static long bytes(NativeImage image) {
        return Math.max(1L, image.getWidth()) * Math.max(1L, image.getHeight()) * 4L;
    }

    private static synchronized void release(Key key, Loaded expected) {
        Loaded current = loaded.get(key);
        if (current == expected && current.references > 0) current.references--;
        trimLoaded();
    }

    private static synchronized ResourceLocation silhouette(Loaded value, boolean antialiasing) {
        if (value.maskId != null) return value.maskId;
        NativeImage source = value.texture.getPixels();
        if (source == null) return value.id;
        try {
            NativeImage mask = new NativeImage(value.width, value.height, true);
            for (int y = 0; y < value.height; y++) {
                for (int x = 0; x < value.width; x++) {
                    int alpha = source.getPixelRGBA(x, y) >>> 24;
                    mask.setPixelRGBA(x, y, alpha << 24 | 0xFFFFFF);
                }
            }
            value.maskTexture = new DynamicTexture(mask);
            value.maskTexture.setFilter(antialiasing, false);
            value.maskId = FnfMod.id("sprite_image_outline/" + NEXT_ID.incrementAndGet());
            Minecraft.getInstance().getTextureManager().register(value.maskId, value.maskTexture);
            loadedBytes += value.bytes;
        } catch (Throwable ignored) {
            value.maskId = value.id;
            value.maskTexture = null;
        }
        return value.maskId;
    }

    private static void releaseMask(Loaded value) {
        if (value.maskTexture != null && value.maskId != null) {
            Minecraft.getInstance().getTextureManager().release(value.maskId);
            loadedBytes = Math.max(0, loadedBytes - value.bytes);
        }
        value.maskTexture = null;
        value.maskId = null;
    }

    private static void trimLoaded() {
        if (loadedBytes <= MAX_BYTES) return;
        Iterator<Map.Entry<Key, Loaded>> iterator = loaded.entrySet().iterator();
        while (loadedBytes > MAX_BYTES && iterator.hasNext()) {
            Loaded value = iterator.next().getValue();
            if (value.references != 0) continue;
            iterator.remove();
            loadedBytes -= value.bytes;
            releaseMask(value);
            Minecraft.getInstance().getTextureManager().release(value.id);
        }
    }

    private static void trimDecoded() {
        Iterator<Map.Entry<Key, NativeImage>> iterator = decoded.entrySet().iterator();
        while (decodedBytes > MAX_BYTES && iterator.hasNext()) {
            NativeImage image = iterator.next().getValue();
            iterator.remove();
            decodedBytes -= bytes(image);
            image.close();
        }
    }

    public static synchronized void clear() {
        generation++;
        for (Loaded value : loaded.values()) {
            releaseMask(value);
            Minecraft.getInstance().getTextureManager().release(value.id);
        }
        for (NativeImage image : decoded.values()) image.close();
        loaded.clear();
        decoded.clear();
        decoding.clear();
        failed.clear();
        loadedBytes = decodedBytes = 0;
    }
}
