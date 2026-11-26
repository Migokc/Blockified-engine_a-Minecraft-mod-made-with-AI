package com.fnfmod.mixin;

import com.fnfmod.world.ModWorldOptions;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Allow shutdown chunk unloads to drain without persisting a no-save mod world. */
@Mixin(ChunkMap.class)
public abstract class ModWorldChunkMapMixin {

    @Inject(method = "save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z",
            at = @At("HEAD"), cancellable = true)
    private void fnfmod$skipChunkWrite(ChunkAccess chunk, CallbackInfoReturnable<Boolean> result) {
        if (ModWorldOptions.preventSaving()) result.setReturnValue(false);
    }
}
