package com.fnfmod.mixin;

import com.fnfmod.client.render.PsychResolutionController;
import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Expands only virtual-canvas viewport requests to the real framebuffer.
 * Menus such as the title panorama reset the viewport after GameRenderer's
 * initial setup, so correcting only that first call leaves their UI cropped.
 */
@Mixin(GlStateManager.class)
public abstract class GlStateManagerPsychCanvasMixin {
    private static boolean fnfmod$adjustingViewport;

    @Inject(method = "_viewport", at = @At("HEAD"), cancellable = true)
    private static void fnfmod$useFullFramebufferForCanvas(int x, int y, int width, int height,
                                                            CallbackInfo callback) {
        if (fnfmod$adjustingViewport || !PsychResolutionController.enabled()
                || !PsychResolutionController.rendering()) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getWindow() == null) return;

        int canvasWidth = PsychResolutionController.pixelWidth(minecraft.getWindow());
        int canvasHeight = PsychResolutionController.pixelHeight(minecraft.getWindow());
        if (x != 0 || y != 0 || width != canvasWidth || height != canvasHeight) return;

        fnfmod$adjustingViewport = true;
        try {
            GlStateManager._viewport(0, 0,
                    PsychResolutionController.rawWidth(minecraft.getWindow()),
                    PsychResolutionController.rawHeight(minecraft.getWindow()));
        } finally {
            fnfmod$adjustingViewport = false;
        }
        callback.cancel();
    }
}
