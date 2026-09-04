package com.fnfmod.mixin;

import com.fnfmod.client.render.PsychResolutionController;
import net.minecraft.client.MouseHandler;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps screen hover, clicks, drags and scrolling aligned with the centered canvas. */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerPsychCanvasMixin {
    @Inject(method = "xpos", at = @At("RETURN"), cancellable = true)
    private void fnfmod$canvasMouseX(CallbackInfoReturnable<Double> cir) {
        cir.setReturnValue(PsychResolutionController.mapMouseX(cir.getReturnValue()));
    }

    @Inject(method = "ypos", at = @At("RETURN"), cancellable = true)
    private void fnfmod$canvasMouseY(CallbackInfoReturnable<Double> cir) {
        cir.setReturnValue(PsychResolutionController.mapMouseY(cir.getReturnValue()));
    }

    @Redirect(method = {"onPress", "onScroll", "handleAccumulatedMovement"},
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/MouseHandler;xpos:D",
                    opcode = Opcodes.GETFIELD))
    private double fnfmod$canvasFieldMouseX(MouseHandler handler) {
        return PsychResolutionController.mapMouseX(((MouseHandlerPsychCanvasAccessor) handler).fnfmod$rawX());
    }

    @Redirect(method = {"onPress", "onScroll", "handleAccumulatedMovement"},
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/MouseHandler;ypos:D",
                    opcode = Opcodes.GETFIELD))
    private double fnfmod$canvasFieldMouseY(MouseHandler handler) {
        return PsychResolutionController.mapMouseY(((MouseHandlerPsychCanvasAccessor) handler).fnfmod$rawY());
    }

    @Redirect(method = "handleAccumulatedMovement",
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/MouseHandler;accumulatedDX:D",
                    opcode = Opcodes.GETFIELD))
    private double fnfmod$canvasMouseDeltaX(MouseHandler handler) {
        return PsychResolutionController.mapMouseDeltaX(
                ((MouseHandlerPsychCanvasAccessor) handler).fnfmod$rawDeltaX());
    }

    @Redirect(method = "handleAccumulatedMovement",
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/MouseHandler;accumulatedDY:D",
                    opcode = Opcodes.GETFIELD))
    private double fnfmod$canvasMouseDeltaY(MouseHandler handler) {
        return PsychResolutionController.mapMouseDeltaY(
                ((MouseHandlerPsychCanvasAccessor) handler).fnfmod$rawDeltaY());
    }
}
