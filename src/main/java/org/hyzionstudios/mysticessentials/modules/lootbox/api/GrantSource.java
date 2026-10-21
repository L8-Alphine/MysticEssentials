package org.hyzionstudios.mysticessentials.modules.lootbox.api;

/**
 * Where a key came from. Recorded on every grant; the policy profile refuses
 * {@link #PAID} unless the server runs {@code PAID_RANDOM_RESTRICTED}, which is
 * never allowed on an All Ages server. LootBox can only govern grants made
 * through it: never wire a store's deliveries to key grants on All Ages servers.
 */
public enum GrantSource {
    /** Staff command or console. */
    ADMIN_GRANT,
    /** Earned through ordinary play (drops, events, playtime). */
    GAMEPLAY,
    /** A quest reward (MysticQuests). */
    QUEST,
    /** Another plugin, through {@code LootBoxApi.grantKey}. */
    API,
    /** Bought with real money, directly or indirectly. */
    PAID;

    public static GrantSource parse(String raw, GrantSource fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
