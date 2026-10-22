package org.hyzionstudios.mysticessentials.modules.lootbox;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.hyzionstudios.mysticessentials.core.util.Json;
import org.hyzionstudios.mysticessentials.modules.lootbox.api.ConditionProvider;
import org.hyzionstudios.mysticessentials.modules.lootbox.api.RewardProvider;
import org.hyzionstudios.mysticessentials.modules.lootbox.integration.IntegrationRules;
import org.hyzionstudios.mysticessentials.modules.lootbox.integration.PaidPolicy;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.KeyDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxRevision;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.Opening;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.PlayerLootState;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.ItemChance;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.OddsEngine;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.OddsFormatter;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.OddsTable;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.PlayerOdds;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.SecureDraw;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.ConditionRules;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.DeliveryRules;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.DraftLifecycle;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.EligibilityRules;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.IntegrationRegistry;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.NativeKeyRules;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.OpeningRules;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.RewardActions;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.ScheduleRules;
import org.hyzionstudios.mysticessentials.modules.lootbox.validate.DraftValidator;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Engine-free checks of Phase 3 (implementation plan §8): the provider API
 * (a missing provider blocks openings and nothing is substituted, LB-013),
 * delivery by declared guarantee, bundles and bonus rolls with their own
 * labelled distributions, eligibility conditions (renormalised or blocking,
 * failing closed), pity and duplicate protection with exact personal odds
 * (LB-003: the table shown is the table drawn, across random pity and
 * duplicate states), the "chance of getting X" calculator, and that pools
 * published before Phase 3 keep their content hash and Odds ID.
 */
public final class LootBoxAdvancedTest {

    private static final String PROFILE = "FREE_GAMEPLAY_ONLY";
    private static final OddsFormatter FORMAT = new OddsFormatter(2, 8, Locale.US);
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000042");

    private LootBoxAdvancedTest() {
    }

    public static void main(String[] args) {
        providersMakePoolsInvalidAndNothingIsSubstituted();
        deliveryFollowsTheDeclaredGuarantee();
        bundlesPlanEveryAction();
        bonusRollsHaveTheirOwnDistribution();
        conditionsRenormaliseOrBlockAndFailClosed();
        personalOddsMatchTheDraw();
        hardPityGuaranteesWithinTheLimit();
        itemChanceCombinesPathsExactly();
        schedulesStartEndAndRollBackOnce();
        phaseTwoPoolsKeepTheirHashes();
        validatorGatesPhaseThree();
        integrationsFailClosedAndParseSafely();
        nativeKeyItemsAreNamedAndValidated();
        System.out.println("LootBoxAdvancedTest: all checks passed.");
    }

    // ----- P3-WP1 Providers ---------------------------------------------------

    private static void providersMakePoolsInvalidAndNothingIsSubstituted() {
        IntegrationRegistry registry = new IntegrationRegistry();
        LootBoxDraft draft = bibleDraft();
        LootBoxDraft.RewardSpec cape = draft.rewards.get(3);
        cape.type = "COSMETIC";
        cape.provider = "cosmetics";
        cape.item = null;
        cape.duplicates = "EXCLUDE_OWNED";
        cape.params = new LinkedHashMap<>(Map.of("cosmetic", "royal_cape"));
        LootBoxRevision revision = revision(draft);

        require(registry.missing(draft).equals(List.of("reward provider 'cosmetics'")),
                "a missing provider was not reported: " + registry.missing(draft));
        PlayerOdds odds = EligibilityRules.evaluate(revision, null, context(node -> true), registry);
        OddsTable table = OddsEngine.table(revision, PROFILE, odds);
        require(table.blocked() != null && table.blocked().contains("not installed"),
                "a pool with a missing provider could be opened: " + table.blocked());

        FakeProvider provider = new FakeProvider("cosmetics", "COSMETIC", RewardProvider.Guarantee.IDEMPOTENT);
        registry.register(provider);
        require(registry.missing(draft).isEmpty(), "a registered provider was still missing");
        require(OddsEngine.table(revision, PROFILE, EligibilityRules.evaluate(revision, null, context(node -> true),
                registry)).blocked() == null, "a pool with every provider stayed blocked");

        // A stale unregister (another instance with the same id) leaves the live one alone.
        require(!registry.unregister(new FakeProvider("cosmetics", "COSMETIC", RewardProvider.Guarantee.IDEMPOTENT)),
                "a stale unregister removed the live provider");
        require(registry.unregister(provider) && !registry.missing(draft).isEmpty(),
                "unregistering did not make the pool invalid again");

        requireThrows(() -> registry.register(new FakeProvider("Bad Id", "COSMETIC",
                RewardProvider.Guarantee.IDEMPOTENT)), "a malformed provider id was accepted");
        requireThrows(() -> registry.register(new FakeProvider("x", "ITEM", RewardProvider.Guarantee.IDEMPOTENT)),
                "a provider for a built-in type was accepted");
        requireThrows(() -> registry.register(condition("PERMISSION", ConditionProvider.Decision.PASS)),
                "a provider took a built-in condition name");

        // Publishing: the provider must exist, serve the type, and accept the parameters.
        DraftValidator.Environment env = env(registry);
        require(contains(DraftValidator.validate(draft, env), "not installed"), "a missing provider was publishable");
        registry.register(new FakeProvider("cosmetics", "RPG", RewardProvider.Guarantee.IDEMPOTENT));
        require(contains(DraftValidator.validate(draft, env), "delivers RPG rewards"), "a kind mismatch passed");
        FakeProvider strict = new FakeProvider("cosmetics", "COSMETIC", RewardProvider.Guarantee.IDEMPOTENT);
        strict.problem = "unknown cosmetic";
        registry.register(strict);
        require(contains(DraftValidator.validate(draft, env), "unknown cosmetic"), "provider problems were dropped");
        strict.problem = null;
        require(DraftValidator.validate(draft, env).isEmpty(), "a valid provider reward was refused: "
                + DraftValidator.validate(draft, env));
    }

