package org.hyzionstudios.mysticessentials.modules.lootbox;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;

import org.hyzionstudios.mysticessentials.modules.lootbox.model.KeyDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.KeySerial;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxRevision;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.Opening;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.OddsEngine;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.OddsFormatter;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.OddsTable;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.SecureDraw;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.KeyMarker;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.RecoveryPolicy;
import org.hyzionstudios.mysticessentials.modules.lootbox.validate.CommandTemplate;
import org.hyzionstudios.mysticessentials.modules.lootbox.validate.DraftValidator;

/**
 * Dependency-free checks of the LootBox engine: exact odds, display rules, the
 * draw, the snapshot hash, command templates, key markers, the recovery table and
 * the publish gates. These are the parts where a bug costs players something.
 */
public final class LootBoxEngineTest {

    private static final OddsFormatter FORMAT = new OddsFormatter(2, 8, Locale.US);

    private LootBoxEngineTest() {
    }

    public static void main(String[] args) {
        weightsResolve();
        percentsMustSumToExactlyHundred();
        badWeightsAreRejected();
        formatterShowsExactValues();
        formatterNeverShowsZero();
        roundingNoteOnlyWhenNeeded();
        pickCoversBoundaries();
        hashIsStableAndSensitive();
        drawMatchesWeights();
        idsAreBase32();
        commandTemplatesAreConstrained();
        keyMarkersRejectTampering();
        recoveryTableMatchesPlan();
        validatorGatesPublishing();
        System.out.println("LootBoxEngineTest: all checks passed.");
    }

    // ----- Odds ---------------------------------------------------------------

    private static void weightsResolve() {
        List<Long> weights = OddsEngine.resolveWeights(bibleDraft());
        require(weights.equals(List.of(4500L, 3000L, 1500L, 900L, 100L)), "weights: " + weights);
    }

    private static void percentsMustSumToExactlyHundred() {
        LootBoxDraft draft = percentDraft("45", "30", "15", "9.9875", "0.0125");
        List<Long> weights = OddsEngine.resolveWeights(draft);
        require(weights.stream().mapToLong(Long::longValue).sum() == OddsEngine.PERCENT_TOTAL,
                "percent weights do not sum to 100%: " + weights);
        require(weights.get(4) == 12_500L, "0.0125% should be 12500 units: " + weights.get(4));
        requireThrows(() -> OddsEngine.resolveWeights(percentDraft("45", "30", "15", "9.9875", "0.0124")),
                "a 99.9999% pool was accepted");
        requireThrows(() -> OddsEngine.resolveWeights(percentDraft("50", "49.9999999", "0.0000001")),
                "a 7-decimal percent was accepted");
    }

    private static void badWeightsAreRejected() {
        LootBoxDraft zero = bibleDraft();
        zero.rewards.get(0).weight = 0;
        requireThrows(() -> OddsEngine.resolveWeights(zero), "a zero weight was accepted");
        LootBoxDraft negative = bibleDraft();
        negative.rewards.get(1).weight = -5;
        requireThrows(() -> OddsEngine.resolveWeights(negative), "a negative weight was accepted");
        LootBoxDraft overflow = bibleDraft();
        overflow.rewards.get(0).weight = Long.MAX_VALUE;
        requireThrows(() -> OddsEngine.resolveWeights(overflow), "an overflowing total was accepted");
        LootBoxDraft garbage = percentDraft("abc", "100");
        requireThrows(() -> OddsEngine.resolveWeights(garbage), "an unreadable percent was accepted");
    }

    private static void formatterShowsExactValues() {
        require("45.00%".equals(FORMAT.percent(4500, 10000)), FORMAT.percent(4500, 10000));
        require("1.00%".equals(FORMAT.percent(100, 10000)), FORMAT.percent(100, 10000));
        require("0.0125%".equals(FORMAT.percent(1, 8000)), FORMAT.percent(1, 8000));
        require("33.33%".equals(FORMAT.percent(1, 3)), FORMAT.percent(1, 3));
        require("0.00333%".equals(FORMAT.percent(1, 30000)), FORMAT.percent(1, 30000));
        require("100%".equals(FORMAT.percent(7, 7)), FORMAT.percent(7, 7));
        require("1 in 8,000".equals(FORMAT.oneIn(1, 8000)), FORMAT.oneIn(1, 8000));
        require("45,00%".equals(new OddsFormatter(2, 8, Locale.GERMANY).percent(4500, 10000)),
                "locale decimal separator not applied");
    }

