package org.hyzionstudios.mysticessentials.core.util;

/** Human-readable duration formatting shared by messages, UI, and placeholders. */
public final class Durations {

    private Durations() {
    }

    /**
     * Formats a second count as {@code "2d 3h 15m 4s"}, dropping leading units
     * that are zero. Always renders at least a seconds component, so
     * {@code 0} becomes {@code "0s"}.
     */
    public static String format(long seconds) {
        long s = Math.max(0, seconds);
        long days = s / 86400;
        long hours = (s % 86400) / 3600;
        long minutes = (s % 3600) / 60;
        long secs = s % 60;
        StringBuilder result = new StringBuilder();
        if (days > 0) {
            result.append(days).append("d ");
        }
        if (hours > 0) {
            result.append(hours).append("h ");
        }
        if (minutes > 0) {
            result.append(minutes).append("m ");
        }
        if (result.isEmpty() || secs > 0) {
            result.append(secs).append("s");
        }
        return result.toString().trim();
    }

    /** Whole hours in {@code seconds}, as a plain number (e.g. {@code "37"}). */
    public static String hours(long seconds) {
        return Long.toString(Math.max(0, seconds) / 3600);
    }
}
