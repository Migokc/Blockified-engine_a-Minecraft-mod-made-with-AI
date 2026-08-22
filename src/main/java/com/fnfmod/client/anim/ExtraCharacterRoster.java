package com.fnfmod.client.anim;

import com.fnfmod.FnfMod;
import com.fnfmod.gameplay.GameplayClock;
import com.fnfmod.client.gameplay.WorldCharacter;
import com.fnfmod.client.math.Easing;
import com.fnfmod.client.render.LuaWorldObject;
import com.fnfmod.client.render.LuaWorldObjectRenderer;
import com.fnfmod.client.render.PerformerRotation;
import com.fnfmod.client.render.ObjectBorderRegistry;
import com.fnfmod.client.render.SparrowAtlas;
import com.fnfmod.client.render.WorldSpriteEntityVisuals;
import com.fnfmod.entity.WorldSpriteEntity;
import com.fnfmod.gameplay.PerformerCollisions;
import com.fnfmod.gameplay.PerformerPin;
import com.fnfmod.gameplay.PerformerShadows;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

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
        final RemotePlayer entity;   // null for a 2D character (rendered as a world sprite)
        WorldSpriteEntity spriteEntity;
        WorldCharacter world2d;      // set for a 2D Psych character
        String definition;
        String role;
        String idleSuffix = "";
        double x, y, z;
        float rotation;
        /** Local pitch/yaw; rotation is compatibility yaw for BBS and roll for 2D. */
        double rotationX, rotationY;
        /** BBS-only roll; 2D characters continue using rotation as their roll. */
        double rotationZ;
        /** Render-only scale for BBS forms; 2D characters keep their WorldCharacter scale. */
        double scaleX = 1, scaleY = 1, scaleZ = 1;
        boolean visible = true;
        /** Projected IRLights shadows for 2D entity-hosted characters. */
        boolean irlightsShadows = true;
        double entityX = Double.NaN, entityY = Double.NaN, entityZ = Double.NaN;

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

    /** Read-only editor view of one song-local character. */
    public record EditableCharacter(
            String tag, String definition, String role, boolean character2D,
            double x, double y, double z,
            double rotationX, double rotationY, double rotationZ, boolean visible,
            double width, double height, double scaleX, double scaleY, double scaleZ,
            double alpha, int color, boolean billboard, boolean lighting,
            boolean seeThrough, boolean antialiasing, String animation,
            List<String> animations) {}

    private final BlockPos machinePosition;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private ClientLevel level;
    /** Resolves a definition name to a Psych character JSON (2D); null keeps the BBS path. */
    private Function<String, Path> characterResolver;
    private long lastUpdateNano;

    public ExtraCharacterRoster(BlockPos machinePosition) {
        this.machinePosition = machinePosition.immutable();
    }

    /** Supplies the Psych character lookup so a 2D definition renders as a world sprite. */
    public void setCharacterResolver(Function<String, Path> resolver) {
        this.characterResolver = resolver;
    }

    private WorldCharacter load2D(String definition) {
        if (characterResolver == null || definition == null || definition.isBlank()) return null;
        try {
            Path json = characterResolver.apply(definition);
            return json == null ? null : WorldCharacter.load(json);
        } catch (Throwable ignored) {
            return null;
        }
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

        // A definition that resolves to a Psych character JSON becomes a real 2D character
        // rendered as a world sprite; anything else stays a BBS 3D performer.
        WorldCharacter world2d = load2D(definition);
        if (world2d != null) {
            Entry entry = new Entry(key, null);
            entry.world2d = world2d;
            entry.definition = definition;
            entry.role = normalizeRole(role);
            entry.x = finite(x);
            entry.y = finite(y);
            entry.z = finite(z);
            entry.rotation = Float.isFinite(rotation) ? rotation : 0;
            entries.put(key, entry);
            spawn2DEntity(entry, current);
            world2d.play(animation == null || animation.isBlank() ? "idle" : animation, true);
            return true;
        }

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
    public void danceAll(int beat) {
        danceAll(beat, null, 1);
    }

    /** Gives one named 3D replacement (normally GF) its own dance interval. */
    public void danceAll(int beat, String specialTag, int specialSpeed) {
        String special = key(specialTag);
        for (Entry entry : entries.values()) {
            if (entry.world2d != null) continue;   // 2D characters dance via beat()
            if (CharacterAnimations.loopIdle(entry.definition, entry.role)) continue;
            if (entry.tag.equals(special) && beat % Math.max(1, specialSpeed) != 0) continue;
            String suffix = entry.idleSuffix;
            String first = "idle" + suffix;
            String secondName = "idle2" + suffix;
            boolean second = CharacterAnimations.hasAction(entry.definition, entry.role, secondName)
                    && (beat & 1) == 1;
            if (!play(entry.tag, second ? secondName : first)) {
                if (second && !play(entry.tag, first)) play(entry.tag, "idle");
                else if (!second && !suffix.isEmpty()) play(entry.tag, "idle");
            }
        }
    }

    /** Beat-synced dance honouring each 2D character's dance_every. */
    public void beat(int beat, int speed) {
        beat(beat, speed, null, speed);
    }

    /** Gives one named replacement (normally GF) its own dance-speed multiplier. */
    public void beat(int beat, int speed, String specialTag, int specialSpeed) {
        String special = key(specialTag);
        for (Entry entry : entries.values()) {
            if (entry.world2d != null) entry.world2d.beat(beat,
                    entry.tag.equals(special) ? specialSpeed : speed);
        }
    }

    public boolean remove(String tag) {
        Entry entry = entries.remove(key(tag));
        if (entry == null) return false;
        if (entry.world2d != null) {
            remove2DEntity(entry);
            entry.world2d.close();
            return true;
        }
        // Drop the per-entity overrides so a recycled id cannot inherit them.
        PerformerRotation.clear(entry.entity);
        ObjectBorderRegistry.clear(entry.entity);
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

    /** Applies a Psych-canvas screen-space outline to either a BBS or 2D extra. */
    public boolean setBorder(String tag, double size, int color) {
        Entry entry = entries.get(key(tag));
        return entry != null && ObjectBorderRegistry.set(physicsEntity(entry), size, color);
    }

    /** Stable snapshots used by free cam; callers never receive mutable roster entries. */
    public List<EditableCharacter> editableCharacters() {
        if (entries.isEmpty()) return List.of();
        List<EditableCharacter> result = new ArrayList<>(entries.size());
        for (Entry entry : entries.values()) {
            WorldCharacter wc = entry.world2d;
            result.add(new EditableCharacter(
                    entry.tag, entry.definition, entry.role, wc != null,
                    entry.x, entry.y, entry.z,
                    entry.rotationX,
                    wc == null ? entry.rotation : entry.rotationY,
                    wc == null ? entry.rotationZ : entry.rotation,
                    entry.visible,
                    wc == null ? 48 : wc.refW(), wc == null ? 96 : wc.refH(),
                    wc == null ? entry.scaleX : wc.scaleX(),
                    wc == null ? entry.scaleY : wc.scaleY(),
                    wc == null ? entry.scaleZ : 1,
                    wc == null ? 1 : wc.alpha(), wc == null ? 0xFFFFFF : wc.color(),
                    wc == null || wc.billboard(), wc == null || wc.lighting(),
                    wc != null && wc.seeThrough(), wc == null || wc.antialiasing(),
                    wc == null ? "idle" : wc.current(),
                    wc == null ? List.of() : List.copyOf(wc.animationNames())));
        }
        return List.copyOf(result);
    }

    /** Applies free-cam values to the same roster entry without recreating it each frame. */
    public boolean applyCharacterEdit(EditableCharacter edit) {
        if (edit == null) return false;
        Entry entry = entries.get(key(edit.tag()));
        if (entry == null) return false;
        if (edit.definition() != null && !edit.definition().isBlank()
                && !edit.definition().equals(entry.definition)) {
            if (!changeDefinition(entry.tag, edit.definition(), entry.role)) return false;
            entry = entries.get(key(edit.tag()));
            if (entry == null) return false;
        }
        setPosition(entry.tag, edit.x(), edit.y(), edit.z());
        setVisible(entry.tag, edit.visible());
        if (entry.world2d != null) {
            entry.tweening = false;
            entry.rotationX = finite(edit.rotationX());
            entry.rotationY = finite(edit.rotationY());
            entry.rotation = (float) finite(edit.rotationZ());
            setScaleX(entry.tag, edit.scaleX());
            setScaleY(entry.tag, edit.scaleY());
            setAlpha(entry.tag, edit.alpha());
            setColor(entry.tag, edit.color());
            setBillboard(entry.tag, edit.billboard());
            setLighting(entry.tag, edit.lighting());
            setSeeThrough(entry.tag, edit.seeThrough());
            setAntialiasing(entry.tag, edit.antialiasing());
            if (edit.animation() != null && !edit.animation().isBlank()
                    && !edit.animation().equals(entry.world2d.current())) {
                entry.world2d.play(edit.animation(), true);
            }
        } else {
            // Keep legacy yaw on the entity; pitch and roll are BBS render offsets.
            setRotationX(entry.tag, edit.rotationX());
            setRotation(entry.tag, edit.rotationY());
            setRotationZ(entry.tag, edit.rotationZ());
            setScaleX(entry.tag, edit.scaleX());
            setScaleY(entry.tag, edit.scaleY());
            setScaleZ(entry.tag, edit.scaleZ());
        }
        return true;
    }

    public boolean play(String tag, String animation) {
        Entry entry = entries.get(key(tag));
        if (entry == null || animation == null || animation.isBlank()) return false;
        if (entry.world2d != null) return entry.world2d.play(animation, true);
        return CharacterAnimations.play(entry.entity, entry.definition, entry.role, animation) != null;
    }

    public boolean dance(String tag) {
        Entry entry = entries.get(key(tag));
        if (entry == null) return false;
        if (entry.world2d != null) { entry.world2d.dance(true); return true; }
        // Re-triggering a looping main state would expire it, so leave it running.
        if (CharacterAnimations.loopIdle(entry.definition, entry.role)) return true;
        return play(tag, "idle");
    }

    /** Plays a chart-note direction using the naming convention of the entry's renderer. */
    public boolean sing(String tag, String direction, boolean miss, String suffix) {
        Entry entry = entries.get(key(tag));
        if (entry == null) return false;
        String safeDirection = direction == null ? "" : direction.trim();
        String safeSuffix = suffix == null ? "" : suffix;
        if (entry.world2d != null) {
            String name = "sing" + safeDirection.toUpperCase(Locale.ROOT)
                    + (miss ? "miss" : "") + safeSuffix;
            return entry.world2d.play(name, true);
        }
        return play(entry.tag, miss ? "miss" + safeSuffix
                : safeDirection.toLowerCase(Locale.ROOT) + safeSuffix);
    }

    /** GF-style Hey support, including the timed Psych 2D special-animation path. */
    public boolean hey(String tag, double seconds) {
        Entry entry = entries.get(key(tag));
        if (entry == null) return false;
        if (entry.world2d != null) {
            entry.world2d.playHey(seconds);
            return true;
        }
        return play(entry.tag, "hey") || play(entry.tag, "cheer");
    }

    /** Applies Psych's Alt Idle Animation suffix to a 2D or BBS extra. */
    public boolean setIdleSuffix(String tag, String suffix) {
        Entry entry = entries.get(key(tag));
        if (entry == null) return false;
        entry.idleSuffix = suffix == null ? "" : suffix;
        if (entry.world2d != null) entry.world2d.setIdleSuffix(entry.idleSuffix);
        return true;
    }

    public boolean setPosition(String tag, double x, double y, double z) {
        Entry entry = entries.get(key(tag));
        if (entry == null) return false;
        entry.tweening = false;
        entry.x = finite(x);
        entry.y = finite(y);
        entry.z = finite(z);
        updateTransform(entry);
        if (entry.spriteEntity != null) update2DTransform(entry, true);
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
        entry.tweenStart = GameplayClock.now();
        entry.tweening = true;
        return true;
    }

    public void update() { update(150); }

    /** Advances active tweens and 2D-character animations; call once per client frame. */
    public void update(double stepMs) { update(stepMs, 1.0, Double.NaN); }

    /** Playback-scaled variant used while an editor playtest fast-forwards. */
    public void update(double stepMs, double playbackRate) {
        update(stepMs, playbackRate, Double.NaN);
    }

    /** Explicit elapsed time lets rapid editor simulation advance without wall-clock waiting. */
    public void update(double stepMs, double playbackRate, double simulatedSeconds) {
        if (entries.isEmpty()) return;
        long nowNano = System.nanoTime();
        double dt = Double.isFinite(simulatedSeconds) ? Math.max(0, simulatedSeconds)
                : lastUpdateNano == 0 ? 0 : Math.min(0.1, (nowNano - lastUpdateNano) / 1.0e9);
        lastUpdateNano = nowNano;
        long now = GameplayClock.now();
        for (Entry entry : entries.values()) {
            if (entry.tweening) {
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
                if (entry.entity != null) updateTransform(entry);
                if (entry.spriteEntity != null) update2DTransform(entry, true);
                if (t >= 1.0) entry.tweening = false;
            } else if (entry.entity != null) {
                if (entry.entity.isNoGravity()) updateTransform(entry);
                else updateFrom3DEntity(entry);
            } else if (entry.spriteEntity != null) {
                update2DTransform(entry, false);
            }
            if (entry.world2d != null) entry.world2d.update(dt, stepMs, playbackRate);
        }
    }

    /** Gameplay 2D characters now render through their entity hosts. */
    public void render2D(PoseStack poseStack, Camera camera, BlockPos speakers, Direction facing) {
        // Free-cam objects use FreeCamObjects and retain the direct preview renderer.
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

    public boolean setRotationX(String tag, double value) {
        Entry entry = entries.get(key(tag));
        if (entry == null) return false;
        entry.rotationX = finite(value);
        updateTransform(entry);
        return true;
    }

    public boolean setRotationY(String tag, double value) {
        Entry entry = entries.get(key(tag));
        if (entry == null) return false;
        if (entry.world2d == null) return setRotation(tag, value);
        entry.rotationY = finite(value);
        return true;
    }

    public boolean setRotationZ(String tag, double value) {
        Entry entry = entries.get(key(tag));
        if (entry == null) return false;
        if (entry.world2d != null) entry.rotation = (float) finite(value);
        else entry.rotationZ = finite(value);
        updateTransform(entry);
        return true;
    }

    public boolean setVisible(String tag, boolean visible) {
        Entry entry = entries.get(key(tag));
        if (entry == null) return false;
        entry.visible = visible;
        if (entry.entity != null) entry.entity.setInvisible(!visible);
        return true;
    }

    public boolean setGravity(String tag, boolean enabled) {
        Entry entry = entries.get(key(tag));
        Entity physics = physicsEntity(entry);
        if (physics == null) return false;
        physics.setNoGravity(!enabled);
        return true;
    }

    public boolean gravity(String tag) {
        Entry entry = entries.get(key(tag));
        Entity physics = physicsEntity(entry);
        return physics != null && !physics.isNoGravity();
    }

    /**
     * Chart performers never push anything by default, because they are created
     * with {@code noPhysics}. Turning collisions on lets one act like a solid
     * body again; the registry keeps the two directions in step for the mixin.
     */
    public boolean setCollision(String tag, boolean enabled) {
        Entry entry = entries.get(key(tag));
        Entity physics = physicsEntity(entry);
        if (physics == null) return false;
        physics.noPhysics = !enabled;
        PerformerCollisions.setEnabled(physics.getId(), enabled);
        return true;
    }

    public boolean collision(String tag) {
        Entry entry = entries.get(key(tag));
        Entity physics = physicsEntity(entry);
        return physics != null && !physics.noPhysics
                && PerformerCollisions.enabled(physics.getId());
    }

    /** Applies a collision setting to every performer this roster owns. */
    public void setAllCollisions(boolean enabled) {
        for (Entry entry : entries.values()) {
            Entity physics = physicsEntity(entry);
            if (physics == null) continue;
            physics.noPhysics = !enabled;
            PerformerCollisions.setEnabled(physics.getId(), enabled);
        }
    }

    /** Shows or hides one performer's vanilla blob shadow (2D hosts default off). */
    public boolean setShadow(String tag, boolean enabled) {
        Entry entry = entries.get(key(tag));
        Entity physics = physicsEntity(entry);
        if (physics == null) return false;
        PerformerShadows.setEnabled(physics.getId(), enabled);
        return true;
    }

    public boolean shadow(String tag) {
        Entry entry = entries.get(key(tag));
        Entity physics = physicsEntity(entry);
        return physics != null && PerformerShadows.enabled(physics.getId());
    }

    public void setAllShadows(boolean enabled) {
        for (Entry entry : entries.values()) {
            Entity physics = physicsEntity(entry);
            if (physics != null) PerformerShadows.setEnabled(physics.getId(), enabled);
        }
    }

    /** Enables projected IRLights shadows for a 2D world character. */
    public boolean setIrlightsShadows(String tag, boolean enabled) {
        Entry entry = entries.get(key(tag));
        if (entry == null || entry.world2d == null) return false;
        entry.irlightsShadows = enabled;
        return true;
    }

    public boolean irlightsShadows(String tag) {
        Entry entry = entries.get(key(tag));
        return entry != null && entry.world2d != null && entry.irlightsShadows;
    }

    public boolean changeDefinition(String tag, String definition, String role) {
        Entry entry = entries.get(key(tag));
        if (entry == null || definition == null || definition.isBlank()) return false;
        if (entry.world2d != null) {
            WorldCharacter reloaded = load2D(definition);
            if (reloaded == null) return false;
            entry.world2d.close();
            entry.world2d = reloaded;
            entry.definition = definition;
            reloaded.play("idle", true);
            return true;
        }
        entry.definition = CharacterAnimations.runtimeSet(definition);
        if (role != null && !role.isBlank()) entry.role = normalizeRole(role);
        updateTransform(entry);
        startAnimation(entry, "idle");
        return true;
    }

    // --- Character visual properties; XYZ scale is also supported by BBS forms. ---

    public boolean is2D(String tag) { Entry e = entries.get(key(tag)); return e != null && e.world2d != null; }

    public boolean setAlpha(String tag, double v) {
        Entry e = entries.get(key(tag));
        if (e == null || e.world2d == null) return false;
        e.world2d.setAlpha(v); return true;
    }

    public boolean setColor(String tag, int rgb) {
        Entry e = entries.get(key(tag));
        if (e == null || e.world2d == null) return false;
        e.world2d.setColor(rgb); return true;
    }

    public boolean setScaleX(String tag, double v) {
        Entry e = entries.get(key(tag));
        if (e == null) return false;
        if (e.world2d != null) e.world2d.setScaleX(v);
        else { e.scaleX = finiteOr(v, 1); updateTransform(e); }
        return true;
    }

    public boolean setScaleY(String tag, double v) {
        Entry e = entries.get(key(tag));
        if (e == null) return false;
        if (e.world2d != null) e.world2d.setScaleY(v);
        else { e.scaleY = finiteOr(v, 1); updateTransform(e); }
        return true;
    }

    public boolean setScaleZ(String tag, double v) {
        Entry e = entries.get(key(tag));
        if (e == null || e.world2d != null) return false;
        e.scaleZ = finiteOr(v, 1);
        updateTransform(e);
        return true;
    }

    public boolean setFlipX(String tag, boolean v) {
        Entry e = entries.get(key(tag));
        if (e == null || e.world2d == null) return false;
        e.world2d.setFlipX(v); return true;
    }

    public boolean setBillboard(String tag, boolean v) {
        Entry e = entries.get(key(tag));
        if (e == null || e.world2d == null) return false;
        e.world2d.setBillboard(v); return true;
    }

    public boolean setLighting(String tag, boolean v) {
        Entry e = entries.get(key(tag));
        if (e == null || e.world2d == null) return false;
        e.world2d.setLighting(v); return true;
    }

    public boolean setSeeThrough(String tag, boolean v) {
        Entry e = entries.get(key(tag));
        if (e == null || e.world2d == null) return false;
        e.world2d.setSeeThrough(v); return true;
    }

    public boolean setAntialiasing(String tag, boolean v) {
        Entry e = entries.get(key(tag));
        if (e == null || e.world2d == null) return false;
        e.world2d.setAntialiasing(v); return true;
    }

    public boolean billboard(String tag) { Entry e = entries.get(key(tag)); return e == null || e.world2d == null || e.world2d.billboard(); }
    public boolean lighting(String tag) { Entry e = entries.get(key(tag)); return e == null || e.world2d == null || e.world2d.lighting(); }
    public boolean seeThrough(String tag) { Entry e = entries.get(key(tag)); return e != null && e.world2d != null && e.world2d.seeThrough(); }
    public boolean antialiasing(String tag) { Entry e = entries.get(key(tag)); return e == null || e.world2d == null || e.world2d.antialiasing(); }

    public double alpha(String tag) { Entry e = entries.get(key(tag)); return e != null && e.world2d != null ? e.world2d.alpha() : 1; }
    public int color(String tag) { Entry e = entries.get(key(tag)); return e != null && e.world2d != null ? e.world2d.color() : 0xFFFFFF; }
    public double scaleX(String tag) { Entry e = entries.get(key(tag)); return e == null ? 1 : e.world2d != null ? e.world2d.scaleX() : e.scaleX; }
    public double scaleY(String tag) { Entry e = entries.get(key(tag)); return e == null ? 1 : e.world2d != null ? e.world2d.scaleY() : e.scaleY; }
    public double scaleZ(String tag) { Entry e = entries.get(key(tag)); return e == null || e.world2d != null ? 1 : e.scaleZ; }
    public boolean flipX(String tag) { Entry e = entries.get(key(tag)); return e != null && e.world2d != null && e.world2d.flipX(); }

    public double x(String tag) { Entry e = entries.get(key(tag)); return e == null ? 0 : e.x; }
    public double y(String tag) { Entry e = entries.get(key(tag)); return e == null ? 0 : e.y; }
    public double z(String tag) { Entry e = entries.get(key(tag)); return e == null ? 0 : e.z; }
    public double rotation(String tag) { Entry e = entries.get(key(tag)); return e == null ? 0 : e.rotation; }
    public double rotationX(String tag) { Entry e = entries.get(key(tag)); return e == null ? 0 : e.rotationX; }
    public double rotationY(String tag) {
        Entry e = entries.get(key(tag));
        return e == null ? 0 : e.world2d == null ? e.rotation : e.rotationY;
    }
    public double rotationZ(String tag) {
        Entry e = entries.get(key(tag));
        return e == null ? 0 : e.world2d == null ? e.rotationZ : e.rotation;
    }
    public boolean visible(String tag) { Entry e = entries.get(key(tag)); return e != null && e.visible; }

    /** Actual rendered origin used by Camera Focus for both BBS and 2D performers. */
    public Vec3 focusWorldPosition(String tag) {
        Entry entry = entries.get(key(tag));
        if (entry == null) return null;
        if (entry.entity != null) return entry.entity.position().add(0, 1.0, 0);
        if (entry.spriteEntity != null) return entry.spriteEntity.position();
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel current = minecraft.level;
        if (current == null) return null;
        Direction facing = com.fnfmod.client.gameplay.StageOrientation.facingOr(current, machinePosition);
        Direction right = facing.getCounterClockWise();
        return Vec3.atCenterOf(machinePosition).add(
                right.getStepX() * entry.x + facing.getStepX() * entry.z,
                entry.y,
                right.getStepZ() * entry.x + facing.getStepZ() * entry.z);
    }

    private static Entity physicsEntity(Entry entry) {
        if (entry == null) return null;
        return entry.spriteEntity != null ? entry.spriteEntity : entry.entity;
    }

    private void spawn2DEntity(Entry entry, ClientLevel current) {
        WorldSpriteEntity entity = new WorldSpriteEntity(FnfMod.WORLD_SPRITE_ENTITY.get(), current);
        entity.setId(WorldSpriteEntity.allocateClientId());
        entry.spriteEntity = entity;
        entry.entityX = entry.entityY = entry.entityZ = Double.NaN;
        entity.setNoGravity(true);
        entity.noPhysics = true;
        WorldSpriteEntityVisuals.bind(entity, () -> {
            LuaWorldObject.Sprite sprite = snapshot2D(entry);
            if (sprite == null) return null;
            Direction facing = com.fnfmod.client.gameplay.StageOrientation.facingOr(
                    Minecraft.getInstance().level, machinePosition);
            return new WorldSpriteEntityVisuals.Visual(sprite, facing, entry.irlightsShadows);
        });
        current.addEntity(entity);
        // Unlike BBS player models, flat sprites do not show Minecraft's blob by default.
        PerformerShadows.setEnabled(entity.getId(), false);
        update2DTransform(entry, true);
    }

    private void remove2DEntity(Entry entry) {
        WorldSpriteEntity entity = entry.spriteEntity;
        if (entity == null) return;
        WorldSpriteEntityVisuals.unbind(entity);
        ObjectBorderRegistry.clear(entity);
        PerformerCollisions.setEnabled(entity.getId(), true);
        PerformerShadows.setEnabled(entity.getId(), true);
        if (entity.level() instanceof ClientLevel owner && owner.getEntity(entity.getId()) != null) {
            owner.removeEntity(entity.getId(), Entity.RemovalReason.DISCARDED);
        }
        entry.spriteEntity = null;
    }

    private LuaWorldObject.Sprite snapshot2D(Entry entry) {
        if (entry.world2d == null || !entry.visible) return null;
        WorldCharacter wc = entry.world2d;
        SparrowAtlas.Frame frame = wc.currentFrame();
        if (frame == null) return null;
        LuaWorldObject.Frame renderFrame = new LuaWorldObject.Frame(
                frame.x, frame.y, frame.w, frame.h, frame.frameX, frame.frameY, frame.rotated);
        double[] offset = wc.currentOffset();
        double sx = wc.scaleX() * (wc.flipX() ? -1 : 1);
        return new LuaWorldObject.Sprite(
                wc.texture(), wc.texWidth(), wc.texHeight(), renderFrame, offset[0], offset[1],
                0, 0, 0, wc.refW(), wc.refH(), wc.refW(), wc.refH(),
                sx, wc.scaleY(), wc.alpha(), entry.rotation,
                entry.rotationX, entry.rotationY,
                wc.color(), wc.billboard(), wc.lighting(), wc.seeThrough());
    }

    private void update2DTransform(Entry entry, boolean authored) {
        if (entry.spriteEntity == null || entry.world2d == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.level instanceof ClientLevel current)) return;
        WorldSpriteEntity entity = entry.spriteEntity;
        Direction facing = com.fnfmod.client.gameplay.StageOrientation.facingOr(current, machinePosition);
        Direction right = facing.getCounterClockWise();
        entity.setVisualBounds(entry.world2d.refW() * Math.abs(entry.world2d.scaleX())
                        * LuaWorldObjectRenderer.PIXEL_SCALE,
                entry.world2d.refH() * Math.abs(entry.world2d.scaleY())
                        * LuaWorldObjectRenderer.PIXEL_SCALE);

        boolean changed = authored || different(entry.x, entry.entityX)
                || different(entry.y, entry.entityY) || different(entry.z, entry.entityZ);
        if (changed || entity.isNoGravity()) {
            Vec3 origin = Vec3.atCenterOf(machinePosition);
            PerformerPin.pin(entity,
                    origin.x + right.getStepX() * entry.x + facing.getStepX() * entry.z,
                    origin.y + entry.y,
                    origin.z + right.getStepZ() * entry.x + facing.getStepZ() * entry.z);
        } else {
            Vec3 delta = entity.position().subtract(Vec3.atCenterOf(machinePosition));
            entry.x = delta.x * right.getStepX() + delta.z * right.getStepZ();
            entry.y = delta.y;
            entry.z = delta.x * facing.getStepX() + delta.z * facing.getStepZ();
        }
        entry.entityX = entry.x; entry.entityY = entry.y; entry.entityZ = entry.z;
    }

    private void updateFrom3DEntity(Entry entry) {
        if (entry.entity == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.level instanceof ClientLevel current)) return;
        Direction facing = com.fnfmod.client.gameplay.StageOrientation.facingOr(current, machinePosition);
        Direction right = facing.getCounterClockWise();
        Vec3 origin = new Vec3(machinePosition.getX() + 0.5 + facing.getStepX() * 2.0,
                machinePosition.getY(), machinePosition.getZ() + 0.5 + facing.getStepZ() * 2.0);
        Vec3 delta = entry.entity.position().subtract(origin);
        entry.x = delta.x * right.getStepX() + delta.z * right.getStepZ();
        entry.y = delta.y;
        entry.z = delta.x * facing.getStepX() + delta.z * facing.getStepZ();
    }

    private static boolean different(double a, double b) {
        return !Double.isFinite(b) || Math.abs(a - b) > 1.0e-7;
    }

    private void updateTransform(Entry entry) {
        if (entry.entity == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel current = minecraft.level;
        if (current == null) return;
        // Cached stage facing: re-reading the block would rotate an extra
        // character's axes if a tween carried it past the machine's view distance.
        Direction facing = com.fnfmod.client.gameplay.StageOrientation.facingOr(current, machinePosition);
        Direction right = facing.getCounterClockWise();
        double centerX = machinePosition.getX() + 0.5 + facing.getStepX() * 2.0;
        double centerZ = machinePosition.getZ() + 0.5 + facing.getStepZ() * 2.0;
        double worldX = centerX + right.getStepX() * entry.x + facing.getStepX() * entry.z;
        double worldY = machinePosition.getY() + entry.y;
        double worldZ = centerZ + right.getStepZ() * entry.x + facing.getStepZ() * entry.z;
        PerformerPin.pin(entry.entity, worldX, worldY, worldZ);
        float yaw = facing.toYRot()
                + CharacterAnimations.rotation(entry.definition, entry.role) + entry.rotation;
        entry.entity.setYRot(yaw);
        entry.entity.setYHeadRot(yaw);
        entry.entity.setYBodyRot(yaw);
        PerformerRotation.set(entry.entity, entry.rotationX, entry.rotationZ,
                entry.scaleX, entry.scaleY, entry.scaleZ);
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

    private static double finiteOr(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }
}
