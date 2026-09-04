package com.fnfmod.mixin;

import com.fnfmod.client.render.PsychResolutionController;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Routes Minecraft's normal frame through the native-resolution 16:9 main target. */
@Mixin(Minecraft.class)
public abstract class MinecraftPsychCanvasMixin {
    @Redirect(method = "runTick", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;bindWrite(Z)V"))
    private void fnfmod$beginCanvasFrame(RenderTarget target, boolean setViewport) {
        PsychResolutionController.beginFrame(target, setViewport);
    }

    @Redirect(method = "runTick", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;unbindWrite()V"))
    private void fnfmod$endCanvasFrame(RenderTarget target) {
        PsychResolutionController.endFrame(target);
    }

    @Redirect(method = "runTick", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;blitToScreen(II)V"))
    private void fnfmod$presentCanvas(RenderTarget target, int width, int height) {
        PsychResolutionController.present(target, width, height);
    }
}
