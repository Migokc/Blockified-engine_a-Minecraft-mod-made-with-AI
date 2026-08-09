package com.fnfmod.client.gui;

import com.fnfmod.client.gui.editor.ChartEditorScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/** Keeps the integrated server ticking until song rollback is acknowledged. */
public final class RollbackWaitingScreen extends Screen {

    private final BlockPos machinePos;
    private final ChartEditorScreen editor;

    public RollbackWaitingScreen(BlockPos machinePos, ChartEditorScreen editor) {
        super(Component.literal("Restoring song changes"));
        this.machinePos = machinePos.immutable();
        this.editor = editor;
    }

    public void complete(BlockPos acknowledgedPos) {
        if (minecraft != null && machinePos.equals(acknowledgedPos)) minecraft.setScreen(editor);
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
