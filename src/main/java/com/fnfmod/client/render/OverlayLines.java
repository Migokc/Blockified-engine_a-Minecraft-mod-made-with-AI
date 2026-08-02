package com.fnfmod.client.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;

import java.lang.reflect.Method;
import java.util.OptionalDouble;

/**
 * A LINES render type that ignores depth, so selection outlines and the drag cube draw
 * in front of the world (but still behind the GUI). Minecraft's render-state shards are
 * package-private and a class placed in {@code net.minecraft.client.renderer} splits that
 * package under NeoForge's module layer, so the state is assembled by reflection and
 * cached. If any lookup fails, the normal depth-tested {@link RenderType#lines()} is used.
 */
public final class OverlayLines {

    private static RenderType cached;
    private static boolean tried;
    private static RenderType thickCached;
    private static boolean thickTried;

    private OverlayLines() {}

    public static RenderType renderType() {
        if (!tried) {
            tried = true;
            try {
                cached = build();
            } catch (Throwable error) {
                com.fnfmod.FnfMod.LOGGER.warn(
                        "Overlay lines unavailable; selection gizmo will be depth-tested: {}",
                        error.toString());
                cached = RenderType.lines();
            }
        }
        return cached;
    }

    /** Three-pixel variant used by the free-camera entry marker. */
    public static RenderType thickRenderType() {
        if (!thickTried) {
            thickTried = true;
            try {
                thickCached = build(3.0);
            } catch (Throwable error) {
                com.fnfmod.FnfMod.LOGGER.warn(
                        "Thick overlay lines unavailable; camera marker will use normal lines: {}",
                        error.toString());
                thickCached = renderType();
            }
        }
        return thickCached;
    }

    private static RenderType build() throws Exception {
        return build(null);
    }

    private static RenderType build(Double lineWidth) throws Exception {
        Class<?> shard = Class.forName("net.minecraft.client.renderer.RenderStateShard");
        Object noDepth = staticField(shard, "NO_DEPTH_TEST");
        Object noCull = staticField(shard, "NO_CULL");
        Object translucent = staticField(shard, "TRANSLUCENT_TRANSPARENCY");
        Object colorWrite = staticField(shard, "COLOR_WRITE");
        Object lineShader = staticField(shard, "RENDERTYPE_LINES_SHADER");

        Object builder = RenderType.CompositeState.builder();
        setter(builder, "setShaderState", lineShader);
        setter(builder, "setTransparencyState", translucent);
        setter(builder, "setCullState", noCull);
        setter(builder, "setWriteMaskState", colorWrite);
        setter(builder, "setDepthTestState", noDepth);
        if (lineWidth != null) {
            Class<?> lineStateClass = Class.forName(
                    "net.minecraft.client.renderer.RenderStateShard$LineStateShard");
            var constructor = lineStateClass.getDeclaredConstructor(OptionalDouble.class);
            constructor.setAccessible(true);
            Object lineState = constructor.newInstance(OptionalDouble.of(lineWidth));
            setter(builder, "setLineState", lineState);
        }

        Method create = builder.getClass().getMethod("createCompositeState", boolean.class);
        create.setAccessible(true);
        RenderType.CompositeState state = (RenderType.CompositeState) create.invoke(builder, false);
        String name = lineWidth == null ? "fnfmod_overlay_lines" : "fnfmod_overlay_lines_3px";
        return RenderType.create(name,
                DefaultVertexFormat.POSITION_COLOR_NORMAL, VertexFormat.Mode.LINES, 512, state);
    }

    private static Object staticField(Class<?> owner, String name) throws Exception {
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
