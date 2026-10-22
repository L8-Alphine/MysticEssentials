package org.hyzionstudios.mysticessentials.modules.lootbox.validate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.hyzionstudios.mysticessentials.modules.lootbox.api.RewardProvider;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.KeyDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.OddsEngine;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.ConditionRules;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.IntegrationRegistry;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.RewardActions;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.ScheduleRules;

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
    private static final Set<String> TYPES = Set.of("ITEM", "COMMAND", "ECONOMY", "PERMISSION", "BUNDLE",
            "EXTENSION", "COSMETIC", "RPG");
    private static final int MAX_QUANTITY = 9999;
    /** Actions one bundle may deliver. */
    static final int MAX_BUNDLE_ACTIONS = 16;
    /** Bonus rolls one pool may have. */
    static final int MAX_BONUS_ROLLS = 4;
    /** Highest pity counter a pool may name, so the guarantee stays meaningful. */
    static final int MAX_PITY = 10_000;

    /** What the gates need from the running server. */
    public record Environment(
            Predicate<String> itemExists,
            Predicate<String> keyPublished,
            Map<String, String> commandPatterns,
            boolean economyAvailable,
            boolean permissionGrantAvailable,
            boolean mayPublishCommands,
            IntegrationRegistry integrations) {

        /** The Phase 1 and 2 gates, with no reward or condition provider registered. */
        public Environment(Predicate<String> itemExists, Predicate<String> keyPublished,
                Map<String, String> commandPatterns, boolean economyAvailable, boolean permissionGrantAvailable,
                boolean mayPublishCommands) {
            this(itemExists, keyPublished, commandPatterns, economyAvailable, permissionGrantAvailable,
                    mayPublishCommands, new IntegrationRegistry());
        }
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
            if (reward == null) {
                problems.add("a reward is empty");
                continue;
            }
            if (reward.id == null || !REWARD_ID.matcher(reward.id).matches()) {
                problems.add("reward id '" + reward.id + "' must match [a-z0-9_]{1,48}");
            } else if (!ids.add(reward.id)) {
                problems.add("reward id '" + reward.id + "' is used twice");
            }
            validateReward(reward, "reward '" + reward.id + "'", env, problems, false);
        }
        try {
            OddsEngine.resolveWeights(draft);
        } catch (IllegalArgumentException e) {
            problems.add(e.getMessage());
        }
        validateBonusRolls(draft, env, problems);
        validateConditions(draft, env, problems);
        validatePity(draft, problems);
        problems.addAll(ScheduleRules.validate(draft, System.currentTimeMillis()));
        String scope = org.hyzionstudios.mysticessentials.modules.lootbox.service.NetworkRules.validateScope(draft.scope);
        if (scope != null) {
            problems.add(scope);
        }
        return problems;
    }

    /** One reward, or one action of a bundle ({@code inBundle}: no id, weight, conditions or nested bundle). */
    private static void validateReward(LootBoxDraft.RewardSpec reward, String label, Environment env,
            List<String> problems, boolean inBundle) {
        if (!inBundle && (reward.name == null || reward.name.isBlank())) {
            problems.add(label + " has no name");
        }
        String type = reward.type == null ? "" : reward.type.toUpperCase(Locale.ROOT);
        if (!TYPES.contains(type)) {
            problems.add(label + " type must be ITEM, COMMAND, ECONOMY, PERMISSION, BUNDLE, EXTENSION, COSMETIC or RPG");
            return;
        }
        if (reward.duplicates != null && !reward.duplicates.isBlank()
                && !"ALLOW".equalsIgnoreCase(reward.duplicates) && !"EXCLUDE_OWNED".equalsIgnoreCase(reward.duplicates)) {
            problems.add(label + " duplicates must be ALLOW or EXCLUDE_OWNED");
        }
        if (inBundle && reward.conditions != null && !reward.conditions.isEmpty()) {
            problems.add(label + ": a bundle action can't have conditions (put them on the bundle)");
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
                if (reward.provider != null && !reward.provider.isBlank()) {
                    validateProvider(reward, label, "ECONOMY", env, problems);
                } else if (!env.economyAvailable()) {
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
            case "BUNDLE" -> {
                if (inBundle) {
                    problems.add(label + ": a bundle can't contain a bundle");
                    return;
                }
                if (reward.bundle == null || reward.bundle.isEmpty()) {
                    problems.add(label + " is a BUNDLE with no actions");
                } else if (reward.bundle.size() > MAX_BUNDLE_ACTIONS) {
                    problems.add(label + " bundles more than " + MAX_BUNDLE_ACTIONS + " actions");
                } else {
                    for (int i = 0; i < reward.bundle.size(); i++) {
                        LootBoxDraft.RewardSpec action = reward.bundle.get(i);
                        if (action == null) {
                            problems.add(label + " action " + (i + 1) + " is empty");
                        } else {
                            validateReward(action, label + " action " + (i + 1), env, problems, true);
                        }
                    }
                }
            }
            case "EXTENSION", "RPG" -> validateProvider(reward, label, type, env, problems);
            case "COSMETIC" -> {
                validateProvider(reward, label, type, env, problems);
                if (!inBundle && (reward.duplicates == null || reward.duplicates.isBlank())) {
                    problems.add(label + " is a COSMETIC reward: say what happens to duplicates "
                            + "(duplicates ALLOW or EXCLUDE_OWNED)");
                }
            }
            default -> {
            }
        }
    }

    /** A provider-delivered reward: its provider is registered, serves this type, and accepts the parameters. */
    private static void validateProvider(LootBoxDraft.RewardSpec reward, String label, String type, Environment env,
            List<String> problems) {
        if (reward.provider == null || reward.provider.isBlank()) {
            problems.add(label + " is a " + type + " reward and names no provider");
            return;
        }
        RewardProvider provider = env.integrations().reward(reward.provider);
        if (provider == null) {
            problems.add(label + " reward provider '" + reward.provider + "' is not installed");
            return;
        }
        if (!type.equalsIgnoreCase(provider.kind())) {
            problems.add(label + " reward provider '" + reward.provider + "' delivers " + provider.kind()
                    + " rewards, not " + type);
        }
        try {
            for (String problem : provider.validate(RewardActions.providerParams(reward))) {
                problems.add(label + ": " + problem);
            }
        } catch (RuntimeException e) {
            problems.add(label + ": reward provider '" + reward.provider + "' failed to check it (" + e.getMessage()
                    + ")");
        }
    }

    private static void validateBonusRolls(LootBoxDraft draft, Environment env, List<String> problems) {
        List<LootBoxDraft.BonusRoll> rolls = draft.bonusRolls();
        if (rolls.isEmpty()) {
            return;
        }
        if (rolls.size() > MAX_BONUS_ROLLS) {
            problems.add("at most " + MAX_BONUS_ROLLS + " bonus rolls");
        }
        Set<String> rollIds = new HashSet<>();
        for (LootBoxDraft.BonusRoll roll : rolls) {
            if (roll == null) {
                problems.add("a bonus roll is empty");
                continue;
            }
            String label = "bonus roll '" + roll.id + "'";
            if (roll.id == null || !REWARD_ID.matcher(roll.id).matches()) {
                problems.add("bonus roll id '" + roll.id + "' must match [a-z0-9_]{1,48}");
            } else if (!rollIds.add(roll.id)) {
                problems.add("bonus roll id '" + roll.id + "' is used twice");
            }
            if (roll.label == null || roll.label.isBlank()) {
                problems.add(label + " has no label (players see it above its rows)");
            }
            String rollMode = roll.mode == null ? "" : roll.mode.toUpperCase(Locale.ROOT);
            if (!rollMode.equals("WEIGHTED") && !rollMode.equals("EXACT_PERCENT")) {
                problems.add(label + " mode must be WEIGHTED or EXACT_PERCENT");
            }
            if (roll.rewards == null || roll.rewards.isEmpty()) {
                problems.add(label + " has no rewards");
                continue;
            }
            Set<String> ids = new HashSet<>();
            for (LootBoxDraft.RewardSpec reward : roll.rewards) {
                if (reward == null) {
                    problems.add(label + ": a reward is empty");
                    continue;
                }
                if (reward.id == null || !REWARD_ID.matcher(reward.id).matches()) {
                    problems.add(label + " reward id '" + reward.id + "' must match [a-z0-9_]{1,48}");
                } else if (!ids.add(reward.id)) {
                    problems.add(label + " reward id '" + reward.id + "' is used twice");
                }
                validateReward(reward, label + " reward '" + reward.id + "'", env, problems, false);
            }
        }
        try {
            OddsEngine.resolveBonus(draft);
        } catch (IllegalArgumentException e) {
            problems.add(e.getMessage());
        }
    }

    private static void validateConditions(LootBoxDraft draft, Environment env, List<String> problems) {
        String ineligible = draft.ineligibleRewards == null ? "" : draft.ineligibleRewards.toUpperCase(Locale.ROOT);
        if (!ineligible.isEmpty() && !ineligible.equals("RENORMALIZE") && !ineligible.equals("BLOCK")) {
            problems.add("ineligibleRewards must be RENORMALIZE or BLOCK");
        }
        for (LootBoxDraft.ConditionSpec condition : draft.conditions()) {
            problems.addAll(ConditionRules.validate(condition, "pool", env.integrations()));
        }
        boolean anyConditional = false;
        boolean anyOpen = false;
        for (LootBoxDraft.RewardSpec reward : draft.rewards) {
            if (reward == null) {
                continue;
            }
            for (LootBoxDraft.ConditionSpec condition : reward.conditions()) {
                problems.addAll(ConditionRules.validate(condition, "reward '" + reward.id + "'", env.integrations()));
            }
            if (reward.conditions().isEmpty()) {
                anyOpen = true;
            } else {
                anyConditional = true;
            }
        }
        for (LootBoxDraft.BonusRoll roll : draft.bonusRolls()) {
            if (roll == null || roll.rewards == null) {
                continue;
            }
            for (LootBoxDraft.RewardSpec reward : roll.rewards) {
                if (reward == null) {
                    continue;
                }
                for (LootBoxDraft.ConditionSpec condition : reward.conditions()) {
                    problems.addAll(ConditionRules.validate(condition,
                            "bonus roll '" + roll.id + "' reward '" + reward.id + "'", env.integrations()));
                }
            }
        }
        // §4.1: at least one outcome stays eligible for every player allowed to open.
        if (anyConditional && !anyOpen && !draft.blocksIneligible()) {
            problems.add("every reward has conditions: give at least one reward no conditions, so a player who "
                    + "fails them all still has odds (or set ineligibleRewards BLOCK)");
        }
    }

    private static void validatePity(LootBoxDraft draft, List<String> problems) {
        LootBoxDraft.Pity pity = draft.pity;
        if (pity == null) {
            return;
        }
        if (pity.targets == null || pity.targets.isEmpty()) {
            problems.add("pity lists no target rewards");
            return;
        }
        Set<String> main = new HashSet<>();
        for (LootBoxDraft.RewardSpec reward : draft.rewards) {
            if (reward != null && reward.id != null) {
                main.add(reward.id);
            }
        }
        for (String target : pity.targets) {
            if (!main.contains(target)) {
                problems.add("pity target '" + target + "' is not a reward of the main table");
            }
        }
        if (main.size() > 0 && main.stream().allMatch(id -> pity.targets.contains(id))) {
            problems.add("pity targets every reward, so it changes nothing");
        }
        if (pity.hardAfter < 0 || pity.hardAfter > MAX_PITY || pity.softAfter < 0 || pity.softAfter > MAX_PITY) {
            problems.add("pity hardAfter and softAfter must be 0-" + MAX_PITY);
        }
        if (pity.hardAfter == 0 && pity.softAfter == 0) {
            problems.add("pity needs hardAfter, softAfter or both");
        }
        if (pity.softAfter > 0 && (pity.softStepPercent < 1 || pity.softStepPercent > 10_000)) {
            problems.add("pity softStepPercent must be 1-10000 when softAfter is set");
        }
        if (pity.hardAfter > 0 && pity.softAfter > 0 && pity.softAfter >= pity.hardAfter) {
            problems.add("pity softAfter must be below hardAfter");
        }
        if (pity.softAfter > 0) {
            try {
                long total = 0;
                for (long weight : OddsEngine.resolveWeights(draft)) {
                    total = Math.addExact(total, weight);
                }
                Math.multiplyExact(total, OddsEngine.MAX_SOFT_PITY_PERCENT);
            } catch (ArithmeticException e) {
                problems.add("the total weight is too large for soft pity; use smaller weights");
            } catch (IllegalArgumentException alreadyReported) {
                // resolveWeights' own problem is reported above.
            }
        }
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
        String identity = org.hyzionstudios.mysticessentials.modules.lootbox.service.NativeKeyRules.validate(key);
        if (identity != null) {
            problems.add(identity);
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
