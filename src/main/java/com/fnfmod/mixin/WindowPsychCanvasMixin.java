package com.fnfmod.mixin;

import com.fnfmod.client.render.PsychResolutionController;
import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Makes all vanilla and mod screens lay themselves out inside the 16:9 canvas. */
@Mixin(Window.class)
public abstract class WindowPsychCanvasMixin {
    @Inject(method = "getWidth", at = @At("HEAD"), cancellable = true)
    private void fnfmod$canvasPixelWidth(CallbackInfoReturnable<Integer> cir) {
        if (PsychResolutionController.enabled() && PsychResolutionController.rendering()) {
            cir.setReturnValue(PsychResolutionController.pixelWidth((Window) (Object) this));
        }
    }

    @Inject(method = "getHeight", at = @At("HEAD"), cancellable = true)
    private void fnfmod$canvasPixelHeight(CallbackInfoReturnable<Integer> cir) {
        if (PsychResolutionController.enabled() && PsychResolutionController.rendering()) {
            cir.setReturnValue(PsychResolutionController.pixelHeight((Window) (Object) this));
        }
    }

    @Inject(method = "getGuiScaledWidth", at = @At("HEAD"), cancellable = true)
    private void fnfmod$canvasGuiWidth(CallbackInfoReturnable<Integer> cir) {
        if (PsychResolutionController.enabled()) {
            cir.setReturnValue(PsychResolutionController.guiWidth((Window) (Object) this));
        }
    }

    @Inject(method = "getGuiScaledHeight", at = @At("HEAD"), cancellable = true)
    private void fnfmod$canvasGuiHeight(CallbackInfoReturnable<Integer> cir) {
        if (PsychResolutionController.enabled()) {
            cir.setReturnValue(PsychResolutionController.guiHeight((Window) (Object) this));
        }
    }
}
