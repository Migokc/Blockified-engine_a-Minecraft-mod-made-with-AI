package com.fnfmod.client.anim;

import com.fnfmod.client.math.Easing;
import com.fnfmod.gameplay.PerformerCollisions;
import com.fnfmod.gameplay.PerformerShadows;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Song-local named BBS performers. They are client-only RemotePlayers because
 * BBS FS attaches forms and animation states to Minecraft players.
 *
 * Coordinates are stage-local blocks: X is camera-right, Y is up, and Z is
 * camera-forward. No entity or form is sent to the server.
 */
public final class ExtraCharacterRoster implements AutoCloseable {
    private static final AtomicInteger NEXT_ENTITY_ID = new AtomicInteger(-1_000_000);

    private static final class Entry {
        final String tag;
        final RemotePlayer entity;
        String definition;
        String role;
        double x, y, z;
        float rotation;
        boolean visible = true;

        // Smooth position/rotation tween state (client-side, event or Lua driven).
        boolean tweening;
        long tweenStart;
        double tweenDurationMs;
        String tweenEase = "linear";
        double fromX, fromY, fromZ, fromRotation;
        double toX, toY, toZ, toRotation;
        boolean tweenRotation;

        Entry(String tag, RemotePlayer entity) {
            this.tag = tag;
            this.entity = entity;
        }
    }

    private final BlockPos machinePosition;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private ClientLevel level;

    public ExtraCharacterRoster(BlockPos machinePosition) {
        this.machinePosition = machinePosition.immutable();
    }

    public boolean create(String tag, String definition, double x, double y, double z,
                          float rotation, String animation, String role) {
        String key = key(tag);
        if (key.isEmpty()) return false;
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel current = minecraft.level;
        if (current == null) return false;
        if (level != current) {
            clear();
            level = current;
        }
        remove(key);

        UUID id = UUID.nameUUIDFromBytes(("blockified:" + machinePosition.asLong() + ":" + key)
                .getBytes(StandardCharsets.UTF_8));
        String profileName = "B_" + Integer.toUnsignedString(key.hashCode(), 36);
        if (profileName.length() > 16) profileName = profileName.substring(0, 16);
        RemotePlayer player = new RemotePlayer(current, new GameProfile(id, profileName));
        player.setId(NEXT_ENTITY_ID.getAndDecrement());
        player.noPhysics = true;
        player.setInvulnerable(true);

        Entry entry = new Entry(key, player);
        entry.definition = definition == null || definition.isBlank()
                ? CharacterAnimations.DEFAULT_SET : CharacterAnimations.runtimeSet(definition);
        entry.role = normalizeRole(role);
        entry.x = finite(x);
        entry.y = finite(y);
        entry.z = finite(z);
        entry.rotation = Float.isFinite(rotation) ? rotation : 0;
        entries.put(key, entry);
        updateTransform(entry);
        current.addEntity(player);

        startAnimation(entry, animation == null || animation.isBlank() ? "idle" : animation);
        return true;
    }

    /**
     * Starts a performer's first animation. A loopIdle definition must not have
     * its idle triggered explicitly: that turns the BBS main state into a
     * one-shot trigger which expires and leaves the model frozen. Applying the
     * form alone lets BBS run the looping main state, which is how the player
     * and partner performers are already started.
     */
    private void startAnimation(Entry entry, String animation) {
        if ("idle".equalsIgnoreCase(animation)
                && CharacterAnimations.loopIdle(entry.definition, entry.role)) {
            CharacterAnimations.prepare(entry.entity, entry.definition, entry.role);
            return;
        }
        play(entry.tag, animation);
    }

    /**
     * Returns every performer to its idle pose on the beat. loopIdle definitions
     * are skipped for the reason above; BBS keeps their idle running by itself.
     */
    public void danceAll() {
        for (Entry entry : entries.values()) {
            if (CharacterAnimations.loopIdle(entry.definition, entry.role)) continue;
            play(entry.tag, "idle");
        }
    }

    public boolean remove(String tag) {
        Entry entry = entries.remove(key(tag));
        if (entry == null) return false;
        // Drop the per-entity overrides so a recycled id cannot inherit them.
        PerformerCollisions.setEnabled(entry.entity.getId(), true);
        PerformerShadows.setEnabled(entry.entity.getId(), true);
        CharacterAnimations.release(entry.entity);
        ClientLevel owner = entry.entity.level() instanceof ClientLevel clientLevel ? clientLevel : null;
        if (owner != null && owner.getEntity(entry.entity.getId()) != null) {
            owner.removeEntity(entry.entity.getId(), Entity.RemovalReason.DISCARDED);
        }
        return true;
    }

