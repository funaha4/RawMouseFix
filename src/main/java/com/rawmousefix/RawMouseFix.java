package com.rawmousefix;

import java.io.File;

import net.minecraft.client.Minecraft;
import net.minecraft.util.MouseHelper;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * RawMouseFix — replaces the unreliable LWJGL 2 grabbed-mouse deltas of Minecraft 1.8.9 with Win32
 * raw input, so high polling rate mice (500 Hz and above) no longer make the camera jerk or run
 * away.
 */
@Mod(modid = RawMouseFix.MODID, name = RawMouseFix.NAME, version = RawMouseFix.VERSION,
        clientSideOnly = true, acceptableRemoteVersions = "*")
public class RawMouseFix {

    public static final String MODID = "rawmousefix";
    public static final String NAME = "RawMouseFix";
    public static final String VERSION = "1.0.0";

    private static final Logger LOGGER = LogManager.getLogger(NAME);

    private static RawMouseFix instance;

    private Configuration configuration;
    private boolean enabled = true;
    private double scaleFactor = 1.0D;
    private boolean foreignHelperWarned;
    private boolean tickFailureLogged;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        instance = this;

        Log.setSink(new Log.Sink() {
            @Override
            public void log(String level, String message) {
                if ("ERROR".equals(level)) {
                    LOGGER.error(message);
                } else if ("WARN".equals(level)) {
                    LOGGER.warn(message);
                } else {
                    LOGGER.info(message);
                }
            }
        });

        loadConfiguration();
        ClientCommandHandler.instance.registerCommand(new CommandRawMouseFix());
        MinecraftForge.EVENT_BUS.register(this);
        LOGGER.info("loaded (enabled={}, scaleFactor={}); raw input will be set up on the first in-game frame",
                enabled, scaleFactor);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        try {
            installHelper();
            if (!isRawInputWanted()) {
                RawMouseCore.flush();
            }
        } catch (Throwable t) {
            if (!tickFailureLogged) {
                tickFailureLogged = true;
                LOGGER.error("tick handling failed; the vanilla mouse path stays in place", t);
            }
        }
    }

    /**
     * Swaps {@code Minecraft.mouseHelper} for our subclass. Re-run every tick so it also recovers if
     * another mod or the game resets the field, while leaving a deliberate replacement alone.
     */
    private void installHelper() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        MouseHelper current = mc.mouseHelper;
        if (current instanceof RawMouseHelper) {
            return;
        }
        if (current != null && current.getClass() != MouseHelper.class) {
            if (!foreignHelperWarned) {
                foreignHelperWarned = true;
                LOGGER.warn("Minecraft.mouseHelper was replaced by {}; leaving it alone. "
                        + "RawMouseFix will not be able to affect the camera.",
                        current.getClass().getName());
            }
            return;
        }
        try {
            mc.mouseHelper = new RawMouseHelper();
            LOGGER.info("installed raw mouse helper");
        } catch (Throwable t) {
            LOGGER.error("could not install the raw mouse helper", t);
        }
    }

    /** True when the camera should currently be driven by raw input. */
    public static boolean isRawInputWanted() {
        RawMouseFix mod = instance;
        if (mod == null || !mod.enabled) {
            return false;
        }
        if (!RawMouseCore.isReady() || RawMouseCore.isAbsoluteDevice()) {
            return false;
        }
        return RawMouseHelper.inGame();
    }

    public static double getScaleFactor() {
        RawMouseFix mod = instance;
        return mod == null ? 1.0D : mod.scaleFactor;
    }

    public static boolean isEnabledSetting() {
        RawMouseFix mod = instance;
        return mod == null || mod.enabled;
    }

    public static void setScaleFactor(double value) {
        RawMouseFix mod = instance;
        if (mod == null) {
            return;
        }
        mod.scaleFactor = Math.max(0.05D, Math.min(20.0D, value));
        if (mod.configuration != null) {
            mod.configuration.get(Configuration.CATEGORY_GENERAL, "scaleFactor", 1.0D,
                    "Multiplier applied to raw mouse counts. Leave at 1.0 for a 1:1 mapping pixel "
                            + "mapping with the default Windows pointer speed; lower it if the camera "
                            + "feels too fast after switching to raw input.")
                    .set(mod.scaleFactor);
            mod.configuration.save();
        }
    }

    public static void setEnabledSetting(boolean value) {
        RawMouseFix mod = instance;
        if (mod == null) {
            return;
        }
        mod.enabled = value;
        if (mod.configuration != null) {
            mod.configuration.get(Configuration.CATEGORY_GENERAL, "enabled", true,
                    "Drive the in-game camera from Win32 raw input instead of LWJGL 2's grabbed "
                            + "cursor deltas.").set(value);
            mod.configuration.save();
        }
        if (!value) {
            RawMouseCore.flush();
        }
    }

    public static void reloadConfiguration() {
        RawMouseFix mod = instance;
        if (mod != null) {
            mod.loadConfiguration();
        }
    }

    private void loadConfiguration() {
        try {
            configuration = new Configuration(new File(new File("config"), "rawmousefix.cfg"));
            configuration.load();
            enabled = configuration.getBoolean("enabled", Configuration.CATEGORY_GENERAL, true,
                    "Drive the in-game camera from Win32 raw input instead of LWJGL 2's grabbed "
                            + "cursor deltas. Disable to compare against vanilla behaviour.");
            scaleFactor = configuration.getFloat("scaleFactor", Configuration.CATEGORY_GENERAL,
                    1.0F, 0.05F, 20.0F,
                    "Multiplier applied to raw mouse counts. 1.0 matches the default Windows pointer "
                            + "speed (1 count = 1 pixel); lower it if the camera feels too fast.");
            if (configuration.hasChanged()) {
                configuration.save();
            }
        } catch (Throwable t) {
            LOGGER.error("could not read the configuration, using defaults", t);
        }
    }

    public static String describeState() {
        StringBuilder sb = new StringBuilder();
        sb.append("enabled=").append(isEnabledSetting());
        sb.append(", scaleFactor=").append(getScaleFactor());
        sb.append(", engineReady=").append(RawMouseCore.isReady());
        sb.append(", absoluteDevice=").append(RawMouseCore.isAbsoluteDevice());
        sb.append(", rawEvents=").append(RawMouseCore.getRawEventCount());
        String failure = RawMouseCore.getFailure();
        if (failure != null) {
            sb.append(", reason=").append(failure);
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null) {
            MouseHelper helper = mc.mouseHelper;
            sb.append(", helper=").append(helper == null ? "null" : helper.getClass().getSimpleName());
            if (helper instanceof RawMouseHelper) {
                sb.append(", applying=").append(((RawMouseHelper) helper).isApplyingRawInput());
            }
        }
        return sb.toString();
    }
}
