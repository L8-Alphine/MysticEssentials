package org.hyzionstudios.mysticessentials.modules.chat;

/** One configured chat glyph alias, raw symbol, Private Use Area codepoint, and asset. */
public final class ChatGlyph {
    public String alias;
    public String symbol;
    public String codepoint;
    public String category;
    public String fallback;
    public String asset;
    public String permission;

    public String privateUseText() {
        if (codepoint == null || !codepoint.startsWith("U+")) {
            return fallback == null ? "" : fallback;
        }
        int value = Integer.parseInt(codepoint.substring(2), 16);
        return new String(Character.toChars(value));
    }
}
