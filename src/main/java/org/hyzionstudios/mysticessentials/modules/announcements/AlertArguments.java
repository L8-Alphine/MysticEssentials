package org.hyzionstudios.mysticessentials.modules.announcements;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.hyzionstudios.mysticessentials.api.notification.Notification;
import org.hyzionstudios.mysticessentials.api.notification.NotificationAction;
import org.hyzionstudios.mysticessentials.api.notification.NotificationAudience;
import org.hyzionstudios.mysticessentials.api.notification.NotificationCategory;
import org.hyzionstudios.mysticessentials.api.notification.NotificationPriority;

/**
 * Parses the {@code /broadcast} and {@code /alert} command lines into a
 * {@link Notification} and a {@link NotificationAudience}.
 *
 * <p>Both commands accept a friendly short form and a precise long one, because
 * they serve two different moments. Typing
 * {@code /alert critical The server restarts in 60 seconds} during an actual
 * incident should not require remembering flag names; scripting a scheduled
 * maintenance window should not require accepting the defaults.</p>
 *
 * <pre>
 * /broadcast &lt;message&gt;
 * /broadcast &lt;category&gt; &lt;message&gt;
 * /alert &lt;priority&gt; &lt;message&gt;
 * /alert &lt;category&gt; &lt;priority&gt; &lt;message&gt;
 * /alert critical --title "Server Restart" --subtitle "60 seconds"
 *                 --message "Move somewhere safe." --sound SFX_Attn_VeryLoud
 *                 --bossbar --duration 60 --audience all
 * </pre>
 *
 * <p>A leading word is only consumed as a category or priority when it actually
 * names one, so {@code /broadcast event horizon opens tonight} broadcasts the
 * whole sentence under the {@code event} category rather than silently eating
 * the first word of a message that started with an unrecognised token.</p>
 */
final class AlertArguments {

    /**
     * A parsed command line. {@code error} is non-null only when parsing failed.
     *
     * @param categoryExplicit whether the sender named a category. When they did,
     *                         the category's own chat prefix wins; when they did
     *                         not, the announcements module keeps applying its
     *                         configured {@code broadcastPrefix}/{@code alertPrefix}.
     */
    record Parsed(Notification notification, NotificationAudience audience, String error,
            boolean categoryExplicit) {

        static Parsed failure(String error) {
            return new Parsed(null, null, error, false);
        }

        boolean ok() {
            return error == null;
        }
    }

    private AlertArguments() {
    }

    /**
     * @param input           the raw argument string
     * @param defaultCategory category when the line does not name one
     * @param defaultPriority priority when the line does not name one
     * @param knownCategories configured category ids, used to decide whether a
     *                        leading word is a category or the start of the message
     * @param source          the {@code source} recorded on the notification
     */
    static Parsed parse(String input, NotificationCategory defaultCategory,
            NotificationPriority defaultPriority, java.util.Set<String> knownCategories,
            String source) {
        if (input == null || input.isBlank()) {
            return Parsed.failure("Nothing to send — provide a message.");
        }

        List<String> tokens = tokenize(input.trim());
        NotificationCategory category = defaultCategory;
        NotificationPriority priority = defaultPriority;
        boolean categoryExplicit = false;
        int index = 0;

        // A leading category, then a leading priority — each consumed only if the
        // word genuinely names one.
        if (index < tokens.size() && knownCategories.contains(lower(tokens.get(index)))) {
            String word = tokens.get(index);
            category = NotificationCategory.of(word);
            categoryExplicit = true;
            index++;
            // Some words name both a category and a priority — "critical",
            // "warning", "emergency". Consuming such a word as a category alone
            // would silently leave the priority at its default, so
            // `/alert critical ...` would parse as a NORMAL notification and slip
            // past the permission gate that gates critical sends. Apply it to
            // both, taking the stronger of the two.
            if (isPriority(word)) {
                priority = strongest(priority, NotificationPriority.parse(word));
            }
        }
        if (index < tokens.size() && isPriority(tokens.get(index))) {
            priority = strongest(priority, NotificationPriority.parse(tokens.get(index)));
            index++;
        }

        Notification.Builder builder = Notification.builder()
                .category(category)
                .priority(priority)
                .source(source);
        NotificationAudience audience = NotificationAudience.all();

        StringBuilder message = new StringBuilder();
        for (; index < tokens.size(); index++) {
            String token = tokens.get(index);
            if (!token.startsWith("--")) {
                if (message.length() > 0) {
                    message.append(' ');
                }
                message.append(token);
                continue;
            }
            String flag = lower(token.substring(2));
            switch (flag) {
                case "bossbar", "banner" -> builder.showAsBanner(true);
                case "no-chat" -> builder.showInChat(false);
                case "toast" -> builder.showAsToast(true);
                case "actionbar" -> builder.showAsActionBar(true);
                case "no-history" -> builder.storeInHistory(false);
                case "sticky" -> builder.dismissible(false);
                default -> {
                    String value = index + 1 < tokens.size() ? tokens.get(++index) : null;
                    if (value == null) {
                        return Parsed.failure("Flag --" + flag + " needs a value.");
                    }
                    String error = applyValueFlag(builder, flag, value);
                    if (error != null) {
                        return Parsed.failure(error);
                    }
                    if ("audience".equals(flag)) {
                        audience = parseAudience(value);
                        if (audience == null) {
                            return Parsed.failure("Unknown audience '" + value
                                    + "'. Use all, world:<name>, channel:<id>, "
                                    + "permission:<node>, player:<name>, staff, or guild:<id>.");
                        }
                    }
                }
            }
        }

        String body = message.toString().trim();
        if (!body.isEmpty()) {
            builder.message(body);
        }
        Notification notification = builder.build();
        if (notification.bestText().isBlank() && notification.title().isEmpty()) {
            return Parsed.failure("Nothing to send — provide a message or a --title.");
        }
        return new Parsed(notification, audience, null, categoryExplicit);
    }

