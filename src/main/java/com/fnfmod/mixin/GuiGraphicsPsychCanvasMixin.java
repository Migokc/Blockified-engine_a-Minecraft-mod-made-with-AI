package com.fnfmod.mixin;

import com.fnfmod.client.render.PsychResolutionController;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Maps GUI clip rectangles from virtual 16:9 pixels into the full framebuffer. */
@Mixin(GuiGraphics.class)
public abstract class GuiGraphicsPsychCanvasMixin {
    @Redirect(method = "applyScissor", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;enableScissor(IIII)V"))
    private void fnfmod$scaleCanvasScissor(int x, int y, int width, int height) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!PsychResolutionController.enabled() || !PsychResolutionController.rendering()
                || minecraft == null || minecraft.getWindow() == null) {
            RenderSystem.enableScissor(x, y, width, height);
            return;
        }

        int canvasWidth = PsychResolutionController.pixelWidth(minecraft.getWindow());
        int canvasHeight = PsychResolutionController.pixelHeight(minecraft.getWindow());
        int rawWidth = PsychResolutionController.rawWidth(minecraft.getWindow());
        int rawHeight = PsychResolutionController.rawHeight(minecraft.getWindow());
        double scaleX = rawWidth / (double) Math.max(1, canvasWidth);
        double scaleY = rawHeight / (double) Math.max(1, canvasHeight);
        int left = (int) Math.floor(x * scaleX);
        int bottom = (int) Math.floor(y * scaleY);
        int right = (int) Math.ceil((x + width) * scaleX);
        int top = (int) Math.ceil((y + height) * scaleY);
        RenderSystem.enableScissor(left, bottom,
                Math.max(0, right - left), Math.max(0, top - bottom));
    }
}
