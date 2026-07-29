package com.fnfmod.client.gameplay;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.Comparator;

/**
 * Keeps the chunks around the Funkin' Machine loaded and ticking for the whole
 * song, in singleplayer.
 *
 * <p>The player anchors chunk loading, but the detached gameplay camera, the
 * opponent bot and tweened performers do not. When one of them sits in a chunk
 * that the server lets fall out of the ticking radius, that chunk stops ticking
 * and can unload; the entity there desyncs and the camera that follows it jumps.
 * A non-persistent region ticket around the machine holds the stage region in
 * place so nothing gameplay touches is ever in an unloaded chunk. The ticket is
 * not saved to disk, so it simply disappears if the game exits uncleanly.
 */
public final class StageChunkLoader {

    private static final TicketType<ChunkPos> STAGE =
            TicketType.create("fnfmod_stage", Comparator.comparingLong(ChunkPos::toLong));

    private static ResourceKey<Level> forcedDimension;
    private static ChunkPos forcedCenter;
    private static int forcedDistance;

    private StageChunkLoader() {}

    /** Forces a square of {@code radiusChunks} around the machine to stay ticking. */
    public static void load(BlockPos machine, int radiusChunks) {
        Minecraft minecraft = Minecraft.getInstance();
        MinecraftServer server = minecraft.getSingleplayerServer();
        if (server == null || minecraft.level == null || machine == null) {
            return; // Multiplayer client cannot force chunks; the server owns them.
        }
        unload();
        ResourceKey<Level> dimension = minecraft.level.dimension();
        ChunkPos center = new ChunkPos(machine);
        // Ticket "distance" of R+1 makes chunks within R of the center tick.
        int distance = Math.max(2, radiusChunks + 1);
        forcedDimension = dimension;
        forcedCenter = center;
        forcedDistance = distance;
        server.execute(() -> {
            ServerLevel level = server.getLevel(dimension);
            if (level != null) {
                level.getChunkSource().addRegionTicket(STAGE, center, distance, center);
            }
        });
    }

    /** Releases the stage region. Safe to call when nothing is forced. */
    public static void unload() {
        if (forcedCenter == null || forcedDimension == null) return;
        ResourceKey<Level> dimension = forcedDimension;
        ChunkPos center = forcedCenter;
        int distance = forcedDistance;
        forcedDimension = null;
        forcedCenter = null;
        forcedDistance = 0;
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return;
        server.execute(() -> {
            ServerLevel level = server.getLevel(dimension);
            if (level != null) {
                level.getChunkSource().removeRegionTicket(STAGE, center, distance, center);
            }
        });
    }
}
