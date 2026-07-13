package com.fnfmod.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Draws immutable Lua sprite snapshots in the Minecraft level. */
public final class LuaWorldSpriteRenderer {
    /** Lua world-camera pixels per Minecraft block (64 px = 1 block). */
    public static final float PIXEL_SCALE = 1f / 64f;
    private static final ResourceLocation WHITE_TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/misc/white.png");

    /** Immutable atlas data for the currently displayed animation frame. */
    public record Frame(int x, int y, int width, int height, int frameX, int frameY,
                        boolean rotated) {}

    /**
     * Render-only state copied from the Lua runtime. Keeping this snapshot free
     * of runtime callbacks prevents level rendering from mutating script state.
     */
    public record Sprite(
            ResourceLocation texture,
            int textureWidth,
            int textureHeight,
            Frame frame,
            double animationOffsetX,
            double animationOffsetY,
            double x,
            double y,
            double z,
            double width,
            double height,
            double graphicWidth,
            double graphicHeight,
            double scaleX,
            double scaleY,
            double alpha,
            double angle,
            int color,
            boolean billboard,
            boolean lighting
    ) {}

    private LuaWorldSpriteRenderer() {}

    /**
     * The speakers are the origin. X points toward stage-right, Y points down
     * like Psych screen coordinates, and Z points toward the stage camera.
     */
    public static void render(PoseStack poseStack, Camera camera, BlockPos speakers,
                              Direction facing, List<Sprite> sprites) {
        if (sprites.isEmpty()) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;

        Direction stageFacing = facing == null ? Direction.NORTH : facing;
        Direction stageRight = stageFacing.getCounterClockWise();
        Vec3 origin = Vec3.atCenterOf(speakers);
        Vec3 cameraPos = camera.getPosition();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();

        for (int i = 0; i < sprites.size(); i++) {
            Sprite sprite = sprites.get(i);
            if (sprite.alpha <= 0 || sprite.width <= 0 || sprite.height <= 0) continue;

            // A tiny order offset prevents equal-depth objects from z-fighting
            // while preserving explicit Z movement and tweening.
            double depth = sprite.z * PIXEL_SCALE + i * 0.0001;
            Vec3 position = origin.add(
                    stageRight.getStepX() * sprite.x * PIXEL_SCALE + stageFacing.getStepX() * depth,
                    -sprite.y * PIXEL_SCALE,
                    stageRight.getStepZ() * sprite.x * PIXEL_SCALE + stageFacing.getStepZ() * depth);

            poseStack.pushPose();
            poseStack.translate(position.x - cameraPos.x, position.y - cameraPos.y, position.z - cameraPos.z);
            if (sprite.billboard) {
                poseStack.mulPose(camera.rotation());
            } else {
                poseStack.mulPose(Axis.YP.rotationDegrees(-stageFacing.toYRot()));
            }
            poseStack.scale((float) sprite.scaleX * PIXEL_SCALE,
                    (float) -sprite.scaleY * PIXEL_SCALE, PIXEL_SCALE);
            if (sprite.angle != 0) poseStack.mulPose(Axis.ZP.rotationDegrees((float) sprite.angle));

            int light = sprite.lighting
                    ? LevelRenderer.getLightColor(minecraft.level, BlockPos.containing(position))
                    : LightTexture.FULL_BRIGHT;
            renderSprite(poseStack, buffers, sprite, light);
            poseStack.popPose();
        }
    }

    private static void renderSprite(PoseStack poseStack, MultiBufferSource.BufferSource buffers,
                                     Sprite sprite, int light) {
        ResourceLocation texture = sprite.texture == null ? WHITE_TEXTURE : sprite.texture;
        RenderType renderType = sprite.lighting
                ? RenderType.entityTranslucent(texture)
                : RenderType.entityTranslucentEmissive(texture);
        VertexConsumer vertices = buffers.getBuffer(renderType);

        float left = (float) (-sprite.width * 0.5);
        float top = (float) (-sprite.height * 0.5);
        float drawWidth = (float) sprite.width;
        float drawHeight = (float) sprite.height;
        float u0 = 0, v0 = 0, u1 = 1, v1 = 1;
        boolean rotated = false;
        if (sprite.frame != null) {
            Frame frame = sprite.frame;
            float frameScaleX = (float) (sprite.width / Math.max(1, sprite.graphicWidth));
            float frameScaleY = (float) (sprite.height / Math.max(1, sprite.graphicHeight));
            left += (float) ((-frame.frameX - sprite.animationOffsetX) * frameScaleX);
            top += (float) ((-frame.frameY - sprite.animationOffsetY) * frameScaleY);
            drawWidth = (frame.rotated ? frame.height : frame.width) * frameScaleX;
            drawHeight = (frame.rotated ? frame.width : frame.height) * frameScaleY;
            u0 = frame.x / (float) Math.max(1, sprite.textureWidth);
            v0 = frame.y / (float) Math.max(1, sprite.textureHeight);
            u1 = (frame.x + frame.width) / (float) Math.max(1, sprite.textureWidth);
            v1 = (frame.y + frame.height) / (float) Math.max(1, sprite.textureHeight);
            rotated = frame.rotated;
        }

        int red = sprite.color >> 16 & 255;
        int green = sprite.color >> 8 & 255;
        int blue = sprite.color & 255;
        int alpha = Math.max(0, Math.min(255, (int) Math.round(sprite.alpha * 255)));
        float right = left + drawWidth;
        float bottom = top + drawHeight;
        PoseStack.Pose pose = poseStack.last();
        if (rotated) {
            // Packed frame is clockwise; rotate its UVs back without changing
            // the object's world transform or animation offsets.
            vertex(vertices, pose, left, bottom, u0, v0, red, green, blue, alpha, light);
            vertex(vertices, pose, right, bottom, u0, v1, red, green, blue, alpha, light);
            vertex(vertices, pose, right, top, u1, v1, red, green, blue, alpha, light);
            vertex(vertices, pose, left, top, u1, v0, red, green, blue, alpha, light);
        } else {
            vertex(vertices, pose, left, bottom, u0, v1, red, green, blue, alpha, light);
            vertex(vertices, pose, right, bottom, u1, v1, red, green, blue, alpha, light);
            vertex(vertices, pose, right, top, u1, v0, red, green, blue, alpha, light);
            vertex(vertices, pose, left, top, u0, v0, red, green, blue, alpha, light);
        }
        buffers.endBatch(renderType);
    }

    private static void vertex(VertexConsumer vertices, PoseStack.Pose pose, float x, float y,
                               float u, float v, int red, int green, int blue, int alpha, int light) {
        vertices.addVertex(pose, x, y, 0)
                .setColor(red, green, blue, alpha)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, 0, 0, 1);
    }
}
