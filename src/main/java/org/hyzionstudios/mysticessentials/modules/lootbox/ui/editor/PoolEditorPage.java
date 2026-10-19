package org.hyzionstudios.mysticessentials.modules.lootbox.ui.editor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.regex.Pattern;

import org.hyzionstudios.mysticessentials.api.Permissions;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.message.MysticText;
import org.hyzionstudios.mysticessentials.modules.lootbox.LootBoxModule;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.KeyDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxRevision;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.OddsEngine;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.OddsFormatter;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.DraftLifecycle;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.DraftStore;
import org.hyzionstudios.mysticessentials.modules.lootbox.ui.LootBoxManagerPage;
import org.hyzionstudios.mysticessentials.platform.ui.ItemCatalog;
import org.hyzionstudios.mysticessentials.platform.ui.MysticPage;

import com.google.gson.JsonObject;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.LocalizableString;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * The pool editor (P2-D): one working copy of a pool draft, edited across the
 * tabs General, Rewards, Keys & Rules and Publish, and written to the draft file
 * only by Save (or by Publish, which saves first).
 *
 * <p>Everything that must outlive a rebuild lives in the {@link EditorSession}:
 * the working copy, the loaded hash, the tab, and the player's unsaved text.
 * Every event carries the form's fields, so typing then switching tabs loses
 * nothing; text that parses is applied to the working copy, and text that does
 * not stays as typed and is listed as a problem. Saves are optimistic (P2-D2):
 * if the file changed since it was loaded, the save is refused and the footer
 * offers Load From Disk or Overwrite.</p>
 *
 * <p>Every press checks that the session is still this player's current one,
 * and checks {@code edit} again; Publish also checks {@code publish}. Handlers
 * reopen the page synchronously, and file work completes off the world thread
 * and then reopens the editor with a notice.</p>
 */
public final class PoolEditorPage extends MysticPage {

    static final String EDITOR_UI = "MysticEssentials/LootBoxEditor.ui";
    static final String TOGGLE_ROW_UI = "MysticEssentials/LootBoxEditorToggleRow.ui";

    static final String TAB_GENERAL = "general";
    static final String TAB_REWARDS = "rewards";
    static final String TAB_RULES = "rules";
    static final String TAB_PUBLISH = "publish";
    static final List<EditorTabs.Tab> TABS = List.of(
            new EditorTabs.Tab(TAB_GENERAL, "General"),
            new EditorTabs.Tab(TAB_REWARDS, "Rewards"),
            new EditorTabs.Tab(TAB_RULES, "Keys & Rules"),
            new EditorTabs.Tab(TAB_PUBLISH, "Publish"));

    static final FormFields.Field NAME = FormFields.Field.text("name", "#NameInput", "Display name");
    static final FormFields.Field DESCRIPTION = FormFields.Field.text("description", "#DescriptionInput", "Description");
    static final FormFields.Field COOLDOWN = FormFields.Field.text("cooldown", "#CooldownInput", "Cooldown");
    static final FormFields.Field PERMISSION = FormFields.Field.text("permission", "#PermissionInput", "Permission");
    static final FormFields INPUTS = FormFields.of(NAME, DESCRIPTION, COOLDOWN, PERMISSION);

    private static final int MAX_NAME = 96;
    private static final int MAX_DESCRIPTION = 200;
    /** A year; longer cooldowns are almost certainly a typo. */
    private static final long MAX_COOLDOWN = 31_536_000L;
    private static final Pattern NODE = Pattern.compile("[A-Za-z0-9_.\\-]{1,128}");

    /**
     * What the page shows besides the session.
     *
     * @param notice   a one-line message, MysticText markup; {@code null} for none
     * @param conflict the last save found the file changed on disk
     */
    public record View(String notice, boolean conflict) {

        public static View of(String notice) {
            return new View(notice, false);
        }
    }

    private final LootBoxModule module;
    private final EditorSession<LootBoxDraft> session;
    private final View view;

    public PoolEditorPage(MysticCore core, PlayerRef player, LootBoxModule module, EditorSession<LootBoxDraft> session,
            View view) {
        super(core, player, CustomPageLifetime.CanDismiss);
        this.module = module;
        this.session = session;
        this.view = view == null ? View.of(null) : view;
    }

