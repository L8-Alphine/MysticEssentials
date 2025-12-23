package com.alphine.mysticessentials.placeholders;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public final class PlaceholderService {

    public enum UnknownPolicy { KEEP, BLANK }

    private final List<PlaceholderProvider> providers = new CopyOnWriteArrayList<>();

    // knobs (put these in config later if you want)
    private int maxPasses = 4;
    private int maxOutputLen = 32_000;
    private UnknownPolicy unknownPolicy = UnknownPolicy.KEEP;

    public void register(PlaceholderProvider provider) {
        if (provider != null) providers.add(provider);
    }

    public String applyAll(String input, PlaceholderContext ctx, boolean enablePercent, boolean enableBrace) {
        if (input == null || input.isEmpty()) return "";
        String out = input;

        for (int pass = 0; pass < maxPasses; pass++) {
            String before = out;

            if (enableBrace) out = replaceBraces(out, ctx);
            if (enablePercent) out = replacePercents(out, ctx);

            if (out.length() > maxOutputLen) out = out.substring(0, maxOutputLen);
            if (Objects.equals(before, out)) break;
        }
        return out;
    }

    private String replaceBraces(String s, PlaceholderContext ctx) {
        // {key} or {key:arg}
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            int open = s.indexOf('{', i);
            if (open < 0) { sb.append(s, i, s.length()); break; }
            int close = s.indexOf('}', open + 1);
            if (close < 0) { sb.append(s, i, s.length()); break; }

            sb.append(s, i, open);

            String inside = s.substring(open + 1, close).trim();
            String repl = resolveKey(inside, ctx);

            if (repl == null) {
                sb.append(unknownPolicy == UnknownPolicy.BLANK ? "" : s, open, close + 1);
            } else {
                sb.append(repl);
            }

            i = close + 1;
        }
        return sb.toString();
    }

    private String replacePercents(String s, PlaceholderContext ctx) {
        // %key% or %key:arg%
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            int open = s.indexOf('%', i);
            if (open < 0) { sb.append(s, i, s.length()); break; }
            int close = s.indexOf('%', open + 1);
            if (close < 0) { sb.append(s, i, s.length()); break; }

            sb.append(s, i, open);

            String inside = s.substring(open + 1, close).trim();
            String repl = resolveKey(inside, ctx);

            if (repl == null) {
                sb.append(unknownPolicy == UnknownPolicy.BLANK ? "" : s, open, close + 1);
            } else {
                sb.append(repl);
            }

            i = close + 1;
        }
        return sb.toString();
    }

    private String resolveKey(String raw, PlaceholderContext ctx) {
        if (raw.isEmpty()) return null;

        // Optional "key:arg" split (future-friendly)
        String key = raw;
        String arg = null;
        int colon = raw.indexOf(':');
        if (colon > 0) {
            key = raw.substring(0, colon).trim();
            arg = raw.substring(colon + 1).trim();
        }

        // try providers in order
        for (PlaceholderProvider p : providers) {
            String val = p.resolve(key, ctx);
            if (val != null) return val;
        }

        // if you want arg support later, you can add a second provider interface.
        return null;
    }

    // setters if you want
    public void setMaxPasses(int v) { this.maxPasses = Math.max(1, v); }
    public void setUnknownPolicy(UnknownPolicy p) { this.unknownPolicy = p == null ? UnknownPolicy.KEEP : p; }
}
