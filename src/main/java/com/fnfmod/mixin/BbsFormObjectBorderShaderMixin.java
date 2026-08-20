package com.fnfmod.mixin;

import com.fnfmod.client.render.BbsObjectBorderRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.Coerce;

import java.util.function.Supplier;

/** Supplies the solid alpha-mask shader during Blockified's second BBS render. */
@Pseudo
@Mixin(targets = "mchorse.bbs_mod.forms.renderers.FormRenderer", remap = false)
public abstract class BbsFormObjectBorderShaderMixin {
    @Inject(method = "getShader", at = @At("HEAD"), cancellable = true, remap = false)
    private void fnfmod$objectBorderShader(@Coerce Object context,
            Supplier<ShaderInstance> normal, Supplier<ShaderInstance> picking,
            CallbackInfoReturnable<Supplier<ShaderInstance>> callback) {
        if (BbsObjectBorderRenderer.capturing() && BbsObjectBorderRenderer.shader() != null) {
            callback.setReturnValue(BbsObjectBorderRenderer::shader);
        }
    }
}
