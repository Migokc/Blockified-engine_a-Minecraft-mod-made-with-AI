package com.fnfmod.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public final class FnfKeys {
    public static final String CATEGORY = "key.categories.fnfmod";

    public static final KeyMapping NOTE_LEFT = new KeyMapping(
            "key.fnfmod.note_left", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_D, CATEGORY);
    public static final KeyMapping NOTE_DOWN = new KeyMapping(
            "key.fnfmod.note_down", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F, CATEGORY);
    public static final KeyMapping NOTE_UP = new KeyMapping(
            "key.fnfmod.note_up", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, CATEGORY);
    public static final KeyMapping NOTE_RIGHT = new KeyMapping(
            "key.fnfmod.note_right", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, CATEGORY);

    public static final KeyMapping[] NOTE_KEYS = {NOTE_LEFT, NOTE_DOWN, NOTE_UP, NOTE_RIGHT};

    private FnfKeys() {}

    /** Which lane a raw key event belongs to, or -1. */
    public static int laneForKey(int keyCode, int scanCode) {
        for (int i = 0; i < 4; i++) {
            if (NOTE_KEYS[i].matches(keyCode, scanCode)) return i;
        }
        return -1;
    }

    /** The four lanes' currently bound GLFW key codes, for the raw input backend. */
    public static int[] currentKeyCodes() {
        int[] codes = new int[4];
        for (int i = 0; i < 4; i++) codes[i] = NOTE_KEYS[i].getKey().getValue();
        return codes;
    }
}
