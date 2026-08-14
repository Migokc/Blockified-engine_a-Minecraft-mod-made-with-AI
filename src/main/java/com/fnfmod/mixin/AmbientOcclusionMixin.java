package com.fnfmod.mixin;

import com.fnfmod.client.render.DirectionalShadingControl;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Prevents smooth corner lighting from surviving the flat block-lighting mode. */
@Mixin(Minecraft.class)
public abstract class AmbientOcclusionMixin {

    @Inject(method = "useAmbientOcclusion", at = @At("HEAD"), cancellable = true)
    private static void fnfmod$disableAmbientOcclusion(CallbackInfoReturnable<Boolean> callback) {
        if (!DirectionalShadingControl.blockShadingEnabled()) callback.setReturnValue(false);
    }
}
