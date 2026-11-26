package com.fnfmod.client.gameplay;

import com.fnfmod.FnfMod;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Modal native file / text dialogs. Files use LWJGL TinyFD (bundled with Minecraft);
 * Windows folders use the modern Explorer IFileOpenDialog directly in-process.
 *
 * <p>TinyFD dispatches to each platform's own dialog automatically — the Windows
 * Explorer file selector on Windows (the same one browsers such as Photopea use),
 * and the native GTK/zenity or Cocoa dialogs on Linux and macOS — so no manual OS
 * branching is needed. Calls are synchronous (the dialog is modal); run them on the
 * client thread, which is also required for the native dialog on macOS.
 */
public final class NativeFilePicker {

    private static volatile boolean dialogOpen;

    private NativeFilePicker() {}

    /** True while a native picker owns input and the game must remain inert. */
    public static boolean isDialogOpen() {
        return dialogOpen;
    }

    /**
     * Runs one native dialog as a modal game operation. Releasing input on both
     * sides prevents a held movement key, mouse button, or scroll event from
     * leaking into gameplay when the OS returns focus to Minecraft.
     */
    private static <T> T modal(Supplier<T> dialog, T fallback) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean mouseWasGrabbed = minecraft != null && minecraft.mouseHandler.isMouseGrabbed();
        dialogOpen = true;
        releaseGameInput(minecraft);
        try {
            return dialog.get();
        } catch (Throwable error) {
            FnfMod.LOGGER.warn("Native dialog unavailable: {}", error.toString());
            return fallback;
        } finally {
            releaseGameInput(minecraft);
            dialogOpen = false;
            if (mouseWasGrabbed && minecraft != null && minecraft.screen == null) {
                minecraft.mouseHandler.grabMouse();
            }
        }
    }

    private static void releaseGameInput(Minecraft minecraft) {
        KeyMapping.releaseAll();
        if (minecraft != null && minecraft.mouseHandler.isMouseGrabbed()) {
            minecraft.mouseHandler.releaseMouse();
        }
    }

    /**
     * Opens the OS file-open dialog. {@code patterns} are globs such as {@code "*.png"};
     * empty means any file. Returns the chosen path, or empty if cancelled/unavailable.
     */
    public static Optional<Path> openFile(String title, String[] patterns, String description) {
        List<Path> selected = openFiles(title, patterns, description, false);
        return selected.isEmpty() ? Optional.empty() : Optional.of(selected.get(0));
    }

    /**
     * Opens the OS file-open dialog with multi-selection enabled. TinyFD returns
     * multiple paths separated by {@code |}; that character is invalid in Windows
     * filenames and is the delimiter TinyFD documents for every platform.
     */
    public static List<Path> openFiles(String title, String[] patterns, String description) {
        return openFiles(title, patterns, description, true);
    }

    private static List<Path> openFiles(String title, String[] patterns, String description,
                                        boolean multiple) {
        return modal(() -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer filters = null;
                if (patterns != null && patterns.length > 0) {
                    filters = stack.mallocPointer(patterns.length);
                    for (String pattern : patterns) filters.put(stack.UTF8(pattern));
                    filters.flip();
                }
                String result = TinyFileDialogs.tinyfd_openFileDialog(
                        title, "", filters, description, multiple);
                if (result == null || result.isBlank()) return List.of();
                return java.util.Arrays.stream(result.split("\\|", -1))
                        .filter(path -> !path.isBlank())
                        .map(Path::of)
                        .toList();
            }
        }, List.of());
    }

    /** Opens native Save As dialog. Existing files require OS confirmation. */
    public static Optional<Path> saveFile(String title, Path defaultPath,
                                          String[] patterns, String description) {
        return modal(() -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer filters = null;
                if (patterns != null && patterns.length > 0) {
                    filters = stack.mallocPointer(patterns.length);
                    for (String pattern : patterns) filters.put(stack.UTF8(pattern));
                    filters.flip();
                }
                String initial = defaultPath == null ? "" : defaultPath.toAbsolutePath().toString();
                String result = TinyFileDialogs.tinyfd_saveFileDialog(
                        title, initial, filters, description);
                return result == null || result.isBlank() ? Optional.empty() : Optional.of(Path.of(result));
            }
        }, Optional.empty());
    }

    /**
     * Selects a folder through a native modal dialog. Keeping this in-process avoids
     * spawning a visible shell window and lets the OS correctly disable Minecraft until
     * the user chooses a folder or cancels.
     */
    public static Optional<Path> selectFolder(String title) {
        return modal(() -> {
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
                return WindowsFolderPicker.select(title);
            }
            String result = TinyFileDialogs.tinyfd_selectFolderDialog(
                    title, System.getProperty("user.home", ""));
            return result == null || result.isBlank() ? Optional.empty() : Optional.of(Path.of(result));
        }, Optional.empty());
    }

    /** Opens the OS colour picker. Returns the chosen 0xRRGGBB, or empty if cancelled. */
    public static Optional<Integer> pickColor(String title, int defaultRgb) {
        return modal(() -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer def = stack.malloc(3);
                def.put(0, (byte) ((defaultRgb >> 16) & 0xFF));
                def.put(1, (byte) ((defaultRgb >> 8) & 0xFF));
                def.put(2, (byte) (defaultRgb & 0xFF));
                ByteBuffer out = stack.malloc(3);
                String hex = String.format("#%06X", defaultRgb & 0xFFFFFF);
                String result = TinyFileDialogs.tinyfd_colorChooser(title, hex, def, out);
                if (result == null) return Optional.empty();
                int r = out.get(0) & 0xFF, g = out.get(1) & 0xFF, b = out.get(2) & 0xFF;
                return Optional.of((r << 16) | (g << 8) | b);
            }
        }, Optional.empty());
    }

    /** Opens the OS text-input dialog. Returns the entered text, or empty if cancelled. */
    public static Optional<String> inputText(String title, String message, String initial) {
        return modal(() -> {
            String result = TinyFileDialogs.tinyfd_inputBox(title, message, initial == null ? "" : initial);
            return result == null ? Optional.empty() : Optional.of(result);
        }, Optional.empty());
    }
}
