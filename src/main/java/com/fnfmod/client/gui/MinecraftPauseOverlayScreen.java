package com.fnfmod.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;

/** Vanilla pause menu that safely returns to Blockified Engine's paused song. */
final class MinecraftPauseOverlayScreen extends PauseScreen {
    private final GameplayScreen gameplay;

    MinecraftPauseOverlayScreen(GameplayScreen gameplay) {
        super(true);
        this.gameplay = gameplay;
    }

    @Override
    public void onClose() {
        if (minecraft != null && minecraft.level != null) minecraft.setScreen(gameplay);
        else super.onClose();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (GameplayScreen.isForceExitChord(keyCode, modifiers)) {
            gameplay.forceExit();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void removed() {
        super.removed();
        // Disconnecting through vanilla menu never restores parent screen.
        // Check next client task, after disconnect has cleared current level.
        Minecraft client = Minecraft.getInstance();
        // tell() always queues. execute() may run immediately on render thread,
        // before setScreen(null) finishes, losing return-to-parent transition.
        client.tell(() -> {
            // Vanilla Return to Game sets screen to null directly instead of
            // invoking onClose. Convert that transition back to gameplay's
            // still-paused screen.
            if (client.level != null && client.screen == null) {
                client.setScreen(gameplay);
            } else if (client.level == null && client.screen != gameplay) {
                gameplay.disposeAfterMinecraftPauseDisconnect();
            }
        });
    }
}
