package org.hyzionstudios.mysticessentials.modules.lootbox.ui.editor;

import static org.hyzionstudios.mysticessentials.platform.ui.MysticPage.uiText;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.hyzionstudios.mysticessentials.core.message.MysticText;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.OddsEngine;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.OddsFormatter;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.DraftLifecycle;
import org.hyzionstudios.mysticessentials.modules.lootbox.validate.DraftValidator;
import org.hyzionstudios.mysticessentials.platform.ui.ItemCatalog;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.LocalizableString;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;

/**
 * The pool editor's Rewards tab (P2-E): the reward list with live chances on the
 * left, and the selected reward's fields on the right. The fields belong to the
 * selected reward; the page applies them to it before anything changes the
 * selection, and refuses to change it while one of them does not parse, so
 * typed text is never applied to the wrong reward or silently dropped.
 *
 * <p>The list operations ({@link #add}, {@link #duplicate}, {@link #move},
 * {@link #setType}) and {@link #sumLine} are engine-free; {@code verifyLootBox}
 * checks them.</p>
 */
public final class RewardsTab {

    static final String ROW_UI = "MysticEssentials/LootBoxEditorRewardRow.ui";

    static final FormFields.Field REWARD_ID = FormFields.Field.text("rewardId", "#RewardIdInput", "Reward id");
    static final FormFields.Field REWARD_NAME = FormFields.Field.text("rewardName", "#RewardNameInput", "Reward name");
    static final FormFields.Field RARITY = FormFields.Field.text("rarity", "#RarityInput", "Rarity");
    static final FormFields.Field ODDS = FormFields.Field.text("odds", "#OddsInput", "Weight or percent");
    static final FormFields.Field QUANTITY = FormFields.Field.text("quantity", "#QuantityInput", "Quantity");
    static final FormFields.Field AMOUNT = FormFields.Field.text("amount", "#AmountInput", "Amount");
    static final FormFields.Field NODE = FormFields.Field.text("node", "#NodeInput", "Permission node");
    static final FormFields.Field DURATION = FormFields.Field.text("duration", "#DurationInput", "Duration");
    static final FormFields.Field COMMAND = FormFields.Field.text("command", "#CommandInput", "Command template");
    static final List<FormFields.Field> FIELDS = List.of(REWARD_ID, REWARD_NAME, RARITY, ODDS, QUANTITY, AMOUNT, NODE,
            DURATION, COMMAND);

    static final String ITEM = "ITEM";
    static final String ECONOMY = "ECONOMY";
    static final String PERMISSION = "PERMISSION";
    static final String COMMAND_TYPE = "COMMAND";

    private static final Pattern REWARD_ID_PATTERN = Pattern.compile("[a-z0-9_]{1,48}");
    /** The validator's node pattern: a reward may grant a wildcard. */
    private static final Pattern NODE_PATTERN = Pattern.compile("[A-Za-z0-9_.\\-*]{1,128}");
    private static final int MAX_NAME = 96;
    private static final int MAX_RARITY = 32;
    private static final int MAX_COMMAND = 256;
    private static final int MAX_QUANTITY = 9999;
    private static final long MAX_WEIGHT = 1_000_000_000_000L;
    /** Ten years; longer is almost certainly a typo for "permanent" (0). */
    private static final long MAX_DURATION = 315_360_000L;
    private static final BigDecimal MIN_PERCENT = new BigDecimal("0.000001");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal MIN_AMOUNT = new BigDecimal("0.01");
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000");

    /**
     * What the tab needs from the page.
     *
     * @param liveIds    reward ids in the active revision: those keep their id
     * @param mayCommand whether the player holds {@code rewards.command}
     */
    record Context(EditorSession<LootBoxDraft> session, Set<String> liveIds, boolean mayCommand,
            DraftValidator.Environment environment, OddsFormatter format) {
    }

    private RewardsTab() {
    }

    // ----- Selection ----------------------------------------------------------

    /** The selected reward's index, clamped; selects the first reward when none is. {@code -1} when there are none. */
    static int selected(EditorSession<LootBoxDraft> session) {
        int count = session.working().rewards.size();
        int row = session.selectedRow();
        if (count == 0) {
            return -1;
        }
        if (row < 0 || row >= count) {
            row = Math.max(0, Math.min(row, count - 1));
            session.selectRow(row);
        }
        return row;
    }

