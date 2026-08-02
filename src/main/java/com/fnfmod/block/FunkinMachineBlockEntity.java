package com.fnfmod.block;

import com.fnfmod.FnfMod;
import com.fnfmod.machine.MachineDefinition;
import com.fnfmod.machine.MachineDataHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.Level;

/** Per-placed-machine profile and Lua-owned persistent data. */
public final class FunkinMachineBlockEntity extends BlockEntity implements MachineDataHolder {

    private String profileId = MachineDefinition.DEFAULT_ID;
    private CompoundTag machineData = new CompoundTag();

    public FunkinMachineBlockEntity(BlockPos pos, BlockState state) {
        super(FnfMod.FUNKIN_MACHINE_BLOCK_ENTITY.get(), pos, state);
    }

    /** Upgrades machines placed before block-entity profiles existed. */
    public static FunkinMachineBlockEntity getOrCreate(Level level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof FunkinMachineBlockEntity existing) return existing;
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof FunkinMachineBlock)) return null;
        FunkinMachineBlockEntity created = new FunkinMachineBlockEntity(pos, state);
        level.setBlockEntity(created);
        created.setChanged();
        if (!level.isClientSide) level.sendBlockUpdated(pos, state, state, 3);
        return created;
    }

    public String profileId() {
        return profileId;
    }

    public CompoundTag machineData() {
        return machineData.copy();
    }

    public void setProfileId(String profileId) {
        String next = profileId == null || profileId.isBlank()
                ? MachineDefinition.DEFAULT_ID : profileId.trim().toLowerCase(java.util.Locale.ROOT);
        if (next.length() > 128 || !next.matches("[a-z0-9_.-]+:[a-z0-9_.-]+")) {
            next = MachineDefinition.DEFAULT_ID;
        }
        if (this.profileId.equals(next)) return;
        this.profileId = next;
        sync();
    }

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
        tag.putString("MachineProfile", profileId);
        tag.put("MachineData", machineData.copy());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        profileId = tag.contains("MachineProfile")
                ? tag.getString("MachineProfile") : MachineDefinition.DEFAULT_ID;
        machineData = tag.contains("MachineData")
                ? tag.getCompound("MachineData").copy() : new CompoundTag();
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection connection, ClientboundBlockEntityDataPacket packet,
                             HolderLookup.Provider registries) {
        CompoundTag tag = packet.getTag();
        if (tag != null) loadAdditional(tag, registries);
    }
}
