package com.fnfmod.client.render;

import com.fnfmod.FnfMod;

import java.lang.reflect.Method;

/**
 * Optional Iris shaders integration through reflection, so Blockified never hard-depends
 * on Iris being installed. Used to temporarily turn shaders off during a transition and
 * restore them afterward.
 */
public final class IrisCompat {

    private static boolean initialized;
    private static Object config;
    private static Method areShadersEnabled;
    private static Method setShadersEnabledAndApply;

    private IrisCompat() {}

    private static synchronized void init() {
        if (initialized) return;
        initialized = true;
        try {
            Class<?> apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object api = apiClass.getMethod("getInstance").invoke(null);
            config = apiClass.getMethod("getConfig").invoke(api);
            Class<?> configClass = config.getClass();
            areShadersEnabled = findMethod(configClass, "areShadersEnabled");
            setShadersEnabledAndApply = findMethod(configClass, "setShadersEnabledAndApply", boolean.class);
        } catch (Throwable ignored) {
            config = null;
        }
    }

    /** True when Iris is installed and its API is reachable. */
    public static boolean available() {
        init();
        return config != null && areShadersEnabled != null && setShadersEnabledAndApply != null;
    }

    /** Whether a shader pack is currently enabled (false when Iris is absent). */
    public static boolean shadersEnabled() {
        if (!available()) return false;
        try {
            return Boolean.TRUE.equals(areShadersEnabled.invoke(config));
        } catch (Throwable error) {
            return false;
        }
    }

    /** Enables or disables the active shader pack, applying immediately. No-op without Iris. */
    public static void setShaders(boolean enabled) {
        if (!available()) return;
        try {
            setShadersEnabledAndApply.invoke(config, enabled);
        } catch (Throwable error) {
            FnfMod.LOGGER.warn("Could not toggle Iris shaders: {}", error.toString());
        }
    }

    private static Method findMethod(Class<?> owner, String name, Class<?>... params) {
        try {
            Method method = owner.getMethod(name, params);
            method.setAccessible(true);
            return method;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
