package com.fnfmod.client;

import com.fnfmod.client.gui.GameplayScreen;
import com.fnfmod.client.gui.SongSelectScreen;
import com.fnfmod.client.gui.WaitingScreen;
import com.fnfmod.net.FnfPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Dispatches client-bound payloads. Only ever class-loaded on the client. */
public final class ClientNetHandler {

    private ClientNetHandler() {}

    public static void handle(CustomPacketPayload payload) {
        Minecraft mc = Minecraft.getInstance();

        if (payload instanceof FnfPayloads.OpenMenuS2C p) {
            switch (p.role()) {
                case 0 -> {
                    ClientSession.reset();
                    ClientSession.activePos = p.pos();
                    mc.setScreen(new SongSelectScreen(p.pos(), p.songs()));
                }
                case 2 -> {
                    if (mc.player != null) {
                        mc.player.displayClientMessage(Component.literal(
                                "This Funkin' Machine is in use by " + p.hostName()), true);
                    }
                }
                default -> {}
            }
        } else if (payload instanceof FnfPayloads.SessionStateS2C p) {
            if (p.state() == 0) {
                mc.setScreen(new WaitingScreen(Component.literal(
                        "Waiting for player 2... (they must click the machine)")));
            } else if (p.state() == 1 && mc.screen instanceof WaitingScreen) {
                mc.setScreen(new WaitingScreen(Component.literal(
                        p.partnerName() + " joined! Preparing...")));
            }
        } else if (payload instanceof FnfPayloads.FileManifestS2C p) {
            ClientSession.onManifest(p);
        } else if (payload instanceof FnfPayloads.FileChunkS2C p) {
            ClientSession.onChunk(p);
        } else if (payload instanceof FnfPayloads.StartSongS2C p) {
            ClientSession.onStart(p);
        } else if (payload instanceof FnfPayloads.PartnerNoteS2C p) {
            if (mc.screen instanceof GameplayScreen gameplay) {
                gameplay.onPartnerNote(p.lane(), p.judgement(), p.combo(), p.score());
            }
        } else if (payload instanceof FnfPayloads.PartnerEndS2C p) {
            if (mc.screen instanceof GameplayScreen gameplay) {
                gameplay.onPartnerEnd(p.score(), p.misses(), p.accuracy(), p.failed());
            }
        } else if (payload instanceof FnfPayloads.SessionCancelS2C p) {
            ClientSession.onCancel(p);
        }
    }
}
