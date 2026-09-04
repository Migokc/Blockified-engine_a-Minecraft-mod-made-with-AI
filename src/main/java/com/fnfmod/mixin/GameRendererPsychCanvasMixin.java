package com.fnfmod.mixin;

import com.fnfmod.client.render.PsychResolutionController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.PostChain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Keeps the vanilla menu-blur post chain at the renderer's real target size. */
@Mixin(GameRenderer.class)
public abstract class GameRendererPsychCanvasMixin {
    @Redirect(method = "loadBlurEffect", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/PostChain;resize(II)V"))
    private void fnfmod$resizeBlurToFramebuffer(PostChain chain, int width, int height) {
        Minecraft minecraft = Minecraft.getInstance();
        if (PsychResolutionController.enabled() && minecraft != null
                && minecraft.getWindow() != null) {
            chain.resize(PsychResolutionController.rawWidth(minecraft.getWindow()),
                    PsychResolutionController.rawHeight(minecraft.getWindow()));
            return;
        }
        chain.resize(width, height);
    }
}
