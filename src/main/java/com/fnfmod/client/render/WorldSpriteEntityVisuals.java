package com.fnfmod.client.render;

import com.fnfmod.entity.WorldSpriteEntity;
import net.minecraft.core.Direction;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Client-only visual bindings for unsynchronized world-sprite entities. */
public final class WorldSpriteEntityVisuals {
    public record Visual(LuaWorldObject.Sprite sprite, Direction facing, boolean irlightsShadows) {}

    private static final Map<Integer, Supplier<Visual>> VISUALS = new ConcurrentHashMap<>();

    private WorldSpriteEntityVisuals() {}

    public static void bind(WorldSpriteEntity entity, Supplier<Visual> visual) {
        if (entity != null && visual != null) VISUALS.put(entity.getId(), visual);
    }

    public static Visual get(WorldSpriteEntity entity) {
        Supplier<Visual> supplier = entity == null ? null : VISUALS.get(entity.getId());
        return supplier == null ? null : supplier.get();
    }

    public static void unbind(WorldSpriteEntity entity) {
        if (entity != null) VISUALS.remove(entity.getId());
    }

    public static void clear() { VISUALS.clear(); }
}
