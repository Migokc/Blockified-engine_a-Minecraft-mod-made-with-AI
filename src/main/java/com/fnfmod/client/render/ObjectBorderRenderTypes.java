package com.fnfmod.client.render;

import com.fnfmod.mixin.accessor.CompositeRenderTypeAccessor;
import com.fnfmod.mixin.accessor.CompositeStateAccessor;
import com.fnfmod.mixin.accessor.EmptyTextureStateShardAccessor;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;

/** Depth-tested counterpart of Minecraft's deliberately through-wall outline type. */
public final class ObjectBorderRenderTypes {
    private static final Map<RenderType, RenderType> CACHE = new IdentityHashMap<>();

    private ObjectBorderRenderTypes() {}

    public static synchronized RenderType depthTested(RenderType vanillaOutline) {
        RenderType cached = CACHE.get(vanillaOutline);
        if (cached != null) return cached;
        Optional<ResourceLocation> texture = texture(vanillaOutline);
        if (texture.isEmpty()) return vanillaOutline;
        RenderType replacement = RenderType.create(
                "fnfmod_object_border",
                DefaultVertexFormat.POSITION_TEX_COLOR,
                VertexFormat.Mode.QUADS,
                RenderType.TRANSIENT_BUFFER_SIZE,
                RenderType.CompositeState.builder()
                        .setShaderState(new RenderStateShard.ShaderStateShard(
                                BbsObjectBorderRenderer::entityShader))
                        .setTextureState(new RenderStateShard.TextureStateShard(texture.get(), false, false))
                        .setCullState(RenderStateShard.NO_CULL)
                        .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                        .setOutputState(RenderStateShard.OUTLINE_TARGET)
                        .createCompositeState(RenderType.OutlineProperty.IS_OUTLINE));
        CACHE.put(vanillaOutline, replacement);
        return replacement;
    }

    private static Optional<ResourceLocation> texture(RenderType type) {
        if (!type.isOutline()) return Optional.empty();
        RenderType.CompositeState state = ((CompositeRenderTypeAccessor) (Object) type).fnfmod$state();
        RenderStateShard.EmptyTextureStateShard texture =
                ((CompositeStateAccessor) (Object) state).fnfmod$textureState();
        return ((EmptyTextureStateShardAccessor) (Object) texture).fnfmod$cutoutTexture();
    }
}
