package org.hyzionstudios.mysticessentials.modules.customcommands.condition;

import org.hyzionstudios.mysticessentials.modules.customcommands.CustomCommandContext;

/**
 * Passes when the sender holds (or, with {@code negate}, lacks) a permission
 * node. The console always holds every node.
 *
 * <pre>{ "type": "permission", "node": "myserver.vip", "negate": false }</pre>
 */
public final class PermissionCondition implements CommandCondition {

    private final String node;
    private final boolean negate;
    private final String denyMessage;

    public PermissionCondition(String node, boolean negate, String denyMessage) {
        this.node = node;
        this.negate = negate;
        this.denyMessage = denyMessage;
    }

    @Override
    public String type() {
        return "permission";
    }

    @Override
    public boolean test(CustomCommandContext context) {
        boolean has = context.isConsole() || context.senderHasPermission(node);
        return negate != has;
    }

    @Override
    public String describe() {
        return (negate ? "lacks permission " : "has permission ") + node;
    }

    @Override
    public String denyMessage() {
        return denyMessage;
    }
}
