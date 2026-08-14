package com.fnfmod.client.render;

import com.fnfmod.entity.WorldSpriteEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

/** Renders a client-side sprite entity using Blockified's existing world-quad renderer. */
public final class WorldSpriteEntityRenderer extends EntityRenderer<WorldSpriteEntity> {
    private static final ResourceLocation TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/misc/white.png");

    public WorldSpriteEntityRenderer(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = 0.5f;
        shadowStrength = 1f;
    }

    @Override
    public void render(WorldSpriteEntity entity, float yaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight) {
        WorldSpriteEntityVisuals.Visual visual = WorldSpriteEntityVisuals.get(entity);
        if (visual == null || visual.sprite() == null || visual.sprite().alpha() <= 0) return;
        // IRLights invokes the ordinary entity renderer while baking its depth map.
        // Suppress only that geometry; visible world rendering remains unchanged.
        if (!visual.irlightsShadows() && IrlightsShadowCompat.isBaking()) return;
        LuaWorldObjectRenderer.renderEntitySprite(poseStack,
                Minecraft.getInstance().gameRenderer.getMainCamera(), visual.facing(),
                visual.sprite(), buffers, packedLight);
    }

    @Override public ResourceLocation getTextureLocation(WorldSpriteEntity entity) { return TEXTURE; }
}
