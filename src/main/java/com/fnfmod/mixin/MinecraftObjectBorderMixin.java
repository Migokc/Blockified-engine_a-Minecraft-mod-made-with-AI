package com.fnfmod.mixin;

import com.fnfmod.client.render.ObjectBorderRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Includes Blockified-selected entities in Minecraft's silhouette-outline pass. */
@Mixin(Minecraft.class)
public abstract class MinecraftObjectBorderMixin {
    @Inject(method = "shouldEntityAppearGlowing", at = @At("HEAD"), cancellable = true)
    private void fnfmod$objectBorderGlow(Entity entity, CallbackInfoReturnable<Boolean> callback) {
        if (ObjectBorderRegistry.prepare(entity)) callback.setReturnValue(true);
    }
}
