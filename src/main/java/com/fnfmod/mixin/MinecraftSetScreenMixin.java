package com.fnfmod.mixin;

import com.fnfmod.client.gui.WorldTransitionScreen;
import com.fnfmod.client.world.WorldImportCutscene;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * While a world-import transition is loading, replaces any Minecraft loading/menu screen with
 * Blockified's plain black transition screen. This keeps our screen the active one throughout
 * the move and reload, so vanilla load text ("Loading terrain", "Downloading terrain", the
 * world list, etc.) never renders through the black loading screen. A {@code null} screen —
 * i.e. going in-world once the player spawns — is passed through unchanged.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftSetScreenMixin {

    @ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
    private Screen fnfmod$replaceLoadingScreen(Screen screen) {
        if (WorldImportCutscene.showLoadingScreen() && screen != null
                && !(screen instanceof WorldTransitionScreen)) {
            WorldImportCutscene.captureLoadScreen(screen);
            return new WorldTransitionScreen();
        }
        return screen;
    }
}
