package org.hyzionstudios.mysticessentials.modules.customcommands;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.hyzionstudios.mysticessentials.modules.customcommands.action.CommandAction;
import org.hyzionstudios.mysticessentials.modules.customcommands.action.CustomCommandAction;
import org.hyzionstudios.mysticessentials.modules.customcommands.action.DelayAction;
import org.hyzionstudios.mysticessentials.modules.customcommands.argument.ArgumentParser;
import org.hyzionstudios.mysticessentials.modules.customcommands.condition.CommandCondition;
import org.hyzionstudios.mysticessentials.core.MysticCore;

import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.console.ConsoleSender;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.util.logging.Level;

/**
 * Runs custom command invocations end to end: permission gate, cooldown gate,
 * typed argument parsing, top-level conditions, and the action chain — with
 * the three safety rails that make owner-authored chains harmless:
 *
 * <ol>
 *   <li><b>Loop prevention</b> — when a {@code command} action targets another
 *       custom command it is invoked internally, and a command already on the
 *       invocation stack is refused.</li>
 *   <li><b>Action budget</b> — one shared counter per invocation
 *       ({@code safety.maxActionsPerChain}) covering every nested command,
 *       branch, and post-delay continuation.</li>
 *   <li><b>Depth limit</b> — nesting beyond {@code safety.maxExecutionDepth}
 *       is refused.</li>
 * </ol>
 *
 * <p>Delays never block: the chain suspends and resumes on the scheduler.</p>
 */
public final class CustomCommandExecutor {

    /** Safety state threaded through one invocation, shared across nesting and delays. */
    private record ChainState(int depth, AtomicInteger budget, List<String> stack) {
    }

    private final MysticCore core;
    private final CustomCommandsModule module;
    private final ArgumentParser argumentParser;

    public CustomCommandExecutor(MysticCore core, CustomCommandsModule module) {
        this.core = core;
        this.module = module;
        this.argumentParser = new ArgumentParser(core.platform());
    }

    /** @return {@code true} for a legal executor spec: console/server/sender/arg:x/player:x. */
    public static boolean isValidExecutorSpec(String spec) {
        if (spec == null || spec.isBlank()) {
            return false;
        }
        String lower = spec.toLowerCase(Locale.ROOT);
        if (lower.equals("console") || lower.equals("server") || lower.equals("sender")) {
            return true;
        }
        return (lower.startsWith("arg:") || lower.startsWith("player:"))
                && lower.substring(lower.indexOf(':') + 1).length() > 0;
    }

    // ----- Entry points -----------------------------------------------------------

    /**
     * Root invocation from a {@link DynamicCommandStub} or
     * {@code /customcommands test}. {@code rawArgs} is the argument portion of
     * the command line (may be blank); {@code test} bypasses the per-command
     * enabled flag and cooldowns.
     */
    public void invoke(CustomCommand definition, CommandSender sender, PlayerRef player,
            String label, String rawArgs, boolean test) {
        ChainState state = new ChainState(0,
                new AtomicInteger(Math.max(1, module.config().safety.maxActionsPerChain)),
                List.of(definition.nameLower()));
        invokeInternal(definition, sender, player, label, rawArgs, state, test);
    }

