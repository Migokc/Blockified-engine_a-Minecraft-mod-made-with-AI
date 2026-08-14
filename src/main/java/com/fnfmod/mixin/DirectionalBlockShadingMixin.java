package com.fnfmod.mixin;

import com.fnfmod.client.render.DirectionalShadingControl;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Removes Minecraft's per-direction block brightness while gameplay requests it. */
@Mixin(ClientLevel.class)
public abstract class DirectionalBlockShadingMixin {

    @Inject(method = "getShade(Lnet/minecraft/core/Direction;Z)F", at = @At("HEAD"), cancellable = true)
    private void fnfmod$flatDirectionShade(Direction direction, boolean shade,
                                            CallbackInfoReturnable<Float> callback) {
        if (!DirectionalShadingControl.blockShadingEnabled()) callback.setReturnValue(1.0f);
    }

    @Inject(method = "getShade(FFFZ)F", at = @At("HEAD"), cancellable = true)
    private void fnfmod$flatVectorShade(float x, float y, float z, boolean shade,
                                        CallbackInfoReturnable<Float> callback) {
        if (!DirectionalShadingControl.blockShadingEnabled()) callback.setReturnValue(1.0f);
    }
}
