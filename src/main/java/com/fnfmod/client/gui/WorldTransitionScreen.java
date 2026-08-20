package com.fnfmod.client.gui;

import com.fnfmod.client.world.WorldImportCutscene;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Opaque black screen shown during a world import/export. Minecraft's own loading screens are
 * redirected to this one (see MinecraftSetScreenMixin), so it is the single visible surface
 * throughout the move and reload; it paints the black background plus the transition text and
 * progress bar, and vanilla load text never renders behind it.
 */
public final class WorldTransitionScreen extends Screen {

    public WorldTransitionScreen() {
        super(Component.literal("Loading..."));
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        gui.fill(0, 0, width, height, 0xFF000000);
        WorldImportCutscene.renderOverlay(gui);
    }

    @Override
    public void renderBackground(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        // Fully custom opaque background; skip the dirt/blur so nothing shows behind it.
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
