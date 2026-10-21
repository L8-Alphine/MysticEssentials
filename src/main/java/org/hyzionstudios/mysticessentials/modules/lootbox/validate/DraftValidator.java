package org.hyzionstudios.mysticessentials.modules.lootbox.validate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.hyzionstudios.mysticessentials.modules.lootbox.model.KeyDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.OddsEngine;

/**
 * The publish gates (Design Bible §05) for pools and keys. Every engine lookup is
 * passed in as a predicate, so the gates run without a server in
 * {@code verifyLootBox}.
 */
public final class DraftValidator {

    public static final Pattern LOOTBOX_ID = Pattern.compile("[a-z0-9_]{3,48}");
    public static final Pattern KEY_ID = Pattern.compile("[a-z0-9_]+:[a-z0-9_]+");
    private static final Pattern REWARD_ID = Pattern.compile("[a-z0-9_]{1,48}");
    private static final Pattern NODE = Pattern.compile("[A-Za-z0-9_.\\-*]+");
    private static final Set<String> TYPES = Set.of("ITEM", "COMMAND", "ECONOMY", "PERMISSION");
    private static final int MAX_QUANTITY = 9999;

    /** What the gates need from the running server. */
    public record Environment(
            Predicate<String> itemExists,
            Predicate<String> keyPublished,
            Map<String, String> commandPatterns,
            boolean economyAvailable,
            boolean permissionGrantAvailable,
            boolean mayPublishCommands) {
    }

    private DraftValidator() {
    }

