package com.fnfmod.mixin;

import com.fnfmod.world.ModWorldOptions;
import net.minecraft.server.level.ServerChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** ServerChunkCache.close normally forces one final chunk save. */
@Mixin(ServerChunkCache.class)
public abstract class ModWorldServerChunkCacheMixin {

    @Redirect(method = "close", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerChunkCache;save(Z)V"))
    private void fnfmod$skipCloseSave(ServerChunkCache cache, boolean flush) {
        if (!ModWorldOptions.preventSaving()) cache.save(flush);
    }
}