    private static void deliveryFollowsTheDeclaredGuarantee() {
        Opening.Fulfillment action = new Opening.Fulfillment();
        action.state = Opening.Fulfillment.PLANNED;
        for (RewardProvider.Guarantee guarantee : RewardProvider.Guarantee.values()) {
            require(DeliveryRules.step(action, guarantee, false) == DeliveryRules.Step.RUN,
                    "a planned action did not run under " + guarantee);
        }
        action.state = Opening.Fulfillment.ATTEMPTED;
        require(DeliveryRules.step(action, RewardProvider.Guarantee.IDEMPOTENT, false) == DeliveryRules.Step.RUN,
                "an interrupted idempotent action was not replayed");
        require(DeliveryRules.step(action, RewardProvider.Guarantee.RECONCILABLE, false)
                == DeliveryRules.Step.RECONCILE, "an interrupted reconcilable action was not reconciled first");
        require(DeliveryRules.step(action, RewardProvider.Guarantee.AT_MOST_ONCE, false) == DeliveryRules.Step.REVIEW,
                "an interrupted at-most-once action was replayed");
        require(DeliveryRules.step(action, RewardProvider.Guarantee.AT_MOST_ONCE, true) == DeliveryRules.Step.RUN,
                "a staff regrant did not run");
        action.state = Opening.Fulfillment.REVIEW;
        require(DeliveryRules.step(action, RewardProvider.Guarantee.RECONCILABLE, false) == DeliveryRules.Step.REVIEW,
                "an action already in review left it without staff");
        action.state = Opening.Fulfillment.DELIVERED;
        require(DeliveryRules.step(action, RewardProvider.Guarantee.IDEMPOTENT, true) == DeliveryRules.Step.SETTLED,
                "a delivered action ran again");

        // A provider action whose provider is gone is at most once: it can only go to review.
        LootBoxDraft.RewardSpec extension = reward("ext", "Ext", 1);
        extension.type = "EXTENSION";
        extension.provider = "gone";
        require(RewardActions.guarantee(extension, id -> null) == RewardProvider.Guarantee.AT_MOST_ONCE,
                "a missing provider's action was treated as safe to replay");
        LootBoxDraft.RewardSpec permission = reward("perm", "Perm", 1);
        permission.type = "PERMISSION";
        require(RewardActions.guarantee(permission, id -> null) == RewardProvider.Guarantee.IDEMPOTENT,
                "a permission node was not idempotent");
    }

    // ----- P3-WP2 Bundles and bonus rolls -------------------------------------

    private static void bundlesPlanEveryAction() {
        LootBoxDraft draft = bibleDraft();
        LootBoxDraft.RewardSpec bundle = draft.rewards.get(1);
        bundle.type = "BUNDLE";
        bundle.item = null;
        bundle.bundle = new ArrayList<>(List.of(reward("a", "Sword", 0), reward("b", null, 0), reward("c", "Gem", 0)));
        LootBoxRevision revision = revision(draft);
        Opening opening = new Opening();
        opening.openingId = "op";
        opening.outcomeId = bundle.id;
        opening.outcomeName = bundle.name;
        RewardActions.plan(opening, bundle, null);
        require(opening.fulfillments.size() == 3, "a 3-action bundle planned " + opening.fulfillments.size());
        for (int i = 0; i < 3; i++) {
            Opening.Fulfillment f = opening.fulfillments.get(i);
            require(f.id.equals("op#" + i) && f.action == i && bundle.id.equals(f.rewardId),
                    "bundle action " + i + " was planned as " + f.id);
            require(RewardActions.actionOf(revision, opening, f) == bundle.bundle.get(i),
                    "bundle action " + i + " resolved to the wrong spec");
        }
        require(RewardActions.labelOf(revision, opening, opening.fulfillments.get(1)).equals(bundle.name),
                "a nameless bundle action did not take its bundle's name");

        // A fulfillment recorded before Phase 3 names no reward: it is the outcome's only action.
        Opening legacy = new Opening();
        legacy.openingId = "old";
        legacy.outcomeId = "iron_sword";
        Opening.Fulfillment old = new Opening.Fulfillment();
        old.id = "old#0";
        legacy.fulfillments.add(old);
        require(RewardActions.actionOf(revision, legacy, old) == draft.rewards.get(0),
                "a Phase 1 fulfillment no longer resolves");
    }

    private static void bonusRollsHaveTheirOwnDistribution() {
        LootBoxDraft draft = bibleDraft();
        LootBoxDraft.BonusRoll roll = new LootBoxDraft.BonusRoll();
        roll.id = "cosmetic";
        roll.label = "Bonus cosmetic";
        roll.mode = "EXACT_PERCENT";
        roll.nonePercent = "90";
        LootBoxDraft.RewardSpec hat = reward("hat", "Hat", 0);
        hat.percent = "7.5";
        LootBoxDraft.RewardSpec cloak = reward("cloak", "Cloak", 0);
        cloak.percent = "2.5";
        roll.rewards.add(hat);
        roll.rewards.add(cloak);
        draft.bonusRolls = new ArrayList<>(List.of(roll));
        LootBoxRevision revision = revision(draft);
        OddsTable table = OddsEngine.table(revision, PROFILE);
        OddsTable.BonusTable bonus = table.bonus("cosmetic");
        require(bonus != null && bonus.total() == OddsEngine.PERCENT_TOTAL && bonus.none() == 90_000_000L,
                "the bonus roll did not resolve to exact percent weights");
        require(FORMAT.percent(bonus.anyWeight(), bonus.total()).equals("10.00%"),
                "a 10% bonus roll showed " + FORMAT.percent(bonus.anyWeight(), bonus.total()));
        require(table.entries().size() == 5 && table.total() == 10_000,
                "the bonus roll leaked into the main table");

        // It changes the snapshot, so a confirmation of the old odds is stale.
        LootBoxRevision plain = revision(bibleDraft());
        require(!OddsEngine.table(plain, PROFILE).hash().equals(table.hash()), "a bonus roll kept the hash");

        // Independent draws: the main distribution and the 10% bonus both hold.
        SecureDraw random = new SecureDraw(new SplittableRandom(20261022L));
        int trials = 400_000;
        long[] main = new long[table.entries().size()];
        long hats = 0;
        long cloaks = 0;
        long none = 0;
        for (int i = 0; i < trials; i++) {
            OddsEngine.Draw draw = OddsEngine.draw(table, random);
            main[draw.entry().index()]++;
            OddsTable.Entry won = draw.bonus().get(0).entry();
            if (won == null) {
                none++;
            } else if (won.rewardId().equals("hat")) {
                hats++;
            } else {
                cloaks++;
            }
        }
        double chi = 0;
        for (int i = 0; i < main.length; i++) {
            double expected = trials * (double) table.entries().get(i).weight() / table.total();
            chi += (main[i] - expected) * (main[i] - expected) / expected;
        }
        require(chi < 18.47, "main draw off with a bonus roll beside it: chi-square " + chi);
        double bonusChi = sq(hats - trials * 0.075) / (trials * 0.075) + sq(cloaks - trials * 0.025) / (trials * 0.025)
                + sq(none - trials * 0.9) / (trials * 0.9);
        // 2 degrees of freedom, p = 0.001.
        require(bonusChi < 13.82, "bonus draw off: chi-square " + bonusChi);

        // Percentages that don't add up are refused.
        roll.nonePercent = "89";
        requireThrows(() -> OddsEngine.resolveBonus(draft), "a 99% bonus roll resolved");
    }

