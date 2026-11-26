package com.fnfmod.client.gameplay;

import com.fnfmod.client.anim.CharacterAnimations;
import com.fnfmod.client.render.PerformerRotation;
import com.fnfmod.gameplay.PerformerCollisions;
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
    private String set;
    private final String role;
    private final CharacterAnimations.SkinChoice skinChoice;
    private long lastSingMs;
    private long lastStatePlayMs;
    private String specialAnimation;

    private OpponentBotCharacter(RemotePlayer entity, String set, String role,
                                 CharacterAnimations.SkinChoice skinChoice) {
        this.entity = entity;
        this.set = set;
        this.role = role;
        this.skinChoice = skinChoice;
    }

    /**
     * Creates the bot's character, or null if the set is disabled or has no BBS form
     * (the caller then keeps the armor stand). {@code role} is "player"/"opponent".
     */
    public static OpponentBotCharacter create(ClientLevel level, String set, String role, Entity stand,
                                              CharacterAnimations.SkinChoice skinChoice) {
        if (level == null || set == null || stand == null || CharacterAnimations.isDisabled(set)) return null;
        UUID id = UUID.nameUUIDFromBytes(("blockified:bot:" + role).getBytes(StandardCharsets.UTF_8));
        String name = "Bot_" + role;
        RemotePlayer player = new RemotePlayer(level, new GameProfile(id, name.length() > 16 ? name.substring(0, 16) : name));
        player.setId(NEXT_ID.getAndDecrement());
        player.noPhysics = false;
        player.setNoGravity(false);
        PerformerCollisions.setEnabled(player.getId(), false);
        player.setInvulnerable(true);
        player.setPos(stand.getX(), stand.getY(), stand.getZ());
        level.addEntity(player);
        // Apply the BBS form. If the character/animation name has no form, bail so the
        // caller falls back to the plain armor stand.
        if (!CharacterAnimations.prepare(player, set, role, skinChoice)) {
            PerformerCollisions.setEnabled(player.getId(), true);
            level.removeEntity(player.getId(), Entity.RemovalReason.DISCARDED);
            return null;
        }
        return new OpponentBotCharacter(player, set, role, skinChoice);
    }

    /** Keeps the character on the bot stand's spot and hides the stand. Call each frame. */
    public void follow(Entity stand) {
        if (stand == null) return;
        entity.setPos(stand.getX(), stand.getY(), stand.getZ());
        // The armor stand owns stage facing; the selected role's JSON adds its
        // own visual yaw. In particular, character-opp.json must not inherit the
        // player's rotation merely because both performers use the same form.
        float yaw = stand.getYRot() + CharacterAnimations.rotation(set, role);
        entity.setYRot(yaw);
        entity.setYHeadRot(yaw);
        entity.setYBodyRot(yaw);
        stand.setInvisible(true);
    }

    /** Plays a sing/miss/hey animation on the opponent bot. */
    public boolean play(String action) {
        return playWithCameraOffset(action) != null;
    }

    /** Plays an action while preserving its character.json camera nudge. */
    public float[] playWithCameraOffset(String action) {
        specialAnimation = null;
        return playStateWithCameraOffset(action, true);
    }

    /** Play Animation event variant: idle waits for the BBS state itself to finish. */
    public float[] playSpecialWithCameraOffset(String action) {
        float[] result = playStateWithCameraOffset(action, true);
        specialAnimation = result == null ? null : action;
        return result;
    }

    /** Replays a sustain sing at the same bounded cadence used by the local performer. */
    public void hold(String action, long replayMs) {
        long now = System.currentTimeMillis();
        if (now - lastStatePlayMs >= Math.max(1, replayMs)) playState(action, true);
    }

    /**
     * Beat idle with role-specific idle/idle2 alternation. A recent note animation
     * wins until its normal one-beat sing duration expires.
     */
    public void idle(int beat, double singHoldMs) {
        if (specialAnimation != null) {
            if (CharacterAnimations.isActionPlaying(entity, set, role, specialAnimation)) return;
            specialAnimation = null;
        }
        if (CharacterAnimations.loopIdle(set, role)
                || System.currentTimeMillis() - lastSingMs <= singHoldMs) return;
        boolean hasSecondIdle = CharacterAnimations.hasAction(set, role, "idle2");
        if (!hasSecondIdle && (beat & 1) == 1) return;
        String action = hasSecondIdle && (beat & 1) == 1 ? "idle2" : "idle";
        if (!playState(action, false) && !"idle".equals(action)) playState("idle", false);
    }

    private boolean playState(String action, boolean sing) {
        return playStateWithCameraOffset(action, sing) != null;
    }

    private float[] playStateWithCameraOffset(String action, boolean sing) {
        float[] cameraOffset = CharacterAnimations.play(entity, set, role, action, skinChoice);
        if (cameraOffset == null) return null;
        long now = System.currentTimeMillis();
        lastStatePlayMs = now;
        if (sing) lastSingMs = now;
        return cameraOffset;
    }

    public String role() {
        return role;
    }

    /** Reuses the existing client entity and BBS morph provider for repeated swaps. */
    public boolean changeSet(String replacement) {
        if (replacement == null || CharacterAnimations.isDisabled(replacement)) return false;
        if (!CharacterAnimations.prepare(entity, replacement, role, skinChoice)) return false;
        set = replacement;
        specialAnimation = null;
        lastSingMs = 0;
        lastStatePlayMs = 0;
        return true;
    }

    /** Client-side player carrying the visible BBS form. */
    public RemotePlayer player() {
        return entity;
    }

    /** Removes the character and un-hides the armor stand. */
    public void remove(Entity stand) {
        if (stand != null) stand.setInvisible(false);
        PerformerRotation.clear(entity);
        PerformerCollisions.setEnabled(entity.getId(), true);
        CharacterAnimations.release(entity);
        if (entity.level() instanceof ClientLevel level && level.getEntity(entity.getId()) != null) {
            level.removeEntity(entity.getId(), Entity.RemovalReason.DISCARDED);
        }
    }
}
