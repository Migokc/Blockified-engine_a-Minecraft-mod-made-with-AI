package com.fnfmod.machine;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Per-dimension tag index: finding a stage must not require keeping all stages loaded. */
public final class VirtualMachineData extends SavedData {
    private static final Factory<VirtualMachineData> FACTORY =
            new Factory<>(VirtualMachineData::new, VirtualMachineData::load);
    private final Map<BlockPos, String> anchors = new LinkedHashMap<>();

    public static VirtualMachineData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, "fnfmod_virtual_machines");
    }

    public static String normalizeTag(String value) {
        String tag = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return tag.length() <= 64 && tag.matches("[a-z0-9_-]+") ? tag : "";
    }

    public void put(BlockPos pos, String tag) {
        String normalized = normalizeTag(tag);
        if (normalized.isEmpty()) { remove(pos); return; }
        if (!normalized.equals(anchors.put(pos.immutable(), normalized))) setDirty();
    }

    public void remove(BlockPos pos) {
        if (anchors.remove(pos) != null) setDirty();
    }

    /** Return all matches, so ambiguous tags are rejected instead of picking an arbitrary stage. */
    public List<BlockPos> find(String tag) {
        String normalized = normalizeTag(tag);
        return anchors.entrySet().stream().filter(entry -> entry.getValue().equals(normalized))
                .map(Map.Entry::getKey).toList();
    }

    static VirtualMachineData load(CompoundTag tag, HolderLookup.Provider registries) {
        VirtualMachineData data = new VirtualMachineData();
        ListTag list = tag.getList("Anchors", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            String id = normalizeTag(entry.getString("Tag"));
            if (!id.isEmpty()) data.anchors.put(BlockPos.of(entry.getLong("Pos")), id);
        }
        return data;
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        anchors.forEach((pos, id) -> {
            CompoundTag entry = new CompoundTag();
            entry.putLong("Pos", pos.asLong());
            entry.putString("Tag", id);
            list.add(entry);
        });
        tag.put("Anchors", list);
        return tag;
    }
}
