package com.fnfmod.client.render;

import com.fnfmod.FnfMod;
import com.fnfmod.machine.MachineHitboxService;
import com.fnfmod.net.FnfPayloads;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Client-only live selection/ready wireframe; never creates world blocks. */
public final class MachineHitboxPreview {
    private static byte stage;
    private static byte mode;
    private static Vec3 first;
    private static BlockPos firstCell;
    private static AABB readyBounds;

    private MachineHitboxPreview() {}

    public static void apply(FnfPayloads.HitboxSelectionStateS2C payload) {
        stage = payload.stage();
        mode = payload.mode();
        first = null;
        firstCell = null;
        readyBounds = null;
        if (stage == 2) {
            first = new Vec3(payload.minX(), payload.minY(), payload.minZ());
            firstCell = BlockPos.containing(first);
        } else if (stage == 3) {
            readyBounds = new AABB(payload.minX(), payload.minY(), payload.minZ(),
                    payload.maxX(), payload.maxY(), payload.maxZ());
        }
    }

    public static void clear() {
        stage = 0;
        first = null;
        firstCell = null;
        readyBounds = null;
    }

    public static void render(PoseStack poseStack, Camera camera) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || stage == 0) return;
        boolean designer = minecraft.player.getMainHandItem().is(FnfMod.FUNKIN_DESIGNER.get())
                || minecraft.player.getOffhandItem().is(FnfMod.FUNKIN_DESIGNER.get());
        boolean anchor = minecraft.player.getMainHandItem().is(FnfMod.MACHINE_ANCHOR_ITEM.get())
                || minecraft.player.getOffhandItem().is(FnfMod.MACHINE_ANCHOR_ITEM.get());
        if (stage < 3 && !designer || stage == 3 && !designer && !anchor) return;

        AABB bounds = stage == 3 ? readyBounds : liveBounds(minecraft);
        if (bounds == null) return;
        var buffers = minecraft.renderBuffers().bufferSource();
        var lines = OverlayLines.thickRenderType();
        var vertices = buffers.getBuffer(lines);
        Vec3 view = camera.getPosition();
        poseStack.pushPose();
        poseStack.translate(-view.x, -view.y, -view.z);
        if (stage == 3) {
            LevelRenderer.renderLineBox(poseStack, vertices, bounds.minX, bounds.minY, bounds.minZ,
                    bounds.maxX, bounds.maxY, bounds.maxZ, 0.2f, 1f, 0.35f, 0.95f);
        } else {
            LevelRenderer.renderLineBox(poseStack, vertices, bounds.minX, bounds.minY, bounds.minZ,
                    bounds.maxX, bounds.maxY, bounds.maxZ, 1f, 0.8f, 0.1f, 0.95f);
        }
        poseStack.popPose();
        buffers.endBatch(lines);
    }

    private static AABB liveBounds(Minecraft minecraft) {
        if (!(minecraft.hitResult instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK) return null;
        Direction face = hit.getDirection();
        BlockPos cell = hit.getBlockPos().relative(face);
        Vec3 normal = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
        Vec3 point = mode == MachineHitboxService.FULL_BLOCKS
                ? Vec3.atLowerCornerOf(cell) : hit.getLocation().add(normal.scale(0.001));
        if (stage == 1 || first == null) {
            return mode == MachineHitboxService.FULL_BLOCKS
                    ? new AABB(cell) : precise(point, point);
        }
        if (mode == MachineHitboxService.FULL_BLOCKS) {
            BlockPos a = firstCell;
            return new AABB(Math.min(a.getX(), cell.getX()), Math.min(a.getY(), cell.getY()),
                    Math.min(a.getZ(), cell.getZ()), Math.max(a.getX(), cell.getX()) + 1,
                    Math.max(a.getY(), cell.getY()) + 1, Math.max(a.getZ(), cell.getZ()) + 1);
        }
        return precise(first, point);
    }

    private static AABB precise(Vec3 a, Vec3 b) {
        double minX = Math.min(a.x, b.x), minY = Math.min(a.y, b.y), minZ = Math.min(a.z, b.z);
        double maxX = Math.max(a.x, b.x), maxY = Math.max(a.y, b.y), maxZ = Math.max(a.z, b.z);
        double minimum = 1.0 / 16.0;
        if (maxX - minX < minimum) maxX = minX + minimum;
        if (maxY - minY < minimum) maxY = minY + minimum;
        if (maxZ - minZ < minimum) maxZ = minZ + minimum;
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