    /** @return an error string, or {@code null} on success. */
    private static String applyValueFlag(Notification.Builder builder, String flag, String value) {
        switch (flag) {
            case "title" -> builder.title(value);
            case "subtitle" -> builder.subtitle(value);
            case "message" -> builder.message(value);
            case "sound" -> builder.sound(value);
            case "icon" -> builder.icon(value);
            case "source" -> builder.source(value);
            case "command" -> builder.action(NotificationAction.command(value));
            case "url" -> builder.action(NotificationAction.url(value));
            case "duration" -> {
                try {
                    builder.durationSeconds(Long.parseLong(value.trim()));
                } catch (NumberFormatException e) {
                    return "--duration must be a whole number of seconds.";
                }
            }
            case "audience" -> { /* handled by the caller, which owns the audience */ }
            default -> {
                return "Unknown flag --" + flag + ".";
            }
        }
        return null;
    }

    /** @return the audience, or {@code null} when the spec is unrecognised. */
    private static NotificationAudience parseAudience(String value) {
        String spec = value.trim();
        String lower = lower(spec);
        if (lower.equals("all") || lower.equals("everyone")) {
            return NotificationAudience.all();
        }
        if (lower.equals("staff")) {
            return NotificationAudience.staff();
        }
        int colon = spec.indexOf(':');
        if (colon <= 0 || colon + 1 >= spec.length()) {
            return null;
        }
        String kind = lower(spec.substring(0, colon));
        String argument = spec.substring(colon + 1);
        return switch (kind) {
            case "world" -> NotificationAudience.world(argument);
            case "channel" -> NotificationAudience.channel(argument);
            case "permission", "perm" -> NotificationAudience.permission(argument);
            case "guild" -> NotificationAudience.guild(argument);
            case "party" -> NotificationAudience.party(argument);
            case "region" -> NotificationAudience.region(argument);
            // A player audience needs a UUID lookup the parser cannot do, so it is
            // resolved by the caller through a name-matching predicate.
            case "player" -> NotificationAudience.matching(
                    player -> player.getUsername().equalsIgnoreCase(argument));
            default -> null;
        };
    }

    private static NotificationPriority strongest(NotificationPriority a, NotificationPriority b) {
        return a.atLeast(b) ? a : b;
    }

    private static boolean isPriority(String token) {
        String lower = lower(token);
        return switch (lower) {
            case "low", "normal", "important", "critical", "info", "minor", "warn", "warning",
                    "emergency", "urgent", "alert" -> true;
            default -> false;
        };
    }

    /**
     * Splits on whitespace, honouring double quotes so a multi-word
     * {@code --title "Server Restart"} survives intact.
     */
    private static List<String> tokenize(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '"') {
                quoted = !quoted;
                continue;
            }
            if (!quoted && Character.isWhitespace(c)) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(c);
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
