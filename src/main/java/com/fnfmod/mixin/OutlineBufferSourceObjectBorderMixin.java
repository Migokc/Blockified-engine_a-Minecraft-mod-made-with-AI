package com.fnfmod.mixin;

import com.fnfmod.client.render.ObjectBorderRegistry;
import com.fnfmod.client.render.ObjectBorderRenderTypes;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.FastColor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/** Routes only Blockified borders through a world-depth-tested outline type. */
@Mixin(OutlineBufferSource.class)
public abstract class OutlineBufferSourceObjectBorderMixin {
    @Shadow @Final private MultiBufferSource.BufferSource bufferSource;
    @Shadow @Final private MultiBufferSource.BufferSource outlineBufferSource;
    @Shadow private int teamR;
    @Shadow private int teamG;
    @Shadow private int teamB;
    @Shadow private int teamA;

    @Inject(method = "getBuffer", at = @At("HEAD"), cancellable = true)
    private void fnfmod$depthTestedObjectBorder(RenderType renderType,
                                                CallbackInfoReturnable<VertexConsumer> callback) {
        if (!ObjectBorderRegistry.renderingCustomBorder()) return;
        if (renderType.isOutline()) {
            VertexConsumer outline = outlineBufferSource.getBuffer(
                    ObjectBorderRenderTypes.depthTested(renderType));
            callback.setReturnValue(new BorderVertexConsumer(outline, teamR, teamG, teamB, teamA));
            return;
        }
        VertexConsumer normal = bufferSource.getBuffer(renderType);
        Optional<RenderType> outlineType = renderType.outline();
        if (outlineType.isEmpty()) {
            callback.setReturnValue(normal);
            return;
        }
        VertexConsumer outline = outlineBufferSource.getBuffer(
                ObjectBorderRenderTypes.depthTested(outlineType.get()));
        callback.setReturnValue(VertexMultiConsumer.create(
                new BorderVertexConsumer(outline, teamR, teamG, teamB, teamA), normal));
    }

    private record BorderVertexConsumer(VertexConsumer delegate, int color) implements VertexConsumer {
        BorderVertexConsumer(VertexConsumer delegate, int red, int green, int blue, int alpha) {
            this(delegate, FastColor.ARGB32.color(alpha, red, green, blue));
        }

        @Override public VertexConsumer addVertex(float x, float y, float z) {
            delegate.addVertex(x, y, z).setColor(color);
            return this;
        }
        @Override public VertexConsumer setColor(int red, int green, int blue, int alpha) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { delegate.setUv(u, v); return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
    }
}
