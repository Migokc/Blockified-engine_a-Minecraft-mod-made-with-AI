package com.fnfmod.client.gameplay;

import com.fnfmod.client.anim.CharacterAnimations;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.entity.Entity;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Gives the solo opponent bot a real BBS character. BBS morphs attach only to players
 * ({@code IMorphProvider}), so — exactly like {@link com.fnfmod.client.anim.ExtraCharacterRoster}
 * does for Add Character events — this spawns a client-only {@link RemotePlayer} and
 * morphs it. It follows the server-spawned bot armor stand (which stays as the command
 * target) and hides it. {@link #create} returns null when the character has no BBS form,
 * so the caller simply keeps the armor stand.
 */
public final class OpponentBotCharacter {
    private static final AtomicInteger NEXT_ID = new AtomicInteger(-40000);

    private final RemotePlayer entity;
    private final String set;
    private final String role;

    private OpponentBotCharacter(RemotePlayer entity, String set, String role) {
        this.entity = entity;
        this.set = set;
        this.role = role;
    }

    /**
     * Creates the bot's character, or null if the set is disabled or has no BBS form
     * (the caller then keeps the armor stand). {@code role} is "player"/"opponent".
     */
    public static OpponentBotCharacter create(ClientLevel level, String set, String role, Entity stand) {
        if (level == null || set == null || stand == null || CharacterAnimations.isDisabled(set)) return null;
        UUID id = UUID.nameUUIDFromBytes(("blockified:bot:" + role).getBytes(StandardCharsets.UTF_8));
        String name = "Bot_" + role;
        RemotePlayer player = new RemotePlayer(level, new GameProfile(id, name.length() > 16 ? name.substring(0, 16) : name));
        player.setId(NEXT_ID.getAndDecrement());
        player.noPhysics = true;
        player.setInvulnerable(true);
        player.setPos(stand.getX(), stand.getY(), stand.getZ());
        level.addEntity(player);
        // Apply the BBS form. If the character/animation name has no form, bail so the
        // caller falls back to the plain armor stand.
        if (!CharacterAnimations.prepare(player, set, role)) {
            level.removeEntity(player.getId(), Entity.RemovalReason.DISCARDED);
            return null;
        }
        return new OpponentBotCharacter(player, set, role);
    }

    /** Keeps the character on the bot stand's spot and hides the stand. Call each frame. */
    public void follow(Entity stand) {
        if (stand == null) return;
        entity.setPos(stand.getX(), stand.getY(), stand.getZ());
        float yaw = stand.getYRot();
        entity.setYRot(yaw);
        entity.setYHeadRot(yaw);
        entity.setYBodyRot(yaw);
        stand.setInvisible(true);
    }

    /** Plays a sing/miss/hey animation on the opponent bot. */
    public void play(String action) {
        CharacterAnimations.play(entity, set, role, action);
    }

    /** Beat idle; skipped for loopIdle characters (BBS keeps their main state running). */
    public void idle() {
        if (!CharacterAnimations.loopIdle(set, role)) {
            CharacterAnimations.play(entity, set, role, "idle");
        }
    }

    public String role() {
        return role;
    }

    /** Removes the character and un-hides the armor stand. */
    public void remove(Entity stand) {
        if (stand != null) stand.setInvisible(false);
        CharacterAnimations.release(entity);
        if (entity.level() instanceof ClientLevel level && level.getEntity(entity.getId()) != null) {
            level.removeEntity(entity.getId(), Entity.RemovalReason.DISCARDED);
        }
    }
}