    private static void formatterNeverShowsZero() {
        SplittableRandom random = new SplittableRandom(7);
        for (int i = 0; i < 20_000; i++) {
            long total = 1 + random.nextLong(Long.MAX_VALUE / 4);
            long weight = 1 + random.nextLong(Math.max(1, Math.min(total, 1L << random.nextInt(1, 62))));
            weight = Math.min(weight, total);
            String shown = FORMAT.percent(weight, total);
            require(!shown.matches("0([.,]0+)?%"), "a non-zero chance showed as " + shown + " (" + weight + "/"
                    + total + ")");
        }
        require("<0.00000001%".equals(FORMAT.percent(1, 10_000_000_000_000L)),
                FORMAT.percent(1, 10_000_000_000_000L));
    }

    private static void roundingNoteOnlyWhenNeeded() {
        require(!FORMAT.needsRoundingNote(List.of(4500L, 3000L, 1500L, 900L, 100L), 10000),
                "the bible's table does not need a rounding note");
        require(FORMAT.needsRoundingNote(List.of(1L, 1L, 1L), 3), "thirds need a rounding note");
    }

    private static void pickCoversBoundaries() {
        OddsTable table = OddsEngine.table(revision(bibleDraft()), "FREE_GAMEPLAY_ONLY");
        require(table.total() == 10000, "total: " + table.total());
        require("iron_sword".equals(table.pick(0).rewardId()), "roll 0");
        require("iron_sword".equals(table.pick(4499).rewardId()), "roll 4499");
        require("gold_250".equals(table.pick(4500).rewardId()), "roll 4500");
        require("guardian_companion".equals(table.pick(9999).rewardId()), "roll 9999");
        requireThrows(() -> table.pick(10000), "a roll equal to the total was accepted");
        requireThrows(() -> table.pick(-1), "a negative roll was accepted");
    }

    private static void hashIsStableAndSensitive() {
        LootBoxRevision base = revision(bibleDraft());
        String hash = OddsEngine.table(base, "FREE_GAMEPLAY_ONLY").hash();
        require(hash.equals(OddsEngine.table(revision(bibleDraft()), "FREE_GAMEPLAY_ONLY").hash()),
                "the hash is not stable");
        LootBoxDraft changed = bibleDraft();
        changed.rewards.get(4).weight = 101;
        require(!hash.equals(OddsEngine.table(revision(changed), "FREE_GAMEPLAY_ONLY").hash()),
                "a weight change kept the hash");
        LootBoxRevision bumped = revision(bibleDraft());
        bumped.revision = 4;
        require(!hash.equals(OddsEngine.table(bumped, "FREE_GAMEPLAY_ONLY").hash()),
                "a revision change kept the hash");
        require(!hash.equals(OddsEngine.table(base, "DISABLED").hash()), "a policy change kept the hash");
        require(hash.length() == 64 && OddsEngine.table(base, "FREE_GAMEPLAY_ONLY").oddsId().length() == 8,
                "hash or odds id has the wrong length");
    }

    private static void drawMatchesWeights() {
        OddsTable table = OddsEngine.table(revision(bibleDraft()), "FREE_GAMEPLAY_ONLY");
        SecureDraw draw = new SecureDraw(new SplittableRandom(20261018L));
        int trials = 1_000_000;
        long[] counts = new long[table.entries().size()];
        for (int i = 0; i < trials; i++) {
            counts[table.pick(draw.roll(table.total())).index()]++;
        }
        double chiSquare = 0;
        for (int i = 0; i < counts.length; i++) {
            double expected = trials * (double) table.entries().get(i).weight() / table.total();
            chiSquare += (counts[i] - expected) * (counts[i] - expected) / expected;
        }
        // 4 degrees of freedom, p = 0.001.
        require(chiSquare < 18.47, "draw distribution is off: chi-square " + chiSquare);
    }

    private static void idsAreBase32() {
        SecureDraw draw = new SecureDraw();
        String id = draw.id();
        require(id.length() == 26 && id.matches("[a-z2-7]+"), "id: " + id);
        require(!id.equals(draw.id()), "two ids collided");
    }

    // ----- Command templates --------------------------------------------------

