package com.fnfmod.client.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * A translucent entity-style render type that ignores depth, for see-through Lua
 * world sprites. Minecraft's render-state shards are package-private, and a class
 * in {@code net.minecraft.client.renderer} breaks NeoForge's module layer (split
 * package), so the state is assembled by reflection and cached per texture. If any
 * of it fails, the caller's normal (depth-tested) render type is used instead —
 * see-through simply does nothing rather than crashing.
 */
final class SeeThroughSprite {

    private static final Map<ResourceLocation, RenderType> CACHE = new HashMap<>();
    private static boolean unavailable;

    private SeeThroughSprite() {}

    static RenderType renderType(ResourceLocation texture, RenderType fallback) {
        if (unavailable) return fallback;
        RenderType cached = CACHE.get(texture);
        if (cached != null) return cached;
        try {
            RenderType built = build(texture);
            CACHE.put(texture, built);
            return built;
        } catch (Throwable error) {
            unavailable = true;
            com.fnfmod.FnfMod.LOGGER.warn(
                    "See-through Lua world objects unavailable; they will be depth-tested instead: {}",
                    error.toString());
            return fallback;
        }
    }

    private static RenderType build(ResourceLocation texture) throws Exception {
        Class<?> shard = Class.forName("net.minecraft.client.renderer.RenderStateShard");
        Object noDepth = staticField(shard, "NO_DEPTH_TEST");
        Object noCull = staticField(shard, "NO_CULL");
        Object translucent = staticField(shard, "TRANSLUCENT_TRANSPARENCY");
        Object lightmap = staticField(shard, "LIGHTMAP");
        Object overlay = staticField(shard, "OVERLAY");
        Object colorWrite = staticField(shard, "COLOR_WRITE");
        // This shader constant is declared on RenderStateShard (RenderType's
        // superclass). Looking it up on RenderType.class with getDeclaredField
        // throws NoSuchFieldException — inherited fields are not returned — which
        // silently disabled every see-through sprite. Look it up where it lives.
        Object shaderState = staticField(shard, "RENDERTYPE_ENTITY_TRANSLUCENT_SHADER");

        Class<?> textureShard = Class.forName(
                "net.minecraft.client.renderer.RenderStateShard$TextureStateShard");
        var ctor = textureShard.getDeclaredConstructor(ResourceLocation.class, boolean.class, boolean.class);
        ctor.setAccessible(true);
        Object textureState = ctor.newInstance(texture, false, false);

        Object builder = RenderType.CompositeState.builder();
        setter(builder, "setShaderState", shaderState);
        setter(builder, "setTextureState", textureState);
        setter(builder, "setTransparencyState", translucent);
        setter(builder, "setCullState", noCull);
        setter(builder, "setLightmapState", lightmap);
        setter(builder, "setOverlayState", overlay);
        setter(builder, "setWriteMaskState", colorWrite);
        setter(builder, "setDepthTestState", noDepth);

        Method create = builder.getClass().getMethod("createCompositeState", boolean.class);
        create.setAccessible(true);
        RenderType.CompositeState state = (RenderType.CompositeState) create.invoke(builder, true);
        return RenderType.create("fnfmod_world_seethrough_sprite",
                DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 1536, false, true, state);
    }

    private static Object staticField(Class<?> owner, String name) throws Exception {
        // Walk the hierarchy so an inherited constant is found even when queried
        // through a subclass (getDeclaredField only returns fields of one class).
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
            try {
                var field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(null);
            } catch (NoSuchFieldException ignored) {
                // try the superclass
            }
        }
        throw new NoSuchFieldException(owner.getName() + "#" + name);
    }

    private static void setter(Object builder, String name, Object value) throws Exception {
        for (Method method : builder.getClass().getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 1) {
                method.setAccessible(true);
                method.invoke(builder, value);
                return;
            }
        }
        throw new NoSuchMethodException(name);
    }
}
