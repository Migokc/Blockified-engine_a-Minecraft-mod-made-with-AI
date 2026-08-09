package com.fnfmod.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.Map;

/** Persistent index used to find chunk-loader blocks even while they are disabled. */
public final class ChunkLoaderPointData extends SavedData {

    public record Point(String tag, int radius, boolean enabled) {}

    private static final String FILE_ID = "fnfmod_chunk_loader_points";
    private static final Factory<ChunkLoaderPointData> FACTORY =
            new Factory<>(ChunkLoaderPointData::new, ChunkLoaderPointData::load);

    private final Map<BlockPos, Point> points = new LinkedHashMap<>();

    public static ChunkLoaderPointData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, FILE_ID);
    }

    private static ChunkLoaderPointData load(CompoundTag tag, HolderLookup.Provider registries) {
        ChunkLoaderPointData data = new ChunkLoaderPointData();
        ListTag list = tag.getList("Points", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            BlockPos pos = BlockPos.of(entry.getLong("Pos"));
            String id = ChunkLoaderPointService.normalizeTag(entry.getString("Tag"), pos);
            int radius = ChunkLoaderPointService.clampRadius(entry.getInt("Radius"));
            data.points.put(pos, new Point(id, radius, entry.getBoolean("Enabled")));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (var entry : points.entrySet()) {
            CompoundTag point = new CompoundTag();
            point.putLong("Pos", entry.getKey().asLong());
            point.putString("Tag", entry.getValue().tag());
            point.putInt("Radius", entry.getValue().radius());
            point.putBoolean("Enabled", entry.getValue().enabled());
            list.add(point);
        }
        tag.put("Points", list);
        return tag;
    }

    public Point point(BlockPos pos) {
        return points.get(pos);
    }

    public BlockPos find(String tag) {
        if (tag == null) return null;
        for (var entry : points.entrySet()) {
            if (entry.getValue().tag().equalsIgnoreCase(tag.trim())) return entry.getKey();
        }
        return null;
    }

    public boolean tagAvailable(String tag, BlockPos owner) {
        BlockPos existing = find(tag);
        return existing == null || existing.equals(owner);
    }

    public void put(BlockPos pos, String tag, int radius, boolean enabled) {
        Point next = new Point(tag, radius, enabled);
        if (next.equals(points.get(pos))) return;
        points.put(pos.immutable(), next);
        setDirty();
    }

    public void remove(BlockPos pos) {
        if (points.remove(pos) != null) setDirty();
    }

    public Map<BlockPos, Point> points() {
        return Map.copyOf(points);
    }
}
