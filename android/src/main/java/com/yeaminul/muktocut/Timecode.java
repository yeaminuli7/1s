package com.yeaminul.muktocut;

import java.util.Locale;

public final class Timecode {
    private Timecode() {}

    public static String clock(long us) {
        long totalSeconds = Math.max(0, us) / 1_000_000L;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    public static String precise(long us) {
        long safe = Math.max(0, us);
        long centis = (safe / 10_000L) % 100;
        return clock(safe) + String.format(Locale.US, ".%02d", centis);
    }

    public static String seconds(long us) {
        return String.format(Locale.US, "%.2f", Math.max(0, us) / 1_000_000.0);
    }
}
