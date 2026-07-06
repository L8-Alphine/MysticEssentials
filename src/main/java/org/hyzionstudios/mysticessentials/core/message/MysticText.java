package org.hyzionstudios.mysticessentials.core.message;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.hypixel.hytale.server.core.Message;

/**
 * Parses Mystic Essentials' markup into a Hytale {@link Message} tree with
 * explicit per-segment colours and styles.
 *
 * <p>Colours are emitted as {@code #RRGGBB} strings (the format the Hytale
 * protocol accepts — {@code ^\s*#([0-9a-fA-F]{3}){1,2}$}) directly onto each
 * segment, rather than relying on client-side markup, so rendering is fully
 * under our control and works regardless of what the client parses. Note that
 * {@code Message.parse} is a JSON reader, <b>not</b> a markup parser — this class
 * is what turns human-written colour codes into a renderable message.</p>
 *
 * <p>Supported markup:</p>
 * <ul>
 *   <li>Legacy: {@code &a}, {@code &c} … colours; {@code &l} bold, {@code &o}
 *       italic, {@code &n} underline, {@code &r} reset ({@code &m}/{@code &k}
 *       have no protocol field and are ignored).</li>
 *   <li>Hex: {@code &#ff00ff} and {@code <#ff00ff>} (also 3-digit {@code #f0f}).</li>
 *   <li>MiniMessage-style tags: {@code <red>}, {@code <color:#ff0000>},
 *       {@code <bold>}/{@code <b>}, {@code <italic>}/{@code <i>},
 *       {@code <underlined>}/{@code <u>}, {@code <reset>}, and their closing
 *       {@code </…>} forms.</li>
 *   <li>{@code <gradient:#a:#b[:#c…]>text</gradient>} and
 *       {@code <rainbow>text</rainbow>} (interpolated per character).</li>
 *   <li>{@code <link:https://…>text</link>} — attaches a clickable link/action to
 *       the enclosed text (the protocol {@code link} field).</li>
 *   <li>{@code <lang:some.translation.key>} — a client-side translated segment
 *       (the protocol {@code messageId} field).</li>
 * </ul>
 *
 * <p><b>Not supported:</b> hover text — the 0.5.6 {@code FormattedMessage} protocol
 * has no hover field (only {@code link}), so hover events cannot be represented.</p>
 */
public final class MysticText {

    private static final Map<Character, String> LEGACY_COLORS = Map.ofEntries(
            Map.entry('0', "#000000"), Map.entry('1', "#0000AA"), Map.entry('2', "#00AA00"),
            Map.entry('3', "#00AAAA"), Map.entry('4', "#AA0000"), Map.entry('5', "#AA00AA"),
            Map.entry('6', "#FFAA00"), Map.entry('7', "#AAAAAA"), Map.entry('8', "#555555"),
            Map.entry('9', "#5555FF"), Map.entry('a', "#55FF55"), Map.entry('b', "#55FFFF"),
            Map.entry('c', "#FF5555"), Map.entry('d', "#FF55FF"), Map.entry('e', "#FFFF55"),
            Map.entry('f', "#FFFFFF"));

    private static final Map<String, String> NAMED_COLORS = Map.ofEntries(
            Map.entry("black", "#000000"), Map.entry("dark_blue", "#0000AA"),
            Map.entry("dark_green", "#00AA00"), Map.entry("dark_aqua", "#00AAAA"),
            Map.entry("dark_red", "#AA0000"), Map.entry("dark_purple", "#AA00AA"),
            Map.entry("gold", "#FFAA00"), Map.entry("gray", "#AAAAAA"), Map.entry("grey", "#AAAAAA"),
            Map.entry("dark_gray", "#555555"), Map.entry("dark_grey", "#555555"),
            Map.entry("blue", "#5555FF"), Map.entry("green", "#55FF55"), Map.entry("aqua", "#55FFFF"),
            Map.entry("red", "#FF5555"), Map.entry("light_purple", "#FF55FF"),
            Map.entry("yellow", "#FFFF55"), Map.entry("white", "#FFFFFF"));

    private MysticText() {
    }

