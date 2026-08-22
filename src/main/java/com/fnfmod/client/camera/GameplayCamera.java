package com.fnfmod.client.camera;

import com.fnfmod.gameplay.GameplayClock;
import com.fnfmod.client.math.Easing;
import com.fnfmod.gameplay.PlaybackMode;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.function.Supplier;

/**
 * FNF-style camera: follows focused characters in 3D while applying animation
 * nudges in the camera's screen plane. Its viewing direction remains tied to
 * the stage rather than a character's custom body rotation.
 *
 * Focus follows the chart (mustHitSection = player focused). Each character
 * side has a base camera offset plus a per-animation nudge (both from the
 * animation set's character.json). Focus changes ease with the section's
 * configured curve.
 */
public final class GameplayCamera {

    public static final String[] EASES = Easing.BASES;
    private static final float DEFAULT_CAMERA_SPEED = 1f;

    private static boolean active;
    private static PlaybackMode playbackMode = PlaybackMode.MINECRAFT;
    private static Vec3 anchor = Vec3.ZERO;
    /** Entity yaw the stage camera used before character.json rotation offsets. */
    private static float stageViewYaw;
    private static Supplier<Vec3> cameraEntityPos;
    private static Supplier<Vec3> playerSidePos;
    private static Supplier<Vec3> opponentSidePos;
    private static Supplier<Vec3> customFocusPos;
    private static float customFocusBaseX, customFocusBaseY;
    private static float playerBaseX, playerBaseY, oppBaseX, oppBaseY;

    private static boolean focusPlayer = true;
    private static Vec3 fromOffset = Vec3.ZERO;
    private static Vec3 curOffset = Vec3.ZERO;
    private static boolean offsetInitialized;
    private static long transStart;
    /** Starts a non-instant focus tween on its first rendered sample, at exactly t=0. */
    private static boolean transPendingFirstSample;
    private static double transDurMs = 500;
    private static String ease = "smooth";

    // per-side sing nudges (decay over time)
    private static float pNudgeX, pNudgeY, oNudgeX, oNudgeY;
    // beat-hit zoom (FOV pinch that decays, like FNF's camZoom bump)
    private static float beatZoom;
    private static float hudBeatZoom;
    /** Disabling blocks future beat impulses; existing impulse still eases out. */
    private static boolean gameBopEnabled = true;
    private static boolean hudBopEnabled = true;
    private static float hudEventZoom;
    private static float baseGameZoom = 1f;
    private static boolean forcedFrame;
    private static float forcedFrameX, forcedFrameY;
    private static boolean positionEventActive;
    private static boolean positionEventOverride;
    /** false = offsets aligned to the machine facing; true = to the camera rotation. */
    private static boolean positionEventCameraRelative;
    private static Vec3 positionEventFrom = Vec3.ZERO;
    private static Vec3 positionEventCurrent = Vec3.ZERO;
    private static Vec3 positionEventTarget = Vec3.ZERO;
    private static long positionEventStart;
    private static String positionEventEase = "smooth";
    private static Vec3 rotationEventFrom = Vec3.ZERO;
    private static Vec3 rotationEventCurrent = Vec3.ZERO;
    private static Vec3 rotationEventTarget = Vec3.ZERO;
    private static long rotationEventStart;
    private static String rotationEventEase = "smooth";
    /** View-only rotation; unlike the normal layer, it never moves an orbiting camera. */
    private static Vec3 viewRotationFrom = Vec3.ZERO;
    private static Vec3 viewRotationCurrent = Vec3.ZERO;
    private static Vec3 viewRotationTarget = Vec3.ZERO;
    private static long viewRotationStart;
    private static String viewRotationEase = "smooth";
    private static final double CAMERA_EVENT_DURATION_MS = 500.0;
    /** Follow-pos move duration; the shared default until an event supplies one. */
    private static double positionEventDurationMs = CAMERA_EVENT_DURATION_MS;
    /** Rotation duration; blank/invalid event values retain the historical 0.5s. */
    private static double rotationEventDurationMs = CAMERA_EVENT_DURATION_MS;
    private static double viewRotationDurationMs = CAMERA_EVENT_DURATION_MS;
    private static long gameShakeEnd, hudShakeEnd;
    private static float gameShakeIntensity, hudShakeIntensity;
    // persistent event zoom, tweened to a target over a fixed short transition
    private static float eventZoom, eventZoomFrom, eventZoomTarget;
    private static long eventZoomStart;
    private static String eventZoomEase = "smooth";
    private static final double EVENT_ZOOM_DURATION_MS = 500.0;
    private static double eventZoomDurationMs = EVENT_ZOOM_DURATION_MS;
    private static long lastFrameNano;
    private static float cameraSpeed = DEFAULT_CAMERA_SPEED;
    private static String cameraEase = "smooth";
    private static boolean animationCameraOffsetsEnabled = true;

    // Free camera (editor playtest): a spectator-style override. Position is kept
    // as a stage-frame block offset from the anchor (X right, Y up, Z forward) so
    // it maps one-to-one onto a Camera Follow Pos override event; rotation is a
    // pitch/yaw/roll offset from the stage view, matching Camera Rotation 3D.
    private static boolean freeCamEngaged;
    private static boolean freeCamInitialized;
    private static double freeX, freeY, freeZ;
    private static double freePitch, freeYaw, freeRoll;
    /** Free-camera zoom offset, matching a Camera Zoom event's amount (-1..0.9). */
    private static double freeZoom;
    // Player position in the same stage frame as the free offset, captured at
    // free-cam start. Chunks load around the player (which a tween/tp may have
    // moved far from the machine), so the loaded-chunk clamp is measured from
    // here, not from the anchor. The world is frozen during free-cam, so this
    // stays valid for the whole session.
    private static double playerFreeX, playerFreeY, playerFreeZ;
    // Stage-camera basis (world unit vectors) captured at free-cam start. Fixed
    // for the song, this is the exact basis freeX/Y/Z and a machine-frame Camera
    // Follow Pos use, so the copied event's values are computed against it.
    private static Vec3 stageRightWorld = new Vec3(1, 0, 0);
    private static Vec3 stageUpWorld = new Vec3(0, 1, 0);
    private static Vec3 stageForwardWorld = new Vec3(0, 0, 1);
    // Minecraft's detached third-person camera starts offset from cameraEntityPos
    // before Blockified adds its normal follow offset. Free cam later replaces the
    // camera position absolutely, but a pasted Camera Follow Pos still runs through
    // that detached baseline. Copy values must subtract it or playback adds it twice.
    private static Vec3 detachedCameraBaselineWorld = Vec3.ZERO;
    private static Vec3 lastNormalWorldOffset = Vec3.ZERO;

    // Authored orbit camera. Camera Follow Pos establishes the starting camera
    // position/radius; Camera Rotation 3D is the sole orbital animator. The
    // pivot can follow Camera Focus with a stage-local offset, or remain pinned
    // at an absolute stage-local position.
    private static boolean orbitActive, orbitCapturePending;
    private static boolean orbitUseFocusPivot;
    private static Vec3 orbitPivotStage = Vec3.ZERO;
    private static Vec3 orbitPivotWorld = Vec3.ZERO;
    private static Vec3 orbitFocusPivot = Vec3.ZERO;
    private static Vec3 orbitFocusPivotFrom = Vec3.ZERO;
    private static boolean orbitFocusPivotInitialized, orbitFocusTransitionPending;
    private static long orbitFocusTransitionStart;
    private static double orbitFocusTransitionDurationMs = 500;
    private static String orbitFocusTransitionEase = "smooth";
    private static Vec3 orbitStartOffset = Vec3.ZERO;
    private static Vec3 orbitRightWorld = new Vec3(1, 0, 0);
    private static Vec3 orbitUpWorld = new Vec3(0, 1, 0);
    private static Vec3 orbitForwardWorld = new Vec3(0, 0, 1);
    private static float orbitStartRoll;
    private static Vec3 orbitStartRotationOffset = Vec3.ZERO;