    /** Whether {@code reward} is a COMMAND reward this player may not touch. */
    static boolean locked(LootBoxDraft.RewardSpec reward, boolean mayCommand) {
        return COMMAND_TYPE.equalsIgnoreCase(reward.type) && !mayCommand;
    }

    static void forgetInputs(EditorSession<?> session) {
        FIELDS.forEach(field -> session.forgetInput(field.key()));
    }

    // ----- Build --------------------------------------------------------------

    /**
     * Fills the list and the selected reward's fields. Runs on every tab, because
     * every event carries these fields and they must hold the reward's real values.
     */
    static void build(UICommandBuilder cmd, UIEventBuilder event, Context ctx, Function<String, EventData> events) {
        EditorSession<LootBoxDraft> session = ctx.session();
        LootBoxDraft draft = session.working();
        int selected = selected(session);
        boolean percent = OddsConverter.EXACT_PERCENT.equalsIgnoreCase(draft.mode);
        Map<String, String> chances = DraftDiff.chances(draft, ctx.format());
        List<Long> weights = weights(draft);
        long total = weights == null ? 0 : weights.stream().mapToLong(Long::longValue).sum();

        cmd.set("#RewardsSum.TextSpans", uiText("#RewardsSum.TextSpans", sumLine(draft)));
        for (int i = 0; i < draft.rewards.size(); i++) {
            LootBoxDraft.RewardSpec reward = draft.rewards.get(i);
            String row = "#RewardList[" + i + "]";
            cmd.append("#RewardList", ROW_UI);
            cmd.set(row + " #Selected.Visible", i == selected);
            String icon = itemIcon(reward);
            if (icon != null) {
                cmd.set(row + " #Icon.ItemId", icon);
            }
            cmd.set(row + " #Name.TextSpans", uiText(row + " #Name.TextSpans",
                    MysticText.stripMarkup(blank(reward.name, reward.id))));
            cmd.set(row + " #Meta.TextSpans", uiText(row + " #Meta.TextSpans", meta(reward)));
            String chance = chances.get(reward.id);
            cmd.set(row + " #Odds.TextSpans", uiText(row + " #Odds.TextSpans", chance == null ? "&8-" : chance));
            event.addEventBinding(CustomUIEventBindingType.Activating, row,
                    events.apply("selectReward").put("row", Integer.toString(i)));
        }

        boolean any = selected >= 0;
        LootBoxDraft.RewardSpec reward = any ? draft.rewards.get(selected) : null;
        cmd.set("#RewardEmpty.Visible", !any);
        cmd.set("#RewardFields.Visible", any);
        cmd.set("#DuplicateRewardButton.Visible", any && !locked(reward, ctx.mayCommand()));
        cmd.set("#MoveUpButton.Visible", any && selected > 0);
        cmd.set("#MoveDownButton.Visible", any && selected < draft.rewards.size() - 1);
        bind(event, events, "#AddRewardButton", "addReward");
        bind(event, events, "#DuplicateRewardButton", "duplicateReward");
        bind(event, events, "#MoveUpButton", "moveRewardUp");
        bind(event, events, "#MoveDownButton", "moveRewardDown");

        // Every field always gets a value, so an event never carries a stale one.
        String type = reward == null ? ITEM : blank(reward.type, ITEM).toUpperCase(Locale.ROOT);
        boolean editableId = reward != null && !ctx.liveIds().contains(reward.id);
        FormFields.fill(cmd, REWARD_ID, session, reward == null ? "" : reward.id);
        FormFields.fill(cmd, REWARD_NAME, session, reward == null ? "" : reward.name);
        FormFields.fill(cmd, RARITY, session, reward == null ? "" : reward.rarity);
        FormFields.fill(cmd, ODDS, session, reward == null ? ""
                : percent ? blank(reward.percent, "") : Long.toString(reward.weight));
        FormFields.fill(cmd, QUANTITY, session, reward == null || reward.item == null ? "1"
                : Integer.toString(reward.item.quantity));
        FormFields.fill(cmd, AMOUNT, session, reward == null || reward.amount <= 0 ? ""
                : BigDecimal.valueOf(reward.amount).stripTrailingZeros().toPlainString());
        FormFields.fill(cmd, NODE, session, reward == null ? "" : reward.node);
        FormFields.fill(cmd, DURATION, session, reward == null ? "0" : Long.toString(reward.durationSeconds));
        FormFields.fill(cmd, COMMAND, session, reward == null ? "" : reward.command);
        if (reward == null) {
            return;
        }

        cmd.set("#RewardIdInput.Visible", editableId);
        cmd.set("#RewardIdLabel.Visible", !editableId);
        cmd.set("#RewardIdLabel.TextSpans", uiText("#RewardIdLabel.TextSpans", reward.id));
        cmd.set("#OddsFieldLabel.TextSpans", uiText("#OddsFieldLabel.TextSpans", percent ? "PERCENT" : "WEIGHT"));

        List<DropdownEntryInfo> types = new ArrayList<>();
        types.add(entry("Item", ITEM));
        types.add(entry("Economy deposit", ECONOMY));
        types.add(entry("Permission node", PERMISSION));
        if (ctx.mayCommand() || COMMAND_TYPE.equals(type)) {
            types.add(entry("Console command", COMMAND_TYPE));
        }
        cmd.set("#RewardTypeInput.Entries", types);
        cmd.set("#RewardTypeInput.Value", type);
        event.addEventBinding(CustomUIEventBindingType.ValueChanged, "#RewardTypeInput",
                events.apply("rewardType").append("@rewardType", "#RewardTypeInput.Value"));

        String chance = chances.get(reward.id);
        int index = selected;
        cmd.set("#RewardChance.TextSpans", uiText("#RewardChance.TextSpans", chance == null
                ? "&cChance can't be computed yet: " + oddsProblem(draft)
                : "Chance now: " + chance + " &8· &7" + ctx.format().oneIn(weights.get(index), total)));

        cmd.set("#ItemSection.Visible", ITEM.equals(type));
        cmd.set("#EconomySection.Visible", ECONOMY.equals(type));
        cmd.set("#PermissionSection.Visible", PERMISSION.equals(type));
        cmd.set("#CommandSection.Visible", COMMAND_TYPE.equals(type));
        String itemId = reward.item == null ? "" : blank(reward.item.itemId, "");
        if (itemIcon(reward) != null) {
            cmd.set("#RewardItemSlot.ItemId", itemId);
        }
        cmd.set("#RewardItemName.TextSpans", uiText("#RewardItemName.TextSpans", itemId.isBlank()
                ? "&8No item chosen" : ItemCatalog.exists(itemId) ? itemId : "&c" + itemId + " is not an item on this server"));
        bind(event, events, "#RewardItemButton", "rewardItem");
        cmd.set("#EconomyNote.TextSpans", uiText("#EconomyNote.TextSpans", ctx.environment().economyAvailable()
                ? "Deposited through the server economy."
                : "&eNo economy plugin is installed, so this reward can't be published."));
        cmd.set("#PermissionNote.TextSpans", uiText("#PermissionNote.TextSpans",
                ctx.environment().permissionGrantAvailable() ? "Granted through LuckPerms; 0 seconds is permanent."
                        : "&eLuckPerms isn't installed, so this reward can't be published."));

        List<DropdownEntryInfo> patterns = new ArrayList<>();
        String pattern = blank(reward.allowedPatternId, "");
        if (pattern.isBlank() || !ctx.environment().commandPatterns().containsKey(pattern)) {
            patterns.add(entry(pattern.isBlank() ? "Choose a pattern" : pattern + " (not in the allowlist)", pattern));
        }
        ctx.environment().commandPatterns().keySet().forEach(id -> patterns.add(entry(id, id)));
        cmd.set("#PatternInput.Entries", patterns);
        cmd.set("#PatternInput.Value", pattern);
        event.addEventBinding(CustomUIEventBindingType.ValueChanged, "#PatternInput",
                events.apply("rewardPattern").append("@pattern", "#PatternInput.Value"));
        cmd.set("#IdempotentOnButton.Visible", !reward.commandIdempotent);
        cmd.set("#IdempotentOffButton.Visible", reward.commandIdempotent);
        bind(event, events, "#IdempotentOnButton", "idempotentOn");
        bind(event, events, "#IdempotentOffButton", "idempotentOff");
        cmd.set("#CommandNote.TextSpans", uiText("#CommandNote.TextSpans",
                "Placeholders: {playerUuid} {playerName} {quantity} {openingId}. It must match the pattern."));

        String lock = locked(reward, ctx.mayCommand())
                ? "You need mysticessentials.lootbox.rewards.command to change this reward."
                : !editableId ? "Published rewards keep their id: receipts refer to it." : "";
        cmd.set("#RewardLock.Visible", !lock.isEmpty());
        cmd.set("#RewardLock.TextSpans", uiText("#RewardLock.TextSpans", lock));

        cmd.set("#AnnounceOnButton.Visible", !reward.announce);
        cmd.set("#AnnounceOffButton.Visible", reward.announce);
        bind(event, events, "#AnnounceOnButton", "announceOn");
        bind(event, events, "#AnnounceOffButton", "announceOff");
        bind(event, events, "#DeleteRewardButton", "deleteReward");
    }

