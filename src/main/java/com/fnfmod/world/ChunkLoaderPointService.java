package com.fnfmod.world;

import com.fnfmod.FnfMod;
import com.fnfmod.block.ChunkLoaderPointBlockEntity;
import com.fnfmod.net.FnfPayloads;
import com.fnfmod.session.SessionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/** Persistent ticket management and Designer/Lua mutation validation. */
public final class ChunkLoaderPointService {

    public static final int MIN_RADIUS = 0;
    public static final int MAX_RADIUS = 12;
    public static final int DEFAULT_RADIUS = 2;

    private static final Map<Level, Map<BlockPos, ChunkLoaderPointBlockEntity>> LOADED =
            new WeakHashMap<>();

    public static final TicketController TICKETS = new TicketController(
            FnfMod.id("chunk_loader_points"), (level, helper) -> {
                ChunkLoaderPointData data = ChunkLoaderPointData.get(level);
                for (BlockPos owner : new ArrayList<>(helper.getBlockTickets().keySet())) {
                    ChunkLoaderPointData.Point point = data.point(owner);
                    if (level.getServer().isDedicatedServer() || point == null || !point.enabled()) {
                        helper.removeAllTickets(owner);
                    }
                }
            });

    private ChunkLoaderPointService() {}

    public static void registerTicketController(RegisterTicketControllersEvent event) {
        event.register(TICKETS);
    }

