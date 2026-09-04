package com.fnfmod.client.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/**
 * Two-sided text-material render types for flat world UI.
 *
 * <p>Vanilla's text type uses the shader behavior wanted here (lightmap, no
 * directional normal), but retains back-face culling. A world-space button or
 * baked label must remain visible from both sides, with the back naturally
 * showing mirrored texture coordinates.</p>
 */
final class FlatWorldRenderTypes {
    private static final Map<ResourceLocation, RenderType> NORMAL = new HashMap<>();
    private static final Map<ResourceLocation, RenderType> POLYGON_OFFSET = new HashMap<>();
    private static final Map<ResourceLocation, RenderType> SEE_THROUGH = new HashMap<>();

    private FlatWorldRenderTypes() {}

    static synchronized RenderType get(ResourceLocation texture, boolean seeThrough,
                                       boolean polygonOffset) {
        Map<ResourceLocation, RenderType> cache = seeThrough ? SEE_THROUGH
                : polygonOffset ? POLYGON_OFFSET : NORMAL;
        return cache.computeIfAbsent(texture,
                key -> build(key, seeThrough, polygonOffset && !seeThrough));
    }

    private static RenderType build(ResourceLocation texture, boolean seeThrough,
                                    boolean polygonOffset) {
        RenderType.CompositeState.CompositeStateBuilder state = RenderType.CompositeState.builder()
                .setShaderState(new RenderStateShard.ShaderStateShard(seeThrough
                        ? GameRenderer::getRendertypeTextSeeThroughShader
                        : GameRenderer::getRendertypeTextShader))
                .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                .setCullState(RenderStateShard.NO_CULL)
                .setLightmapState(RenderStateShard.LIGHTMAP);
        if (seeThrough) {
            state.setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE);
        } else if (polygonOffset) {
            state.setLayeringState(RenderStateShard.POLYGON_OFFSET_LAYERING);
        }
        return RenderType.create("fnfmod_flat_world_" + (seeThrough ? "see_through"
                        : polygonOffset ? "offset" : "normal"),
                DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP,
                VertexFormat.Mode.QUADS, 1536, false, true,
                state.createCompositeState(false));
    }
}
