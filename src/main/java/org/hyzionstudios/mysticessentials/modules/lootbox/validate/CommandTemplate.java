package org.hyzionstudios.mysticessentials.modules.lootbox.validate;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A COMMAND reward's template. Only typed placeholders are filled, the player's
 * name is reduced to {@code [A-Za-z0-9_]}, and the rendered command must fully
 * match its allowlisted pattern, so no player-controlled text can reach the
 * console beyond what the operator approved.
 */
public final class CommandTemplate {

    public static final Set<String> PLACEHOLDERS = Set.of("playerUuid", "playerName", "quantity", "openingId");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z]+)}");
    private static final Pattern UNSAFE = Pattern.compile("[\\r\\n\\u0000]");

    private CommandTemplate() {
    }

    /** @return a problem with the template, or {@code null} when it is acceptable. */
    public static String check(String template) {
        if (template == null || template.isBlank()) {
            return "command is empty";
        }
        if (UNSAFE.matcher(template).find()) {
            return "command contains a line break or NUL";
        }
        Matcher placeholders = PLACEHOLDER.matcher(template);
        while (placeholders.find()) {
            if (!PLACEHOLDERS.contains(placeholders.group(1))) {
                return "unknown placeholder {" + placeholders.group(1) + "}";
            }
        }
        return null;
    }

    /** @return a problem with the pattern, or {@code null}. */
    public static String checkPattern(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return "pattern is empty";
        }
        try {
            Pattern.compile(pattern);
            return null;
        } catch (PatternSyntaxException e) {
            return "pattern does not compile: " + e.getDescription();
        }
    }

    /** Fills the placeholders; the leading slash, if any, is dropped. */
    public static String render(String template, String playerUuid, String playerName, int quantity,
            String openingId) {
        Map<String, String> values = Map.of(
                "playerUuid", playerUuid == null ? "" : playerUuid,
                "playerName", safeName(playerName),
                "quantity", Integer.toString(Math.max(0, quantity)),
                "openingId", openingId == null ? "" : openingId.replaceAll("[^a-z0-9]", ""));
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(values.getOrDefault(matcher.group(1), "")));
        }
        matcher.appendTail(out);
        String rendered = out.toString().trim();
        return rendered.startsWith("/") ? rendered.substring(1).trim() : rendered;
    }

    /** True when {@code rendered} fully matches {@code pattern}. */
    public static boolean allowed(String rendered, String pattern) {
        if (rendered == null || pattern == null || UNSAFE.matcher(rendered).find()) {
            return false;
        }
        try {
            return Pattern.compile(pattern).matcher(rendered).matches();
        } catch (PatternSyntaxException e) {
            return false;
        }
    }

    static String safeName(String name) {
        return name == null ? "" : name.replaceAll("[^A-Za-z0-9_]", "");
    }
}
