package com.rawmousefix;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

/**
 * Platform engine: creates a hidden Win32 window, registers it for raw mouse input and accumulates
 * relative mouse movement as reported by the driver.
 *
 * <p>This class has no Minecraft or Forge types in it, so it can be reused verbatim by clients that
 * do not run Forge.</p>
 *
 * <h3>Why raw input</h3>
 * <p>Until LWJGL 2 (i.e. every Minecraft version up to 1.12.2) the camera delta comes from
 * {@code Mouse.getDX()/getDY()}, which is computed by LWJGL's native grab handler: it warps the
 * cursor back to the window centre every poll and reports the displacement it found. Above roughly
 * 500 Hz the number of hardware reports per frame grows large enough that this warp bookkeeping
 * gets confused and produces occasional bogus deltas of arbitrary size 窶・the mouse "flicks" the
 * camera. {@code WM_INPUT} deltas never pass through the cursor, so they cannot be corrupted by it
 * and stay correct at any polling rate.</p>
 */
public final class RawMouseCore {

    private static final String WINDOW_CLASS = "RawMouseFixRawInputWnd";

    private static final Object LOCK = new Object();

    /** Accumulated raw deltas, in mouse counts. Guarded by {@link #LOCK}. */
    private static long pendingX;
    private static long pendingY;
    private static long rawEvents;

    private static volatile boolean initialised;
    private static volatile boolean ready;
    private static volatile boolean absoluteDevice;
    private static volatile String failure = "not initialised yet";

    private static int ownerThreadId = -1;

    // Everything below must stay strongly reachable for as long as the window lives.
    private static Win32.User32 user32;
    private static Win32.Kernel32 kernel32;
    private static Win32.WndProc wndProc;
    private static Win32.WindowClass windowClass;
    private static Memory className;
    private static Pointer hwnd;
    private static Memory scratch;
    private static Memory messageBuffer;

    private RawMouseCore() {
    }

    /**
     * Attempts to set up raw mouse input. Safe to call repeatedly; only the first call does work.
     *
     * <p>Must be invoked from the thread that pumps the window message queue (the render thread),
     * because that is the thread {@code WM_INPUT} is delivered to.</p>
     */
    public static void ensureInitialised() {
        if (initialised) {
            return;
        }
        synchronized (LOCK) {
            if (initialised) {
                return;
            }
            initialised = true;
            setup();
        }
    }

    private static void setup() {
        if (!Win32.isWindows()) {
            failure = "raw input is only implemented for Windows";
            return;
        }
        try {
            user32 = Win32.User32.INSTANCE;
            kernel32 = Win32.Kernel32.INSTANCE;
            ownerThreadId = kernel32.GetCurrentThreadId();
            scratch = new Memory(256);
            messageBuffer = new Memory(64);

            wndProc = new Win32.WndProc() {
                @Override
                public Pointer callback(Pointer window, int msg, Pointer wParam, Pointer lParam) {
                    try {
                        if (msg == Win32.WM_INPUT) {
                            handleRawInput(lParam);
                        }
                    } catch (Throwable ignored) {
                        // never let an exception escape into the native message loop
                    }
                    return user32.DefWindowProcW(window, msg, wParam, lParam);
                }
            };

            className = Win32.wide(WINDOW_CLASS);

            windowClass = new Win32.WindowClass();
            windowClass.cbSize = windowClass.size();
            windowClass.style = 0;
            windowClass.lpfnWndProc = wndProc;
            windowClass.cbClsExtra = 0;
            windowClass.cbWndExtra = 0;
            windowClass.hInstance = kernel32.GetModuleHandleW(null);
            windowClass.lpszClassName = className;
            windowClass.write();

            short atom = user32.RegisterClassExW(windowClass);
            if (atom == 0) {
                fail("RegisterClassExW", kernel32.GetLastError());
                return;
            }

            // A never-shown top level window. WS_POPUP keeps it out of the task bar and off screen.
            hwnd = user32.CreateWindowExW(0, className, className, 0x80000000 /* WS_POPUP */,
                    0, 0, 0, 0, null, null, windowClass.hInstance, null);
            if (hwnd == null) {
                fail("CreateWindowExW", kernel32.GetLastError());
                return;
            }

            Win32.RawInputDevice device = new Win32.RawInputDevice();
            device.usUsagePage = 0x01; // generic desktop controls
            device.usUsage = 0x02;     // mouse
            device.dwFlags = Win32.RIDEV_INPUTSINK; // deliver even while we are not foreground
            device.hwndTarget = hwnd;
            device.write();

            if (!user32.RegisterRawInputDevices(device, 1, device.size())) {
                fail("RegisterRawInputDevices", kernel32.GetLastError());
                return;
            }

            ready = true;
            failure = null;
            Log.info("raw mouse input active (window handle 0x%s)",
                    Long.toHexString(Pointer.nativeValue(hwnd)));
        } catch (Throwable t) {
            fail("initialisation", -1);
            failure = "initialisation failed: " + t;
        }
    }

