package com.fnfmod.client.render;

import com.fnfmod.net.FnfPayloads;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Map;

/** Transient, non-colliding stage marker for a second human player. */
public final class SongJoinGuide {
    private record Marker(double x, double y, double z, long expiresAtNanos) {}
    private static final Map<BlockPos, Marker> MARKERS = new HashMap<>();
    private static final long LIFETIME_NANOS = 1_300_000_000L;

    private SongJoinGuide() {}

    public static void accept(FnfPayloads.JoinGuideS2C payload) {
        if (!Double.isFinite(payload.x()) || !Double.isFinite(payload.y())
                || !Double.isFinite(payload.z())) return;
        MARKERS.put(payload.machinePos(), new Marker(payload.x(), payload.y(), payload.z(),
                System.nanoTime() + LIFETIME_NANOS));
    }

    public static void clear() { MARKERS.clear(); }

    public static void render(PoseStack poseStack, Camera camera) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || minecraft.screen != null) return;
        long now = System.nanoTime();
        MARKERS.entrySet().removeIf(entry -> entry.getValue().expiresAtNanos() < now);
        if (MARKERS.isEmpty()) return;
        RenderType type = RenderType.lightning();
        VertexConsumer vertices = minecraft.renderBuffers().bufferSource().getBuffer(type);
        Vec3 view = camera.getPosition();
        poseStack.pushPose();
        poseStack.translate(-view.x, -view.y, -view.z);
        Matrix4f matrix = poseStack.last().pose();
        float pulse = 0.86f + 0.14f * (float) Math.sin(now / 330_000_000.0);
        for (Marker marker : MARKERS.values()) {
            if (minecraft.player.distanceToSqr(marker.x, marker.y, marker.z) > 48 * 48) continue;
            drawMarker(vertices, matrix, marker, pulse);
        }
        poseStack.popPose();
        minecraft.renderBuffers().bufferSource().endBatch(type);
    }

    private static void drawMarker(VertexConsumer out, Matrix4f pose, Marker marker, float pulse) {
        float x0 = (float) marker.x - 0.65f, x1 = (float) marker.x + 0.65f;
        float z0 = (float) marker.z - 0.65f, z1 = (float) marker.z + 0.65f;
        float bottom = (float) marker.y + 0.02f;
        float height = 2.08f;
        // The GPU interpolates alpha between bottom and top vertices for a truly
        // smooth gradient (not eight visible bands). No top/bottom faces or collision.
        int bottomAlpha = (int) (115 * pulse);
        float top = bottom + height;
        face(out, pose, x0, bottom, z0, x1, z0, top, bottomAlpha, 0);
        face(out, pose, x1, bottom, z1, x0, z1, top, bottomAlpha, 0);
        face(out, pose, x1, bottom, z0, x1, z1, top, bottomAlpha, 0);
        face(out, pose, x0, bottom, z1, x0, z0, top, bottomAlpha, 0);
        float lineTop = bottom + 1f / 16f;
        face(out, pose, x0, bottom, z0, x1, z0, lineTop, 245, 245);
        face(out, pose, x1, bottom, z1, x0, z1, lineTop, 245, 245);
        face(out, pose, x1, bottom, z0, x1, z1, lineTop, 245, 245);
        face(out, pose, x0, bottom, z1, x0, z0, lineTop, 245, 245);
    }

    private static void face(VertexConsumer out, Matrix4f pose,
                             float x0, float y0, float z0, float x1,
                             float z1, float top, int lowAlpha, int highAlpha) {
        vertex(out, pose, x0, y0, z0, lowAlpha);
        vertex(out, pose, x1, y0, z1, lowAlpha);
        vertex(out, pose, x1, top, z1, highAlpha);
        vertex(out, pose, x0, top, z0, highAlpha);
        // Render both sides even when a shader pack enables back-face culling.
        vertex(out, pose, x0, top, z0, highAlpha);
        vertex(out, pose, x1, top, z1, highAlpha);
        vertex(out, pose, x1, y0, z1, lowAlpha);
        vertex(out, pose, x0, y0, z0, lowAlpha);
    }

    private static void vertex(VertexConsumer out, Matrix4f pose,
                               float x, float y, float z, int alpha) {
        out.addVertex(pose, x, y, z).setColor(255, 111, 30, alpha);
    }
}
