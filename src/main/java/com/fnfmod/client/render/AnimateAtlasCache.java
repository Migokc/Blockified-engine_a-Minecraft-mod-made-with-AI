package com.fnfmod.client.render;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RAM-only cache for V-Slice Animation.json atlases.
 *
 * <p>JSON parsing, symbol traversal, bounds calculation and PNG decoding happen on
 * the song preload worker. The render thread then creates and primes one atlas per
 * character before Ready is sent, so opening gameplay or changing a character does
 * not perform that work in the middle of a frame.</p>
 */
public final class AnimateAtlasCache {
    private static final long MAX_READY_BYTES = 256L * 1024L * 1024L;

    private record Key(Path folder, long signature) {}

    private static final Map<Key, AnimateAtlas.Decoded> decoded = new LinkedHashMap<>(16, 0.75f, true);
    private static final Map<Key, ArrayDeque<AnimateAtlas>> ready = new LinkedHashMap<>(16, 0.75f, true);
    private static long readyBytes;
    private static int generation;

    private AnimateAtlasCache() {}

    /** Background-safe parse/decode. */
    public static void prefetch(Path folder) {
        Key key = key(folder);
        if (key == null) return;
        int expectedGeneration;
        synchronized (AnimateAtlasCache.class) {
            if (decoded.containsKey(key)) return;
            expectedGeneration = generation;
        }
        AnimateAtlas.Decoded value = AnimateAtlas.decode(key.folder());
        if (value == null) return;
        synchronized (AnimateAtlasCache.class) {
            if (expectedGeneration == generation) decoded.putIfAbsent(key, value);
        }
    }

    /** Render-thread upload and first-frame composition. */
    public static void warm(Path folder, boolean antialiasing) {
        Key key = key(folder);
        if (key == null) return;
        synchronized (AnimateAtlasCache.class) {
            ArrayDeque<AnimateAtlas> queue = ready.get(key);
            if (queue != null && !queue.isEmpty()) return;
        }
        AnimateAtlas atlas = create(key, antialiasing);
        if (atlas == null) return;
        if (!atlas.allFrames().isEmpty()) atlas.prepareFrame(atlas.allFrames().get(0));
        synchronized (AnimateAtlasCache.class) {
            ready.computeIfAbsent(key, ignored -> new ArrayDeque<>()).addLast(atlas);
            readyBytes += atlas.estimatedBytes();
            trimReady();
        }
    }

    /** Render-thread acquire. The caller owns and eventually closes the returned atlas. */
    public static AnimateAtlas acquire(Path folder, boolean antialiasing) {
        Key key = key(folder);
        if (key == null) return null;
        synchronized (AnimateAtlasCache.class) {
            ArrayDeque<AnimateAtlas> queue = ready.get(key);
            if (queue != null) {
                AnimateAtlas atlas = queue.pollFirst();
                if (atlas != null) {
                    readyBytes = Math.max(0, readyBytes - atlas.estimatedBytes());
                    if (queue.isEmpty()) ready.remove(key);
                    atlas.setAntialiasing(antialiasing);
                    return atlas;
                }
            }
        }
        return create(key, antialiasing);
    }

    private static AnimateAtlas create(Key key, boolean antialiasing) {
        AnimateAtlas.Decoded source;
        synchronized (AnimateAtlasCache.class) { source = decoded.get(key); }
        if (source == null) {
            source = AnimateAtlas.decode(key.folder());
            if (source == null) return null;
            synchronized (AnimateAtlasCache.class) { decoded.putIfAbsent(key, source); }
        }
        AnimateAtlas atlas = AnimateAtlas.finish(source);
        if (atlas != null) atlas.setAntialiasing(antialiasing);
        return atlas;
    }

    private static Key key(Path folder) {
        if (folder == null) return null;
        try {
            Path normalized = folder.toAbsolutePath().normalize();
            if (!Files.isDirectory(normalized) || !Files.isRegularFile(normalized.resolve("Animation.json"))) {
                return null;
            }
            long signature = 1;
            try (var files = Files.list(normalized)) {
                for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                    String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                    if (!name.equals("animation.json") && !name.matches("spritemap\\d*\\.(json|png)")) continue;
                    signature = 31 * signature + Files.size(file);
                    signature = 31 * signature + Files.getLastModifiedTime(file).toMillis();
                }
            }
            return new Key(normalized, signature);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void trimReady() {
        Iterator<Map.Entry<Key, ArrayDeque<AnimateAtlas>>> iterator = ready.entrySet().iterator();
        while (readyBytes > MAX_READY_BYTES && iterator.hasNext()) {
            ArrayDeque<AnimateAtlas> queue = iterator.next().getValue();
            while (readyBytes > MAX_READY_BYTES && !queue.isEmpty()) {
                AnimateAtlas atlas = queue.removeFirst();
                readyBytes = Math.max(0, readyBytes - atlas.estimatedBytes());
                atlas.close();
            }
            if (queue.isEmpty()) iterator.remove();
        }
    }

    public static synchronized void clear() {
        generation++;
        for (ArrayDeque<AnimateAtlas> queue : ready.values()) {
            for (AnimateAtlas atlas : queue) atlas.close();
        }
        ready.clear();
        decoded.clear();
        readyBytes = 0;
    }
}
