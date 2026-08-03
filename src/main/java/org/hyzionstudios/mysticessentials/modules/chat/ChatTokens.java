package org.hyzionstudios.mysticessentials.modules.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The structured token layer that sits between a player's raw message and the
 * rendered chat line.
 *
 * <p>Item links and mentions travel through the pipeline as <b>tokens</b> — a
 * NUL-delimited {@code item:code} or {@code mention:name} — not as finished
 * markup. A token is expanded into client-visible markup only at the final
 * render step, and only by this mod.</p>
 *
 * <p>That indirection is what makes the raw-markup class of bug impossible
 * rather than merely unlikely. Rewriting an item link into
 * {@code <link:/itemview ab12>…</link>} at detection time meant every downstream
 * consumer of the message — the cross-server relay, the
 * {@code ChatMessagePublishedEvent} bridge, moderation logs, a Discord bridge —
 * received markup and showed it verbatim. Tokens are inert everywhere except the
 * one place that understands them, and {@link #toPlainText(String)} gives every
 * plain-text sink a clean rendering.</p>
 *
 * <p>The delimiter is {@code U+0000}, stripped from player input before
 * tokenization, so a player cannot forge a token by typing one.</p>
 */
public final class ChatTokens {

    /** Delimiter around a structured token. Never legal in player input. */
    public static final char DELIMITER = (char) 0;

    /** {@link #DELIMITER} as a string, for concatenation. */
    private static final String D = String.valueOf(DELIMITER);

    private static final Pattern TOKEN =
            Pattern.compile("\\x00(item|mention):([^\\x00]*)\\x00");

    /** Any residual chat markup, used by the final safety net. */
    private static final Pattern ANY_MARKUP = Pattern.compile("(?i)</?(?:link|url|lang)(:[^>]*)?>");

    private ChatTokens() {
    }

    /** A decoded token occurrence within a message. */
    public record Token(String type, String value) {

        public boolean isItem() {
            return "item".equals(type);
        }

        public boolean isMention() {
            return "mention".equals(type);
        }
    }

    /**
     * Removes any token delimiter a player typed, so their text can never be
     * mistaken for a token this mod emitted. Must run before tokenization.
     */
    public static String sanitizeInput(String message) {
        return message == null ? null : message.replace(DELIMITER, ' ');
    }

    public static String itemToken(String snapshotId) {
        return D + "item:" + safeValue(snapshotId) + D;
    }

    public static String mentionToken(String playerName) {
        return D + "mention:" + safeValue(playerName) + D;
    }

    /** Whether {@code message} carries any structured token. */
    public static boolean hasTokens(String message) {
        return message != null && message.indexOf(DELIMITER) >= 0;
    }

    /** Every token in {@code message}, in order of appearance. */
    public static List<Token> tokens(String message) {
        List<Token> out = new ArrayList<>();
        if (message == null) {
            return out;
        }
        Matcher matcher = TOKEN.matcher(message);
        while (matcher.find()) {
            out.add(new Token(matcher.group(1), matcher.group(2)));
        }
        return out;
    }

    /**
     * Expands every token with {@code expander}, leaving surrounding text
     * untouched. An expander returning {@code null} falls back to the token's
     * plain form, so an unresolvable link degrades to readable text rather than
     * leaking its internal representation.
     */
    public static String expand(String message, Function<Token, String> expander) {
        if (!hasTokens(message)) {
            return message;
        }
        Matcher matcher = TOKEN.matcher(message);
        StringBuilder out = new StringBuilder(message.length() + 64);
        while (matcher.find()) {
            Token token = new Token(matcher.group(1), matcher.group(2));
            String replacement = expander.apply(token);
            if (replacement == null) {
                replacement = plainFor(token);
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * The plain-text rendering of a tokenized message: {@code [Item]} for an item
     * link, {@code @Aether} for a mention. This is what every non-chat consumer
     * should be handed — logs, the cross-server relay, external bridges.
     *
     * <p>It also strips stray chat markup as a last resort. Nothing should reach
     * here carrying markup, but a plain-text sink is precisely where leaked
     * markup would become visible to players, so the net is worth its cost.</p>
     */
    public static String toPlainText(String message) {
        if (message == null) {
            return null;
        }
        return ANY_MARKUP.matcher(expand(message, ChatTokens::plainFor)).replaceAll("");
    }

    /**
     * Plain text with item tokens resolved through {@code itemLabels}. Used by
     * the cross-server relay, where the label must be resolved on the sending
     * server because the receiving one holds no snapshot.
     */
    public static String toPlainText(String message, Function<String, String> itemLabels) {
        if (message == null) {
            return null;
        }
        String expanded = expand(message, token -> {
            if (!token.isItem()) {
                return plainFor(token);
            }
            String label = itemLabels.apply(token.value());
            return "[" + (label == null || label.isBlank() ? "Item" : label) + "]";
        });
        return ANY_MARKUP.matcher(expanded).replaceAll("");
    }

    private static String plainFor(Token token) {
        if (token.isMention()) {
            return "@" + token.value();
        }
        // Without a snapshot to resolve the name, the code is meaningless to a
        // reader; a neutral label is more honest than exposing an internal id.
        return "[Item]";
    }

    /** Strips characters that would break the surrounding chat markup. */
    public static String sanitizeInline(String text) {
        return text == null ? "" : text.replaceAll("[<>&\\[\\]]", "").replace(DELIMITER, ' ');
    }

    private static String safeValue(String value) {
        return value == null ? "" : value.replace(DELIMITER, ' ').replace(":", "");
    }
}
