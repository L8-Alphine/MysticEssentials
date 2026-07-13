package org.hyzionstudios.mysticessentials.modules.customcommands.argument;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.hyzionstudios.mysticessentials.platform.HytalePlatform;

import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Parses the raw argument portion of a custom command invocation against the
 * command's declared {@link CommandArgument} list, producing typed values.
 *
 * <p>Tokenization is quote-aware: a {@code string} argument may be written as
 * {@code "two words"}. {@code greedy_string} consumes the untouched remainder
 * of the input. All failures return a structured {@link Result} — the parser
 * never throws on user input.</p>
 */
public final class ArgumentParser {

    private static final Pattern DURATION_PART = Pattern.compile("(\\d+)([smhd])");
    private static final Pattern DURATION_FULL = Pattern.compile("(?:\\d+[smhd])+|\\d+");

    private final HytalePlatform platform;

    public ArgumentParser(HytalePlatform platform) {
        this.platform = platform;
    }

    /** One parsed value: the display string plus the typed object behind it. */
    public record ParsedValue(String display, Object value, ArgumentType type) {
    }

    /** Outcome of a parse: either a value map or a user-facing failure. */
    public static final class Result {
        private final Map<String, ParsedValue> values;
        private final Failure failure;

        private Result(Map<String, ParsedValue> values, Failure failure) {
            this.values = values;
            this.failure = failure;
        }

        public boolean ok() {
            return failure == null;
        }

        public Map<String, ParsedValue> values() {
            return values;
        }

        public Failure failure() {
            return failure;
        }
    }

    /** Why a parse failed, in message-key + params form for the message service. */
    public record Failure(String messageKey, Map<String, String> params) {
    }

    /**
     * Parses {@code rawInput} (the argument portion of the command line, may be
     * empty) against {@code declared}.
     */
    public Result parse(List<CommandArgument> declared, String rawInput) {
        Map<String, ParsedValue> out = new LinkedHashMap<>();
        String remaining = rawInput == null ? "" : rawInput.trim();

        for (CommandArgument arg : declared) {
            ArgumentType type = arg.resolvedType;
            if (type == null) {
                // Compilation rejected the definition; treat as internal error.
                return fail("customcommands-arg-invalid", arg.name, "?", "valid argument type");
            }
            if (type == ArgumentType.GREEDY_STRING) {
                if (remaining.isEmpty()) {
                    if (arg.required && !hasDefault(arg)) {
                        return missing(arg);
                    }
                    putDefault(out, arg);
                } else {
                    out.put(arg.nameLower(), new ParsedValue(remaining, remaining, type));
                    remaining = "";
                }
                continue;
            }

            Token token = nextToken(remaining, type == ArgumentType.STRING);
            if (token == null) {
                if (arg.required && !hasDefault(arg)) {
                    return missing(arg);
                }
                putDefault(out, arg);
                continue;
            }
            remaining = token.rest;

            ParsedValue value = convert(arg, type, token.text);
            if (value == null) {
                return fail(type == ArgumentType.PLAYER
                                ? "customcommands-player-not-online" : "customcommands-arg-invalid",
                        arg.name, token.text, type.expected());
            }
            out.put(arg.nameLower(), value);
        }
        return new Result(out, null);
    }

    // ----- Tokenization --------------------------------------------------------

    private record Token(String text, String rest) {
    }

    /** Pulls the next whitespace-delimited token; honours double quotes when {@code allowQuotes}. */
    private static Token nextToken(String input, boolean allowQuotes) {
        if (input.isEmpty()) {
            return null;
        }
        if (allowQuotes && input.charAt(0) == '"') {
            int close = input.indexOf('"', 1);
            if (close > 0) {
                return new Token(input.substring(1, close), input.substring(close + 1).trim());
            }
            // Unterminated quote: fall through and treat as a bare token.
        }
        int space = input.indexOf(' ');
        if (space < 0) {
            return new Token(input, "");
        }
        return new Token(input.substring(0, space), input.substring(space + 1).trim());
    }