    public static int clampRadius(int radius) {
        return Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, radius));
    }

    public static String normalizeTag(String value, BlockPos pos) {
        String tag = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (tag.length() > 64 || !tag.matches("[a-z0-9_-]+")) {
            tag = "chunk_" + pos.getX() + "_" + pos.getY() + "_" + pos.getZ();
        }
        return tag;
    }

    public static synchronized void registerLoaded(ChunkLoaderPointBlockEntity point) {
        if (point.getLevel() == null) return;
        LOADED.computeIfAbsent(point.getLevel(), ignored -> new java.util.LinkedHashMap<>())
                .put(point.getBlockPos().immutable(), point);
    }

    public static synchronized void unregisterLoaded(ChunkLoaderPointBlockEntity point) {
        if (point.getLevel() == null) return;
        Map<BlockPos, ChunkLoaderPointBlockEntity> points = LOADED.get(point.getLevel());
        if (points != null) {
            points.remove(point.getBlockPos());
            if (points.isEmpty()) LOADED.remove(point.getLevel());
        }
    }

    public static synchronized ChunkLoaderPointBlockEntity findLoaded(Level level, String tag) {
        Map<BlockPos, ChunkLoaderPointBlockEntity> points = LOADED.get(level);
        if (points == null || tag == null) return null;
        for (ChunkLoaderPointBlockEntity point : points.values()) {
            if (!point.isRemoved() && point.pointTag().equalsIgnoreCase(tag.trim())) return point;
        }
        return null;
    }

    public static void onLoaded(ChunkLoaderPointBlockEntity point) {
        registerLoaded(point);
        if (!(point.getLevel() instanceof ServerLevel level)) return;
        point.ensureDefaultTag();
        ChunkLoaderPointData.get(level).put(point.getBlockPos(), point.pointTag(),
                point.radius(), point.enabled());
        if (point.enabled()) forceRadius(level, point.getBlockPos(), point.radius(), true);
    }

    public static void onRemoved(ServerLevel level, BlockPos pos, int radius, boolean enabled) {
        if (enabled) forceRadius(level, pos, radius, false);
        ChunkLoaderPointData.get(level).remove(pos);
    }

    public static void refresh(ServerLevel level, BlockPos pos,
                               int oldRadius, boolean oldEnabled,
                               int newRadius, boolean newEnabled,
                               String tag) {
        oldRadius = clampRadius(oldRadius);
        newRadius = clampRadius(newRadius);
        if (oldEnabled && (!newEnabled || oldRadius != newRadius)) {
            forceRadius(level, pos, oldRadius, false);
        }
        if (newEnabled && (!oldEnabled || oldRadius != newRadius)) {
            forceRadius(level, pos, newRadius, true);
        }
        ChunkLoaderPointData.get(level).put(pos, tag, newRadius, newEnabled);
    }

    private static void forceRadius(ServerLevel level, BlockPos owner, int radius, boolean add) {
        if (level.getServer().isDedicatedServer()) return;
        int centerX = owner.getX() >> 4;
        int centerZ = owner.getZ() >> 4;
        int clamped = clampRadius(radius);
        for (int x = centerX - clamped; x <= centerX + clamped; x++) {
            for (int z = centerZ - clamped; z <= centerZ + clamped; z++) {
                TICKETS.forceChunk(level, owner, x, z, add, true);
            }
        }
    }

    public static void openEditor(ServerPlayer player, BlockPos pos) {
        if (!canEdit(player) || player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5,
                pos.getZ() + 0.5) > 64.0) return;
        if (!(player.level().getBlockEntity(pos) instanceof ChunkLoaderPointBlockEntity point)) return;
        PacketDistributor.sendToPlayer(player, new FnfPayloads.OpenChunkLoaderEditorS2C(
                pos, point.pointTag(), point.radius(), point.enabled()));
    }

    public static void handleEdit(ServerPlayer player, FnfPayloads.ChunkLoaderEditC2S payload) {
        if (!canEdit(player) || player.distanceToSqr(payload.pos().getX() + 0.5,
                payload.pos().getY() + 0.5, payload.pos().getZ() + 0.5) > 64.0) return;
        if (!(player.level().getBlockEntity(payload.pos()) instanceof ChunkLoaderPointBlockEntity point)) return;
        String tag = normalizeTag(payload.tag(), payload.pos());
        ChunkLoaderPointData data = ChunkLoaderPointData.get(player.serverLevel());
        if (!data.tagAvailable(tag, payload.pos())) {
            result(player, false, "Another chunk-loader point already uses tag '" + tag + "'.", point);
            return;
        }
        point.configure(tag, clampRadius(payload.radius()), payload.enabled());
        result(player, true, "Chunk-loader point saved.", point);
    }

    public static void handleLuaProperty(ServerPlayer player, FnfPayloads.ChunkLoaderPropertyC2S payload) {
        ServerLevel level = player.serverLevel();
        ChunkLoaderPointData data = ChunkLoaderPointData.get(level);
        BlockPos pos = data.find(payload.tag());
        if (pos == null) return;
        if (!SessionManager.captureLuaPointMutation(player, payload.machinePos(), level, pos)) return;
        level.getChunkAt(pos);
        if (!(level.getBlockEntity(pos) instanceof ChunkLoaderPointBlockEntity point)) {
            ChunkLoaderPointData.Point stale = data.point(pos);
            if (stale != null) onRemoved(level, pos, stale.radius(), stale.enabled());
            return;
        }

        switch (payload.property().toLowerCase(Locale.ROOT)) {
            case "enabled", "active", "on" -> point.configure(
                    point.pointTag(), point.radius(), luaBoolean(payload.value()));
            case "radius" -> {
                try {
                    point.configure(point.pointTag(), clampRadius((int) Math.round(
                            Double.parseDouble(payload.value()))), point.enabled());
                } catch (NumberFormatException ignored) {}
            }
            case "tag", "id" -> {
                String next = normalizeTag(payload.value(), pos);
                if (data.tagAvailable(next, pos)) point.configure(next, point.radius(), point.enabled());
            }
            default -> { }
        }
    }

    private static boolean canEdit(ServerPlayer player) {
        var server = player.getServer();
        return server != null && !server.isDedicatedServer()
                && (server.isSingleplayerOwner(player.getGameProfile()) || player.hasPermissions(2));
    }

    private static boolean luaBoolean(String value) {
        if (value == null) return false;
        if (value.equalsIgnoreCase("true")) return true;
        if (value.equalsIgnoreCase("false")) return false;
        try { return Double.parseDouble(value) != 0; }
        catch (NumberFormatException ignored) { return false; }
    }

    private static void result(ServerPlayer player, boolean success, String message,
                               ChunkLoaderPointBlockEntity point) {
        PacketDistributor.sendToPlayer(player, new FnfPayloads.ChunkLoaderEditorResultS2C(
                success, message, point.pointTag(), point.radius(), point.enabled()));
    }
}
