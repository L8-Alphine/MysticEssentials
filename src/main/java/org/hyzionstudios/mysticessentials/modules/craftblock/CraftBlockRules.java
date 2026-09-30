package org.hyzionstudios.mysticessentials.modules.craftblock;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * An immutable, pre-compiled snapshot of the configured block list.
 *
 * <p>Compiled once per config load and published to the crafting system as a
 * whole object, so the world threads that evaluate it never see a half-updated
 * list while {@code /mystic reload} is running.</p>
 *
 * <p>Matching is case-insensitive; entries containing {@code *} become glob
 * patterns ({@code Mcw_*}, {@code *_bench}).</p>
 */
public final class CraftBlockRules {

    private static final CraftBlockRules EMPTY = new CraftBlockRules(Set.of(), List.of(), List.of());

    /** Exact ids, already lower-cased. */
    private final Set<String> exact;
    /** Compiled glob entries. */
    private final List<Pattern> patterns;
    /** The configured entries as written, for display. */
    private final List<String> entries;

    private CraftBlockRules(Set<String> exact, List<Pattern> patterns, List<String> entries) {
        this.exact = exact;
        this.patterns = patterns;
        this.entries = entries;
    }

    public static CraftBlockRules empty() {
        return EMPTY;
    }

    /** Compiles configured entries; null/blank entries are skipped. */
    public static CraftBlockRules compile(List<String> configured) {
        if (configured == null || configured.isEmpty()) {
            return EMPTY;
        }
        Set<String> exact = new LinkedHashSet<>();
        List<Pattern> patterns = new ArrayList<>();
        List<String> entries = new ArrayList<>();
        for (String raw : configured) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String entry = raw.trim();
            entries.add(entry);
            String normalized = entry.toLowerCase(Locale.ROOT);
            if (normalized.indexOf('*') >= 0) {
                patterns.add(Pattern.compile(globToRegex(normalized)));
            } else {
                exact.add(normalized);
            }
        }
        if (exact.isEmpty() && patterns.isEmpty()) {
            return EMPTY;
        }
        return new CraftBlockRules(exact, patterns, List.copyOf(entries));
    }

    public boolean isEmpty() {
        return exact.isEmpty() && patterns.isEmpty();
    }

    /** The configured entries exactly as written, for {@code /craftblock}. */
    public List<String> entries() {
        return entries;
    }

    /** @return whether {@code id} (an item id or recipe id) is blocked. */
    public boolean matches(String id) {
        if (id == null || id.isEmpty() || isEmpty()) {
            return false;
        }
        String normalized = id.toLowerCase(Locale.ROOT);
        if (exact.contains(normalized)) {
            return true;
        }
        for (Pattern pattern : patterns) {
            if (pattern.matcher(normalized).matches()) {
                return true;
            }
        }
        return false;
    }

    /** Translates a {@code *} glob into a regex, quoting everything else. */
    private static String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        int start = 0;
        for (int i = 0; i < glob.length(); i++) {
            if (glob.charAt(i) == '*') {
                if (i > start) {
                    regex.append(Pattern.quote(glob.substring(start, i)));
                }
                regex.append(".*");
                start = i + 1;
            }
        }
        if (start < glob.length()) {
            regex.append(Pattern.quote(glob.substring(start)));
        }
        return regex.toString();
    }
}
