package com.fnfmod.machine;

import com.fnfmod.FnfMod;
import com.fnfmod.block.ChunkLoaderPointBlockEntity;
import com.fnfmod.block.MachineAnchorBlock;
import com.fnfmod.block.MachineAnchorBlockEntity;
import com.fnfmod.net.FnfPayloads;
import com.fnfmod.world.ChunkLoaderPointService;
import com.fnfmod.world.ModContentScope;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Server-authoritative click-to-place modes owned by the Funkin' Designer. */
@EventBusSubscriber(modid = FnfMod.MODID)
public final class DesignerPlacementService {

    public static final byte NONE = 0;
    public static final byte CHUNK_LOADER = 1;
    public static final byte STANDALONE_MACHINE = 2;

    private record Pending(byte mode, String profileId, String tag) { }
    private static final Map<UUID, Pending> PENDING = new HashMap<>();

    private DesignerPlacementService() { }

    public static void handle(ServerPlayer player, FnfPayloads.DesignerActionC2S payload) {
        if (!canUse(player)) return;
        if (payload.action() == FnfPayloads.DesignerActionC2S.CANCEL) {
            cancel(player, true);
            return;
        }
        if (payload.action() == FnfPayloads.DesignerActionC2S.START_CHUNK_LOADER) {
            begin(player, new Pending(CHUNK_LOADER, "", ""),
                    "Chunk-loader placement ready. Right-click a block face with the Designer.");
            return;
        }
        if (payload.action() != FnfPayloads.DesignerActionC2S.START_STANDALONE_MACHINE
                || !ModContentScope.isModWorld()) return;
        MachineDefinition profile = MachineLibrary.find(payload.profileId()).orElse(null);
        String tag = normalizeTag(payload.tag());
        if (profile == null || tag.isBlank()) {
            player.displayClientMessage(Component.literal(
                    "Choose a valid machine profile and enter an ID before placement."), true);
            return;
        }
        begin(player, new Pending(STANDALONE_MACHINE, profile.id(), tag),
                "Virtual-machine placement ready. Right-click a block face with the Designer.");
    }

    private static void begin(ServerPlayer player, Pending pending, String message) {
        MachineHitboxService.cancel(player, false);
        PENDING.put(player.getUUID(), pending);
        state(player, pending.mode());
        player.displayClientMessage(Component.literal(message), true);
    }

    public static boolean hasPending(ServerPlayer player) {
        return PENDING.containsKey(player.getUUID());
    }

    public static boolean place(ServerPlayer player, UseOnContext context) {
        Pending pending = PENDING.get(player.getUUID());
        if (pending == null || !canUse(player)) return false;
        ServerLevel level = player.serverLevel();
        BlockPos pos = new BlockPlaceContext(context).getClickedPos();
        if (level.isOutsideBuildHeight(pos) || !level.hasChunkAt(pos)
                || !level.getBlockState(pos).canBeReplaced()) {
            player.displayClientMessage(Component.literal(
                    "That position is blocked. Aim at another block face and try again."), true);
            return true;
        }

        boolean placed = pending.mode() == CHUNK_LOADER
                ? placeChunkLoader(level, pos)
                : placeMachine(level, player, pos, pending);
        if (!placed) {
            player.displayClientMessage(Component.literal("Could not place the selected Designer block."), true);
            return true;
        }
        PENDING.remove(player.getUUID());
        state(player, NONE);
        player.displayClientMessage(Component.literal((pending.mode() == CHUNK_LOADER
                ? "Chunk-loader point" : "Virtual machine '" + pending.tag() + "'")
                + " placed at " + pos.toShortString() + "."), true);
        return true;
    }

    private static boolean placeChunkLoader(ServerLevel level, BlockPos pos) {
        if (!level.setBlock(pos, FnfMod.CHUNK_LOADER_POINT.get().defaultBlockState(), 3)
                || !(level.getBlockEntity(pos) instanceof ChunkLoaderPointBlockEntity point)) return false;
        point.ensureDefaultTag();
        point.configure(point.pointTag(), ChunkLoaderPointService.DEFAULT_RADIUS, true);
        return true;
    }

    private static boolean placeMachine(ServerLevel level, ServerPlayer player, BlockPos pos, Pending pending) {
        if (!ModContentScope.isModWorld()) return false;
        var state = FnfMod.MACHINE_ANCHOR.get().defaultBlockState()
                .setValue(MachineAnchorBlock.FACING, player.getDirection().getOpposite());
        if (!level.setBlock(pos, state, 3)
                || !(level.getBlockEntity(pos) instanceof MachineAnchorBlockEntity anchor)) return false;
        anchor.configureStandalone(pending.profileId(), pending.tag());
        return true;
    }

    public static void cancel(ServerPlayer player, boolean message) {
        if (PENDING.remove(player.getUUID()) != null) {
            state(player, NONE);
            if (message) player.displayClientMessage(Component.literal("Designer placement cancelled."), true);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) PENDING.remove(player.getUUID());
    }

    private static void state(ServerPlayer player, byte mode) {
        PacketDistributor.sendToPlayer(player, new FnfPayloads.DesignerPlacementStateS2C(mode));
    }

    private static boolean canUse(ServerPlayer player) {
        var server = player.getServer();
        return server != null && !server.isDedicatedServer()
                && (server.isSingleplayerOwner(player.getGameProfile()) || player.hasPermissions(2));
    }

    private static String normalizeTag(String value) {
        String tag = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return tag.length() <= 64 && tag.matches("[a-z0-9_-]+") ? tag : "";
    }
}
