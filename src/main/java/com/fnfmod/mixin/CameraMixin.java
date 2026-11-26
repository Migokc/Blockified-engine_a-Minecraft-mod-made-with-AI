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
        if (com.fnfmod.client.camera.MenuCameraController.hasEngagedCamera()) {
            var pose = com.fnfmod.client.camera.MenuCameraController.sample((Camera) (Object) this);
            if (pose != null) {
                if (pose.rotationControlled()) setRotation(pose.yaw(), pose.pitch(), pose.roll());
                if (pose.positionControlled()) {
                    Vec3 p = pose.position();
                    setPosition(p.x, p.y, p.z);
                }
            }
            return;
        }
        boolean editorCamera = GameplayCamera.isFreeCamEngaged();
        // Sample the real song camera even while Free View owns the rendered
        // viewport. The editor marker and Gameplay Camera view both need this
        // continuing focus/event/Lua/orbit pose; only the navigation view is isolated.
        Vec3 rotation = GameplayCamera.rotationOffset(editorCamera);
        // Snapshot the stage-facing basis before Camera Rotation 3D tilts it, so
        // Camera Follow Pos can keep its offsets aligned to the machine facing.
        Vector3f stageLeft = new Vector3f(getLeftVector());
        Vector3f stageUp = new Vector3f(getUpVector());
        Vector3f stageLook = new Vector3f(getLookVector());
        GameplayCamera.OrbitPose orbit = GameplayCamera.orbitPose(editorCamera);
        if (orbit != null) {
            setRotation(orbit.yaw(), orbit.pitch(), orbit.roll());
            Vec3 pos = orbit.position();
            setPosition(pos.x, pos.y, pos.z);
        } else {
            if (!rotation.equals(Vec3.ZERO)) {
                setRotation(getYRot() + (float) rotation.y,
                        getXRot() + (float) rotation.x,
                        getRoll() + (float) rotation.z);
            }
            Vec3 offset = GameplayCamera.worldOffset(getPosition(),
                    getLeftVector(), getUpVector(), getLookVector(),
                    stageLeft, stageUp, stageLook, editorCamera);
            if (offset != null) {
                Vec3 pos = getPosition();
                setPosition(pos.x + offset.x, pos.y + offset.y, pos.z + offset.z);
            }

            // Follow Pos and Rotation at the same timestamp establish the exact
            // start pose before orbit takes ownership, including during preview.
            if (GameplayCamera.isOrbitCapturePending(editorCamera)) {
                GameplayCamera.captureOrbitStart(getPosition(), getYRot(), getXRot(), getRoll(),
                        stageLeft, stageUp, stageLook, editorCamera);
                GameplayCamera.OrbitPose captured = GameplayCamera.orbitPose(editorCamera);
                if (captured != null) {
                    setRotation(captured.yaw(), captured.pitch(), captured.roll());
                    Vec3 orbitPos = captured.position();
                    setPosition(orbitPos.x, orbitPos.y, orbitPos.z);
                }
            }
        }

        if (GameplayCamera.isActive()) {
            GameplayCamera.captureLuaBasePosition(getPosition());
            Vec3 luaOffset = GameplayCamera.luaWorldOffset(
                    com.fnfmod.client.gameplay.StageOrientation.facing());
            if (!luaOffset.equals(Vec3.ZERO)) {
                Vec3 base = getPosition();
                setPosition(base.x + luaOffset.x, base.y + luaOffset.y, base.z + luaOffset.z);
            }
            if (GameplayCamera.luaPitch() != 0f || GameplayCamera.luaYaw() != 0f
                    || GameplayCamera.luaRoll() != 0f) {
                setRotation(getYRot() + GameplayCamera.luaYaw(),
                        getXRot() + GameplayCamera.luaPitch(),
                        getRoll() + GameplayCamera.luaRoll());
            }
        }

        if (editorCamera) {
            // Record before applying the editor viewport. Free View must never
            // feed its position/rotation back into the song camera simulation.
            GameplayCamera.captureLiveGameplayPose(getPosition(), getYRot(), getXRot(), getRoll());
            if (!GameplayCamera.isFreeCamInitialized()) {
                GameplayCamera.captureFreeCamStart(getPosition(), getYRot(), getXRot(), getRoll(),
                        stageLeft, stageUp, stageLook);
            }
            if (GameplayCamera.isFreeCamInitialized()) {
                if (GameplayCamera.isFreeCamGameplayView()) {
                    GameplayCamera.LiveCameraPose gameplay = GameplayCamera.editorGameplayViewPose();
                    if (gameplay != null) {
                        setRotation(gameplay.yaw(), gameplay.pitch(), gameplay.roll());
                        Vec3 pos = gameplay.position();
                        setPosition(pos.x, pos.y, pos.z);
                    }
                } else {
                    // Navigation is a separate camera: no focus, shake, orbit,
                    // chart event or Lua rotation may leak into this viewport.
                    setRotation(GameplayCamera.freeViewBaseYaw() + (float) GameplayCamera.freeYaw(),
                            GameplayCamera.freeViewBasePitch() + (float) GameplayCamera.freePitch(),
                            GameplayCamera.freeViewBaseRoll() + (float) GameplayCamera.freeRoll());
                    Vec3 pos = GameplayCamera.freeCamWorldPos();
                    setPosition(pos.x, pos.y, pos.z);
                }
            }
        }
        if (GameplayCamera.isActive() && (!editorCamera || GameplayCamera.isFreeCamGameplayView())) {
            float exitRoll = GameplayCamera.exitHoldRollDegrees();
            if (exitRoll != 0f) setRotation(getYRot(), getXRot(), getRoll() + exitRoll);
        }
    }
}
