package com.fnfmod.mixin;

import com.fnfmod.client.render.ObjectBorderRegistry;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.OutlineBufferSource;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Encodes the selected entity's individual Psych-space border radius in alpha. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererObjectBorderMixin {
    @Shadow @Final private Minecraft minecraft;
    @Shadow private RenderTarget entityTarget;

    @ModifyArg(
            method = "renderLevel",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/OutlineBufferSource;setColor(IIII)V"),
            index = 3)
    private int fnfmod$objectBorderWidth(int vanillaAlpha) {
        return ObjectBorderRegistry.consumeAlpha(vanillaAlpha);
    }

    /**
     * Hides only Minecraft's targeted-block wireframe while Blockified owns the view.
     * Blockified selection boxes use separate render types and remain visible.
     */
    @ModifyVariable(method = "renderLevel", at = @At("HEAD"), argsOnly = true, index = 2)
    private boolean fnfmod$hideVanillaBlockOutline(boolean renderBlockOutline) {
        if (!renderBlockOutline) return false;
        net.minecraft.client.gui.screens.Screen screen = minecraft.screen;
        if (com.fnfmod.client.ClientSession.activePos != null
                || screen instanceof com.fnfmod.client.gui.machine.MachineMenuScreen
                || screen instanceof com.fnfmod.client.gui.machine.MachineMenuEditorScreen) {
            return false;
        }
        return true;
    }

    /** Gives the custom outline target the completed world/entity depth before it flushes. */
    @org.spongepowered.asm.mixin.injection.Inject(
            method = "renderLevel",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/OutlineBufferSource;endOutlineBatch()V",
                    shift = At.Shift.BEFORE))
    private void fnfmod$copyWorldDepth(
            net.minecraft.client.DeltaTracker deltaTracker,
            boolean renderBlockOutline,
            net.minecraft.client.Camera camera,
            net.minecraft.client.renderer.GameRenderer gameRenderer,
            net.minecraft.client.renderer.LightTexture lightTexture,
            org.joml.Matrix4f projection,
            org.joml.Matrix4f frustum,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfo callback) {
        if (!ObjectBorderRegistry.hasBorders() || entityTarget == null) return;
        entityTarget.copyDepthFrom(minecraft.getMainRenderTarget());
        minecraft.getMainRenderTarget().bindWrite(false);
    }
}
