package com.fnfmod.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.texture.DynamicTexture;

/** Small render-texture helpers shared across the note/icon renderers. */
final class Textures {

    private Textures() {}

    /**
     * Switches a texture to linear (bilinear) filtering so it scales smoothly
     * instead of showing hard pixels — i.e. antialiased notes/icons. Applied to
     * custom skin atlases and health icons, never the built-in pixel arrows.
     * The filter is a persistent texture parameter, so it survives later uploads.
     */
    static void smooth(DynamicTexture tex) {
        if (tex == null) return;
        if (!RenderSystem.isOnRenderThread()) {
            RenderSystem.recordRenderCall(() -> smooth(tex));
            return;
        }
        try {
            tex.setFilter(true, false);
        } catch (Throwable ignored) {
            // never let a filtering hiccup break rendering
        }
    }
}