    // ----- P3-WP3 Conditions --------------------------------------------------

    private static void conditionsRenormaliseOrBlockAndFailClosed() {
        IntegrationRegistry registry = new IntegrationRegistry();
        LootBoxDraft draft = bibleDraft();
        draft.rewards.get(4).conditions = new ArrayList<>(List.of(condition("PERMISSION", Map.of("node", "vip"))));
        LootBoxRevision revision = revision(draft);

        PlayerOdds vip = EligibilityRules.evaluate(revision, null, context(node -> node.equals("vip")), registry);
        PlayerOdds regular = EligibilityRules.evaluate(revision, null, context(node -> false), registry);
        OddsTable full = OddsEngine.table(revision, PROFILE, vip);
        OddsTable fewer = OddsEngine.table(revision, PROFILE, regular);
        require(full.entries().size() == 5 && full.total() == 10_000 && full.blocked() == null,
                "an eligible player lost a reward");
        require(fewer.entries().size() == 4 && fewer.total() == 9_900 && fewer.blocked() == null,
                "an ineligible reward was not left out and renormalised");
        require(fewer.personal().excluded().size() == 1 && !fewer.personal().notes().isEmpty(),
                "renormalising was not disclosed");
        require(!full.hash().equals(fewer.hash()), "two different tables share a hash");
        require(full.hash().equals(OddsEngine.table(revision, PROFILE).hash()),
                "an eligible player's table hashes differently from the published one");

        // BLOCK: the full odds stay on screen, and the opening is refused.
        draft.ineligibleRewards = "BLOCK";
        OddsTable blocked = OddsEngine.table(revision, PROFILE, EligibilityRules.evaluate(revision, null,
                context(node -> false), registry));
        require(blocked.entries().size() == 5 && blocked.blocked() != null, "BLOCK mode did not block");
        draft.ineligibleRewards = null;

        // Built-ins: world, region, dates, negation.
        ConditionRules.Context inside = new ConditionRules.Context(PLAYER, node -> false, "default", 10.5, 64, -3.2,
                true, 1_800_000_000_000L);
        require(test(condition("WORLD", Map.of("worlds", "nether, Default")), inside), "WORLD missed a listed world");
        require(!test(condition("WORLD", Map.of("worlds", "nether")), inside), "WORLD passed an unlisted world");
        require(test(condition("REGION", Map.of("min", "0,0,-10", "max", "20,100,0")), inside),
                "REGION missed a player inside it");
        require(!test(condition("REGION", Map.of("min", "0,0,-2", "max", "20,100,0")), inside),
                "REGION passed a player outside it (z -3.2 is block -4)");
        require(test(condition("DATE_WINDOW", Map.of("from", "2026-01-01T00:00:00Z")), inside),
                "DATE_WINDOW refused a date after from");
        require(!test(condition("DATE_WINDOW", Map.of("until", "2026-01-01T00:00:00Z")), inside),
                "DATE_WINDOW passed a date after until");
        LootBoxDraft.ConditionSpec notVip = condition("PERMISSION", Map.of("node", "vip"));
        notVip.negate = true;
        require(test(notVip, inside), "a negated condition did not invert");
        ConditionRules.Context nowhere = new ConditionRules.Context(PLAYER, node -> false, null, 0, 0, 0, false, 0);
        require(ConditionRules.test(condition("REGION", Map.of("min", "0,0,0", "max", "1,1,1")), nowhere, registry)
                == ConditionProvider.Decision.UNAVAILABLE, "an unknown position did not fail closed");

        // A provider that can't answer blocks the opening rather than guessing the odds.
        registry.register(condition("QUEST_COMPLETED", ConditionProvider.Decision.UNAVAILABLE));
        draft.rewards.get(3).conditions = new ArrayList<>(List.of(condition("QUEST_COMPLETED",
                Map.of("quest", "main:intro"))));
        OddsTable unknown = OddsEngine.table(revision, PROFILE, EligibilityRules.evaluate(revision, null,
                context(node -> true), registry));
        require(unknown.blocked() != null && unknown.blocked().contains("can't be checked"),
                "an unanswerable condition did not fail closed");

        // A pool condition that fails blocks everyone it fails for.
        draft.rewards.get(3).conditions = null;
        draft.conditions = new ArrayList<>(List.of(condition("PERMISSION", Map.of("node", "event"))));
        require(OddsEngine.table(revision, PROFILE, EligibilityRules.evaluate(revision, null, context(node -> false),
                registry)).blocked() != null, "a failed pool condition did not block");
    }

    // ----- P3-WP4 Pity and duplicates (LB-003) ---------------------------------

