package com.fnfmod.mixin;

import com.fnfmod.gameplay.PerformerShadows;
import com.fnfmod.client.render.ObjectBorderRegistry;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelReader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides the vanilla blob shadow for performers a chart or Lua script asked to
 * render without one. The shadow radius belongs to the entity renderer, which is
 * shared by every entity of that type, so skipping the draw is the only way to
 * control it per character.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityShadowMixin {

    /** Prevents one outlined entity's buffer/color state leaking into the next render. */
    @Inject(method = "render", at = @At("RETURN"))
    private void fnfmod$finishObjectBorder(Entity entity, double x, double y, double z,
                                           float yaw, float partialTick, PoseStack poseStack,
                                           MultiBufferSource buffers, int packedLight,
                                           CallbackInfo ci) {
        ObjectBorderRegistry.finish(entity);
    }

    @Inject(method = "renderShadow", at = @At("HEAD"), cancellable = true)
    private static void fnfmod$skipPerformerShadow(PoseStack poseStack, MultiBufferSource buffer,
                                                   Entity entity, float strength, float partialTick,
                                                   LevelReader level, float radius, CallbackInfo ci) {
        if (com.fnfmod.client.world.WorldImportCutscene.hideShadows()
                || (entity != null && PerformerShadows.hidden(entity.getId()))) {
            ci.cancel();
        }
    }
}
