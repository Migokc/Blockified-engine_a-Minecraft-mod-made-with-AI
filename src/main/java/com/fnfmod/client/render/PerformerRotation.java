package com.fnfmod.client.render;

import com.fnfmod.client.anim.CharacterAnimations;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Client-only pitch/roll and XYZ scale applied to BBS forms without changing entity state. */
public final class PerformerRotation {
    private record Transform(float x, float z, float scaleX, float scaleY, float scaleZ) {}

    private static final Map<UUID, Transform> TRANSFORMS = new HashMap<>();

    private PerformerRotation() {}

    public static void set(Player player, double x, double z) {
        if (player == null) return;
        Transform old = transform(player);
        store(player, finite(x, 0), finite(z, 0), old.scaleX(), old.scaleY(), old.scaleZ());
    }

    public static void setScale(Player player, double x, double y, double z) {
        if (player == null) return;
        Transform old = transform(player);
        store(player, old.x(), old.z(), finite(x, 1), finite(y, 1), finite(z, 1));
    }

    public static void set(Player player, double rotationX, double rotationZ,
            double scaleX, double scaleY, double scaleZ) {
        if (player == null) return;
        store(player, finite(rotationX, 0), finite(rotationZ, 0),
                finite(scaleX, 1), finite(scaleY, 1), finite(scaleZ, 1));
    }

    public static double x(Player player) {
        return transform(player).x();
    }

    public static double z(Player player) {
        return transform(player).z();
    }

    public static double scaleX(Player player) { return transform(player).scaleX(); }
    public static double scaleY(Player player) { return transform(player).scaleY(); }
    public static double scaleZ(Player player) { return transform(player).scaleZ(); }

    public static void clear(Player player) {
        if (player != null) TRANSFORMS.remove(player.getUUID());
    }

    /** Called after vanilla establishes body yaw, leaving the existing yaw path untouched. */
    public static void apply(Player player, PoseStack poseStack) {
        Transform transform = player == null ? null : TRANSFORMS.get(player.getUUID());
        if (transform == null || !CharacterAnimations.hasActiveBbsForm(player)) return;
        if (transform.x() != 0) poseStack.mulPose(Axis.XP.rotationDegrees(transform.x()));
        if (transform.z() != 0) poseStack.mulPose(Axis.ZP.rotationDegrees(transform.z()));
        if (!unit(transform.scaleX()) || !unit(transform.scaleY()) || !unit(transform.scaleZ())) {
            poseStack.scale(transform.scaleX(), transform.scaleY(), transform.scaleZ());
        }
    }

    private static Transform transform(Player player) {
        Transform transform = player == null ? null : TRANSFORMS.get(player.getUUID());
        return transform == null ? new Transform(0, 0, 1, 1, 1) : transform;
    }

    private static void store(Player player, float x, float z, float scaleX, float scaleY, float scaleZ) {
        if (Math.abs(x) < 0.0001f && Math.abs(z) < 0.0001f
                && unit(scaleX) && unit(scaleY) && unit(scaleZ)) {
            TRANSFORMS.remove(player.getUUID());
        } else {
            TRANSFORMS.put(player.getUUID(), new Transform(x, z, scaleX, scaleY, scaleZ));
        }
    }

    private static boolean unit(float value) {
        return Math.abs(value - 1f) < 0.0001f;
    }

    private static float finite(double value, float fallback) {
        return Double.isFinite(value) ? (float) value : fallback;
    }
}
