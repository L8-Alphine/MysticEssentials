package org.hyzionstudios.mysticessentials.api.notification;

/**
 * One tab in the Notification Center's filter row.
 *
 * <p>Mystic Essentials registers only the filters it can define on its own —
 * {@code all}, {@code unread}, {@code mentions}, and {@code system}. Anything
 * that groups notifications by a concept this mod does not own (guild, party,
 * faction, auction) is contributed by the mod that owns it, and a filter nobody
 * registers is not shown at all.</p>
 *
 * <p>That rule exists because the alternative was visibly wrong: a hardcoded
 * "Guild" tab on a server with no guild mod is a button that can only ever
 * report an empty list, which reads as a broken notification centre rather than
 * an absent feature.</p>
 *
 * <p>Register with
 * {@link NotificationService#registerFilter(NotificationFilter)}:</p>
 *
 * <pre>{@code
 * notifications.registerFilter(new NotificationFilter() {
 *     public String getId()          { return "mysticguilds:guild"; }
 *     public String getDisplayName() { return "Guild"; }
 *     public int getSortOrder()      { return 40; }
 *
 *     public boolean matches(NotificationRecord record) {
 *         return "guild".equals(record.category().id());
 *     }
 * });
 * }</pre>
 */
public interface NotificationFilter {

    /**
     * Stable id, used as the selection key. Namespace it with your mod
     * ({@code mysticguilds:guild}) so two mods cannot collide on {@code guild}.
     */
    String getId();

    /** Short label shown on the tab. Keep it to one or two words. */
    String getDisplayName();

    /** Ordering in the row; lower sorts first. The built-ins use 0 through 30. */
    default int getSortOrder() {
        return 100;
    }

    /**
     * Whether this tab should currently be offered. Return {@code false} while
     * your backing system is unloaded and the tab disappears rather than showing
     * a list that can only be empty.
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Whether {@code record} belongs under this tab.
     *
     * <p>Called once per stored notification each time the page is drawn, so keep
     * it to a field comparison. Throwing is treated as "no match" and logged; it
     * will not break the page.</p>
     */
    boolean matches(NotificationRecord record);
}
