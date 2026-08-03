package org.hyzionstudios.mysticessentials.api.ui;

import java.lang.reflect.Method;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Restricted expression resolver; it never evaluates Java or JavaScript. */
public final class UiBindingEngine {
    private static final Pattern EMBEDDED = Pattern.compile("\\{([^{}]+)}");

    public String render(String template, Map<String, ?> model) {
        if (template == null) return "";
        Matcher matcher = EMBEDDED.matcher(template);
        StringBuilder output = new StringBuilder();
        while (matcher.find()) {
            Object value = evaluate(matcher.group(1), model);
            matcher.appendReplacement(output, Matcher.quoteReplacement(value == null ? "" : String.valueOf(value)));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    public Object evaluate(String expression, Map<String, ?> model) {
        Objects.requireNonNull(model, "model");
        if (expression == null) return null;
        String value = unwrap(expression.trim());
        int question = topLevel(value, '?');
        if (question >= 0) {
            int colon = topLevel(value.substring(question + 1), ':');
            if (colon >= 0) {
                colon += question + 1;
                return truthy(evaluate(value.substring(0, question), model))
                        ? evaluate(value.substring(question + 1, colon), model)
                        : evaluate(value.substring(colon + 1), model);
            }
        }
        for (String operator : List.of("!=", "==", ">=", "<=", ">", "<")) {
            int split = value.indexOf(operator);
            if (split > 0) return compare(evaluate(value.substring(0, split), model),
                    evaluate(value.substring(split + operator.length()), model), operator);
        }
        if (value.startsWith("!")) return !truthy(evaluate(value.substring(1), model));
        if ((value.startsWith("'") && value.endsWith("'"))
                || (value.startsWith("\"") && value.endsWith("\""))) return value.substring(1, value.length() - 1);
        if (value.equals("true") || value.equals("false")) return Boolean.parseBoolean(value);
        if (value.equals("null")) return null;
        if (value.matches("-?\\d+(\\.\\d+)?")) return value.contains(".") ? Double.parseDouble(value) : Long.parseLong(value);
        if (value.startsWith("format.") && value.endsWith(")")) return format(value, model);
        if (value.startsWith("permissions.has(") && value.endsWith(")")) {
            Object checker = model.get("permissions");
            String permission = String.valueOf(evaluate(value.substring(16, value.length() - 1), model));
            return checker instanceof Predicate<?> predicate && test(predicate, permission);
        }
        return resolvePath(model, value);
    }

    private Object format(String expression, Map<String, ?> model) {
        int open = expression.indexOf('(');
        String name = expression.substring(7, open);
        String[] args = expression.substring(open + 1, expression.length() - 1).split(",", 2);
        Object raw = evaluate(args[0], model);
        return switch (name) {
            case "number" -> raw instanceof Number n ? NumberFormat.getNumberInstance(Locale.US).format(n) : raw;
            case "currency" -> raw instanceof Number n ? NumberFormat.getCurrencyInstance(Locale.US).format(n) : raw;
            case "percent" -> raw instanceof Number n ? NumberFormat.getPercentInstance(Locale.US).format(n.doubleValue()) : raw;
            case "duration" -> duration(raw);
            case "date" -> raw instanceof Instant instant ? instant.toString() : raw;
            default -> raw;
        };
    }

    private static String duration(Object raw) {
        Duration duration = raw instanceof Duration d ? d
                : raw instanceof Number n ? Duration.ofMillis(n.longValue()) : null;
        if (duration == null) return String.valueOf(raw);
        long seconds = Math.max(0, duration.toSeconds());
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long remaining = seconds % 60;
        return hours > 0 ? "%dh %dm".formatted(hours, minutes)
                : minutes > 0 ? "%dm %ds".formatted(minutes, remaining) : remaining + "s";
    }

    private static Object resolvePath(Object current, String path) {
        for (String segment : path.split("\\.")) {
            if (current == null) return null;
            if (current instanceof Map<?, ?> map) current = map.get(segment);
            else if (segment.equals("size") && current instanceof java.util.Collection<?> collection) current = collection.size();
            else current = property(current, segment);
        }
        return current;
    }

    private static Object property(Object target, String name) {
        // Only zero-argument bean/record accessors are allowed; arbitrary calls are not.
        for (String candidate : List.of(name, "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1),
                "is" + Character.toUpperCase(name.charAt(0)) + name.substring(1))) {
            try {
                Method method = target.getClass().getMethod(candidate);
                if (method.getParameterCount() == 0 && method.getDeclaringClass() != Object.class) return method.invoke(target);
            } catch (ReflectiveOperationException ignored) { }
        }
        return null;
    }

    private static boolean compare(Object left, Object right, String operator) {
        if (operator.equals("==")) return Objects.equals(normalize(left), normalize(right));
        if (operator.equals("!=")) return !Objects.equals(normalize(left), normalize(right));
        if (left instanceof Number a && right instanceof Number b) {
            int comparison = Double.compare(a.doubleValue(), b.doubleValue());
            return switch (operator) { case ">" -> comparison > 0; case "<" -> comparison < 0;
                case ">=" -> comparison >= 0; case "<=" -> comparison <= 0; default -> false; };
        }
        return false;
    }

    private static Object normalize(Object value) {
        return value instanceof Number n ? n.doubleValue() : value;
    }

    private static boolean truthy(Object value) {
        return value instanceof Boolean b ? b : value instanceof Number n ? n.doubleValue() != 0
                : value != null && !String.valueOf(value).isBlank();
    }

    @SuppressWarnings("unchecked")
    private static boolean test(Predicate<?> predicate, String value) { return ((Predicate<String>) predicate).test(value); }
    private static String unwrap(String value) { return value.startsWith("{") && value.endsWith("}") ? value.substring(1, value.length() - 1).trim() : value; }
    private static int topLevel(String value, char target) {
        boolean quoted = false; char quote = 0;
        for (int i = 0; i < value.length(); i++) { char c = value.charAt(i);
            if ((c == '\'' || c == '"') && (i == 0 || value.charAt(i - 1) != '\\')) { if (!quoted) { quoted = true; quote = c; } else if (quote == c) quoted = false; }
            if (!quoted && c == target) return i;
        }
        return -1;
    }
}
