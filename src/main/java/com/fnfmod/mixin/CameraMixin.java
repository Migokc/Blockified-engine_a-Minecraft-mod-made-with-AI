package com.fnfmod.mixin;

import com.fnfmod.client.camera.GameplayCamera;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraMixin {

    @Shadow public abstract Vec3 getPosition();

    @Shadow public abstract Vector3f getUpVector();

    @Shadow public abstract Vector3f getLeftVector();

    @Shadow protected abstract void setPosition(double x, double y, double z);

    @Inject(method = "setup", at = @At("TAIL"))
    private void fnfmod$applyGameplayPan(BlockGetter level, Entity entity, boolean detached,
                                         boolean thirdPersonReverse, float partialTick, CallbackInfo ci) {
        Vec3 offset = GameplayCamera.worldOffset(getLeftVector(), getUpVector());
        if (offset == null) return;
        Vec3 pos = getPosition();
        setPosition(pos.x + offset.x, pos.y + offset.y, pos.z + offset.z);
    }
}