    /**
     * Property test: across random pity counters and owned sets, the table the
     * preview shows and the table the confirmation recomputes are the same (same
     * hash), every chance shown is exact and never 0%, and the server's draws
     * follow exactly the shown distribution.
     */
    private static void personalOddsMatchTheDraw() {
        LootBoxDraft draft = pityDraft();
        LootBoxRevision revision = revision(draft);
        IntegrationRegistry registry = new IntegrationRegistry();
        SplittableRandom states = new SplittableRandom(7L);
        for (int sample = 0; sample < 400; sample++) {
            PlayerLootState.PoolState pool = new PlayerLootState.PoolState();
            pool.sincePity = states.nextInt(0, 12);
            if (states.nextBoolean()) {
                pool.owned.add("royal_cosmetic");
            }
            if (states.nextInt(4) == 0) {
                pool.owned.add("dungeon_token");
            }
            OddsTable preview = OddsEngine.table(revision, PROFILE,
                    EligibilityRules.evaluate(revision, pool, context(node -> true), registry));
            OddsTable confirm = OddsEngine.table(revision, PROFILE,
                    EligibilityRules.evaluate(revision, pool, context(node -> true), registry));
            require(preview.hash().equals(confirm.hash()) && preview.entries().equals(confirm.entries()),
                    "the confirmation recomputed different odds for the same state");
            long sum = 0;
            for (OddsTable.Entry entry : preview.entries()) {
                sum += entry.weight();
                require(!FORMAT.percent(entry.weight(), preview.total()).startsWith("0.00"),
                        "a possible reward showed as 0%");
                if (pool.owned.contains(entry.rewardId())) {
                    require(!draft.rewards.get(entry.index()).excludesOwned(), "an owned unique reward stayed in");
                }
            }
            require(sum == preview.total(), "shown weights don't add up to the total drawn from");
            if (pool.sincePity >= draft.pity.hardAfter - 1) {
                require(preview.personal().hardPity()
                        && preview.entries().stream().allMatch(e -> draft.pity.targets.contains(e.rewardId())),
                        "hard pity left a non-target in the table");
            }
            // A different state is a different table: the old confirmation would be stale.
            PlayerLootState.PoolState next = new PlayerLootState.PoolState();
            next.sincePity = pool.sincePity + 1;
            next.owned.addAll(pool.owned);
            OddsTable after = OddsEngine.table(revision, PROFILE,
                    EligibilityRules.evaluate(revision, next, context(node -> true), registry));
            require(!after.hash().equals(preview.hash()), "a pity step kept the hash");
        }

        // The draw follows the personal table exactly, in a soft-pity state.
        PlayerLootState.PoolState soft = new PlayerLootState.PoolState();
        soft.sincePity = 6;
        OddsTable table = OddsEngine.table(revision, PROFILE,
                EligibilityRules.evaluate(revision, soft, context(node -> true), registry));
        require(table.personal().softPityPercent() == 75, "soft pity is " + table.personal().softPityPercent()
                + "%, expected 3 steps of 25%");
        OddsTable.Entry companion = table.entry("guardian_companion");
        require(companion.weight() == 100L * 175, "the soft-pity weight is not exact: " + companion.weight());
        SecureDraw random = new SecureDraw(new SplittableRandom(99L));
        int trials = 500_000;
        Map<String, Long> counts = new HashMap<>();
        for (int i = 0; i < trials; i++) {
            counts.merge(table.pick(random.roll(table.total())).rewardId(), 1L, Long::sum);
        }
        double chi = 0;
        for (OddsTable.Entry entry : table.entries()) {
            double expected = trials * (double) entry.weight() / table.total();
            chi += sq(counts.getOrDefault(entry.rewardId(), 0L) - expected) / expected;
        }
        require(chi < 18.47, "the draw does not follow the shown soft-pity odds: chi-square " + chi);
    }

    /** Opening after opening, nobody ever goes {@code hardAfter} openings without a target. */
    private static void hardPityGuaranteesWithinTheLimit() {
        LootBoxDraft draft = pityDraft();
        LootBoxRevision revision = revision(draft);
        IntegrationRegistry registry = new IntegrationRegistry();
        SecureDraw random = new SecureDraw(new SplittableRandom(5L));
        PlayerLootState state = new PlayerLootState();
        int dry = 0;
        int longest = 0;
        for (int i = 0; i < 20_000; i++) {
            OddsTable table = OddsEngine.table(revision, PROFILE,
                    EligibilityRules.evaluate(revision, state.pools.get(draft.id), context(node -> true), registry));
            OddsTable.Entry won = table.pick(random.roll(table.total()));
            Opening opening = new Opening();
            opening.openingId = "o" + i;
            opening.lootboxId = draft.id;
            opening.outcomeId = won.rewardId();
            opening.snapshotHash = table.hash();
            OpeningRules.recordCommit(state, opening, "Pool", 5, i, EligibilityRules.effects(draft, opening));
            boolean target = draft.pity.targets.contains(won.rewardId());
            dry = target ? 0 : dry + 1;
            longest = Math.max(longest, dry);
            require(state.pools.get(draft.id).sincePity == dry, "the pity counter drifted from the real streak");
        }
        require(longest < draft.pity.hardAfter, "a player went " + longest + " openings without a target; the "
                + "guarantee is " + draft.pity.hardAfter);

        // Winning an EXCLUDE_OWNED reward marks it owned in the same write.
        Opening opening = new Opening();
        opening.openingId = "x";
        opening.lootboxId = draft.id;
        opening.outcomeId = "royal_cosmetic";
        opening.snapshotHash = "h";
        PlayerLootState fresh = new PlayerLootState();
        OpeningRules.recordCommit(fresh, opening, "Pool", 5, 1, EligibilityRules.effects(draft, opening));
        require(fresh.pools.get(draft.id).owned.contains("royal_cosmetic"), "a won unique reward was not owned");
    }

    // ----- P3-WP6 Chance of getting X -------------------------------------------

