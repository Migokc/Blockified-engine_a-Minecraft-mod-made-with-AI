package com.fnfmod.client.gui;

import com.fnfmod.FnfMod;
import com.fnfmod.client.ClientSession;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Simple "please wait" screen with a cancel button. */
public class WaitingScreen extends Screen {

    private final String message;
    private final boolean cancellable;
    private final long openedAtNanos = System.nanoTime();
    private boolean slowReturnLogged;

    public WaitingScreen(Component message) {
        this(message, true);
    }

    /** Non-cancellable waits are used while the server rolls back and routes a finished song. */
    public WaitingScreen(Component message, boolean cancellable) {
        super(Component.literal("Funkin' Machine"));
        String text = message == null ? "Loading" : message.getString();
        // The screen owns the animated ellipsis. Callers may keep using natural
        // labels such as "Loading..." without producing two sets of dots.
        this.message = text.replaceFirst("\\.{1,3}$", "");
        this.cancellable = cancellable;
    }

    public String messageText() {
        return message;
    }

    @Override
    protected void init() {
        if (!cancellable) return;
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> {
            ClientSession.leave();
            onClose();
        }).bounds(width / 2 - 50, height / 2 + 30, 100, 20).build());
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        super.render(gui, mouseX, mouseY, partialTick);
        int dots = (int) ((System.currentTimeMillis() / 400) % 4);
        // Reserve the completed ellipsis width so the sentence stays still as
        // its suffix animates instead of shifting horizontally every frame.
        int x = (width - font.width(message + "...")) / 2;
        int y = height / 2 - 20;
        gui.drawString(font, message, x, y, 0xFFFFFF, false);
        gui.drawString(font, ".".repeat(dots), x + font.width(message), y, 0xFFFFFF, false);
        if (!cancellable && System.nanoTime() - openedAtNanos > 5_000_000_000L) {
            gui.drawCenteredString(font, "Still waiting for the server to restore the song",
                    width / 2, height / 2 + 2, 0xFFAAAAAA);
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void tick() {
        if (cancellable) ClientSession.checkReadyTimeout();
        else if (!slowReturnLogged && System.nanoTime() - openedAtNanos > 10_000_000_000L) {
            slowReturnLogged = true;
            FnfMod.LOGGER.warn("Song return has waited over 10 seconds for the server response");
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
