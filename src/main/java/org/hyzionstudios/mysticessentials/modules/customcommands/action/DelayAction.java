package org.hyzionstudios.mysticessentials.modules.customcommands.action;

import org.hyzionstudios.mysticessentials.modules.customcommands.CustomCommandContext;

/**
 * Pauses the action chain. Not executed directly: the
 * {@code CustomCommandExecutor} intercepts this action, schedules the rest of
 * the chain, and returns — so a delay never blocks a thread.
 *
 * <pre>{ "type": "delay", "seconds": 2 }</pre>
 * <pre>{ "type": "delay", "ticks": 10 }</pre>
 */
public final class DelayAction implements CustomCommandAction {

    private final long delayMillis;

    public DelayAction(long delayMillis) {
        this.delayMillis = Math.max(0, delayMillis);
    }

    public long delayMillis() {
        return delayMillis;
    }

    @Override
    public String type() {
        return "delay";
    }

    @Override
    public void execute(CustomCommandContext context) {
        // Chain control lives in the executor; nothing to do here.
    }

    @Override
    public String describe() {
        return "delay " + delayMillis + "ms";
    }
}
