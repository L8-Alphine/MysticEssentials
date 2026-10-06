package org.hyzionstudios.mysticessentials.core.notification;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * One player's notification settings.
 *
 * <p>Preferences are <b>opt-out</b>: every surface is on until the player turns
 * it off, so a new category or a new mod's notifications reach people without
 * anyone having to opt in.</p>
 *
 * <p>The one thing preferences cannot do is silence a critical alert. That is
 * enforced in {@link #allows}, not left to each caller, because a rule applied
 * in nineteen of twenty call sites is not a rule. A server that genuinely wants
 * players to be able to mute emergencies sets {@code critical.allowPlayerDisable}
 * in {@code notifications.json}.</p>
 */
public final class NotificationPreferences {

    /** Which surfaces this player accepts. */
    public boolean chat = true;
    public boolean titles = true;
    public boolean actionBar = true;
    public boolean toasts = true;
    public boolean sounds = true;
    public boolean banners = true;

    /** Mention-specific switches, mirrored in the {@code /mentions} UI. */
    public boolean mentionHighlight = true;
    public boolean mentionSound = true;
    public boolean mentionTitle = true;
    public boolean mentionActionBar = false;

    /** Built-in scope id meaning "anyone may mention me". */
    public static final String SCOPE_EVERYONE = "everyone";
    /** Built-in scope id meaning "nobody may mention me". */
    public static final String SCOPE_NOBODY = "nobody";

    /**
     * Which mention scope this player chose, by id.
     *
     * <p>A free-form id rather than an enum, because the available scopes are
     * contributed at runtime by whichever mods are installed. A stored id whose
     * provider is not currently registered is kept as-is and simply not enforced
     * — a mod being absent must not silently rewrite somebody's choice.</p>
     */
    public String mentionScope = SCOPE_EVERYONE;

    /** Suppresses every non-critical notification while set. */
    public boolean doNotDisturb = false;

    /** Category ids this player has switched off. */
    public Set<String> mutedCategories = new LinkedHashSet<>();

    /**
     * The players this player ignores ({@code /ignore}), by UUID so a rename keeps
     * them: their chat lines, mentions and private messages do not reach this player.
     * Read and written through the synchronized accessors below, since chat threads
     * read the list while a command edits it.
     */
    public Set<UUID> ignoredPlayers = new LinkedHashSet<>();

    /**
     * Ignore-list entries from before the list was kept by UUID: lower-case names.
     * Each is resolved to {@link #ignoredPlayers} when the preferences load; a name no
     * known player has yet stays here, still applying by name, until it resolves.
     */
    public Set<String> blockedMentioners = new LinkedHashSet<>();

    /** The chosen scope id, normalized. Never blank. */
    public String scopeId() {
        return mentionScope == null || mentionScope.isBlank()
                ? SCOPE_EVERYONE
                : mentionScope.trim().toLowerCase(Locale.ROOT);
    }

    /** Whether this player has switched mentions off entirely. */
    public boolean blocksAllMentions() {
        return SCOPE_NOBODY.equals(scopeId());
    }

    /**
     * Whether this player should receive a notification of {@code category} at
     * {@code priority}.
     *
     * <p>Critical notifications bypass do-not-disturb and category mutes unless
     * the server has explicitly allowed players to disable them.</p>
     */
    public boolean allows(String category, boolean critical, boolean allowCriticalDisable) {
        if (critical && !allowCriticalDisable) {
            return true;
        }
        if (doNotDisturb) {
            return false;
        }
        return !mutedCategories.contains(normalize(category));
    }

    public boolean isMuted(String category) {
        return mutedCategories.contains(normalize(category));
    }

    public void setMuted(String category, boolean muted) {
        String normalized = normalize(category);
        if (muted) {
            mutedCategories.add(normalized);
        } else {
            mutedCategories.remove(normalized);
        }
    }

    /**
     * Whether this player ignores a player: by UUID, or by a not yet resolved name from
     * before the list was kept by UUID. Either argument may be {@code null}.
     */
    public synchronized boolean ignores(UUID player, String playerName) {
        return (player != null && ignoredPlayers.contains(player))
                || (playerName != null && blockedMentioners.contains(playerName.toLowerCase(Locale.ROOT)));
    }

    /** @return whether the list changed */
    public synchronized boolean setIgnored(UUID player, boolean ignored) {
        if (player == null) {
            return false;
        }
        return ignored ? ignoredPlayers.add(player) : ignoredPlayers.remove(player);
    }

    /** The ignored players, in the order they were added. */
    public synchronized List<UUID> ignoredIds() {
        return List.copyOf(ignoredPlayers);
    }

    /** Ignore-list names still waiting to be resolved to a UUID. */
    public synchronized List<String> pendingIgnoredNames() {
        return List.copyOf(blockedMentioners);
    }

    /** Moves a pending name to its UUID. @return whether {@code playerName} was pending */
    public synchronized boolean resolvePendingIgnore(String playerName, UUID player) {
        if (playerName == null || player == null
                || !blockedMentioners.remove(playerName.toLowerCase(Locale.ROOT))) {
            return false;
        }
        ignoredPlayers.add(player);
        return true;
    }

    /** Drops a pending name. @return whether it was pending */
    public synchronized boolean removePendingIgnore(String playerName) {
        return playerName != null && blockedMentioners.remove(playerName.trim().toLowerCase(Locale.ROOT));
    }

    /** How many players this player ignores, pending names included. */
    public synchronized int ignoredCount() {
        return ignoredPlayers.size() + blockedMentioners.size();
    }

    /** Restores collections nulled out by a hand-edited or partial JSON document. */
    public NotificationPreferences normalized() {
        if (mutedCategories == null) {
            mutedCategories = new LinkedHashSet<>();
        }
        if (blockedMentioners == null) {
            blockedMentioners = new LinkedHashSet<>();
        }
        if (ignoredPlayers == null) {
            ignoredPlayers = new LinkedHashSet<>();
        }
        ignoredPlayers.removeIf(Objects::isNull);
        blockedMentioners.removeIf(name -> name == null || name.isBlank());
        if (mentionScope == null || mentionScope.isBlank()) {
            mentionScope = SCOPE_EVERYONE;
        }
        return this;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
