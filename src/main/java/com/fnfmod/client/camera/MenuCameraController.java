package com.fnfmod.client.camera;

import com.fnfmod.client.math.Easing;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.world.phys.Vec3;

import java.util.function.Consumer;

/** Camera override used by custom Lua menus and their editor, independent of gameplay camera events. */
public final class MenuCameraController {
    private static MenuCameraController active;

    private Vec3 position = Vec3.ZERO;
    private float yaw;
    private float pitch;
    private float roll;
    /** Menu-authored base FOV. It must not inherit the player's video setting. */
    private double fov = 70.0;
    private double zoom = 1.0;
    /** True only after Lua/editor explicitly takes ownership of a camera property. */
    private boolean engaged;
    private boolean captured;
    private Vec3 orbitPivot;
    private Vec3 storedOrbitPivot;
    private CameraType previousCameraType;
    private boolean cameraTypeChanged;
    private boolean positionControlled;
    private boolean rotationControlled;
    private Tween positionTween;
    private Tween rotationTween;
    private Tween positionXTween, positionYTween, positionZTween;
    private Tween rotationXTween, rotationYTween, rotationZTween;
    private Tween zoomTween;
    private Consumer<String> completion = ignored -> {};

    private record Tween(String tag, double[] from, double[] to, long start, long duration, String easing) {}
    public record Pose(Vec3 position, float yaw, float pitch, float roll,
                       boolean positionControlled, boolean rotationControlled) {}

    public void activate() {
        if (active == this) return;
        if (active != null) active.deactivate();
        active = this;
        // Legacy menus begin as a normal first-person screen. Camera takeover is
        // delayed until Lua/editor actually changes a camera property.
        try {
            net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
            previousCameraType = minecraft.options.getCameraType();
            CameraType desired = engaged ? CameraType.THIRD_PERSON_FRONT : CameraType.FIRST_PERSON;
            if (previousCameraType != desired) {
                minecraft.options.setCameraType(desired);
                cameraTypeChanged = true;
            }
        } catch (Throwable ignored) {}
    }

    /** Enables the detached authored camera. Safe to call repeatedly. */
    public void engage() {
        if (engaged) return;
        engaged = true;
        captured = false;
        try {
            net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
            if (minecraft.options.getCameraType() != CameraType.THIRD_PERSON_FRONT) {
                minecraft.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
                cameraTypeChanged = true;
            }
        } catch (Throwable ignored) {}
    }
    public void deactivate() {
        if (active == this) active = null;
        if (cameraTypeChanged) {
            try { net.minecraft.client.Minecraft.getInstance().options.setCameraType(previousCameraType); }
            catch (Throwable ignored) {}
            cameraTypeChanged = false;
        }
    }
    public static MenuCameraController active() { return active; }
    public boolean isEngaged() { return engaged; }
    public static boolean hasEngagedCamera() { return active != null && active.engaged; }

    public void onComplete(Consumer<String> listener) {
        completion = listener == null ? ignored -> {} : listener;
    }

    public void setPosition(double x, double y, double z, double seconds, String easing, String tag) {
        engage();
        positionControlled = true;
        positionXTween = positionYTween = positionZTween = null;
        positionTween = tween(tag, new double[]{position.x, position.y, position.z},
                new double[]{finite(x, position.x), finite(y, position.y), finite(z, position.z)},
                seconds, easing);
        if (positionTween == null) position = new Vec3(finite(x, position.x), finite(y, position.y), finite(z, position.z));
    }

    public void setRotation(double pitch, double yaw, double roll, double seconds, String easing, String tag) {
        engage();
        rotationControlled = true;
        rotationXTween = rotationYTween = rotationZTween = null;
        rotationTween = tween(tag, new double[]{this.pitch, this.yaw, this.roll},
                new double[]{clampPitch(pitch), wrap(yaw), wrap(roll)}, seconds, easing);
        if (rotationTween == null) {
            this.pitch = (float) clampPitch(pitch);
            this.yaw = (float) wrap(yaw);
            this.roll = (float) wrap(roll);
        }
    }

    public void setZoom(double value, double seconds, String easing, String tag) {
        engage();
        double target = Math.max(0.05, Math.min(20.0, finite(value, zoom)));
        zoomTween = tween(tag, new double[]{zoom}, new double[]{target}, seconds, easing);
        if (zoomTween == null) zoom = target;
    }

    /** Sets this menu's base FOV without changing Minecraft's FOV option. */
    public void setFov(double value) {
        engage();
        fov = Math.max(30.0, Math.min(110.0, finite(value, fov)));
    }

