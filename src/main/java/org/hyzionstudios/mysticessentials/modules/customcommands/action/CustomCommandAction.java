package org.hyzionstudios.mysticessentials.modules.customcommands.action;

import org.hyzionstudios.mysticessentials.modules.customcommands.CustomCommandContext;

/**
 * One step of a custom command's action chain, compiled from an entry in the
 * definition's {@code actions} array.
 *
 * <p>Actions are executed in order by the {@code CustomCommandExecutor}, which
 * owns chain control: it enforces the per-chain action budget, intercepts
 * {@link DelayAction} to suspend/resume the chain on the scheduler, and wraps
 * every {@link #execute} in a catch so one broken action cannot kill the
 * chain. Implementations therefore just perform their single effect.</p>
 */
public interface CustomCommandAction {

    /** JSON id of this action type (e.g. {@code "message"}). */
    String type();

    /** Performs the action. Placeholders in text fields resolve via the context. */
    void execute(CustomCommandContext context);

    /** Human-readable summary for {@code /customcommands info} and audit logs. */
    default String describe() {
        return type();
    }
}
