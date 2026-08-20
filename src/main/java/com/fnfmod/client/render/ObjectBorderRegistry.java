package com.fnfmod.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Client-only screen-space borders for real entities (including BBS performers and
 * entity-backed world sprites). Minecraft's existing entity-outline framebuffer
 * supplies the silhouette; the custom post pass reads the alpha encoded here as
 * an individual radius. No enlarged model geometry is rendered.
 */
public final class ObjectBorderRegistry {
    /* 255 remains vanilla's ordinary glowing marker. */
    private static final int MAX_ENCODED_PHYSICAL_SIZE = 253;

    private record Border(double logicalSize, int color) {}
    public record Capture(int color, int encodedAlpha) {}

    private static final Map<Entity, Border> BORDERS = new WeakHashMap<>();
    /** Set by shouldEntityAppearGlowing and consumed immediately by LevelRenderer#setColor. */
    private static int pendingAlpha = -1;
    private static boolean renderingCustomBorder;
    private static Entity renderingEntity;

    private ObjectBorderRegistry() {}

    public static synchronized boolean set(Entity entity, double size, int color) {
        if (entity == null) return false;
        double finite = Double.isFinite(size) ? size : 0;
        double positive = Math.max(0, finite);
        if (positive <= 0) BORDERS.remove(entity);
        else BORDERS.put(entity, new Border(positive, color & 0xFFFFFF));
        return true;
    }

    public static synchronized void clear(Entity entity) {
        if (entity != null) BORDERS.remove(entity);
    }

    public static synchronized void clearAll() {
        BORDERS.clear();
        pendingAlpha = -1;
        renderingCustomBorder = false;
        renderingEntity = null;
    }

    /** Called from Minecraft.shouldEntityAppearGlowing. */
    public static synchronized boolean prepare(Entity entity) {
        Border border = entity == null ? null : BORDERS.get(entity);
        if (border == null || border.logicalSize() <= 0) {
            pendingAlpha = -1;
            renderingCustomBorder = false;
            renderingEntity = null;
            return false;
        }
        pendingAlpha = encodedPhysicalSize(border.logicalSize());
        renderingCustomBorder = true;
        renderingEntity = entity;
        return true;
    }

    /** Ends the state opened by {@link #prepare(Entity)} after that exact entity render. */
    public static synchronized void finish(Entity entity) {
        if (entity == null || entity != renderingEntity) return;
        pendingAlpha = -1;
        renderingCustomBorder = false;
        renderingEntity = null;
    }

    /** Called from the entity team-colour getter while the outline buffer is active. */
    public static synchronized Integer color(Entity entity) {
        Border border = entity == null ? null : BORDERS.get(entity);
        return border == null ? null : border.color();
    }

    /** True while Minecraft is requesting render buffers for a selected entity. */
    public static synchronized boolean renderingCustomBorder() {
        return renderingCustomBorder;
    }

    public static synchronized boolean hasBorders() {
        return !BORDERS.isEmpty();
    }

    /** Render information used by direct renderers such as BBS's VAO models. */
    public static synchronized Capture capture(Entity entity) {
        Border border = entity == null ? null : BORDERS.get(entity);
        if (border == null || border.logicalSize() <= 0) return null;
        return new Capture(border.color(), encodedPhysicalSize(border.logicalSize()));
    }

    /** Replaces vanilla's opaque alpha only for the selected Blockified entity. */
    public static synchronized int consumeAlpha(int vanillaAlpha) {
        if (pendingAlpha < 0) return vanillaAlpha;
        int result = pendingAlpha;
        pendingAlpha = -1;
        return result;
    }

    private static int encodedPhysicalSize(double logicalSize) {
        Minecraft minecraft = Minecraft.getInstance();
        double scale = 1.0;
        if (minecraft != null && minecraft.getWindow() != null) {
            int width = Math.max(1, minecraft.getWindow().getWidth());
            int height = Math.max(1, minecraft.getWindow().getHeight());
            scale = Math.min(width / 1280.0, height / 720.0);
        }
        int physical = Math.max(1, Math.min(MAX_ENCODED_PHYSICAL_SIZE,
                (int) Math.round(logicalSize * scale)));
        // 0 means no seed and 255 is reserved for ordinary vanilla glowing.
        return physical + 1;
    }
}
