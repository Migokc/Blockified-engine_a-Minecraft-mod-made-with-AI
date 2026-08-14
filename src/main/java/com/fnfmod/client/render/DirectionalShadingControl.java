package com.fnfmod.client.render;

import net.minecraft.client.Minecraft;

/** Gameplay-scoped controls for Minecraft's block and entity face lighting. */
public final class DirectionalShadingControl {

    private static volatile boolean blockShading = true;
    private static volatile boolean entityShading = true;
    private DirectionalShadingControl() {}

    public static boolean blockShadingEnabled() {
        return blockShading;
    }

    public static boolean entityShadingEnabled() {
        return entityShading;
    }

    public static void setBlockShading(boolean enabled) {
        if (blockShading == enabled) return;
        blockShading = enabled;
        rebuildChunks();
    }

    public static void setEntityShading(boolean enabled) {
        entityShading = enabled;
    }

    public static boolean parseToggle(String raw, boolean fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "on", "true", "yes", "1", "enabled", "enable", "normal" -> true;
            case "off", "false", "no", "0", "disabled", "disable", "flat" -> false;
            default -> fallback;
        };
    }

    /** Restores vanilla rendering after gameplay, rebuilding chunks only when necessary. */
    public static void restore() {
        boolean rebuild = !blockShading;
        blockShading = true;
        entityShading = true;
        if (rebuild) rebuildChunks();
    }

    private static void rebuildChunks() {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (minecraft.level != null && minecraft.levelRenderer != null) {
                minecraft.levelRenderer.allChanged();
            }
        });
    }
}
