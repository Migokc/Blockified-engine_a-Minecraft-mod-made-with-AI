package com.fnfmod.mixin;

import com.fnfmod.session.SessionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Copy-on-write journal for block mutations made by song commands. */
@Mixin(Level.class)
public abstract class LevelMutationMixin {
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"))
    private void fnfmod$captureSongBlock(BlockPos pos, BlockState state, int flags,
                                         int recursionLeft,
                                         CallbackInfoReturnable<Boolean> cir) {
        SessionManager.captureBlockBeforeMutation((Level) (Object) this, pos);
    }
}
