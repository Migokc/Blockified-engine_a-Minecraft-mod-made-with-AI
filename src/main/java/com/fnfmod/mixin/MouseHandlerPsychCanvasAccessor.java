package com.fnfmod.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Raw values used by the canvas input redirects without recursively calling mapped getters. */
@Mixin(MouseHandler.class)
public interface MouseHandlerPsychCanvasAccessor {
    @Accessor("xpos") double fnfmod$rawX();
    @Accessor("ypos") double fnfmod$rawY();
    @Accessor("accumulatedDX") double fnfmod$rawDeltaX();
    @Accessor("accumulatedDY") double fnfmod$rawDeltaY();
}
