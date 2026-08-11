package com.fnfmod.client.render;

import com.fnfmod.FnfMod;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/** Shared black/magenta fallback for unresolved Blockified image assets. */
public final class MissingAssetTexture {

    private static final ResourceLocation ID = FnfMod.id("textures/misc/missing_asset.png");

    private MissingAssetTexture() {}

    /**
     * Returns the fallback and forces nearest-neighbour filtering whenever its
     * texture object is available. The bundled metadata also disables blur on
     * the first frame and after resource reloads.
     */
    public static ResourceLocation texture() {
        try {
            var loaded = Minecraft.getInstance().getTextureManager().getTexture(ID, null);
            if (loaded != null) loaded.setFilter(false, false);
        } catch (Throwable ignored) {
            // Rendering the fallback must never make a missing asset fatal.
        }
        return ID;
    }

    public static int width() { return 2; }

    public static int height() { return 2; }
}
