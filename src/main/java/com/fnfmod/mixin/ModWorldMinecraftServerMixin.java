package com.fnfmod.mixin;

import com.fnfmod.world.ModWorldOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevents autosave and level.dat writes for non-persistent mod worlds. */
@Mixin(MinecraftServer.class)
public abstract class ModWorldMinecraftServerMixin {

    @Inject(method = "stopServer", at = @At("HEAD"))
    private void fnfmod$releaseChunkLoaderTickets(CallbackInfo info) {
        if (ModWorldOptions.preventSaving()) {
            com.fnfmod.world.ChunkLoaderPointService.onServerStopping(
                    ((MinecraftServer) (Object) this).getAllLevels());
        }
    }

    @Inject(method = "saveAllChunks", at = @At("HEAD"), cancellable = true)
    private void fnfmod$skipModWorldSave(boolean suppressLogs, boolean flush, boolean forced,
                                         CallbackInfoReturnable<Boolean> result) {
        if (ModWorldOptions.preventSaving()) result.setReturnValue(false);
    }

}