    private void invokeInternal(CustomCommand definition, CommandSender sender, PlayerRef player,
            String label, String rawArgs, ChainState state, boolean test) {
        boolean console = player == null;
        UUID senderUuid = player != null ? player.getUuid() : sender.getUuid();

        if (!definition.enabled && !test) {
            core.getMessageService().sendKey(sender, "customcommands-command-disabled");
            return;
        }

        // Permission gate.
        if (!module.permissions().mayUse(sender, definition, console)) {
            String custom = module.permissions().denyMessage(definition);
            if (custom != null) {
                sender.sendMessage(core.getMessageService().formatFor(senderUuid, custom));
            } else {
                core.getMessageService().sendKey(sender, "no-permission");
            }
            return;
        }

        // Cooldown gate (tests never check or start cooldowns).
        boolean bypassesCooldown = test
                || module.permissions().bypassesCooldown(sender, definition, console);
        if (!bypassesCooldown) {
            long remaining = module.cooldowns().remainingSeconds(senderUuid, definition.nameLower());
            if (remaining > 0) {
                String custom = definition.cooldown == null ? null : definition.cooldown.message;
                if (custom != null && !custom.isBlank()) {
                    sender.sendMessage(core.getMessageService().formatFor(senderUuid,
                            custom.replace("{cooldown_remaining}", Long.toString(remaining))));
                } else {
                    core.getMessageService().sendKey(sender, "customcommands-cooldown", Map.of(
                            "command", definition.nameLower(),
                            "seconds", Long.toString(remaining)));
                }
                return;
            }
        }

        // Typed arguments.
        ArgumentParser.Result parsed = argumentParser.parse(definition.arguments, rawArgs);
        if (!parsed.ok()) {
            core.getMessageService().sendKey(sender, parsed.failure().messageKey(),
                    parsed.failure().params());
            core.getMessageService().sendKey(sender, "customcommands-usage", Map.of(
                    "command", label,
                    "usage", definition.usage()));
            return;
        }

        CustomCommandContext context = new CustomCommandContext(core, module, definition, label,
                sender, player, parsed.values(), state.depth(), state.budget(), state.stack(), test);

        // Top-level conditions.
        for (CommandCondition condition : definition.compiledConditions) {
            if (condition.test(context)) {
                continue;
            }
            String deny = condition.denyMessage();
            if (deny != null && !deny.isBlank()) {
                context.replyFormatted(deny);
            } else {
                core.getMessageService().sendKey(sender, "customcommands-condition-failed");
            }
            return;
        }

        if (!bypassesCooldown && definition.cooldown != null && definition.cooldown.seconds > 0) {
            module.cooldowns().start(senderUuid, definition.nameLower(), definition.cooldown.seconds);
        }
        module.audit().logExecution(definition, sender.getUsername(), console, rawArgs, test);

        runChain(context, definition.compiledActions);
    }

    // ----- Chain driving -------------------------------------------------------------

    /** Runs an action list from the start; also used by {@code ConditionalAction} branches. */
    public void runChain(CustomCommandContext context, List<CustomCommandAction> actions) {
        runChain(context, actions, 0);
    }

    private void runChain(CustomCommandContext context, List<CustomCommandAction> actions, int start) {
        for (int i = start; i < actions.size(); i++) {
            if (!context.consumeActionBudget()) {
                module.audit().logSafetyStop(context.command(),
                        "action budget exhausted (safety.maxActionsPerChain = "
                                + module.config().safety.maxActionsPerChain + ")");
                return;
            }
            CustomCommandAction action = actions.get(i);
            if (action instanceof DelayAction delay) {
                int next = i + 1;
                core.scheduler().runLater(() -> {
                    if (module.isActive() && module.config().enabled) {
                        runChain(context, actions, next);
                    }
                }, delay.delayMillis(), TimeUnit.MILLISECONDS);
                return;
            }
            try {
                action.execute(context);
            } catch (Throwable t) {
                core.log(Level.SEVERE, "[customcommands] Action '" + action.describe()
                        + "' of /" + context.commandName() + " threw: " + t);
            }
        }
    }

    // ----- Command actions ---------------------------------------------------------------

    /** Called by {@link CommandAction}; owns blocked-command, executor, and recursion logic. */
    public void dispatchCommandAction(CustomCommandContext context, CommandAction action) {
        String resolved = context.resolvePlaceholders(action.commandTemplate()).trim();
        if (resolved.startsWith("/")) {
            resolved = resolved.substring(1);
        }
        if (resolved.isBlank()) {
            return;
        }
        int space = resolved.indexOf(' ');
        String targetName = (space < 0 ? resolved : resolved.substring(0, space)).toLowerCase(Locale.ROOT);
        String targetArgs = space < 0 ? "" : resolved.substring(space + 1).trim();

        if (isBlocked(targetName)) {
            module.audit().logSafetyStop(context.command(),
                    "refused blocked command '" + targetName + "'");
            return;
        }

        String spec = action.runAs() != null ? action.runAs()
                : (context.command().runAs == null || context.command().runAs.isBlank()
                        ? "console" : context.command().runAs);
        ResolvedExecutor executor = resolveExecutor(context, spec);
        if (executor == null) {
            module.audit().logSafetyStop(context.command(),
                    "could not resolve executor '" + spec + "' for '" + resolved + "'");
            return;
        }

        CustomCommand nested = module.registry().byLabel(targetName).orElse(null);
        if (nested != null) {
            dispatchNested(context, nested, targetName, targetArgs, executor);
            return;
        }

        module.audit().logDispatch(context.command(), resolved, executor.description);
        if (executor.player != null) {
            core.platform().dispatchPlayerCommand(executor.player, resolved);
        } else {
            core.platform().dispatchConsoleCommand(resolved);
        }
    }

