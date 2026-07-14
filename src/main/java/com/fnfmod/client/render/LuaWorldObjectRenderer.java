package com.fnfmod.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Positions ordered Lua objects in the Minecraft world and delegates their drawing by type. */
public final class LuaWorldObjectRenderer {
    /** Lua world-camera pixels per Minecraft block (64 px = 1 block). */
    public static final float PIXEL_SCALE = 1f / 64f;

    private LuaWorldObjectRenderer() {}

    /**
     * The speakers are the origin. X points toward stage-right, Y points down
     * like Psych screen coordinates, and Z points toward the stage camera.
     */
    public static void render(PoseStack poseStack, Camera camera, BlockPos speakers,
                              Direction facing, List<LuaWorldObject> objects) {
        if (objects.isEmpty()) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;

        Direction stageFacing = facing == null ? Direction.NORTH : facing;
        Direction stageRight = stageFacing.getCounterClockWise();
        Vec3 origin = Vec3.atCenterOf(speakers);
        Vec3 cameraPos = camera.getPosition();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();

        for (int i = 0; i < objects.size(); i++) {
            LuaWorldObject object = objects.get(i);
            if (object.alpha() <= 0) continue;

            // A tiny order offset prevents equal-depth objects from z-fighting
            // while preserving explicit Z movement and tweening.
            double depth = object.z() * PIXEL_SCALE + i * 0.0001;
            Vec3 position = origin.add(
                    stageRight.getStepX() * object.x() * PIXEL_SCALE + stageFacing.getStepX() * depth,
                    -object.y() * PIXEL_SCALE,
                    stageRight.getStepZ() * object.x() * PIXEL_SCALE + stageFacing.getStepZ() * depth);

            poseStack.pushPose();
            poseStack.translate(position.x - cameraPos.x, position.y - cameraPos.y, position.z - cameraPos.z);
            int light = object.lighting()
                    ? LevelRenderer.getLightColor(minecraft.level, BlockPos.containing(position))
                    : LightTexture.FULL_BRIGHT;

            if (object instanceof LuaWorldObject.Sprite sprite) {
                orientSprite(poseStack, camera, stageFacing, sprite.billboard());
                applyLocalRotation(poseStack, sprite);
                poseStack.scale((float) sprite.scaleX() * PIXEL_SCALE,
                        (float) -sprite.scaleY() * PIXEL_SCALE, PIXEL_SCALE);
                LuaWorldSpriteRenderer.render(poseStack, buffers, sprite, light);
            } else if (object instanceof LuaWorldObject.Text text) {
                orientText(poseStack, camera, stageFacing, text.billboard());
                applyLocalRotation(poseStack, text);
                float textScale = Math.max(0.25f, text.textSize() / 9f);
                poseStack.scale((float) -text.scaleX() * PIXEL_SCALE * textScale,
                        (float) -text.scaleY() * PIXEL_SCALE * textScale, PIXEL_SCALE);
                LuaWorldTextRenderer.render(poseStack, buffers, text, textScale, light);
            }
            poseStack.popPose();

            // Flush between objects so setObjectOrder also works across text
            // and sprite render types, rather than only within one buffer.
            buffers.endBatch();
        }
    }

    private static void orientSprite(PoseStack poseStack, Camera camera,
                                     Direction stageFacing, boolean billboard) {
        if (billboard) poseStack.mulPose(camera.rotation());
        else poseStack.mulPose(Axis.YP.rotationDegrees(-stageFacing.toYRot()));
    }

    private static void orientText(PoseStack poseStack, Camera camera,
                                   Direction stageFacing, boolean billboard) {
        if (billboard) {
            // Minecraft name tags use a mirrored XY text plane after applying
            // the camera quaternion, which keeps glyphs readable from the camera.
            poseStack.mulPose(camera.rotation());
        } else {
            // The extra half-turn compensates for the mirrored text plane and
            // makes fixed text face the same stage direction as fixed sprites.
            poseStack.mulPose(Axis.YP.rotationDegrees(180f - stageFacing.toYRot()));
        }
    }

    /** Applies local pitch, yaw, then roll after billboard/stage orientation. */
    private static void applyLocalRotation(PoseStack poseStack, LuaWorldObject object) {
        if (object.rotationX() != 0) {
            poseStack.mulPose(Axis.XP.rotationDegrees((float) object.rotationX()));
        }
        if (object.rotationY() != 0) {
            poseStack.mulPose(Axis.YP.rotationDegrees((float) object.rotationY()));
        }
        if (object.angle() != 0) {
            poseStack.mulPose(Axis.ZP.rotationDegrees((float) object.angle()));
        }
    }
}