    private static void commandTemplatesAreConstrained() {
        String pattern = "^examplegrant [0-9a-f-]{36} [0-9]{1,4}$";
        String uuid = "123e4567-e89b-12d3-a456-426614174000";
        String rendered = CommandTemplate.render("/examplegrant {playerUuid} {quantity}", uuid, "Steve", 3, "abc");
        require(("examplegrant " + uuid + " 3").equals(rendered), "rendered: " + rendered);
        require(CommandTemplate.allowed(rendered, pattern), "a valid command was refused");
        String hostile = CommandTemplate.render("say {playerName}", uuid, "a;b && c \"x\"", 1, "abc");
        require("say abcx".equals(hostile), "a player name was not sanitised: " + hostile);
        require(!CommandTemplate.allowed("examplegrant " + uuid + " 3; op Steve", pattern),
                "a chained command passed the allowlist");
        require(!CommandTemplate.allowed("examplegrant " + uuid + " 3\nop Steve", pattern),
                "a newline passed the allowlist");
        require(CommandTemplate.check("give {player} 1") != null, "an unknown placeholder was accepted");
        require(CommandTemplate.check("say hi\nop x") != null, "a line break was accepted");
        require(CommandTemplate.check("give {playerUuid} {quantity}") == null, "a valid template was refused");
        require(CommandTemplate.checkPattern("[unclosed") != null, "a broken pattern was accepted");
    }

    // ----- Key markers --------------------------------------------------------

    private static void keyMarkersRejectTampering() {
        byte[] secret = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
        KeyMarker marker = KeyMarker.signed("mystic:royal", 3, "serial1", 1, secret);
        require(marker.verify(secret), "a genuine marker failed");
        require(!new KeyMarker("mystic:other", 3, "serial1", 1, marker.signature()).verify(secret),
                "a changed key id verified");
        require(!new KeyMarker("mystic:royal", 4, "serial1", 1, marker.signature()).verify(secret),
                "a changed version verified");
        require(!new KeyMarker("mystic:royal", 3, "serial2", 1, marker.signature()).verify(secret),
                "a changed serial verified");
        require(!marker.verify("another-secret-another-secret-xx".getBytes(StandardCharsets.US_ASCII)),
                "a marker verified with the wrong secret");
        require(!new KeyMarker("mystic:royal", 3, "serial1", 1, null).verify(secret), "an unsigned marker verified");
    }

    // ----- Recovery -----------------------------------------------------------

    private static void recoveryTableMatchesPlan() {
        String op = "op1";
        require(RecoveryPolicy.decide(Opening.PREPARED, serial(KeySerial.ISSUED, null, null), op, true)
                == RecoveryPolicy.Action.ABORT, "PREPARED + ISSUED");
        require(RecoveryPolicy.decide(Opening.PREPARED, serial(KeySerial.RESERVED, op, null), op, true)
                == RecoveryPolicy.Action.ABORT_RELEASE, "PREPARED + RESERVED + present");
        require(RecoveryPolicy.decide(Opening.PREPARED, serial(KeySerial.RESERVED, op, null), op, false)
                == RecoveryPolicy.Action.COMMIT, "PREPARED + RESERVED + absent");
        require(RecoveryPolicy.decide(Opening.PREPARED, serial(KeySerial.REDEEMED, null, op), op, true)
                == RecoveryPolicy.Action.ENSURE_COMMITTED_CLEAN_UP, "REDEEMED + present");
        require(RecoveryPolicy.decide(Opening.COMMITTED, serial(KeySerial.REDEEMED, null, op), op, false)
                == RecoveryPolicy.Action.ENSURE_COMMITTED, "REDEEMED + absent");
        require(RecoveryPolicy.decide(Opening.PREPARED, serial(KeySerial.ISSUED, null, op), op, true)
                == RecoveryPolicy.Action.ENSURE_COMMITTED, "multi-use key redeemed by this opening");
        require(RecoveryPolicy.decide(Opening.PREPARED, serial(KeySerial.RESERVED, "other", null), op, true)
                == RecoveryPolicy.Action.ABORT_FOREIGN, "reserved by another opening");
        require(RecoveryPolicy.decide(Opening.ABORTED, serial(KeySerial.ISSUED, null, null), op, true)
                == RecoveryPolicy.Action.NONE, "terminal opening");
        require(RecoveryPolicy.decide(Opening.PREPARED, null, op, false) == RecoveryPolicy.Action.ABORT,
                "missing serial before commit");
    }