    /** Parses markup into a renderable {@link Message}. Never throws; falls back to raw text. */
    public static Message parse(String input) {
        if (input == null || input.isEmpty()) {
            return Message.empty();
        }
        try {
            List<Segment> segments = new Scanner(input).scan();
            return build(segments, input);
        } catch (Throwable t) {
            return Message.raw(input);
        }
    }

    /** Removes supported Mystic Essentials colour/style markup for plain-text UI fields. */
    public static String stripMarkup(String text) {
        return stripColors(text);
    }

    private static Message build(List<Segment> segments, String original) {
        if (segments.isEmpty()) {
            return Message.empty();
        }
        if (segments.size() == 1 && segments.get(0).isPlain()) {
            return toMessage(segments.get(0));
        }
        Message root = Message.raw("");
        List<Message> children = new ArrayList<>(segments.size());
        for (Segment segment : segments) {
            children.add(toMessage(segment));
        }
        root.insertAll(children);
        return root;
    }

    private static Message toMessage(Segment segment) {
        Message message = segment.translationKey != null
                ? Message.translation(segment.translationKey)
                : Message.raw(segment.text);
        if (segment.color != null) {
            message.color(segment.color);
        }
        if (segment.bold) {
            message.bold(true);
        }
        if (segment.italic) {
            message.italic(true);
        }
        if (segment.underlined) {
            // Message exposes no underlined() setter, but the protocol field is public.
            message.getFormattedMessage().underlined = Boolean.TRUE;
        }
        if (segment.link != null) {
            message.link(segment.link);
        }
        return message;
    }

    // ----- Parsing -----------------------------------------------------------

    private static final class Segment {
        final String text;
        final String color;
        final boolean bold;
        final boolean italic;
        final boolean underlined;
        final String link;
        final String translationKey;

        Segment(String text, String color, boolean bold, boolean italic, boolean underlined,
                String link, String translationKey) {
            this.text = text;
            this.color = color;
            this.bold = bold;
            this.italic = italic;
            this.underlined = underlined;
            this.link = link;
            this.translationKey = translationKey;
        }

        boolean isPlain() {
            return color == null && !bold && !italic && !underlined && link == null && translationKey == null;
        }
    }

    /** Single-pass, allocation-light scanner over the markup string. */
    private static final class Scanner {
        private final String in;
        private final int len;
        private final List<Segment> out = new ArrayList<>();
        private final StringBuilder run = new StringBuilder();

        private String color;
        private boolean bold;
        private boolean italic;
        private boolean underlined;
        private String link;

        Scanner(String in) {
            this.in = in;
            this.len = in.length();
        }

        List<Segment> scan() {
            int i = 0;
            while (i < len) {
                char c = in.charAt(i);
                if (c == '&' && i + 1 < len) {
                    i = handleAmp(i);
                } else if (c == '<') {
                    int next = handleTag(i);
                    i = next < 0 ? appendLiteral(i) : next;
                } else {
                    run.append(c);
                    i++;
                }
            }
            flush();
            return out;
        }

        private int appendLiteral(int i) {
            run.append(in.charAt(i));
            return i + 1;
        }

        private int handleAmp(int i) {
            char code = in.charAt(i + 1);
            if (code == '#' && hasHex(i + 2, 6)) {
                flush();
                color = "#" + in.substring(i + 2, i + 8).toUpperCase(Locale.ROOT);
                return i + 8;
            }
            char lower = Character.toLowerCase(code);
            if (LEGACY_COLORS.containsKey(lower)) {
                flush();
                color = LEGACY_COLORS.get(lower);
                return i + 2;
            }
            switch (lower) {
                case 'l' -> {
                    flush();
                    bold = true;
                }
                case 'o' -> {
                    flush();
                    italic = true;
                }
                case 'n' -> {
                    flush();
                    underlined = true;
                }
                case 'r' -> {
                    flush();
                    reset();
                }
                case 'm', 'k' -> {
                    // strikethrough / obfuscated: no protocol field — drop the code.
                }
                default -> {
                    // Unknown code: keep the ampersand literally.
                    run.append('&').append(code);
                }
            }
            return i + 2;
        }

