package com.fnfmod.client;

import com.fnfmod.client.gui.GameplayScreen;
import com.fnfmod.client.gui.MinecraftPauseOverlayScreen;
import com.fnfmod.client.gui.SongSelectScreen;
import com.fnfmod.client.gui.WaitingScreen;
import com.fnfmod.client.gui.RollbackWaitingScreen;
import com.fnfmod.client.gui.machine.MachineEditorScreen;
import com.fnfmod.client.gui.machine.MachineMenuScreen;
import com.fnfmod.client.gui.machine.HitboxBuilderScreen;
import com.fnfmod.client.gui.machine.FunkinDesignerScreen;
import com.fnfmod.client.gui.machine.ChunkLoaderPointEditorScreen;
import com.fnfmod.net.FnfPayloads;
import com.fnfmod.machine.MachineLibrary;
import com.fnfmod.song.SongLibrary;
import com.fnfmod.world.ModContentScope;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;

/** Dispatches client-bound payloads. Only ever class-loaded on the client. */
public final class ClientNetHandler {

    private ClientNetHandler() {}

    public static void handle(CustomPacketPayload payload) {
        Minecraft mc = Minecraft.getInstance();

        if (payload instanceof FnfPayloads.OpenMenuS2C p) {
            switch (p.role()) {
                case 0 -> {
                    // A custom menu may hand off to the built-in selector while
                    // retaining its chosen post-song destination.
                    byte returnTarget = ClientSession.pendingSongExitTarget;
                    boolean lockedWorldMenu = ClientSession.lockedWorldMenu;
                    ClientSession.reset();
                    ClientSession.pendingSongExitTarget = FnfPayloads.LeaveC2S.normalizeReturnTarget(returnTarget);
                    ClientSession.lockedWorldMenu = lockedWorldMenu;
                    ClientSession.activePos = p.pos();
                    // Song availability is local to the player choosing it. The
                    // server only coordinates the session; it is not the catalog.
                    mc.setScreen(new SongSelectScreen(p.pos(), localSongs()));
                }
                case 2 -> {
                    if (mc.player != null) {
                        mc.player.displayClientMessage(Component.literal(
                                "This Funkin' Machine is in use by " + p.hostName()), true);
                    }
                    // a reopen-menu request that lost the machine to someone else: don't
                    // strand the player on the "Returning to song list..." waiting screen
                    if (mc.screen instanceof WaitingScreen) mc.setScreen(null);
                }
                default -> {}
            }
        } else if (payload instanceof FnfPayloads.SessionStateS2C p) {
            if (p.state() == 0) {
                String message = p.partnerName().isBlank()
                        ? "Waiting for player 2 at the orange stage marker..."
                        : p.partnerName();
                if (!(mc.screen instanceof WaitingScreen waiting)
                        || !waiting.messageText().equals(message.replaceFirst("\\.{1,3}$", ""))) {
                    mc.setScreen(new WaitingScreen(Component.literal(message)));
                }
            } else if (p.state() == 1 && mc.screen instanceof WaitingScreen) {
                String message = p.partnerName() + " joined! Preparing...";
                if (!(mc.screen instanceof WaitingScreen waiting)
                        || !waiting.messageText().equals(message)) {
                    mc.setScreen(new WaitingScreen(Component.literal(message)));
                }
            }
        } else if (payload instanceof FnfPayloads.JoinGuideS2C p) {
            com.fnfmod.client.render.SongJoinGuide.accept(p);
        } else if (payload instanceof FnfPayloads.SongCompatibilityS2C p) {
            ClientSession.onCompatibility(p);
        } else if (payload instanceof FnfPayloads.StartSongS2C p) {
            ClientSession.onStart(p);
        } else if (payload instanceof FnfPayloads.BeginSongS2C p) {
            if (mc.screen instanceof GameplayScreen gameplay) gameplay.onServerBegin(p);
        } else if (payload instanceof FnfPayloads.NearbySongS2C p) {
            com.fnfmod.client.audio.NearbySongAudio.onSnapshot(p);
        } else if (payload instanceof FnfPayloads.NearbyNoteS2C p) {
            com.fnfmod.client.audio.NearbySongAudio.onNote(p);
        } else if (payload instanceof FnfPayloads.RestartSongS2C p) {
            if (mc.screen instanceof GameplayScreen gameplay) {
                gameplay.onServerRestart(p);
            }
        } else if (payload instanceof FnfPayloads.PartnerNoteS2C p) {
            if (mc.screen instanceof GameplayScreen gameplay) {
                gameplay.onPartnerNote(p.lane(), p.judgement(), p.combo(), p.score());
            }
        } else if (payload instanceof FnfPayloads.PartnerEndS2C p) {
            if (mc.screen instanceof GameplayScreen gameplay) {
                gameplay.onPartnerEnd(p.score(), p.misses(), p.accuracy(), p.failed());
            }
        } else if (payload instanceof FnfPayloads.PartnerLeftS2C p) {
            if (mc.screen instanceof GameplayScreen gameplay) {
                gameplay.onPartnerLeft(p);
            } else if (mc.screen instanceof MinecraftPauseOverlayScreen pause) {
                pause.gameplay().onPartnerLeft(p);
            }
        } else if (payload instanceof FnfPayloads.SessionCancelS2C p) {
            ClientSession.onCancel(p);
        } else if (payload instanceof FnfPayloads.RollbackCompleteS2C p) {
            if (mc.screen instanceof RollbackWaitingScreen waiting) waiting.complete(p.pos());
        } else if (payload instanceof FnfPayloads.EditorBotS2C p) {
            if (mc.screen instanceof GameplayScreen gameplay) gameplay.onEditorBotSpawned(p);
        } else if (payload instanceof FnfPayloads.OpenMachineEditorS2C p) {
            mc.setScreen(new MachineEditorScreen(p.pos(), p.profileId()));
        } else if (payload instanceof FnfPayloads.MachineEditorResultS2C p) {
            if (mc.screen instanceof MachineEditorScreen editor) {
                editor.onServerResult(p.success(), p.message(), p.profileId(), p.refresh());
            } else if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal(p.message()), true);
            }
        } else if (payload instanceof FnfPayloads.OpenMachineMenuS2C p) {
            ClientSession.reset();
            ClientSession.lockedWorldMenu = p.lockedWorldMenu();
            ClientSession.activePos = p.pos();
            String compatibilityError = null;
            String activeId = ModContentScope.activeMod().map(ModContentScope.ActiveMod::id).orElse("");
            if (p.modId().isBlank()) {
                // Remote LAN players keep their own global mod/song library; a
                // blank server scope means “do not constrain this client”.
                if (!activeId.isBlank()) {
                    ModContentScope.clear();
                    SongLibrary.rescan();
                    MachineLibrary.rescan();
                    com.fnfmod.client.render.IconLibrary.rescan();
                }
            } else if (!activeId.equals(p.modId())) {
                ModContentScope.clear();
                if (ModContentScope.bindClientMod(p.modId())) {
                    SongLibrary.rescan();
                    MachineLibrary.rescan();
                    com.fnfmod.client.render.IconLibrary.rescan();
                } else {
                    compatibilityError = "Missing required mod: " + p.modId();
                }
            }
            if (compatibilityError == null && !MachineLibrary.packVersion().equals(p.packVersion())) {
                compatibilityError = "Mod version/content mismatch. Server: " + p.packVersion()
                        + ", client: " + MachineLibrary.packVersion();
            }
            mc.setScreen(new MachineMenuScreen(p.pos(), p.profileId(), p.machineTag(), p.machineData(),
                    compatibilityError, localSongs(), p.lockedWorldMenu()));
        } else if (payload instanceof FnfPayloads.MachineTaggedPlayResultS2C p) {
            ClientSession.onTaggedPlayResult(p);
        } else if (payload instanceof FnfPayloads.ModScopeS2C p) {
            ClientSession.serverAllowsGameplayLua = p.gameplayLuaAllowed();
            com.fnfmod.client.AutoWorldMenuClient.configure(p.automaticMenu());
            if (p.modId().isBlank()) {
                ModContentScope.clear();
            } else {
                String activeId = ModContentScope.activeMod().map(ModContentScope.ActiveMod::id).orElse("");
                if (!activeId.equals(p.modId())) {
                    ModContentScope.clear();
                    if (!ModContentScope.bindClientMod(p.modId()) && mc.player != null) {
                        mc.player.displayClientMessage(Component.literal(
                                "Missing bundled-world mod assets: " + p.modId()), false);
                    }
                }
            }
            SongLibrary.rescan();
            MachineLibrary.rescan();
            com.fnfmod.client.render.IconLibrary.rescan();
            if (!p.modId().isBlank() && !MachineLibrary.packVersion().equals(p.packVersion())
                    && mc.player != null) {
                mc.player.displayClientMessage(Component.literal(
                        "Machine mod mismatch. Custom menus use fallback until versions match."), false);
            }
        } else if (payload instanceof FnfPayloads.OpenHitboxBuilderS2C p) {
            mc.setScreen(new FunkinDesignerScreen(p.profileId(), p.machineToolsEnabled()));
        } else if (payload instanceof FnfPayloads.HitboxSelectionStateS2C p) {
            com.fnfmod.client.render.MachineHitboxPreview.apply(p);
        } else if (payload instanceof FnfPayloads.ConfirmHitboxRemovalS2C p) {
            mc.setScreen(new ConfirmScreen(confirmed -> {
                if (confirmed) {
                    PacketDistributor.sendToServer(new FnfPayloads.ConfirmHitboxRemovalC2S(
                            p.anchorPos(), p.groupId()));
                }
                mc.setScreen(null);
            }, Component.literal("Remove virtual Funkin' Machine?"),
                    Component.literal("This removes its hitbox and anchor. Any active song session will stop.")));
        } else if (payload instanceof FnfPayloads.OpenChunkLoaderEditorS2C p) {
            mc.setScreen(new ChunkLoaderPointEditorScreen(p.pos(), p.tag(), p.radius(), p.enabled()));
        } else if (payload instanceof FnfPayloads.ChunkLoaderEditorResultS2C p) {
            if (mc.screen instanceof ChunkLoaderPointEditorScreen editor) {
                editor.onServerResult(p.success(), p.message(), p.tag(), p.radius(), p.enabled());
            } else if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal(p.message()), true);
            }
        } else if (payload instanceof FnfPayloads.DesignerPlacementStateS2C p) {
            com.fnfmod.client.render.MachineHitboxPreview.setDesignerPlacement(p.mode());
        }
    }

    private static java.util.List<FnfPayloads.SongInfo> localSongs() {
        return SongLibrary.getSongs().values().stream().map(entry -> new FnfPayloads.SongInfo(
                entry.id, entry.displayName, java.util.List.copyOf(entry.difficulties), entry.opponentIcon,
                entry.opponentIconFile == null ? "" : entry.opponentIconFile.toAbsolutePath().toString()))
                .toList();
    }
}
