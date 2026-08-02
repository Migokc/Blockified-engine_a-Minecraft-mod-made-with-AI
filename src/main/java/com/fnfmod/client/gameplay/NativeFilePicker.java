package com.fnfmod.client.gameplay;

import com.fnfmod.FnfMod;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;

/**
 * Native OS file / text dialogs via LWJGL TinyFD (bundled with Minecraft).
 *
 * <p>TinyFD dispatches to each platform's own dialog automatically — the Windows
 * Explorer file selector on Windows (the same one browsers such as Photopea use),
 * and the native GTK/zenity or Cocoa dialogs on Linux and macOS — so no manual OS
 * branching is needed. Calls are synchronous (the dialog is modal); run them on the
 * client thread, which is also required for the native dialog on macOS.
 */
public final class NativeFilePicker {

    private NativeFilePicker() {}

    /**
     * Opens the OS file-open dialog. {@code patterns} are globs such as {@code "*.png"};
     * empty means any file. Returns the chosen path, or empty if cancelled/unavailable.
     */
    public static Optional<Path> openFile(String title, String[] patterns, String description) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = null;
            if (patterns != null && patterns.length > 0) {
                filters = stack.mallocPointer(patterns.length);
                for (String pattern : patterns) filters.put(stack.UTF8(pattern));
                filters.flip();
            }
            String result = TinyFileDialogs.tinyfd_openFileDialog(
                    title, "", filters, description, false);
            return result == null || result.isBlank() ? Optional.empty() : Optional.of(Path.of(result));
        } catch (Throwable error) {
            FnfMod.LOGGER.warn("Native file dialog unavailable: {}", error.toString());
            return Optional.empty();
        }
    }

    /**
     * Selects a folder. On Windows this drives the modern common file dialog in folder
     * mode (the same modern Explorer selector browsers use) via PowerShell, so a folder
     * — even an empty one — can be chosen. Other platforms use their native folder dialog.
     */
    public static Optional<Path> selectFolder(String title) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        if (windows) {
            try {
                // Ran to completion — empty means the user cancelled; do NOT fall back.
                return selectFolderWindows(title);
            } catch (Throwable error) {
                FnfMod.LOGGER.warn("Modern Windows folder dialog failed, using native: {}", error.toString());
                // Only a genuine failure to run falls through to the legacy dialog.
            }
        }
        try {
            String result = TinyFileDialogs.tinyfd_selectFolderDialog(
                    title, System.getProperty("user.home", ""));
            return result == null || result.isBlank() ? Optional.empty() : Optional.of(Path.of(result));
        } catch (Throwable error) {
            FnfMod.LOGGER.warn("Native folder dialog unavailable: {}", error.toString());
            return Optional.empty();
        }
    }

    private static Optional<Path> selectFolderWindows(String title) throws Exception {
        // Invoke the real modern folder picker (IFileOpenDialog + FOS_PICKFOLDERS — the
        // "Folder: / Select Folder" dialog) through PowerShell COM interop. The C# is
        // written to a temp file so no fragile here-string escaping is needed.
        Path cs = Path.of(System.getProperty("java.io.tmpdir", "."), "fnfmod_folderpicker.cs");
        java.nio.file.Files.writeString(cs, FOLDER_PICKER_CS);
        String safeTitle = (title == null ? "Select folder" : title).replace("'", "''");
        String script =
                "$ProgressPreference='SilentlyContinue'\n"
                + "[Console]::OutputEncoding=[System.Text.Encoding]::UTF8\n"
                + "Add-Type -Path '" + cs.toString().replace("'", "''") + "'\n"
                + "$p=[FnfFolderPicker]::Pick('" + safeTitle + "')\n"
                + "if($p){[Console]::Out.Write($p)}";
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        Process process = new ProcessBuilder("powershell.exe",
                "-NoProfile", "-STA", "-EncodedCommand", encoded).start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        process.waitFor();
        return out.isBlank() ? Optional.empty() : Optional.of(Path.of(out));
    }

    private static final String FOLDER_PICKER_CS = """
            using System;
            using System.Runtime.InteropServices;
            public static class FnfFolderPicker {
                [ComImport, Guid("DC1C5A9C-E88A-4dde-A5A1-60F82A20AEF7")] private class FileOpenDialogRcw {}
                [ComImport, Guid("d57c7288-d4ad-4768-be02-9d969532d960"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
                private interface IFileOpenDialog {
                    [PreserveSig] int Show(IntPtr parent);
                    void SetFileTypes(uint cFileTypes, IntPtr rgFilterSpec);
                    void SetFileTypeIndex(uint iFileType);
                    void GetFileTypeIndex(out uint piFileType);
                    void Advise(IntPtr pfde, out uint pdwCookie);
                    void Unadvise(uint dwCookie);
                    void SetOptions(uint fos);
                    void GetOptions(out uint fos);
                    void SetDefaultFolder(IntPtr psi);
                    void SetFolder(IntPtr psi);
                    void GetFolder(out IntPtr ppsi);
                    void GetCurrentSelection(out IntPtr ppsi);
                    void SetFileName([MarshalAs(UnmanagedType.LPWStr)] string pszName);
                    void GetFileName([MarshalAs(UnmanagedType.LPWStr)] out string pszName);
                    void SetTitle([MarshalAs(UnmanagedType.LPWStr)] string pszTitle);
                    void SetOkButtonLabel([MarshalAs(UnmanagedType.LPWStr)] string pszText);
                    void SetFileNameLabel([MarshalAs(UnmanagedType.LPWStr)] string pszLabel);
                    void GetResult(out IShellItem ppsi);
                }
                [ComImport, Guid("43826d1e-e718-42ee-bc55-a1e261c37bfe"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
                private interface IShellItem {
                    void BindToHandler(IntPtr pbc, ref Guid bhid, ref Guid riid, out IntPtr ppv);
                    void GetParent(out IShellItem ppsi);
                    void GetDisplayName(uint sigdnName, [MarshalAs(UnmanagedType.LPWStr)] out string ppszName);
                    void GetAttributes(uint sfgaoMask, out uint psfgaoAttribs);
                    void Compare(IShellItem psi, uint hint, out int piOrder);
                }
                public static string Pick(string title) {
                    var dialog = (IFileOpenDialog)(new FileOpenDialogRcw());
                    uint options; dialog.GetOptions(out options); dialog.SetOptions(options | 0x20u | 0x40u);
                    if (!string.IsNullOrEmpty(title)) dialog.SetTitle(title);
                    int hr = dialog.Show(IntPtr.Zero);
                    if (hr != 0) return "";
                    IShellItem item; dialog.GetResult(out item);
                    string path; item.GetDisplayName(0x80058000u, out path);
                    return path == null ? "" : path;
                }
            }
            """;

    /** Opens the OS colour picker. Returns the chosen 0xRRGGBB, or empty if cancelled. */
    public static Optional<Integer> pickColor(String title, int defaultRgb) {
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
        } catch (Throwable error) {
            FnfMod.LOGGER.warn("Native colour dialog unavailable: {}", error.toString());
            return Optional.empty();
        }
    }

    /** Opens the OS text-input dialog. Returns the entered text, or empty if cancelled. */
    public static Optional<String> inputText(String title, String message, String initial) {
        try {
            String result = TinyFileDialogs.tinyfd_inputBox(title, message, initial == null ? "" : initial);
            return result == null ? Optional.empty() : Optional.of(result);
        } catch (Throwable error) {
            FnfMod.LOGGER.warn("Native input dialog unavailable: {}", error.toString());
            return Optional.empty();
        }
    }
}
