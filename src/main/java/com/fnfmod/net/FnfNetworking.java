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
        PayloadRegistrar registrar = event.registrar("1");

        // client-bound
        registrar.playToClient(FnfPayloads.OpenMenuS2C.TYPE, FnfPayloads.OpenMenuS2C.CODEC, FnfNetworking::client);
        registrar.playToClient(FnfPayloads.SessionStateS2C.TYPE, FnfPayloads.SessionStateS2C.CODEC, FnfNetworking::client);
        registrar.playToClient(FnfPayloads.FileManifestS2C.TYPE, FnfPayloads.FileManifestS2C.CODEC, FnfNetworking::client);
        registrar.playToClient(FnfPayloads.FileChunkS2C.TYPE, FnfPayloads.FileChunkS2C.CODEC, FnfNetworking::client);
        registrar.playToClient(FnfPayloads.StartSongS2C.TYPE, FnfPayloads.StartSongS2C.CODEC, FnfNetworking::client);
        registrar.playToClient(FnfPayloads.PartnerNoteS2C.TYPE, FnfPayloads.PartnerNoteS2C.CODEC, FnfNetworking::client);
        registrar.playToClient(FnfPayloads.PartnerEndS2C.TYPE, FnfPayloads.PartnerEndS2C.CODEC, FnfNetworking::client);
        registrar.playToClient(FnfPayloads.SessionCancelS2C.TYPE, FnfPayloads.SessionCancelS2C.CODEC, FnfNetworking::client);

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
        registrar.playToServer(FnfPayloads.LeaveC2S.TYPE, FnfPayloads.LeaveC2S.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sp) SessionManager.onLeave(sp, payload.pos(), payload.finishedOnly(), payload.reopenMenu());
                }));
        registrar.playToServer(FnfPayloads.ReloadC2S.TYPE, FnfPayloads.ReloadC2S.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sp) SessionManager.onReloadRequest(sp);
                }));
        registrar.playToServer(FnfPayloads.SetHealthC2S.TYPE, FnfPayloads.SetHealthC2S.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sp) SessionManager.onSetHealth(sp, payload.health());
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
