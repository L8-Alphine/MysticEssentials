package org.hyzionstudios.mysticessentials.modules.lootbox.integration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.hyzionstudios.mysticessentials.modules.lootbox.validate.DraftValidator;

/**
 * The engine-free rules behind the P3-WP7 bridges, kept apart so
 * {@code verifyLootBoxAdvanced} can test them without any Mystic sibling
 * installed.
 */
public final class IntegrationRules {

    /** A MysticQuests quest id, {@code package:quest}. No '.', '%' or '>': the placeholder syntax uses them. */
    public static final Pattern QUEST_ID = Pattern.compile("[A-Za-z0-9_/-]{1,64}:[A-Za-z0-9_/-]{1,64}");
    /** A MysticRPG key, {@code namespace:path} or a bare {@code path} in {@code mysticrpg}. */
    public static final Pattern RPG_KEY = Pattern.compile("([a-z0-9_.-]{1,64}:)?[a-z0-9_./-]{1,64}");

    private IntegrationRules() {
    }

    // ----- MysticQuests ------------------------------------------------------

    /** A parsed {@code mysticessentials:lootbox_key} quest event. */
    public record QuestKeyGrant(String keyId, int count, boolean virtual) {
    }

    /**
     * Reads a quest event's authored fields: {@code key} (required, a published
     * key id), {@code amount} (1 to {@code maxCount}, default 1) and
     * {@code virtual} ({@code true} by default; {@code false} hands out key
     * items). Returns the problem as a string, or the grant.
     */
    public static Object parseQuestKeyGrant(Function<String, String> field, int maxCount) {
        String key = trim(field.apply("key")).toLowerCase(Locale.ROOT);
        if (!DraftValidator.KEY_ID.matcher(key).matches()) {
            return "key must be a key id such as mystic:gold, not '" + key + "'";
        }
        String rawAmount = trim(field.apply("amount"));
        int amount;
        try {
            amount = rawAmount.isEmpty() ? 1 : Integer.parseInt(rawAmount);
        } catch (NumberFormatException e) {
            return "amount must be a whole number, not '" + rawAmount + "'";
        }
        if (amount < 1 || amount > Math.max(1, maxCount)) {
            return "amount must be between 1 and " + Math.max(1, maxCount);
        }
        String rawVirtual = trim(field.apply("virtual")).toLowerCase(Locale.ROOT);
        if (!rawVirtual.isEmpty() && !rawVirtual.equals("true") && !rawVirtual.equals("false")) {
            return "virtual must be true or false";
        }
        return new QuestKeyGrant(key, amount, !rawVirtual.equals("false"));
    }

    /** The MysticQuests placeholder whose value is {@code completed} for a finished quest. */
    public static String questPlaceholder(String questId) {
        return "%quest." + questId + "%";
    }

    public static String questPackage(String questId) {
        int colon = questId.indexOf(':');
        return colon > 0 ? questId.substring(0, colon) : questId;
    }

    // ----- MysticRPG ---------------------------------------------------------

    /** {@code path} becomes {@code mysticrpg:path}, as MysticRPG's own {@code NamespacedKey.parse} does. */
    public static String rpgKey(String raw) {
        String key = trim(raw).toLowerCase(Locale.ROOT);
        return key.indexOf(':') < 0 ? "mysticrpg:" + key : key;
    }

    // ----- Placeholders --------------------------------------------------------

    /** {@code lootbox_keys_<namespace>_<name>} for key {@code namespace:name}. */
    public static String keyPlaceholder(String keyId) {
        return "lootbox_keys_" + keyId.toLowerCase(Locale.ROOT).replace(':', '_');
    }

    /**
     * Placeholder name to key id. Two key ids that flatten to the same name
     * ({@code a_b:c} and {@code a:b_c}) map to {@code null}: the placeholder
     * answers {@code ?} rather than one of them.
     */
    public static Map<String, String> keyPlaceholders(Collection<String> keyIds) {
        Map<String, String> names = new LinkedHashMap<>();
        for (String keyId : keyIds) {
            String name = keyPlaceholder(keyId);
            if (names.containsKey(name) && !keyId.equals(names.get(name))) {
                names.put(name, null);
            } else {
                names.put(name, keyId);
            }
        }
        return names;
    }

    // ----- MysticModeration ----------------------------------------------------

    /**
     * Throttles flag forwarding: at most one forwarded flag per player and pool
     * within the cooldown, so a burst of forged keys makes one report, not a
     * hundred (MysticModeration also rate-limits each reporter).
     */
    public static final class FlagThrottle {
        private final Map<String, Long> last = new ConcurrentHashMap<>();

        /** Whether a flag for {@code player}/{@code lootboxId} at {@code now} should be forwarded. */
        public boolean admit(UUID player, String lootboxId, long now, long cooldownMs) {
            String key = player + "|" + lootboxId;
            boolean[] admitted = {false};
            last.compute(key, (k, previous) -> {
                if (previous == null || now - previous >= cooldownMs) {
                    admitted[0] = true;
                    return now;
                }
                return previous;
            });
            if (last.size() > 4096) {
                List<String> stale = new ArrayList<>();
                last.forEach((k, at) -> {
                    if (now - at >= cooldownMs) {
                        stale.add(k);
                    }
                });
                stale.forEach(last::remove);
            }
            return admitted[0];
        }
    }

    /** The report text for a flagged opening. Plain text; MysticModeration escapes it on display. */
    public static String flagReason(String lootboxId, String reason, String detail) {
        String text = "LootBox flag on " + (lootboxId == null ? "?" : lootboxId) + ": " + trim(reason)
                + (detail == null || detail.isBlank() ? "" : " (" + detail.trim() + ")");
        return text.length() > 480 ? text.substring(0, 479) + "…" : text;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