    public boolean exists(String tag) {
        return entries.containsKey(key(tag));
    }

    public boolean play(String tag, String animation) {
        Entry entry = entries.get(key(tag));
        if (entry == null || animation == null || animation.isBlank()) return false;
        return CharacterAnimations.play(entry.entity, entry.definition, entry.role, animation) != null;
    }

    public boolean dance(String tag) {
        Entry entry = entries.get(key(tag));
        if (entry == null) return false;
        // Re-triggering a looping main state would expire it, so leave it running.
        if (CharacterAnimations.loopIdle(entry.definition, entry.role)) return true;
        return play(tag, "idle");
    }

    public boolean setPosition(String tag, double x, double y, double z) {
        Entry entry = entries.get(key(tag));
        if (entry == null) return false;
        entry.tweening = false;
        entry.x = finite(x);
        entry.y = finite(y);
        entry.z = finite(z);
        updateTransform(entry);
        return true;
    }

    /**
     * Smoothly moves a performer to a target position (and optional rotation)
     * over a duration. Null components keep the current value. Works without any
     * Lua script, so Legacy and Minecraft charts can tween through events.
     */
    public boolean tween(String tag, Double x, Double y, Double z, Double rotation,
                         double seconds, String ease) {
        Entry entry = entries.get(key(tag));
        if (entry == null) return false;
        if (seconds <= 0) {
            if (x != null || y != null || z != null) {
                setPosition(tag, x == null ? entry.x : x, y == null ? entry.y : y,
                        z == null ? entry.z : z);
            }
            if (rotation != null) setRotation(tag, rotation);
            return true;
        }
        entry.fromX = entry.x;
        entry.fromY = entry.y;
        entry.fromZ = entry.z;
        entry.fromRotation = entry.rotation;
        entry.toX = x == null ? entry.x : finite(x);
        entry.toY = y == null ? entry.y : finite(y);
        entry.toZ = z == null ? entry.z : finite(z);
        entry.tweenRotation = rotation != null;
        entry.toRotation = rotation == null || !Double.isFinite(rotation) ? entry.rotation : rotation;
        entry.tweenDurationMs = seconds * 1000.0;
        entry.tweenEase = ease == null || ease.isBlank() ? "linear" : ease;
        entry.tweenStart = System.currentTimeMillis();
        entry.tweening = true;
        return true;
    }

    /** Advances active tweens; call once per client frame while a song plays. */
    public void update() {
        if (entries.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Entry entry : entries.values()) {
            if (!entry.tweening) continue;
            double t = entry.tweenDurationMs <= 0 ? 1
                    : Math.min(1.0, (now - entry.tweenStart) / entry.tweenDurationMs);
            double f = Easing.apply(entry.tweenEase, t);
            entry.x = entry.fromX + (entry.toX - entry.fromX) * f;
            entry.y = entry.fromY + (entry.toY - entry.fromY) * f;
            entry.z = entry.fromZ + (entry.toZ - entry.fromZ) * f;
            if (entry.tweenRotation) {
                entry.rotation = (float) (entry.fromRotation
                        + (entry.toRotation - entry.fromRotation) * f);
            }
            updateTransform(entry);
            if (t >= 1.0) entry.tweening = false;
        }
    }

    public boolean setX(String tag, double value) {
        Entry entry = entries.get(key(tag));
        return entry != null && setPosition(tag, value, entry.y, entry.z);
    }

    public boolean setY(String tag, double value) {
        Entry entry = entries.get(key(tag));
        return entry != null && setPosition(tag, entry.x, value, entry.z);
    }

    public boolean setZ(String tag, double value) {
        Entry entry = entries.get(key(tag));
        return entry != null && setPosition(tag, entry.x, entry.y, value);
    }

    public boolean setRotation(String tag, double value) {
        Entry entry = entries.get(key(tag));
        if (entry == null) return false;
        entry.tweening = false;
        entry.rotation = Double.isFinite(value) ? (float) value : 0;
        updateTransform(entry);
        return true;
    }

    public boolean setVisible(String tag, boolean visible) {
        Entry entry = entries.get(key(tag));
        if (entry == null) return false;
        entry.visible = visible;
        entry.entity.setInvisible(!visible);
        return true;
    }

    public boolean setGravity(String tag, boolean enabled) {
        Entry entry = entries.get(key(tag));
        if (entry == null) return false;
        entry.entity.setNoGravity(!enabled);
        return true;
    }

