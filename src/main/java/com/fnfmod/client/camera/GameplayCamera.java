package com.fnfmod.client.camera;

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

    public static final String[] EASES = {"smooth", "expo", "linear", "snap"};

    private static boolean active;
    private static Vec3 anchor = Vec3.ZERO;
    /** Entity yaw the stage camera used before character.json rotation offsets. */
    private static float stageViewYaw;
    private static Supplier<Vec3> cameraEntityPos;
    private static Supplier<Vec3> playerSidePos;
    private static Supplier<Vec3> opponentSidePos;
    private static float playerBaseX, playerBaseY, oppBaseX, oppBaseY;

    private static boolean focusPlayer = true;
    private static Vec3 fromOffset = Vec3.ZERO;
    private static Vec3 curOffset = Vec3.ZERO;
    private static boolean offsetInitialized;
    private static long transStart;
    private static double transDurMs = 500;
    private static String ease = "smooth";

    // per-side sing nudges (decay over time)
    private static float pNudgeX, pNudgeY, oNudgeX, oNudgeY;
    // beat-hit zoom (FOV pinch that decays, like FNF's camZoom bump)
    private static float beatZoom;
    // persistent event zoom, tweened to a target over a fixed short transition
    private static float eventZoom, eventZoomFrom, eventZoomTarget;
    private static long eventZoomStart;
    private static String eventZoomEase = "smooth";
    private static final double EVENT_ZOOM_DURATION_MS = 500.0;
    private static long lastFrameNano;

    private static CameraType previousCameraType;
    private static boolean cameraTypeChanged;

    private GameplayCamera() {}

    public static void begin(Vec3 anchorPos, float fixedStageYaw, Supplier<Vec3> cameraEntity,
                             Supplier<Vec3> playerSide, Supplier<Vec3> opponentSide,
                             float[] playerBase, float[] opponentBase) {
        anchor = anchorPos;
        stageViewYaw = fixedStageYaw;
        cameraEntityPos = cameraEntity;
        playerSidePos = playerSide;
        opponentSidePos = opponentSide;
        playerBaseX = playerBase[0];
        playerBaseY = playerBase[1];
        oppBaseX = opponentBase[0];
        oppBaseY = opponentBase[1];
        focusPlayer = true;
        pNudgeX = pNudgeY = oNudgeX = oNudgeY = 0;
        beatZoom = 0;
        eventZoom = eventZoomFrom = eventZoomTarget = 0;
        eventZoomStart = 0;
        curOffset = fromOffset = Vec3.ZERO;
        offsetInitialized = false;
        transStart = 0;
        lastFrameNano = System.nanoTime();
        active = true;

        Minecraft mc = Minecraft.getInstance();
        previousCameraType = mc.options.getCameraType();
        // characters face the camera (away from the machine), so front view looks back at them
        mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
        cameraTypeChanged = true;
    }

    public static void end() {
        if (!active) return;
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
        pNudgeX = pNudgeY = oNudgeX = oNudgeY = 0;
        beatZoom = 0;
        eventZoom = eventZoomFrom = eventZoomTarget = 0;
        eventZoomStart = 0;
        curOffset = fromOffset = Vec3.ZERO;
        offsetInitialized = false;
        transStart = 0;
        lastFrameNano = System.nanoTime();
    }

    /** Fixed entity yaw used to build the detached front camera during songs. */
    public static float stageViewYaw() {
        return stageViewYaw;
    }

    /** Switch camera focus (called on chart section changes). */
    public static void focus(boolean player, String easeName, double durationMs) {
        if (!active || player == focusPlayer) return;
        focusPlayer = player;
        fromOffset = curOffset;
        ease = easeName == null ? "smooth" : easeName;
        transDurMs = Math.max(50, durationMs);
        transStart = System.currentTimeMillis();
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

    /** FNF-style beat zoom bump; decays over the following beats. */
    public static void bumpZoom(float amount) {
        if (!active) return;
        beatZoom = Math.min(0.25f, beatZoom + amount);
    }

    /** Tweens to a persistent FOV zoom offset. Zero restores the normal zoom. */
    public static void zoomTo(float amount, String easeName) {
        if (!active || !Float.isFinite(amount)) return;
        updateEventZoom(System.currentTimeMillis());
        eventZoomFrom = eventZoom;
        eventZoomTarget = Math.max(-1f, Math.min(0.9f, amount));
        eventZoomEase = easeName == null ? "smooth" : easeName.trim().toLowerCase();
        eventZoomStart = System.currentTimeMillis();
    }

    /** Multiplier applied to the FOV while active (smaller fov = zoomed in). */
    public static float fovScale() {
        if (!active) return 1f;
        updateEventZoom(System.currentTimeMillis());
        return Math.max(0.1f, Math.min(2f, 1f - beatZoom - eventZoom));
    }

    private static float easeF(double t) {
        return easeF(ease, t);
    }

    private static float easeF(String easeName, double t) {
        t = Math.max(0, Math.min(1, t));
        return (float) switch (easeName) {
            case "linear" -> t;
            case "snap" -> 1.0;
            case "expo" -> t >= 1 ? 1.0 : 1.0 - Math.pow(2, -10 * t);
            default -> t * t * (3 - 2 * t); // smoothstep
        };
    }

    private static void updateEventZoom(long nowMs) {
        if (eventZoomStart == 0) return;
        double t = (nowMs - eventZoomStart) / EVENT_ZOOM_DURATION_MS;
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
    public static Vec3 worldOffset(Vector3f leftVec, Vector3f upVec) {
        if (!active) return null;

        long now = System.nanoTime();
        double dt = Math.min(0.1, (now - lastFrameNano) / 1_000_000_000.0);
        lastFrameNano = now;

        // sing nudges relax back to 0
        float decay = (float) Math.exp(-dt * 2.5);
        pNudgeX *= decay; pNudgeY *= decay;
        oNudgeX *= decay; oNudgeY *= decay;
        // beat zoom eases back out
        beatZoom *= (float) Math.exp(-dt * 3.5);

        // screen right = -left
        float rx = -leftVec.x(), ry = -leftVec.y(), rz = -leftVec.z();
        float ux = upVec.x(), uy = upVec.y(), uz = upVec.z();

        Vec3 focusPos = null;
        Supplier<Vec3> sup = focusPlayer ? playerSidePos : opponentSidePos;
        if (sup != null) focusPos = sup.get();
        if (focusPos == null) focusPos = anchor;
        Vec3 cameraBase = cameraEntityPos == null ? null : cameraEntityPos.get();
        if (cameraBase == null) cameraBase = anchor;

        // Minecraft's base camera already follows cameraBase. Subtracting its
        // current position prevents local movement from being counted twice.
        Vec3 characterDelta = focusPos.subtract(cameraBase);
        float frameX = focusPlayer ? playerBaseX + pNudgeX : oppBaseX + oNudgeX;
        float frameY = focusPlayer ? playerBaseY + pNudgeY : oppBaseY + oNudgeY;
        Vec3 targetOffset = characterDelta.add(
                rx * frameX + ux * frameY,
                ry * frameX + uy * frameY,
                rz * frameX + uz * frameY);

        if (!offsetInitialized) {
            curOffset = fromOffset = targetOffset;
            offsetInitialized = true;
        }

        double t = transStart == 0 ? 1 : (System.currentTimeMillis() - transStart) / transDurMs;
        if (t < 1) {
            float f = easeF(t);
            curOffset = fromOffset.lerp(targetOffset, f);
        } else {
            // FNF-style continuous follow once the focus transition is done
            float follow = (float) Math.min(1, dt * 6);
            curOffset = curOffset.lerp(targetOffset, follow);
        }

        return curOffset;
    }
}
