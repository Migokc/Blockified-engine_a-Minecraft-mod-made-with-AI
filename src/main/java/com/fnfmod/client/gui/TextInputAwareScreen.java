package com.fnfmod.client.gui;

/** Screens with text-entry modes that are not exposed through Screen#getFocused(). */
public interface TextInputAwareScreen {
    boolean isTextInputActive();
}
