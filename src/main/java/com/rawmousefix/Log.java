package com.rawmousefix;

import java.io.PrintStream;

/**
 * Tiny logging facade so {@link RawMouseCore} does not have to depend on Forge or log4j. The mod
 * swaps {@link #setSink(Sink)} for a log4j backed sink at startup.
 */
public final class Log {

    public interface Sink {
        void log(String level, String message);
    }

    private static volatile Sink sink = new Sink() {
        @Override
        public void log(String level, String message) {
            PrintStream out = "ERROR".equals(level) || "WARN".equals(level) ? System.err : System.out;
            out.println("[RawMouseFix/" + level + "] " + message);
        }
    };

    private Log() {
    }

    public static void setSink(Sink newSink) {
        if (newSink != null) {
            sink = newSink;
        }
    }

    public static void info(String format, Object... args) {
        emit("INFO", format, args);
    }

    public static void warn(String format, Object... args) {
        emit("WARN", format, args);
    }

    public static void error(String format, Object... args) {
        emit("ERROR", format, args);
    }

    private static void emit(String level, String format, Object... args) {
        String message;
        try {
            message = args == null || args.length == 0 ? format : String.format(format, args);
        } catch (Throwable t) {
            message = format;
        }
        try {
            sink.log(level, message);
        } catch (Throwable ignored) {
            // logging must never break the game
        }
    }
}
