package com.fnfmod.client.gui;

import com.fnfmod.client.FnfKeys;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import org.lwjgl.glfw.GLFW;

/** Global FNF-style master-volume control, including vanilla and modded screens. */
public final class MasterVolumeOverlay {
    private static final int PANEL_WIDTH = 95;
    private static final int PANEL_HEIGHT = 30;
    // Vanilla option sliders are 150 px wide. The compact overlay is half that size.
    private static final int SLIDER_WIDTH = 75;
    private static final int SLIDER_HEIGHT = 6;
    private static final double ANIMATION_SECONDS = 0.3;
    private static final long DISPLAY_NANOS = 2_000_000_000L;

    private static double visibility;
    private static long lastFrameNanos;
    private static long visibleUntilNanos;
    private static boolean dragging;

    private MasterVolumeOverlay() {}

    public static boolean handleKey(Screen screen, int keyCode, int scanCode) {
        if (!allowed(screen) || isTyping(screen)) return false;
        int direction = FnfKeys.VOLUME_UP.matches(keyCode, scanCode) ? 1
                : FnfKeys.VOLUME_DOWN.matches(keyCode, scanCode) ? -1 : 0;
        // Keep both old +/- locations while the bindings remain at their
        // defaults. Rebinding removes these aliases, so the chosen control fully
        // replaces the old keys instead of leaving an unchangeable shortcut.
        if (direction == 0 && FnfKeys.VOLUME_UP.isDefault() && keyCode == GLFW.GLFW_KEY_KP_ADD) direction = 1;
        if (direction == 0 && FnfKeys.VOLUME_DOWN.isDefault() && keyCode == GLFW.GLFW_KEY_KP_SUBTRACT) direction = -1;
        return changeVolume(direction);
    }

    public static boolean handleMouseBinding(Screen screen, int button) {
        if (!allowed(screen) || isTyping(screen)) return false;
        int direction = FnfKeys.VOLUME_UP.matchesMouse(button) ? 1
                : FnfKeys.VOLUME_DOWN.matchesMouse(button) ? -1 : 0;
        return changeVolume(direction);
    }

    private static boolean changeVolume(int direction) {
        if (direction == 0) return false;

        Minecraft mc = Minecraft.getInstance();
        double current = mc.options.getSoundSourceVolume(SoundSource.MASTER);
        if (setVolume(clamp(current + direction * 0.1))) mc.options.save();
        show();
        return true;
    }

    public static void render(GuiGraphics gui, int mouseX, int mouseY, Screen screen) {
        if (!allowed(screen)) {
            dragging = false;
            visibility = 0;
            lastFrameNanos = 0;
            return;
        }

        long now = System.nanoTime();
        if (lastFrameNanos == 0) lastFrameNanos = now;
        double dt = Math.min(0.1, Math.max(0, (now - lastFrameNanos) / 1_000_000_000.0));
        lastFrameNanos = now;

        boolean shouldShow = dragging || now < visibleUntilNanos;
        double step = dt / ANIMATION_SECONDS;
        visibility = shouldShow ? Math.min(1, visibility + step) : Math.max(0, visibility - step);
        if (visibility <= 0.0001) return;

        int x = (gui.guiWidth() - PANEL_WIDTH) / 2;
        int y = panelY();
        if (screen != null && inside(mouseX, mouseY, x, y, PANEL_WIDTH, PANEL_HEIGHT)) {
            visibleUntilNanos = Math.max(visibleUntilNanos, now + 150_000_000L);
        }

        Minecraft mc = Minecraft.getInstance();
        double volume = mc.options.getSoundSourceVolume(SoundSource.MASTER);
        int sliderX = x + (PANEL_WIDTH - SLIDER_WIDTH) / 2;
        int sliderY = y + 18;
        int filled = (int) Math.round(SLIDER_WIDTH * volume);
        int thumbX = sliderX + Math.max(0, Math.min(SLIDER_WIDTH - 2, filled - 1));

        // Flush the current screen, then render from an identity GUI pose at a
        // dedicated topmost depth. This behaves like FNF's camOther: screen/HUD
        // transforms and object ordering cannot move through the volume overlay.
        gui.flush();
        gui.pose().pushPose();
        try {
            gui.pose().setIdentity();
            gui.pose().translate(0, 0, 1000);
            gui.fill(x, y, x + PANEL_WIDTH, y + PANEL_HEIGHT, 0xD0101010);
            gui.renderOutline(x, y, PANEL_WIDTH, PANEL_HEIGHT, 0xB0FFFFFF);
            gui.drawCenteredString(mc.font, "Master " + Math.round(volume * 100) + "%",
                    x + PANEL_WIDTH / 2, y + 5, 0xFFFFFFFF);
            gui.fill(sliderX, sliderY, sliderX + SLIDER_WIDTH, sliderY + SLIDER_HEIGHT, 0xFF343434);
            gui.fill(sliderX, sliderY, sliderX + filled, sliderY + SLIDER_HEIGHT, 0xFFFFFFFF);
            gui.fill(thumbX, sliderY - 2, thumbX + 3, sliderY + SLIDER_HEIGHT + 2, 0xFFFFFFFF);
            gui.flush();
        } finally {
            gui.pose().popPose();
        }
    }

