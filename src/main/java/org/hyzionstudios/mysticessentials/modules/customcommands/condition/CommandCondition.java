package org.hyzionstudios.mysticessentials.modules.customcommands.condition;

import org.hyzionstudios.mysticessentials.modules.customcommands.CustomCommandContext;

/**
 * A runtime gate evaluated before a custom command (or a {@code condition}
 * action branch) runs. Implementations must be side-effect free and safe to
 * call from the command thread and from scheduler continuations.
 */
public interface CommandCondition {

    /** JSON id of this condition type (e.g. {@code "permission"}). */
    String type();

    /** @return {@code true} if the context passes this condition. */
    boolean test(CustomCommandContext context);

    /** Human-readable summary for {@code /customcommands info} and audit logs. */
    String describe();

    /**
     * Message sent to the sender when a top-level condition fails, or
     * {@code null}/blank to use the default {@code customcommands-condition-failed}
     * bundle message. Placeholders are resolved against the context.
     */
    String denyMessage();
}
