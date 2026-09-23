package com.weave.lunarmods.internal;

/** Console logging with the library prefix; Weave captures standard streams into its log. */
public final class Log {
    private static final String PREFIX = "[Lunar Mod API] ";

    private Log() {
    }

    public static void info(String message) {
        System.out.println(PREFIX + message);
    }

    public static void warn(String message) {
        System.err.println(PREFIX + message);
    }

    public static void error(String message, Throwable cause) {
        System.err.println(PREFIX + message + (cause == null ? "" : ": " + cause));
        if (cause != null) {
            cause.printStackTrace();
        }
    }
}