    // ----- Publish gates ------------------------------------------------------

    private static void validatorGatesPublishing() {
        DraftValidator.Environment env = new DraftValidator.Environment(
                item -> !item.startsWith("Missing"), key -> key.equals("mystic:royal"),
                Map.of("examplegrant-v1", "^examplegrant [0-9a-f-]{36} [0-9]{1,4}$"), true, true, false);
        require(DraftValidator.validate(bibleDraft(), env).isEmpty(),
                "a valid draft was refused: " + DraftValidator.validate(bibleDraft(), env));

        LootBoxDraft noKey = bibleDraft();
        noKey.keys = List.of("mystic:unpublished");
        require(contains(DraftValidator.validate(noKey, env), "not published"), "an unpublished key passed");

        LootBoxDraft badItem = bibleDraft();
        badItem.rewards.get(0).item.itemId = "Missing_Item";
        require(contains(DraftValidator.validate(badItem, env), "does not exist"), "a missing item passed");

        LootBoxDraft command = bibleDraft();
        LootBoxDraft.RewardSpec reward = command.rewards.get(1);
        reward.type = "COMMAND";
        reward.command = "examplegrant {playerUuid} 1";
        reward.allowedPatternId = "examplegrant-v1";
        require(contains(DraftValidator.validate(command, env), "rewards.command"),
                "a COMMAND reward passed without the permission");

        LootBoxDraft duplicate = bibleDraft();
        duplicate.rewards.get(1).id = "iron_sword";
        require(contains(DraftValidator.validate(duplicate, env), "used twice"), "a duplicate reward id passed");

        LootBoxDraft badId = bibleDraft();
        badId.id = "Bad Id";
        require(contains(DraftValidator.validate(badId, env), "[a-z0-9_]{3,48}"), "a bad pool id passed");

        LootBoxDraft percent = percentDraft("50", "49");
        percent.keys = List.of("mystic:royal");
        require(contains(DraftValidator.validate(percent, env), "not exactly 100%"), "a 99% pool passed");

        KeyDraft key = new KeyDraft();
        key.id = "mystic:royal";
        key.baseItemId = "Ingredient_Bar_Iron";
        key.name = "Royal Key";
        require(DraftValidator.validateKey(key, item -> true).isEmpty(), "a valid key was refused");
        key.uses = 0;
        require(!DraftValidator.validateKey(key, item -> true).isEmpty(), "a zero-use key passed");
        require("mystic:royal".equals(DraftValidator.normaliseKeyId("Royal")), "namespace default not applied");
    }

    // ----- Fixtures -----------------------------------------------------------

    /** The bible's §07 sample table. */
    private static LootBoxDraft bibleDraft() {
        LootBoxDraft draft = new LootBoxDraft();
        draft.id = "avalon_supply";
        draft.displayName = "Avalon Supply Crate";
        draft.keys = new ArrayList<>(List.of("mystic:royal"));
        draft.rewards.add(item("iron_sword", "Iron Sword", 4500));
        draft.rewards.add(item("gold_250", "250 Gold", 3000));
        draft.rewards.add(item("dungeon_token", "Dungeon Token", 1500));
        draft.rewards.add(item("royal_cosmetic", "Royal Cosmetic", 900));
        draft.rewards.add(item("guardian_companion", "Guardian Companion", 100));
        return draft;
    }

    private static LootBoxDraft percentDraft(String... percents) {
        LootBoxDraft draft = new LootBoxDraft();
        draft.id = "percent_pool";
        draft.displayName = "Percent Pool";
        draft.mode = "EXACT_PERCENT";
        for (int i = 0; i < percents.length; i++) {
            LootBoxDraft.RewardSpec reward = item("r" + i, "Reward " + i, 0);
            reward.percent = percents[i];
            draft.rewards.add(reward);
        }
        return draft;
    }

    private static LootBoxDraft.RewardSpec item(String id, String name, long weight) {
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
        revision.contentHash = "content";
        return revision;
    }

    private static KeySerial serial(String state, String reservedBy, String redeemedBy) {
        KeySerial serial = new KeySerial();
        serial.state = state;
        serial.reservedBy = reservedBy;
        serial.redeemedBy = redeemedBy;
        return serial;
    }

    private static boolean contains(List<String> problems, String fragment) {
        return problems.stream().anyMatch(problem -> problem.contains(fragment));
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
