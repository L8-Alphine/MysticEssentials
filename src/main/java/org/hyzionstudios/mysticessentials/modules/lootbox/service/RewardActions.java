package org.hyzionstudios.mysticessentials.modules.lootbox.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import org.hyzionstudios.mysticessentials.modules.lootbox.api.RewardProvider;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxRevision;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.Opening;

/**
 * Which delivery actions an outcome turns into, and which action a fulfillment
 * delivers. One outcome is one action, except a {@code BUNDLE}, which delivers
 * every action it lists; bonus rolls add their own outcomes. Engine-free.
 */
public final class RewardActions {

    private RewardActions() {
    }

    public static String type(LootBoxDraft.RewardSpec reward) {
        return reward == null || reward.type == null ? "" : reward.type.toUpperCase(Locale.ROOT);
    }

    /** Every reward a pool can award: its main table and every bonus roll's rewards. */
    public static List<LootBoxDraft.RewardSpec> everyReward(LootBoxDraft spec) {
        List<LootBoxDraft.RewardSpec> all = new ArrayList<>();
        if (spec == null) {
            return all;
        }
        if (spec.rewards != null) {
            all.addAll(spec.rewards);
        }
        for (LootBoxDraft.BonusRoll bonus : spec.bonusRolls()) {
            if (bonus != null && bonus.rewards != null) {
                all.addAll(bonus.rewards);
            }
        }
        return all;
    }

    /** What one outcome delivers: a bundle's actions in order, otherwise the reward itself. */
    public static List<LootBoxDraft.RewardSpec> actions(LootBoxDraft.RewardSpec reward) {
        if ("BUNDLE".equals(type(reward))) {
            return reward.bundle == null ? List.of() : reward.bundle;
        }
        return List.of(reward);
    }

    /** Whether a provider delivers this action: EXTENSION, COSMETIC, RPG, or ECONOMY naming one. */
    public static boolean needsProvider(LootBoxDraft.RewardSpec action) {
        String type = type(action);
        return IntegrationRegistry.PROVIDER_TYPES.contains(type)
                || ("ECONOMY".equals(type) && action.provider != null && !action.provider.isBlank());
    }

    /**
     * The fulfillments of an outcome, appended to {@code opening}: one per action,
     * ids continuing {@code openingId#n}. {@code bonusRoll} is {@code null} for the
     * main outcome.
     */
    public static void plan(Opening opening, LootBoxDraft.RewardSpec reward, String bonusRoll) {
        List<LootBoxDraft.RewardSpec> actions = actions(reward);
        for (int i = 0; i < actions.size(); i++) {
            Opening.Fulfillment action = new Opening.Fulfillment();
            action.id = opening.openingId + "#" + opening.fulfillments.size();
            action.type = type(actions.get(i));
            action.rewardId = reward.id;
            action.action = i;
            action.bonusRoll = bonusRoll;
            opening.fulfillments.add(action);
        }
    }

    /**
     * The action {@code fulfillment} delivers, from the opening's own revision, or
     * {@code null} when the revision no longer has it. A fulfillment recorded
     * before Phase 3 names no reward: it is the main outcome's single action.
     */
    public static LootBoxDraft.RewardSpec actionOf(LootBoxRevision revision, Opening opening,
            Opening.Fulfillment fulfillment) {
        if (revision == null || revision.spec == null) {
            return null;
        }
        String rewardId = fulfillment.rewardId != null ? fulfillment.rewardId : opening.outcomeId;
        LootBoxDraft.RewardSpec reward = find(revision.spec, rewardId, fulfillment.bonusRoll);
        if (reward == null) {
            return null;
        }
        List<LootBoxDraft.RewardSpec> actions = actions(reward);
        int index = fulfillment.action;
        return index >= 0 && index < actions.size() ? actions.get(index) : null;
    }

    /**
     * The name a player reads for {@code fulfillment}: its action's own name, or
     * the reward it belongs to when a bundle action has none.
     */
    public static String labelOf(LootBoxRevision revision, Opening opening, Opening.Fulfillment fulfillment) {
        LootBoxDraft.RewardSpec action = actionOf(revision, opening, fulfillment);
        if (action != null && action.name != null && !action.name.isBlank()) {
            return action.name;
        }
        String rewardId = fulfillment.rewardId != null ? fulfillment.rewardId : opening.outcomeId;
        LootBoxDraft.RewardSpec reward = revision == null ? null : find(revision.spec, rewardId,
                fulfillment.bonusRoll);
        return reward != null && reward.name != null ? reward.name : opening.outcomeName;
    }

    /**
     * What a provider receives for {@code action}: its {@code params}, plus
     * {@code amount} and {@code currency} for an ECONOMY action (P3-WP7), which
     * the reward keeps in its own fields.
     */
    public static java.util.Map<String, String> providerParams(LootBoxDraft.RewardSpec action) {
        java.util.Map<String, String> params = new java.util.LinkedHashMap<>(action.params());
        if ("ECONOMY".equals(type(action))) {
            params.put("amount", java.math.BigDecimal.valueOf(action.amount).stripTrailingZeros().toPlainString());
            if (action.currency != null && !action.currency.isBlank()) {
                params.put("currency", action.currency.trim());
            }
        }
        return params;
    }

    /** The reward {@code rewardId} of the main table ({@code bonusRoll == null}) or of that bonus roll. */
    public static LootBoxDraft.RewardSpec find(LootBoxDraft spec, String rewardId, String bonusRoll) {
        List<LootBoxDraft.RewardSpec> list = spec.rewards;
        if (bonusRoll != null) {
            list = null;
            for (LootBoxDraft.BonusRoll bonus : spec.bonusRolls()) {
                if (bonus != null && bonusRoll.equals(bonus.id)) {
                    list = bonus.rewards;
                }
            }
        }
        if (list == null) {
            return null;
        }
        for (LootBoxDraft.RewardSpec reward : list) {
            if (reward != null && reward.id != null && reward.id.equals(rewardId)) {
                return reward;
            }
        }
        return null;
    }

    /**
     * What a repeat of {@code action} does after a crash. Built-in actions: a
     * permission node is idempotent, a command is idempotent only when the
     * operator declared it so, everything else is at most once. Provider actions
     * use their provider's declaration; with the provider gone, at most once.
     */
    public static RewardProvider.Guarantee guarantee(LootBoxDraft.RewardSpec action,
            Function<String, RewardProvider> providers) {
        if (needsProvider(action)) {
            RewardProvider provider = providers.apply(action.provider);
            return provider == null ? RewardProvider.Guarantee.AT_MOST_ONCE : provider.guarantee();
        }
        return DeliveryRules.replaySafe(action) ? RewardProvider.Guarantee.IDEMPOTENT
                : RewardProvider.Guarantee.AT_MOST_ONCE;
    }
}
