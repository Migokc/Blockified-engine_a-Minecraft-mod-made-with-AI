package com.fnfmod.mixin;

import com.fnfmod.world.ModWorldOptions;
import net.minecraft.world.level.storage.PrimaryLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Makes allowCheats a runtime world rule without rewriting the user's config. */
@Mixin(PrimaryLevelData.class)
public abstract class ModWorldPrimaryLevelDataMixin {

    @Inject(method = "isAllowCommands", at = @At("HEAD"), cancellable = true)
    private void fnfmod$modWorldCheats(CallbackInfoReturnable<Boolean> result) {
        if (ModWorldOptions.hasCheatOverride()) result.setReturnValue(ModWorldOptions.allowCheats());
    }
}
