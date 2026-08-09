package com.fnfmod.mixin;

import com.fnfmod.world.ModWorldOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Prevents autosave, level.dat writes, and shutdown saves for non-persistent mod worlds. */
@Mixin(MinecraftServer.class)
public abstract class ModWorldMinecraftServerMixin {

    @Inject(method = "saveAllChunks", at = @At("HEAD"), cancellable = true)
    private void fnfmod$skipModWorldSave(boolean suppressLogs, boolean flush, boolean forced,
                                         CallbackInfoReturnable<Boolean> result) {
        if (ModWorldOptions.preventSaving()) result.setReturnValue(false);
    }

    @Redirect(method = "stopServer", at = @At(value = "FIELD",
            target = "Lnet/minecraft/server/level/ServerLevel;noSave:Z", opcode = Opcodes.PUTFIELD))
    private void fnfmod$keepNoSaveDuringShutdown(ServerLevel level, boolean value) {
        level.noSave = ModWorldOptions.preventSaving() || value;
    }
}
