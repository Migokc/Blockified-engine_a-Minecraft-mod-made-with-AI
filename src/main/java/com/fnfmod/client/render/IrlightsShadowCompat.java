package com.fnfmod.client.render;

import java.lang.reflect.Method;

/** Optional, dependency-free access to IRLights' shadow-bake state. */
final class IrlightsShadowCompat {
    private static final Method IS_BAKING = findIsBaking();

    private IrlightsShadowCompat() {}

    static boolean isBaking() {
        if (IS_BAKING == null) return false;
        try {
            return Boolean.TRUE.equals(IS_BAKING.invoke(null));
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return false;
        }
    }

    private static Method findIsBaking() {
        try {
            Class<?> state = Class.forName(
                    "org.qualet.irl.light.shadow.ShadowBakeState", false,
                    IrlightsShadowCompat.class.getClassLoader());
            return state.getMethod("isBaking");
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // IRLights is optional. Normal rendering needs no special handling.
            return null;
        }
    }
}
