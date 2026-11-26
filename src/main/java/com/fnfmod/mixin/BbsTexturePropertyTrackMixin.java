package com.fnfmod.mixin;

import com.fnfmod.client.anim.BbsTextureKeyframeCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps the last texture keyframe on Blockified-controlled BBS FS 2.6+ forms. */
@Pseudo
@Mixin(targets = "mchorse.bbs_mod.film.replays.tracks.behaviours.PropertyTrack", remap = false)
public abstract class BbsTexturePropertyTrackMixin {
    @Inject(method = "reset", at = @At("HEAD"), cancellable = true, remap = false)
    private void fnfmod$keepBlockifiedTexture(@Coerce Object rootForm, @Coerce Object track,
                                               CallbackInfo callback) {
        if (BbsTextureKeyframeCompat.keepRuntimeTexture(rootForm, track)) {
            callback.cancel();
        }
    }
}