    private static void itemChanceCombinesPathsExactly() {
        LootBoxDraft draft = new LootBoxDraft();
        draft.id = "chance_pool";
        draft.displayName = "Chance";
        LootBoxDraft.RewardSpec a = reward("a", "A", 1);
        a.item.itemId = "Gem";
        LootBoxDraft.RewardSpec b = reward("b", "B", 1);
        b.type = "BUNDLE";
        LootBoxDraft.RewardSpec gem = reward("g", null, 0);
        gem.item.itemId = "Gem";
        LootBoxDraft.RewardSpec ore = reward("o", null, 0);
        ore.item.itemId = "Ore";
        b.bundle = new ArrayList<>(List.of(gem, ore));
        b.item = null;
        LootBoxDraft.RewardSpec c = reward("c", "C", 2);
        c.item.itemId = "Ore";
        draft.rewards.addAll(List.of(a, b, c));
        LootBoxDraft.BonusRoll roll = new LootBoxDraft.BonusRoll();
        roll.id = "extra";
        roll.label = "Extra";
        roll.noneWeight = 3;
        LootBoxDraft.RewardSpec extraGem = reward("eg", "Extra gem", 1);
        extraGem.item.itemId = "Gem";
        roll.rewards.add(extraGem);
        draft.bonusRolls = new ArrayList<>(List.of(roll));
        LootBoxRevision revision = revision(draft);
        OddsTable table = OddsEngine.table(revision, PROFILE);

        // Main: Gem in a (1) and the bundle b (1) of 4 → 1/2. Bonus: 1/4. Combined 1 − (1/2)(3/4) = 5/8.
        ItemChance.Chance gemChance = ItemChance.of(table, revision, ItemChance.containsItem("gem"));
        require(gemChance.numerator().equals(BigInteger.valueOf(5)) && gemChance.denominator().equals(
                BigInteger.valueOf(8)), "chance of a gem is " + gemChance + ", expected exactly 5/8");
        require(FORMAT.percent(gemChance.numerator(), gemChance.denominator()).equals("62.50%"),
                "5/8 was shown as " + FORMAT.percent(gemChance.numerator(), gemChance.denominator()));
        // Ore: b (1) and c (2) of 4 → 3/4; no bonus path.
        ItemChance.Chance oreChance = ItemChance.of(table, revision, ItemChance.containsItem("Ore"));
        require(oreChance.equals(new ItemChance.Chance(BigInteger.valueOf(3), BigInteger.valueOf(4))),
                "chance of ore is " + oreChance);
        require(ItemChance.of(table, revision, ItemChance.containsItem("Nothing")).isZero(),
                "an item the pool can't give had a chance");
        require(ItemChance.items(revision).equals(List.of("Gem", "Ore")), "item list " + ItemChance.items(revision));
        require(ItemChance.worthShowing(revision), "a pool with bundles and a bonus roll hid the item chances");
        require(!ItemChance.worthShowing(revision(bibleDraftDistinctItems())),
                "item chances shown for a pool where they equal the rows");
    }

    // ----- P3-WP5 Schedules ---------------------------------------------------

    private static void schedulesStartEndAndRollBackOnce() {
        long now = ScheduleRules.instant("2026-11-01T00:00:00Z");
        LootBoxDraft draft = bibleDraft();
        draft.startsAt = "2026-12-01T00:00:00Z";
        draft.endsAt = "2026-12-31T00:00:00Z";
        require(ScheduleRules.validate(draft, now).isEmpty(), "a valid schedule was refused: "
                + ScheduleRules.validate(draft, now));
        require(ScheduleRules.startsLater(draft, now), "a future start was not scheduled");
        LootBoxDraft bad = bibleDraft();
        bad.startsAt = "next week";
        bad.endsAt = "2026-10-01T00:00:00Z";
        bad.afterEnd = "EXPLODE";
        List<String> problems = ScheduleRules.validate(bad, now);
        require(contains(problems, "startsAt must be") && contains(problems, "already in the past")
                && contains(problems, "REVERT or CLOSE"), "bad schedule fields passed: " + problems);

        // Scheduled → live at its start, remembering what was live before.
        LootBoxRevision.State state = new LootBoxRevision.State();
        state.activeRevision = 2;
        state.scheduledRevision = 3;
        state.scheduledAt = ScheduleRules.instant(draft.startsAt);
        LootBoxRevision three = revision(draft);
        three.revision = 3;
        require(ScheduleRules.due(state, now) == ScheduleRules.Due.NONE, "something was due before the start");
        require(ScheduleRules.banner(state, now).contains("start 2026-12-01"), "the start date was not shown: "
                + ScheduleRules.banner(state, now));
        long start = state.scheduledAt;
        require(ScheduleRules.due(state, start) == ScheduleRules.Due.ACTIVATE, "the start was not due");
        ScheduleRules.Claim claim = ScheduleRules.claim(state, ScheduleRules.Due.ACTIVATE, three, start);
        require(claim != null && state.activeRevision == 3 && state.revertTo == 2 && state.scheduledRevision == 0,
                "activation did not switch to the scheduled revision and remember the old one");
        // A second server sweeping the same state finds nothing to do.
        require(ScheduleRules.claim(state, ScheduleRules.Due.ACTIVATE, three, start) == null,
                "a second server activated the revision again");
        require(ScheduleRules.banner(state, start).contains("Time-limited odds until 2026-12-31"),
                "the time-limited banner is missing");

        // At the end: revert exactly once.
        long end = state.endsAt;
        require(ScheduleRules.due(state, end) == ScheduleRules.Due.END_REVERT, "the end was not due as a revert");
        ScheduleRules.Claim revert = ScheduleRules.claim(state, ScheduleRules.Due.END_REVERT, null, end);
        require(revert != null && revert.revision() == 2 && state.endsAt == 0, "the revert did not claim revision 2");
        require(ScheduleRules.claim(state, ScheduleRules.Due.END_REVERT, null, end) == null,
                "the end was claimed twice");

        // CLOSE instead of REVERT closes the pool.
        LootBoxRevision.State closing = new LootBoxRevision.State();
        LootBoxDraft closes = bibleDraft();
        closes.endsAt = "2026-12-31T00:00:00Z";
        closes.afterEnd = "close";
        LootBoxRevision closingRevision = revision(closes);
        ScheduleRules.activate(closing, closingRevision, 1, now);
        require(ScheduleRules.claim(closing, ScheduleRules.Due.END_CLOSE, null, end) != null && closing.disabled,
                "afterEnd CLOSE did not close the pool");

        // A rollback of old time-limited odds doesn't end the moment it starts.
        LootBoxRevision.State late = new LootBoxRevision.State();
        ScheduleRules.activate(late, closingRevision, 1, end + 1);
        require(late.endsAt == 0, "an end that already passed was kept");

        // A staff-scheduled rollback.
        LootBoxRevision.State rollback = new LootBoxRevision.State();
        rollback.activeRevision = 5;
        rollback.rollbackTo = 4;
        rollback.rollbackAt = now + 1000;
        require(ScheduleRules.due(rollback, now) == ScheduleRules.Due.NONE
                && ScheduleRules.due(rollback, now + 1000) == ScheduleRules.Due.ROLLBACK, "rollback timing is wrong");
        ScheduleRules.Claim back = ScheduleRules.claim(rollback, ScheduleRules.Due.ROLLBACK, null, now + 1000);
        require(back != null && back.revision() == 4 && rollback.rollbackTo == 0, "the rollback was not claimed");
    }

