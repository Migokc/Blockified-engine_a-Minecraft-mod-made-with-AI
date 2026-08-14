package com.fnfmod.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Client-side physics host for a Lua world sprite or 2D world character.
 *
 * <p>The visual stays in Blockified's client renderer, but its transform,
 * collision, gravity and vanilla blob shadow belong to a real Minecraft entity.
 * These hosts are never spawned by or synchronized with the server.</p>
 */
public final class WorldSpriteEntity extends ItemEntity {
    private static final AtomicInteger NEXT_CLIENT_ID = new AtomicInteger(-2_000_000);
    private float visualWidth = 0.5f;
    private float visualHeight = 0.5f;

    public WorldSpriteEntity(EntityType<? extends WorldSpriteEntity> type, Level level) {
        super(type, level);
        // IRLights' caster source supports LivingEntity and ItemEntity. A harmless
        // non-empty stack keeps vanilla ItemEntity ticking without self-discarding;
        // Blockified's registered renderer replaces the item visual completely.
        setItem(new ItemStack(Items.BARRIER));
        setUnlimitedLifetime();
        noPhysics = true;
        setNoGravity(true);
        setInvulnerable(true);
    }

    public static int allocateClientId() { return NEXT_CLIENT_ID.getAndDecrement(); }

    public void setVisualBounds(double width, double height) {
        visualWidth = (float) Math.max(0.02, Math.min(64.0, Math.abs(width)));
        visualHeight = (float) Math.max(0.02, Math.min(64.0, Math.abs(height)));
        setBoundingBox(makeBoundingBox());
    }

    @Override
    protected AABB makeBoundingBox() {
        double halfWidth = visualWidth * 0.5;
        double halfHeight = visualHeight * 0.5;
        return new AABB(getX() - halfWidth, getY() - halfHeight, getZ() - halfWidth,
                getX() + halfWidth, getY() + halfHeight, getZ() + halfWidth);
    }

    @Override
    public void tick() {
        super.tick();
        setBoundingBox(makeBoundingBox());
    }

    @Override public boolean isPickable() { return false; }
    @Override public boolean canBeCollidedWith() { return !noPhysics; }
    @Override public boolean isPushable() { return !noPhysics; }
    @Override public boolean isPushedByFluid() { return false; }
    @Override public boolean isAttackable() { return false; }
    @Override public void playerTouch(Player player) { /* client-only physics host; never collectible */ }

    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) { super.defineSynchedData(builder); }
    @Override public void readAdditionalSaveData(CompoundTag tag) {}
    @Override public void addAdditionalSaveData(CompoundTag tag) {}
}