    private static void bind(UIEventBuilder event, Function<String, EventData> events, String button, String action) {
        event.addEventBinding(CustomUIEventBindingType.Activating, button, events.apply(action));
    }

    // ----- Inputs -------------------------------------------------------------

    /**
     * Copies the selected reward's fields that parse into it and forgets their
     * text; text that does not parse stays as typed. Fields for other reward
     * types, and every field of a locked COMMAND reward, are dropped unapplied.
     *
     * @return whether anything changed
     */
    static boolean apply(Context ctx) {
        EditorSession<LootBoxDraft> session = ctx.session();
        LootBoxDraft draft = session.working();
        int row = session.selectedRow();
        if (row < 0 || row >= draft.rewards.size()) {
            forgetInputs(session);
            return false;
        }
        LootBoxDraft.RewardSpec reward = draft.rewards.get(row);
        if (locked(reward, ctx.mayCommand())) {
            forgetInputs(session);
            return false;
        }
        boolean changed = false;
        String type = blank(reward.type, ITEM).toUpperCase(Locale.ROOT);

        String id = session.input(REWARD_ID.key());
        if (id != null) {
            if (ctx.liveIds().contains(reward.id)) {
                session.forgetInput(REWARD_ID.key());
            } else if (idProblem(draft, row, id) == null) {
                changed |= set(!id.trim().equals(reward.id), () -> reward.id = id.trim());
                session.forgetInput(REWARD_ID.key());
            }
        }
        changed |= text(session, REWARD_NAME, FormFields.nonBlank(REWARD_NAME, session.input(REWARD_NAME.key()), MAX_NAME),
                value -> !value.equals(reward.name), value -> reward.name = value);
        String rarity = session.input(RARITY.key());
        if (rarity != null && rarity.trim().length() <= MAX_RARITY) {
            String value = rarity.trim();
            changed |= set(!value.equals(blank(reward.rarity, "")), () -> reward.rarity = value);
            session.forgetInput(RARITY.key());
        }
        String odds = session.input(ODDS.key());
        if (odds != null) {
            if (OddsConverter.EXACT_PERCENT.equalsIgnoreCase(draft.mode)) {
                FormFields.Result<BigDecimal> parsed = FormFields.decimal(ODDS, odds, 6, MIN_PERCENT, HUNDRED);
                if (parsed.ok()) {
                    String value = parsed.value().toPlainString();
                    changed |= set(!value.equals(reward.percent), () -> reward.percent = value);
                    session.forgetInput(ODDS.key());
                }
            } else {
                FormFields.Result<Long> parsed = FormFields.longInRange(ODDS, odds, 1, MAX_WEIGHT);
                if (parsed.ok()) {
                    changed |= set(parsed.value() != reward.weight, () -> reward.weight = parsed.value());
                    session.forgetInput(ODDS.key());
                }
            }
        }

        if (ITEM.equals(type)) {
            String quantity = session.input(QUANTITY.key());
            if (quantity != null) {
                FormFields.Result<Integer> parsed = FormFields.intInRange(QUANTITY, quantity, 1, MAX_QUANTITY);
                if (parsed.ok()) {
                    if (reward.item == null) {
                        reward.item = new LootBoxDraft.ItemSpec();
                    }
                    changed |= set(parsed.value() != reward.item.quantity, () -> reward.item.quantity = parsed.value());
                    session.forgetInput(QUANTITY.key());
                }
            }
        } else {
            session.forgetInput(QUANTITY.key());
        }
        if (ECONOMY.equals(type)) {
            String amount = session.input(AMOUNT.key());
            if (amount != null) {
                FormFields.Result<BigDecimal> parsed = FormFields.decimal(AMOUNT, amount, 2, MIN_AMOUNT, MAX_AMOUNT);
                if (parsed.ok()) {
                    double value = parsed.value().doubleValue();
                    changed |= set(value != reward.amount, () -> reward.amount = value);
                    session.forgetInput(AMOUNT.key());
                }
            }
        } else {
            session.forgetInput(AMOUNT.key());
        }
        if (PERMISSION.equals(type)) {
            changed |= text(session, NODE, FormFields.id(NODE, session.input(NODE.key()), NODE_PATTERN,
                    "letters, digits, _ . - and *"), value -> !value.equals(reward.node), value -> reward.node = value);
            String duration = session.input(DURATION.key());
            if (duration != null) {
                FormFields.Result<Long> parsed = FormFields.longInRange(DURATION, duration, 0, MAX_DURATION);
                if (parsed.ok()) {
                    changed |= set(parsed.value() != reward.durationSeconds, () -> reward.durationSeconds = parsed.value());
                    session.forgetInput(DURATION.key());
                }
            }
        } else {
            session.forgetInput(NODE.key());
            session.forgetInput(DURATION.key());
        }
        if (COMMAND_TYPE.equals(type)) {
            changed |= text(session, COMMAND, FormFields.nonBlank(COMMAND, session.input(COMMAND.key()), MAX_COMMAND),
                    value -> !value.equals(reward.command), value -> reward.command = value);
        } else {
            session.forgetInput(COMMAND.key());
        }
        return changed;
    }