    // ----- Compatibility ------------------------------------------------------

    /**
     * Pools published before Phase 3 must keep their content hash (so the
     * Manager still says "Published") and their Odds ID (so open previews stay
     * valid). The canonical JSON of a draft without Phase 3 fields equals the
     * Phase 2 JSON, which had none of these keys.
     */
    private static void phaseTwoPoolsKeepTheirHashes() {
        LootBoxDraft draft = bibleDraft();
        List<Long> weights = OddsEngine.resolveWeights(draft);
        JsonObject phaseTwo = Json.toTree(draft).getAsJsonObject();
        for (String key : List.of("bonusRolls", "conditions", "ineligibleRewards", "pity", "startsAt", "endsAt",
                "afterEnd", "scope")) {
            phaseTwo.remove(key);
        }
        JsonArray rewards = phaseTwo.getAsJsonArray("rewards");
        for (JsonElement element : rewards) {
            JsonObject reward = element.getAsJsonObject();
            for (String key : List.of("provider", "params", "bundle", "currency", "conditions", "duplicates")) {
                reward.remove(key);
            }
        }
        String expected = OddsEngine.sha256(Json.toString(phaseTwo) + "|" + weights);
        require(DraftLifecycle.poolContentHash(draft, weights).equals(expected),
                "a Phase 2 pool's content hash changed");
        require(Json.toString(Json.toTree(draft)).equals(Json.toString(draft)),
                "Gson prints a tree differently from the object it came from");

        // The snapshot hash of a plain pool is the Phase 1 hash.
        LootBoxRevision revision = revision(draft);
        StringBuilder canonical = new StringBuilder().append(revision.lootboxId).append('\n')
                .append(revision.revision).append('\n').append(revision.contentHash).append('\n')
                .append(PROFILE).append('\n');
        draft.rewards.stream().map(r -> r.id).sorted().forEach(id -> canonical.append(id).append('=')
                .append(weights.get(indexOf(draft, id))).append('\n'));
        require(OddsEngine.table(revision, PROFILE).hash().equals(OddsEngine.sha256(canonical.toString())),
                "a plain pool's Odds ID changed");
        require(OddsEngine.table(revision, PROFILE).hash().equals(OddsEngine.table(revision, PROFILE,
                PlayerOdds.NONE).hash()), "nothing personal changed the hash");
    }

    private static void validatorGatesPhaseThree() {
        IntegrationRegistry registry = new IntegrationRegistry();
        DraftValidator.Environment env = env(registry);
        require(DraftValidator.validate(pityDraft(), env).isEmpty(), "a valid pity pool was refused: "
                + DraftValidator.validate(pityDraft(), env));

        LootBoxDraft badPity = pityDraft();
        badPity.pity.targets = new ArrayList<>(List.of("nope"));
        require(contains(DraftValidator.validate(badPity, env), "not a reward"), "a pity target that isn't a reward passed");
        badPity = pityDraft();
        badPity.pity.softAfter = 20;
        require(contains(DraftValidator.validate(badPity, env), "below hardAfter"), "soft pity after hard pity passed");

        LootBoxDraft cosmetic = bibleDraft();
        registry.register(new FakeProvider("cosmetics", "COSMETIC", RewardProvider.Guarantee.IDEMPOTENT));
        cosmetic.rewards.get(3).type = "COSMETIC";
        cosmetic.rewards.get(3).provider = "cosmetics";
        require(contains(DraftValidator.validate(cosmetic, env), "duplicates"),
                "a COSMETIC reward without a duplicate rule passed");

        LootBoxDraft allConditional = bibleDraft();
        for (LootBoxDraft.RewardSpec reward : allConditional.rewards) {
            reward.conditions = new ArrayList<>(List.of(condition("PERMISSION", Map.of("node", "x"))));
        }
        require(contains(DraftValidator.validate(allConditional, env), "at least one reward no conditions"),
                "a pool where everyone could be left with no odds passed");
        allConditional.ineligibleRewards = "BLOCK";
        require(!contains(DraftValidator.validate(allConditional, env), "at least one reward no conditions"),
                "BLOCK mode still asked for an unconditional reward");

        LootBoxDraft badCondition = bibleDraft();
        badCondition.conditions = new ArrayList<>(List.of(condition("DATE_WINDOW", Map.of("from", "tomorrow")),
                condition("UNKNOWN_THING", Map.of())));
        List<String> problems = DraftValidator.validate(badCondition, env);
        require(contains(problems, "ISO-8601") && contains(problems, "no plugin provides"),
                "bad conditions passed: " + problems);

        LootBoxDraft nested = bibleDraft();
        nested.rewards.get(0).type = "BUNDLE";
        LootBoxDraft.RewardSpec inner = reward("in", "In", 0);
        inner.type = "BUNDLE";
        nested.rewards.get(0).bundle = new ArrayList<>(List.of(inner));
        require(contains(DraftValidator.validate(nested, env), "can't contain a bundle"), "a nested bundle passed");
    }

    // ----- Fixtures -----------------------------------------------------------

    private static LootBoxDraft bibleDraft() {
        LootBoxDraft draft = new LootBoxDraft();
        draft.id = "avalon_supply";
        draft.displayName = "Avalon Supply Crate";
        draft.keys = new ArrayList<>(List.of("mystic:royal"));
        draft.rewards.add(reward("iron_sword", "Iron Sword", 4500));
        draft.rewards.add(reward("gold_250", "250 Gold", 3000));
        draft.rewards.add(reward("dungeon_token", "Dungeon Token", 1500));
        draft.rewards.add(reward("royal_cosmetic", "Royal Cosmetic", 900));
        draft.rewards.add(reward("guardian_companion", "Guardian Companion", 100));
        return draft;
    }

