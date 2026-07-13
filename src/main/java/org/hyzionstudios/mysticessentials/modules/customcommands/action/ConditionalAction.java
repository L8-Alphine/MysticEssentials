package org.hyzionstudios.mysticessentials.modules.customcommands.action;

import java.util.List;

import org.hyzionstudios.mysticessentials.modules.customcommands.CustomCommandContext;
import org.hyzionstudios.mysticessentials.modules.customcommands.condition.CommandCondition;

/**
 * Branches the chain: when every condition passes, the {@code then} actions
 * run; otherwise the {@code else} actions run. Branch actions count against
 * the same chain budget and depth limits as top-level actions.
 *
 * <pre>
 * { "type": "condition",
 *   "conditions": [ { "type": "permission", "node": "myserver.vip" } ],
 *   "then": [ { "type": "message", "text": "&aWelcome, VIP!" } ],
 *   "else": [ { "type": "message", "text": "&7Buy VIP at our store." } ] }
 * </pre>
 *
 * <p>Note: a {@code delay} inside a branch suspends only that branch's
 * remaining actions; the outer chain continues immediately after the branch's
 * synchronous part.</p>
 */
public final class ConditionalAction implements CustomCommandAction {

    private final List<CommandCondition> conditions;
    private final List<CustomCommandAction> thenActions;
    private final List<CustomCommandAction> elseActions;

    public ConditionalAction(List<CommandCondition> conditions,
            List<CustomCommandAction> thenActions, List<CustomCommandAction> elseActions) {
        this.conditions = List.copyOf(conditions);
        this.thenActions = List.copyOf(thenActions);
        this.elseActions = List.copyOf(elseActions);
    }

    @Override
    public String type() {
        return "condition";
    }

    @Override
    public void execute(CustomCommandContext context) {
        boolean pass = conditions.stream().allMatch(condition -> condition.test(context));
        context.executor().runChain(context, pass ? thenActions : elseActions);
    }

    @Override
    public String describe() {
        return "condition (" + conditions.size() + " check(s), " + thenActions.size()
                + " then / " + elseActions.size() + " else)";
    }
}
