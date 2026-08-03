package org.hyzionstudios.mysticessentials.modules.customcontent.layout;

import java.util.List;

import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * The services a layout needs from its host module: placeholder substitution
 * and the requirement/action engine. Keeping this an interface lets the layout
 * engine render without knowing whether a compatibility plugin is connected.
 */
public interface LayoutBridge {

    /** @return {@code text} with placeholders resolved for {@code player}. */
    String substitute(PlayerRef player, String text);

    /** @return true when {@code player} satisfies every requirement. */
    boolean meetsRequirements(PlayerRef player, List<String> requirements);

    /** Deducts the cost of requirements that consume something. */
    void consumeRequirements(PlayerRef player, List<String> requirements);

    /** Runs the action list on behalf of {@code player}. */
    void executeActions(PlayerRef player, List<String> actions);

    /**
     * @return true when an external action provider is connected and can be
     *         given action verbs the layout engine does not implement itself.
     *         When false, an unknown verb is reported to the player instead of
     *         being dropped silently.
     */
    default boolean handlesUnknownActions() {
        return false;
    }
}
