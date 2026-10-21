package org.hyzionstudios.mysticessentials.modules.lootbox;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;

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
import org.hyzionstudios.mysticessentials.modules.lootbox.service.DraftLifecycle;
import org.hyzionstudios.mysticessentials.modules.lootbox.ui.editor.DraftDiff;
import org.hyzionstudios.mysticessentials.modules.lootbox.ui.editor.EditorSession;
import org.hyzionstudios.mysticessentials.modules.lootbox.ui.editor.EditorSessions;
import org.hyzionstudios.mysticessentials.modules.lootbox.ui.editor.EditorTabs;
import org.hyzionstudios.mysticessentials.modules.lootbox.ui.editor.FormFields;
import org.hyzionstudios.mysticessentials.modules.lootbox.ui.editor.KeyDiff;
import org.hyzionstudios.mysticessentials.modules.lootbox.ui.editor.KeyText;
import org.hyzionstudios.mysticessentials.modules.lootbox.ui.editor.OddsConverter;
import org.hyzionstudios.mysticessentials.modules.lootbox.ui.editor.RewardsTab;
import org.hyzionstudios.mysticessentials.modules.lootbox.validate.CommandTemplate;
import org.hyzionstudios.mysticessentials.modules.lootbox.validate.DraftValidator;
import org.hyzionstudios.mysticessentials.platform.ui.ItemCatalog;

import com.google.gson.JsonObject;
import com.hypixel.hytale.server.core.ui.builder.EventData;

