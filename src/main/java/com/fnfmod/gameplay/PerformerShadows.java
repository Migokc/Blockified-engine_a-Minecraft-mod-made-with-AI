package com.fnfmod.gameplay;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Performers whose vanilla blob shadow is hidden during a song.
 *
 * <p>The shadow under an entity is drawn by Minecraft's entity dispatcher from
 * the renderer's shadow radius, which is per entity <em>type</em>, so it cannot
 * be turned off on a single character through normal means. Ids registered here
 * are skipped by {@code EntityShadowMixin}.
 *
 * <p>This is unrelated to a BBS form's {@code shaderShadow}, which only affects
 * the shadow pass of an installed shader pack.
 */
public final class PerformerShadows {

    private static final Set<Integer> HIDDEN = Collections.synchronizedSet(new HashSet<>());

    private PerformerShadows() {}

    /** Shadows are on by default, matching normal Minecraft rendering. */
    public static void setEnabled(int entityId, boolean enabled) {
        if (enabled) HIDDEN.remove(entityId);
        else HIDDEN.add(entityId);
    }

    public static boolean enabled(int entityId) {
        return !HIDDEN.contains(entityId);
    }

    public static boolean hidden(int entityId) {
        return !HIDDEN.isEmpty() && HIDDEN.contains(entityId);
    }

    /** Restores every performer's shadow. Called when a song ends. */
    public static void clear() {
        HIDDEN.clear();
    }
}
