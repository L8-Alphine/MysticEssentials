package org.hyzionstudios.mysticessentials.modules.customcommands.action;

import org.hyzionstudios.mysticessentials.modules.customcommands.CustomCommandContext;

/**
 * Runs another command with resolved placeholders, either as the console or
 * as a player (see {@code runAs} executor specs below). If the target is
 * itself a custom command it is invoked <b>internally</b> through the executor
 * so recursion tracking, the shared action budget, and the execution-depth
 * limit all apply — custom commands can compose each other but can never loop
 * forever.
 *
 * <pre>{ "type": "command", "command": "give {player_name} torch 8", "runAs": "console" }</pre>
 *
 * <p>{@code runAs} values: {@code console} (a.k.a. {@code server}),
 * {@code sender}, {@code arg:&lt;argumentName&gt;} (a declared {@code player}
 * argument), {@code player:&lt;name&gt;} (a literal or placeholder name).
 * Omitted = the command definition's default executor.</p>
 */
public final class CommandAction implements CustomCommandAction {

    private final String commandTemplate;
    /** Executor spec, or {@code null} to inherit the definition default. */
    private final String runAs;

    public CommandAction(String commandTemplate, String runAs) {
        this.commandTemplate = commandTemplate;
        this.runAs = runAs;
    }

    public String commandTemplate() {
        return commandTemplate;
    }

    public String runAs() {
        return runAs;
    }

    @Override
    public String type() {
        return "command";
    }

    @Override
    public void execute(CustomCommandContext context) {
        context.executor().dispatchCommandAction(context, this);
    }

    @Override
    public String describe() {
        return "command [" + (runAs == null ? "default" : runAs) + "]: " + commandTemplate;
    }
}
