package com.fnfmod.client.gui;

import com.fnfmod.client.render.PsychResolutionController;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Optional Sodium/Iris video-settings page, discovered from neoforge.mods.toml. */
public final class BlockifiedSodiumOptions implements ConfigEntryPoint {
    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        builder.registerOwnModOptions()
                .addPage(builder.createOptionPage()
                        .setName(Component.literal("Blockified"))
                        .addOptionGroup(builder.createOptionGroup()
                                .addOption(builder.createBooleanOption(ResourceLocation.fromNamespaceAndPath(
                                                "fnfmod", "psych_resolution"))
                                        .setName(Component.literal("Psych 1280×720 canvas"))
                                        .setTooltip(Component.literal(
                                                "Keep a Psych-style 16:9 game canvas at native display resolution; "
                                                        + "areas outside it are black."))
                                        .setStorageHandler(() -> {
                                            // The binding persists the same options.json used without Sodium.
                                        })
                                        .setBinding(PsychResolutionController::setEnabled,
                                                PsychResolutionController::enabled)
                                        .setDefaultValue(false))));
    }
}
