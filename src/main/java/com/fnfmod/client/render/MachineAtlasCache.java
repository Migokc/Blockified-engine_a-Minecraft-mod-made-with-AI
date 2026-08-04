package com.fnfmod.client.render;

import com.fnfmod.machine.MachineLibrary;
import com.fnfmod.world.ModContentScope;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Shared Sparrow-atlas cache for sandboxed machine menus. */
public final class MachineAtlasCache {

    private record Key(Path png, Path xml) {}

    private static final Map<Key, SparrowAtlas> loaded = new HashMap<>();
    // Background-decoded atlases awaiting a (cheap) main-thread GL upload.
    private static final Map<Key, SparrowAtlas.Decoded> decoded = new HashMap<>();
    private static final Set<Key> decodeFailed = new HashSet<>();
    private static int generation = -1;

    private MachineAtlasCache() {}

    /**
     * Background-safe: decodes the PNG and parses the XML (the expensive part) with no
     * GL work, so it can run on a worker thread. Finished later by {@link #get(Path, Path)}.
     */
    public static void prefetch(Path png, Path xml) {
        if (png == null || xml == null || !Files.isRegularFile(png) || !Files.isRegularFile(xml)
                || !ModContentScope.allowsContentPath(png) || !ModContentScope.allowsContentPath(xml)) {
            return;
        }
        Key key = new Key(png.toAbsolutePath().normalize(), xml.toAbsolutePath().normalize());
        synchronized (MachineAtlasCache.class) {
            if (loaded.containsKey(key) || decoded.containsKey(key) || decodeFailed.contains(key)) return;
        }
        SparrowAtlas.Decoded result = SparrowAtlas.decode(key.png(), key.xml());
        synchronized (MachineAtlasCache.class) {
            if (result == null) {
                decodeFailed.add(key);
            } else if (loaded.containsKey(key)) {
                result.close();
            } else {
                SparrowAtlas.Decoded previous = decoded.put(key, result);
                if (previous != null) previous.close();
            }
        }
    }

    public static synchronized SparrowAtlas get(Path png, Path xml) {
        if (generation != MachineLibrary.generation()) clear();
        if (png == null || xml == null || !Files.isRegularFile(png) || !Files.isRegularFile(xml)
                || !ModContentScope.allowsContentPath(png) || !ModContentScope.allowsContentPath(xml)) {
            return null;
        }
        Key key = new Key(png.toAbsolutePath().normalize(), xml.toAbsolutePath().normalize());
        SparrowAtlas existing = loaded.get(key);
        if (existing != null) return existing;
        SparrowAtlas.Decoded prefetched = decoded.remove(key);
        SparrowAtlas atlas = prefetched != null
                ? SparrowAtlas.finish(prefetched)
                : SparrowAtlas.load(key.png(), key.xml());
        if (atlas != null) loaded.put(key, atlas);
        return atlas;
    }

    /** Frees any background-decoded atlases that were never uploaded (e.g. a cancelled menu). */
    public static synchronized void dropPending() {
        for (SparrowAtlas.Decoded value : decoded.values()) value.close();
        decoded.clear();
        decodeFailed.clear();
    }

    public static synchronized void clear() {
        for (SparrowAtlas atlas : loaded.values()) {
            try {
                atlas.close();
            } catch (Exception ignored) {}
        }
        loaded.clear();
        for (SparrowAtlas.Decoded value : decoded.values()) value.close();
        decoded.clear();
        decodeFailed.clear();
        generation = MachineLibrary.generation();
    }
}
