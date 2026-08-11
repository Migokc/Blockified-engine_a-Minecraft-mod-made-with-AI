package com.fnfmod.client.render;

import com.fnfmod.block.FunkinMachineBlock;
import com.fnfmod.block.FunkinMachineBlockEntity;
import com.fnfmod.machine.MachineDefinition;
import com.fnfmod.machine.MachineLibrary;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

import java.nio.file.Path;

/** Draws optional per-face textures over the built-in machine model. */
public final class FunkinMachineRenderer implements BlockEntityRenderer<FunkinMachineBlockEntity> {

    private static final float MIN = -0.001f;
    private static final float MAX = 1.001f;

    public FunkinMachineRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(FunkinMachineBlockEntity machine, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        MachineDefinition definition = MachineLibrary.get(machine.profileId());
        if (definition.builtIn() || definition.textures().isEmpty()) return;
        Direction front = machine.getBlockState().hasProperty(FunkinMachineBlock.FACING)
                ? machine.getBlockState().getValue(FunkinMachineBlock.FACING) : Direction.NORTH;
        for (Direction face : Direction.values()) {
            Path file = definition.texture(faceName(face, front));
            if (file == null) continue;
            ResourceLocation texture = MachineTextureCache.get(file);
            if (texture == null) texture = MissingAssetTexture.texture();
            VertexConsumer vertices = buffers.getBuffer(RenderType.entityCutoutNoCull(texture));
            quad(vertices, poseStack.last(), face, packedLight, packedOverlay);
        }
    }

    private static String faceName(Direction face, Direction front) {
        if (face == Direction.UP) return "top";
        if (face == Direction.DOWN) return "bottom";
        if (face == front) return "front";
        if (face == front.getOpposite()) return "back";
        return face == front.getClockWise() ? "right" : "left";
    }

    private static void quad(VertexConsumer out, PoseStack.Pose pose, Direction face,
                             int light, int overlay) {
        switch (face) {
            case NORTH -> {
                vertex(out, pose, MIN, MIN, MIN, 1, 1, face, light, overlay);
                vertex(out, pose, MIN, MAX, MIN, 1, 0, face, light, overlay);
                vertex(out, pose, MAX, MAX, MIN, 0, 0, face, light, overlay);
                vertex(out, pose, MAX, MIN, MIN, 0, 1, face, light, overlay);
            }
            case SOUTH -> {
                vertex(out, pose, MAX, MIN, MAX, 1, 1, face, light, overlay);
                vertex(out, pose, MAX, MAX, MAX, 1, 0, face, light, overlay);
                vertex(out, pose, MIN, MAX, MAX, 0, 0, face, light, overlay);
                vertex(out, pose, MIN, MIN, MAX, 0, 1, face, light, overlay);
            }
            case WEST -> {
                vertex(out, pose, MIN, MIN, MAX, 1, 1, face, light, overlay);
                vertex(out, pose, MIN, MAX, MAX, 1, 0, face, light, overlay);
                vertex(out, pose, MIN, MAX, MIN, 0, 0, face, light, overlay);
                vertex(out, pose, MIN, MIN, MIN, 0, 1, face, light, overlay);
            }
            case EAST -> {
                vertex(out, pose, MAX, MIN, MIN, 1, 1, face, light, overlay);
                vertex(out, pose, MAX, MAX, MIN, 1, 0, face, light, overlay);
                vertex(out, pose, MAX, MAX, MAX, 0, 0, face, light, overlay);
                vertex(out, pose, MAX, MIN, MAX, 0, 1, face, light, overlay);
            }
            case UP -> {
                vertex(out, pose, MIN, MAX, MIN, 0, 0, face, light, overlay);
                vertex(out, pose, MIN, MAX, MAX, 0, 1, face, light, overlay);
                vertex(out, pose, MAX, MAX, MAX, 1, 1, face, light, overlay);
                vertex(out, pose, MAX, MAX, MIN, 1, 0, face, light, overlay);
            }
            case DOWN -> {
                vertex(out, pose, MIN, MIN, MAX, 0, 0, face, light, overlay);
                vertex(out, pose, MIN, MIN, MIN, 0, 1, face, light, overlay);
                vertex(out, pose, MAX, MIN, MIN, 1, 1, face, light, overlay);
                vertex(out, pose, MAX, MIN, MAX, 1, 0, face, light, overlay);
            }
        }
    }

    private static void vertex(VertexConsumer out, PoseStack.Pose pose, float x, float y, float z,
                               float u, float v, Direction normal, int light, int overlay) {
        out.addVertex(pose, x, y, z).setColor(255, 255, 255, 255).setUv(u, v)
                .setOverlay(overlay == 0 ? OverlayTexture.NO_OVERLAY : overlay).setLight(light)
                .setNormal(pose, normal.getStepX(), normal.getStepY(), normal.getStepZ());
    }
}
