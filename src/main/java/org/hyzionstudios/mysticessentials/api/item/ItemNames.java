package org.hyzionstudios.mysticessentials.api.item;

/**
 * Shared helpers for turning engine identifiers and translation keys into
 * readable labels.
 *
 * <p>These are <b>formatting</b> helpers only. They never decide whether a value
 * exists — an empty input yields an empty string, and the caller decides what
 * absence means. In particular {@link #prettify(String)} maps {@code "null"} to
 * {@code "Null"} like any other word, because that is a legitimate name.</p>
 */
public final class ItemNames {

    private ItemNames() {
    }

    /**
     * Turns an identifier or key fragment into title case:
     * {@code custom_mod:scarlet_requiem} &rarr; {@code Scarlet Requiem}.
     * Returns an empty string for blank input.
     */
    public static String prettify(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String base = raw;
        int colon = base.indexOf(':');
        if (colon >= 0 && colon + 1 < base.length()) {
            base = base.substring(colon + 1);
        }
        String[] parts = base.split("[_\\s.-]+");
        StringBuilder out = new StringBuilder(base.length());
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                out.append(part, 1, part.length());
            }
        }
        return out.length() == 0 ? base : out.toString();
    }

    /**
     * Prettifies the last dot-separated segment of a translation key:
     * {@code item.damage.basic} &rarr; {@code Basic}.
     */
    public static String prettifyKey(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        String base = key;
        int dot = base.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < base.length()) {
            base = base.substring(dot + 1);
        }
        return prettify(base);
    }

    /** The namespace of {@code itemId} ({@code custom_mod:sword} &rarr; {@code custom_mod}), or {@code null}. */
    public static String namespaceOf(String itemId) {
        if (itemId == null) {
            return null;
        }
        int colon = itemId.indexOf(':');
        return colon > 0 ? itemId.substring(0, colon) : null;
    }

    /** Formats a number without a trailing {@code .0}, using at most two decimals. */
    public static String number(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.001) {
            return Long.toString(Math.round(value));
        }
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    /** Formats a modifier amount with an explicit sign, e.g. {@code +8.1} / {@code -4.8}. */
    public static String signedNumber(double value) {
        String text = number(Math.abs(value));
        return (value < 0 ? "-" : "+") + text;
    }

    /** Formats a min–max pair, collapsing to a single value when they match. */
    public static String range(double min, double max) {
        if (Math.abs(min - max) < 0.001) {
            return number(min);
        }
        return number(min) + "-" + number(max);
    }
}
