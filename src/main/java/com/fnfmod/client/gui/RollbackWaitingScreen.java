package com.fnfmod.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/** Keeps the integrated server ticking until song rollback is acknowledged. */
public final class RollbackWaitingScreen extends Screen {

    private final BlockPos machinePos;
    private final Screen destination;

    public RollbackWaitingScreen(BlockPos machinePos, Screen destination) {
        super(Component.literal("Restoring song changes"));
        this.machinePos = machinePos.immutable();
        this.destination = destination;
    }

    public void complete(BlockPos acknowledgedPos) {
        if (minecraft != null && machinePos.equals(acknowledgedPos)) minecraft.setScreen(destination);
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        super.render(gui, mouseX, mouseY, partialTick);
        gui.drawCenteredString(font, "Restoring song changes...", width / 2, height / 2, 0xFFFFFF);
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
