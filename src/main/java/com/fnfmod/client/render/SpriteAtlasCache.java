package com.fnfmod.client.render;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Shared, RAM-only Sparrow atlas cache for gameplay characters and Lua sprites. */
public final class SpriteAtlasCache {
    private static final long MAX_BYTES = 256L * 1024L * 1024L;

    private record Key(Path png, Path xml, long pngSize, long pngTime, long xmlSize, long xmlTime,
                       boolean antialiasing) {}
    private static final class Loaded {
        final SparrowAtlas atlas;
        final long bytes;
        int references;

        Loaded(SparrowAtlas atlas) {
            this.atlas = atlas;
            this.bytes = atlas.estimatedBytes();
        }
    }

    private static final Map<Key, Loaded> loaded = new LinkedHashMap<>(16, 0.75f, true);
    private static final Map<Key, SparrowAtlas.Decoded> decoded = new LinkedHashMap<>(16, 0.75f, true);
    private static final Set<Key> decoding = new HashSet<>();
    private static final Set<Key> failed = new HashSet<>();
    private static long loadedBytes;
    private static long decodedBytes;
    private static int generation;

    private SpriteAtlasCache() {}

    /** Background-safe PNG decode + XML parse. No GPU work occurs here. */
    public static void prefetch(Path png, Path xml) {
        prefetch(png, xml, true);
    }

    public static void prefetch(Path png, Path xml, boolean antialiasing) {
        Key key = key(png, xml, antialiasing);
        if (key == null) return;
        int decodeGeneration;
        synchronized (SpriteAtlasCache.class) {
            if (loaded.containsKey(key) || decoded.containsKey(key) || decoding.contains(key) || failed.contains(key)) {
                return;
            }
            decoding.add(key);
            decodeGeneration = generation;
        }
        SparrowAtlas.Decoded result = SparrowAtlas.decode(key.png(), key.xml());
        synchronized (SpriteAtlasCache.class) {
            decoding.remove(key);
            if (decodeGeneration != generation) {
                if (result != null) result.close();
                return;
            }
            if (result == null) {
                failed.add(key);
            } else if (loaded.containsKey(key) || decoded.containsKey(key)) {
                result.close();
            } else {
                decoded.put(key, result);
                decodedBytes += result.estimatedBytes();
                trimDecoded();
            }
        }
    }

    /** Render-thread acquire. Uses a background-decoded result whenever available. */
    public static synchronized SparrowAtlas acquire(Path png, Path xml, boolean antialiasing) {
        Key key = key(png, xml, antialiasing);
        if (key == null) return null;
        Loaded value = loaded.get(key);
        if (value == null) {
            SparrowAtlas.Decoded pending = decoded.remove(key);
            if (pending != null) decodedBytes -= pending.estimatedBytes();
            SparrowAtlas atlas = pending == null
                    ? SparrowAtlas.load(key.png(), key.xml()) : SparrowAtlas.finish(pending);
            if (atlas == null) {
                failed.add(key);
                return null;
            }
            atlas.setAntialiasing(key.antialiasing());
            value = new Loaded(atlas);
            loaded.put(key, value);
            loadedBytes += value.bytes;
        }
        value.references++;
        Loaded acquired = value;
        return value.atlas.sharedView(() -> release(key, acquired));
    }

    /** Uploads a prefetched atlas now and leaves it warm with zero live references. */
    public static void warm(Path png, Path xml, boolean antialiasing) {
        SparrowAtlas atlas = acquire(png, xml, antialiasing);
        if (atlas != null) atlas.close();
    }

    private static synchronized void release(Key key, Loaded expected) {
        Loaded current = loaded.get(key);
        if (current == expected && current.references > 0) current.references--;
        trimLoaded();
    }

    private static Key key(Path png, Path xml, boolean antialiasing) {
        if (png == null || xml == null) return null;
        try {
            Path p = png.toAbsolutePath().normalize();
            Path x = xml.toAbsolutePath().normalize();
            if (!Files.isRegularFile(p) || !Files.isRegularFile(x)) return null;
            return new Key(p, x, Files.size(p), Files.getLastModifiedTime(p).toMillis(),
                    Files.size(x), Files.getLastModifiedTime(x).toMillis(), antialiasing);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void trimLoaded() {
        if (loadedBytes <= MAX_BYTES) return;
        Iterator<Map.Entry<Key, Loaded>> iterator = loaded.entrySet().iterator();
        while (loadedBytes > MAX_BYTES && iterator.hasNext()) {
            Loaded value = iterator.next().getValue();
            if (value.references != 0) continue;
            iterator.remove();
            loadedBytes -= value.bytes;
            value.atlas.close();
        }
    }

    private static void trimDecoded() {
        Iterator<Map.Entry<Key, SparrowAtlas.Decoded>> iterator = decoded.entrySet().iterator();
        while (decodedBytes > MAX_BYTES && iterator.hasNext()) {
            SparrowAtlas.Decoded value = iterator.next().getValue();
            iterator.remove();
            decodedBytes -= value.estimatedBytes();
            value.close();
        }
    }

    public static synchronized void clear() {
        generation++;
        for (Loaded value : loaded.values()) value.atlas.close();
        for (SparrowAtlas.Decoded value : decoded.values()) value.close();
        loaded.clear();
        decoded.clear();
        decoding.clear();
        failed.clear();
        loadedBytes = decodedBytes = 0;
    }
}
