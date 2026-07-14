package com.fnfmod.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

/** Draws world-camera Lua sprites after their world transform has been applied. */
final class LuaWorldSpriteRenderer {
    private static final ResourceLocation WHITE_TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/misc/white.png");

    private LuaWorldSpriteRenderer() {}

    static void render(PoseStack poseStack, MultiBufferSource.BufferSource buffers,
                       LuaWorldObject.Sprite sprite, int light) {
        if (sprite.width() <= 0 || sprite.height() <= 0) return;
        ResourceLocation texture = sprite.texture() == null ? WHITE_TEXTURE : sprite.texture();
        RenderType renderType = sprite.lighting()
                ? RenderType.entityTranslucent(texture)
                : RenderType.entityTranslucentEmissive(texture);
        VertexConsumer vertices = buffers.getBuffer(renderType);

        float left = (float) (-sprite.width() * 0.5);
        float top = (float) (-sprite.height() * 0.5);
        float drawWidth = (float) sprite.width();
        float drawHeight = (float) sprite.height();
        float u0 = 0, v0 = 0, u1 = 1, v1 = 1;
        boolean rotated = false;
        if (sprite.frame() != null) {
            LuaWorldObject.Frame frame = sprite.frame();
            float frameScaleX = (float) (sprite.width() / Math.max(1, sprite.graphicWidth()));
            float frameScaleY = (float) (sprite.height() / Math.max(1, sprite.graphicHeight()));
            left += (float) ((-frame.frameX() - sprite.animationOffsetX()) * frameScaleX);
            top += (float) ((-frame.frameY() - sprite.animationOffsetY()) * frameScaleY);
            drawWidth = (frame.rotated() ? frame.height() : frame.width()) * frameScaleX;
            drawHeight = (frame.rotated() ? frame.width() : frame.height()) * frameScaleY;
            u0 = frame.x() / (float) Math.max(1, sprite.textureWidth());
            v0 = frame.y() / (float) Math.max(1, sprite.textureHeight());
            u1 = (frame.x() + frame.width()) / (float) Math.max(1, sprite.textureWidth());
            v1 = (frame.y() + frame.height()) / (float) Math.max(1, sprite.textureHeight());
            rotated = frame.rotated();
        }

        int red = sprite.color() >> 16 & 255;
        int green = sprite.color() >> 8 & 255;
        int blue = sprite.color() & 255;
        int alpha = Math.max(0, Math.min(255, (int) Math.round(sprite.alpha() * 255)));
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