    // ----- Conversion ----------------------------------------------------------

    /** @return the typed value, or {@code null} if the token is not valid for the type. */
    private ParsedValue convert(CommandArgument arg, ArgumentType type, String token) {
        switch (type) {
            case STRING, WORD:
                return new ParsedValue(token, token, type);
            case NUMBER: {
                try {
                    double d = Double.parseDouble(token);
                    return new ParsedValue(trimNumber(d), d, type);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            case INTEGER: {
                try {
                    long l = Long.parseLong(token);
                    return new ParsedValue(Long.toString(l), l, type);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            case BOOLEAN: {
                Boolean b = parseBoolean(token);
                return b == null ? null : new ParsedValue(b.toString(), b, type);
            }
            case PLAYER: {
                PlayerRef player = platform.findPlayerByName(token).orElse(null);
                return player == null ? null : new ParsedValue(player.getUsername(), player, type);
            }
            case OFFLINE_PLAYER: {
                if (token.isBlank()) {
                    return null;
                }
                // Canonicalize the name when the player happens to be online.
                String display = platform.findPlayerByName(token)
                        .map(PlayerRef::getUsername).orElse(token);
                return new ParsedValue(display, display, type);
            }
            case DURATION: {
                Long seconds = parseDurationSeconds(token);
                return seconds == null ? null : new ParsedValue(seconds + "s", seconds, type);
            }
            default:
                return null;
        }
    }

    private static Boolean parseBoolean(String token) {
        return switch (token.toLowerCase(Locale.ROOT)) {
            case "true", "yes", "on" -> Boolean.TRUE;
            case "false", "no", "off" -> Boolean.FALSE;
            default -> null;
        };
    }

    /** Parses {@code 90}, {@code 30s}, {@code 2h30m}, {@code 1d}... into whole seconds. */
    public static Long parseDurationSeconds(String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        if (!DURATION_FULL.matcher(lower).matches()) {
            return null;
        }
        if (lower.chars().allMatch(Character::isDigit)) {
            try {
                return Long.parseLong(lower);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        long total = 0;
        Matcher matcher = DURATION_PART.matcher(lower);
        while (matcher.find()) {
            long amount = Long.parseLong(matcher.group(1));
            total += switch (matcher.group(2)) {
                case "m" -> amount * 60L;
                case "h" -> amount * 3600L;
                case "d" -> amount * 86400L;
                default -> amount;
            };
        }
        return total;
    }

    private static String trimNumber(double d) {
        return d == Math.rint(d) && !Double.isInfinite(d) ? Long.toString((long) d) : Double.toString(d);
    }

    // ----- Defaults & failures ---------------------------------------------------

    private static boolean hasDefault(CommandArgument arg) {
        return arg.defaultValue != null && !arg.defaultValue.isEmpty();
    }

    private void putDefault(Map<String, ParsedValue> out, CommandArgument arg) {
        if (!hasDefault(arg)) {
            return;
        }
        ParsedValue value = convert(arg, arg.resolvedType, arg.defaultValue);
        // A default that fails typed conversion still resolves as plain text so
        // {arg:x} placeholders keep working; the validator warns about it upfront.
        out.put(arg.nameLower(), value != null
                ? value
                : new ParsedValue(arg.defaultValue, arg.defaultValue, arg.resolvedType));
    }

    private static Result missing(CommandArgument arg) {
        return fail("customcommands-arg-missing", arg.name, "", arg.resolvedType.expected());
    }

    private static Result fail(String key, String argName, String value, String expected) {
        return new Result(null, new Failure(key, Map.of(
                "arg", argName == null ? "?" : argName,
                "value", value == null ? "" : value,
                "expected", expected == null ? "" : expected)));
    }

    /** Builds the usage suffix for a declared argument list (e.g. {@code <target> [message]}). */
    public static String usageOf(List<CommandArgument> declared) {
        List<String> tokens = new ArrayList<>();
        for (CommandArgument arg : declared) {
            tokens.add(arg.usageToken());
        }
        return String.join(" ", tokens);
    }
}