    public void tweenPositionAxis(int axis, double target, double seconds, String easing, String tag) {
        engage();
        positionControlled = true;
        positionTween = null;
        double from = axis == 0 ? position.x : axis == 1 ? position.y : position.z;
        Tween value = tween(tag, new double[]{from}, new double[]{finite(target, from)}, seconds, easing);
        if (axis == 0) positionXTween = value;
        else if (axis == 1) positionYTween = value;
        else positionZTween = value;
        if (value == null) setPositionAxis(axis, finite(target, from));
    }

    public void tweenRotationAxis(int axis, double target, double seconds, String easing, String tag) {
        engage();
        rotationControlled = true;
        rotationTween = null;
        double from = axis == 0 ? pitch : axis == 1 ? yaw : roll;
        double destination = axis == 0 ? clampPitch(target) : wrap(target);
        Tween value = tween(tag, new double[]{from}, new double[]{destination}, seconds, easing);
        if (axis == 0) rotationXTween = value;
        else if (axis == 1) rotationYTween = value;
        else rotationZTween = value;
        if (value == null) setRotationAxis(axis, destination);
    }

    public void lookAt(double x, double y, double z, double seconds, String easing, String tag) {
        Vec3 delta = new Vec3(x, y, z).subtract(position);
        if (delta.lengthSqr() < 1.0e-9) return;
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        setRotation(-Math.toDegrees(Math.atan2(delta.y, horizontal)),
                Math.toDegrees(Math.atan2(-delta.x, delta.z)), roll, seconds, easing, tag);
    }

    /** Animated focus which computes both tween endpoints before either tween begins. */
    public void focus(Vec3 target, double distance, double seconds, String easing) {
        if (target == null) return;
        storedOrbitPivot = target;
        if (orbitPivot != null) orbitPivot = target;
        Vec3 destination = target.subtract(viewDirection().scale(Math.max(0.1, distance)));
        Vec3 delta = target.subtract(destination);
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        double targetPitch = -Math.toDegrees(Math.atan2(delta.y, horizontal));
        double targetYaw = Math.toDegrees(Math.atan2(-delta.x, delta.z));
        setPosition(destination.x, destination.y, destination.z, seconds, easing, "editorFocusPosition");
        setRotation(targetPitch, targetYaw, roll, seconds, easing, "editorFocusRotation");
    }

    public void reset(double seconds, String easing, String tag) {
        if (!captured) return;
        positionControlled = false;
        rotationControlled = false;
        positionTween = null;
        rotationTween = null;
        positionXTween = positionYTween = positionZTween = null;
        rotationXTween = rotationYTween = rotationZTween = null;
        orbitPivot = null;
        storedOrbitPivot = null;
        setFov(70.0);
        setZoom(1.0, seconds, easing, tag);
    }

    public Vec3 position() { return position; }
    public float yaw() { return yaw; }
    public float pitch() { return pitch; }
    public float roll() { return roll; }
    public double fov() { return fov; }
    public double zoom() { return zoom; }

    /** Actual vertical FOV after this menu's Zoom multiplier. */
    public double effectiveFov() {
        return Math.max(10.0, Math.min(170.0, fov / Math.max(0.05, zoom)));
    }

    public void move(Vec3 delta) {
        if (delta == null) return;
        engage();
        positionControlled = true;
        positionTween = null;
        positionXTween = positionYTween = positionZTween = null;
        position = position.add(delta);
    }

    /** Minecraft-style movement: horizontal motion follows view yaw; Q/E stay world-vertical. */
    public void moveRelative(double forward, double strafe, double vertical, double distance) {
        if (distance == 0) return;
        double radians = Math.toRadians(yaw);
        double sin = Math.sin(radians);
        double cos = Math.cos(radians);
        // Minecraft yaw 0 looks toward +Z. When facing +Z, screen-right/player-right
        // is -X, not +X; this sign is what the earlier implementation got wrong.
        move(new Vec3((-forward * sin - strafe * cos) * distance,
                vertical * distance,
                (forward * cos - strafe * sin) * distance));
    }

    /** Absolute Minecraft axes with NORTH as forward, independent of view or machine facing. */
    public void moveAbsolute(double forward, double strafe, double vertical, double distance) {
        if (distance == 0) return;
        move(new Vec3(strafe * distance, vertical * distance, -forward * distance));
    }

