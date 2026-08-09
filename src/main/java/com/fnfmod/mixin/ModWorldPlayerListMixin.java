package com.fnfmod.mixin;

import com.fnfmod.world.ModWorldOptions;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Applies cheat denial and suppresses player/stat/advancement persistence. */
@Mixin(PlayerList.class)
public abstract class ModWorldPlayerListMixin {

    @Inject(method = "save(Lnet/minecraft/server/level/ServerPlayer;)V",
            at = @At("HEAD"), cancellable = true)
    private void fnfmod$skipPlayerSave(ServerPlayer player, CallbackInfo info) {
        if (ModWorldOptions.preventSaving()) info.cancel();
    }

    @Inject(method = "isOp", at = @At("HEAD"), cancellable = true)
    private void fnfmod$enforceDisabledCheats(GameProfile profile,
                                               CallbackInfoReturnable<Boolean> result) {
        if (ModWorldOptions.hasCheatOverride() && !ModWorldOptions.allowCheats()) {
            result.setReturnValue(false);
        }
    }
}