    public static boolean mousePressed(Screen screen, double mouseX, double mouseY, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT || !allowed(screen) || visibility <= 0.05) return false;
        int sliderX = sliderX(Minecraft.getInstance().getWindow().getGuiScaledWidth());
        int sliderY = panelY() + 14;
        if (!inside(mouseX, mouseY, sliderX - 2, sliderY, SLIDER_WIDTH + 4, SLIDER_HEIGHT + 8)) return false;
        dragging = true;
        updateFromMouse(mouseX, sliderX);
        show();
        return true;
    }

    public static boolean mouseDragged(Screen screen, double mouseX, double mouseY, int button) {
        if (!dragging || button != GLFW.GLFW_MOUSE_BUTTON_LEFT || !allowed(screen)) return false;
        updateFromMouse(mouseX, sliderX(Minecraft.getInstance().getWindow().getGuiScaledWidth()));
        show();
        return true;
    }

    public static boolean mouseReleased(Screen screen, int button) {
        if (!dragging || button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        dragging = false;
        Minecraft.getInstance().options.save();
        show();
        return true;
    }

    private static void updateFromMouse(double mouseX, int sliderX) {
        setVolume(clamp((mouseX - sliderX) / SLIDER_WIDTH));
    }

    private static boolean setVolume(double volume) {
        Minecraft mc = Minecraft.getInstance();
        double current = mc.options.getSoundSourceVolume(SoundSource.MASTER);
        if (Math.abs(current - volume) < 0.000001) return false;
        mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(volume);
        com.fnfmod.client.audio.PsychSoundPlayer.refreshMasterVolumes();
        // Dragging remains smooth, but feedback follows the number shown in the
        // overlay instead of every sub-percent mouse movement.
        if (Math.round(current * 100) != Math.round(volume * 100)) {
            // Minecraft's supported pitch range is 0..2. Master volume maps
            // directly across it, so the feedback rises with the displayed value.
            mc.getSoundManager().play(SimpleSoundInstance.forUI(
                    SoundEvents.UI_BUTTON_CLICK.value(), (float) (volume * 2.0)));
        }
        return true;
    }

    private static void show() {
        visibleUntilNanos = System.nanoTime() + DISPLAY_NANOS;
    }

    private static boolean allowed(Screen screen) {
        return true;
    }

    private static boolean isTyping(Screen screen) {
        if (screen == null) return false;
        if (screen instanceof TextInputAwareScreen aware && aware.isTextInputActive()) return true;
        if (screen.getFocused() instanceof EditBox edit && edit.isFocused()) return true;

        // Book and sign editors use custom text handlers instead of a focused EditBox.
        String screenName = screen.getClass().getSimpleName();
        return screenName.contains("BookEditScreen") || screenName.contains("SignEditScreen");
    }

    private static int sliderX(int guiWidth) {
        return (guiWidth - PANEL_WIDTH) / 2 + (PANEL_WIDTH - SLIDER_WIDTH) / 2;
    }

    private static int panelY() {
        // Exponential-out entry; reversing it gives a quick, clean exit upward.
        double eased = visibility >= 1 ? 1 : 1 - Math.pow(2, -10 * visibility);
        return (int) Math.round((-PANEL_HEIGHT - 2) + (8 + PANEL_HEIGHT + 2) * eased);
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }
}
