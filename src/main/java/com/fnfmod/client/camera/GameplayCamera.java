package com.fnfmod.client.camera;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.function.Supplier;

/**
 * FNF-style camera: only pans in the camera's screen plane (X/Y), never moves
 * or rotates anything else. Characters are never repositioned — they stay on
 * the ground; the camera does all the work.
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
    private static Supplier<Vec3> playerSidePos;
    private static Supplier<Vec3> opponentSidePos;
    private static float playerBaseX, playerBaseY, oppBaseX, oppBaseY;

    private static boolean focusPlayer = true;
    private static float fromX, fromY;
    private static float curX, curY;
    private static long transStart;
    private static double transDurMs = 500;
    private static String ease = "smooth";

    // per-side sing nudges (decay over time)
    private static float pNudgeX, pNudgeY, oNudgeX, oNudgeY;
    // beat-hit zoom (FOV pinch that decays, like FNF's camZoom bump)
    private static float zoom;
    private static long lastFrameNano;

    private static CameraType previousCameraType;
    private static boolean cameraTypeChanged;

    private GameplayCamera() {}

    public static void begin(Vec3 anchorPos, Supplier<Vec3> playerSide, Supplier<Vec3> opponentSide,
                             float[] playerBase, float[] opponentBase) {
        anchor = anchorPos;
        playerSidePos = playerSide;
        opponentSidePos = opponentSide;
        playerBaseX = playerBase[0];
        playerBaseY = playerBase[1];
        oppBaseX = opponentBase[0];
        oppBaseY = opponentBase[1];
        focusPlayer = true;
        pNudgeX = pNudgeY = oNudgeX = oNudgeY = 0;
        curX = fromX = playerBaseX;
        curY = fromY = playerBaseY;
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

    /** Switch camera focus (called on chart section changes). */
    public static void focus(boolean player, String easeName, double durationMs) {
        if (!active || player == focusPlayer) return;
        focusPlayer = player;
        fromX = curX;
        fromY = curY;
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
        zoom = Math.min(0.25f, zoom + amount);
    }

    /** Multiplier applied to the FOV while active (smaller fov = zoomed in). */
    public static float fovScale() {
        return active ? 1f - zoom : 1f;
    }

    private static float easeF(double t) {
        t = Math.max(0, Math.min(1, t));
        return (float) switch (ease) {
            case "linear" -> t;
            case "snap" -> 1.0;
            case "expo" -> t >= 1 ? 1.0 : 1.0 - Math.pow(2, -10 * t);
            default -> t * t * (3 - 2 * t); // smoothstep
        };
    }

    /**
     * Called from the Camera mixin every frame. Returns the world-space offset
     * to add to the camera position, restricted to the camera's right/up plane.
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
        zoom *= (float) Math.exp(-dt * 3.5);

        // screen right = -left
        float rx = -leftVec.x(), ry = -leftVec.y(), rz = -leftVec.z();
        float ux = upVec.x(), uy = upVec.y(), uz = upVec.z();

        Vec3 focusPos = null;
        Supplier<Vec3> sup = focusPlayer ? playerSidePos : opponentSidePos;
        if (sup != null) focusPos = sup.get();
        if (focusPos == null) focusPos = anchor;
        Vec3 d = focusPos.subtract(anchor);

        float targetX = (float) (d.x * rx + d.y * ry + d.z * rz)
                + (focusPlayer ? playerBaseX + pNudgeX : oppBaseX + oNudgeX);
        float targetY = (float) (d.x * ux + d.y * uy + d.z * uz)
                + (focusPlayer ? playerBaseY + pNudgeY : oppBaseY + oNudgeY);

        double t = transStart == 0 ? 1 : (System.currentTimeMillis() - transStart) / transDurMs;
        if (t < 1) {
            float f = easeF(t);
            curX = fromX + (targetX - fromX) * f;
            curY = fromY + (targetY - fromY) * f;
        } else {
            // FNF-style continuous follow once the focus transition is done
            float follow = (float) Math.min(1, dt * 6);
            curX += (targetX - curX) * follow;
            curY += (targetY - curY) * follow;
        }

        return new Vec3(rx * curX + ux * curY, ry * curX + uy * curY, rz * curX + uz * curY);
    }
}
