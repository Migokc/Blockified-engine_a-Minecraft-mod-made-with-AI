package com.fnfmod.gameplay;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Entities whose character-to-character pushing is suppressed during a song.
 *
 * <p>Real performers are ordinary players, so their collisions cannot be turned
 * off with {@code noPhysics} the way client-side extra characters do it: that
 * flag also disables block collision and would drop the player through the
 * world. Instead the ids are registered here and {@code EntityPushMixin} skips
 * the push, leaving block physics untouched.
 *
 * <p>A push is skipped when <em>either</em> side is registered, so disabling one
 * character stops it shoving others and being shoved by them.
 */
public final class PerformerCollisions {

    private static final Set<Integer> DISABLED = Collections.synchronizedSet(new HashSet<>());

    private PerformerCollisions() {}

    public static void setEnabled(int entityId, boolean enabled) {
        if (enabled) DISABLED.remove(entityId);
        else DISABLED.add(entityId);
    }

    public static boolean enabled(int entityId) {
        return !DISABLED.contains(entityId);
    }

    /** True when a push between these two entities must not happen. */
    public static boolean blocked(int firstId, int secondId) {
        if (DISABLED.isEmpty()) return false;
        return DISABLED.contains(firstId) || DISABLED.contains(secondId);
    }

    /** Restores normal pushing for every performer. Called when a song ends. */
    public static void clear() {
        DISABLED.clear();
    }
}