        /** @return index after the tag, or -1 if this {@code <} is not a recognized tag. */
        private int handleTag(int i) {
            int close = in.indexOf('>', i);
            if (close < 0) {
                return -1;
            }
            String tag = in.substring(i + 1, close).trim();
            String lower = tag.toLowerCase(Locale.ROOT);

            // <#rrggbb> or <#rgb>
            if (tag.startsWith("#") && isHexColor(tag.substring(1))) {
                flush();
                color = normalizeHex(tag.substring(1));
                return close + 1;
            }
            // <gradient:...> ... </gradient>
            if (lower.startsWith("gradient:")) {
                return handleGradient(tag.substring("gradient:".length()), close + 1);
            }
            if (lower.equals("rainbow")) {
                return handleRainbow(close + 1);
            }
            // <lang:key> — emit a client-translated segment immediately.
            if (lower.startsWith("lang:")) {
                String key = tag.substring("lang:".length()).trim();
                if (!key.isEmpty()) {
                    flush();
                    out.add(new Segment("", color, bold, italic, underlined, link, key));
                    return close + 1;
                }
                return -1;
            }
            // <link:target> ... </link>
            if (lower.startsWith("link:") || lower.startsWith("url:")) {
                flush();
                link = tag.substring(tag.indexOf(':') + 1).trim();
                return close + 1;
            }
            if (lower.equals("/link") || lower.equals("/url")) {
                flush();
                link = null;
                return close + 1;
            }
            // <color:#hex> / <c:#hex>
            if (lower.startsWith("color:") || lower.startsWith("c:")) {
                String value = tag.substring(tag.indexOf(':') + 1).trim();
                String resolved = resolveColor(value);
                if (resolved != null) {
                    flush();
                    color = resolved;
                    return close + 1;
                }
                return -1;
            }
            // named color open
            if (NAMED_COLORS.containsKey(lower)) {
                flush();
                color = NAMED_COLORS.get(lower);
                return close + 1;
            }
            // style + reset + closing tags
            switch (lower) {
                case "bold", "b" -> {
                    flush();
                    bold = true;
                    return close + 1;
                }
                case "italic", "i", "em" -> {
                    flush();
                    italic = true;
                    return close + 1;
                }
                case "underlined", "u" -> {
                    flush();
                    underlined = true;
                    return close + 1;
                }
                case "reset", "r" -> {
                    flush();
                    reset();
                    return close + 1;
                }
                case "/bold", "/b" -> {
                    flush();
                    bold = false;
                    return close + 1;
                }
                case "/italic", "/i", "/em" -> {
                    flush();
                    italic = false;
                    return close + 1;
                }
                case "/underlined", "/u" -> {
                    flush();
                    underlined = false;
                    return close + 1;
                }
                default -> {
                    // closing colour tags (</red>, </color>, </c>, </#hex>) revert to default colour.
                    if (lower.startsWith("/")) {
                        String name = lower.substring(1);
                        if (name.equals("color") || name.equals("c") || name.startsWith("#")
                                || NAMED_COLORS.containsKey(name)) {
                            flush();
                            color = null;
                            return close + 1;
                        }
                    }
                    return -1;
                }
            }
        }

        private int handleGradient(String spec, int contentStart) {
            List<String> stops = new ArrayList<>();
            for (String part : spec.split(":")) {
                String resolved = resolveColor(part.trim());
                if (resolved != null) {
                    stops.add(resolved);
                }
            }
            int end = indexOfClose("gradient", contentStart);
            String inner = stripColors(in.substring(contentStart, end < 0 ? len : end));
            if (stops.size() < 2 || inner.isEmpty()) {
                run.append(inner);
            } else {
                flush();
                emitGradient(inner, stops);
            }
            return end < 0 ? len : end + "</gradient>".length();
        }

        private int handleRainbow(int contentStart) {
            int end = indexOfClose("rainbow", contentStart);
            String inner = stripColors(in.substring(contentStart, end < 0 ? len : end));
            flush();
            for (int i = 0; i < inner.length(); i++) {
                float hue = inner.length() <= 1 ? 0f : (float) i / inner.length();
                out.add(new Segment(String.valueOf(inner.charAt(i)),
                        hsbToHex(hue), bold, italic, underlined, link, null));
            }
            return end < 0 ? len : end + "</rainbow>".length();
        }

