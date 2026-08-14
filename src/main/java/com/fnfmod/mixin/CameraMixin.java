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
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraMixin {

    @Shadow public abstract Vec3 getPosition();

    @Shadow public abstract Vector3f getUpVector();

    @Shadow public abstract Vector3f getLeftVector();

    @Shadow public abstract Vector3f getLookVector();

    @Shadow public abstract float getXRot();

    @Shadow public abstract float getYRot();

    @Shadow public abstract float getRoll();

    @Shadow protected abstract void setPosition(double x, double y, double z);

    @Shadow protected abstract void setRotation(float yaw, float pitch, float roll);

    /**
     * The character may have a custom body rotation, but the gameplay camera
     * must retain the stage's original viewing direction.
     */
    @Redirect(method = "setup", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;getViewYRot(F)F"))
    private float fnfmod$fixedGameplayYaw(Entity entity, float partialTick) {
        return GameplayCamera.isActive() ? GameplayCamera.stageViewYaw() : entity.getViewYRot(partialTick);
    }

    @Inject(method = "setup", at = @At("TAIL"))
    private void fnfmod$applyGameplayPan(BlockGetter level, Entity entity, boolean detached,
                                         boolean thirdPersonReverse, float partialTick, CallbackInfo ci) {
        Vec3 rotation = GameplayCamera.rotationOffset();
        // Snapshot the stage-facing basis before Camera Rotation 3D tilts it, so
        // Camera Follow Pos can keep its offsets aligned to the machine facing.
        Vector3f stageLeft = new Vector3f(getLeftVector());
        Vector3f stageUp = new Vector3f(getUpVector());
        Vector3f stageLook = new Vector3f(getLookVector());
        GameplayCamera.OrbitPose orbit = GameplayCamera.orbitPose();
        if (orbit != null) {
            setRotation(orbit.yaw(), orbit.pitch(), orbit.roll());
            Vec3 pos = orbit.position();
            setPosition(pos.x, pos.y, pos.z);
            return;
        }
        if (!rotation.equals(Vec3.ZERO)) {
            setRotation(getYRot() + (float) rotation.y,
                    getXRot() + (float) rotation.x,
                    getRoll() + (float) rotation.z);
        }

        // Free-cam X/Y/Z already describe the camera's absolute world position
        // relative to the stage anchor. Do not add that position to Minecraft's
        // detached third-person offset; doing so shifts view-selected away from
        // the object's origin even when the look direction is mathematically exact.
        if (GameplayCamera.isFreeCamEngaged() && GameplayCamera.isFreeCamInitialized()) {
            Vec3 pos = GameplayCamera.freeCamWorldPos();
            setPosition(pos.x, pos.y, pos.z);
            return;
        }

        Vec3 offset = GameplayCamera.worldOffset(getLeftVector(), getUpVector(), getLookVector(),
                stageLeft, stageUp, stageLook);
        if (offset == null) return;
        Vec3 pos = getPosition();
        setPosition(pos.x + offset.x, pos.y + offset.y, pos.z + offset.z);

        // Camera Follow Pos and Rotation events at the same timestamp establish
        // the exact start pose before orbit takes ownership.
        if (GameplayCamera.isOrbitCapturePending()) {
            GameplayCamera.captureOrbitStart(getPosition(), getYRot(), getXRot(), getRoll(),
                    stageLeft, stageUp, stageLook);
            GameplayCamera.OrbitPose captured = GameplayCamera.orbitPose();
            if (captured != null) {
                setRotation(captured.yaw(), captured.pitch(), captured.roll());
                Vec3 orbitPos = captured.position();
                setPosition(orbitPos.x, orbitPos.y, orbitPos.z);
            }
        }

        // On the first free-camera frame the normal follow pose above is the exact
        // starting point; capture it (using the un-rotated stage basis) so the
        // free camera takes over without a visible jump.
        if (GameplayCamera.isFreeCamEngaged() && !GameplayCamera.isFreeCamInitialized()) {
            GameplayCamera.captureFreeCamStart(getPosition(), getYRot(), getXRot(), getRoll(),
                    stageLeft, stageUp, stageLook);
        }
    }
}
