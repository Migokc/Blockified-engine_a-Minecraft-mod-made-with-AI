package com.fnfmod.block;

import com.fnfmod.FnfMod;
import com.fnfmod.world.ChunkLoaderPointService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Persistent tag, radius, and enabled state for one invisible loading point. */
public final class ChunkLoaderPointBlockEntity extends BlockEntity {

    private String pointTag = "";
    private int radius = ChunkLoaderPointService.DEFAULT_RADIUS;
    private boolean enabled = true;

    public ChunkLoaderPointBlockEntity(BlockPos pos, BlockState state) {
        super(FnfMod.CHUNK_LOADER_POINT_BLOCK_ENTITY.get(), pos, state);
    }

    public String pointTag() { return pointTag; }
    public int radius() { return radius; }
    public boolean enabled() { return enabled; }

    public void ensureDefaultTag() {
        if (!pointTag.isBlank()) return;
        pointTag = ChunkLoaderPointService.normalizeTag("", worldPosition);
        sync();
    }

    public void configure(String tag, int radius, boolean enabled) {
        ensureDefaultTag();
        String nextTag = ChunkLoaderPointService.normalizeTag(tag, worldPosition);
        int nextRadius = ChunkLoaderPointService.clampRadius(radius);
        if (pointTag.equals(nextTag) && this.radius == nextRadius && this.enabled == enabled) return;
        int oldRadius = this.radius;
        boolean oldEnabled = this.enabled;
        pointTag = nextTag;
        this.radius = nextRadius;
        this.enabled = enabled;
        if (level instanceof ServerLevel serverLevel) {
            ChunkLoaderPointService.refresh(serverLevel, worldPosition, oldRadius, oldEnabled,
                    this.radius, this.enabled, pointTag);
        }
        sync();
    }

    /** Reconciles persistent tickets after SessionManager restores this block entity's NBT. */
    public void reconcileAfterRollback(int replacedRadius, boolean replacedEnabled) {
        ensureDefaultTag();
        if (level instanceof ServerLevel serverLevel) {
            ChunkLoaderPointService.refresh(serverLevel, worldPosition, replacedRadius, replacedEnabled,
                    radius, enabled, pointTag);
        }
        sync();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        ChunkLoaderPointService.onLoaded(this);
    }

    @Override
    public void setRemoved() {
        ChunkLoaderPointService.unregisterLoaded(this);
        super.setRemoved();
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
        tag.putString("PointTag", pointTag);
        tag.putInt("Radius", radius);
        tag.putBoolean("Enabled", enabled);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        pointTag = tag.getString("PointTag");
        radius = ChunkLoaderPointService.clampRadius(tag.contains("Radius")
                ? tag.getInt("Radius") : ChunkLoaderPointService.DEFAULT_RADIUS);
        enabled = !tag.contains("Enabled") || tag.getBoolean("Enabled");
    }

    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) { return saveWithoutMetadata(registries); }
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }

    @Override
    public void onDataPacket(Connection connection, ClientboundBlockEntityDataPacket packet,
                             HolderLookup.Provider registries) {
        if (packet.getTag() != null) loadAdditional(packet.getTag(), registries);
    }
}