        private void emitGradient(String text, List<String> stops) {
            int n = text.length();
            for (int i = 0; i < n; i++) {
                float t = n <= 1 ? 0f : (float) i / (n - 1);
                out.add(new Segment(String.valueOf(text.charAt(i)),
                        interpolate(stops, t), bold, italic, underlined, link, null));
            }
        }

        private int indexOfClose(String tag, int from) {
            return in.toLowerCase(Locale.ROOT).indexOf("</" + tag + ">", from);
        }

        private void flush() {
            if (run.length() > 0) {
                out.add(new Segment(run.toString(), color, bold, italic, underlined, link, null));
                run.setLength(0);
            }
        }

        private void reset() {
            color = null;
            bold = false;
            italic = false;
            underlined = false;
            link = null;
        }

        private boolean hasHex(int start, int count) {
            if (start + count > len) {
                return false;
            }
            for (int i = start; i < start + count; i++) {
                if (Character.digit(in.charAt(i), 16) < 0) {
                    return false;
                }
            }
            return true;
        }
    }

    // ----- Colour helpers ----------------------------------------------------

    private static String resolveColor(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (NAMED_COLORS.containsKey(lower)) {
            return NAMED_COLORS.get(lower);
        }
        String hex = value.startsWith("#") ? value.substring(1) : value;
        return isHexColor(hex) ? normalizeHex(hex) : null;
    }

    private static boolean isHexColor(String hex) {
        if (hex.length() != 3 && hex.length() != 6) {
            return false;
        }
        for (int i = 0; i < hex.length(); i++) {
            if (Character.digit(hex.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static String normalizeHex(String hex) {
        if (hex.length() == 3) {
            StringBuilder sb = new StringBuilder("#");
            for (int i = 0; i < 3; i++) {
                sb.append(hex.charAt(i)).append(hex.charAt(i));
            }
            return sb.toString().toUpperCase(Locale.ROOT);
        }
        return "#" + hex.toUpperCase(Locale.ROOT);
    }

    private static String interpolate(List<String> stops, float t) {
        if (stops.size() == 1) {
            return stops.get(0);
        }
        float scaled = t * (stops.size() - 1);
        int idx = Math.min((int) scaled, stops.size() - 2);
        float local = scaled - idx;
        int[] a = rgb(stops.get(idx));
        int[] b = rgb(stops.get(idx + 1));
        int r = Math.round(a[0] + (b[0] - a[0]) * local);
        int g = Math.round(a[1] + (b[1] - a[1]) * local);
        int bl = Math.round(a[2] + (b[2] - a[2]) * local);
        return toHex(r, g, bl);
    }

    private static int[] rgb(String hex) {
        int v = Integer.parseInt(hex.substring(1), 16);
        return new int[] {(v >> 16) & 0xFF, (v >> 8) & 0xFF, v & 0xFF};
    }

    private static String hsbToHex(float hue) {
        int rgb = java.awt.Color.HSBtoRGB(hue, 0.9f, 1.0f);
        return toHex((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
    }

    private static String toHex(int r, int g, int b) {
        return String.format("#%02X%02X%02X", clamp(r), clamp(g), clamp(b));
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    /** Removes colour markup from text (used inside gradient/rainbow, which supply the colour). */
    private static String stripColors(String text) {
        return text
                .replaceAll("&#[0-9a-fA-F]{6}", "")
                .replaceAll("(?i)&[0-9a-fk-or]", "")
                .replaceAll("<#([0-9a-fA-F]{3}){1,2}>", "")
                .replaceAll("(?i)</?(color|c|gradient|rainbow|bold|b|italic|i|em|underlined|u|reset|r)(:[^>]*)?>", "")
                .replaceAll("(?i)</?(black|dark_blue|dark_green|dark_aqua|dark_red|dark_purple|gold|gr[ae]y|dark_gr[ae]y|blue|green|aqua|red|light_purple|yellow|white)>", "");
    }
}
