package com.fnfmod.client.render;

import com.fnfmod.machine.MachineLibrary;
import com.fnfmod.world.ModContentScope;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Shared Sparrow-atlas cache for sandboxed machine menus. */
public final class MachineAtlasCache {

    private record Key(Path png, Path xml) {}

    private static final Map<Key, SparrowAtlas> loaded = new HashMap<>();
    private static int generation = -1;

    private MachineAtlasCache() {}

    public static synchronized SparrowAtlas get(Path png, Path xml) {
        if (generation != MachineLibrary.generation()) clear();
        if (png == null || xml == null || !Files.isRegularFile(png) || !Files.isRegularFile(xml)
                || !ModContentScope.allowsContentPath(png) || !ModContentScope.allowsContentPath(xml)) {
            return null;
        }
        Key key = new Key(png.toAbsolutePath().normalize(), xml.toAbsolutePath().normalize());
        SparrowAtlas existing = loaded.get(key);
        if (existing != null) return existing;
        SparrowAtlas atlas = SparrowAtlas.load(key.png(), key.xml());
        if (atlas != null) loaded.put(key, atlas);
        return atlas;
    }

    public static synchronized void clear() {
        for (SparrowAtlas atlas : loaded.values()) {
            try {
                atlas.close();
            } catch (Exception ignored) {}
        }
        loaded.clear();
        generation = MachineLibrary.generation();
    }
}
