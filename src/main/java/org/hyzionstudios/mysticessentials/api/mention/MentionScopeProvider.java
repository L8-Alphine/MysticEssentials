package org.hyzionstudios.mysticessentials.api.mention;

import java.util.UUID;

/**
 * One option in a player's "who may mention you" list.
 *
 * <p>Mystic Essentials ships only the two scopes it can actually enforce on its
 * own — {@code everyone} and {@code nobody}. Anything that depends on a
 * relationship this mod does not model (friends, guild members, party members,
 * faction, ignore lists) is contributed by the mod that owns that relationship.
 * A scope that nothing implements is <b>not shown at all</b>, which is the point:
 * offering "Friends Only" on a server with no friend system is a setting that
 * silently does nothing, and a setting that lies is worse than a missing one.</p>
 *
 * <p>Register with
 * {@link org.hyzionstudios.mysticessentials.api.service.ChatService#registerMentionScope}:</p>
 *
 * <pre>{@code
 * chat.registerMentionScope(new MentionScopeProvider() {
 *     public String getId()          { return "mysticguilds:guild"; }
 *     public String getDisplayName() { return "Guild Members Only"; }
 *     public int getSortOrder()      { return 20; }
 *
 *     public boolean isAvailable() { return guilds.isLoaded(); }
 *
 *     public boolean allows(UUID sender, UUID target) {
 *         return guilds.shareGuild(sender, target);
 *     }
 * });
 * }</pre>
 *
 * <p>A player who selected a scope whose provider later disappears keeps their
 * stored choice — it simply stops being enforced until the provider returns.
 * Their selection is never silently rewritten, because a mod being temporarily
 * absent is not a decision the player made.</p>
 */
public interface MentionScopeProvider {

    /**
     * Stable id persisted in the player's preferences. Namespace it with your mod
     * ({@code mysticguilds:guild}) so two mods cannot collide on {@code friends}.
     */
    String getId();

    /** Label shown in the {@code /mentions} list, e.g. "Friends Only". */
    String getDisplayName();

    /** Ordering in the list; lower sorts first. Built-ins use 0 and 1000. */
    default int getSortOrder() {
        return 100;
    }

    /**
     * Whether this option can currently be honoured. Return {@code false} while
     * your backing system is unloaded or misconfigured and the option is hidden
     * from the list rather than shown as a setting that would not work.
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Whether {@code sender} may mention {@code target} under this scope.
     *
     * <p>Called on the chat path, once per candidate mention, so keep it to an
     * in-memory lookup. Throwing is treated as "no" and logged; it will not break
     * the message.</p>
     */
    boolean allows(UUID sender, UUID target);
}
