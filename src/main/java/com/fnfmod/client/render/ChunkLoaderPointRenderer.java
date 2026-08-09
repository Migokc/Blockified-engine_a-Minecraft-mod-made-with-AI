package com.fnfmod.client.render;

import com.fnfmod.FnfMod;
import com.fnfmod.block.ChunkLoaderPointBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;

/** Designer/point-item-only marker and chunk-radius boundary. */
public final class ChunkLoaderPointRenderer implements BlockEntityRenderer<ChunkLoaderPointBlockEntity> {

    public ChunkLoaderPointRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(ChunkLoaderPointBlockEntity point, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (!holdingPointTool()) return;
        float red = point.enabled() ? 0.15f : 1.0f;
        float green = point.enabled() ? 0.85f : 0.2f;
        float blue = point.enabled() ? 1.0f : 0.2f;
        VertexConsumer vertices = buffers.getBuffer(OverlayLines.thickRenderType());
        LevelRenderer.renderLineBox(poseStack, vertices, 0.02, 0.02, 0.02, 0.98, 0.98, 0.98,
                red, green, blue, 1.0f);

        int chunkX = point.getBlockPos().getX() >> 4;
        int chunkZ = point.getBlockPos().getZ() >> 4;
        int radius = point.radius();
        float minX = (chunkX - radius) * 16 - point.getBlockPos().getX();
        float maxX = (chunkX + radius + 1) * 16 - point.getBlockPos().getX();
        float minZ = (chunkZ - radius) * 16 - point.getBlockPos().getZ();
        float maxZ = (chunkZ + radius + 1) * 16 - point.getBlockPos().getZ();
        PoseStack.Pose pose = poseStack.last();
        line(vertices, pose, minX, 0.04f, minZ, maxX, 0.04f, minZ, red, green, blue);
        line(vertices, pose, maxX, 0.04f, minZ, maxX, 0.04f, maxZ, red, green, blue);
        line(vertices, pose, maxX, 0.04f, maxZ, minX, 0.04f, maxZ, red, green, blue);
        line(vertices, pose, minX, 0.04f, maxZ, minX, 0.04f, minZ, red, green, blue);
        line(vertices, pose, 0.5f, 0.1f, 0.5f, 0.5f, 1.45f, 0.5f, red, green, blue);
    }

    private static void line(VertexConsumer vertices, PoseStack.Pose pose,
                             float x1, float y1, float z1, float x2, float y2, float z2,
                             float red, float green, float blue) {
        vertices.addVertex(pose, x1, y1, z1).setColor(red, green, blue, 1.0f)
                .setNormal(pose, 0, 1, 0);
        vertices.addVertex(pose, x2, y2, z2).setColor(red, green, blue, 1.0f)
                .setNormal(pose, 0, 1, 0);
    }

    public static boolean holdingPointTool() {
        var player = Minecraft.getInstance().player;
        return player != null && (player.getMainHandItem().is(FnfMod.FUNKIN_DESIGNER.get())
                || player.getOffhandItem().is(FnfMod.FUNKIN_DESIGNER.get())
                || player.getMainHandItem().is(FnfMod.CHUNK_LOADER_POINT_ITEM.get())
                || player.getOffhandItem().is(FnfMod.CHUNK_LOADER_POINT_ITEM.get()));
    }

    @Override public boolean shouldRenderOffScreen(ChunkLoaderPointBlockEntity point) { return true; }
    @Override public int getViewDistance() { return 256; }
}
