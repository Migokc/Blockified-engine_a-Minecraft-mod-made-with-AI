package com.fnfmod.mixin;

import com.fnfmod.client.render.BbsObjectBorderRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps BBS's hybrid/welded model path inside the flat outline capture shader.
 * That path otherwise replaces FormRenderer's selected shader with BBS's normal
 * model shader and writes lighting-dependent (occasionally black) pixels into
 * Minecraft's outline target.
 */
@Pseudo
@Mixin(targets = "mchorse.bbs_mod.client.BBSShaders", remap = false)
public abstract class BbsModelObjectBorderShaderMixin {
    @Inject(method = "getModel", at = @At("HEAD"), cancellable = true, remap = false)
    private static void fnfmod$objectBorderShader(CallbackInfoReturnable<ShaderInstance> callback) {
        if (BbsObjectBorderRenderer.capturing() && BbsObjectBorderRenderer.shader() != null) {
            callback.setReturnValue(BbsObjectBorderRenderer.shader());
        }
    }
}
