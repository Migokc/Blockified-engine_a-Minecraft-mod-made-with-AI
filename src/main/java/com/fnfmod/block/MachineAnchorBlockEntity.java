package com.fnfmod.block;

import com.fnfmod.FnfMod;
import com.fnfmod.machine.MachineDataHolder;
import com.fnfmod.machine.MachineDefinition;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.Locale;
import java.util.UUID;

/** Invisible virtual-machine origin. Owns profile, menu data, and hitbox bounds. */
public final class MachineAnchorBlockEntity extends BlockEntity implements MachineDataHolder {

    private UUID groupId = UUID.randomUUID();
    private String profileId = MachineDefinition.DEFAULT_ID;
    private CompoundTag machineData = new CompoundTag();
    private AABB selectionBounds = new AABB(0, 0, 0, 0, 0, 0);

    public MachineAnchorBlockEntity(BlockPos pos, BlockState state) {
        super(FnfMod.MACHINE_ANCHOR_BLOCK_ENTITY.get(), pos, state);
    }

    public UUID groupId() { return groupId; }
    public AABB selectionBounds() { return selectionBounds; }

    public void configure(UUID groupId, String profileId, AABB bounds) {
        this.groupId = groupId == null ? UUID.randomUUID() : groupId;
        this.selectionBounds = bounds == null ? new AABB(worldPosition) : bounds;
        setProfileIdInternal(profileId);
        sync();
    }

    @Override public String profileId() { return profileId; }
    @Override public CompoundTag machineData() { return machineData.copy(); }

    @Override
    public void setProfileId(String profileId) {
        setProfileIdInternal(profileId);
        sync();
    }

    private void setProfileIdInternal(String value) {
        String next = value == null || value.isBlank()
                ? MachineDefinition.DEFAULT_ID : value.trim().toLowerCase(Locale.ROOT);
        profileId = next.length() <= 128 && next.matches("[a-z0-9_.-]+:[a-z0-9_.-]+")
                ? next : MachineDefinition.DEFAULT_ID;
    }

    @Override
    public void setMachineData(CompoundTag data) {
        machineData = data == null ? new CompoundTag() : data.copy();
        sync();
    }

    private void sync() {
        setChanged();
        if (level != null && !level.isClientSide) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, 3);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putUUID("Group", groupId);
        tag.putString("MachineProfile", profileId);
        tag.put("MachineData", machineData.copy());
        tag.putDouble("MinX", selectionBounds.minX);
        tag.putDouble("MinY", selectionBounds.minY);
        tag.putDouble("MinZ", selectionBounds.minZ);
        tag.putDouble("MaxX", selectionBounds.maxX);
        tag.putDouble("MaxY", selectionBounds.maxY);
        tag.putDouble("MaxZ", selectionBounds.maxZ);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        groupId = tag.hasUUID("Group") ? tag.getUUID("Group") : UUID.randomUUID();
        setProfileIdInternal(tag.getString("MachineProfile"));
        machineData = tag.contains("MachineData") ? tag.getCompound("MachineData").copy() : new CompoundTag();
        selectionBounds = new AABB(tag.getDouble("MinX"), tag.getDouble("MinY"), tag.getDouble("MinZ"),
                tag.getDouble("MaxX"), tag.getDouble("MaxY"), tag.getDouble("MaxZ"));
    }

    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) { return saveWithoutMetadata(registries); }
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }

    @Override
    public void onDataPacket(Connection connection, ClientboundBlockEntityDataPacket packet,
                             HolderLookup.Provider registries) {
        if (packet.getTag() != null) loadAdditional(packet.getTag(), registries);
    }
}
