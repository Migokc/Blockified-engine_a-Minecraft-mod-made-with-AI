package com.fnfmod.mixin;

import com.fnfmod.world.ModWorldOptions;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** PersistentEntitySectionManager.close normally forces all entities to disk. */
@Mixin(PersistentEntitySectionManager.class)
public abstract class ModWorldEntityStorageMixin {

    @Redirect(method = "close", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/entity/PersistentEntitySectionManager;saveAll()V"))
    private void fnfmod$skipCloseSave(PersistentEntitySectionManager<?> storage) {
        if (!ModWorldOptions.preventSaving()) storage.saveAll();
    }
}
