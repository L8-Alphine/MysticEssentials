package org.hyzionstudios.mysticessentials.modules.lootbox.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hyzionstudios.mysticessentials.modules.lootbox.api.ConditionProvider;
import org.hyzionstudios.mysticessentials.modules.lootbox.api.RewardProvider;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxRevision;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.Opening;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.PlayerLootState;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.PlayerOdds;

/**
 * Turns one player's situation into the {@link PlayerOdds} their table is built
 * from (Design Bible §07 steps 2–3), and works out what a committed opening does
 * to their pity counter and owned rewards. The preview and the confirmation both
 * call {@link #evaluate}; anything that changes in between (a condition, an
 * owned reward, the pity counter) changes the table's hash, so the confirmation
 * is stale and the player sees the new odds before any key is spent.
 * Engine-free.
 */
public final class EligibilityRules {

    /** What a commit changes in the player's per-pool bookkeeping. */
    public record Effects(boolean pityCounted, boolean pityReset, List<String> owned) {

        public static final Effects NONE = new Effects(false, false, List.of());
    }

    private EligibilityRules() {
    }

    /**
     * @param pool      the player's bookkeeping for this pool, or {@code null}
     * @param providers registered providers, for provider conditions, missing
     *                  providers and provider-side ownership
     */
    public static PlayerOdds evaluate(LootBoxRevision revision, PlayerLootState.PoolState pool,
            ConditionRules.Context context, IntegrationRegistry providers) {
        LootBoxDraft spec = revision.spec;
        String blocked = null;
        List<String> missing = providers.missing(spec);
        if (!missing.isEmpty()) {
            blocked = "This loot box is unavailable: " + String.join(", ", missing)
                    + (missing.size() == 1 ? " is" : " are") + " not installed. Nothing is substituted.";
        }
        for (LootBoxDraft.ConditionSpec condition : spec.conditions()) {
            if (blocked != null) {
                break;
            }
            ConditionProvider.Decision decision = ConditionRules.test(condition, context, providers);
            if (decision == ConditionProvider.Decision.FAIL) {
                blocked = "You can't open this: it needs " + ConditionRules.describe(condition, providers) + ".";
            } else if (decision == ConditionProvider.Decision.UNAVAILABLE) {
                blocked = "This can't be opened right now: " + ConditionRules.describe(condition, providers)
                        + " can't be checked.";
            }
        }
        Map<String, String> ineligible = new LinkedHashMap<>();
        Set<String> owned = new HashSet<>(pool == null || pool.owned == null ? List.of() : pool.owned);
        for (Keyed keyed : keyed(spec)) {
            for (LootBoxDraft.ConditionSpec condition : keyed.reward().conditions()) {
                ConditionProvider.Decision decision = ConditionRules.test(condition, context, providers);
                if (decision == ConditionProvider.Decision.UNAVAILABLE) {
                    if (blocked == null) {
                        blocked = "This can't be opened right now: " + ConditionRules.describe(condition, providers)
                                + " can't be checked, so the odds can't be worked out exactly.";
                    }
                } else if (decision == ConditionProvider.Decision.FAIL && !ineligible.containsKey(keyed.key())) {
                    ineligible.put(keyed.key(), "needs " + ConditionRules.describe(condition, providers));
                }
            }
            if (keyed.reward().excludesOwned() && !owned.contains(keyed.key())
                    && ownedThroughProvider(keyed.reward(), context, providers)) {
                owned.add(keyed.key());
            }
        }
        int sincePity = pool == null ? 0 : pool.sincePity;
        return new PlayerOdds(ineligible, owned, sincePity, blocked);
    }

    /** A provider-backed single action that its provider says the player already owns. */
    private static boolean ownedThroughProvider(LootBoxDraft.RewardSpec reward, ConditionRules.Context context,
            IntegrationRegistry providers) {
        if (!RewardActions.needsProvider(reward)) {
            return false;
        }
        RewardProvider provider = providers.reward(reward.provider);
        try {
            return provider != null && provider.supportsOwnership() && provider.owns(context.player(),
                    reward.params());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** What committing {@code opening} (main outcome and bonus draws) does to the player's pool bookkeeping. */
    public static Effects effects(LootBoxDraft spec, Opening opening) {
        if (spec == null || opening == null) {
            return Effects.NONE;
        }
        boolean pity = spec.pity != null && spec.pity.targets != null && !spec.pity.targets.isEmpty();
        boolean reset = pity && spec.pity.targets.contains(opening.outcomeId);
        List<String> owned = new ArrayList<>();
        LootBoxDraft.RewardSpec main = RewardActions.find(spec, opening.outcomeId, null);
        if (main != null && main.excludesOwned()) {
            owned.add(main.id);
        }
        if (opening.bonus != null) {
            for (Opening.BonusDraw draw : opening.bonus) {
                if (draw.outcomeId == null) {
                    continue;
                }
                LootBoxDraft.RewardSpec reward = RewardActions.find(spec, draw.outcomeId, draw.rollId);
                if (reward != null && reward.excludesOwned()) {
                    owned.add(PlayerOdds.key(draw.rollId, draw.outcomeId));
                }
            }
        }
        return new Effects(pity, reset, owned);
    }

    /** Applies {@code effects} to the player's bookkeeping of one pool (the caller holds the update). */
    public static void apply(PlayerLootState.PoolState pool, Effects effects) {
        if (effects.pityCounted()) {
            pool.sincePity = effects.pityReset() ? 0 : pool.sincePity + 1;
        }
        if (pool.owned == null) {
            pool.owned = new ArrayList<>();
        }
        for (String key : effects.owned()) {
            if (!pool.owned.contains(key)) {
                pool.owned.add(key);
            }
        }
    }

    private record Keyed(String key, LootBoxDraft.RewardSpec reward) {
    }

    /** Every reward of the pool with its {@link PlayerOdds#key}. */
    private static List<Keyed> keyed(LootBoxDraft spec) {
        List<Keyed> out = new ArrayList<>();
        for (LootBoxDraft.RewardSpec reward : spec.rewards) {
            out.add(new Keyed(reward.id, reward));
        }
        for (LootBoxDraft.BonusRoll roll : spec.bonusRolls()) {
            for (LootBoxDraft.RewardSpec reward : roll.rewards) {
                out.add(new Keyed(PlayerOdds.key(roll.id, reward.id), reward));
            }
        }
        return out;
    }
}
