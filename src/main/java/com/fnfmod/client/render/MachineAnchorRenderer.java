package com.fnfmod.client.render;

import com.fnfmod.FnfMod;
import com.fnfmod.block.MachineAnchorBlock;
import com.fnfmod.block.MachineAnchorBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;

/** Tool-only orange outline for virtual machine origin/facing. */
public final class MachineAnchorRenderer implements BlockEntityRenderer<MachineAnchorBlockEntity> {
    public MachineAnchorRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(MachineAnchorBlockEntity anchor, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (!holdingDesigner()) return;
        VertexConsumer vertices = buffers.getBuffer(OverlayLines.thickRenderType());
        LevelRenderer.renderLineBox(poseStack, vertices, 0.02, 0.02, 0.02, 0.98, 0.98, 0.98,
                1f, 0.55f, 0.08f, 1f);
        Direction facing = anchor.getBlockState().hasProperty(MachineAnchorBlock.FACING)
                ? anchor.getBlockState().getValue(MachineAnchorBlock.FACING) : Direction.NORTH;
        PoseStack.Pose pose = poseStack.last();
        float endX = 0.5f + facing.getStepX() * 0.85f;
        float endZ = 0.5f + facing.getStepZ() * 0.85f;
        vertex(vertices, pose, 0.5f, 0.5f, 0.5f, facing);
        vertex(vertices, pose, endX, 0.5f, endZ, facing);
    }

    private static void vertex(VertexConsumer vertices, PoseStack.Pose pose, float x, float y, float z,
                               Direction normal) {
        vertices.addVertex(pose, x, y, z).setColor(255, 120, 20, 255)
                .setNormal(pose, normal.getStepX(), normal.getStepY(), normal.getStepZ());
    }

    static boolean holdingDesigner() {
        var player = Minecraft.getInstance().player;
        return player != null && (player.getMainHandItem().is(FnfMod.FUNKIN_DESIGNER.get())
                || player.getOffhandItem().is(FnfMod.FUNKIN_DESIGNER.get()));
    }
}