    private static LootBoxDraft bibleDraftDistinctItems() {
        LootBoxDraft draft = bibleDraft();
        for (int i = 0; i < draft.rewards.size(); i++) {
            draft.rewards.get(i).item.itemId = "Item_" + i;
        }
        return draft;
    }

    /** Hard pity at 10, soft pity from 4 at +25% per opening, on the two rarest rewards; cosmetics unique. */
    private static LootBoxDraft pityDraft() {
        LootBoxDraft draft = bibleDraft();
        draft.pity = new LootBoxDraft.Pity();
        draft.pity.targets = new ArrayList<>(List.of("royal_cosmetic", "guardian_companion"));
        draft.pity.hardAfter = 10;
        draft.pity.softAfter = 4;
        draft.pity.softStepPercent = 25;
        draft.rewards.get(3).duplicates = "EXCLUDE_OWNED";
        draft.rewards.get(2).duplicates = "ALLOW";
        return draft;
    }

    private static LootBoxDraft.RewardSpec reward(String id, String name, long weight) {
        LootBoxDraft.RewardSpec reward = new LootBoxDraft.RewardSpec();
        reward.id = id;
        reward.name = name;
        reward.type = "ITEM";
        reward.weight = weight;
        reward.item = new LootBoxDraft.ItemSpec();
        reward.item.itemId = "Ingredient_Bar_Iron";
        return reward;
    }

    private static LootBoxRevision revision(LootBoxDraft draft) {
        LootBoxRevision revision = new LootBoxRevision();
        revision.lootboxId = draft.id;
        revision.revision = 3;
        revision.spec = draft;
        revision.weights = OddsEngine.resolveWeights(draft);
        revision.bonusWeights = OddsEngine.resolveBonus(draft);
        revision.contentHash = "content";
        return revision;
    }