    /** Applies a parsed text value when its field was submitted and parses. */
    private static boolean text(EditorSession<?> session, FormFields.Field field, FormFields.Result<String> parsed,
            java.util.function.Predicate<String> differs, java.util.function.Consumer<String> apply) {
        if (session.input(field.key()) == null || !parsed.ok()) {
            return false;
        }
        boolean changed = differs.test(parsed.value());
        if (changed) {
            apply.accept(parsed.value());
        }
        session.forgetInput(field.key());
        return changed;
    }

    private static boolean set(boolean differs, Runnable apply) {
        if (differs) {
            apply.run();
        }
        return differs;
    }

    /** Problems with the selected reward's typed text that was not applied. */
    static List<FormFields.Problem> unparsed(Context ctx) {
        EditorSession<LootBoxDraft> session = ctx.session();
        LootBoxDraft draft = session.working();
        int row = session.selectedRow();
        List<FormFields.Problem> problems = new ArrayList<>();
        if (row < 0 || row >= draft.rewards.size()) {
            return problems;
        }
        String id = session.input(REWARD_ID.key());
        if (id != null) {
            String why = idProblem(draft, row, id);
            if (why != null) {
                problems.add(new FormFields.Problem(REWARD_ID.key(), FormFields.Kind.BAD_FORMAT, why));
            }
        }
        check(session, REWARD_NAME, raw -> FormFields.nonBlank(REWARD_NAME, raw, MAX_NAME), problems);
        String rarity = session.input(RARITY.key());
        if (rarity != null && rarity.trim().length() > MAX_RARITY) {
            problems.add(new FormFields.Problem(RARITY.key(), FormFields.Kind.TOO_LONG,
                    "Rarity is longer than " + MAX_RARITY + " characters."));
        }
        boolean percent = OddsConverter.EXACT_PERCENT.equalsIgnoreCase(draft.mode);
        check(session, ODDS, raw -> percent ? FormFields.decimal(ODDS, raw, 6, MIN_PERCENT, HUNDRED)
                : FormFields.longInRange(ODDS, raw, 1, MAX_WEIGHT), problems);
        check(session, QUANTITY, raw -> FormFields.intInRange(QUANTITY, raw, 1, MAX_QUANTITY), problems);
        check(session, AMOUNT, raw -> FormFields.decimal(AMOUNT, raw, 2, MIN_AMOUNT, MAX_AMOUNT), problems);
        check(session, NODE, raw -> FormFields.id(NODE, raw, NODE_PATTERN, "letters, digits, _ . - and *"), problems);
        check(session, DURATION, raw -> FormFields.longInRange(DURATION, raw, 0, MAX_DURATION), problems);
        check(session, COMMAND, raw -> FormFields.nonBlank(COMMAND, raw, MAX_COMMAND), problems);
        return problems;
    }