    private void dispatchNested(CustomCommandContext context, CustomCommand nested,
            String label, String rawArgs, ResolvedExecutor executor) {
        int maxDepth = Math.max(1, module.config().safety.maxExecutionDepth);
        if (context.depth() + 1 >= maxDepth) {
            module.audit().logSafetyStop(context.command(),
                    "refused nested call to /" + label + " (safety.maxExecutionDepth = " + maxDepth + ")");
            return;
        }
        if (context.callStack().contains(nested.nameLower())) {
            module.audit().logSafetyStop(context.command(),
                    "refused recursive call to /" + label + " (already on the invocation stack: "
                            + String.join(" -> ", context.callStack()) + ")");
            return;
        }
        module.audit().logDispatch(context.command(), "/" + label
                + (rawArgs.isBlank() ? "" : " " + rawArgs) + " (custom)", executor.description);

        List<String> stack = new ArrayList<>(context.callStack());
        stack.add(nested.nameLower());
        // Budget is intentionally shared: composition cannot multiply work.
        invokeInternal(nested, executor.sender, executor.player, label, rawArgs,
                new ChainState(context.depth() + 1, sharedBudget(context), List.copyOf(stack)),
                context.isTest());
    }

    /** The context exposes budget consumption only; recover the counter for nesting. */
    private AtomicInteger sharedBudget(CustomCommandContext context) {
        return context.sharedBudget();
    }

    // ----- Executor resolution ---------------------------------------------------------------

    private record ResolvedExecutor(CommandSender sender, PlayerRef player, String description) {
    }

    /**
     * Resolves an executor spec to a concrete sender. {@code console}/
     * {@code server} = the console; {@code sender} = whoever invoked the
     * command; {@code arg:<name>} = the player from a declared {@code player}
     * argument; {@code player:<name>} = a literal/placeholder player name.
     *
     * @return {@code null} when the spec cannot be resolved right now (e.g.
     *         target player offline).
     */
    private ResolvedExecutor resolveExecutor(CustomCommandContext context, String spec) {
        String lower = spec.toLowerCase(Locale.ROOT);
        switch (lower) {
            case "console", "server":
                return new ResolvedExecutor(ConsoleSender.INSTANCE, null, "console");
            case "sender":
                return new ResolvedExecutor(context.sender(), context.player(),
                        "sender (" + context.sender().getUsername() + ")");
            default:
                break;
        }
        if (lower.startsWith("arg:")) {
            String argName = spec.substring("arg:".length());
            Object value = context.argValue(argName);
            PlayerRef player = value instanceof PlayerRef ref ? ref
                    : core.platform().findPlayerByName(context.argDisplay(argName)).orElse(null);
            return player == null ? null
                    : new ResolvedExecutor(player, player, "arg:" + argName
                            + " (" + player.getUsername() + ")");
        }
        if (lower.startsWith("player:")) {
            String name = context.resolvePlaceholders(spec.substring("player:".length()));
            PlayerRef player = core.platform().findPlayerByName(name).orElse(null);
            return player == null ? null
                    : new ResolvedExecutor(player, player, "player:" + player.getUsername());
        }
        return null;
    }

    private boolean isBlocked(String commandName) {
        List<String> blocked = module.config().safety.blockedCommands;
        return blocked != null && blocked.stream()
                .anyMatch(entry -> entry != null && entry.equalsIgnoreCase(commandName));
    }
}
