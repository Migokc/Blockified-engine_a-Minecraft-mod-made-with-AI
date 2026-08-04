package com.fnfmod.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.PostChain;

import java.lang.reflect.Field;

/**
 * Applies Minecraft's vanilla menu background blur with a caller-supplied radius,
 * so a machine menu can enable, disable, or tween it independently of the player's
 * accessibility option. The primary path drives the blur post-chain's {@code Radius}
 * uniform directly for smooth (fractional) control; if that private field cannot be
 * reached it falls back to the public {@code processBlurEffect} driven through the
 * integer option, restoring the option afterwards so nothing persists.
 */
public final class MenuBlur {

    private static Field blurField;
    private static boolean resolved;

    private MenuBlur() {}

    /** Blurs the current framebuffer by {@code radius}. A radius below 1 draws nothing. */
    public static void render(Minecraft minecraft, float radius, float partialTick) {
        if (minecraft == null || radius < 1f) return;
        RenderSystem.disableDepthTest();
        PostChain chain = blurEffect(minecraft);
        if (chain != null) {
            chain.setUniform("Radius", radius);
            chain.process(partialTick);
        } else {
            var option = minecraft.options.menuBackgroundBlurriness();
            int original = option.get();
            try {
                option.set(Math.max(1, Math.round(radius)));
                minecraft.gameRenderer.processBlurEffect(partialTick);
            } finally {
                option.set(original);
            }
        }
        minecraft.getMainRenderTarget().bindWrite(false);
    }

    private static PostChain blurEffect(Minecraft minecraft) {
        if (!resolved) {
            resolved = true;
            try {
                blurField = GameRenderer.class.getDeclaredField("blurEffect");
                blurField.setAccessible(true);
            } catch (Throwable ignored) {
                blurField = null;
            }
        }
        if (blurField == null) return null;
        try {
            return (PostChain) blurField.get(minecraft.gameRenderer);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
