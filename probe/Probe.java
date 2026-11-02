import com.rawmousefix.RawMouseCore;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.win32.StdCallLibrary;

/**
 * Standalone probe for the native half of RawMouseFix. Runs the exact same Win32 code the mod runs,
 * but with a hand written message pump so it can be executed outside Minecraft.
 *
 * Compile:  javac -cp jna-3.4.0.jar -d probe/out src/main/java/com/rawmousefix/{Win32,Log,RawMouseCore}.java probe/Probe.java
 * Run:      java -cp "probe/out;jna-3.4.0.jar" Probe
 */
public final class Probe {

    public interface Pump extends StdCallLibrary {
        Pump INSTANCE = (Pump) Native.loadLibrary("user32", Pump.class);

        boolean PeekMessageW(Pointer msg, Pointer hwnd, int filterMin, int filterMax, int removeMsg);

        boolean TranslateMessage(Pointer msg);

        Pointer DispatchMessageW(Pointer msg);

        boolean GetLastInputInfo(Pointer plii);
    }

    public interface Kernel extends StdCallLibrary {
        Kernel INSTANCE = (Kernel) Native.loadLibrary("kernel32", Kernel.class);

        int GetTickCount();
    }

    public static void main(String[] args) throws Exception {
        System.out.println("os=" + System.getProperty("os.name") + " arch=" + System.getProperty("os.arch")
                + " pointerSize=" + Pointer.SIZE);

        RawMouseCore.ensureInitialised();
        System.out.println("engineReady=" + RawMouseCore.isReady());
        System.out.println("failure=" + RawMouseCore.getFailure());
        if (!RawMouseCore.isReady()) {
            System.out.println("RESULT: FAILED");
            return;
        }

        long idle = idleMillis();
        System.out.println("user idle for " + idle + " ms");

        Memory msg = new Memory(64);
        long messages = 0;
        long deadline = System.currentTimeMillis() + 3000L;
        while (System.currentTimeMillis() < deadline) {
            while (Pump.INSTANCE.PeekMessageW(msg, null, 0, 0, 0x0001)) {
                messages++;
                Pump.INSTANCE.TranslateMessage(msg);
                Pump.INSTANCE.DispatchMessageW(msg);
            }
            Thread.sleep(5L);
        }

        System.out.println("pumped messages=" + messages);
        System.out.println("rawEvents=" + RawMouseCore.getRawEventCount());
        System.out.println("accumulated dx=" + RawMouseCore.takeX() + " dy=" + RawMouseCore.takeY());
        System.out.println("RESULT: OK (window created, raw input registered, wndproc survived"
                + " WM_NCCREATE/WM_CREATE/WM_DESTROY)");
    }

    private static long idleMillis() {
        Memory info = new Memory(8);
        info.setInt(0, 8); // sizeof(LASTINPUTINFO)
        if (!Pump.INSTANCE.GetLastInputInfo(info)) {
            return -1L;
        }
        long last = info.getInt(4) & 0xFFFFFFFFL;
        long now = Kernel.INSTANCE.GetTickCount() & 0xFFFFFFFFL;
        return now - last;
    }
}
