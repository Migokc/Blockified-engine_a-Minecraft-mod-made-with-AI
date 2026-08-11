package com.fnfmod.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public final class FnfKeys {
    public static final String CATEGORY = "key.categories.fnfmod";

    public static final KeyMapping NOTE_LEFT = key("key.fnfmod.note_left", GLFW.GLFW_KEY_D);
    public static final KeyMapping NOTE_DOWN = key("key.fnfmod.note_down", GLFW.GLFW_KEY_F);
    public static final KeyMapping NOTE_UP = key("key.fnfmod.note_up", GLFW.GLFW_KEY_J);
    public static final KeyMapping NOTE_RIGHT = key("key.fnfmod.note_right", GLFW.GLFW_KEY_K);
    public static final KeyMapping VOLUME_UP = key("key.fnfmod.volume_up", GLFW.GLFW_KEY_EQUAL);
    public static final KeyMapping VOLUME_DOWN = key("key.fnfmod.volume_down", GLFW.GLFW_KEY_MINUS);

    public static final KeyMapping[] NOTE_KEYS = {NOTE_LEFT, NOTE_DOWN, NOTE_UP, NOTE_RIGHT};
    public static final KeyMapping[] ALL_KEYS = {
            NOTE_LEFT, NOTE_DOWN, NOTE_UP, NOTE_RIGHT, VOLUME_UP, VOLUME_DOWN
    };

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

    private static KeyMapping key(String name, int defaultKey) {
        return new OrderedKeyMapping(name, defaultKey);
    }

    private static int controlOrder(String name) {
        return switch (name) {
            case "key.fnfmod.note_left" -> 0;
            case "key.fnfmod.note_down" -> 1;
            case "key.fnfmod.note_up" -> 2;
            case "key.fnfmod.note_right" -> 3;
            case "key.fnfmod.volume_up" -> 4;
            case "key.fnfmod.volume_down" -> 5;
            default -> Integer.MAX_VALUE;
        };
    }

    /** Minecraft normally alphabetizes controls; preserve the four-lane gameplay order instead. */
    private static final class OrderedKeyMapping extends KeyMapping {
        OrderedKeyMapping(String name, int defaultKey) {
            super(name, InputConstants.Type.KEYSYM, defaultKey, CATEGORY);
        }

        @Override
        public int compareTo(KeyMapping other) {
            if (CATEGORY.equals(other.getCategory())) {
                int comparison = Integer.compare(controlOrder(getName()), controlOrder(other.getName()));
                if (comparison != 0) return comparison;
            }
            return super.compareTo(other);
        }
    }
}