    private static void fail(String step, int errorCode) {
        ready = false;
        failure = step + " failed" + (errorCode >= 0 ? " (Win32 error " + errorCode + ")" : "");
    }

    /**
     * Drains queued {@code WM_INPUT} messages for our own window.
     *
     * <p>The regular path is LWJGL's own message pump, which dispatches every {@code WM_INPUT} to
     * {@link #wndProc}. This is a belt and braces fallback in case another pump filters by window
     * handle. Must be called from the owning thread.</p>
     */
    public static void drainQueue() {
        if (!ready) {
            return;
        }
        try {
            if (kernel32.GetCurrentThreadId() != ownerThreadId) {
                return;
            }
            while (user32.PeekMessageW(messageBuffer, hwnd, Win32.WM_INPUT, Win32.WM_INPUT,
                    Win32.PM_REMOVE)) {
                handleRawInput(messageBuffer.getPointer(Win32.MSG_LPARAM_OFFSET));
            }
        } catch (Throwable ignored) {
            // a missing message cannot be reported anywhere sensible; the wndproc path still works
        }
    }

    private static void handleRawInput(Pointer rawInputHandle) {
        if (rawInputHandle == null || user32 == null) {
            return;
        }
        Memory buffer = scratch;
        IntByReference size = new IntByReference((int) buffer.size());
        int read = user32.GetRawInputData(rawInputHandle, Win32.RID_INPUT, buffer, size,
                Win32.RAWINPUT_HEADER_SIZE);
        if (read <= 0) {
            IntByReference needed = new IntByReference(0);
            user32.GetRawInputData(rawInputHandle, Win32.RID_INPUT, null, needed,
                    Win32.RAWINPUT_HEADER_SIZE);
            int required = needed.getValue();
            if (required <= 0 || required > 8192) {
                return;
            }
            buffer = new Memory(required);
            IntByReference actual = new IntByReference(required);
            read = user32.GetRawInputData(rawInputHandle, Win32.RID_INPUT, buffer, actual,
                    Win32.RAWINPUT_HEADER_SIZE);
            if (read <= 0) {
                return;
            }
        }

        int type = buffer.getInt(0);
        if (type != 0 /* RIM_TYPEMOUSE */) {
            return;
        }
        int flags = buffer.getShort(4) & 0xFFFF;
        if ((flags & Win32.MOUSE_MOVE_ABSOLUTE) != 0) {
            // Remote Desktop and drawing tablets report absolute positions; those deltas would be
            // meaningless as camera input, so fall back to the vanilla path for this session.
            absoluteDevice = true;
            return;
        }

        int base = Win32.RAWINPUT_HEADER_SIZE;
        int deltaX = buffer.getInt(base + Win32.RAWMOUSE_LAST_X);
        int deltaY = buffer.getInt(base + Win32.RAWMOUSE_LAST_Y);
        if (deltaX == 0 && deltaY == 0) {
            return;
        }
        synchronized (LOCK) {
            pendingX += deltaX;
            pendingY += deltaY;
            rawEvents++;
        }
    }

    /** Takes and clears the accumulated horizontal movement. */
    public static long takeX() {
        synchronized (LOCK) {
            long value = pendingX;
            pendingX = 0L;
            return value;
        }
    }

    /** Takes and clears the accumulated vertical movement. */
    public static long takeY() {
        synchronized (LOCK) {
            long value = pendingY;
            pendingY = 0L;
            return value;
        }
    }

    /** Throws away everything accumulated so far (used when raw input is not being applied). */
    public static void flush() {
        synchronized (LOCK) {
            pendingX = 0L;
            pendingY = 0L;
        }
    }

    public static boolean isReady() {
        return ready;
    }

    public static boolean isAbsoluteDevice() {
        return absoluteDevice;
    }

    public static boolean isInitialised() {
        return initialised;
    }

    public static String getFailure() {
        return failure;
    }

    public static long getRawEventCount() {
        synchronized (LOCK) {
            return rawEvents;
        }
    }

    public static int getOwnerThreadId() {
        return ownerThreadId;
    }
}
