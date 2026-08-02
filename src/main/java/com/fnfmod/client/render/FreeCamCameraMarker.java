package com.fnfmod.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Always-visible wireframe showing the exact camera transform captured when
 * free-cam was entered. The body sits behind the optical origin while the
 * frustum and coloured axes make yaw, pitch and roll unambiguous.
 */
public final class FreeCamCameraMarker {

    private FreeCamCameraMarker() {}

    public static void render(PoseStack poseStack, Camera viewingCamera, Vec3 origin,
                              Vector3f cameraLeft, Vector3f cameraUp, Vector3f cameraLook) {
        if (origin == null || cameraLeft == null || cameraUp == null || cameraLook == null) return;

        Vec3 right = unit(new Vec3(-cameraLeft.x(), -cameraLeft.y(), -cameraLeft.z()),
                new Vec3(1, 0, 0));
        Vec3 up = unit(new Vec3(cameraUp.x(), cameraUp.y(), cameraUp.z()),
                new Vec3(0, 1, 0));
        Vec3 forward = unit(new Vec3(cameraLook.x(), cameraLook.y(), cameraLook.z()),
                new Vec3(0, 0, 1));

        Vec3 view = viewingCamera.getPosition();
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        RenderType lines = OverlayLines.thickRenderType();
        VertexConsumer vertices = buffers.getBuffer(lines);

        poseStack.pushPose();
        poseStack.translate(origin.x - view.x, origin.y - view.y, origin.z - view.z);
        PoseStack.Pose pose = poseStack.last();

        // Camera body: compact box behind the optical origin.
        Vec3[] bodyBack = rectangle(right, up, forward, -0.30, 0.21, 0.14);
        Vec3[] bodyFront = rectangle(right, up, forward, -0.05, 0.21, 0.14);
        outline(vertices, pose, bodyBack, 235, 235, 235, 230);
        outline(vertices, pose, bodyFront, 235, 235, 235, 230);
        for (int i = 0; i < 4; i++) {
            line(vertices, pose, bodyBack[i], bodyFront[i], 235, 235, 235, 230);
        }

        // Lens and view frustum. Frustum points in exact captured look direction.
        Vec3[] lens = rectangle(right, up, forward, 0.0, 0.13, 0.085);
        Vec3[] far = rectangle(right, up, forward, 0.90, 0.45, 0.255);
        outline(vertices, pose, lens, 255, 196, 64, 245);
        outline(vertices, pose, far, 255, 196, 64, 220);
        for (int i = 0; i < 4; i++) {
            line(vertices, pose, lens[i], far[i], 255, 196, 64, 220);
        }

        // Small top finder gives camera silhouette and makes roll obvious.
        Vec3 finderLeft = up.scale(0.14).subtract(right.scale(0.09)).subtract(forward.scale(0.17));
        Vec3 finderRight = up.scale(0.14).add(right.scale(0.09)).subtract(forward.scale(0.17));
        Vec3 finderTop = up.scale(0.27).subtract(forward.scale(0.17));
        line(vertices, pose, finderLeft, finderTop, 235, 235, 235, 230);
        line(vertices, pose, finderTop, finderRight, 235, 235, 235, 230);
        line(vertices, pose, finderRight, finderLeft, 235, 235, 235, 230);

        // Exact origin plus familiar orientation axes: X right, Y up, Z/look forward.
        double cross = 0.055;
        line(vertices, pose, right.scale(-cross), right.scale(cross), 255, 255, 255, 255);
        line(vertices, pose, up.scale(-cross), up.scale(cross), 255, 255, 255, 255);
        line(vertices, pose, forward.scale(-cross), forward.scale(cross), 255, 255, 255, 255);
        line(vertices, pose, Vec3.ZERO, right.scale(0.38), 255, 80, 80, 255);
        line(vertices, pose, Vec3.ZERO, up.scale(0.38), 80, 255, 100, 255);
        line(vertices, pose, Vec3.ZERO, forward.scale(1.15), 80, 150, 255, 255);

        poseStack.popPose();
        buffers.endBatch(lines);
    }

    private static Vec3[] rectangle(Vec3 right, Vec3 up, Vec3 forward,
                                    double depth, double halfWidth, double halfHeight) {
        Vec3 center = forward.scale(depth);
        Vec3 horizontal = right.scale(halfWidth);
        Vec3 vertical = up.scale(halfHeight);
        return new Vec3[]{
                center.subtract(horizontal).subtract(vertical),
                center.add(horizontal).subtract(vertical),
                center.add(horizontal).add(vertical),
                center.subtract(horizontal).add(vertical)
        };
    }

    private static void outline(VertexConsumer vertices, PoseStack.Pose pose, Vec3[] corners,
                                int red, int green, int blue, int alpha) {
        for (int i = 0; i < corners.length; i++) {
            line(vertices, pose, corners[i], corners[(i + 1) % corners.length],
                    red, green, blue, alpha);
        }
    }

    private static void line(VertexConsumer vertices, PoseStack.Pose pose, Vec3 from, Vec3 to,
                             int red, int green, int blue, int alpha) {
        Vec3 normal = unit(to.subtract(from), new Vec3(0, 1, 0));
        vertex(vertices, pose, from, normal, red, green, blue, alpha);
        vertex(vertices, pose, to, normal, red, green, blue, alpha);
    }

    private static void vertex(VertexConsumer vertices, PoseStack.Pose pose, Vec3 point, Vec3 normal,
                               int red, int green, int blue, int alpha) {
        vertices.addVertex(pose, (float) point.x, (float) point.y, (float) point.z)
                .setColor(red, green, blue, alpha)
                .setNormal(pose, (float) normal.x, (float) normal.y, (float) normal.z);
    }

    private static Vec3 unit(Vec3 value, Vec3 fallback) {
        return value.lengthSqr() > 1.0e-10 ? value.normalize() : fallback;
    }
}
