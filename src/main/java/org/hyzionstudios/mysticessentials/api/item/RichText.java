package org.hyzionstudios.mysticessentials.api.item;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.hyzionstudios.mysticessentials.core.message.MysticText;

/**
 * A piece of display text that may carry Mystic Essentials markup or be a
 * client-side translation key.
 *
 * <p>This is the only text type the ItemView model uses. Keeping it distinct
 * from {@code String} lets the renderer decide, per surface, whether it needs
 * the marked-up form (chat, {@code TextSpans}) or the stripped plain form
 * (plain-text {@code Label.Text}, log lines, external bridges) — a distinction
 * the 0.5.6 client is unforgiving about.</p>
 *
 * <p><b>Absence is expressed by a {@code null} {@code RichText}, never by a
 * sentinel string.</b> {@code RichText.plain("Null")} is a perfectly valid
 * value whose {@link #plain()} is the four characters {@code Null}; it must
 * never be mistaken for missing data. See {@link ItemClassification}.</p>
 */
public final class RichText {

    private static final RichText EMPTY = new RichText("", null, Map.of());

    private final String markup;
    private final String translationKey;
    private final Map<String, String> translationArguments;

    private RichText(String markup, String translationKey, Map<String, String> translationArguments) {
        this.markup = markup == null ? "" : markup;
        this.translationKey = translationKey;
        this.translationArguments = translationArguments == null
                ? Map.of() : Map.copyOf(translationArguments);
    }

    /** Text that may contain Mystic markup ({@code &a}, {@code <#RRGGBB>}, {@code <link:…>}). */
    public static RichText of(String markup) {
        return markup == null || markup.isEmpty() ? EMPTY : new RichText(markup, null, Map.of());
    }

    /**
     * A literal that must render exactly as given: markup characters are escaped
     * so provider- or metadata-supplied text can never inject formatting or a
     * chat link. Use this for anything that originated outside this mod.
     */
    public static RichText plain(String literal) {
        return literal == null || literal.isEmpty() ? EMPTY : new RichText(escape(literal), null, Map.of());
    }

    /**
     * A client-translated segment. {@code arguments} are the item name/description
     * parameters (e.g. {@code {material}}); each value is a translation key prefixed
     * with {@code @} or a literal prefixed with {@code ~}, matching the engine's
     * {@code Message.param} encoding.
     */
    public static RichText translated(String translationKey, Map<String, String> arguments) {
        if (translationKey == null || translationKey.isBlank()) {
            return EMPTY;
        }
        return new RichText("", translationKey, arguments);
    }

    public static RichText empty() {
        return EMPTY;
    }

    /** The marked-up form, suitable for {@code MysticText.parse} / {@code TextSpans}. */
    public String markup() {
        if (translationKey != null) {
            StringBuilder sb = new StringBuilder("<lang:").append(translationKey);
            for (Map.Entry<String, String> argument : translationArguments.entrySet()) {
                sb.append('|').append(argument.getKey()).append('=').append(argument.getValue());
            }
            return sb.append('>').toString();
        }
        return markup;
    }

    /**
     * The markup-stripped form. For a translated segment this is empty — the
     * literal text only exists client-side — so callers that need a guaranteed
     * non-empty label should fall back to a known plain value.
     */
    public String plain() {
        return translationKey != null ? "" : MysticText.stripMarkup(markup);
    }

    public boolean isTranslated() {
        return translationKey != null;
    }

    public String translationKey() {
        return translationKey;
    }

    public Map<String, String> translationArguments() {
        return translationArguments;
    }

    /**
     * Whether this carries nothing renderable. Note that a value whose plain text
     * is {@code "Null"}, {@code "None"}, or {@code "Unknown"} is <b>not</b> empty.
     */
    public boolean isEmpty() {
        return translationKey == null && markup.isEmpty();
    }

    /** Escapes the characters that would otherwise open Mystic markup. */
    private static String escape(String literal) {
        StringBuilder sb = new StringBuilder(literal.length() + 8);
        for (int i = 0; i < literal.length(); i++) {
            char c = literal.charAt(i);
            switch (c) {
                case '&' -> sb.append("&&");
                case '<' -> sb.append('(');
                case '>' -> sb.append(')');
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Copies this text with {@code suffix} appended to its marked-up form. */
    public RichText append(RichText other) {
        if (other == null || other.isEmpty()) {
            return this;
        }
        if (isEmpty()) {
            return other;
        }
        return RichText.of(markup() + other.markup());
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RichText other
                && markup.equals(other.markup)
                && Objects.equals(translationKey, other.translationKey)
                && translationArguments.equals(other.translationArguments);
    }

    @Override
    public int hashCode() {
        return Objects.hash(markup, translationKey, translationArguments);
    }

    @Override
    public String toString() {
        return translationKey != null ? "RichText[@" + translationKey + "]" : "RichText[" + markup + "]";
    }

    /** Builds a mutable argument map for {@link #translated(String, Map)}. */
    public static Map<String, String> arguments() {
        return new LinkedHashMap<>();
    }
}