    private static void check(EditorSession<?> session, FormFields.Field field,
            Function<String, FormFields.Result<?>> parse, List<FormFields.Problem> problems) {
        String raw = session.input(field.key());
        if (raw != null) {
            parse.apply(raw).collect(problems);
        }
    }

    private static String idProblem(LootBoxDraft draft, int row, String raw) {
        String id = raw == null ? "" : raw.trim();
        if (!REWARD_ID_PATTERN.matcher(id).matches()) {
            return "Reward id must be 1-48 characters: a-z, 0-9 and _.";
        }
        for (int i = 0; i < draft.rewards.size(); i++) {
            if (i != row && id.equals(draft.rewards.get(i).id)) {
                return "Reward id '" + id + "' is already used by another reward.";
            }
        }
        return null;
    }

    // ----- List operations (engine-free) ---------------------------------------

    /**
     * Appends a new ITEM reward with the next free id. Its weight copies the
     * rarest reward's (so it never dominates by accident); in percent mode it
     * takes whatever is left of 100%.
     *
     * @return the new reward's index
     */
    public static int add(LootBoxDraft draft) {
        LootBoxDraft.RewardSpec reward = new LootBoxDraft.RewardSpec();
        reward.id = nextId(draft, "reward");
        reward.name = "New reward";
        reward.type = ITEM;
        reward.item = new LootBoxDraft.ItemSpec();
        reward.item.itemId = "";
        reward.item.quantity = 1;
        if (OddsConverter.EXACT_PERCENT.equalsIgnoreCase(draft.mode)) {
            BigDecimal left = HUNDRED.subtract(percentSum(draft));
            reward.percent = left.signum() > 0 ? plain(left) : null;
        } else {
            reward.weight = draft.rewards.stream().mapToLong(r -> r.weight).filter(w -> w > 0).min().orElse(1);
        }
        draft.rewards.add(reward);
        return draft.rewards.size() - 1;
    }