    /** Absolute pose consumed by CameraMixin while an authored orbit owns the view. */
    public record OrbitPose(Vec3 position, float yaw, float pitch, float roll) {}

    public static void beginFreeCam() {
        freeCamEngaged = true;
        freeCamInitialized = false;
    }

    public static void endFreeCam() {
        freeCamEngaged = false;
        freeCamInitialized = false;
    }

    public static boolean isFreeCamEngaged() { return freeCamEngaged; }

    public static boolean isFreeCamInitialized() { return freeCamInitialized; }

    /** Seeds the free camera from the current stage-camera pose, so it starts seamlessly. */
    public static void captureFreeCamStart(Vec3 camPos, float yaw, float pitch, float roll,
                                           Vector3f stageLeft, Vector3f stageUp, Vector3f stageForward) {
        Vec3 delta = camPos.subtract(anchor);
        // Screen right = -left. Decompose the world delta onto the stage basis so
        // the position is a full override offset (like Camera Follow Pos override).
        double rx = -stageLeft.x(), ry = -stageLeft.y(), rz = -stageLeft.z();
        double ux = stageUp.x(), uy = stageUp.y(), uz = stageUp.z();
        double fx = stageForward.x(), fy = stageForward.y(), fz = stageForward.z();
        freeX = delta.x * rx + delta.y * ry + delta.z * rz;
        freeY = delta.x * ux + delta.y * uy + delta.z * uz;
        freeZ = delta.x * fx + delta.y * fy + delta.z * fz;
        // The player (chunk-loading anchor) in the same frame, for the clamp.
        Vec3 player = cameraEntityPos == null ? anchor : cameraEntityPos.get();
        if (player == null) player = anchor;
        Vec3 pd = player.subtract(anchor);
        playerFreeX = pd.x * rx + pd.y * ry + pd.z * rz;
        playerFreeY = pd.x * ux + pd.y * uy + pd.z * uz;
        playerFreeZ = pd.x * fx + pd.y * fy + pd.z * fz;
        stageRightWorld = new Vec3(rx, ry, rz);
        stageUpWorld = new Vec3(ux, uy, uz);
        stageForwardWorld = new Vec3(fx, fy, fz);
        // camPos already includes lastNormalWorldOffset. Removing it recovers
        // Minecraft's untouched detached-camera position; comparing that with
        // cameraEntityPos gives the baseline a normal event will add on playback.
        Vec3 cameraBase = cameraEntityPos == null ? anchor : cameraEntityPos.get();
        if (cameraBase == null) cameraBase = anchor;
        detachedCameraBaselineWorld = camPos.subtract(lastNormalWorldOffset).subtract(cameraBase);
        // Rotation is applied additively (Camera Rotation 3D semantics), so start
        // from the active rotation-event offset, not the absolute camera angles.
        long now = GameplayClock.now();
        updateRotationEvent(now);
        updateViewRotationEvent(now);
        Vec3 combinedRotation = rotationEventCurrent.add(viewRotationCurrent);
        freePitch = combinedRotation.x;
        freeYaw = combinedRotation.y;
        freeRoll = combinedRotation.z;
        updateEventZoom(GameplayClock.now());
        freeZoom = eventZoom;
        freeCamInitialized = true;
    }

    /** Moves the free camera by a stage-frame block delta (X right, Y up, Z forward). */
    public static void moveFreeCam(double dx, double dy, double dz) {
        if (!freeCamEngaged) return;
        freeX += dx; freeY += dy; freeZ += dz;
    }

    /** Sets the free camera's stage-frame offset directly (used by the focus tween). */
    public static void setFreeCamOffset(double x, double y, double z) {
        if (!freeCamEngaged) return;
        freeX = x; freeY = y; freeZ = z;
    }

    /** Decomposes a world position into the free camera's stage-frame offset. */
    public static double[] worldToFreeOffset(Vec3 world) {
        Vec3 d = world.subtract(anchor);
        return new double[]{d.dot(stageRightWorld), d.dot(stageUpWorld), d.dot(stageForwardWorld)};
    }

    /** The free camera's current world position, reconstructed from its offset. */
    public static Vec3 freeCamWorldPos() {
        return anchor.add(stageRightWorld.scale(freeX))
                .add(stageUpWorld.scale(freeY))
                .add(stageForwardWorld.scale(freeZ));
    }

    /** Resolves stage-local X/Y/Z from an arbitrary world-space origin. */
    public static Vec3 stageOffsetFrom(Vec3 origin, double x, double y, double z) {
        Vec3 base = origin == null ? anchor : origin;
        return base.add(stageRightWorld.scale(x)).add(stageUpWorld.scale(y))
                .add(stageForwardWorld.scale(z));
    }

    /** Turns the free camera so its screen centre lands exactly on a world point. */
    public static void aimFreeCamAt(Vec3 target, float currentCameraYaw, float currentCameraPitch) {
        if (!freeCamEngaged || target == null) return;
        Vec3 direction = target.subtract(freeCamWorldPos());
        if (direction.lengthSqr() < 1.0e-12) return;
        direction = direction.normalize();

        double desiredYaw = Math.toDegrees(Math.atan2(-direction.x, direction.z));
        double desiredPitch = Math.toDegrees(-Math.asin(Math.max(-1.0, Math.min(1.0, direction.y))));
        double baseYaw = currentCameraYaw - freeYaw;
        double basePitch = currentCameraPitch - freePitch;
        freeYaw = wrapDegrees(desiredYaw - baseYaw);
        freePitch = Math.max(-89.9, Math.min(89.9, desiredPitch - basePitch));
    }

    private static double wrapDegrees(double angle) {
        angle %= 360.0;
        if (angle >= 180.0) angle -= 360.0;
        if (angle < -180.0) angle += 360.0;
        return angle;
    }

    /** The character the section focus is currently on (for an attached Follow Pos). */
    public static Vec3 focusWorldPos() {
        Supplier<Vec3> supplier = customFocusPos != null ? customFocusPos
                : focusPlayer ? playerSidePos : opponentSidePos;
        Vec3 pos = supplier == null ? null : supplier.get();
        return pos == null ? anchor : pos;
    }

