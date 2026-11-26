package com.fnfmod.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Client-only render suppression for physical performers replaced by 2D sprites. */
public final class PerformerRenderVisibility {
    private static final Set<Entity> HIDDEN = Collections.newSetFromMap(new IdentityHashMap<>());
    private static Object owner;

    private PerformerRenderVisibility() {}

    /** Replaces the previous frame's anchors, including any client-only visual proxy. */
    public static void replace(Object source, Entity... entities) {
        HIDDEN.clear();
        owner = source;
        if (entities == null) return;
        for (Entity entity : entities) {
            if (entity != null && !entity.isRemoved()) HIDDEN.add(entity);
        }
    }

    public static void clear(Object source) {
        if (owner != source) return;
        HIDDEN.clear();
        owner = null;
    }

    public static boolean hidden(Entity entity) {
        return entity != null && entity.level() == Minecraft.getInstance().level
                && HIDDEN.contains(entity);
    }
}
