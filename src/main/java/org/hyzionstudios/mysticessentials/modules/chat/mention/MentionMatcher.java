package org.hyzionstudios.mysticessentials.modules.chat.mention;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Finds {@code @Name} mentions in a chat message.
 *
 * <p>The matching rules are deliberately strict, because a mention that fires on
 * a near-miss is worse than one that does not fire at all — it trains people to
 * ignore mentions.</p>
 *
 * <ul>
 *   <li><b>Exact names only.</b> The candidate is the whole run of name
 *       characters after the prefix, so {@code @Aeth} does not reach Aether and
 *       {@code @AetherPlayer} does not either. Underscores and digits are part
 *       of a name; punctuation ends it, so {@code @Aether,} and {@code @Aether!}
 *       both resolve cleanly.</li>
 *   <li><b>Case-insensitive</b> by default, since nobody types capitals reliably.</li>
 *   <li><b>Not inside a URL.</b> An {@code @} in {@code https://x.com/@handle} or
 *       an email address is left alone.</li>
 *   <li><b>Not inside a structured token.</b> Item links are already tokenized by
 *       the time this runs, so their contents are invisible here.</li>
 *   <li><b>Word-initial only.</b> The prefix must start a word, so
 *       {@code mail@Aether} is not a mention.</li>
 * </ul>
 */
final class MentionMatcher {

    /** One resolved mention occurrence. */
    record Match(String typed, String resolved, int start, int end, boolean mass) {
    }

    private MentionMatcher() {
    }

    /**
     * Scans {@code message} for mentions.
     *
     * @param resolver     maps a typed name to its canonical form, or {@code null}
     *                     if no such player exists
     * @param massKeywords lowercase keywords that address a group rather than a person
     * @return matches in order of appearance, capped by {@code limit}
     */
    static List<Match> find(String message, String prefix, boolean caseSensitive,
            Function<String, String> resolver, List<String> massKeywords, int limit) {
        List<Match> matches = new ArrayList<>();
        if (message == null || message.isEmpty() || prefix == null || prefix.isEmpty()) {
            return matches;
        }
        int index = 0;
        while (matches.size() < limit && (index = message.indexOf(prefix, index)) >= 0) {
            int nameStart = index + prefix.length();
            if (!startsWord(message, index) || nameStart >= message.length()) {
                index = nameStart;
                continue;
            }
            int nameEnd = nameStart;
            while (nameEnd < message.length() && isNameChar(message.charAt(nameEnd))) {
                nameEnd++;
            }
            if (nameEnd == nameStart) {
                index = nameStart;
                continue;
            }
            String typed = message.substring(nameStart, nameEnd);

            if (isInsideUrl(message, index)) {
                index = nameEnd;
                continue;
            }

            String lower = typed.toLowerCase(Locale.ROOT);
            if (massKeywords.contains(lower)) {
                matches.add(new Match(typed, lower, index, nameEnd, true));
                index = nameEnd;
                continue;
            }

            String resolved = resolver.apply(caseSensitive ? typed : lower);
            if (resolved != null) {
                matches.add(new Match(typed, resolved, index, nameEnd, false));
            }
            index = nameEnd;
        }
        return matches;
    }

    /** Characters that may appear in a player name. */
    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /**
     * Whether the prefix at {@code index} begins a word. Anything alphanumeric
     * immediately before it means this is part of a longer token — an email
     * address, a handle, a path — and not a mention.
     */
    private static boolean startsWord(String message, int index) {
        if (index == 0) {
            return true;
        }
        char before = message.charAt(index - 1);
        return !Character.isLetterOrDigit(before) && before != '_' && before != '.'
                && before != '/' && before != '@';
    }

    /**
     * Whether the {@code @} at {@code index} sits inside a URL. Scans back to the
     * enclosing whitespace-delimited token and checks it for a scheme or a
     * {@code www.} prefix.
     */
    private static boolean isInsideUrl(String message, int index) {
        int tokenStart = index;
        while (tokenStart > 0 && !Character.isWhitespace(message.charAt(tokenStart - 1))) {
            tokenStart--;
        }
        int tokenEnd = index;
        while (tokenEnd < message.length() && !Character.isWhitespace(message.charAt(tokenEnd))) {
            tokenEnd++;
        }
        String token = message.substring(tokenStart, tokenEnd).toLowerCase(Locale.ROOT);
        return token.contains("://") || token.startsWith("www.");
    }
}
