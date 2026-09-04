package com.fnfmod.net;

import com.fnfmod.FnfMod;
import com.fnfmod.session.SessionManager;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@EventBusSubscriber(modid = FnfMod.MODID)
public final class FnfNetworking {

    private FnfNetworking() {}

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("4");

        // client-bound
        registrar.playToClient(FnfPayloads.OpenMenuS2C.TYPE, FnfPayloads.OpenMenuS2C.CODEC, FnfNetworking::client);
        registrar.playToClient(FnfPayloads.SessionStateS2C.TYPE, FnfPayloads.SessionStateS2C.CODEC, FnfNetworking::client);
        registrar.playToClient(FnfPayloads.FileManifestS2C.TYPE, FnfPayloads.FileManifestS2C.CODEC, FnfNetworking::client);
        registrar.playToClient(FnfPayloads.FileChunkS2C.TYPE, FnfPayloads.FileChunkS2C.CODEC, FnfNetworking::client);
        registrar.playToClient(FnfPayloads.StartSongS2C.TYPE, FnfPayloads.StartSongS2C.CODEC, FnfNetworking::client);
        registrar.playToClient(FnfPayloads.RestartSongS2C.TYPE, FnfPayloads.RestartSongS2C.CODEC,
                FnfNetworking::client);
        registrar.playToClient(FnfPayloads.PartnerNoteS2C.TYPE, FnfPayloads.PartnerNoteS2C.CODEC, FnfNetworking::client);
        registrar.playToClient(FnfPayloads.PartnerEndS2C.TYPE, FnfPayloads.PartnerEndS2C.CODEC, FnfNetworking::client);
        registrar.playToClient(FnfPayloads.SessionCancelS2C.TYPE, FnfPayloads.SessionCancelS2C.CODEC, FnfNetworking::client);
        registrar.playToClient(FnfPayloads.RollbackCompleteS2C.TYPE, FnfPayloads.RollbackCompleteS2C.CODEC,
                FnfNetworking::client);
        registrar.playToClient(FnfPayloads.EditorBotS2C.TYPE, FnfPayloads.EditorBotS2C.CODEC,
                FnfNetworking::client);
        registrar.playToClient(FnfPayloads.OpenMachineEditorS2C.TYPE, FnfPayloads.OpenMachineEditorS2C.CODEC, FnfNetworking::client);
        registrar.playToClient(FnfPayloads.MachineEditorResultS2C.TYPE, FnfPayloads.MachineEditorResultS2C.CODEC, FnfNetworking::client);
        registrar.playToClient(FnfPayloads.OpenMachineMenuS2C.TYPE, FnfPayloads.OpenMachineMenuS2C.CODEC, FnfNetworking::client);
        registrar.playToClient(FnfPayloads.MachineTaggedPlayResultS2C.TYPE,
                FnfPayloads.MachineTaggedPlayResultS2C.CODEC, FnfNetworking::client);
        registrar.playToClient(FnfPayloads.ModScopeS2C.TYPE, FnfPayloads.ModScopeS2C.CODEC, FnfNetworking::client);
        registrar.playToClient(FnfPayloads.OpenHitboxBuilderS2C.TYPE, FnfPayloads.OpenHitboxBuilderS2C.CODEC, FnfNetworking::client);
        registrar.playToClient(FnfPayloads.HitboxSelectionStateS2C.TYPE, FnfPayloads.HitboxSelectionStateS2C.CODEC, FnfNetworking::client);
        registrar.playToClient(FnfPayloads.ConfirmHitboxRemovalS2C.TYPE,
                FnfPayloads.ConfirmHitboxRemovalS2C.CODEC, FnfNetworking::client);
        registrar.playToClient(FnfPayloads.OpenChunkLoaderEditorS2C.TYPE,
                FnfPayloads.OpenChunkLoaderEditorS2C.CODEC, FnfNetworking::client);
        registrar.playToClient(FnfPayloads.ChunkLoaderEditorResultS2C.TYPE,
                FnfPayloads.ChunkLoaderEditorResultS2C.CODEC, FnfNetworking::client);
        registrar.playToClient(FnfPayloads.DesignerPlacementStateS2C.TYPE,
                FnfPayloads.DesignerPlacementStateS2C.CODEC, FnfNetworking::client);

