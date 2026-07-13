package org.hyzionstudios.mysticessentials.modules.customcommands.condition;

import java.util.Locale;

import org.hyzionstudios.mysticessentials.modules.customcommands.CustomCommandContext;

/**
 * Resolves a placeholder template against the context (module placeholders
 * plus PlaceholderAPI when present) and compares the result to a value.
 *
 * <pre>
 * { "type": "placeholder", "placeholder": "{arg:amount}",
 *   "operator": "greater_than", "value": "0" }
 * </pre>
 *
 * <p>Operators: {@code equals}, {@code not_equals}, {@code contains},
 * {@code greater_than}, {@code less_than}. Numeric operators fall back to a
 * failed match when either side is not a number.</p>
 */
public final class PlaceholderCondition implements CommandCondition {

    private final String placeholder;
    private final String operator;
    private final String value;
    private final String denyMessage;

    public PlaceholderCondition(String placeholder, String operator, String value, String denyMessage) {
        this.placeholder = placeholder;
        this.operator = operator == null ? "equals" : operator.toLowerCase(Locale.ROOT);
        this.value = value == null ? "" : value;
        this.denyMessage = denyMessage;
    }

    /** @return {@code true} if {@code operator} is one this condition understands. */
    public static boolean isKnownOperator(String operator) {
        if (operator == null) {
            return true; // defaults to equals
        }
        return switch (operator.toLowerCase(Locale.ROOT)) {
            case "equals", "not_equals", "contains", "greater_than", "less_than" -> true;
            default -> false;
        };
    }

    @Override
    public String type() {
        return "placeholder";
    }

    @Override
    public boolean test(CustomCommandContext context) {
        String resolved = context.resolvePlaceholders(placeholder);
        String expected = context.resolvePlaceholders(value);
        return switch (operator) {
            case "not_equals" -> !resolved.equalsIgnoreCase(expected);
            case "contains" -> resolved.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT));
            case "greater_than" -> compareNumeric(resolved, expected) > 0;
            case "less_than" -> compareNumeric(resolved, expected) < 0;
            default -> resolved.equalsIgnoreCase(expected);
        };
    }

    /** @return sign of (left - right), or 0 when either side is not numeric. */
    private static int compareNumeric(String left, String right) {
        try {
            return Double.compare(Double.parseDouble(left.trim()), Double.parseDouble(right.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public String describe() {
        return placeholder + " " + operator + " " + value;
    }

    @Override
    public String denyMessage() {
        return denyMessage;
    }
}
