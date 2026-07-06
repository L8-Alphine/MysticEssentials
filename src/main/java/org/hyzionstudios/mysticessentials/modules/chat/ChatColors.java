package org.hyzionstudios.mysticessentials.modules.chat;

import java.util.regex.Pattern;

/**
 * Strips colour/format tokens from a chat message that the sender is not
 * permitted to use, so unprivileged players cannot inject formatting. Each style
 * is gated independently.
 */
final class ChatColors {

    private static final Pattern LEGACY = Pattern.compile("&[0-9a-fk-orA-FK-OR]");
    private static final Pattern HEX = Pattern.compile("&#[0-9a-fA-F]{6}|<#[0-9a-fA-F]{6}>");
    private static final Pattern GRADIENT = Pattern.compile("(?i)</?gradient(:[^>]*)?>");
    private static final Pattern RAINBOW = Pattern.compile("(?i)</?rainbow>");
    private static final Pattern LINK = Pattern.compile("(?i)</?link(:[^>]*)?>");
    // Any MiniMessage-style tag that is NOT independently gated above.
    private static final Pattern MINIMESSAGE = Pattern.compile("<(?!#)(?!/?gradient)(?!/?rainbow)(?!/?link)[^>]*>");

    private ChatColors() {
    }

    static String sanitize(String content, boolean legacy, boolean hex, boolean gradient, boolean minimessage) {
        return sanitize(content, legacy, hex, gradient, false, minimessage, false);
    }

    static String sanitize(String content, boolean legacy, boolean hex, boolean gradient,
            boolean rainbow, boolean minimessage, boolean links) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        String result = content;
        if (!links) {
            result = LINK.matcher(result).replaceAll("");
        }
        if (!rainbow) {
            result = RAINBOW.matcher(result).replaceAll("");
        }
        if (!gradient) {
            result = GRADIENT.matcher(result).replaceAll("");
        }
        if (!hex) {
            result = HEX.matcher(result).replaceAll("");
        }
        if (!legacy) {
            result = LEGACY.matcher(result).replaceAll("");
        }
        if (!minimessage) {
            result = MINIMESSAGE.matcher(result).replaceAll("");
        }
        return result;
    }
}
