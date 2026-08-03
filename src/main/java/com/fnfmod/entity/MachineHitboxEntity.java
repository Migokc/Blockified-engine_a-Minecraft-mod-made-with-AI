package com.fnfmod.entity;

import com.fnfmod.FnfMod;
import com.fnfmod.block.MachineAnchorBlockEntity;
import com.fnfmod.machine.MachineEditorService;
import com.fnfmod.machine.MachineHitboxService;
import com.fnfmod.machine.MachineMenuService;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.UUID;

/** Persistent, arbitrary-size, non-colliding interaction volume for a virtual machine. */
public final class MachineHitboxEntity extends Entity {

    private static final EntityDataAccessor<BlockPos> DATA_ANCHOR =
            SynchedEntityData.defineId(MachineHitboxEntity.class, EntityDataSerializers.BLOCK_POS);
    private static final EntityDataAccessor<Optional<UUID>> DATA_GROUP =
            SynchedEntityData.defineId(MachineHitboxEntity.class, EntityDataSerializers.OPTIONAL_UUID);
    private static final EntityDataAccessor<Float> DATA_WIDTH =
            SynchedEntityData.defineId(MachineHitboxEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_HEIGHT =
            SynchedEntityData.defineId(MachineHitboxEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_DEPTH =
            SynchedEntityData.defineId(MachineHitboxEntity.class, EntityDataSerializers.FLOAT);

    public MachineHitboxEntity(EntityType<? extends MachineHitboxEntity> type, Level level) {
        super(type, level);
        noPhysics = true;
        setNoGravity(true);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_ANCHOR, BlockPos.ZERO);
        builder.define(DATA_GROUP, Optional.empty());
        builder.define(DATA_WIDTH, 1f);
        builder.define(DATA_HEIGHT, 1f);
        builder.define(DATA_DEPTH, 1f);
    }

    public void configure(BlockPos anchorPos, UUID groupId, AABB bounds) {
        entityData.set(DATA_ANCHOR, anchorPos.immutable());
        entityData.set(DATA_GROUP, Optional.of(groupId));
        entityData.set(DATA_WIDTH, (float) Math.max(0.01, bounds.getXsize()));
        entityData.set(DATA_HEIGHT, (float) Math.max(0.01, bounds.getYsize()));
        entityData.set(DATA_DEPTH, (float) Math.max(0.01, bounds.getZsize()));
        setPos(bounds.getCenter());
        updateCustomBoundingBox();
    }

    public BlockPos anchorPos() { return entityData.get(DATA_ANCHOR); }
    public Optional<UUID> groupId() { return entityData.get(DATA_GROUP); }
    public float hitboxWidth() { return entityData.get(DATA_WIDTH); }
    public float hitboxHeight() { return entityData.get(DATA_HEIGHT); }
    public float hitboxDepth() { return entityData.get(DATA_DEPTH); }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (key.equals(DATA_WIDTH) || key.equals(DATA_HEIGHT) || key.equals(DATA_DEPTH)) {
            updateCustomBoundingBox();
        }
    }

    private void updateCustomBoundingBox() {
        setBoundingBox(makeBoundingBox());
    }

    /**
     * Vanilla calls makeBoundingBox whenever position/network interpolation is
     * reapplied. Returning the custom volume here prevents a one-frame fallback
     * to EntityType's registered 1x1 box between client sync and tick.
     */
    @Override
    protected AABB makeBoundingBox() {
        double halfX = hitboxWidth() * 0.5;
        double halfZ = hitboxDepth() * 0.5;
        double halfY = hitboxHeight() * 0.5;
        return new AABB(getX() - halfX, getY() - halfY, getZ() - halfZ,
                getX() + halfX, getY() + halfY, getZ() + halfZ);
    }

    @Override
    public void tick() {
        super.tick();
        noPhysics = true;
        setDeltaMovement(Vec3.ZERO);
        updateCustomBoundingBox();
        if (!level().isClientSide && tickCount % 20 == 0 && level().hasChunkAt(anchorPos())) {
            if (!(level().getBlockEntity(anchorPos()) instanceof MachineAnchorBlockEntity anchor)
                    || groupId().isEmpty() || !groupId().get().equals(anchor.groupId())) {
                discard();
            }
        }
    }

    @Override public boolean isPickable() { return true; }
    @Override public boolean canBeHitByProjectile() { return false; }
    @Override public boolean canBeCollidedWith() { return false; }
    @Override public boolean canCollideWith(Entity other) { return false; }
    @Override public boolean isAttackable() { return true; }
    @Override public boolean isInvulnerableTo(DamageSource source) { return true; }
    @Override public boolean isPushable() { return false; }
    @Override public boolean isPushedByFluid() { return false; }
    @Override protected boolean canAddPassenger(Entity passenger) { return false; }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (!level().isClientSide && player instanceof ServerPlayer serverPlayer) {
            if (player.getItemInHand(hand).is(FnfMod.FUNKIN_DESIGNER.get())) {
                if (player.isShiftKeyDown()) {
                    MachineEditorService.copyOrApply(serverPlayer, anchorPos());
                } else {
                    MachineEditorService.open(serverPlayer, anchorPos());
                }
            } else {
                MachineMenuService.onInteract(serverPlayer, anchorPos());
            }
        }
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    @Override
    public boolean skipAttackInteraction(Entity attacker) {
        if (!level().isClientSide && attacker instanceof ServerPlayer player
                && (player.getMainHandItem().is(FnfMod.FUNKIN_DESIGNER.get())
                || player.getOffhandItem().is(FnfMod.FUNKIN_DESIGNER.get()))) {
            MachineHitboxService.requestRemoval(player, this);
        }
        return true;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        entityData.set(DATA_ANCHOR, BlockPos.of(tag.getLong("Anchor")));
        entityData.set(DATA_GROUP, tag.hasUUID("Group") ? Optional.of(tag.getUUID("Group")) : Optional.empty());
        entityData.set(DATA_WIDTH, Math.max(0.01f, tag.getFloat("Width")));
        entityData.set(DATA_HEIGHT, Math.max(0.01f, tag.getFloat("Height")));
        entityData.set(DATA_DEPTH, Math.max(0.01f, tag.getFloat("Depth")));
        updateCustomBoundingBox();
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putLong("Anchor", anchorPos().asLong());
        groupId().ifPresent(uuid -> tag.putUUID("Group", uuid));
        tag.putFloat("Width", hitboxWidth());
        tag.putFloat("Height", hitboxHeight());
        tag.putFloat("Depth", hitboxDepth());
    }
}
