package com.fnfmod.client.gameplay;

import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.COM.COMUtils;
import com.sun.jna.platform.win32.COM.Unknown;
import com.sun.jna.platform.win32.Guid.CLSID;
import com.sun.jna.platform.win32.Guid.IID;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinNT.HRESULT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFWNativeWin32;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** In-process Windows 10/11 Explorer folder picker backed by IFileOpenDialog. */
final class WindowsFolderPicker {

    private static final CLSID CLSID_FILE_OPEN_DIALOG =
            new CLSID("DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7");
    private static final IID IID_FILE_OPEN_DIALOG =
            new IID("D57C7288-D4AD-4768-BE02-9D969532D960");
    private static final int CLSCTX_INPROC_SERVER = 0x1;
    private static final int FOS_PICKFOLDERS = 0x20;
    private static final int FOS_FORCEFILESYSTEM = 0x40;
    private static final int SIGDN_FILESYSPATH = 0x80058000;
    private static final int ERROR_CANCELLED = 0x800704C7;

    private WindowsFolderPicker() {}

    static Optional<Path> select(String title) {
        long glfwWindow = Minecraft.getInstance().getWindow().getWindow();
        long nativeWindow = GLFWNativeWin32.glfwGetWin32Window(glfwWindow);
        HWND owner = nativeWindow == 0L ? null : new HWND(Pointer.createConstant(nativeWindow));
        AtomicReference<Optional<Path>> selected = new AtomicReference<>(Optional.empty());
        AtomicReference<Throwable> failure = new AtomicReference<>();

        // A dedicated STA keeps the COM dialog independent of Minecraft's render
        // thread apartment while the caller still waits synchronously for a result.
        Thread thread = new Thread(() -> {
            try {
                selected.set(show(title, owner));
            } catch (Throwable error) {
                failure.set(error);
            }
        }, "Blockified Windows folder picker");
        thread.setDaemon(true);
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }

        if (failure.get() != null) {
            throw new IllegalStateException("Windows Explorer folder picker failed", failure.get());
        }
        return selected.get();
    }

    private static Optional<Path> show(String title, HWND owner) {
        HRESULT initialized = Ole32.INSTANCE.CoInitializeEx(Pointer.NULL, Ole32.COINIT_APARTMENTTHREADED);
        boolean uninitialize = COMUtils.SUCCEEDED(initialized);
        if (COMUtils.FAILED(initialized)) {
            COMUtils.checkRC(initialized);
        }

        FileOpenDialog dialog = null;
        try {
            PointerByReference created = new PointerByReference();
            HRESULT result = Ole32.INSTANCE.CoCreateInstance(
                    CLSID_FILE_OPEN_DIALOG, null, CLSCTX_INPROC_SERVER, IID_FILE_OPEN_DIALOG, created);
            COMUtils.checkRC(result);
            dialog = new FileOpenDialog(created.getValue());

            IntByReference currentOptions = new IntByReference();
            COMUtils.checkRC(dialog.getOptions(currentOptions));
            COMUtils.checkRC(dialog.setOptions(currentOptions.getValue() | FOS_PICKFOLDERS | FOS_FORCEFILESYSTEM));
            if (title != null && !title.isBlank()) {
                COMUtils.checkRC(dialog.setTitle(title));
            }

            HRESULT shown = dialog.show(owner);
            if (shown.intValue() == ERROR_CANCELLED) return Optional.empty();
            COMUtils.checkRC(shown);

            PointerByReference shellItemPointer = new PointerByReference();
            COMUtils.checkRC(dialog.getResult(shellItemPointer));
            ShellItem item = new ShellItem(shellItemPointer.getValue());
            try {
                PointerByReference pathPointer = new PointerByReference();
                COMUtils.checkRC(item.getDisplayName(SIGDN_FILESYSPATH, pathPointer));
                Pointer nativePath = pathPointer.getValue();
                try {
                    String path = nativePath == null ? "" : nativePath.getWideString(0);
                    return path.isBlank() ? Optional.empty() : Optional.of(Path.of(path));
                } finally {
                    if (nativePath != null) Ole32.INSTANCE.CoTaskMemFree(nativePath);
                }
            } finally {
                item.Release();
            }
        } finally {
            if (dialog != null) dialog.Release();
            if (uninitialize) Ole32.INSTANCE.CoUninitialize();
        }
    }

    private static final class FileOpenDialog extends Unknown {
        private FileOpenDialog(Pointer pointer) {
            super(pointer);
        }

        private HRESULT show(HWND owner) {
            return (HRESULT) _invokeNativeObject(3,
                    new Object[]{getPointer(), owner}, HRESULT.class);
        }

        private HRESULT setOptions(int options) {
            return (HRESULT) _invokeNativeObject(9,
                    new Object[]{getPointer(), options}, HRESULT.class);
        }

        private HRESULT getOptions(IntByReference options) {
            return (HRESULT) _invokeNativeObject(10,
                    new Object[]{getPointer(), options}, HRESULT.class);
        }

        private HRESULT setTitle(String title) {
            return (HRESULT) _invokeNativeObject(17,
                    new Object[]{getPointer(), new WString(title)}, HRESULT.class);
        }

        private HRESULT getResult(PointerByReference result) {
            return (HRESULT) _invokeNativeObject(20,
                    new Object[]{getPointer(), result}, HRESULT.class);
        }
    }

    private static final class ShellItem extends Unknown {
        private ShellItem(Pointer pointer) {
            super(pointer);
        }

        private HRESULT getDisplayName(int kind, PointerByReference name) {
            return (HRESULT) _invokeNativeObject(5,
                    new Object[]{getPointer(), kind, name}, HRESULT.class);
        }
    }
}
