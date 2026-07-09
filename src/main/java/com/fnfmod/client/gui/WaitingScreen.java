package com.fnfmod.client.gui;

import com.fnfmod.client.ClientSession;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Simple "please wait" screen with a cancel button. */
public class WaitingScreen extends Screen {

    private final Component message;

    public WaitingScreen(Component message) {
        super(Component.literal("Funkin' Machine"));
        this.message = message;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> {
            ClientSession.leave();
            onClose();
        }).bounds(width / 2 - 50, height / 2 + 30, 100, 20).build());
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        super.render(gui, mouseX, mouseY, partialTick);
        gui.drawCenteredString(font, message, width / 2, height / 2 - 20, 0xFFFFFF);
        int dots = (int) ((System.currentTimeMillis() / 400) % 4);
        gui.drawCenteredString(font, ".".repeat(dots), width / 2, height / 2 - 8, 0xAAAAAA);
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
