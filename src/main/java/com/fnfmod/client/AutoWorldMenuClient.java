package com.fnfmod.client;

import com.fnfmod.net.FnfPayloads;
import com.mojang.realmsclient.RealmsMainScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** Ensures a locked automatic world menu survives the client world-loading screen transition. */
public final class AutoWorldMenuClient {
    private static boolean enabled;
    private static int cooldown;

    private AutoWorldMenuClient() {}

    public static void configure(boolean value) {
        enabled = value;
        cooldown = value ? 10 : 0;
    }

    public static void clear() { enabled = false; cooldown = 0; }

    /** Vanilla pause screen, retaining the current Lua page when the player resumes. */
    public static void openPauseMenu(Screen returnTo, Runnable beforeLeave) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        cooldown = 40;
        minecraft.setScreen(new MenuPauseScreen(returnTo, beforeLeave));
    }

    /** Explicit Lua action: disconnect this client without displaying a confirmation. */
    public static void exitWorld(Runnable beforeLeave) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        if (beforeLeave != null) beforeLeave.run();
        clear();
        ClientSession.leave();
        leaveWorld(minecraft);
    }

    /** Mirrors vanilla's Return to Menu / Disconnect destination handling. */
    private static void leaveWorld(Minecraft minecraft) {
        boolean local = minecraft.isLocalServer();
        ServerData server = minecraft.getCurrentServer();
        if (minecraft.level != null) minecraft.level.disconnect();
        if (local) {
            minecraft.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")));
        } else {
            minecraft.disconnect();
        }

        TitleScreen title = new TitleScreen();
        if (local) minecraft.setScreen(title);
        else if (server != null && server.isRealm()) minecraft.setScreen(new RealmsMainScreen(title));
        else minecraft.setScreen(new JoinMultiplayerScreen(title));
    }

    /** Vanilla pause screen whose Return/Escape route restores the same Lua menu. */
    private static final class MenuPauseScreen extends PauseScreen {
        private final Screen returnTo;
        private final Runnable beforeLeave;
        private boolean disconnectCleaned;

        private MenuPauseScreen(Screen returnTo, Runnable beforeLeave) {
            super(true);
            this.returnTo = returnTo;
            this.beforeLeave = beforeLeave;
        }

        @Override public void onClose() {
            cooldown = 40;
            minecraft.setScreen(returnTo);
        }

        @Override public void removed() {
            super.removed();
            Minecraft client = Minecraft.getInstance();
            client.tell(() -> {
                if (client.level != null && client.screen == null) {
                    cooldown = 40;
                    client.setScreen(returnTo);
                } else if (client.level == null && !disconnectCleaned) {
                    disconnectCleaned = true;
                    if (beforeLeave != null) beforeLeave.run();
                    clear();
                    ClientSession.reset();
                }
            });
        }
    }

    public static void tick() {
        if (!enabled) return;
        if (cooldown > 0) { cooldown--; return; }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || minecraft.screen != null
                || ClientSession.activePos != null) return;
        PacketDistributor.sendToServer(new FnfPayloads.AutoWorldMenuC2S());
        cooldown = 40;
    }
}