        // server-bound
        registrar.playToServer(FnfPayloads.SelectSongC2S.TYPE, FnfPayloads.SelectSongC2S.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sp) SessionManager.onSelectSong(sp, payload);
                }));
        registrar.playToServer(FnfPayloads.RequestFilesC2S.TYPE, FnfPayloads.RequestFilesC2S.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sp) SessionManager.onRequestFiles(sp, payload);
                }));
        registrar.playToServer(FnfPayloads.ReadyC2S.TYPE, FnfPayloads.ReadyC2S.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sp) SessionManager.onReady(sp, payload);
                }));
        registrar.playToServer(FnfPayloads.RestartSongC2S.TYPE, FnfPayloads.RestartSongC2S.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sp) SessionManager.onRestartSong(sp, payload);
                }));
        registrar.playToServer(FnfPayloads.EditorPlaytestC2S.TYPE, FnfPayloads.EditorPlaytestC2S.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sp) SessionManager.onEditorPlaytest(sp, payload);
                }));
        registrar.playToServer(FnfPayloads.NoteEventC2S.TYPE, FnfPayloads.NoteEventC2S.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sp) SessionManager.onNoteEvent(sp, payload);
                }));
        registrar.playToServer(FnfPayloads.SongEndC2S.TYPE, FnfPayloads.SongEndC2S.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sp) SessionManager.onSongEnd(sp, payload);
                }));
        registrar.playToServer(FnfPayloads.CommandEventC2S.TYPE, FnfPayloads.CommandEventC2S.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sp) SessionManager.onCommandEvent(sp, payload);
                }));
        registrar.playToServer(FnfPayloads.LuaCommandC2S.TYPE, FnfPayloads.LuaCommandC2S.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sp) SessionManager.onLuaCommand(sp, payload);
                }));
        registrar.playToServer(FnfPayloads.LeaveC2S.TYPE, FnfPayloads.LeaveC2S.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sp) SessionManager.onLeave(sp, payload.pos(), payload.finishedOnly(), payload.returnTarget());
                }));
        registrar.playToServer(FnfPayloads.ReloadC2S.TYPE, FnfPayloads.ReloadC2S.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sp) SessionManager.onReloadRequest(sp, payload);
                }));
        registrar.playToServer(FnfPayloads.SyncVanillaHudC2S.TYPE, FnfPayloads.SyncVanillaHudC2S.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sp) {
                        SessionManager.onSyncVanillaHud(sp, payload.health(), payload.foodLevel());
                    }
                }));
        registrar.playToServer(FnfPayloads.MachineEditC2S.TYPE, FnfPayloads.MachineEditC2S.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sp) {
                        com.fnfmod.machine.MachineEditorService.handleEdit(sp, payload);
                    }
                }));
        registrar.playToServer(FnfPayloads.MachineMenuActionC2S.TYPE, FnfPayloads.MachineMenuActionC2S.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sp) {
                        com.fnfmod.machine.MachineMenuService.handleAction(sp, payload);
                    }
                }));
        registrar.playToServer(FnfPayloads.AutoWorldMenuC2S.TYPE, FnfPayloads.AutoWorldMenuC2S.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sp) {
                        com.fnfmod.machine.MachineMenuService.openAutomatic(sp);
                    }
                }));
        registrar.playToServer(FnfPayloads.HitboxBuilderC2S.TYPE, FnfPayloads.HitboxBuilderC2S.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sp) {
                        com.fnfmod.machine.MachineHitboxService.handleBuilder(sp, payload);
                    }
                }));
        registrar.playToServer(FnfPayloads.ConfirmHitboxRemovalC2S.TYPE,
                FnfPayloads.ConfirmHitboxRemovalC2S.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sp) {
                        com.fnfmod.machine.MachineHitboxService.confirmRemoval(sp, payload);
                    }
                }));
        registrar.playToServer(FnfPayloads.MachineDirectPlayC2S.TYPE,
                FnfPayloads.MachineDirectPlayC2S.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sp) {
                        com.fnfmod.machine.MachineMenuService.handleDirectPlay(sp, payload);
                    }
                }));
        registrar.playToServer(FnfPayloads.MachineTaggedPlayC2S.TYPE,
                FnfPayloads.MachineTaggedPlayC2S.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sp) {
                        com.fnfmod.machine.MachineMenuService.handleTaggedPlay(sp, payload);
                    }
                }));
        registrar.playToServer(FnfPayloads.ChunkLoaderEditC2S.TYPE,
                FnfPayloads.ChunkLoaderEditC2S.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sp) {
                        com.fnfmod.world.ChunkLoaderPointService.handleEdit(sp, payload);
                    }
                }));
        registrar.playToServer(FnfPayloads.DesignerActionC2S.TYPE,
                FnfPayloads.DesignerActionC2S.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sp) {
                        com.fnfmod.machine.DesignerPlacementService.handle(sp, payload);
                    }
                }));
        registrar.playToServer(FnfPayloads.ChunkLoaderPropertyC2S.TYPE,
                FnfPayloads.ChunkLoaderPropertyC2S.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sp) {
                        com.fnfmod.world.ChunkLoaderPointService.handleLuaProperty(sp, payload);
                    }
                }));
    }

    /** Routes client-bound payloads to the client-only handler class (never loaded on servers). */
    private static <T extends net.minecraft.network.protocol.common.custom.CustomPacketPayload> void client(
            T payload, IPayloadContext ctx) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ctx.enqueueWork(() -> com.fnfmod.client.ClientNetHandler.handle(payload));
        }
    }
}
