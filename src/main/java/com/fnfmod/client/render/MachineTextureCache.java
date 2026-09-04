package com.fnfmod.client.render;

import com.fnfmod.FnfMod;
import com.fnfmod.machine.MachineLibrary;
import com.fnfmod.world.ModContentScope;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Client cache for active-pack machine textures. */
public final class MachineTextureCache {

    private record Loaded(ResourceLocation id, DynamicTexture texture, int width, int height) {}
    public record Size(int width, int height) {}

    private static final Map<Path, Loaded> loaded = new HashMap<>();
    // Background-decoded images awaiting a (cheap) main-thread GL upload, so heavy
    // PNG decoding never runs on the render thread. Populated off-thread by prefetch.
    private static final Map<Path, NativeImage> decoded = new HashMap<>();
    private static final Set<Path> decodeFailed = new HashSet<>();
    private static int generation = -1;

    private MachineTextureCache() {}

    /**
     * Background-safe: reads and decodes the PNG (the expensive part) without any GL
     * work, so it can run on a worker thread. The image is uploaded later by {@link
     * #get(Path)} on the render thread. Safe to call for an already-loaded texture.
     */
    public static void prefetch(Path file) {
        if (file == null) return;
        Path key = file.toAbsolutePath().normalize();
        synchronized (MachineTextureCache.class) {
            if (loaded.containsKey(key) || decoded.containsKey(key) || decodeFailed.contains(key)) return;
        }
        if (!Files.isRegularFile(key) || !ModContentScope.allowsContentPath(key)) {
            synchronized (MachineTextureCache.class) { decodeFailed.add(key); }
            return;
        }
        NativeImage image = null;
        try (var input = Files.newInputStream(key)) {
            image = NativeImage.read(input);
        } catch (Exception e) {
            FnfMod.LOGGER.warn("Could not decode machine texture {}: {}", key, e.toString());
        }
        synchronized (MachineTextureCache.class) {
            if (image == null) {
                decodeFailed.add(key);
            } else if (loaded.containsKey(key)) {
                image.close();
            } else {
                NativeImage previous = decoded.put(key, image);
                if (previous != null) previous.close();
            }
        }
    }

    public static synchronized ResourceLocation get(Path file) {
        if (generation != MachineLibrary.generation()) clear();
        if (file == null || !Files.isRegularFile(file) || !ModContentScope.allowsContentPath(file)) return null;
        Path key = file.toAbsolutePath().normalize();
        Loaded existing = loaded.get(key);
        if (existing != null) return existing.id();
        NativeImage prefetched = decoded.remove(key);
        try {
            NativeImage image;
            if (prefetched != null) {
                image = prefetched;
            } else {
                try (var input = Files.newInputStream(key)) {
                    image = NativeImage.read(input);
                }
            }
            int width = image.getWidth();
            int height = image.getHeight();
            DynamicTexture texture = new DynamicTexture(image);
            ResourceLocation id = FnfMod.id("machine_texture/" + Integer.toUnsignedString(key.hashCode(), 36));
            Minecraft.getInstance().getTextureManager().register(id, texture);
            loaded.put(key, new Loaded(id, texture, width, height));
            return id;
        } catch (Exception e) {
            FnfMod.LOGGER.warn("Could not load machine texture {}: {}", key, e.toString());
            return null;
        }
    }

    public static synchronized Size size(Path file) {
        if (file == null) return new Size(1, 1);
        get(file);
        Loaded value = loaded.get(file.toAbsolutePath().normalize());
        return value == null ? new Size(1, 1) : new Size(value.width(), value.height());
    }

    public static synchronized void setAntialiasing(Path file, boolean enabled) {
        if (file == null) return;
        Loaded value = loaded.get(file.toAbsolutePath().normalize());
        if (value == null) return;
        try {
            value.texture().setFilter(enabled, false);
        } catch (Throwable ignored) {}
    }

    /** Frees any background-decoded images that were never uploaded (e.g. a cancelled menu). */
    public static synchronized void dropPending() {
        for (NativeImage image : decoded.values()) {
            try { image.close(); } catch (Exception ignored) {}
        }
        decoded.clear();
        decodeFailed.clear();
    }

    public static synchronized void clear() {
        for (Loaded value : loaded.values()) {
            try {
                Minecraft.getInstance().getTextureManager().release(value.id());
                value.texture().close();
            } catch (Exception ignored) {}
        }
        loaded.clear();
        for (NativeImage image : decoded.values()) {
            try { image.close(); } catch (Exception ignored) {}
        }
        decoded.clear();
        decodeFailed.clear();
        generation = MachineLibrary.generation();
    }
}