    /** Copies reward {@code index} just after itself, under a fresh id and a "(copy)" name. @return the copy's index */
    public static int duplicate(LootBoxDraft draft, int index) {
        LootBoxDraft.RewardSpec source = draft.rewards.get(index);
        LootBoxDraft.RewardSpec copy = DraftLifecycle.freeze(source, LootBoxDraft.RewardSpec.class);
        copy.id = nextId(draft, source.id + "_copy");
        copy.name = blank(source.name, source.id) + " (copy)";
        draft.rewards.add(index + 1, copy);
        return index + 1;
    }

    /** Moves reward {@code index} by {@code delta} places. @return its new index, unchanged at either end */
    public static int move(LootBoxDraft draft, int index, int delta) {
        int target = index + delta;
        if (index < 0 || index >= draft.rewards.size() || target < 0 || target >= draft.rewards.size()) {
            return index;
        }
        LootBoxDraft.RewardSpec reward = draft.rewards.remove(index);
        draft.rewards.add(target, reward);
        return target;
    }

    /** Sets {@code type} and clears the other types' fields, so a revision never carries a stale command or node. */
    public static void setType(LootBoxDraft.RewardSpec reward, String type) {
        String next = type == null ? ITEM : type.toUpperCase(Locale.ROOT);
        reward.type = next;
        if (ITEM.equals(next)) {
            if (reward.item == null) {
                reward.item = new LootBoxDraft.ItemSpec();
                reward.item.itemId = "";
                reward.item.quantity = 1;
            }
        } else {
            reward.item = null;
        }
        if (!ECONOMY.equals(next)) {
            reward.amount = 0;
        }
        if (!PERMISSION.equals(next)) {
            reward.node = null;
            reward.durationSeconds = 0;
        }
        if (!COMMAND_TYPE.equals(next)) {
            reward.command = null;
            reward.allowedPatternId = null;
            reward.commandIdempotent = false;
        }
    }

