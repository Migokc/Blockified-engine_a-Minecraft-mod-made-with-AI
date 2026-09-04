package com.fnfmod.mixin;

import com.fnfmod.session.SessionManager;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures weather lazily: natural weather remains untouched unless a song changes it. */
@Mixin(ServerLevel.class)
public abstract class WeatherMutationMixin {
    @Inject(method = "setWeatherParameters", at = @At("HEAD"))
    private void fnfmod$captureSongWeather(int clearTime, int rainTime,
                                           boolean raining, boolean thundering,
                                           CallbackInfo ci) {
        SessionManager.captureWeatherBeforeMutation((ServerLevel) (Object) this);
    }
}
