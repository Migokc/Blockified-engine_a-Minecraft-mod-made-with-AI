package com.fnfmod.mixin;

import com.fnfmod.world.ModWorldOptions;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Drain pending POI/section work without writing it to a no-save mod world. */
@Mixin(SectionStorage.class)
public abstract class ModWorldSectionStorageMixin {
    @Shadow @Final private LongLinkedOpenHashSet dirty;
    @Shadow @Final protected LevelHeightAccessor levelHeightAccessor;

    @Inject(method = "writeColumn(Lnet/minecraft/world/level/ChunkPos;)V",
            at = @At("HEAD"), cancellable = true)
    private void fnfmod$skipSectionWrite(ChunkPos pos, CallbackInfo info) {
        if (!ModWorldOptions.preventSaving()) return;
        for (int sectionY = levelHeightAccessor.getMinSection();
             sectionY < levelHeightAccessor.getMaxSection(); sectionY++) {
            dirty.remove(SectionPos.asLong(pos.x, sectionY, pos.z));
        }
        info.cancel();
    }
}
