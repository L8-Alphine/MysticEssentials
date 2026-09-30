package org.hyzionstudios.mysticessentials.modules.craftblock;

import java.util.ArrayList;
import java.util.List;

/**
 * Persisted settings for {@code modules/craftblock/config.json}.
 *
 * <p>{@link #blockedItems} is matched against a recipe's <b>output item ids</b>
 * (every output, not just the primary) and against the recipe id itself, so a
 * plain item id is enough to stop everything that produces it. Entries are
 * case-insensitive and may use {@code *} wildcards — {@code Mcw_*} blocks the
 * whole Macaw's family in one line.</p>
 */
public final class CraftBlockConfig {

    public int configVersion = 1;

    /**
     * Item ids that may not be crafted. Wildcards ({@code *}) are allowed.
     * An empty list disables blocking entirely.
     */
    public List<String> blockedItems = defaultBlockedItems();

    /**
     * Shows the client toast (Danger style) when a craft is denied — the same
     * surface the server uses for "missing ingredient", so it lands on top of
     * the open bench window where the player is actually looking.
     */
    public boolean notifyPlayer = true;

    /** Also sends the {@code craftblock-denied} chat message. Off by default: the toast is enough. */
    public boolean messageInChat = false;

    /** Logs every denied attempt (player + item id) to the server log. */
    public boolean logAttempts = false;

    private static List<String> defaultBlockedItems() {
        List<String> ids = new ArrayList<>();
        ids.add("Mcw_Windows_bench");
        ids.add("McwDoor_bench");
        ids.add("McwPaths_bench");
        ids.add("Ymmersive_carpentry_bench");
        ids.add("Ymmersive_Masnary_bench");
        ids.add("Engraving_Table");
        return ids;
    }
}
