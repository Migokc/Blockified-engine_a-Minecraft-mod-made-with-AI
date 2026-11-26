package com.fnfmod.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

/** Draws world-camera Lua sprites after their world transform has been applied. */
final class LuaWorldSpriteRenderer {
    record Bounds(float left, float top, float width, float height) {
        float centerX() { return left + width * 0.5f; }
        float centerY() { return top + height * 0.5f; }
    }
    private static final ResourceLocation WHITE_TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/misc/white.png");

    private LuaWorldSpriteRenderer() {}

    static void render(PoseStack poseStack, MultiBufferSource buffers,
                       LuaWorldObject.Sprite sprite, int light) {
        render(poseStack, buffers, sprite, light, true);
    }

    /** Entity renderers append to Minecraft/IRLights' owner-managed batch. */
    static void renderBatched(PoseStack poseStack, MultiBufferSource buffers,
                              LuaWorldObject.Sprite sprite, int light) {
        render(poseStack, buffers, sprite, light, false);
    }

    private static void render(PoseStack poseStack, MultiBufferSource buffers,
                               LuaWorldObject.Sprite sprite, int light, boolean flush) {
        if (sprite.width() <= 0 || sprite.height() <= 0) return;
        ResourceLocation texture = sprite.texture() == null ? WHITE_TEXTURE : sprite.texture();
        boolean layer = LuaLayerRenderer.isSurface(texture);
        if (layer && !IrlightsShadowCompat.isBaking()) {
            if (buffers instanceof MultiBufferSource.BufferSource source) source.endBatch();
            LuaLayerRenderer.drawTexture(poseStack.last().pose(), texture,
                    originLeft(sprite), originTop(sprite),
                    (float)sprite.width(), (float)sprite.height(), (float)sprite.alpha(), true, sprite.seeThrough());
            return;
        }
        LuaWorldObject.RenderMode mode = sprite.renderMode() == null
                ? LuaWorldObject.RenderMode.resolve(null, sprite.lighting()) : sprite.renderMode();
        RenderType base = switch (mode) {
            case LIT -> RenderType.entityTranslucent(texture);
            // The text material is lightmap-aware but has no entity-normal shading.
            // It therefore stays genuinely flat in vanilla and is substantially less
            // likely to be treated as a glowing material by Iris shader packs.
            case FLAT -> FlatWorldRenderTypes.get(texture, false, false);
            case EMISSIVE -> RenderType.entityTranslucentEmissive(texture);
        };
        RenderType renderType = sprite.seeThrough()
                ? mode == LuaWorldObject.RenderMode.FLAT
                    ? FlatWorldRenderTypes.get(texture, true, false)
                    : SeeThroughSprite.renderType(texture, base)
                : base;
        VertexConsumer vertices = buffers.getBuffer(renderType);

        Bounds bounds = visibleBounds(sprite);
        float left = bounds.left();
        float top = bounds.top();
        float drawWidth = bounds.width();
        float drawHeight = bounds.height();
        float u0 = 0, v0 = 0, u1 = 1, v1 = 1;
        if (layer) { v0 = 1; v1 = 0; }
        boolean rotated = false;
        if (sprite.frame() != null) {
            LuaWorldObject.Frame frame = sprite.frame();
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
            vertex(vertices, pose, left, bottom, u0, v0, red, green, blue, alpha, light, mode);
            vertex(vertices, pose, right, bottom, u0, v1, red, green, blue, alpha, light, mode);
            vertex(vertices, pose, right, top, u1, v1, red, green, blue, alpha, light, mode);
            vertex(vertices, pose, left, top, u1, v0, red, green, blue, alpha, light, mode);
        } else {
            vertex(vertices, pose, left, bottom, u0, v1, red, green, blue, alpha, light, mode);
            vertex(vertices, pose, right, bottom, u1, v1, red, green, blue, alpha, light, mode);
            vertex(vertices, pose, right, top, u1, v0, red, green, blue, alpha, light, mode);
            vertex(vertices, pose, left, top, u0, v0, red, green, blue, alpha, light, mode);
        }
        if (flush && buffers instanceof MultiBufferSource.BufferSource source) source.endBatch(renderType);
    }

    /** Vertex bounds before the shared world-sprite transform is applied. */
    static Bounds visibleBounds(LuaWorldObject.Sprite sprite) {
        float left = originLeft(sprite);
        float top = originTop(sprite);
        float width = (float) sprite.width();
        float height = (float) sprite.height();
        if (sprite.frame() != null) {
            LuaWorldObject.Frame frame = sprite.frame();
            float sx = (float) (sprite.width() / Math.max(1, sprite.graphicWidth()));
            float sy = (float) (sprite.height() / Math.max(1, sprite.graphicHeight()));
            left += (float) ((-frame.frameX() - sprite.animationOffsetX()) * sx);
            top += (float) ((-frame.frameY() - sprite.animationOffsetY()) * sy);
            width = (frame.rotated() ? frame.height() : frame.width()) * sx;
            height = (frame.rotated() ? frame.width() : frame.height()) * sy;
        }
        return new Bounds(left, top, width, height);
    }

    private static float originLeft(LuaWorldObject.Sprite sprite) {
        double sourceWidth = Math.max(1.0, sprite.graphicWidth());
        return (float) (-sprite.originX() * sprite.width() / sourceWidth);
    }

    private static float originTop(LuaWorldObject.Sprite sprite) {
        double sourceHeight = Math.max(1.0, sprite.graphicHeight());
        return (float) (-sprite.originY() * sprite.height() / sourceHeight);
    }

    private static void vertex(VertexConsumer vertices, PoseStack.Pose pose, float x, float y,
                               float u, float v, int red, int green, int blue, int alpha, int light,
                               LuaWorldObject.RenderMode mode) {
        VertexConsumer value = vertices.addVertex(pose, x, y, 0)
                .setColor(red, green, blue, alpha)
                .setUv(u, v)
                .setLight(light);
        // RenderType.text uses POSITION_COLOR_TEX_LIGHTMAP and has neither of
        // these NEW_ENTITY attributes.
        if (mode != LuaWorldObject.RenderMode.FLAT) {
            value.setOverlay(OverlayTexture.NO_OVERLAY).setNormal(pose, 0, 0, 1);
        }
    }
}