    private static int indexOf(LootBoxDraft draft, String id) {
        for (int i = 0; i < draft.rewards.size(); i++) {
            if (draft.rewards.get(i).id.equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private static LootBoxDraft.ConditionSpec condition(String type, Map<String, String> params) {
        LootBoxDraft.ConditionSpec condition = new LootBoxDraft.ConditionSpec();
        condition.type = type;
        condition.params = new LinkedHashMap<>(params);
        return condition;
    }

    private static ConditionProvider condition(String type, ConditionProvider.Decision answer) {
        return new ConditionProvider() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public List<String> validate(Map<String, String> params) {
                return List.of();
            }

            @Override
            public String describe(Map<String, String> params) {
                return "test " + type;
            }

            @Override
            public Decision test(UUID player, Map<String, String> params) {
                return answer;
            }
        };
    }

    private static boolean test(LootBoxDraft.ConditionSpec condition, ConditionRules.Context context) {
        return ConditionRules.test(condition, context, new IntegrationRegistry()) == ConditionProvider.Decision.PASS;
    }

    private static ConditionRules.Context context(java.util.function.Predicate<String> permission) {
        return new ConditionRules.Context(PLAYER, permission, "default", 0, 64, 0, true, 1_800_000_000_000L);
    }

    private static DraftValidator.Environment env(IntegrationRegistry registry) {
        return new DraftValidator.Environment(item -> true, key -> true, Map.of(), true, true, false, registry);
    }

    /** A provider with a settable validation problem; grants succeed. */
    static final class FakeProvider implements RewardProvider {
        private final String id;
        private final String kind;
        private final Guarantee guarantee;
        volatile String problem;
        final Set<String> granted = new HashSet<>();

        FakeProvider(String id, String kind, Guarantee guarantee) {
            this.id = id;
            this.kind = kind;
            this.guarantee = guarantee;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String kind() {
            return kind;
        }

        @Override
        public Guarantee guarantee() {
            return guarantee;
        }

        @Override
        public List<String> validate(Map<String, String> params) {
            return problem == null ? List.of() : List.of(problem);
        }

        @Override
        public String describe(Map<String, String> params) {
            return "Cosmetic " + params.getOrDefault("cosmetic", "?");
        }

        @Override
        public CompletableFuture<Result> grant(String fulfillmentId, UUID player, Map<String, String> params) {
            return CompletableFuture.completedFuture(granted.add(fulfillmentId) ? Result.delivered("ok")
                    : new Result(Outcome.ALREADY_DELIVERED, "again"));
        }
    }

    private static double sq(double value) {
        return value * value;
    }

    private static boolean contains(List<String> problems, String fragment) {
        return problems.stream().anyMatch(problem -> problem.contains(fragment));
    }

    // ----- P3-WP7 Integrations -------------------------------------------------

    private static void integrationsFailClosedAndParseSafely() {
        // PAID grants under PAID_RANDOM_RESTRICTED: every unknown refuses.
        Optional<Boolean> no = Optional.of(false);
        require(PaidPolicy.refusal(PaidPolicy.Signals.absent(), false) != null, "no MysticIdentity must refuse");
        require(PaidPolicy.refusal(null, false) != null, "no signals must refuse");
        require(PaidPolicy.refusal(new PaidPolicy.Signals(true, Optional.empty(), false, true, Optional.of(true)),
                false) != null, "an unknown managed claim must refuse");
        require(PaidPolicy.refusal(new PaidPolicy.Signals(true, Optional.of(true), false, true, Optional.of(true)),
                false) != null, "a managed claim must refuse");
        require(PaidPolicy.refusal(new PaidPolicy.Signals(true, no, true, true, Optional.of(true)), false) != null,
                "isManaged must refuse even when the claim says no");
        require(PaidPolicy.refusal(new PaidPolicy.Signals(true, no, false, false, Optional.of(true)), false) != null,
                "a refused REAL_MONEY_PURCHASE must refuse");
        require(PaidPolicy.refusal(new PaidPolicy.Signals(true, no, false, true, Optional.empty()), true) != null,
                "a required age signal that is missing must refuse");
        require(PaidPolicy.refusal(new PaidPolicy.Signals(true, no, false, true, Optional.of(false)), true) != null,
                "a minor must be refused");
        require(PaidPolicy.refusal(new PaidPolicy.Signals(true, no, false, true, Optional.empty()), false) == null,
                "a known adult-path account must be allowed when ages are verified elsewhere");
        require(PaidPolicy.refusal(new PaidPolicy.Signals(true, no, false, true, Optional.of(true)), true) == null,
                "an explicit adult signal must be allowed");

        // Quest event fields.
        Map<String, String> fields = new HashMap<>(Map.of("key", "Mystic:Gold"));
        Object parsed = IntegrationRules.parseQuestKeyGrant(fields::get, 64);
        require(parsed.equals(new IntegrationRules.QuestKeyGrant("mystic:gold", 1, true)),
                "quest key grant defaults are wrong: " + parsed);
        fields.put("amount", "3");
        fields.put("virtual", "false");
        require(IntegrationRules.parseQuestKeyGrant(fields::get, 64)
                .equals(new IntegrationRules.QuestKeyGrant("mystic:gold", 3, false)), "amount/virtual not read");
        fields.put("amount", "65");
        require(IntegrationRules.parseQuestKeyGrant(fields::get, 64) instanceof String, "amount over the cap passed");
        fields.put("amount", "0");
        require(IntegrationRules.parseQuestKeyGrant(fields::get, 64) instanceof String, "amount 0 passed");
        fields.put("amount", "1");
        fields.put("virtual", "maybe");
        require(IntegrationRules.parseQuestKeyGrant(fields::get, 64) instanceof String, "virtual=maybe passed");
        require(IntegrationRules.parseQuestKeyGrant(Map.of("key", "gold")::get, 64) instanceof String,
                "a key id without namespace passed");
        require(IntegrationRules.parseQuestKeyGrant(k -> null, 64) instanceof String, "a missing key passed");

        // Quest ids can't smuggle placeholder syntax.
        require(IntegrationRules.QUEST_ID.matcher("main:intro").matches(), "main:intro must be a quest id");
        require(IntegrationRules.QUEST_ID.matcher("chapters/one:boss-1").matches(), "package paths must pass");
        require(!IntegrationRules.QUEST_ID.matcher("main:a.b").matches(), "'.' must be refused");
        require(!IntegrationRules.QUEST_ID.matcher("main:a%b").matches(), "'%' must be refused");
        require(!IntegrationRules.QUEST_ID.matcher("a>b:c").matches(), "'>' must be refused");
        require(IntegrationRules.questPlaceholder("main:intro").equals("%quest.main:intro%"), "quest placeholder");
        require(IntegrationRules.questPackage("main:intro").equals("main"), "quest package");

        // RPG keys follow MysticRPG's NamespacedKey.parse.
        require(IntegrationRules.rpgKey("Warrior").equals("mysticrpg:warrior"), "bare RPG key not namespaced");
        require(IntegrationRules.rpgKey("addon:mage").equals("addon:mage"), "namespaced RPG key changed");

        // Placeholder names; a collision answers nothing rather than the wrong key.
        Map<String, String> names = IntegrationRules.keyPlaceholders(List.of("mystic:gold", "a_b:c", "a:b_c"));
        require("mystic:gold".equals(names.get("lootbox_keys_mystic_gold")), "key placeholder name: " + names);
        require(names.containsKey("lootbox_keys_a_b_c") && names.get("lootbox_keys_a_b_c") == null,
                "colliding key placeholders must resolve to neither: " + names);

        // Flags: one per player and pool per cooldown.
        IntegrationRules.FlagThrottle throttle = new IntegrationRules.FlagThrottle();
        UUID player = UUID.randomUUID();
        require(throttle.admit(player, "pool", 1_000, 60_000), "first flag must pass");
        require(!throttle.admit(player, "pool", 30_000, 60_000), "a flag inside the cooldown must be held");
        require(throttle.admit(player, "other", 30_000, 60_000), "another pool is a separate flag");
        require(throttle.admit(player, "pool", 61_000, 60_000), "after the cooldown a flag must pass");
        String reason = IntegrationRules.flagReason("pool", "key forged", "x".repeat(1000));
        require(reason.length() <= 480 && reason.startsWith("LootBox flag on pool: key forged"),
                "flag text not bounded: " + reason.length());
    }

    // ----- P3-WP8 Native key items ---------------------------------------------

    private static void nativeKeyItemsAreNamedAndValidated() {
        String gold = NativeKeyRules.itemId("mystic:gold_key");
        require(gold.matches("MysticLootBox_Mystic_Gold_Key_[0-9a-f]{6}"), "native item id: " + gold);
        require(gold.equals(NativeKeyRules.itemId("mystic:gold_key")), "native item ids must be stable");
        require(!NativeKeyRules.itemId("a_b:c").equals(NativeKeyRules.itemId("a:b_c")),
                "keys that flatten alike must still get different items");
        JsonObject doc = Json.gson().fromJson(NativeKeyRules.document("Ingredient_Bar_Gold"), JsonObject.class);
        require("Ingredient_Bar_Gold".equals(doc.get("Parent").getAsString()), "the item must be a child of its base");
        require(doc.get("Variant").getAsBoolean() && doc.getAsJsonArray("Categories").isEmpty(),
                "the generated item must stay out of the creative library");
        require(doc.keySet().equals(Set.of("Parent", "Variant", "Categories")),
                "nothing else may be overridden (Recipe and BlockType are not inherited): " + doc.keySet());

        KeyDraft key = new KeyDraft();
        key.id = "mystic:gold_key";
        key.baseItemId = "Ingredient_Bar_Gold";
        key.name = "Gold Key";
        require(NativeKeyRules.mode(key).equals(NativeKeyRules.METADATA_TAG), "unset identity must be METADATA_TAG");
        String before = DraftLifecycle.keySpecHash(key);
        key.identityMode = "registered_custom_asset";
        require(NativeKeyRules.wantsNative(key), "identity mode must be case-insensitive");
        require(DraftValidator.validateKey(key, id -> true).isEmpty(), "a native key must validate");
        require(!before.equals(DraftLifecycle.keySpecHash(key)), "choosing native items is a content change");
        key.identityMode = null;
        require(before.equals(DraftLifecycle.keySpecHash(key)), "an unset identityMode must keep the old hash");
        key.identityMode = "BLOCK";
        require(!DraftValidator.validateKey(key, id -> true).isEmpty(), "an unknown identity mode must be refused");
    }

    private static void requireThrows(Runnable action, String message) {
        try {
            action.run();
        } catch (RuntimeException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
