package com.fnfmod.mixin;

import com.fnfmod.client.render.PerformerRotation;
import com.fnfmod.client.render.BbsObjectBorderRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Applies Blockified's BBS-only pitch, roll, and scale inside BBS's own pose scope. */
@Pseudo
@Mixin(targets = "mchorse.bbs_mod.client.renderer.MorphRenderer", remap = false)
public abstract class BbsMorphRendererMixin {

    /*
     * BBS renders a morphed player itself and cancels PlayerRenderer at HEAD, so
     * PlayerRenderer.setupRotations never runs. Inject immediately after BBS
     * pushes its pose instead: this makes Lua/free-camera transforms visible and
     * lets BBS's matching popPose contain them safely.
     */
    @Inject(
            method = "renderPlayer",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V",
                    shift = At.Shift.AFTER
            )
    )
    private static void fnfmod$applyBbsTransform(AbstractClientPlayer player, float yaw,
            float tickDelta, PoseStack poseStack, MultiBufferSource buffers, int light,
            CallbackInfoReturnable<Boolean> callback) {
        PerformerRotation.apply(player, poseStack);
    }

    /** BBS renders outside Minecraft's buffers; capture its visible form silhouette. */
    @Inject(
            method = "renderPlayer",
            at = @At(
                    value = "INVOKE",
                    target = "Lmchorse/bbs_mod/forms/FormUtilsClient;render(Lmchorse/bbs_mod/forms/forms/Form;Lmchorse/bbs_mod/forms/renderers/FormRenderingContext;)V",
                    shift = At.Shift.AFTER,
                    remap = false
            ),
            remap = false
    )
    private static void fnfmod$captureBbsBorder(AbstractClientPlayer player, float yaw,
            float tickDelta, PoseStack poseStack, MultiBufferSource buffers, int light,
            CallbackInfoReturnable<Boolean> callback) {
        BbsObjectBorderRenderer.capture(player, poseStack, light, tickDelta);
    }
}
