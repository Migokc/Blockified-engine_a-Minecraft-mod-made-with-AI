package com.fnfmod.client.render;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.fnfmod.client.ClientOptions;
import com.fnfmod.mixin.WindowPsychCanvasAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/**
 * Applies Blockified's optional Psych-style 16:9 presentation.
 *
 * <p>This deliberately does <em>not</em> force a 1280x720 framebuffer or GUI
 * scale. 1280x720 defines the canvas's shape and authoring coordinates; the
 * game still renders at the window/monitor's native pixel resolution. Areas
 * outside that aspect-correct canvas are covered by opaque black letterboxing.
 */
public final class PsychResolutionController {
    private static final double ASPECT = PsychCanvas.WIDTH / (double) PsychCanvas.HEIGHT;
    private static boolean lastEnabled;
    private static int lastWindowWidth = -1;
    private static int lastWindowHeight = -1;
    private static double lastGuiScale = -1;
    private static boolean rendering;

    private PsychResolutionController() {}

    public static boolean enabled() {
        return ClientOptions.get().forcePsychResolution;
    }

    public static void toggle() {
        ClientOptions.get().forcePsychResolution = !enabled();
        ClientOptions.save();
        refresh();
    }

    /** Re-evaluates immediately; the client tick also catches window/fullscreen changes. */
    public static void refresh() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null) minecraft.execute(PsychResolutionController::update);
    }

    /**
     * Kept as the central refresh hook for option/world changes. The previous
     * implementation changed Window.guiScale here, which made all of Minecraft
     * smaller. Native resolution and the user's own GUI scale now remain intact.
     */
    public static void update() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getWindow() == null) return;
        Window window = minecraft.getWindow();
        boolean active = enabled();
        int width = rawWidth(window);
        int height = rawHeight(window);
        double guiScale = window.getGuiScale();
        if (active == lastEnabled && width == lastWindowWidth && height == lastWindowHeight
                && Math.abs(guiScale - lastGuiScale) < 0.0001) return;
        lastEnabled = active;
        lastWindowWidth = width;
        lastWindowHeight = height;
        lastGuiScale = guiScale;
        // Renderer-owned targets remain at the real framebuffer size. This is
        // required by Sodium/Iris and avoids their entity layers retaining a
        // different size from terrain. The 16:9 projection is restored by the
        // Window mixin and the final presentation compresses it back to shape.
        resizeMainTarget(minecraft, width, height);
        if (minecraft.gameRenderer != null) minecraft.gameRenderer.resize(width, height);
        if (minecraft.screen != null) {
            minecraft.screen.resize(minecraft, guiWidth(window), guiHeight(window));
        }
    }

    /** Native-pixel rectangle occupied by the centered 16:9 game canvas. */
    public static Bounds bounds(Window window) {
        int fullWidth = Math.max(1, rawWidth(window));
        int fullHeight = Math.max(1, rawHeight(window));
        if (!enabled()) return new Bounds(0, 0, fullWidth, fullHeight);
        if (fullWidth / (double) fullHeight > ASPECT) {
            int width = Math.max(1, (int) Math.round(fullHeight * ASPECT));
            return new Bounds((fullWidth - width) / 2, 0, width, fullHeight);
        }
        int height = Math.max(1, (int) Math.round(fullWidth / ASPECT));
        return new Bounds(0, (fullHeight - height) / 2, fullWidth, height);
    }

    public static int guiWidth(Window window) {
        if (!enabled()) return Mth.ceil(rawWidth(window) / window.getGuiScale());
        return Mth.ceil(bounds(window).width() / window.getGuiScale());
    }

    public static int guiHeight(Window window) {
        if (!enabled()) return Mth.ceil(rawHeight(window) / window.getGuiScale());
        return Mth.ceil(bounds(window).height() / window.getGuiScale());
    }

    public static int pixelWidth(Window window) {
        return bounds(window).width();
    }

    public static int pixelHeight(Window window) {
        return bounds(window).height();
    }

    /** Converts GLFW's whole-window cursor X into the centered canvas coordinate space. */
    public static double mapMouseX(double raw) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!enabled() || minecraft == null || minecraft.getWindow() == null) return raw;
        Window window = minecraft.getWindow();
        Bounds b = bounds(window);
        double screenWidth = Math.max(1, window.getScreenWidth());
        double pixelToScreen = screenWidth / Math.max(1.0, rawWidth(window));
        double offset = b.x() * pixelToScreen;
        double canvas = Math.max(1.0, b.width() * pixelToScreen);
        return (raw - offset) * screenWidth / canvas;
    }

    /** Converts GLFW's whole-window cursor Y into the centered canvas coordinate space. */
    public static double mapMouseY(double raw) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!enabled() || minecraft == null || minecraft.getWindow() == null) return raw;
        Window window = minecraft.getWindow();
        Bounds b = bounds(window);
        double screenHeight = Math.max(1, window.getScreenHeight());
        double pixelToScreen = screenHeight / Math.max(1.0, rawHeight(window));
        double offset = b.y() * pixelToScreen;
        double canvas = Math.max(1.0, b.height() * pixelToScreen);
        return (raw - offset) * screenHeight / canvas;
    }

    /** Inverse of {@link #mapMouseX(double)}, used when an editor deliberately warps the cursor. */
    public static double unmapMouseX(double mapped) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!enabled() || minecraft == null || minecraft.getWindow() == null) return mapped;
        Window window = minecraft.getWindow();
        Bounds b = bounds(window);
        double screenWidth = Math.max(1, window.getScreenWidth());
        double pixelToScreen = screenWidth / Math.max(1.0, rawWidth(window));
        double offset = b.x() * pixelToScreen;
        double canvas = Math.max(1.0, b.width() * pixelToScreen);
        return offset + mapped * canvas / screenWidth;
    }

    /** Inverse of {@link #mapMouseY(double)}, used when an editor deliberately warps the cursor. */
    public static double unmapMouseY(double mapped) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!enabled() || minecraft == null || minecraft.getWindow() == null) return mapped;
        Window window = minecraft.getWindow();
        Bounds b = bounds(window);
        double screenHeight = Math.max(1, window.getScreenHeight());
        double pixelToScreen = screenHeight / Math.max(1.0, rawHeight(window));
        double offset = b.y() * pixelToScreen;
        double canvas = Math.max(1.0, b.height() * pixelToScreen);
        return offset + mapped * canvas / screenHeight;
    }

    public static double mapMouseDeltaX(double delta) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!enabled() || minecraft == null || minecraft.screen == null) return delta;
        Window window = minecraft.getWindow();
        Bounds b = bounds(window);
        return delta * rawWidth(window) / Math.max(1.0, b.width());
    }

    public static double mapMouseDeltaY(double delta) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!enabled() || minecraft == null || minecraft.screen == null) return delta;
        Window window = minecraft.getWindow();
        Bounds b = bounds(window);
        return delta * rawHeight(window) / Math.max(1.0, b.height());
    }

    /** Binds Minecraft's normal full-window target using a virtual 16:9 projection. */
    public static void beginFrame(RenderTarget target, boolean setViewport) {
        if (!enabled()) {
            target.bindWrite(setViewport);
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getWindow() == null) {
            target.bindWrite(setViewport);
            return;
        }
        int rawWidth = rawWidth(minecraft.getWindow());
        int rawHeight = rawHeight(minecraft.getWindow());
        if (resizeMainTarget(minecraft, rawWidth, rawHeight) && minecraft.gameRenderer != null) {
            minecraft.gameRenderer.resize(rawWidth, rawHeight);
        }
        rendering = true;
        target.bindWrite(setViewport);
        RenderSystem.clearColor(0, 0, 0, 1);
        RenderSystem.clear(16640, Minecraft.ON_OSX);
    }

    public static void endFrame(RenderTarget target) {
        target.unbindWrite();
        rendering = false;
    }

    /** Clears the real window black, then copies the finished canvas 1:1 at its center. */
    public static void present(RenderTarget target, int fallbackWidth, int fallbackHeight) {
        if (!enabled()) {
            target.blitToScreen(fallbackWidth, fallbackHeight);
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getWindow() == null) {
            target.blitToScreen(fallbackWidth, fallbackHeight);
            return;
        }
        Window window = minecraft.getWindow();
        Bounds b = bounds(window);
        int fullWidth = rawWidth(window);
        int fullHeight = rawHeight(window);
        RenderSystem.viewport(0, 0, fullWidth, fullHeight);
        RenderSystem.clearColor(0, 0, 0, 1);
        RenderSystem.clear(16640, Minecraft.ON_OSX);

        // Copy the framebuffer rectangle directly. RenderTarget's normal blit
        // shader always builds a full-viewport quad, which made the canvas keep
        // its old corner even when its viewport was offset. A framebuffer blit
        // has explicit destination bounds, so presentation and mapped input now
        // use the exact same centered rectangle.
        GlStateManager._glBindFramebuffer(36008, target.frameBufferId); // GL_READ_FRAMEBUFFER
        GlStateManager._glBindFramebuffer(36009, 0);                    // GL_DRAW_FRAMEBUFFER
        GlStateManager._glBlitFrameBuffer(
                0, 0, target.width, target.height,
                b.x(), b.y(), b.x() + b.width(), b.y() + b.height(),
                16384, 9728); // GL_COLOR_BUFFER_BIT, GL_NEAREST
        GlStateManager._glBindFramebuffer(36160, 0); // GL_FRAMEBUFFER
        RenderSystem.viewport(0, 0, fullWidth, fullHeight);
    }

    public static boolean rendering() {
        return rendering;
    }

    public static int rawWidth(Window window) {
        return Math.max(1, ((WindowPsychCanvasAccessor) (Object) window).fnfmod$rawWidth());
    }

    public static int rawHeight(Window window) {
        return Math.max(1, ((WindowPsychCanvasAccessor) (Object) window).fnfmod$rawHeight());
    }

    /**
     * Keeps Minecraft and renderer mods on the real framebuffer dimensions.
     * The scene is deliberately projected as 16:9 into this full-size target;
     * the final copy then maps it into the centered 16:9 presentation rectangle.
     * This makes the projection and presentation cancel cleanly without resizing
     * any Sodium/Iris-owned entity, outline, transparency, or shader framebuffer.
     */
    private static boolean resizeMainTarget(Minecraft minecraft, int width, int height) {
        RenderTarget target = minecraft.getMainRenderTarget();
        if (target == null) return false;
        if (target.width == width && target.height == height
                && target.viewWidth == width && target.viewHeight == height) return false;
        target.resize(width, height, Minecraft.ON_OSX);
        return true;
    }

    /** Draws opaque bars after the current GUI/screen so only the 16:9 game canvas remains visible. */
    public static void renderLetterbox(GuiGraphics gui) {
        // The centered framebuffer copy and its preceding black clear now own
        // the letterbox. Retain this hook for source/binary compatibility.
    }

    public record Bounds(int x, int y, int width, int height) {}
}
