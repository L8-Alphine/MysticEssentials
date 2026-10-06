package org.hyzionstudios.mysticessentials.modules.customcommands;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.message.MysticText;
import org.hyzionstudios.mysticessentials.modules.customcommands.argument.ArgumentParser;
import org.hyzionstudios.mysticessentials.modules.customcommands.argument.ArgumentType;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Everything one invocation of a custom command carries through its action
 * chain: the sender, the parsed arguments, placeholder resolution, and the
 * shared safety state (execution depth, per-chain action budget, and the call
 * stack used for recursion detection).
 *
 * <p>Nested invocations (a {@code command} action calling another custom
 * command) get a child context via {@link #child} that shares the same action
 * budget and extends the call stack, so the safety limits cover the whole
 * composed execution, including scheduler continuations after delays.</p>
 */
public final class CustomCommandContext {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z0-9_]+(?::[a-zA-Z0-9_]+)?)}");
    private static final Pattern ARG_PLACEHOLDER = Pattern.compile("(?i)\\{arg:([a-zA-Z0-9_]+)}");
    /** Any tag left after {@link MysticText#stripMarkup} ({@code <link:…>}, {@code <lang:…>}, …). */
    private static final Pattern MARKUP_TAG = Pattern.compile("<[^<>]*>");

    private final MysticCore core;
    private final CustomCommandsModule module;
    private final CustomCommand command;
    /** Alias actually typed (may differ from the primary name). */
    private final String label;
    private final CommandSender sender;
    private final PlayerRef player;
    private final Map<String, ArgumentParser.ParsedValue> args;
    private final int depth;
    private final AtomicInteger actionBudget;
    private final List<String> callStack;
    private final boolean test;

    CustomCommandContext(MysticCore core, CustomCommandsModule module, CustomCommand command,
            String label, CommandSender sender, PlayerRef player,
            Map<String, ArgumentParser.ParsedValue> args, int depth, AtomicInteger actionBudget,
            List<String> callStack, boolean test) {
        this.core = core;
        this.module = module;
        this.command = command;
        this.label = label;
        this.sender = sender;
        this.player = player;
        this.args = args;
        this.depth = depth;
        this.actionBudget = actionBudget;
        this.callStack = callStack;
        this.test = test;
    }

    // ----- Identity ------------------------------------------------------------

    public CustomCommand command() {
        return command;
    }

    public String commandName() {
        return command.nameLower();
    }

    public String label() {
        return label;
    }

    public CommandSender sender() {
        return sender;
    }

    /** @return the player behind the sender, or {@code null} for the console. */
    public PlayerRef player() {
        return player;
    }

    public boolean isConsole() {
        return player == null;
    }

    public boolean isTest() {
        return test;
    }

    public boolean senderHasPermission(String node) {
        return node == null || node.isBlank() || sender.hasPermission(node);
    }

    // ----- Safety state ----------------------------------------------------------

    public int depth() {
        return depth;
    }

    /** Names of the custom commands currently on the invocation stack (loop detection). */
    public List<String> callStack() {
        return callStack;
    }

    /** Takes one action from the shared chain budget; {@code false} = budget exhausted. */
    public boolean consumeActionBudget() {
        return actionBudget.decrementAndGet() >= 0;
    }

    /** The shared budget counter, for handing to nested invocations (executor only). */
    AtomicInteger sharedBudget() {
        return actionBudget;
    }

    // ----- Environment -------------------------------------------------------------

    public CustomCommandExecutor executor() {
        return module.executor();
    }

    public Collection<PlayerRef> onlinePlayers() {
        return core.platform().onlinePlayers();
    }

    public int serverOnline() {
        return core.platform().onlinePlayers().size();
    }

    public String serverName() {
        return module.serverName();
    }

    /** @return the sender's current world name, or {@code null} for console/unknown. */
    public String playerWorldName() {
        return player == null ? null : core.platform().worldNameOf(player).orElse(null);
    }

    public void log(Level level, String message) {
        core.log(level, "[customcommands] " + message);
    }

    // ----- Arguments -----------------------------------------------------------------

    /** Display string of a parsed argument, or {@code null} if not provided. */
    public String argDisplay(String name) {
        ArgumentParser.ParsedValue value = args.get(name == null ? "" : name.toLowerCase(Locale.ROOT));
        return value == null ? null : value.display();
    }

    /** Typed value of a parsed argument (e.g. a {@code PlayerRef}), or {@code null}. */
    public Object argValue(String name) {
        ArgumentParser.ParsedValue value = args.get(name == null ? "" : name.toLowerCase(Locale.ROOT));
        return value == null ? null : value.value();
    }

    // ----- Placeholders & output ---------------------------------------------------------

    /**
     * Resolves the module placeholders ({@code {player_name}},
     * {@code {cooldown_remaining}}, ...), then the core placeholder pipeline
     * (internal registry + PlaceholderAPI when present), and {@code {arg:x}}
     * last, so text a player typed is never placeholder-expanded.
     */
    public String resolvePlaceholders(String input) {
        return resolveArgs(resolveTemplate(input), ArgUse.PLAIN).line();
    }

    /** A resolved command line, or the argument whose value was refused for one. */
    public record CommandLine(String line, String refusedArg) {
    }

    /**
     * Resolves a {@code command} action template. The engine re-tokenizes the
     * line (quotes, {@code \}, {@code [ ] ,} lists, {@code --name} options), so
     * a typed value that could add arguments or options to the target command
     * is refused instead of substituted: see {@link #commandLineValue}.
     */
    public CommandLine resolveCommandLine(String template) {
        return resolveArgs(resolveTemplate(template), ArgUse.COMMAND);
    }

    /** Where an {@code {arg:x}} value lands, which decides how it is made safe. */
    private enum ArgUse {
        /** Compared or looked up as-is (conditions, player names). */
        PLAIN,
        /** Shown in a message: colour, link and other markup is removed. */
        MESSAGE,
        /** Spliced into a command line: refused unless it stays one argument. */
        COMMAND
    }

    /** Everything except {@code {arg:x}}: the author's template, never player text. */
    private String resolveTemplate(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        String resolved = resolveModulePlaceholders(input);
        try {
            return core.getMessageService().resolvePlaceholders(player == null ? null : player.getUuid(), resolved);
        } catch (Throwable t) {
            return resolved;
        }
    }

    private CommandLine resolveArgs(String input, ArgUse use) {
        Matcher matcher = ARG_PLACEHOLDER.matcher(input);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            ArgumentParser.ParsedValue value = args.get(name.toLowerCase(Locale.ROOT));
            String text = value == null ? "" : value.display();
            if (use == ArgUse.MESSAGE) {
                text = stripMarkup(text);
            } else if (use == ArgUse.COMMAND && value != null) {
                text = commandLineValue(text, value.type());
                if (text == null) {
                    return new CommandLine(null, name);
                }
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(text));
        }
        matcher.appendTail(out);
        return new CommandLine(out.toString(), null);
    }

    /**
     * @return {@code value} as it can safely appear on a command line, or
     *         {@code null} when it must be refused: it must not contain quotes,
     *         backslashes or control characters, nor a word starting with
     *         {@code --} (an option) or {@code '} (a quote). A
     *         {@code greedy_string} is trailing text and may hold several words; a
     *         {@code string} with spaces is quoted so it stays one argument; any
     *         other value must be a single word without {@code [ ] ,} (lists).
     */
    private static String commandLineValue(String value, ArgumentType type) {
        if (value.isEmpty()) {
            return value;
        }
        boolean multiWord = type == ArgumentType.GREEDY_STRING || type == ArgumentType.STRING;
        boolean spaced = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\' || Character.isISOControl(c)) {
                return null;
            }
            if (Character.isWhitespace(c)) {
                spaced = true;
            }
            if (!multiWord && (spaced || c == '[' || c == ']' || c == ',')) {
                return null;
            }
        }
        for (String word : value.trim().split("\\s+")) {
            if (word.startsWith("--") || word.startsWith("'")) {
                return null;
            }
        }
        return spaced && type == ArgumentType.STRING ? "\"" + value + "\"" : value;
    }

    /** Removes colour, link and other markup a player typed, until none is left. */
    private static String stripMarkup(String text) {
        String previous;
        String current = text;
        do {
            previous = current;
            current = MARKUP_TAG.matcher(MysticText.stripMarkup(previous)).replaceAll("");
        } while (!current.equals(previous));
        return current;
    }

    private String resolveModulePlaceholders(String input) {
        Matcher matcher = PLACEHOLDER.matcher(input);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String token = matcher.group(1);
            String replacement = resolveToken(token);
            matcher.appendReplacement(out,
                    Matcher.quoteReplacement(replacement != null ? replacement : matcher.group(0)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * @return the replacement for a token, or {@code null} to leave it untouched
     *         ({@code {arg:x}} is left for {@link #resolveArgs}, after every other pass)
     */
    private String resolveToken(String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "player_name" -> player != null ? player.getUsername() : sender.getUsername();
            case "player_uuid" -> player != null ? player.getUuid().toString() : uuidOrEmpty();
            case "sender_name" -> sender.getUsername();
            case "sender_uuid" -> uuidOrEmpty();
            case "server_name" -> serverName();
            case "server_online" -> Integer.toString(serverOnline());
            case "cooldown_remaining" -> Long.toString(module.cooldowns().remainingSeconds(
                    player != null ? player.getUuid() : sender.getUuid(), command.nameLower()));
            default -> null;
        };
    }

    private String uuidOrEmpty() {
        UUID uuid = sender.getUuid();
        return uuid == null ? "" : uuid.toString();
    }

    /**
     * Formats a template (placeholders + colours) into a Hytale {@link Message}.
     * Argument values lose their markup, and the result is only colourized
     * (not resolved again), so player text cannot carry codes, links or placeholders.
     */
    public Message formatMessage(String template) {
        return core.getMessageService().colorize(resolveArgs(resolveTemplate(template), ArgUse.MESSAGE).line());
    }

    /** Resolves placeholders, formats colours, and sends to the sender. */
    public void replyFormatted(String template) {
        sender.sendMessage(formatMessage(template));
    }

    /** Sends a message-bundle entry (with params) to the sender. */
    public void replyKey(String key, Map<String, String> params) {
        sender.sendMessage(core.getMessageService().fromKey(key, params));
    }

    /** Resolves placeholders, formats colours, and sends to every online player. */
    public void broadcastFormatted(String template) {
        Message message = formatMessage(template);
        for (PlayerRef online : onlinePlayers()) {
            online.sendMessage(message);
        }
    }
}
