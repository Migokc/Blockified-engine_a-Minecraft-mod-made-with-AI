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
import java.util.Map;

/** Client cache for active-pack machine textures. */
public final class MachineTextureCache {

    private record Loaded(ResourceLocation id, DynamicTexture texture) {}

    private static final Map<Path, Loaded> loaded = new HashMap<>();
    private static int generation = -1;

    private MachineTextureCache() {}

    public static synchronized ResourceLocation get(Path file) {
        if (generation != MachineLibrary.generation()) clear();
        if (file == null || !Files.isRegularFile(file) || !ModContentScope.allowsContentPath(file)) return null;
        Path key = file.toAbsolutePath().normalize();
        Loaded existing = loaded.get(key);
        if (existing != null) return existing.id();
        try (var input = Files.newInputStream(key)) {
            NativeImage image = NativeImage.read(input);
            DynamicTexture texture = new DynamicTexture(image);
            ResourceLocation id = FnfMod.id("machine_texture/" + Integer.toUnsignedString(key.hashCode(), 36));
            Minecraft.getInstance().getTextureManager().register(id, texture);
            loaded.put(key, new Loaded(id, texture));
            return id;
        } catch (Exception e) {
            FnfMod.LOGGER.warn("Could not load machine texture {}: {}", key, e.toString());
            return null;
        }
    }

    public static synchronized void setAntialiasing(Path file, boolean enabled) {
        if (file == null) return;
        Loaded value = loaded.get(file.toAbsolutePath().normalize());
        if (value == null) return;
        try {
            value.texture().setFilter(enabled, false);
        } catch (Throwable ignored) {}
    }

    public static synchronized void clear() {
        for (Loaded value : loaded.values()) {
            try {
                Minecraft.getInstance().getTextureManager().release(value.id());
                value.texture().close();
            } catch (Exception ignored) {}
        }
        loaded.clear();
        generation = MachineLibrary.generation();
    }
}
