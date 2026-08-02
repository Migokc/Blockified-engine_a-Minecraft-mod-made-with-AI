package com.fnfmod.client.input;

import com.fnfmod.FnfMod;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.windows.User32;

import java.util.Locale;
import java.util.concurrent.locks.LockSupport;

/**
 * Frame-rate-independent note input for Windows.
 *
 * <p>A background thread reads the raw keyboard state with
 * {@code GetAsyncKeyState} at roughly a kilohertz — far faster than the render
 * loop — and pushes each press/release into {@link NoteInput} stamped with the
 * {@link System#nanoTime()} it was seen. The game thread then judges it against
 * the song position at that instant, so hit timing no longer rounds to the frame.
 *
 * <p>This reads global key state, so it only samples while the game window is
 * focused and gameplay is active, both signalled from the main thread. Anything
 * it cannot map (a bound key with no known virtual-key) is left to the ordinary
 * GLFW handler.
 */
public final class WindowsRawKeyBackend {

    private static final int KEY_DOWN_BIT = 0x8000;
    private static final long POLL_INTERVAL_NANOS = 500_000L; // ~2 kHz ceiling

    /** Runs on the backend thread the instant a press is detected, before it queues. */
    @FunctionalInterface
    public interface PressHook {
        void onPress(int lane, long nano);
    }

    private final NoteInput noteInput;
    private final PressHook pressHook;
    private final int[] virtualKeys = {-1, -1, -1, -1};
    private final boolean[] down = new boolean[4];

    private volatile boolean running;
    private volatile boolean active;
    private volatile boolean primePending;
    private Thread thread;

    public WindowsRawKeyBackend(NoteInput noteInput, PressHook pressHook) {
        this.noteInput = noteInput;
        this.pressHook = pressHook;
    }

    /** True only on Windows, where {@code GetAsyncKeyState} exists. */
    public static boolean isSupported() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * Sets the four bound note keys as GLFW key codes. Codes that cannot be
     * mapped to a Windows virtual-key are stored as -1 and skipped, so that lane
     * keeps using GLFW input.
     */
    public void setKeys(int[] glfwKeys) {
        for (int lane = 0; lane < 4; lane++) {
            virtualKeys[lane] = lane < glfwKeys.length ? glfwToVirtualKey(glfwKeys[lane]) : -1;
        }
    }

    /** True when this backend is polling a lane, so GLFW should not also handle it. */
    public boolean handlesLane(int lane) {
        return running && lane >= 0 && lane < 4 && virtualKeys[lane] >= 0;
    }

    /** Starts the polling thread. Probes user32 up front; returns false if unavailable. */
    public synchronized boolean start() {
        if (running) return true;
        try {
            User32.GetAsyncKeyState(0); // verify the LWJGL binding resolves
        } catch (Throwable error) {
            FnfMod.LOGGER.warn("Raw key input unavailable, using per-frame input: {}", error.toString());
            return false;
        }
        running = true;
        thread = new Thread(this::loop, "fnfmod-raw-input");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    public synchronized void stop() {
        running = false;
        active = false;
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    /**
     * Enables or disables sampling. Turning it off flushes any held keys as
     * releases so a key held while the window loses focus does not stay stuck.
     */
    public void setActive(boolean value) {
        if (value && !active) {
            // Adopt the current key state on the next sample without firing, so a
            // key already held when gameplay begins is not read as a fresh press.
            primePending = true;
        }
        if (!value && active) {
            long nano = System.nanoTime();
            for (int lane = 0; lane < 4; lane++) {
                if (down[lane]) {
                    down[lane] = false;
                    noteInput.push(lane, false, nano);
                }
            }
        }
        active = value;
    }

    private void loop() {
        while (running) {
            if (active) sample();
            LockSupport.parkNanos(POLL_INTERVAL_NANOS);
        }
    }

    private void sample() {
        long nano = System.nanoTime();
        boolean prime = primePending;
        if (prime) primePending = false;
        for (int lane = 0; lane < 4; lane++) {
            int vk = virtualKeys[lane];
            if (vk < 0) continue;
            boolean isDown;
            try {
                isDown = (User32.GetAsyncKeyState(vk) & KEY_DOWN_BIT) != 0;
            } catch (Throwable error) {
                continue;
            }
            if (prime) {
                down[lane] = isDown; // seed state, no event
            } else if (isDown != down[lane]) {
                down[lane] = isDown;
                // A press sounds its hit here, sub-frame, before it is queued for
                // the game thread to credit on the next frame.
                if (isDown && pressHook != null) {
                    try {
                        pressHook.onPress(lane, nano);
                    } catch (Throwable ignored) {
                        // A hitsound problem must not stop key sampling.
                    }
                }
                noteInput.push(lane, isDown, nano);
            }
        }
    }

    /**
     * Maps a GLFW key code to a Windows virtual-key code.
     *
     * <p>For letters and digits the codes already match (GLFW A..Z / 0..9 use the
     * ASCII values, which are the virtual-key codes), so most FNF binds map for
     * free. The rest are looked up; an unknown key returns -1.
     */
    private static int glfwToVirtualKey(int glfw) {
        if (glfw >= GLFW.GLFW_KEY_A && glfw <= GLFW.GLFW_KEY_Z) return glfw; // 'A'..'Z'
        if (glfw >= GLFW.GLFW_KEY_0 && glfw <= GLFW.GLFW_KEY_9) return glfw; // '0'..'9'
        return switch (glfw) {
            case GLFW.GLFW_KEY_SPACE -> 0x20;
            case GLFW.GLFW_KEY_LEFT -> 0x25;
            case GLFW.GLFW_KEY_UP -> 0x26;
            case GLFW.GLFW_KEY_RIGHT -> 0x27;
            case GLFW.GLFW_KEY_DOWN -> 0x28;
            case GLFW.GLFW_KEY_LEFT_SHIFT -> 0xA0;
            case GLFW.GLFW_KEY_RIGHT_SHIFT -> 0xA1;
            case GLFW.GLFW_KEY_LEFT_CONTROL -> 0xA2;
            case GLFW.GLFW_KEY_RIGHT_CONTROL -> 0xA3;
            case GLFW.GLFW_KEY_ENTER -> 0x0D;
            case GLFW.GLFW_KEY_TAB -> 0x09;
            case GLFW.GLFW_KEY_SEMICOLON -> 0xBA;
            case GLFW.GLFW_KEY_COMMA -> 0xBC;
            case GLFW.GLFW_KEY_PERIOD -> 0xBE;
            case GLFW.GLFW_KEY_SLASH -> 0xBF;
            case GLFW.GLFW_KEY_LEFT_BRACKET -> 0xDB;
            case GLFW.GLFW_KEY_RIGHT_BRACKET -> 0xDD;
            case GLFW.GLFW_KEY_APOSTROPHE -> 0xDE;
            default -> -1;
        };
    }
}
