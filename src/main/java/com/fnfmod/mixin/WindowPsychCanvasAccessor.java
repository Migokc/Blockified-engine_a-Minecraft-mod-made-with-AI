package com.fnfmod.mixin;

import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Physical framebuffer dimensions, bypassing the temporary render-time virtual size. */
@Mixin(Window.class)
public interface WindowPsychCanvasAccessor {
    @Accessor("width") int fnfmod$rawWidth();
    @Accessor("height") int fnfmod$rawHeight();
}
