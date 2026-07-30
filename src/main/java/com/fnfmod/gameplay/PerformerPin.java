package com.fnfmod.gameplay;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Holds a song performer at an exact position.
 *
 * <p>{@link Entity#setPos} on its own is not enough to keep an entity still.
 * It moves the current position but leaves the previous tick's, which is what
 * the entity renderer interpolates from, and it does nothing about the entity's
 * own physics. Between two calls the entity ticks, gravity moves it, and the
 * next call drags it back, so a held performer shakes between the two positions.
 *
 * <p>While something is actively moving the performer the target advances every
 * frame and hides the shaking; as soon as it stops - a finished or cancelled
 * tween - the target is constant and the shaking is all that remains.
 */
public final class PerformerPin {

    private PerformerPin() {}

    public static void pin(Entity entity, double x, double y, double z) {
        entity.setPos(x, y, z);
        // The renderer lerps from the previous tick's position, so it has to be
        // moved as well or the performer is drawn sliding back to where it was.
        entity.xOld = entity.xo = x;
        entity.yOld = entity.yo = y;
        entity.zOld = entity.zo = z;
        // Stop gravity accumulating; otherwise every tick pulls against the pin.
        entity.setDeltaMovement(Vec3.ZERO);
        // A performer held above ground would otherwise bank fall damage and take
        // it the instant the chart releases it.
        entity.fallDistance = 0;
    }
}
