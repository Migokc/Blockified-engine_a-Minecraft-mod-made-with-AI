package com.fnfmod.mixin;

import com.fnfmod.client.render.ObjectBorderRegistry;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Supplies each selected entity's Lua border colour to the outline framebuffer. */
@Mixin(Entity.class)
public abstract class EntityObjectBorderMixin {
    @Inject(method = "getTeamColor", at = @At("HEAD"), cancellable = true)
    private void fnfmod$objectBorderColor(CallbackInfoReturnable<Integer> callback) {
        Integer color = ObjectBorderRegistry.color((Entity) (Object) this);
        if (color != null) callback.setReturnValue(color);
    }
}
