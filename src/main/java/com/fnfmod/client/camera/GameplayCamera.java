package com.fnfmod.client.camera;

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

    public static final String[] EASES = {"smooth", "expo", "linear", "constant"};
    private static final float DEFAULT_CAMERA_SPEED = 1f;

    private static boolean active;
    private static PlaybackMode playbackMode = PlaybackMode.LEGACY;
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
    private static float hudBeatZoom;
    /** Disabling blocks future beat impulses; existing impulse still eases out. */
    private static boolean gameBopEnabled = true;
    private static boolean hudBopEnabled = true;
    private static float hudEventZoom;
    private static float baseGameZoom = 1f;
    private static boolean forcedFrame;
    private static float forcedFrameX, forcedFrameY;
    private static long gameShakeEnd, hudShakeEnd;
    private static float gameShakeIntensity, hudShakeIntensity;
    // persistent event zoom, tweened to a target over a fixed short transition
    private static float eventZoom, eventZoomFrom, eventZoomTarget;
    private static long eventZoomStart;
    private static String eventZoomEase = "smooth";
    private static final double EVENT_ZOOM_DURATION_MS = 500.0;
    private static long lastFrameNano;
    private static float cameraSpeed = DEFAULT_CAMERA_SPEED;
    private static String cameraEase = "smooth";

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
        playbackMode = mode == null ? PlaybackMode.LEGACY : mode;
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
        gameShakeEnd = hudShakeEnd = 0;
        gameShakeIntensity = hudShakeIntensity = 0;
        curOffset = fromOffset = Vec3.ZERO;
        offsetInitialized = false;
        transStart = 0;
        lastFrameNano = System.nanoTime();
        cameraSpeed = DEFAULT_CAMERA_SPEED;
        cameraEase = "smooth";
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
        hudBeatZoom = 0;
        gameBopEnabled = true;
        hudBopEnabled = true;
        hudEventZoom = 0;
        eventZoom = eventZoomFrom = eventZoomTarget = 0;
        eventZoomStart = 0;
        forcedFrame = false;
        gameShakeEnd = hudShakeEnd = 0;
        gameShakeIntensity = hudShakeIntensity = 0;
        curOffset = fromOffset = Vec3.ZERO;
        offsetInitialized = false;
        transStart = 0;
        lastFrameNano = System.nanoTime();
        cameraSpeed = DEFAULT_CAMERA_SPEED;
        cameraEase = "smooth";
    }

    /** Fixed entity yaw used to build the detached front camera during songs. */
    public static float stageViewYaw() {
        return stageViewYaw;
    }

    /** Switch camera focus (called on chart section changes). */
    public static void focus(boolean player, String easeName, double durationMs) {
        if (!active || player == focusPlayer) return;
        focusPlayer = player;
        if (playbackMode == PlaybackMode.FNF) {
            // Psych changes the target immediately and lets followLerp perform
            // the entire transition instead of starting a separate fixed tween.
            transStart = 0;
            return;
        }
        fromOffset = curOffset;
        ease = easeName == null ? "smooth" : easeName;
        transDurMs = Math.max(50, durationMs);
        transStart = System.currentTimeMillis();
    }

    /** Must-Hit section focus using the active Camera Behavior event settings. */
    public static void focusSection(boolean player, double defaultDurationMs) {
        focus(player, cameraEase, defaultDurationMs / Math.max(0.01f, cameraSpeed));
    }

    /** Legacy/Minecraft only. Empty values restore normal speed and smooth easing. */
    public static void setCameraBehavior(String speedValue, String easeValue) {
        if (!active || playbackMode == PlaybackMode.FNF) return;
        String rawSpeed = speedValue == null ? "" : speedValue.trim();
        String rawEase = easeValue == null ? "" : easeValue.trim();
        if (rawSpeed.isEmpty() && rawEase.isEmpty()) {
            cameraSpeed = DEFAULT_CAMERA_SPEED;
            cameraEase = "smooth";
        } else {
            float parsed = DEFAULT_CAMERA_SPEED;
            try { parsed = Float.parseFloat(rawSpeed); }
            catch (Exception ignored) {}
            cameraSpeed = Math.max(0.01f, Math.min(100f,
                    Float.isFinite(parsed) ? parsed : DEFAULT_CAMERA_SPEED));
            cameraEase = normalizeCameraEase(rawEase);
        }
        fromOffset = curOffset;
        ease = cameraEase;
        transDurMs = Math.max(1, 500.0 / cameraSpeed);
        transStart = System.currentTimeMillis();
    }

    private static String normalizeCameraEase(String value) {
        if (value == null || value.isBlank()) return "smooth";
        for (String candidate : EASES) {
            if (candidate.equalsIgnoreCase(value.trim())) return candidate;
        }
        if (value.equalsIgnoreCase("snap")) return "constant"; // old chart alias
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

    /** Psych Add Camera Zoom impulse. Explicit events ignore automatic-bop toggles. */
    public static void addZoomImpulse(float gameAmount, float hudAmount) {
        if (!active) return;
        if (Float.isFinite(gameAmount)) beatZoom = Math.max(-0.5f, Math.min(0.5f, beatZoom + gameAmount));
        if (Float.isFinite(hudAmount)) hudBeatZoom = Math.max(-0.75f, Math.min(0.75f, hudBeatZoom + hudAmount));
    }

    /** Psych Camera Follow Pos variation for Minecraft's 3D camera plane. */
    public static void forceFramePosition(Double x, Double y) {
        if (!active) return;
        if (x == null && y == null) {
            forcedFrame = false;
            return;
        }
        double px = x == null ? 0 : x;
        double py = y == null ? 0 : y;
        forcedFrameX = (float) ((px - 640.0) / 128.0);
        forcedFrameY = (float) ((360.0 - py) / 128.0);
        forcedFrame = true;
    }

    public static void shake(double gameDuration, double gameIntensity,
                             double hudDuration, double hudIntensity) {
        long now = System.currentTimeMillis();
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
        long now = System.currentTimeMillis();
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

    /** Psych-compatible logical camGame zoom exposed to Lua and 2D game sprites. */
    public static float gameZoom() {
        if (!active) return 1f;
        updateEventZoom(System.currentTimeMillis());
        return Math.max(0.1f, baseGameZoom + beatZoom + eventZoom);
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
        t = Math.max(0, Math.min(1, t));
        return (float) switch (easeName) {
            case "linear" -> t;
            case "constant", "snap" -> 1.0;
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
        float zoomDecay = playbackMode == PlaybackMode.FNF ? 3.125f : 3.5f;
        beatZoom *= (float) Math.exp(-dt * zoomDecay);
        hudBeatZoom *= (float) Math.exp(-dt * zoomDecay);

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
        float frameX = forcedFrame ? forcedFrameX
                : focusPlayer ? playerBaseX + pNudgeX : oppBaseX + oNudgeX;
        float frameY = forcedFrame ? forcedFrameY
                : focusPlayer ? playerBaseY + pNudgeY : oppBaseY + oNudgeY;
        if (System.currentTimeMillis() < gameShakeEnd) {
            frameX += gameShakeX() / 128.0f;
            frameY += gameShakeY() / 128.0f;
        }
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
            float follow = playbackMode == PlaybackMode.FNF
                    ? (float) (1.0 - Math.exp(-dt * 2.45))
                    : "constant".equals(cameraEase) ? 1f
                    : (float) Math.min(1, dt * 6 * cameraSpeed);
            curOffset = curOffset.lerp(targetOffset, follow);
        }

        return curOffset;
    }
}