    // ----- Build --------------------------------------------------------------

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event, Store<EntityStore> store) {
        cmd.append(EDITOR_UI);
        LootBoxDraft draft = session.working();
        String id = session.draftId();
        String tab = EditorTabs.resolve(TABS, session.tab());
        List<String> problems = problems(draft);

        cmd.set("#HeaderTitle.TextSpans", uiText("#HeaderTitle.TextSpans",
                "Edit " + MysticText.stripMarkup(blankTo(draft.displayName, id))));
        cmd.set("#HeaderInfo.TextSpans", uiText("#HeaderInfo.TextSpans", headerInfo(id, draft, problems)));
        EditorTabs.render(cmd, event, "#Tabs", TABS, tab, t -> INPUTS.event("tab").put("tab", t.id()));

        String notice = view.notice();
        cmd.set("#Notice.Visible", notice != null && !notice.isBlank());
        if (notice != null && !notice.isBlank()) {
            cmd.set("#Notice.TextSpans", uiText("#Notice.TextSpans", notice));
        }

        cmd.set("#GeneralPanel.Visible", TAB_GENERAL.equals(tab));
        cmd.set("#RewardsPanel.Visible", TAB_REWARDS.equals(tab));
        cmd.set("#RulesPanel.Visible", TAB_RULES.equals(tab));
        cmd.set("#PublishPanel.Visible", TAB_PUBLISH.equals(tab));
        // The form's inputs exist on every tab (hidden ones too), so every event can carry them.
        buildGeneral(cmd, event, draft);
        buildRules(cmd, event, draft);
        if (TAB_REWARDS.equals(tab)) {
            buildRewards(cmd, draft);
        }
        if (TAB_PUBLISH.equals(tab)) {
            buildPublish(cmd, draft, problems);
        }

        cmd.set("#ReloadDiskButton.Visible", view.conflict());
        cmd.set("#OverwriteButton.Visible", view.conflict());
        cmd.set("#RevertButton.Visible", !view.conflict() && session.dirty());
        cmd.set("#PublishButton.Visible", player.hasPermission(Permissions.LOOTBOX_PUBLISH));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#SaveButton", INPUTS.event("save"));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#RevertButton", INPUTS.event("revert"));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#ReloadDiskButton", INPUTS.event("reloadDisk"));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#OverwriteButton", INPUTS.event("overwrite"));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#PublishButton", INPUTS.event("publish"));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton", INPUTS.event("close"));
    }

    private void buildGeneral(UICommandBuilder cmd, UIEventBuilder event, LootBoxDraft draft) {
        FormFields.fill(cmd, NAME, session, draft.displayName);
        FormFields.fill(cmd, DESCRIPTION, session, draft.description);
        cmd.set("#NamePreview.TextSpans", uiText("#NamePreview.TextSpans", blankTo(draft.displayName, "&8(no name)")));
        boolean hasIcon = draft.icon != null && !draft.icon.isBlank() && ItemCatalog.exists(draft.icon);
        if (hasIcon) {
            cmd.set("#IconSlot.ItemId", draft.icon);
        }
        cmd.set("#IconName.TextSpans", uiText("#IconName.TextSpans", hasIcon ? draft.icon
                : draft.icon == null || draft.icon.isBlank() ? "&8None: the catalog shows the first item reward"
                : "&c" + draft.icon + " is not an item on this server"));
        cmd.set("#IconClearButton.Visible", draft.icon != null && !draft.icon.isBlank());
        event.addEventBinding(CustomUIEventBindingType.Activating, "#IconButton", INPUTS.event("icon"));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#IconClearButton", INPUTS.event("iconClear"));

        boolean percent = OddsConverter.EXACT_PERCENT.equalsIgnoreCase(draft.mode);
        cmd.set("#ModeInput.Entries", List.of(
                new DropdownEntryInfo(LocalizableString.fromString("Weights (relative)"), OddsConverter.WEIGHTED),
                new DropdownEntryInfo(LocalizableString.fromString("Exact percentages"), OddsConverter.EXACT_PERCENT)));
        cmd.set("#ModeInput.Value", percent ? OddsConverter.EXACT_PERCENT : OddsConverter.WEIGHTED);
        cmd.set("#ModeHint.TextSpans", uiText("#ModeHint.TextSpans", percent
                ? "Each reward's percent; together exactly 100. Switching converts the numbers."
                : "Chance = a reward's weight ÷ the total weight. Switching converts the numbers."));
        event.addEventBinding(CustomUIEventBindingType.ValueChanged, "#ModeInput",
                INPUTS.event("mode").append("@mode", "#ModeInput.Value"));
    }

    private void buildRules(UICommandBuilder cmd, UIEventBuilder event, LootBoxDraft draft) {
        FormFields.fill(cmd, COOLDOWN, session, Long.toString(Math.max(0, draft.cooldownSeconds)));
        FormFields.fill(cmd, PERMISSION, session, draft.permission);
        cmd.set("#RulesNote.TextSpans", uiText("#RulesNote.TextSpans",
                "Each opening spends one key. Players also need mysticessentials.lootbox.use."));

        Set<String> linked = new LinkedHashSet<>(draft.keys == null ? List.of() : draft.keys);
        List<KeyRow> rows = new ArrayList<>();
        for (KeyDraft.Published published : module.registry().keys()) {
            rows.add(new KeyRow(published.spec.id, published, linked.contains(published.spec.id)));
        }
        for (String keyId : linked) {
            if (module.registry().key(keyId).isEmpty()) {
                rows.add(new KeyRow(keyId, null, true));
            }
        }
        cmd.set("#KeysHint.TextSpans", uiText("#KeysHint.TextSpans", rows.isEmpty()
                ? "&eNo keys are published yet. Publish one with /lootbox key publish <keyId>."
                : "Select a key to link or unlink it. A pool needs at least one."));
        for (int i = 0; i < rows.size(); i++) {
            KeyRow row = rows.get(i);
            String sel = "#KeyList[" + i + "]";
            cmd.append("#KeyList", TOGGLE_ROW_UI);
            cmd.set(sel + " #Selected.Visible", row.linked());
            String name = row.published() == null ? row.id()
                    : MysticText.stripMarkup(blankTo(row.published().spec.name, row.id()));
            cmd.set(sel + " #Name.TextSpans", uiText(sel + " #Name.TextSpans", name + " &8(" + row.id() + ")"));
            cmd.set(sel + " #Meta.TextSpans", uiText(sel + " #Meta.TextSpans", row.published() == null
                    ? "&cnot published: publishing the pool will fail until it is"
                    : "v" + row.published().version + " · base " + row.published().spec.baseItemId + " · "
                            + row.published().spec.uses + " use(s)"));
            cmd.set(sel + " #State.TextSpans", uiText(sel + " #State.TextSpans", row.linked() ? "&aLinked" : "&8Not linked"));
            event.addEventBinding(CustomUIEventBindingType.Activating, sel, INPUTS.event("toggleKey").put("key", row.id()));
        }
    }

    private record KeyRow(String id, KeyDraft.Published published, boolean linked) {
    }

    private void buildRewards(UICommandBuilder cmd, LootBoxDraft draft) {
        OddsFormatter format = module.opening().formatter();
        Map<String, String> chances = DraftDiff.chances(draft, format);
        cmd.set("#RewardsHint.TextSpans", uiText("#RewardsHint.TextSpans", "Rewards are edited in "
                + "lootboxes/" + session.draftId() + ".json for now. Use Load From Disk after changing the file."));
        List<String> lines = new ArrayList<>();
        if (draft.rewards.isEmpty()) {
            lines.add("&8No rewards yet.");
        }
        List<Long> weights = null;
        long total = 0;
        try {
            weights = OddsEngine.resolveWeights(draft);
            for (long weight : weights) {
                total += weight;
            }
        } catch (IllegalArgumentException e) {
            lines.add("&c" + e.getMessage());
        }
        for (int i = 0; i < draft.rewards.size(); i++) {
            LootBoxDraft.RewardSpec reward = draft.rewards.get(i);
            String odds = weights == null
                    ? (OddsConverter.EXACT_PERCENT.equalsIgnoreCase(draft.mode) ? reward.percent + "%" : "weight " + reward.weight)
                    : "&6" + chances.get(reward.id) + " &8(" + format.oneIn(weights.get(i), total) + ")";
            lines.add("&f" + MysticText.stripMarkup(blankTo(reward.name, reward.id)) + " &8· "
                    + blankTo(reward.type, "?").toUpperCase(Locale.ROOT)
                    + (reward.rarity == null || reward.rarity.isBlank() ? "" : " · " + reward.rarity) + " &8· " + odds);
        }
        lines(cmd, "#RewardList", lines);
    }

    private void buildPublish(UICommandBuilder cmd, LootBoxDraft draft, List<String> problems) {
        List<String> problemLines = new ArrayList<>();
        if (problems.isEmpty()) {
            problemLines.add("&aNone. This draft can be published.");
        }
        problems.forEach(problem -> problemLines.add("&c• " + problem));
        lines(cmd, "#ProblemList", problemLines);
        LootBoxRevision live = module.registry().active(session.draftId()).orElse(null);
        DraftDiff.Diff diff = DraftDiff.between(draft, live == null ? null : live.spec, module.opening().formatter());
        List<String> diffLines = new ArrayList<>(diff.lines());
        if (live != null && diff.oddsChange()) {
            diffLines.add("&ePublishing changes the odds: players see the new ones from their next preview.");
        }
        if (session.dirty()) {
            diffLines.add("&7Unsaved changes are saved to the draft file first.");
        }
        lines(cmd, "#DiffList", diffLines);
    }

    private static void lines(UICommandBuilder cmd, String list, List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String row = list + "[" + i + "]";
            cmd.append(list, ConfirmActionPage.LINE_UI);
            cmd.set(row + " #Line.TextSpans", uiText(row + " #Line.TextSpans", lines.get(i)));
        }
    }

    private String headerInfo(String id, LootBoxDraft draft, List<String> problems) {
        DraftLifecycle.Status status = module.registry().poolStore().status(draft);
        StringBuilder text = new StringBuilder(id).append(" · ");
        text.append(problems.isEmpty() ? status.label() : "INVALID (" + problems.size() + ")");
        if (session.dirty()) {
            text.append(" · unsaved");
        }
        List<UUID> others = module.editors().editing(LootBoxDraft.class, id, player.getUuid());
        if (!others.isEmpty()) {
            text.append(" · also open by ").append(others.size()).append(" other(s)");
        }
        return text.toString();
    }

    /** Publish gates on the working copy, plus any text that did not parse. */
    private List<String> problems(LootBoxDraft draft) {
        List<String> problems = new ArrayList<>();
        for (FormFields.Problem problem : unparsed()) {
            problems.add(problem.message());
        }
        problems.addAll(module.registry().poolStore().status(draft).problems());
        return problems;
    }

    /** Problems with the text the player typed that was not applied, because it did not parse. */
    private List<FormFields.Problem> unparsed() {
        List<FormFields.Problem> problems = new ArrayList<>();
        String cooldown = session.input(COOLDOWN.key());
        if (cooldown != null) {
            FormFields.longInRange(COOLDOWN, cooldown, 0, MAX_COOLDOWN).collect(problems);
        }
        String permission = session.input(PERMISSION.key());
        if (permission != null && !permission.isBlank()) {
            FormFields.id(PERMISSION, permission.trim(), NODE, "letters, digits, _ . and -").collect(problems);
        }
        String name = session.input(NAME.key());
        if (name != null) {
            FormFields.nonBlank(NAME, name, MAX_NAME).collect(problems);
        }
        return problems;
    }

    // ----- Events -------------------------------------------------------------

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
        if (!current()) {
            core.getMessageService().send(player, "&eThat editor was closed; open the pool again from /lootbox admin.");
            close(ref, store);
            return;
        }
        JsonObject payload = parse(data);
        String action = string(payload, "action");
        if (!player.hasPermission(Permissions.LOOTBOX_EDIT)) {
            core.getMessageService().sendKey(player, "no-permission");
            close(ref, store);
            return;
        }
        INPUTS.remember(payload, session);
        applyInputs();
        switch (action) {
            case "tab" -> {
                session.tab(EditorTabs.resolve(TABS, field(payload, "tab")));
                reopen(ref, store, page(View.of(null)));
            }
            case "mode" -> switchMode(ref, store, string(payload, "@mode"));
            case "icon" -> pickIcon(ref, store);
            case "iconClear" -> {
                session.working().icon = "";
                session.markDirty();
                reopen(ref, store, page(View.of(null)));
            }
            case "toggleKey" -> {
                toggleKey(field(payload, "key"));
                reopen(ref, store, page(View.of(null)));
            }
            case "save" -> save(ref, store, null);
            case "revert" -> confirmRevert(ref, store);
            case "reloadDisk" -> loadFromDisk(ref, store, "&7Loaded the draft file; your unsaved changes were dropped.");
            case "overwrite" -> confirmOverwrite(ref, store);
            case "publish" -> publish(ref, store);
            case "close" -> confirmClose(ref, store);
            default -> reopen(ref, store, page(view));
        }
    }

    /** Copies every field that parses into the working copy and forgets its text; the rest stays as typed. */
    private void applyInputs() {
        LootBoxDraft draft = session.working();
        String name = session.input(NAME.key());
        if (name != null) {
            FormFields.Result<String> parsed = FormFields.nonBlank(NAME, name, MAX_NAME);
            if (parsed.ok()) {
                change(!parsed.value().equals(draft.displayName), () -> draft.displayName = parsed.value());
                session.forgetInput(NAME.key());
            }
        }
        String description = session.input(DESCRIPTION.key());
        if (description != null) {
            String value = description.length() > MAX_DESCRIPTION ? description.substring(0, MAX_DESCRIPTION)
                    : description;
            change(!value.equals(blankTo(draft.description, "")), () -> draft.description = value);
            session.forgetInput(DESCRIPTION.key());
        }
        String cooldown = session.input(COOLDOWN.key());
        if (cooldown != null) {
            FormFields.Result<Long> parsed = FormFields.longInRange(COOLDOWN, cooldown, 0, MAX_COOLDOWN);
            if (parsed.ok()) {
                change(parsed.value() != draft.cooldownSeconds, () -> draft.cooldownSeconds = parsed.value());
                session.forgetInput(COOLDOWN.key());
            }
        }
        String permission = session.input(PERMISSION.key());
        if (permission != null) {
            String value = permission.trim();
            if (value.isEmpty() || FormFields.id(PERMISSION, value, NODE, "").ok()) {
                change(!value.equals(blankTo(draft.permission, "")), () -> draft.permission = value);
                session.forgetInput(PERMISSION.key());
            }
        }
    }

    private void change(boolean differs, Runnable apply) {
        if (differs) {
            apply.run();
            session.markDirty();
        }
    }

    private void switchMode(Ref<EntityStore> ref, Store<EntityStore> store, String mode) {
        OddsConverter.Result result = OddsConverter.switchTo(session.working(), mode);
        if (!result.ok()) {
            reopen(ref, store, page(View.of("&cKept the current mode: " + result.problem() + ".")));
            return;
        }
        session.markDirty();
        String label = OddsConverter.EXACT_PERCENT.equals(session.working().mode) ? "exact percentages" : "weights";
        String notice = result.exact() ? "&aSwitched to " + label + "; every chance is unchanged."
                : "&eSwitched to " + label + " with rounding: " + String.join("; ", result.notes()) + ".";
        reopen(ref, store, page(View.of(notice)));
    }

    private void pickIcon(Ref<EntityStore> ref, Store<EntityStore> store) {
        LootBoxDraft draft = session.working();
        session.awaitReturn(itemId -> draft.icon = itemId);
        ItemPickerPage.Spec spec = new ItemPickerPage.Spec("Choose an Icon",
                "Icon for " + MysticText.stripMarkup(blankTo(draft.displayName, session.draftId())),
                blankTo(draft.icon, ""), Permissions.LOOTBOX_EDIT, "");
        reopen(ref, store, ItemPickerPage.create(core, player, session, spec,
                (r, s) -> reopen(r, s, page(View.of(null)))));
    }

    private void toggleKey(String keyId) {
        if (keyId == null || keyId.isBlank()) {
            return;
        }
        LootBoxDraft draft = session.working();
        List<String> keys = new ArrayList<>(draft.keys == null ? List.of() : draft.keys);
        if (!keys.remove(keyId)) {
            if (module.registry().key(keyId).isEmpty()) {
                return;
            }
            keys.add(keyId);
        }
        draft.keys = keys;
        session.markDirty();
    }

    // ----- Saving -------------------------------------------------------------

    /** What runs once a save succeeded, on a background thread. */
    @FunctionalInterface
    private interface AfterSave {
        void run();
    }

    /**
     * Saves the working copy as it stands now; {@code then} runs after a successful
     * save (Publish), otherwise the editor reopens with the outcome.
     */
    private void save(Ref<EntityStore> ref, Store<EntityStore> store, AfterSave then) {
        List<FormFields.Problem> unparsed = unparsed();
        if (!unparsed.isEmpty()) {
            reopen(ref, store, page(View.of("&cNot saved: " + unparsed.get(0).message() + ".")));
            return;
        }
        long changes = session.changes();
        LootBoxDraft snapshot = DraftLifecycle.freeze(session.working(), LootBoxDraft.class);
        String expected = session.loadedHash();
        reopen(ref, store, page(View.of("&7Saving...")));
        module.registry().poolStore().save(snapshot, expected, player.getUsername()).whenComplete((result, error) -> {
            if (error != null) {
                core.log(Level.WARNING, "[lootbox] saving draft " + session.draftId() + " failed: " + error);
                openLater(View.of("&cNot saved: the draft file could not be written (see the server log)."));
            } else if (result instanceof DraftStore.Saved saved) {
                session.markSaved(saved.hash(), changes);
                if (then != null) {
                    then.run();
                } else {
                    openLater(View.of("&aSaved to lootboxes/" + session.draftId() + ".json. Nothing is published yet."));
                }
            } else if (result instanceof DraftStore.Conflict) {
                openLater(new View("&eThe draft file changed since you opened it, so nothing was saved. Load From Disk "
                        + "drops your changes; Overwrite replaces the file with yours.", true));
            } else if (result instanceof DraftStore.Refused refused) {
                openLater(View.of("&cNot saved: " + refused.reason() + "."));
            }
        });
    }

    private void confirmOverwrite(Ref<EntityStore> ref, Store<EntityStore> store) {
        ConfirmActionPage.Spec spec = new ConfirmActionPage.Spec("Overwrite the draft file?", List.of(
                "&7Someone (or a text editor) changed &flootboxes/" + session.draftId() + ".json &7after you opened it.",
                "&7Overwrite replaces their version with yours. The overwrite is audited."),
                "Overwrite", true, Permissions.LOOTBOX_EDIT);
        reopen(ref, store, new ConfirmActionPage(core, player, spec, (r, s) -> {
            if (!current()) {
                close(r, s);
                return;
            }
            long changes = session.changes();
            LootBoxDraft snapshot = DraftLifecycle.freeze(session.working(), LootBoxDraft.class);
            reopen(r, s, page(View.of("&7Overwriting...")));
            module.registry().poolStore().overwrite(snapshot, player.getUsername()).whenComplete((result, error) -> {
                if (result instanceof DraftStore.Saved saved) {
                    session.markSaved(saved.hash(), changes);
                    openLater(View.of("&aOverwrote the draft file with your version."));
                } else {
                    if (error != null) {
                        core.log(Level.WARNING, "[lootbox] overwriting draft " + session.draftId() + " failed: " + error);
                    }
                    openLater(new View("&cNot overwritten: " + (result instanceof DraftStore.Refused refused
                            ? refused.reason() : "see the server log") + ".", true));
                }
            });
        }, (r, s) -> reopen(r, s, page(view))));
    }

    private void confirmRevert(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (!session.dirty()) {
            reopen(ref, store, page(View.of("&7Nothing to revert.")));
            return;
        }
        ConfirmActionPage.Spec spec = new ConfirmActionPage.Spec("Discard your changes?", List.of(
                "&7Reloads &flootboxes/" + session.draftId() + ".json &7and drops everything you changed since the "
                        + "last save."), "Discard", true, Permissions.LOOTBOX_EDIT);
        reopen(ref, store, new ConfirmActionPage(core, player, spec,
                (r, s) -> {
                    if (current()) {
                        loadFromDisk(r, s, "&7Reverted to the saved draft.");
                    } else {
                        close(r, s);
                    }
                },
                (r, s) -> reopen(r, s, page(view))));
    }

    private void loadFromDisk(Ref<EntityStore> ref, Store<EntityStore> store, String notice) {
        reopen(ref, store, page(View.of("&7Loading the draft file...")));
        module.registry().poolStore().load(session.draftId()).whenComplete((loaded, error) -> {
            if (error != null || loaded == null || loaded.isEmpty()) {
                if (error != null) {
                    core.log(Level.WARNING, "[lootbox] loading draft " + session.draftId() + " failed: " + error);
                }
                openLater(View.of("&cThe draft file is gone or unreadable; nothing was loaded."));
                return;
            }
            session.reload(loaded.get().draft(), loaded.get().hash());
            openLater(View.of(notice));
        });
    }

    // ----- Publishing ---------------------------------------------------------

    private void publish(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (!player.hasPermission(Permissions.LOOTBOX_PUBLISH)) {
            core.getMessageService().sendKey(player, "no-permission");
            reopen(ref, store, page(view));
            return;
        }
        LootBoxDraft draft = session.working();
        List<String> problems = problems(draft);
        if (!problems.isEmpty()) {
            session.tab(TAB_PUBLISH);
            reopen(ref, store, page(View.of("&cFix the " + problems.size() + " problem(s) below before publishing.")));
            return;
        }
        LootBoxRevision live = module.registry().active(session.draftId()).orElse(null);
        DraftDiff.Diff diff = DraftDiff.between(draft, live == null ? null : live.spec, module.opening().formatter());
        if (live != null && !diff.changed() && !session.dirty()
                && module.registry().poolStore().status(draft).state() == DraftLifecycle.State.PUBLISHED) {
            reopen(ref, store, page(View.of("&7Nothing to publish: this matches live revision r" + live.revision + ".")));
            return;
        }
        List<String> lines = new ArrayList<>(diff.lines());
        lines.add(live == null ? "&7This becomes the pool's first revision."
                : "&7This becomes the revision after r" + live.revision + "; openings already made keep their odds.");
        if (session.dirty()) {
            lines.add("&7Your unsaved changes are saved to the draft file first.");
        }
        String name = MysticText.stripMarkup(blankTo(draft.displayName, session.draftId()));
        ConfirmActionPage.Spec spec = new ConfirmActionPage.Spec("Publish " + name + "?", lines, "Publish", false,
                Permissions.LOOTBOX_PUBLISH);
        reopen(ref, store, new ConfirmActionPage(core, player, spec, (r, s) -> {
            if (!current()) {
                close(r, s);
                return;
            }
            AfterSave publishNow = this::publishSaved;
            if (session.dirty() || session.isNew()) {
                save(r, s, publishNow);
            } else {
                reopen(r, s, page(View.of("&7Publishing...")));
                publishNow.run();
            }
        }, (r, s) -> reopen(r, s, page(view))));
    }

    /** Publishes the saved draft file (the registry's cached copy, which the save just updated). */
    private void publishSaved() {
        String id = session.draftId();
        module.registry().publish(id, player.getUsername(), player.hasPermission(Permissions.LOOTBOX_REWARDS_COMMAND))
                .whenComplete((result, error) -> {
                    if (error != null) {
                        core.log(Level.WARNING, "[lootbox] publishing " + id + " failed: " + error);
                        openLater(View.of("&cPublishing failed; see the server log. The draft is saved."));
                        return;
                    }
                    module.ui().sendPublishResult(player, id, result);
                    openLater(View.of(!result.ok() ? "&cNot published: " + String.join("; ", result.problems())
                            : result.unchanged() ? "&7Unchanged: revision " + result.revision() + " stays live."
                            : "&aPublished revision " + result.revision() + " &7(odds " + result.hash() + ")."));
                });
    }

    // ----- Closing ------------------------------------------------------------

    private void confirmClose(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (!session.dirty()) {
            closeEditor(ref, store, null);
            return;
        }
        ConfirmActionPage.Spec spec = new ConfirmActionPage.Spec("Close without saving?", List.of(
                "&7You have unsaved changes to &f" + session.draftId() + "&7. Closing drops them; the draft file keeps "
                        + "its last saved version."), "Discard and Close", true, null);
        reopen(ref, store, new ConfirmActionPage(core, player, spec,
                (r, s) -> closeEditor(r, s, "&7Closed " + session.draftId() + " without saving."),
                (r, s) -> reopen(r, s, page(view))));
    }

    private void closeEditor(Ref<EntityStore> ref, Store<EntityStore> store, String notice) {
        module.editors().end(session);
        reopen(ref, store, new LootBoxManagerPage(core, player, module,
                LootBoxManagerPage.State.selecting(session.draftId(), notice)));
    }

    // ----- Helpers ------------------------------------------------------------

    /** Whether this page's session is still open and still the player's current editor. */
    private boolean current() {
        return session.active() && module.editors().get(player.getUuid()).map(open -> open == session).orElse(false);
    }

    private PoolEditorPage page(View next) {
        return new PoolEditorPage(core, player, module, session, next);
    }

    /** Reopens the editor from a background completion, unless the player has moved on to another editor. */
    private void openLater(View next) {
        if (current()) {
            core.platform().openPage(player, page(next));
        }
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
