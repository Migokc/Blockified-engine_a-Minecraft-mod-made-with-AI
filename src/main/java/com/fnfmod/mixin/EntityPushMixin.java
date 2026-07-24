package com.fnfmod.mixin;

import com.fnfmod.gameplay.PerformerCollisions;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses entity pushing between song performers whose collisions a chart or
 * Lua script turned off. Only the push is cancelled, so block collision, gravity,
 * and every other physics behaviour stay exactly as they were.
 */
@Mixin(Entity.class)
public abstract class EntityPushMixin {

    @Inject(method = "push(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
    private void fnfmod$skipPerformerPush(Entity other, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (other != null && PerformerCollisions.blocked(self.getId(), other.getId())) {
            ci.cancel();
        }
    }
}
