package com.fnfmod.mixin;

import com.fnfmod.client.render.DirectionalShadingControl;
import com.mojang.blaze3d.shaders.Uniform;
import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.FloatBuffer;

/**
 * Removes directional face lighting from every entity-style shader, including
 * BBS/custom model shaders which render from VAOs and never visit BufferBuilder.
 */
@Mixin(ShaderInstance.class)
public abstract class ShaderDirectionalShadingMixin {

    @Shadow public Uniform COLOR_MODULATOR;
    @Shadow public Uniform LIGHT0_DIRECTION;
    @Shadow public Uniform LIGHT1_DIRECTION;

    @Unique private boolean fnfmod$flatLightingApplied;
    @Unique private final float[] fnfmod$savedColor = new float[4];
    @Unique private final float[] fnfmod$savedLight0 = new float[3];
    @Unique private final float[] fnfmod$savedLight1 = new float[3];

    @Inject(method = "apply", at = @At("HEAD"))
    private void fnfmod$applyFlatEntityLighting(CallbackInfo callback) {
        fnfmod$flatLightingApplied = false;
        if (DirectionalShadingControl.entityShadingEnabled()
                || LIGHT0_DIRECTION == null || LIGHT1_DIRECTION == null
                || COLOR_MODULATOR == null) {
            return;
        }

        FloatBuffer color = COLOR_MODULATOR.getFloatBuffer();
        FloatBuffer light0 = LIGHT0_DIRECTION.getFloatBuffer();
        FloatBuffer light1 = LIGHT1_DIRECTION.getFloatBuffer();
        if (color == null || color.capacity() < 4
                || light0 == null || light0.capacity() < 3
                || light1 == null || light1.capacity() < 3) {
            return;
        }

        fnfmod$copy(color, fnfmod$savedColor);
        fnfmod$copy(light0, fnfmod$savedLight0);
        fnfmod$copy(light1, fnfmod$savedLight1);

        // light.glsl contributes a constant 0.4 with zero directional lights.
        // Multiplying RGB by 1 / 0.4 keeps the original texture/tint brightness.
        LIGHT0_DIRECTION.set(0.0F, 0.0F, 0.0F);
        LIGHT1_DIRECTION.set(0.0F, 0.0F, 0.0F);
        COLOR_MODULATOR.set(
                fnfmod$savedColor[0] * 2.5F,
                fnfmod$savedColor[1] * 2.5F,
                fnfmod$savedColor[2] * 2.5F,
                fnfmod$savedColor[3]
        );
        fnfmod$flatLightingApplied = true;
    }

    @Inject(method = "apply", at = @At("RETURN"))
    private void fnfmod$restoreEntityLightingUniforms(CallbackInfo callback) {
        if (!fnfmod$flatLightingApplied) return;

        COLOR_MODULATOR.set(fnfmod$savedColor);
        LIGHT0_DIRECTION.set(fnfmod$savedLight0);
        LIGHT1_DIRECTION.set(fnfmod$savedLight1);
        fnfmod$flatLightingApplied = false;
    }

    @Unique
    private static void fnfmod$copy(FloatBuffer source, float[] destination) {
        for (int i = 0; i < destination.length; i++) destination[i] = source.get(i);
    }
}
