package com.fnfmod.mixin;

import com.fnfmod.client.world.WorldImportCutscene;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Locks the camera (mouse look) while the world-import cutscene is running. */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerCutsceneMixin {

    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void fnfmod$freezeCameraDuringImport(CallbackInfo ci) {
        if (WorldImportCutscene.active()) {
            ci.cancel();
        }
    }
}