    public void turn(double deltaYaw, double deltaPitch) {
        engage();
        rotationControlled = true;
        rotationTween = null;
        rotationXTween = rotationYTween = rotationZTween = null;
        yaw = (float) wrap(yaw + deltaYaw);
        pitch = (float) clampPitch(pitch + deltaPitch);
    }

    /** Starts a Blender/free-cam style orbit. Null reuses the last focused pivot. */
    public void beginOrbit(Vec3 pivot) {
        engage();
        if (pivot != null) storedOrbitPivot = pivot;
        orbitPivot = pivot != null ? pivot : storedOrbitPivot != null
                ? storedOrbitPivot : position.add(viewDirection().scale(6.0));
    }

    public void orbit(double dragX, double dragY) {
        if (orbitPivot == null) beginOrbit(null);
        Vec3 offset = position.subtract(orbitPivot);
        if (offset.lengthSqr() < 1.0e-8) return;
        double yawDegrees = dragX * 0.375;
        // Menu-editor drag coordinates arrive opposite to gameplay Free Cam's
        // captured-mouse delta on the vertical axis.
        double pitchDegrees = Math.max(-89.0, Math.min(89.0, pitch + dragY * 0.375)) - pitch;
        Vec3 yawed = rotateAroundAxis(offset, new Vec3(0, 1, 0), Math.toRadians(-yawDegrees));
        Vec3 right = viewRight();
        right = rotateAroundAxis(right, new Vec3(0, 1, 0), Math.toRadians(-yawDegrees));
        position = orbitPivot.add(rotateAroundAxis(yawed, right, Math.toRadians(pitchDegrees)));
        positionControlled = true;
        positionTween = null;
        positionXTween = positionYTween = positionZTween = null;
        lookAt(orbitPivot.x, orbitPivot.y, orbitPivot.z, 0, "linear", "");
    }

    public void panOrbit(double dragX, double dragY, double viewportHeight, double fovDegrees) {
        if (orbitPivot == null) beginOrbit(null);
        double distance = Math.max(0.1, position.distanceTo(orbitPivot));
        double worldPerPixel = 2.0 * distance * Math.tan(Math.toRadians(fovDegrees) * 0.5)
                / Math.max(1.0, viewportHeight);
        Vec3 delta = viewRight().scale(dragX * worldPerPixel)
                .add(viewUp().scale(dragY * worldPerPixel));
        move(delta);
        orbitPivot = orbitPivot.add(delta);
        storedOrbitPivot = orbitPivot;
    }

    public void dollyOrbit(double steps) {
        if (orbitPivot == null) beginOrbit(null);
        Vec3 offset = position.subtract(orbitPivot);
        double distance = offset.length();
        if (distance < 1.0e-6) return;
        double next = Math.max(0.10, Math.min(512.0, distance * Math.pow(0.82, steps)));
        position = orbitPivot.add(offset.scale(next / distance));
        positionControlled = true;
        positionTween = null;
        positionXTween = positionYTween = positionZTween = null;
    }

    public void endOrbit() { orbitPivot = null; }

    public Vec3 storedOrbitPivot() { return storedOrbitPivot; }
    public void setStoredOrbitPivot(Vec3 pivot) {
        storedOrbitPivot = pivot;
        if (orbitPivot != null) orbitPivot = pivot;
    }

    public boolean cancelTween(String tag) {
        if (tag == null || tag.isBlank()) return false;
        boolean removed = false;
        if (hasTag(positionTween, tag)) { positionTween = null; removed = true; }
        if (hasTag(rotationTween, tag)) { rotationTween = null; removed = true; }
        if (hasTag(positionXTween, tag)) { positionXTween = null; removed = true; }
        if (hasTag(positionYTween, tag)) { positionYTween = null; removed = true; }
        if (hasTag(positionZTween, tag)) { positionZTween = null; removed = true; }
        if (hasTag(rotationXTween, tag)) { rotationXTween = null; removed = true; }
        if (hasTag(rotationYTween, tag)) { rotationYTween = null; removed = true; }
        if (hasTag(rotationZTween, tag)) { rotationZTween = null; removed = true; }
        if (hasTag(zoomTween, tag)) { zoomTween = null; removed = true; }
        return removed;
    }

    private static boolean hasTag(Tween tween, String tag) {
        return tween != null && tag.equals(tween.tag());
    }

