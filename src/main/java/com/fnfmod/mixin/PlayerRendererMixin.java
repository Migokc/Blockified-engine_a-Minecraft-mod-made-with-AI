package com.fnfmod.mixin;

import com.fnfmod.client.render.PerformerRotation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds Lua/free-camera X/Z rotation after Minecraft has applied normal player yaw. */
@Mixin(PlayerRenderer.class)
public abstract class PlayerRendererMixin {

    @Inject(method = "setupRotations", at = @At("TAIL"))
    private void fnfmod$applyBbsTilt(AbstractClientPlayer player, PoseStack poseStack,
                                     float ageInTicks, float bodyYaw, float partialTick,
                                     float scale, CallbackInfo info) {
        PerformerRotation.apply(player, poseStack);
    }
}
