package com.rawmousefix;

import net.minecraft.client.Minecraft;
import net.minecraft.util.MouseHelper;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;

/**
 * Vanilla {@link MouseHelper} replacement that feeds the camera from Win32 raw input instead of
 * LWJGL 2's grabbed-cursor deltas.
 *
 * <p>Everything the game does with the helper (grabbing, ungrabbing, hdeltas) is inherited; only the
 * per-frame delta source changes. When raw input is unavailable, unsupported or disabled, this class
 * behaves exactly like the vanilla helper.</p>
 */
public class RawMouseHelper extends MouseHelper {

    /** Sub-count remainder of the scale factor, so fractional scaling does not drift. */
    private double residualX;
    private double residualY;

    /** True while the camera is actually being driven by raw input. */
    private boolean applying;

    private boolean warnedAboutFailure;

    @Override
    public void mouseXYChange() {
        try {
            rawMouseXYChange();
        } catch (Throwable t) {
            // A launcher without JNA, an unexpected Win32 failure, anything: never take the game down.
            if (!warnedAboutFailure) {
                warnedAboutFailure = true;
                Log.error("raw input path failed (%s); falling back to vanilla mouse handling",
                        String.valueOf(t));
            }
            applying = false;
            super.mouseXYChange();
        }
    }

    private void rawMouseXYChange() {
        RawMouseCore.ensureInitialised();

        if (!RawMouseFix.isRawInputWanted()) {
            if (applying) {
                applying = false;
                RawMouseCore.flush();
            }
            super.mouseXYChange();
            return;
        }

        if (!applying) {
            // Entering the world or closing a GUI: everything gathered while the camera was not under
            // raw control is stale and would otherwise snap the view.
            applying = true;
            RawMouseCore.flush();
            RawMouseCore.drainQueue();
            setDeltas(0, 0);
            return;
        }

        RawMouseCore.drainQueue();

        long rawX = RawMouseCore.takeX();
        // RAWMOUSE.lLastY is positive downwards (screen coordinates), while Minecraft's camera wants
        // the LWJGL convention where +deltaY means "moved up": EntityRenderer does
        // thePlayer.setAngles(dx * scale, dy * scale), which applies rotationPitch -= dy * 0.15.
        // Without this negation the pitch axis ends up inverted.
        long rawY = -RawMouseCore.takeY();

        double scale = RawMouseFix.getScaleFactor();
        double scaledX = rawX * scale + residualX;
        double scaledY = rawY * scale + residualY;
        int outX = (int) Math.floor(scaledX);
        int outY = (int) Math.floor(scaledY);
        residualX = scaledX - outX;
        residualY = scaledY - outY;

        setDeltas(outX, outY);
    }

    private void setDeltas(int x, int y) {
        // Written through a vanilla typed reference so the obfuscation remapper resolves the fields.
        MouseHelper self = this;
        self.deltaX = x;
        self.deltaY = y;
    }

    /** Debug aid: how many raw reports the engine has seen. */
    public long rawEventCount() {
        return RawMouseCore.getRawEventCount();
    }

    /** True when this instance currently drives the camera from raw input. */
    public boolean isApplyingRawInput() {
        return applying;
    }

    /** True when the world is running and the game owns the mouse. */
    static boolean inGame() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc != null && mc.inGameHasFocus && Display.isActive() && Mouse.isGrabbed();
    }
}