    /**
     * Camera Follow Pos X/Y/Z that reproduce the free camera's current position
     * under the chosen Movement (override = fixed at the anchor, attached = offset
     * from the focused character) and Frame (machine = the fixed stage basis,
     * camera = the live camera basis). The camera never moves; only the value
     * representation changes, so switching options is jump-free on playback.
     */
    public static double[] followPosValues(boolean override, boolean cameraFrame,
                                           Vector3f camLeft, Vector3f camUp, Vector3f camLook) {
        Vec3 cam = freeCamWorldPos();
        // Camera Follow Pos playback starts from Minecraft's detached third-person
        // baseline. Include it in the origin so copied XYZ describe only the event
        // offset needed to reach the current absolute free-camera position.
        Vec3 origin = (override ? anchor : focusWorldPos()).add(detachedCameraBaselineWorld);
        if (!override && camLeft != null) {
            // Default Follow Pos sits at focus + the stage's base camera framing,
            // then adds the event offset. Fold the framing into the origin so the
            // decomposed offset reproduces the free-cam spot exactly on playback.
            double frameX = customFocusPos != null ? customFocusBaseX
                    : focusPlayer ? playerBaseX + (animationCameraOffsetsEnabled ? pNudgeX : 0)
                    : oppBaseX + (animationCameraOffsetsEnabled ? oNudgeX : 0);
            double frameY = customFocusPos != null ? customFocusBaseY
                    : focusPlayer ? playerBaseY + (animationCameraOffsetsEnabled ? pNudgeY : 0)
                    : oppBaseY + (animationCameraOffsetsEnabled ? oNudgeY : 0);
            Vec3 screenRight = new Vec3(-camLeft.x(), -camLeft.y(), -camLeft.z());
            Vec3 screenUp = new Vec3(camUp.x(), camUp.y(), camUp.z());
            origin = origin.add(screenRight.scale(frameX)).add(screenUp.scale(frameY));
        }
        Vec3 d = cam.subtract(origin);
        Vec3 right, up, forward;
        if (cameraFrame && camLeft != null) {
            right = new Vec3(-camLeft.x(), -camLeft.y(), -camLeft.z());
            up = new Vec3(camUp.x(), camUp.y(), camUp.z());
            forward = new Vec3(camLook.x(), camLook.y(), camLook.z());
        } else {
            right = stageRightWorld;
            up = stageUpWorld;
            forward = stageForwardWorld;
        }
        return new double[]{ d.dot(right), d.dot(up), d.dot(forward) };
    }