    /** @return every problem found; empty means the draft may be published. */
    public static List<String> validate(LootBoxDraft draft, Environment env) {
        List<String> problems = new ArrayList<>();
        if (draft == null) {
            problems.add("the draft is empty");
            return problems;
        }
        if (draft.id == null || !LOOTBOX_ID.matcher(draft.id).matches()) {
            problems.add("id must match [a-z0-9_]{3,48}");
        }
        if (draft.displayName == null || draft.displayName.isBlank()) {
            problems.add("displayName is empty");
        }
        String mode = draft.mode == null ? "" : draft.mode.toUpperCase(Locale.ROOT);
        if (!mode.equals("WEIGHTED") && !mode.equals("EXACT_PERCENT")) {
            problems.add("mode must be WEIGHTED or EXACT_PERCENT");
        }
        if (draft.keysPerOpening != 1) {
            problems.add("keysPerOpening must be 1");
        }
        if (draft.cooldownSeconds < 0) {
            problems.add("cooldownSeconds cannot be negative");
        }
        if (draft.keys == null || draft.keys.isEmpty()) {
            problems.add("list at least one key that opens this pool");
        } else {
            for (String key : draft.keys) {
                if (key == null || !KEY_ID.matcher(key).matches()) {
                    problems.add("key id '" + key + "' must look like namespace:name");
                } else if (!env.keyPublished().test(key)) {
                    problems.add("key '" + key + "' is not published (run /lootbox key publish)");
                }
            }
        }
        if (draft.icon != null && !draft.icon.isBlank() && !env.itemExists().test(draft.icon)) {
            problems.add("icon item '" + draft.icon + "' does not exist");
        }
        if (draft.rewards == null || draft.rewards.isEmpty()) {
            problems.add("add at least one reward");
            return problems;
        }

        Set<String> ids = new HashSet<>();
        for (LootBoxDraft.RewardSpec reward : draft.rewards) {
            String label = "reward '" + reward.id + "'";
            if (reward.id == null || !REWARD_ID.matcher(reward.id).matches()) {
                problems.add("reward id '" + reward.id + "' must match [a-z0-9_]{1,48}");
            } else if (!ids.add(reward.id)) {
                problems.add("reward id '" + reward.id + "' is used twice");
            }
            if (reward.name == null || reward.name.isBlank()) {
                problems.add(label + " has no name");
            }
            String type = reward.type == null ? "" : reward.type.toUpperCase(Locale.ROOT);
            if (!TYPES.contains(type)) {
                problems.add(label + " type must be ITEM, COMMAND, ECONOMY or PERMISSION");
                continue;
            }
            switch (type) {
                case "ITEM" -> {
                    if (reward.item == null || reward.item.itemId == null || reward.item.itemId.isBlank()) {
                        problems.add(label + " has no item");
                    } else if (!env.itemExists().test(reward.item.itemId)) {
                        problems.add(label + " item '" + reward.item.itemId + "' does not exist");
                    }
                    if (reward.item != null && (reward.item.quantity < 1 || reward.item.quantity > MAX_QUANTITY)) {
                        problems.add(label + " quantity must be 1-" + MAX_QUANTITY);
                    }
                }
                case "ECONOMY" -> {
                    if (!(reward.amount > 0) || Double.isInfinite(reward.amount)) {
                        problems.add(label + " amount must be a positive number");
                    }
                    if (!env.economyAvailable()) {
                        problems.add(label + " needs an economy plugin, and none is available");
                    }
                }
                case "COMMAND" -> {
                    String templateProblem = CommandTemplate.check(reward.command);
                    if (templateProblem != null) {
                        problems.add(label + ": " + templateProblem);
                    }
                    String pattern = reward.allowedPatternId == null ? null
                            : env.commandPatterns().get(reward.allowedPatternId);
                    if (pattern == null) {
                        problems.add(label + " allowedPatternId '" + reward.allowedPatternId
                                + "' is not in rewards.commandAllowlist");
                    } else {
                        String patternProblem = CommandTemplate.checkPattern(pattern);
                        if (patternProblem != null) {
                            problems.add(label + " allowlist pattern: " + patternProblem);
                        }
                    }
                    if (!env.mayPublishCommands()) {
                        problems.add(label + " is a COMMAND reward; publishing it needs "
                                + "mysticessentials.lootbox.rewards.command");
                    }
                }
                case "PERMISSION" -> {
                    if (reward.node == null || !NODE.matcher(reward.node).matches()) {
                        problems.add(label + " node is missing or has invalid characters");
                    }
                    if (reward.durationSeconds < 0) {
                        problems.add(label + " durationSeconds cannot be negative");
                    }
                    if (!env.permissionGrantAvailable()) {
                        problems.add(label + " needs LuckPerms to grant permissions");
                    }
                }
                default -> {
                }
            }
        }
        try {
            OddsEngine.resolveWeights(draft);
        } catch (IllegalArgumentException e) {
            problems.add(e.getMessage());
        }
        return problems;
    }

    /** @return every problem found in a key definition. */
    public static List<String> validateKey(KeyDraft key, Predicate<String> itemExists) {
        List<String> problems = new ArrayList<>();
        if (key == null) {
            problems.add("the key draft is empty");
            return problems;
        }
        if (key.id == null || !KEY_ID.matcher(key.id).matches()) {
            problems.add("key id must look like namespace:name ([a-z0-9_])");
        }
        if (key.baseItemId == null || key.baseItemId.isBlank()) {
            problems.add("baseItemId is empty");
        } else if (!itemExists.test(key.baseItemId)) {
            problems.add("base item '" + key.baseItemId + "' does not exist");
        }
        if (key.name == null || key.name.isBlank()) {
            problems.add("name is empty");
        }
        if (key.uses < 1 || key.uses > 1000) {
            problems.add("uses must be 1-1000");
        } else if (key.stackable && key.uses != 1) {
            // Each item of a stack opens once (P2-K); more uses would never be spent.
            problems.add("a stackable key opens once per item: uses must be 1");
        }
        if (key.expiresAfterDays < 0) {
            problems.add("expiresAfterDays cannot be negative");
        }
        return problems;
    }

    /** Applies the default {@code mystic} namespace to a bare key name. */
    public static String normaliseKeyId(String raw) {
        if (raw == null) {
            return null;
        }
        String id = raw.trim().toLowerCase(Locale.ROOT);
        return id.contains(":") ? id : "mystic:" + id;
    }
}