    /** {@code base}, or {@code base_2}, {@code base_3}, ... whichever is free first; always a valid reward id. */
    public static String nextId(LootBoxDraft draft, String base) {
        String clean = base == null ? "reward" : base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        if (clean.length() > 40) {
            clean = clean.substring(0, 40);
        }
        if (clean.isEmpty()) {
            clean = "reward";
        }
        Set<String> used = new HashSet<>();
        draft.rewards.forEach(r -> used.add(r.id));
        if (!used.contains(clean)) {
            return clean;
        }
        for (int n = 2; ; n++) {
            String candidate = clean + "_" + n;
            if (!used.contains(candidate)) {
                return candidate;
            }
        }
    }

    /** "5 rewards · total weight 10000", or in percent mode how much of 100% is assigned. */
    public static String sumLine(LootBoxDraft draft) {
        int count = draft.rewards.size();
        String head = count + (count == 1 ? " reward" : " rewards");
        if (!OddsConverter.EXACT_PERCENT.equalsIgnoreCase(draft.mode)) {
            long total = 0;
            for (LootBoxDraft.RewardSpec reward : draft.rewards) {
                total += Math.max(0, reward.weight);
            }
            return head + " · total weight " + total;
        }
        BigDecimal sum = percentSum(draft);
        int compare = sum.compareTo(HUNDRED);
        return head + " · " + plain(sum) + "% assigned" + (compare == 0 ? " &a(exactly 100%)"
                : compare < 0 ? " &e(" + plain(HUNDRED.subtract(sum)) + "% left)"
                : " &c(" + plain(sum.subtract(HUNDRED)) + "% too much)");
    }

    private static BigDecimal percentSum(LootBoxDraft draft) {
        BigDecimal sum = BigDecimal.ZERO;
        for (LootBoxDraft.RewardSpec reward : draft.rewards) {
            try {
                if (reward.percent != null && !reward.percent.isBlank()) {
                    sum = sum.add(new BigDecimal(reward.percent.trim()));
                }
            } catch (NumberFormatException ignored) {
                // An unreadable percent is the validator's to report.
            }
        }
        return sum;
    }

    // ----- Helpers ------------------------------------------------------------

    private static List<Long> weights(LootBoxDraft draft) {
        try {
            return OddsEngine.resolveWeights(draft);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String oddsProblem(LootBoxDraft draft) {
        try {
            OddsEngine.resolveWeights(draft);
            return "";
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    private static String itemIcon(LootBoxDraft.RewardSpec reward) {
        return reward.item != null && reward.item.itemId != null && !reward.item.itemId.isBlank()
                && ItemCatalog.exists(reward.item.itemId) ? reward.item.itemId : null;
    }

    private static String meta(LootBoxDraft.RewardSpec reward) {
        String type = blank(reward.type, "?").toUpperCase(Locale.ROOT);
        String detail = switch (type) {
            case ITEM -> reward.item == null || blank(reward.item.itemId, "").isBlank() ? "no item"
                    : reward.item.itemId + " x" + reward.item.quantity;
            case ECONOMY -> BigDecimal.valueOf(reward.amount).stripTrailingZeros().toPlainString();
            case PERMISSION -> blank(reward.node, "no node")
                    + (reward.durationSeconds > 0 ? " for " + reward.durationSeconds + "s" : "");
            case COMMAND_TYPE -> blank(reward.allowedPatternId, "no pattern");
            default -> "";
        };
        return type + (reward.rarity == null || reward.rarity.isBlank() ? "" : " · " + reward.rarity)
                + (detail.isEmpty() ? "" : " · " + detail) + (reward.announce ? " · announced" : "");
    }

    private static DropdownEntryInfo entry(String label, String value) {
        return new DropdownEntryInfo(LocalizableString.fromString(label), value);
    }

    private static String blank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String plain(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0) : stripped).toPlainString();
    }
}
