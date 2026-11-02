package com.rawmousefix;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;

/**
 * Minimal Win32 bindings for reading raw mouse input.
 *
 * <p>Deliberately written against the core JNA API only (no {@code jna-platform}) and against the
 * oldest API surface that still exists in current JNA releases: Minecraft 1.8.9 already ships
 * {@code net.java.dev.jna:jna:3.4.0} in its {@code libraries/} folder, so nothing has to be bundled
 * with the mod. Every pointer sized quantity is declared as {@link Pointer}, which is correct on
 * both x86 and x64.</p>
 */
public final class Win32 {

    /** {@code WM_INPUT}. */
    public static final int WM_INPUT = 0x00FF;

    /** {@code GetRawInputData} command + {@code RIDEV_*} flags. */
    public static final int RID_INPUT = 0x10000003;
    public static final int RIDEV_INPUTSINK = 0x00000100;

    /** {@code RAWMOUSE.usFlags} bit: the device reports absolute coordinates (RDP, tablets, VMs). */
    public static final int MOUSE_MOVE_ABSOLUTE = 0x01;

    /** {@code PeekMessage} flag. */
    public static final int PM_REMOVE = 0x0001;

    /** {@code RAWINPUTHEADER} is 16 bytes on x86 and 24 bytes on x64 (two pointer sized fields). */
    public static final int RAWINPUT_HEADER_SIZE = Pointer.SIZE == 8 ? 24 : 16;

    /** Offsets of {@code RAWMOUSE.lLastX} / {@code lLastY}, relative to the start of {@code RAWMOUSE}. */
    public static final int RAWMOUSE_LAST_X = 12;
    public static final int RAWMOUSE_LAST_Y = 16;

    /**
     * Offsets inside {@code MSG}. We only ever need the message id and {@code lParam}, and reading
     * them out of a raw buffer keeps us independent of JNA Structure quirks.
     */
    public static final int MSG_MESSAGE_OFFSET = Pointer.SIZE;
    public static final int MSG_LPARAM_OFFSET = Pointer.SIZE == 8 ? 24 : 12;

    private Win32() {
    }

    /** Win32 window procedure. Pointer sized arguments stay correct on x86 and x64. */
    public interface WndProc extends StdCallLibrary.StdCallCallback {
        Pointer callback(Pointer hwnd, int msg, Pointer wParam, Pointer lParam);
    }

    public interface User32 extends StdCallLibrary {
        User32 INSTANCE = (User32) Native.loadLibrary("user32", User32.class);

        short RegisterClassExW(WindowClass windowClass);

        Pointer CreateWindowExW(int exStyle, Pointer className, Pointer windowName, int style,
                                int x, int y, int width, int height, Pointer parent, Pointer menu,
                                Pointer instance, Pointer param);

        boolean DestroyWindow(Pointer hwnd);

        Pointer DefWindowProcW(Pointer hwnd, int msg, Pointer wParam, Pointer lParam);

        boolean RegisterRawInputDevices(RawInputDevice devices, int deviceCount, int structSize);

        int GetRawInputData(Pointer rawInputHandle, int command, Pointer data,
                            IntByReference size, int headerSize);

        boolean PeekMessageW(Pointer msg, Pointer hwnd, int filterMin, int filterMax, int removeMsg);
    }

    public interface Kernel32 extends StdCallLibrary {
        Kernel32 INSTANCE = (Kernel32) Native.loadLibrary("kernel32", Kernel32.class);

        Pointer GetModuleHandleW(Pointer moduleName);

        int GetCurrentThreadId();

        int GetLastError();
    }

    /** {@code WNDCLASSEXW}. Kept alive for as long as the window exists. */
    public static class WindowClass extends Structure {
        public int cbSize;
        public int style;
        /** JNA writes the callback's function pointer into the structure field directly. */
        public WndProc lpfnWndProc;
        public int cbClsExtra;
        public int cbWndExtra;
        public Pointer hInstance;
        public Pointer hIcon;
        public Pointer hCursor;
        public Pointer hbrBackground;
        public Pointer lpszMenuName;
        public Pointer lpszClassName;
        public Pointer hIconSm;

        @Override
        protected List getFieldOrder() {
            return Arrays.asList("cbSize", "style", "lpfnWndProc", "cbClsExtra", "cbWndExtra",
                    "hInstance", "hIcon", "hCursor", "hbrBackground", "lpszMenuName",
                    "lpszClassName", "hIconSm");
        }
    }

    /** {@code RAWINPUTDEVICE}. */
    public static class RawInputDevice extends Structure {
        public short usUsagePage;
        public short usUsage;
        public int dwFlags;
        public Pointer hwndTarget;

        @Override
        protected List getFieldOrder() {
            return Arrays.asList("usUsagePage", "usUsage", "dwFlags", "hwndTarget");
        }
    }

    public static boolean isWindows() {
        return Platform.isWindows();
    }

    /** Allocates a NUL terminated UTF-16LE string in native memory. */
    public static Memory wide(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_16LE);
        Memory memory = new Memory(bytes.length + 2L);
        memory.write(0L, bytes, 0, bytes.length);
        memory.setShort(bytes.length, (short) 0);
        return memory;
    }
}