    public boolean gravity(String tag) {
        Entry entry = entries.get(key(tag));
        return entry != null && !entry.entity.isNoGravity();
    }

    /**
     * Chart performers never push anything by default, because they are created
     * with {@code noPhysics}. Turning collisions on lets one act like a solid
     * body again; the registry keeps the two directions in step for the mixin.
     */
    public boolean setCollision(String tag, boolean enabled) {
        Entry entry = entries.get(key(tag));
        if (entry == null) return false;
        entry.entity.noPhysics = !enabled;
        PerformerCollisions.setEnabled(entry.entity.getId(), enabled);
        return true;
    }

    public boolean collision(String tag) {
        Entry entry = entries.get(key(tag));
        return entry != null && !entry.entity.noPhysics
                && PerformerCollisions.enabled(entry.entity.getId());
    }

    /** Applies a collision setting to every performer this roster owns. */
    public void setAllCollisions(boolean enabled) {
        for (Entry entry : entries.values()) {
            entry.entity.noPhysics = !enabled;
            PerformerCollisions.setEnabled(entry.entity.getId(), enabled);
        }
    }

    /** Shows or hides one performer's vanilla blob shadow. On by default. */
    public boolean setShadow(String tag, boolean enabled) {
        Entry entry = entries.get(key(tag));
        if (entry == null) return false;
        PerformerShadows.setEnabled(entry.entity.getId(), enabled);
        return true;
    }

    public boolean shadow(String tag) {
        Entry entry = entries.get(key(tag));
        return entry != null && PerformerShadows.enabled(entry.entity.getId());
    }

    public void setAllShadows(boolean enabled) {
        for (Entry entry : entries.values()) {
            PerformerShadows.setEnabled(entry.entity.getId(), enabled);
        }
    }

    public boolean changeDefinition(String tag, String definition, String role) {
        Entry entry = entries.get(key(tag));
        if (entry == null || definition == null || definition.isBlank()) return false;
        entry.definition = CharacterAnimations.runtimeSet(definition);
        if (role != null && !role.isBlank()) entry.role = normalizeRole(role);
        updateTransform(entry);
        startAnimation(entry, "idle");
        return true;
    }

    public double x(String tag) { Entry e = entries.get(key(tag)); return e == null ? 0 : e.x; }
    public double y(String tag) { Entry e = entries.get(key(tag)); return e == null ? 0 : e.y; }
    public double z(String tag) { Entry e = entries.get(key(tag)); return e == null ? 0 : e.z; }
    public double rotation(String tag) { Entry e = entries.get(key(tag)); return e == null ? 0 : e.rotation; }
    public boolean visible(String tag) { Entry e = entries.get(key(tag)); return e != null && e.visible; }

    private void updateTransform(Entry entry) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel current = minecraft.level;
        if (current == null) return;
        Direction facing = Direction.NORTH;
        var state = current.getBlockState(machinePosition);
        if (state.hasProperty(com.fnfmod.block.FunkinMachineBlock.FACING)) {
            facing = state.getValue(com.fnfmod.block.FunkinMachineBlock.FACING);
        }
        Direction right = facing.getCounterClockWise();
        double centerX = machinePosition.getX() + 0.5 + facing.getStepX() * 2.0;
        double centerZ = machinePosition.getZ() + 0.5 + facing.getStepZ() * 2.0;
        double worldX = centerX + right.getStepX() * entry.x + facing.getStepX() * entry.z;
        double worldY = machinePosition.getY() + entry.y;
        double worldZ = centerZ + right.getStepZ() * entry.x + facing.getStepZ() * entry.z;
        entry.entity.setPos(worldX, worldY, worldZ);
        float yaw = facing.toYRot()
                + CharacterAnimations.rotation(entry.definition, entry.role) + entry.rotation;
        entry.entity.setYRot(yaw);
        entry.entity.setYHeadRot(yaw);
        entry.entity.setYBodyRot(yaw);
        entry.entity.setInvisible(!entry.visible);
    }

    public void clear() {
        for (String tag : entries.keySet().toArray(String[]::new)) remove(tag);
        level = null;
    }

    @Override
    public void close() {
        clear();
    }

    private static String normalizeRole(String role) {
        return role != null && (role.equalsIgnoreCase("opponent") || role.equalsIgnoreCase("dad"))
                ? "opponent" : "player";
    }

    private static String key(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static double finite(double value) {
        return Double.isFinite(value) ? value : 0;
    }
}
