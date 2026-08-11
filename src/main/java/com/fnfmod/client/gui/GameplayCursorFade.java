package com.fnfmod.client.gui;

import org.lwjgl.glfw.GLFW;

/** PlayState-owned visibility for the cursor already selected by Minecraft/the OS. */
final class GameplayCursorFade implements AutoCloseable {
    private boolean hidden;
    private long activeWindow;

    /**
     * GLFW cannot alter the alpha of the currently installed cursor. Keep that
     * cursor intact and use the hidden input mode as soon as fading would begin.
     */
    void setOpacity(long window, double opacity) {
        if (window == 0) return;
        activeWindow = window;
        boolean shouldHide = opacity < 1;
        if (hidden == shouldHide) return;
        int mode = GLFW.glfwGetInputMode(window, GLFW.GLFW_CURSOR);
        if (shouldHide) {
            if (mode == GLFW.GLFW_CURSOR_NORMAL) {
                GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_HIDDEN);
                hidden = true;
            }
        } else {
            restore(window);
        }
    }

    /** Reveals the same cursor that was active before PlayState hid it. */
    void restore(long window) {
        if (window == 0) return;
        activeWindow = window;
        if (hidden && GLFW.glfwGetInputMode(window, GLFW.GLFW_CURSOR) == GLFW.GLFW_CURSOR_HIDDEN) {
            GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
        }
        hidden = false;
    }

    @Override
    public void close() {
        if (activeWindow != 0) {
            try { restore(activeWindow); } catch (Throwable ignored) {}
        }
        activeWindow = 0;
    }
}
