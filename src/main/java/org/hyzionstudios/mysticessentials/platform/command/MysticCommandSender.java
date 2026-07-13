package org.hyzionstudios.mysticessentials.platform.command;

import java.util.Optional;
import java.util.Map;
import java.util.UUID;

import org.hyzionstudios.mysticessentials.core.MysticCore;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.Argument;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Convenience wrapper around a Hytale {@link CommandContext} passed to module
 * command handlers. Exposes identity, a resolved {@link PlayerRef} for player
 * senders, simple whitespace-split arguments, and message-service-formatted
 * replies, so command code never touches the raw Hytale command API.
 */
public final class MysticCommandSender {

    private final MysticCore core;
    private final CommandContext context;
    private final String[] args;

    public MysticCommandSender(MysticCore core, CommandContext context) {
        this.core = core;
        this.context = context;
        this.args = splitArguments(context);
    }

    private static String[] splitArguments(CommandContext context) {
        String input = context.getInputString();
        if (input == null || input.isBlank()) {
            return new String[0];
        }
        String[] tokens = input.trim().split("\\s+");
        int skip = leadingCommandTokens(context, tokens);
        return skip == 0 ? tokens : java.util.Arrays.copyOfRange(tokens, skip, tokens.length);
    }

    /**
     * How many leading tokens of the input belong to the command itself rather
     * than its arguments. Verified against 0.5.6: both the console path and the
     * client chat path hand {@code CommandManager.handleCommand} the <b>whole
     * command line</b>, and {@code ParserContext.inputString} joins all of its
     * tokens — so the input starts with the called command's name path (as
     * typed, possibly an alias). Defensive: if the input does not actually
     * start with the command's name/alias at the expected depth, nothing is
     * stripped.
     */
    private static int leadingCommandTokens(CommandContext context, String[] tokens) {
        try {
            var called = context.getCalledCommand();
            if (called == null) {
                return 0;
            }
            int depth = called.countParents() + 1;
            if (tokens.length < depth) {
                return 0;
            }
            String expected = tokens[depth - 1];
            if (expected.equalsIgnoreCase(called.getName())) {
                return depth;
            }
            var aliases = called.getAliases();
            if (aliases != null && aliases.stream().anyMatch(expected::equalsIgnoreCase)) {
                return depth;
            }
            return 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Underlying Hytale context, for handlers needing the full command API. */
    public CommandContext raw() {
        return context;
    }

    public boolean isPlayer() {
        return context.isPlayer();
    }

    public String name() {
        return context.sender().getUsername();
    }

    public UUID uuid() {
        return context.sender().getUuid();
    }

    /** @return the sender as a {@link PlayerRef}, or empty for the console. */
    public Optional<PlayerRef> player() {
        if (!context.isPlayer()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(context.senderAs(PlayerRef.class));
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    public boolean hasPermission(String permission) {
        return context.sender().hasPermission(permission);
    }

    /** Reads a declared argument's parsed value (see {@code withRequiredArg}/{@code withOptionalArg}). */
    public <T> T get(Argument<?, T> argument) {
        return context.get(argument);
    }

    /** @return {@code true} if an optional argument was supplied. */
    public boolean provided(Argument<?, ?> argument) {
        return context.provided(argument);
    }

    /** Simple whitespace-split arguments (argument-token portion of the input). */
    public String[] args() {
        return args;
    }

    /** The argument portion of the input re-joined as one string (may be empty). */
    public String argString() {
        return String.join(" ", args);
    }

    public Optional<String> arg(int index) {
        return index >= 0 && index < args.length ? Optional.of(args[index]) : Optional.empty();
    }

    /** Sends a raw (formattable) string through the message pipeline. */
    public void reply(String raw) {
        context.sendMessage(core.getMessageService().format(raw));
    }

    /** Sends a message looked up by key from the message bundle. */
    public void replyKey(String key) {
        context.sendMessage(core.getMessageService().fromKey(key));
    }

    /** Sends a message looked up by key with placeholder params. */
    public void replyKey(String key, Map<String, String> params) {
        context.sendMessage(core.getMessageService().fromKey(key, params));
    }
}
