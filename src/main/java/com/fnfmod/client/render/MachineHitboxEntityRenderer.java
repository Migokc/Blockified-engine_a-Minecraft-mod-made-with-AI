package com.fnfmod.client.render;

import com.fnfmod.entity.MachineHitboxEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

/** Cyan Designer-only outline for persistent entity hitboxes. */
public final class MachineHitboxEntityRenderer extends EntityRenderer<MachineHitboxEntity> {
    private static final ResourceLocation TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/misc/white.png");

    public MachineHitboxEntityRenderer(EntityRendererProvider.Context context) { super(context); }

    @Override
    public void render(MachineHitboxEntity entity, float yaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight) {
        if (!MachineAnchorRenderer.holdingDesigner()) return;
        var box = entity.getBoundingBox().move(-entity.getX(), -entity.getY(), -entity.getZ());
        LevelRenderer.renderLineBox(poseStack, buffers.getBuffer(OverlayLines.thickRenderType()),
                box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ,
                0.1f, 0.85f, 1f, 0.95f);
    }

    @Override public ResourceLocation getTextureLocation(MachineHitboxEntity entity) { return TEXTURE; }
}
