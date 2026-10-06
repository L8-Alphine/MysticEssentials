package org.hyzionstudios.mysticessentials.core.notification;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
 * players to be able to mute emergencies sets
 * {@code notifications.critical.allow-player-disable}.</p>
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
     * Lower-case names of the players this player ignores ({@code /ignore}): their
     * chat lines and mentions do not reach this player. Read and written through the
     * synchronized accessors below, since chat threads read it while a command edits it.
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

    public synchronized boolean blocks(String playerName) {
        if (playerName == null) {
            return false;
        }
        return blockedMentioners.contains(playerName.toLowerCase(Locale.ROOT));
    }

    /** @return whether the list changed */
    public synchronized boolean setBlocked(String playerName, boolean blocked) {
        if (playerName == null || playerName.isBlank()) {
            return false;
        }
        String normalized = playerName.trim().toLowerCase(Locale.ROOT);
        return blocked ? blockedMentioners.add(normalized) : blockedMentioners.remove(normalized);
    }

    /** The ignored names, in the order they were added. */
    public synchronized List<String> blockedNames() {
        return List.copyOf(blockedMentioners);
    }

    /** Restores collections nulled out by a hand-edited or partial JSON document. */
    public NotificationPreferences normalized() {
        if (mutedCategories == null) {
            mutedCategories = new LinkedHashSet<>();
        }
        if (blockedMentioners == null) {
            blockedMentioners = new LinkedHashSet<>();
        }
        if (mentionScope == null || mentionScope.isBlank()) {
            mentionScope = SCOPE_EVERYONE;
        }
        return this;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