    private Vec3 viewDirection() {
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
        return new Vec3(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p)).normalize();
    }

    private Vec3 viewRight() {
        double y = Math.toRadians(yaw);
        return new Vec3(Math.cos(y), 0, Math.sin(y)).normalize();
    }

    private Vec3 viewUp() {
        Vec3 right = viewRight();
        Vec3 look = viewDirection();
        Vec3 up = look.cross(right);
        return up.lengthSqr() < 1.0e-8 ? new Vec3(0, 1, 0) : up.normalize();
    }

    private static Vec3 rotateAroundAxis(Vec3 vector, Vec3 axis, double radians) {
        Vec3 unit = axis.normalize();
        double cos = Math.cos(radians), sin = Math.sin(radians);
        return vector.scale(cos).add(unit.cross(vector).scale(sin))
                .add(unit.scale(unit.dot(vector) * (1.0 - cos)));
    }

    public static Pose sample(Camera camera) {
        MenuCameraController controller = active;
        if (controller == null || !controller.engaged || camera == null) return null;
        controller.capture(camera);
        controller.update();
        return new Pose(controller.position, controller.yaw, controller.pitch, controller.roll,
                controller.positionControlled, controller.rotationControlled);
    }

    public static double fovScale(double incomingFov) {
        MenuCameraController controller = active;
        if (controller == null || !controller.engaged) return 1.0;
        controller.update();
        return controller.effectiveFov() / Math.max(1.0, incomingFov);
    }

    public static double effectiveFov(double fallback) {
        MenuCameraController controller = active;
        if (controller == null || !controller.engaged) return fallback;
        controller.update();
        return controller.effectiveFov();
    }

    /** Advances an inactive authored camera while an editor-only free view is active. */
    public void tickDetached() {
        if (active != this) update();
    }

    private void capture(Camera camera) {
        if (captured) return;
        captured = true;
        if (!positionControlled) position = camera.getPosition();
        if (!rotationControlled) {
            yaw = camera.getYRot();
            pitch = camera.getXRot();
            roll = camera.getRoll();
        }
    }

    private void update() {
        positionTween = update(positionTween, values -> position = new Vec3(values[0], values[1], values[2]));
        positionXTween = update(positionXTween, values -> setPositionAxis(0, values[0]));
        positionYTween = update(positionYTween, values -> setPositionAxis(1, values[0]));
        positionZTween = update(positionZTween, values -> setPositionAxis(2, values[0]));
        rotationTween = update(rotationTween, values -> {
            pitch = (float) values[0]; yaw = (float) values[1]; roll = (float) values[2];
        });
        rotationXTween = update(rotationXTween, values -> setRotationAxis(0, values[0]));
        rotationYTween = update(rotationYTween, values -> setRotationAxis(1, values[0]));
        rotationZTween = update(rotationZTween, values -> setRotationAxis(2, values[0]));
        zoomTween = update(zoomTween, values -> zoom = values[0]);
    }

    private void setPositionAxis(int axis, double value) {
        position = axis == 0 ? new Vec3(value, position.y, position.z)
                : axis == 1 ? new Vec3(position.x, value, position.z)
                : new Vec3(position.x, position.y, value);
    }

    private void setRotationAxis(int axis, double value) {
        if (axis == 0) pitch = (float) value;
        else if (axis == 1) yaw = (float) value;
        else roll = (float) value;
    }

    private Tween update(Tween tween, Consumer<double[]> setter) {
        if (tween == null) return null;
        double progress = Math.min(1.0, Math.max(0.0,
                (System.nanoTime() - tween.start()) / (double) tween.duration()));
        double eased = Easing.apply(tween.easing(), progress);
        double[] values = new double[tween.from().length];
        for (int i = 0; i < values.length; i++) values[i] = tween.from()[i] + (tween.to()[i] - tween.from()[i]) * eased;
        setter.accept(values);
        if (progress < 1.0) return tween;
        if (tween.tag() != null && !tween.tag().isBlank()) completion.accept(tween.tag());
        return null;
    }

    private static Tween tween(String tag, double[] from, double[] to, double seconds, String easing) {
        if (!Double.isFinite(seconds) || seconds <= 0) return null;
        long duration = Math.max(1_000_000L, (long) (Math.min(3600, seconds) * 1_000_000_000L));
        return new Tween(tag == null ? "" : tag, from, to, System.nanoTime(), duration,
                easing == null || easing.isBlank() ? "linear" : easing);
    }

    private static double finite(double value, double fallback) { return Double.isFinite(value) ? value : fallback; }
    private static double clampPitch(double value) { return Math.max(-89.9, Math.min(89.9, finite(value, 0))); }
    private static double wrap(double value) {
        double wrapped = finite(value, 0) % 360.0;
        return wrapped > 180 ? wrapped - 360 : wrapped < -180 ? wrapped + 360 : wrapped;
    }
}