/**
 * Dependency-free checks of the LootBox engine: exact odds, display rules, the
 * draw, the snapshot hash, command templates, key markers, the recovery table and
 * the publish gates. These are the parts where a bug costs players something.
 * Also the editor kit's engine-free parts: form parsing, editor sessions, tabs
 * and the item category filter.
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
        formFieldsParseNumbers();
        formFieldsParseText();
        formFieldsCarryInput();
        editorSessionsKeepOneEditorPerPlayer();
        editorSessionTracksEditsAndReturns();
        tabsAndCategoriesResolve();
        oddsConverterKeepsChances();
        oddsConverterRoundsAndRefuses();
        draftDiffListsChanges();
        editorChancesMatchThePreview();
        rewardListOperations();
        keyNamesSplitAndJoin();
        keyDiffListsChanges();
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

    // ----- Editor kit ---------------------------------------------------------

    private static final FormFields.Field WEIGHT = FormFields.Field.text("weight", "WeightInput", "Weight");
    private static final FormFields.Field PERCENT = FormFields.Field.text("percent", "PercentInput", "Chance");
    private static final FormFields.Field POOL_ID = FormFields.Field.text("id", "IdInput", "Pool id");
    private static final FormFields.Field NAME = FormFields.Field.text("name", "NameInput", "Display name");

    private static void formFieldsParseNumbers() {
        require(FormFields.intInRange(WEIGHT, " 250 ", 1, 1000).value() == 250, "a padded weight was refused");
        require(FormFields.intInRange(WEIGHT, "1", 1, 1000).ok() && FormFields.intInRange(WEIGHT, "1000", 1, 1000).ok(),
                "a weight on the range boundary was refused");
        requireKind(FormFields.intInRange(WEIGHT, "", 1, 1000), FormFields.Kind.BLANK, "blank weight");
        requireKind(FormFields.intInRange(WEIGHT, "12a", 1, 1000), FormFields.Kind.NOT_A_NUMBER, "12a");
        requireKind(FormFields.intInRange(WEIGHT, "2.5", 1, 1000), FormFields.Kind.NOT_A_NUMBER, "2.5 as a weight");
        requireKind(FormFields.intInRange(WEIGHT, "0", 1, 1000), FormFields.Kind.OUT_OF_RANGE, "weight 0");
        requireKind(FormFields.intInRange(WEIGHT, "1001", 1, 1000), FormFields.Kind.OUT_OF_RANGE, "weight 1001");
        requireKind(FormFields.intInRange(WEIGHT, "99999999999999999999", 1, 1000), FormFields.Kind.OUT_OF_RANGE,
                "a weight past Long.MAX_VALUE");
        require(FormFields.longInRange(WEIGHT, "86400", 0, 31_536_000L).value() == 86_400L, "a cooldown was refused");

        BigDecimal hundred = BigDecimal.valueOf(100);
        FormFields.Result<BigDecimal> exact = FormFields.decimal(PERCENT, "0.012500", 6, BigDecimal.ZERO, hundred);
        require(exact.ok() && "0.0125".equals(exact.value().toPlainString()),
                "trailing zeros should not count as places: " + exact);
        require("45".equals(FormFields.decimal(PERCENT, "45.000", 6, BigDecimal.ZERO, hundred).value().toPlainString()),
                "45.000 should store as 45");
        require("100".equals(FormFields.decimal(PERCENT, "100.0", 6, BigDecimal.ZERO, hundred).value().toPlainString()),
                "100.0 should store as 100, not 1E+2");
        require(FormFields.decimal(PERCENT, "0.000001", 6, BigDecimal.ZERO, hundred).ok(), "6 places was refused");
        requireKind(FormFields.decimal(PERCENT, "0.0000001", 6, BigDecimal.ZERO, hundred),
                FormFields.Kind.TOO_MANY_PLACES, "7 places");
        requireKind(FormFields.decimal(PERCENT, "1e2", 6, BigDecimal.ZERO, hundred), FormFields.Kind.NOT_A_NUMBER,
                "an exponent");
        requireKind(FormFields.decimal(PERCENT, "12,5", 6, BigDecimal.ZERO, hundred), FormFields.Kind.NOT_A_NUMBER,
                "a decimal comma");
        requireKind(FormFields.decimal(PERCENT, "100.5", 6, BigDecimal.ZERO, hundred), FormFields.Kind.OUT_OF_RANGE,
                "100.5%");
        requireKind(FormFields.decimal(PERCENT, "-1", 6, BigDecimal.ZERO, null), FormFields.Kind.OUT_OF_RANGE,
                "a negative chance");
        requireKind(FormFields.decimal(PERCENT, "  ", 6, null, null), FormFields.Kind.BLANK, "a blank chance");
        // What the parser accepts, the odds engine accepts.
        LootBoxDraft draft = percentDraft(
                FormFields.decimal(PERCENT, "99.9875000", 6, BigDecimal.ZERO, hundred).value().toPlainString(),
                FormFields.decimal(PERCENT, ".0125", 6, BigDecimal.ZERO, hundred).value().toPlainString());
        require(OddsEngine.resolveWeights(draft).stream().mapToLong(Long::longValue).sum() == OddsEngine.PERCENT_TOTAL,
                "parsed percents did not resolve to exactly 100%");
    }

    private static void formFieldsParseText() {
        require("avalon_supply".equals(FormFields.id(POOL_ID, " avalon_supply ", DraftValidator.LOOTBOX_ID,
                "3-48 of a-z, 0-9 and _").value()), "a valid pool id was refused");
        FormFields.Result<String> spaced = FormFields.id(POOL_ID, "Bad Id", DraftValidator.LOOTBOX_ID,
                "3-48 of a-z, 0-9 and _");
        requireKind(spaced, FormFields.Kind.BAD_FORMAT, "a pool id with a space");
        require(spaced.problem().message().startsWith("Pool id must be 3-48"), "id message: " + spaced.problem());
        requireKind(FormFields.id(POOL_ID, "ab", DraftValidator.LOOTBOX_ID, "an id"), FormFields.Kind.BAD_FORMAT,
                "a 2-character pool id");
        requireKind(FormFields.id(POOL_ID, "royal", DraftValidator.KEY_ID, "namespace:name"),
                FormFields.Kind.BAD_FORMAT, "a key id without a namespace");
        require(FormFields.id(POOL_ID, "mystic:royal", DraftValidator.KEY_ID, "namespace:name").ok(),
                "a valid key id was refused");
        requireKind(FormFields.id(POOL_ID, null, DraftValidator.KEY_ID, "namespace:name"), FormFields.Kind.BLANK,
                "a missing id");

        require("Avalon".equals(FormFields.nonBlank(NAME, "  Avalon ", 64).value()), "a name was not trimmed");
        requireKind(FormFields.nonBlank(NAME, " \t", 64), FormFields.Kind.BLANK, "a blank name");
        requireKind(FormFields.nonBlank(NAME, "x".repeat(65), 64), FormFields.Kind.TOO_LONG, "a 65-character name");

        List<FormFields.Problem> problems = new ArrayList<>();
        Integer weight = FormFields.intInRange(WEIGHT, "abc", 1, 1000).collect(problems);
        String name = FormFields.nonBlank(NAME, "Crate", 64).collect(problems);
        FormFields.nonBlank(NAME, "", 64).collect(problems);
        require(weight == null && "Crate".equals(name) && problems.size() == 2
                && problems.get(0).field().equals("weight") && problems.get(1).kind() == FormFields.Kind.BLANK,
                "collect should gather every problem by field: " + problems);
    }

    private static void formFieldsCarryInput() {
        FormFields form = FormFields.of(NAME, WEIGHT, FormFields.Field.color("color", "ColorInput", "Colour"));
        EventData event = form.event("tab").put("tab", "rewards");
        require("#NameInput.Value".equals(event.events().get("@name"))
                && "#WeightInput.Value".equals(event.events().get("@weight"))
                && "#ColorInput.Color".equals(event.events().get("@color"))
                && "tab".equals(event.events().get("action")) && "rewards".equals(event.events().get("tab")),
                "an event did not carry every field: " + event.events());
        // Pages pass ids with their '#'; a doubled "##NameInput.Value" binds nothing on the client.
        require("#NameInput.Value".equals(FormFields.Field.text("name", "#NameInput", "Name").selector())
                && "#NameColor.Color".equals(FormFields.Field.color("c", "#NameColor", "Colour").selector()),
                "an element id given with '#' produced a doubled selector");

        JsonObject payload = new JsonObject();
        payload.addProperty("action", "tab");
        payload.addProperty("@name", "Half-typed");
        payload.addProperty("@weight", "");
        Map<String, String> read = form.read(payload);
        require(read.equals(Map.of("name", "Half-typed", "weight", "")),
                "read should keep emptied fields and skip absent ones: " + read);
        require(FormFields.submitted(payload, "color") == null, "an absent field read as present");

        EditorSession<LootBoxDraft> session = new EditorSessions().start(UUID.randomUUID(), "avalon_supply",
                LootBoxDraft.class, bibleDraft(), "hash");
        form.remember(payload, session);
        payload.addProperty("@weight", "12a");
        form.remember(payload, session);
        require("Half-typed".equals(session.input("name")) && "12a".equals(session.input("weight"))
                && session.input("color") == null, "unsaved input was not remembered: " + session.inputs());
    }

    private static void editorSessionsKeepOneEditorPerPlayer() {
        EditorSessions sessions = new EditorSessions();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        EditorSession<LootBoxDraft> first = sessions.start(alice, "avalon_supply", LootBoxDraft.class, bibleDraft(),
                "h1");
        EditorSession<KeyDraft> second = sessions.start(alice, "mystic:royal", KeyDraft.class, new KeyDraft(), null);
        require(!first.active() && second.active() && sessions.size() == 1,
                "starting a second editor did not end the first");
        require(sessions.get(alice, KeyDraft.class).isPresent() && sessions.get(alice, LootBoxDraft.class).isEmpty(),
                "a typed lookup returned the wrong draft type");
        require(second.isNew() && !first.isNew(), "a null hash should mean a new draft");

        EditorSession<LootBoxDraft> bobs = sessions.start(bob, "avalon_supply", LootBoxDraft.class, bibleDraft(), "h1");
        require(sessions.editing(LootBoxDraft.class, "avalon_supply", alice).equals(List.of(bob)),
                "the other editor of a draft was not found");
        require(!sessions.end(first), "ending a stale session reported success");
        require(sessions.get(alice).isPresent(), "a stale end removed Alice's session");

        sessions.end(bob);
        require(!bobs.active() && sessions.get(bob).isEmpty(), "disconnect did not end the session");
        bobs.awaitReturn(id -> {
            throw new AssertionError("an ended session ran a picker return");
        });
        require(!bobs.completeReturn("Rock_Stone"), "an ended session accepted a picker return");

        sessions.clear();
        require(!second.active() && sessions.size() == 0, "disable did not end every session");
    }

    private static void editorSessionTracksEditsAndReturns() {
        EditorSession<LootBoxDraft> session = new EditorSessions().start(UUID.randomUUID(), "avalon_supply",
                LootBoxDraft.class, bibleDraft(), "h1");
        require(!session.dirty() && session.selectedRow() == -1 && session.tab() == null,
                "a fresh session should be clean with nothing selected");

        session.awaitReturn(id -> session.working().icon = id);
        require(session.awaitingReturn(), "the picker return was not armed");
        require(session.completeReturn("Rock_Stone") && "Rock_Stone".equals(session.working().icon),
                "the picker return did not reach the working copy");
        require(session.dirty(), "a picker return did not mark the draft dirty");
        require(!session.completeReturn("Rock_Marble") && "Rock_Stone".equals(session.working().icon),
                "a picker return ran twice");
        session.awaitReturn(id -> session.working().icon = id);
        require(session.cancelReturn() && !session.completeReturn("Rock_Marble"),
                "a cancelled picker still applied its choice");

        long atSave = session.changes();
        session.markDirty();
        session.markSaved("h2", atSave);
        require(session.dirty() && "h2".equals(session.loadedHash()),
                "an edit made during the save was hidden by it");
        session.markSaved("h3", session.changes());
        require(!session.dirty() && "h3".equals(session.loadedHash()), "a save did not clean the session");

        session.tab("rewards");
        session.selectRow(3);
        session.rememberInputs(Map.of("name", "typed"));
        session.awaitReturn(id -> session.working().icon = id);
        session.markDirty();
        LootBoxDraft fresh = bibleDraft();
        session.reload(fresh, "h4");
        require(session.working() == fresh && !session.dirty() && "h4".equals(session.loadedHash())
                && session.inputs().isEmpty() && !session.awaitingReturn(),
                "reload should replace the copy and drop unsaved input");
        require("rewards".equals(session.tab()) && session.selectedRow() == 3, "reload should keep the tab and row");
        session.selectRow(-7);
        require(session.selectedRow() == -1, "a negative row should mean no selection");
    }

    private static void tabsAndCategoriesResolve() {
        List<EditorTabs.Tab> tabs = List.of(new EditorTabs.Tab("general", "General"),
                new EditorTabs.Tab("rewards", "Rewards"));
        require("rewards".equals(EditorTabs.resolve(tabs, "rewards")), "a known tab did not resolve");
        require("general".equals(EditorTabs.resolve(tabs, null)) && "general".equals(EditorTabs.resolve(tabs, "gone")),
                "an unknown tab should fall back to the first");
        require(EditorTabs.resolve(List.of(), "x") == null, "no tabs should resolve to null");

        require(ItemCatalog.inCategory(List.of("Furniture.Beds"), "Furniture")
                && ItemCatalog.inCategory(List.of("Furniture.Beds"), "furniture.")
                && ItemCatalog.inCategory(List.of("Items.Weapons"), "Items.Weapons"),
                "an item in the category was filtered out");
        require(!ItemCatalog.inCategory(List.of("FurnitureSet.Beds"), "Furniture")
                && !ItemCatalog.inCategory(List.of("Items.Weapons"), "Items.Weapon")
                && !ItemCatalog.inCategory(List.of(), "Blocks"),
                "an item outside the category passed the filter");
        require(ItemCatalog.inCategory(List.of(), "") && ItemCatalog.inCategory(List.of(), null),
                "a blank category should keep every item");
    }

    private static void requireKind(FormFields.Result<?> result, FormFields.Kind kind, String what) {
        require(!result.ok() && result.problem().kind() == kind,
                what + " should fail with " + kind + " but gave " + result);
    }

    // ----- Pool editor: odds conversion and the publish diff --------------------

    private static void oddsConverterKeepsChances() {
        LootBoxDraft draft = bibleDraft();
        OddsConverter.Result toPercent = OddsConverter.switchTo(draft, OddsConverter.EXACT_PERCENT);
        require(toPercent.ok() && toPercent.exact() && toPercent.notes().isEmpty(),
                "weights 4500/3000/1500/900/100 did not convert exactly: " + toPercent);
        require("EXACT_PERCENT".equals(draft.mode), "mode did not switch to EXACT_PERCENT");
        List<String> percents = draft.rewards.stream().map(r -> r.percent).toList();
        require(percents.equals(List.of("45", "30", "15", "9", "1")), "percents: " + percents);
        require(OddsEngine.resolveWeights(draft).stream().mapToLong(Long::longValue).sum() == OddsEngine.PERCENT_TOTAL,
                "converted percents do not resolve to exactly 100%");

        OddsConverter.Result back = OddsConverter.switchTo(draft, OddsConverter.WEIGHTED);
        require(back.ok() && back.exact(), "percent to weights was not exact: " + back);
        List<Long> weights = draft.rewards.stream().map(r -> r.weight).toList();
        require(weights.equals(List.of(45L, 30L, 15L, 9L, 1L)), "weights were not reduced by their GCD: " + weights);
        require(draft.rewards.stream().allMatch(r -> r.percent == null), "percents were left behind on weights");

        OddsConverter.Result same = OddsConverter.switchTo(draft, OddsConverter.WEIGHTED);
        require(same.ok() && same.exact() && weights.equals(draft.rewards.stream().map(r -> r.weight).toList()),
                "switching to the current mode changed the draft");

        LootBoxDraft tiny = percentDraft("99.999999", "0.000001");
        require(OddsConverter.toWeights(tiny).ok(), "a 0.000001% reward did not convert to weights");
        require(tiny.rewards.get(0).weight == 99_999_999L && tiny.rewards.get(1).weight == 1L,
                "0.000001% did not become weight 1 of 100000000: " + tiny.rewards.get(1).weight);
    }

    private static void oddsConverterRoundsAndRefuses() {
        LootBoxDraft thirds = bibleDraft();
        thirds.rewards.subList(3, 5).clear();
        thirds.rewards.forEach(r -> r.weight = 1);
        OddsConverter.Result rounded = OddsConverter.toPercent(thirds);
        require(rounded.ok() && !rounded.exact() && !rounded.notes().isEmpty(),
                "1/3 each was not reported as rounded: " + rounded);
        require(OddsEngine.resolveWeights(thirds).stream().mapToLong(Long::longValue).sum() == OddsEngine.PERCENT_TOTAL,
                "rounded thirds do not total exactly 100%");
        require(thirds.rewards.stream().map(r -> r.percent).toList().equals(List.of("33.333334", "33.333333", "33.333333")),
                "thirds: " + thirds.rewards.stream().map(r -> r.percent).toList());

        LootBoxDraft zero = bibleDraft();
        zero.rewards.get(2).weight = 0;
        OddsConverter.Result refused = OddsConverter.toPercent(zero);
        require(!refused.ok() && "WEIGHTED".equals(zero.mode) && zero.rewards.get(0).percent == null,
                "a zero weight was converted, or the draft changed: " + refused);

        LootBoxDraft rare = bibleDraft();
        rare.rewards.subList(2, 5).clear();
        rare.rewards.get(0).weight = 1;
        rare.rewards.get(1).weight = 1_000_000_000_000L;
        require(!OddsConverter.toPercent(rare).ok() && rare.rewards.get(0).percent == null,
                "a reward rarer than 0.000001% was rounded to nothing");

        LootBoxDraft broken = percentDraft("60", "30");
        require(!OddsConverter.toWeights(broken).ok() && "EXACT_PERCENT".equals(broken.mode),
                "percents summing to 90% were converted to weights");
    }

    private static void draftDiffListsChanges() {
        LootBoxDraft live = bibleDraft();
        DraftDiff.Diff none = DraftDiff.between(DraftLifecycle.freeze(live, LootBoxDraft.class), live, FORMAT);
        require(!none.changed() && !none.oddsChange() && none.lines().size() == 1,
                "an identical draft reported changes: " + none.lines());

        LootBoxDraft converted = DraftLifecycle.freeze(live, LootBoxDraft.class);
        OddsConverter.toPercent(converted);
        DraftDiff.Diff modeOnly = DraftDiff.between(converted, live, FORMAT);
        require(modeOnly.changed() && !modeOnly.oddsChange(),
                "an exact mode switch reported an odds change: " + modeOnly.lines());

        LootBoxDraft edited = DraftLifecycle.freeze(live, LootBoxDraft.class);
        edited.displayName = "Avalon Supply Crate II";
        edited.rewards.get(0).weight = 4400;
        edited.rewards.remove(4);
        edited.rewards.add(item("bonus", "Bonus", 100));
        edited.keys = new ArrayList<>(List.of("mystic:royal", "mystic:event"));
        DraftDiff.Diff diff = DraftDiff.between(edited, live, FORMAT);
        String all = String.join(" | ", diff.lines());
        require(diff.changed() && diff.oddsChange(), "edits were not reported as an odds change");
        require(all.contains("~ Name") && all.contains("Avalon Supply Crate II"), "the name change is missing: " + all);
        require(all.contains("+ &fBonus"), "the added reward is missing: " + all);
        require(all.contains("- &fGuardian Companion"), "the removed reward is missing: " + all);
        require(all.contains("Iron Sword") && all.contains("45.00%") && all.contains("44.4"),
                "the Iron Sword 45% -> 44.4% move is missing: " + all);
        require(all.contains("+ key &fmystic:event"), "the added key is missing: " + all);

        DraftDiff.Diff first = DraftDiff.between(live, null, FORMAT);
        require(first.changed() && first.oddsChange() && first.lines().get(0).contains("First revision"),
                "a never-published pool was not reported as its first revision");
    }

    /** The Rewards tab's chances come from the same weights and formatter as the player's preview. */
    private static void editorChancesMatchThePreview() {
        for (LootBoxDraft draft : List.of(bibleDraft(), percentDraft("45", "30", "15", "9.9875", "0.0125"))) {
            Map<String, String> editor = DraftDiff.chances(draft, FORMAT);
            OddsTable table = OddsEngine.table(revision(draft), "FREE_GAMEPLAY_ONLY");
            for (OddsTable.Entry entry : table.entries()) {
                String preview = FORMAT.percent(entry.weight(), table.total());
                require(preview.equals(editor.get(entry.rewardId())),
                        "editor shows " + editor.get(entry.rewardId()) + " but the preview shows " + preview
                                + " for " + entry.rewardId());
            }
        }
    }

    private static void rewardListOperations() {
        LootBoxDraft draft = bibleDraft();
        int added = RewardsTab.add(draft);
        LootBoxDraft.RewardSpec fresh = draft.rewards.get(added);
        require(added == 5 && "reward".equals(fresh.id) && "ITEM".equals(fresh.type) && fresh.item != null,
                "add did not append a blank ITEM reward: " + fresh.id);
        require(fresh.weight == 100, "a new reward should copy the rarest weight (100), got " + fresh.weight);
        require("reward_2".equals(draft.rewards.get(RewardsTab.add(draft)).id), "the second new reward is not reward_2");

        int copy = RewardsTab.duplicate(draft, 0);
        LootBoxDraft.RewardSpec dup = draft.rewards.get(copy);
        require(copy == 1 && "iron_sword_copy".equals(dup.id) && dup.name.endsWith("(copy)") && dup.weight == 4500,
                "duplicate did not insert a renamed copy after the source: " + dup.id);
        require(dup != draft.rewards.get(0) && dup.item != draft.rewards.get(0).item,
                "duplicate shares objects with its source");
        require("iron_sword_copy_2".equals(draft.rewards.get(RewardsTab.duplicate(draft, 0)).id),
                "a second copy did not get a free id");

        int last = draft.rewards.size() - 1;
        require(RewardsTab.move(draft, 0, -1) == 0, "moving the first reward up should stay put");
        require(RewardsTab.move(draft, last, 1) == last, "moving the last reward down should stay put");
        String first = draft.rewards.get(0).id;
        require(RewardsTab.move(draft, 0, 1) == 1 && first.equals(draft.rewards.get(1).id), "move down failed");

        LootBoxDraft.RewardSpec command = item("cmd", "Command", 10);
        command.command = "examplegrant {playerUuid} 1";
        command.allowedPatternId = "examplegrant-v1";
        command.commandIdempotent = true;
        RewardsTab.setType(command, "COMMAND");
        require(command.item == null && command.command != null, "switching to COMMAND kept the item or lost the command");
        RewardsTab.setType(command, "ITEM");
        require(command.command == null && command.allowedPatternId == null && !command.commandIdempotent
                && command.item != null && command.item.quantity == 1,
                "switching back to ITEM kept command fields or gave no item");

        require(RewardsTab.nextId(draft, "Gold Coins!").equals("gold_coins_"), "nextId did not clean the base id");
        require(RewardsTab.sumLine(bibleDraft()).equals("5 rewards · total weight 10000"),
                "weights sum line: " + RewardsTab.sumLine(bibleDraft()));
        String partial = RewardsTab.sumLine(percentDraft("60", "30"));
        require(partial.contains("90% assigned") && partial.contains("10% left"), "percent sum line: " + partial);
        LootBoxDraft percent = percentDraft("60", "30");
        RewardsTab.add(percent);
        require("10".equals(percent.rewards.get(2).percent), "a new percent reward should take the 10% left");
        require(RewardsTab.sumLine(percent).contains("exactly 100%"), "after adding, the pool should be exactly 100%");
    }

    // ----- Key editor -----------------------------------------------------------

    private static void keyNamesSplitAndJoin() {
        KeyText.Style royal = KeyText.parse("<#E7B84D>&lAvalon Royal Key");
        require("#E7B84D".equals(royal.color()) && royal.bold() && !royal.italic() && "Avalon Royal Key".equals(royal.text()),
                "parse: " + royal);
        require(KeyText.compose(royal).equals("<#E7B84D>&lAvalon Royal Key"), "compose did not round-trip");
        KeyText.Style plain = KeyText.parse("Plain Key");
        require(plain.color() == null && !plain.bold() && "Plain Key".equals(plain.text()), "plain: " + plain);
        require(KeyText.compose(plain.withColor("#00FF00").withItalic(true)).equals("<#00FF00>&oPlain Key"),
                "compose with colour and italic: " + KeyText.compose(plain.withColor("#00FF00").withItalic(true)));
        KeyText.Style inner = KeyText.parse("&oFancy &cRed");
        require(inner.italic() && "Fancy &cRed".equals(inner.text()), "markup inside the text was not kept: " + inner);
        require(KeyText.compose(royal.withColor(null).withBold(false)).equals("Avalon Royal Key"), "clearing style failed");
        require("#A1B2C3".equals(KeyText.color("a1b2c3")) && "#A1B2C3".equals(KeyText.color("#a1b2c3ff"))
                && KeyText.color("red") == null && KeyText.color(null) == null, "colour normalising failed");
        require(KeyText.lore(java.util.Arrays.asList("One", "", "Three", " ", "")).equals(List.of("One", "", "Three")),
                "lore trimming: " + KeyText.lore(java.util.Arrays.asList("One", "", "Three", " ", "")));
        require(KeyText.lore(java.util.Arrays.asList("", null, "")).isEmpty(), "blank lore did not become empty");
    }

    private static void keyDiffListsChanges() {
        KeyDraft published = new KeyDraft();
        published.id = "mystic:royal";
        published.baseItemId = "Ingredient_Bar_Gold";
        published.name = "<#E7B84D>&lRoyal Key";
        published.lore = new ArrayList<>(List.of("Opens the Royal crate"));
        published.uses = 1;
        KeyDraft same = DraftLifecycle.freeze(published, KeyDraft.class);
        require(!KeyDiff.changed(same, published) && KeyDiff.lines(same, published).size() == 1,
                "an identical key reported changes: " + KeyDiff.lines(same, published));
        KeyDraft edited = DraftLifecycle.freeze(published, KeyDraft.class);
        edited.name = "<#FF0000>&lRoyal Key";
        edited.uses = 3;
        edited.expiresAfterDays = 7;
        edited.lore.add("Second line");
        String all = String.join(" | ", KeyDiff.lines(edited, published));
        require(KeyDiff.changed(edited, published), "edits were not reported");
        require(all.contains("Name colour or style") && all.contains("1 use") && all.contains("3 uses")
                && all.contains("expires after 7 days") && all.contains("1 line(s)") && all.contains("2 line(s)"),
                "key diff is missing a change: " + all);
        require(KeyDiff.lines(edited, null).get(0).contains("New key"), "an unpublished key was not reported as new");
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