    /**
     * Keeps the free camera inside loaded terrain. The offset is measured from
     * the anchor (near the player, around which chunks load), so limiting the
     * horizontal distance to the render distance stops the camera from drifting
     * into unloaded chunks, where the view and lighting break down.
     */
    public static void clampFreeCamToLoaded(double maxHorizontal, double maxVertical) {
        if (!freeCamEngaged) return;
        // Measure from the player (around whom chunks load), not the anchor: a
        // tween/tp can leave the machine far from the loaded region.
        double dx = freeX - playerFreeX;
        double dz = freeZ - playerFreeZ;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal > maxHorizontal && horizontal > 0) {
            double scale = maxHorizontal / horizontal;
            freeX = playerFreeX + dx * scale;
            freeZ = playerFreeZ + dz * scale;
        }
        freeY = Math.max(playerFreeY - maxVertical, Math.min(playerFreeY + maxVertical, freeY));
    }

    /** Adds to the free camera's look; pitch clamps to avoid gimbal flips. */
    public static void turnFreeCam(double dYaw, double dPitch) {
        if (!freeCamEngaged) return;
        freeYaw += dYaw;
        freePitch = Math.max(-89.9, Math.min(89.9, freePitch + dPitch));
    }

    public static void rollFreeCam(double dRoll) {
        if (freeCamEngaged) freeRoll += dRoll;
    }

    /** Adds to the free-camera zoom, clamped to the Camera Zoom event's range. */
    public static void zoomFreeCam(double dZoom) {
        if (freeCamEngaged) freeZoom = Math.max(-1.0, Math.min(0.9, freeZoom + dZoom));
    }

    /** R key: roll back to level and zoom back to Blockified's default (0). */
    public static void resetFreeRollZoom() {
        if (!freeCamEngaged) return;
        freeRoll = 0;
        freeZoom = 0;
    }

    public static double freeYaw() { return freeYaw; }
    public static double freePitch() { return freePitch; }
    public static double freeRoll() { return freeRoll; }
    public static double freeZoom() { return freeZoom; }
    public static double freeX() { return freeX; }
    public static double freeY() { return freeY; }
    public static double freeZ() { return freeZ; }

    /**
     * Enables pivot-orbit mode. Follow uses Camera Focus + XYZ stage-local
     * offset; pinned interprets XYZ as an absolute stage-local pivot position.
     * Camera Rotation 3D supplies all subsequent orbital rotation.
     */
    public static void orbit(boolean pinned, double pivotX, double pivotY, double pivotZ,
                             double durationSeconds, String easing) {
        if (!active || playbackMode == PlaybackMode.FNF) return;
        boolean updating = orbitActive && !orbitCapturePending;
        orbitUseFocusPivot = !pinned;
        orbitPivotStage = new Vec3(finite(pivotX), finite(pivotY), finite(pivotZ));
        if (updating) {
            orbitFocusTransitionEase = normalizeCameraEase(easing);
            orbitFocusTransitionDurationMs = Math.max(0,
                    Double.isFinite(durationSeconds) ? durationSeconds * 1000.0 : 500.0);
            String normalized = Easing.normalize(orbitFocusTransitionEase);
            if (orbitFocusTransitionDurationMs <= 0 || normalized.equals("constant")
                    || normalized.equals("snap")) {
                orbitFocusPivot = orbitPivotTarget();
                orbitFocusPivotFrom = orbitFocusPivot;
                orbitFocusTransitionPending = false;
                orbitFocusTransitionStart = 0;
            } else {
                orbitFocusPivotFrom = orbitFocusPivot;
                orbitFocusTransitionPending = true;
                orbitFocusTransitionStart = 0;
            }
            return;
        }
        orbitActive = true;
        orbitCapturePending = true;
    }

    /** Stops orbit ownership; the normal focus/follow camera resumes. */
    public static void stopOrbit() {
        orbitActive = false;
        orbitCapturePending = false;
        orbitFocusPivotInitialized = false;
        orbitFocusTransitionPending = false;
        orbitFocusTransitionStart = 0;
    }

    public static boolean isOrbitCapturePending() {
        return orbitActive && orbitCapturePending && !freeCamEngaged;
    }

    /** Captures the post-Follow-Pos pose on the first rendered orbit frame. */
    public static void captureOrbitStart(Vec3 cameraPosition, float yaw, float pitch, float roll,
                                         Vector3f stageLeft, Vector3f stageUp,
                                         Vector3f stageForward) {
        if (!isOrbitCapturePending() || cameraPosition == null) return;
        Vec3 right = new Vec3(stageLeft).scale(-1).normalize();
        Vec3 up = new Vec3(stageUp).normalize();
        Vec3 forward = new Vec3(stageForward).normalize();
        orbitRightWorld = right;
        orbitUpWorld = up;
        orbitForwardWorld = forward;
        orbitPivotWorld = orbitUseFocusPivot ? orbitFocusTarget()
                : anchor.add(right.scale(orbitPivotStage.x))
                .add(up.scale(orbitPivotStage.y)).add(forward.scale(orbitPivotStage.z));
        orbitFocusPivot = orbitPivotWorld;
        orbitFocusPivotFrom = orbitPivotWorld;
        orbitFocusPivotInitialized = true;
        orbitFocusTransitionPending = false;
        orbitFocusTransitionStart = 0;
        orbitStartOffset = cameraPosition.subtract(orbitPivotWorld);
        // A zero radius cannot describe an orbit. Nudge backward by one block so
        // malformed/manual events stay visible and deterministic.
        if (orbitStartOffset.lengthSqr() < 1.0e-8) orbitStartOffset = forward.scale(-1);
        orbitStartRoll = roll;
        long now = GameplayClock.now();
        updateRotationEvent(now);
        updateViewRotationEvent(now);
        // CameraMixin has already applied both layers to the captured roll.
        // Remove the view layer here; orbitPose adds its live value exactly once.
        orbitStartRoll -= (float) viewRotationCurrent.z;
        orbitStartRotationOffset = rotationEventCurrent;
        orbitCapturePending = false;
    }

    /** Current absolute orbit pose, or null while normal/free camera owns the view. */
    public static OrbitPose orbitPose() {
        if (!orbitActive || orbitCapturePending || freeCamEngaged) return null;
        // Orbit bypasses worldOffset(), but beat zoom and sing nudges still need
        // their normal per-frame decay or each beat accumulates indefinitely.
        double dt = frameDeltaSeconds();
        decayTransientEffects(dt);
        long now = GameplayClock.now();
        Vec3 pivot = updateOrbitFocusPivot(dt, now);
        updateRotationEvent(now);
        updateViewRotationEvent(now);
        Vec3 rotationDelta = rotationEventCurrent.subtract(orbitStartRotationOffset);
        Vec3 orbitalOffset = orbitStartOffset;
        // Rotation 3D values are extrinsic stage rotations: X around right,
        // Y around up, then Z around forward.
        orbitalOffset = rotateAxis(orbitalOffset, orbitRightWorld,
                Math.toRadians(rotationDelta.x));
        orbitalOffset = rotateAxis(orbitalOffset, orbitUpWorld,
                Math.toRadians(rotationDelta.y));
        orbitalOffset = rotateAxis(orbitalOffset, orbitForwardWorld,
                Math.toRadians(rotationDelta.z));
        Vec3 position = pivot.add(orbitalOffset);
        float yaw;
        float pitch;
        float roll;
        Vec3 direction = pivot.subtract(position);
        if (direction.lengthSqr() < 1.0e-10) direction = new Vec3(0, 0, 1);
        direction = direction.normalize();
        yaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z))
                + (float) viewRotationCurrent.y;
        pitch = (float) Math.toDegrees(-Math.asin(Math.max(-1, Math.min(1, direction.y))))
                + (float) viewRotationCurrent.x;
        roll = orbitStartRoll + (float) viewRotationCurrent.z;
        return new OrbitPose(position, yaw, pitch, roll);
    }

    /**
     * Smooths a live Camera Focus target exactly as the normal Minecraft/Legacy
     * camera does. Camera Behavior decides whether sing/idle camera nudges are
     * included in the live pivot.
     */
    private static Vec3 updateOrbitFocusPivot(double dt, long now) {
        Vec3 target = orbitPivotTarget();
        if (!orbitFocusPivotInitialized) {
            orbitFocusPivot = orbitFocusPivotFrom = target;
            orbitFocusPivotInitialized = true;
            return target;
        }
        if (orbitFocusTransitionPending) {
            orbitFocusPivotFrom = orbitFocusPivot;
            orbitFocusTransitionStart = now;
            orbitFocusTransitionPending = false;
        }
        double t = orbitFocusTransitionStart == 0 ? 1
                : (now - orbitFocusTransitionStart) / orbitFocusTransitionDurationMs;
        if (t < 1) {
            orbitFocusPivot = orbitFocusPivotFrom.lerp(target,
                    Easing.apply(orbitFocusTransitionEase, Math.max(0, t)));
        } else {
            orbitFocusTransitionStart = 0;
            float follow = "constant".equals(cameraEase) ? 1f
                    : (float) Math.min(1, dt * 6 * cameraSpeed);
            orbitFocusPivot = orbitFocusPivot.lerp(target, follow);
        }
        return orbitFocusPivot;
    }

    private static Vec3 orbitPivotTarget() {
        return orbitUseFocusPivot ? orbitFocusTarget()
                : anchor.add(orbitRightWorld.scale(orbitPivotStage.x))
                .add(orbitUpWorld.scale(orbitPivotStage.y))
                .add(orbitForwardWorld.scale(orbitPivotStage.z));
    }

    private static Vec3 orbitFocusTarget() {
        Vec3 target = focusWorldPos().add(orbitRightWorld.scale(orbitPivotStage.x))
                .add(orbitUpWorld.scale(orbitPivotStage.y))
                .add(orbitForwardWorld.scale(orbitPivotStage.z));
        if (!animationCameraOffsetsEnabled || customFocusPos != null) return target;
        float nudgeX = focusPlayer ? pNudgeX : oNudgeX;
        float nudgeY = focusPlayer ? pNudgeY : oNudgeY;
        return target.add(orbitRightWorld.scale(nudgeX)).add(orbitUpWorld.scale(nudgeY));
    }

    private static void beginOrbitFocusTransition(String easeName, double durationMs) {
        if (!orbitActive || orbitCapturePending || !orbitUseFocusPivot
                || !orbitFocusPivotInitialized) return;
        orbitFocusTransitionEase = normalizeCameraEase(easeName);
        orbitFocusTransitionDurationMs = Math.max(1, durationMs);
        String normalized = Easing.normalize(orbitFocusTransitionEase);
        if (normalized.equals("constant") || normalized.equals("snap")) {
            orbitFocusPivot = orbitFocusTarget();
            orbitFocusTransitionPending = false;
            orbitFocusTransitionStart = 0;
        } else {
            orbitFocusTransitionPending = true;
            orbitFocusTransitionStart = 0;
        }
    }

    private static Vec3 rotateAxis(Vec3 vector, Vec3 axis, double radians) {
        Vec3 unit = axis.normalize();
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return vector.scale(cos).add(unit.cross(vector).scale(sin))
                .add(unit.scale(unit.dot(vector) * (1 - cos)));
    }

    private static double frameDeltaSeconds() {
        long now = System.nanoTime();
        double dt = Math.min(0.1, Math.max(0, (now - lastFrameNano) / 1_000_000_000.0));
        lastFrameNano = now;
        return dt;
    }

    private static void decayTransientEffects(double dt) {
        float decay = (float) Math.exp(-dt * 2.5);
        pNudgeX *= decay;
        pNudgeY *= decay;
        oNudgeX *= decay;
        oNudgeY *= decay;

        float zoomDecay = playbackMode == PlaybackMode.FNF ? 3.125f : 3.5f;
        beatZoom *= (float) Math.exp(-dt * zoomDecay);
        hudBeatZoom *= (float) Math.exp(-dt * zoomDecay);
    }

    private static CameraType previousCameraType;
    private static boolean cameraTypeChanged;

    private GameplayCamera() {}

    public static void begin(Vec3 anchorPos, float fixedStageYaw, Supplier<Vec3> cameraEntity,
                             Supplier<Vec3> playerSide, Supplier<Vec3> opponentSide,
                             float[] playerBase, float[] opponentBase, PlaybackMode mode) {
        anchor = anchorPos;
        stageViewYaw = fixedStageYaw;
        cameraEntityPos = cameraEntity;
        playerSidePos = playerSide;
        opponentSidePos = opponentSide;
        customFocusPos = null;
        customFocusBaseX = customFocusBaseY = 0;
        playbackMode = mode == null ? PlaybackMode.MINECRAFT : mode;
        playerBaseX = playerBase[0];
        playerBaseY = playerBase[1];
        oppBaseX = opponentBase[0];
        oppBaseY = opponentBase[1];
        focusPlayer = true;
        pNudgeX = pNudgeY = oNudgeX = oNudgeY = 0;
        beatZoom = 0;
        hudBeatZoom = 0;
        gameBopEnabled = true;
        hudBopEnabled = true;
        hudEventZoom = 0;
        baseGameZoom = 1f;
        eventZoom = eventZoomFrom = eventZoomTarget = 0;
        eventZoomStart = 0;
        forcedFrame = false;
        positionEventActive = false;
        positionEventOverride = false;
        positionEventCameraRelative = false;
        positionEventFrom = positionEventCurrent = positionEventTarget = Vec3.ZERO;
        positionEventStart = 0;
        positionEventDurationMs = CAMERA_EVENT_DURATION_MS;
        rotationEventFrom = rotationEventCurrent = rotationEventTarget = Vec3.ZERO;
        rotationEventStart = 0;
        rotationEventDurationMs = CAMERA_EVENT_DURATION_MS;
        viewRotationFrom = viewRotationCurrent = viewRotationTarget = Vec3.ZERO;
        viewRotationStart = 0;
        viewRotationDurationMs = CAMERA_EVENT_DURATION_MS;
        gameShakeEnd = hudShakeEnd = 0;
        gameShakeIntensity = hudShakeIntensity = 0;
        CameraOverlay.reset();
        curOffset = fromOffset = Vec3.ZERO;
        detachedCameraBaselineWorld = Vec3.ZERO;
        lastNormalWorldOffset = Vec3.ZERO;
        offsetInitialized = false;
        transStart = 0;
        transPendingFirstSample = false;
        lastFrameNano = System.nanoTime();
        cameraSpeed = DEFAULT_CAMERA_SPEED;
        cameraEase = "smooth";
        animationCameraOffsetsEnabled = true;
        stopOrbit();
        active = true;

        Minecraft mc = Minecraft.getInstance();
        previousCameraType = mc.options.getCameraType();
        // characters face the camera (away from the machine), so front view looks back at them
        mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
        cameraTypeChanged = true;
    }

    public static void end() {
        if (!active) return;
        stopOrbit();
        active = false;
        if (cameraTypeChanged) {
            Minecraft.getInstance().options.setCameraType(previousCameraType);
            cameraTypeChanged = false;
        }
    }

    public static boolean isActive() {
        return active;
    }

    /** Clears effects that belong to one song run without replacing the camera session. */
    public static void resetSongState() {
        if (!active) return;
        focusPlayer = true;
        customFocusPos = null;
        customFocusBaseX = customFocusBaseY = 0;
        pNudgeX = pNudgeY = oNudgeX = oNudgeY = 0;
        beatZoom = 0;
        hudBeatZoom = 0;
        gameBopEnabled = true;
        hudBopEnabled = true;
        hudEventZoom = 0;
        eventZoom = eventZoomFrom = eventZoomTarget = 0;
        eventZoomStart = 0;
        forcedFrame = false;
        positionEventActive = false;
        positionEventOverride = false;
        positionEventCameraRelative = false;
        positionEventFrom = positionEventCurrent = positionEventTarget = Vec3.ZERO;
        positionEventStart = 0;
        positionEventDurationMs = CAMERA_EVENT_DURATION_MS;
        rotationEventFrom = rotationEventCurrent = rotationEventTarget = Vec3.ZERO;
        rotationEventStart = 0;
        rotationEventDurationMs = CAMERA_EVENT_DURATION_MS;
        viewRotationFrom = viewRotationCurrent = viewRotationTarget = Vec3.ZERO;
        viewRotationStart = 0;
        viewRotationDurationMs = CAMERA_EVENT_DURATION_MS;
        gameShakeEnd = hudShakeEnd = 0;
        gameShakeIntensity = hudShakeIntensity = 0;
        CameraOverlay.reset();
        curOffset = fromOffset = Vec3.ZERO;
        detachedCameraBaselineWorld = Vec3.ZERO;
        lastNormalWorldOffset = Vec3.ZERO;
        offsetInitialized = false;
        transStart = 0;
        transPendingFirstSample = false;
        lastFrameNano = System.nanoTime();
        cameraSpeed = DEFAULT_CAMERA_SPEED;
        cameraEase = "smooth";
        animationCameraOffsetsEnabled = true;
        stopOrbit();
    }

    /** Fixed entity yaw used to build the detached front camera during songs. */
    public static float stageViewYaw() {
        return stageViewYaw;
    }

    /** Switch camera focus (called on chart section changes). */
    public static void focus(boolean player, String easeName, double durationMs) {
        if (!active || player == focusPlayer && customFocusPos == null) return;
        focusPlayer = player;
        customFocusPos = null;
        customFocusBaseX = customFocusBaseY = 0;
        beginFocusTransition(easeName, durationMs);
    }

    /** Focuses an arbitrary world performer while continuing to track its live position. */
    public static void focusAt(Supplier<Vec3> position, float baseX, float baseY,
                               String easeName, double durationMs) {
        if (!active || position == null) return;
        customFocusPos = position;
        customFocusBaseX = Float.isFinite(baseX) ? baseX : 0;
        customFocusBaseY = Float.isFinite(baseY) ? baseY : 0;
        beginFocusTransition(easeName, durationMs);
    }

    private static void beginFocusTransition(String easeName, double durationMs) {
        beginOrbitFocusTransition(easeName, durationMs);
        if (playbackMode == PlaybackMode.FNF) {
            // Psych changes the target immediately and lets followLerp perform
            // the entire transition instead of starting a separate fixed tween.
            transStart = 0;
            transPendingFirstSample = false;
            return;
        }
        fromOffset = curOffset;
        ease = easeName == null ? "smooth" : easeName;
        transDurMs = Math.max(50, durationMs);
        // The focus change happens after the world camera was rendered for the
        // current frame. Starting the clock here makes the next rendered sample
        // jump several milliseconds into the curve. Defer non-instant starts so
        // the first camera sample is exactly the previously displayed position.
        String normalizedEase = Easing.normalize(ease);
        transPendingFirstSample = !normalizedEase.equals("constant")
                && !normalizedEase.equals("snap");
        transStart = transPendingFirstSample ? 0 : GameplayClock.now();
    }

    /** Must-Hit section focus using the active Camera Behavior event settings. */
    public static void focusSection(boolean player, double defaultDurationMs) {
        focus(player, cameraEase, defaultDurationMs / Math.max(0.01f, cameraSpeed));
    }

    /** Legacy/Minecraft only. Empty values restore normal behavior and animation offsets. */
    public static void setCameraBehavior(String speedValue, String easeValue, String offsetsValue) {
        if (!active || playbackMode == PlaybackMode.FNF) return;
        String rawSpeed = speedValue == null ? "" : speedValue.trim();
        String rawEase = easeValue == null ? "" : easeValue.trim();
        String rawOffsets = offsetsValue == null ? "" : offsetsValue.trim();
        if (rawSpeed.isEmpty() && rawEase.isEmpty() && rawOffsets.isEmpty()) {
            cameraSpeed = DEFAULT_CAMERA_SPEED;
            cameraEase = "smooth";
            animationCameraOffsetsEnabled = true;
        } else {
            float parsed = DEFAULT_CAMERA_SPEED;
            try { parsed = Float.parseFloat(rawSpeed); }
            catch (Exception ignored) {}
            cameraSpeed = Math.max(0.01f, Math.min(100f,
                    Float.isFinite(parsed) ? parsed : DEFAULT_CAMERA_SPEED));
            cameraEase = normalizeCameraEase(rawEase);
            animationCameraOffsetsEnabled = !rawOffsets.equalsIgnoreCase("false")
                    && !rawOffsets.equalsIgnoreCase("off")
                    && !rawOffsets.equalsIgnoreCase("no")
                    && !rawOffsets.equals("0")
                    && !rawOffsets.equalsIgnoreCase("disabled");
        }
        fromOffset = curOffset;
        ease = cameraEase;
        transDurMs = Math.max(1, 500.0 / cameraSpeed);
        transStart = GameplayClock.now();
    }

    public static String normalizeCameraEase(String value) {
        if (value == null || value.isBlank()) return "smooth";
        if (value.equalsIgnoreCase("snap")) return "constant"; // old chart alias
        String normalized = Easing.normalize(value);
        for (String candidate : EASES) {
            if (normalized.startsWith(candidate)) return value.trim();
        }
        return "smooth";
    }

    /** Nudge a side's camera center for the animation that just played. */
    public static void sing(boolean playerSide, float offX, float offY) {
        if (!active) return;
        if (playerSide) {
            pNudgeX = offX;
            pNudgeY = offY;
        } else {
            oNudgeX = offX;
            oNudgeY = offY;
        }
    }

    /** Replaces one side's character.json base offset after Change Character. */
    public static void setBaseOffset(boolean playerSide, float[] offset) {
        if (offset == null || offset.length < 2) return;
        if (playerSide) {
            playerBaseX = offset[0];
            playerBaseY = offset[1];
        } else {
            oppBaseX = offset[0];
            oppBaseY = offset[1];
        }
    }

    /** FNF-style beat zoom bump; decays over the following beats. */
    public static void bumpZoom(float amount) {
        if (!active) return;
        if (gameBopEnabled) {
            beatZoom = Math.min(0.25f, beatZoom + amount);
        }
        if (hudBopEnabled) {
            hudBeatZoom = Math.min(0.5f, hudBeatZoom + amount * 2f);
        }
    }

    /**
     * Psych's Add Camera Zoom skips when the camera is already zoomed in past its
     * limit. That cap belongs to the beat impulse alone; the persistent Camera
     * Zoom (base/event) must not disable the impulse, or the two events conflict.
     */
    public static boolean canBumpZoom() {
        return active && beatZoom < 0.35f;
    }

    /** Psych Add Camera Zoom impulse. Explicit events ignore automatic-bop toggles. */
    public static void addZoomImpulse(float gameAmount, float hudAmount) {
        if (!active) return;
        if (Float.isFinite(gameAmount)) beatZoom = Math.max(-0.5f, Math.min(0.5f, beatZoom + gameAmount));
        if (Float.isFinite(hudAmount)) hudBeatZoom = Math.max(-0.75f, Math.min(0.75f, hudBeatZoom + hudAmount));
    }

    /** Psych two-value behavior retained for old charts/editor previews. */
    public static void forceFramePosition(Double x, Double y) {
        forceFramePosition(x, y, null, "", false, false, false);
    }

    /**
     * Camera Follow Pos. Extended values are stage-local block offsets:
     * X right, Y up, Z forward. By default they are aligned to the Funkin'
     * Machine facing so Camera Rotation 3D does not skew them; cameraRelative
     * aligns them to the current camera rotation instead. Override locks
     * tracking to the speakers anchor.
     */
    public static void forceFramePosition(Double x, Double y, Double z, String easing,
                                          boolean overrideMovement, boolean cameraRelative,
                                          boolean extended) {
        forceFramePosition(x, y, z, easing, overrideMovement, cameraRelative, extended, null);
    }

    public static void forceFramePosition(Double x, Double y, Double z, String easing,
                                          boolean overrideMovement, boolean cameraRelative,
                                          boolean extended, Double durationSeconds) {
        if (!active) return;
        // Empty/non-positive duration keeps the default; otherwise the move takes
        // exactly the requested seconds, eased by the chosen curve.
        positionEventDurationMs = durationSeconds != null && durationSeconds > 0
                ? durationSeconds * 1000.0 : CAMERA_EVENT_DURATION_MS;
        if (!extended) {
            positionEventActive = false;
            positionEventOverride = false;
            positionEventCameraRelative = false;
            positionEventCurrent = positionEventFrom = positionEventTarget = Vec3.ZERO;
            positionEventStart = 0;
            if (x == null && y == null) {
                forcedFrame = false;
                return;
            }
            double px = x == null ? 0 : x;
            double py = y == null ? 0 : y;
            forcedFrameX = (float) ((px - 640.0) / 128.0);
            // Minecraft's world-up axis is positive; the previous conversion
            // inverted Camera Follow Pos vertically in the detached camera.
            forcedFrameY = (float) ((py - 360.0) / 128.0);
            forcedFrame = true;
            return;
        }

        forcedFrame = false;
        updatePositionEvent(GameplayClock.now());
        positionEventFrom = positionEventCurrent;
        positionEventTarget = new Vec3(finite(x), finite(y), finite(z));
        positionEventEase = normalizeCameraEase(easing);
        positionEventOverride = overrideMovement;
        positionEventCameraRelative = cameraRelative;
        positionEventStart = GameplayClock.now();
        positionEventActive = overrideMovement || !positionEventTarget.equals(Vec3.ZERO)
                || !positionEventCurrent.equals(Vec3.ZERO);
        if (!positionEventActive) {
            positionEventOverride = false;
            positionEventStart = 0;
        }
    }

    /**
     * X=pitch, Y=yaw, Z=roll. The default layer drives normal rotation or
     * orbital position; cameraViewLayer always changes view orientation only.
     * Empty XYZ eases the selected layer back to zero.
     */
    public static void rotateTo(Double pitch, Double yaw, Double roll, String easing,
                                Double durationSeconds, boolean cameraViewLayer) {
        if (!active) return;
        long now = GameplayClock.now();
        if (cameraViewLayer) {
            updateViewRotationEvent(now);
            viewRotationFrom = viewRotationCurrent;
            viewRotationTarget = new Vec3(finite(pitch), finite(yaw), finite(roll));
            viewRotationEase = normalizeCameraEase(easing);
            viewRotationDurationMs = durationSeconds != null && durationSeconds > 0
                    ? durationSeconds * 1000.0 : CAMERA_EVENT_DURATION_MS;
            viewRotationStart = now;
        } else {
            updateRotationEvent(now);
            rotationEventFrom = rotationEventCurrent;
            rotationEventTarget = new Vec3(finite(pitch), finite(yaw), finite(roll));
            rotationEventEase = normalizeCameraEase(easing);
            rotationEventDurationMs = durationSeconds != null && durationSeconds > 0
                    ? durationSeconds * 1000.0 : CAMERA_EVENT_DURATION_MS;
            rotationEventStart = now;
        }
    }

    /** Additive pitch/yaw/roll applied by CameraMixin after vanilla setup. */
    public static Vec3 rotationOffset() {
        if (!active) return Vec3.ZERO;
        if (freeCamEngaged && freeCamInitialized) {
            return new Vec3(freePitch, freeYaw, freeRoll);
        }
        long now = GameplayClock.now();
        updateRotationEvent(now);
        updateViewRotationEvent(now);
        return rotationEventCurrent.add(viewRotationCurrent);
    }

    private static double finite(Double value) {
        return value != null && Double.isFinite(value) ? value : 0;
    }

    private static void updatePositionEvent(long nowMs) {
        if (positionEventStart == 0) return;
        double t = (nowMs - positionEventStart) / positionEventDurationMs;
        if (t >= 1) {
            positionEventCurrent = positionEventTarget;
            positionEventStart = 0;
            if (positionEventTarget.equals(Vec3.ZERO) && !positionEventOverride) {
                positionEventActive = false;
            }
        } else {
            positionEventCurrent = positionEventFrom.lerp(positionEventTarget,
                    easeF(positionEventEase, t));
        }
    }

    private static void updateRotationEvent(long nowMs) {
        if (rotationEventStart == 0) return;
        double t = (nowMs - rotationEventStart) / rotationEventDurationMs;
        if (t >= 1) {
            rotationEventCurrent = rotationEventTarget;
            rotationEventStart = 0;
        } else {
            rotationEventCurrent = rotationEventFrom.lerp(rotationEventTarget,
                    easeF(rotationEventEase, t));
        }
    }

    private static void updateViewRotationEvent(long nowMs) {
        if (viewRotationStart == 0) return;
        double t = (nowMs - viewRotationStart) / viewRotationDurationMs;
        if (t >= 1) {
            viewRotationCurrent = viewRotationTarget;
            viewRotationStart = 0;
        } else {
            viewRotationCurrent = viewRotationFrom.lerp(viewRotationTarget,
                    easeF(viewRotationEase, t));
        }
    }

    public static void shake(double gameDuration, double gameIntensity,
                             double hudDuration, double hudIntensity) {
        long now = GameplayClock.now();
        if (gameDuration > 0 && gameIntensity != 0) {
            gameShakeEnd = now + (long) (gameDuration * 1000);
            gameShakeIntensity = (float) Math.abs(gameIntensity);
        }
        if (hudDuration > 0 && hudIntensity != 0) {
            hudShakeEnd = now + (long) (hudDuration * 1000);
            hudShakeIntensity = (float) Math.abs(hudIntensity);
        }
    }

    public static float gameShakeX() { return shakeAxis(gameShakeEnd, gameShakeIntensity, 1280, 0.031); }
    public static float gameShakeY() { return shakeAxis(gameShakeEnd, gameShakeIntensity, 720, 0.043); }
    public static float hudShakeX(int width) { return shakeAxis(hudShakeEnd, hudShakeIntensity, width, 0.037); }
    public static float hudShakeY(int height) { return shakeAxis(hudShakeEnd, hudShakeIntensity, height, 0.047); }

    private static float shakeAxis(long end, float intensity, int span, double frequency) {
        long now = GameplayClock.now();
        if (now >= end || intensity <= 0) return 0;
        return (float) (Math.sin(now * frequency) * intensity * span);
    }

    /** Controls automatic beat impulses without snapping an in-progress bop. */
    public static void setBopEnabled(String camera, boolean enabled) {
        String target = camera == null ? "both" : camera.trim().toLowerCase();
        if (target.equals("game") || target.equals("camgame") || target.equals("minecraft")
                || target.equals("world") || target.equals("both") || target.equals("all")) {
            gameBopEnabled = enabled;
        }
        if (target.equals("hud") || target.equals("camhud")
                || target.equals("both") || target.equals("all")) {
            hudBopEnabled = enabled;
        }
    }

    public static boolean bopEnabled(String camera) {
        String target = camera == null ? "both" : camera.trim().toLowerCase();
        if (target.equals("hud") || target.equals("camhud")) return hudBopEnabled;
        if (target.equals("both") || target.equals("all")) return gameBopEnabled && hudBopEnabled;
        return gameBopEnabled;
    }

    /** Tweens to a persistent FOV zoom offset over the default duration. */
    public static void zoomTo(float amount, String easeName) {
        zoomTo(amount, 0, easeName);
    }

    /**
     * Tweens to a persistent FOV zoom offset over durationMs. Zero restores the
     * default duration. Zero amount restores the normal zoom.
     */
    public static void zoomTo(float amount, double durationMs, String easeName) {
        if (!active || !Float.isFinite(amount)) return;
        updateEventZoom(GameplayClock.now());
        eventZoomFrom = eventZoom;
        eventZoomTarget = Math.max(-1f, Math.min(0.9f, amount));
        eventZoomEase = easeName == null ? "smooth" : easeName.trim().toLowerCase();
        eventZoomDurationMs = durationMs > 0 && Double.isFinite(durationMs)
                ? durationMs : EVENT_ZOOM_DURATION_MS;
        eventZoomStart = GameplayClock.now();
    }

    /** Multiplier applied to the FOV while active (smaller fov = zoomed in). */
    public static float fovScale() {
        if (!active) return 1f;
        updateEventZoom(GameplayClock.now());
        float zoom = freeCamEngaged && freeCamInitialized ? (float) freeZoom : eventZoom;
        return Math.max(0.1f, Math.min(2f, 1f - beatZoom - zoom));
    }

    /** Psych-compatible logical camGame zoom exposed to Lua and 2D game sprites. */
    public static float gameZoom() {
        if (!active) return 1f;
        updateEventZoom(GameplayClock.now());
        float zoom = freeCamEngaged && freeCamInitialized ? (float) freeZoom : eventZoom;
        return Math.max(0.1f, baseGameZoom + beatZoom + zoom);
    }

    public static void setBaseGameZoom(float zoom) {
        if (!active || !Float.isFinite(zoom)) return;
        baseGameZoom = Math.max(0.1f, Math.min(4f, zoom));
    }

    /** Sets Psych camGame.zoom as an absolute value. */
    public static void setGameZoom(float zoom) {
        if (!active || !Float.isFinite(zoom)) return;
        zoomTo(zoom - baseGameZoom, "snap");
    }

    /** Psych-compatible camHUD zoom, also used by Legacy and Minecraft HUDs. */
    public static float hudZoom() {
        return hudZoom(true);
    }

    /** HUD styles outside FNF can keep explicit event/Lua zoom without automatic beat bop. */
    public static float hudZoom(boolean includeAutomaticBop) {
        return active ? Math.max(0.1f, 1f + (includeAutomaticBop ? hudBeatZoom : 0f) + hudEventZoom) : 1f;
    }

    public static void setHudZoom(float zoom) {
        setHudZoom(zoom, true);
    }

    public static void setHudZoom(float zoom, boolean includeAutomaticBop) {
        if (!active || !Float.isFinite(zoom)) return;
        hudEventZoom = Math.max(-0.9f, Math.min(1f,
                zoom - 1f - (includeAutomaticBop ? hudBeatZoom : 0f)));
    }

    public static boolean usesPsychProfile() {
        return active && playbackMode == PlaybackMode.FNF;
    }

    private static float easeF(double t) {
        return easeF(ease, t);
    }

    private static float easeF(String easeName, double t) {
        String resolved = easeName == null ? "smooth" : easeName.trim();
        // Preserve the old unsuffixed camera expo as ease-out. Explicit
        // expoIn/expoOut/expoInOut values use the shared Psych implementation.
        if (resolved.equalsIgnoreCase("expo")) resolved = "expoOut";
        return (float) Easing.apply(resolved, t);
    }

    private static void updateEventZoom(long nowMs) {
        if (eventZoomStart == 0) return;
        double t = (nowMs - eventZoomStart) / eventZoomDurationMs;
        if (t >= 1) {
            eventZoom = eventZoomTarget;
            eventZoomStart = 0;
        } else {
            float f = easeF(eventZoomEase, t);
            eventZoom = eventZoomFrom + (eventZoomTarget - eventZoomFrom) * f;
        }
    }

    /**
     * Called from the Camera mixin every frame. Returns the world-space offset
     * to add to the camera position. Character tracking uses all three world
     * axes; configured camera offsets and animation nudges use screen right/up.
     */
    public static Vec3 worldOffset(Vector3f leftVec, Vector3f upVec, Vector3f forwardVec,
                                   Vector3f stageLeftVec, Vector3f stageUpVec, Vector3f stageForwardVec) {
        if (!active) return null;

        double dt = frameDeltaSeconds();

        // Free camera fully overrides the follow: sit at anchor + the stage-frame
        // offset (exactly like a Camera Follow Pos override), ignoring tracking.
        if (freeCamEngaged && freeCamInitialized) {
            Vector3f sLeft = stageLeftVec == null ? leftVec : stageLeftVec;
            Vector3f sUp = stageUpVec == null ? upVec : stageUpVec;
            Vector3f sForward = stageForwardVec == null ? forwardVec : stageForwardVec;
            double rx = -sLeft.x(), ry = -sLeft.y(), rz = -sLeft.z();
            double ux = sUp.x(), uy = sUp.y(), uz = sUp.z();
            double fx = sForward.x(), fy = sForward.y(), fz = sForward.z();
            Vec3 base = cameraEntityPos == null ? anchor : cameraEntityPos.get();
            if (base == null) base = anchor;
            Vec3 world = new Vec3(
                    rx * freeX + ux * freeY + fx * freeZ,
                    ry * freeX + uy * freeY + fy * freeZ,
                    rz * freeX + uz * freeY + fz * freeZ);
            return anchor.subtract(base).add(world);
        }

        decayTransientEffects(dt);

        // screen right = -left
        float rx = -leftVec.x(), ry = -leftVec.y(), rz = -leftVec.z();
        float ux = upVec.x(), uy = upVec.y(), uz = upVec.z();
        float fx = forwardVec.x(), fy = forwardVec.y(), fz = forwardVec.z();

        // Stage frame ignores Camera Rotation 3D so Follow Pos offsets can track
        // the Funkin' Machine facing. Falls back to the camera frame if absent.
        Vector3f stageLeft = stageLeftVec == null ? leftVec : stageLeftVec;
        Vector3f stageUp = stageUpVec == null ? upVec : stageUpVec;
        Vector3f stageForward = stageForwardVec == null ? forwardVec : stageForwardVec;
        float srx = -stageLeft.x(), sry = -stageLeft.y(), srz = -stageLeft.z();
        float sux = stageUp.x(), suy = stageUp.y(), suz = stageUp.z();
        float sfx = stageForward.x(), sfy = stageForward.y(), sfz = stageForward.z();

        Vec3 focusPos = null;
        Supplier<Vec3> sup = customFocusPos != null ? customFocusPos
                : focusPlayer ? playerSidePos : opponentSidePos;
        if (sup != null) focusPos = sup.get();
        if (focusPos == null) focusPos = anchor;
        Vec3 cameraBase = cameraEntityPos == null ? null : cameraEntityPos.get();
        if (cameraBase == null) cameraBase = anchor;

        // Minecraft's base camera already follows cameraBase. Subtracting its
        // current position prevents local movement from being counted twice.
        Vec3 characterDelta = focusPos.subtract(cameraBase);
        float frameX = forcedFrame ? forcedFrameX : customFocusPos != null ? customFocusBaseX
                : focusPlayer ? playerBaseX + (animationCameraOffsetsEnabled ? pNudgeX : 0)
                : oppBaseX + (animationCameraOffsetsEnabled ? oNudgeX : 0);
        float frameY = forcedFrame ? forcedFrameY : customFocusPos != null ? customFocusBaseY
                : focusPlayer ? playerBaseY + (animationCameraOffsetsEnabled ? pNudgeY : 0)
                : oppBaseY + (animationCameraOffsetsEnabled ? oNudgeY : 0);
        if (GameplayClock.now() < gameShakeEnd) {
            frameX += gameShakeX() / 128.0f;
            frameY += gameShakeY() / 128.0f;
        }
        Vec3 targetOffset = characterDelta.add(
                rx * frameX + ux * frameY,
                ry * frameX + uy * frameY,
                rz * frameX + uz * frameY);
        updatePositionEvent(GameplayClock.now());
        Vec3 eventWorld = Vec3.ZERO;
        if (positionEventActive) {
            // Default aligns the offset to the machine facing (stage frame);
            // cameraRelative aligns it to the current, possibly rotated, camera.
            float brx = positionEventCameraRelative ? rx : srx;
            float bry = positionEventCameraRelative ? ry : sry;
            float brz = positionEventCameraRelative ? rz : srz;
            float bux = positionEventCameraRelative ? ux : sux;
            float buy = positionEventCameraRelative ? uy : suy;
            float buz = positionEventCameraRelative ? uz : suz;
            float bfx = positionEventCameraRelative ? fx : sfx;
            float bfy = positionEventCameraRelative ? fy : sfy;
            float bfz = positionEventCameraRelative ? fz : sfz;
            eventWorld = new Vec3(
                    brx * positionEventCurrent.x + bux * positionEventCurrent.y + bfx * positionEventCurrent.z,
                    bry * positionEventCurrent.x + buy * positionEventCurrent.y + bfy * positionEventCurrent.z,
                    brz * positionEventCurrent.x + buz * positionEventCurrent.y + bfz * positionEventCurrent.z);
            if (positionEventOverride) targetOffset = anchor.subtract(cameraBase);
        }

        if (!offsetInitialized) {
            curOffset = fromOffset = targetOffset;
            offsetInitialized = true;
            transPendingFirstSample = false;
            transStart = 0;
        } else if (transPendingFirstSample) {
            transStart = GameplayClock.now();
            transPendingFirstSample = false;
            curOffset = fromOffset;
        }

        double t = transStart == 0 ? 1 : (GameplayClock.now() - transStart) / transDurMs;
        if (t < 1) {
            float f = easeF(t);
            curOffset = fromOffset.lerp(targetOffset, f);
        } else {
            // FNF-style continuous follow once the focus transition is done
            float follow = playbackMode == PlaybackMode.FNF
                    ? (float) (1.0 - Math.exp(-dt * 2.45))
                    : "constant".equals(cameraEase) ? 1f
                    : (float) Math.min(1, dt * 6 * cameraSpeed);
            curOffset = curOffset.lerp(targetOffset, follow);
        }

        // Keep event easing independent from normal follow smoothing. This makes
        // Constant truly snap while default tracking retains its own movement.
        Vec3 result = curOffset.add(eventWorld);
        lastNormalWorldOffset = result;
        return result;
    }
}
