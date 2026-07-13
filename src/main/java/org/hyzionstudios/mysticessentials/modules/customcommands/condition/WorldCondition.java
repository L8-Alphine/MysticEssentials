package org.hyzionstudios.mysticessentials.modules.customcommands.condition;

import java.util.List;
import java.util.Locale;

import org.hyzionstudios.mysticessentials.modules.customcommands.CustomCommandContext;

/**
 * Passes when the player is in one of the listed worlds (case-insensitive).
 * Fails for console senders with no player context, since they are in no world.
 *
 * <pre>{ "type": "world", "worlds": ["orbis", "arena"], "negate": false }</pre>
 */
public final class WorldCondition implements CommandCondition {

    private final List<String> worlds;
    private final boolean negate;
    private final String denyMessage;

    public WorldCondition(List<String> worlds, boolean negate, String denyMessage) {
        this.worlds = worlds.stream().map(w -> w.toLowerCase(Locale.ROOT)).toList();
        this.negate = negate;
        this.denyMessage = denyMessage;
    }

    @Override
    public String type() {
        return "world";
    }

    @Override
    public boolean test(CustomCommandContext context) {
        String current = context.playerWorldName();
        boolean inListed = current != null && worlds.contains(current.toLowerCase(Locale.ROOT));
        return negate != inListed;
    }

    @Override
    public String describe() {
        return (negate ? "not in world(s) " : "in world(s) ") + String.join(", ", worlds);
    }

    @Override
    public String denyMessage() {
        return denyMessage;
    }
}
