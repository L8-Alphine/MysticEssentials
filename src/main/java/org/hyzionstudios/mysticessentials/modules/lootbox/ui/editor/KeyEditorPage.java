package org.hyzionstudios.mysticessentials.modules.lootbox.ui.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.api.Permissions;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.message.MysticText;
import org.hyzionstudios.mysticessentials.modules.lootbox.LootBoxModule;
import org.hyzionstudios.mysticessentials.modules.lootbox.hytale.KeyItemFactory;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.KeyDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.DraftLifecycle;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.DraftStore;
import org.hyzionstudios.mysticessentials.modules.lootbox.ui.KeyManagerPage;
import org.hyzionstudios.mysticessentials.platform.ui.ItemCatalog;
import org.hyzionstudios.mysticessentials.platform.ui.MysticPage;

import com.google.gson.JsonObject;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * The key editor (P2-F1): one working copy of a key draft — base item, name with
 * colour, bold and italic, lore, uses and expiry — with a live preview of the
 * key item, the pools it opens, and its problems and changes. Written to the
 * key file only by Save (or Publish, which saves first).
 *
 * <p>It works like {@link PoolEditorPage}: the working copy and unsaved text
 * live in an {@link EditorSession}, every event carries every field, saves are
 * optimistic (a changed file offers Load From Disk or Overwrite), every press
 * checks the session is still current and re-checks {@code edit} (and
 * {@code publish} for Publish), handlers reopen synchronously, and file work
 * reopens the editor with a notice.</p>
 */
public final class KeyEditorPage extends MysticPage {

    static final String EDITOR_UI = "MysticEssentials/LootBoxKeyEditor.ui";
    static final int LORE_LINES = 5;

    static final FormFields.Field NAME = FormFields.Field.text("keyName", "#KeyNameInput", "Name");
    static final FormFields.Field COLOR = FormFields.Field.color("nameColor", "#NameColor", "Name colour");
    static final FormFields.Field USES = FormFields.Field.text("uses", "#UsesInput", "Uses");
    static final FormFields.Field EXPIRY = FormFields.Field.text("expiry", "#ExpiryInput", "Expiry");
    static final List<FormFields.Field> LORE = List.of(
            FormFields.Field.text("lore1", "#Lore1Input", "Lore line 1"),
            FormFields.Field.text("lore2", "#Lore2Input", "Lore line 2"),
            FormFields.Field.text("lore3", "#Lore3Input", "Lore line 3"),
            FormFields.Field.text("lore4", "#Lore4Input", "Lore line 4"),
            FormFields.Field.text("lore5", "#Lore5Input", "Lore line 5"));
    static final FormFields INPUTS = FormFields.of(fields());

    private static final int MAX_NAME = 96;
    private static final int MAX_LORE = 120;
    private static final long MAX_EXPIRY_DAYS = 3650;
    /** The picker's colour when the name has none; picking it again changes nothing. */
    private static final String DEFAULT_COLOR = "#FFFFFF";

    private static FormFields.Field[] fields() {
        List<FormFields.Field> all = new ArrayList<>(List.of(NAME, COLOR, USES, EXPIRY));
        all.addAll(LORE);
        return all.toArray(FormFields.Field[]::new);
    }

    private final LootBoxModule module;
    private final EditorSession<KeyDraft> session;
    private final PoolEditorPage.View view;

    public KeyEditorPage(MysticCore core, PlayerRef player, LootBoxModule module, EditorSession<KeyDraft> session,
            PoolEditorPage.View view) {
        super(core, player, CustomPageLifetime.CanDismiss);
        this.module = module;
        this.session = session;
        this.view = view == null ? PoolEditorPage.View.of(null) : view;
    }

    // ----- Build --------------------------------------------------------------

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event, Store<EntityStore> store) {
        cmd.append(EDITOR_UI);
        KeyDraft key = session.working();
        String id = session.draftId();
        List<String> problems = problems(key);
        KeyText.Style style = KeyText.parse(key.name);

        cmd.set("#HeaderTitle.TextSpans", uiText("#HeaderTitle.TextSpans",
                "Edit Key " + MysticText.stripMarkup(blank(key.name, id))));
        cmd.set("#HeaderInfo.TextSpans", uiText("#HeaderInfo.TextSpans", headerInfo(id, key, problems)));
        String notice = view.notice();
        cmd.set("#Notice.Visible", notice != null && !notice.isBlank());
        if (notice != null && !notice.isBlank()) {
            cmd.set("#Notice.TextSpans", uiText("#Notice.TextSpans", notice));
        }

        boolean baseExists = key.baseItemId != null && !key.baseItemId.isBlank() && ItemCatalog.exists(key.baseItemId);
        if (baseExists) {
            cmd.set("#BaseItemSlot.ItemId", key.baseItemId);
            cmd.set("#PreviewSlot.ItemId", key.baseItemId);
        }
        cmd.set("#BaseItemName.TextSpans", uiText("#BaseItemName.TextSpans", baseExists ? key.baseItemId
                : key.baseItemId == null || key.baseItemId.isBlank() ? "&8No base item: choose one"
                : "&c" + key.baseItemId + " is not an item on this server"));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#BaseItemButton", INPUTS.event("baseItem"));

        FormFields.fill(cmd, NAME, session, style.text());
        FormFields.fill(cmd, COLOR, session, style.color() == null ? DEFAULT_COLOR : style.color());
        cmd.set("#BoldOnButton.Visible", !style.bold());
        cmd.set("#BoldOffButton.Visible", style.bold());
        cmd.set("#ItalicOnButton.Visible", !style.italic());
        cmd.set("#ItalicOffButton.Visible", style.italic());
        cmd.set("#ColorClearButton.Visible", style.color() != null);
        for (String[] toggle : new String[][] {{"#BoldOnButton", "boldOn"}, {"#BoldOffButton", "boldOff"},
                {"#ItalicOnButton", "italicOn"}, {"#ItalicOffButton", "italicOff"}, {"#ColorClearButton", "colorClear"}}) {
            event.addEventBinding(CustomUIEventBindingType.Activating, toggle[0], INPUTS.event(toggle[1]));
        }
        List<String> lore = key.lore == null ? List.of() : key.lore;
        for (int i = 0; i < LORE_LINES; i++) {
            FormFields.fill(cmd, LORE.get(i), session, i < lore.size() ? lore.get(i) : "");
        }
        FormFields.fill(cmd, USES, session, Integer.toString(key.uses));
        FormFields.fill(cmd, EXPIRY, session, Long.toString(Math.max(0, key.expiresAfterDays)));
        cmd.set("#StackableOnButton.Visible", !key.stackable);
        cmd.set("#StackableOffButton.Visible", key.stackable);
        cmd.set("#StackHint.TextSpans", uiText("#StackHint.TextSpans", stackHint(key)));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#StackableOnButton", INPUTS.event("stackOn"));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#StackableOffButton", INPUTS.event("stackOff"));

        cmd.set("#PreviewName.TextSpans", uiText("#PreviewName.TextSpans", blank(key.name, "&8(no name)")));
        cmd.set("#PreviewMeta.TextSpans", uiText("#PreviewMeta.TextSpans",
                KeyDiff.uses(key.stackable ? 1 : key.uses) + " · " + KeyDiff.expiry(key.expiresAfterDays)
                        + (key.stackable ? " · stacks" : "")));
        List<String> previewLore = new ArrayList<>();
        lore.forEach(line -> previewLore.add(line.isBlank() ? " " : line));
        if (previewLore.isEmpty()) {
            previewLore.add("&8No lore");
        }
        if (lore.size() > LORE_LINES) {
            previewLore.add("&8(lines after " + LORE_LINES + " are kept but only editable in the file)");
        }
        lines(cmd, "#PreviewLore", previewLore);

        List<String> opens = KeyManagerPage.opens(module.registry(), id);
        cmd.set("#OpensLine.TextSpans", uiText("#OpensLine.TextSpans", opens.isEmpty()
                ? "&eNo pool lists this key yet. Link it on a pool's Keys & Rules tab."
                : String.join(", ", opens)));

        List<String> lines = new ArrayList<>();
        if (problems.isEmpty()) {
            lines.add("&aNo problems. This key can be published.");
        }
        problems.forEach(problem -> lines.add("&c• " + problem));
        KeyDraft.Published live = module.registry().key(id).orElse(null);
        lines.addAll(KeyDiff.lines(key, live == null ? null : live.spec));
        if (session.dirty()) {
            lines.add("&7Unsaved changes are saved to the key file first when you publish.");
        }
        lines(cmd, "#ProblemList", lines);

        cmd.set("#ReloadDiskButton.Visible", view.conflict());
        cmd.set("#OverwriteButton.Visible", view.conflict());
        cmd.set("#RevertButton.Visible", !view.conflict() && session.dirty());
        cmd.set("#PublishButton.Visible", player.hasPermission(Permissions.LOOTBOX_PUBLISH));
        for (String[] button : new String[][] {{"#SaveButton", "save"}, {"#RevertButton", "revert"},
                {"#ReloadDiskButton", "reloadDisk"}, {"#OverwriteButton", "overwrite"}, {"#PublishButton", "publish"},
                {"#CloseButton", "close"}}) {
            event.addEventBinding(CustomUIEventBindingType.Activating, button[0], INPUTS.event(button[1]));
        }
    }

    private static void lines(UICommandBuilder cmd, String list, List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String row = list + "[" + i + "]";
            cmd.append(list, ConfirmActionPage.LINE_UI);
            cmd.set(row + " #Line.TextSpans", uiText(row + " #Line.TextSpans", lines.get(i)));
        }
    }

    private String headerInfo(String id, KeyDraft key, List<String> problems) {
        DraftLifecycle.Status status = module.registry().keyStore().status(key);
        StringBuilder text = new StringBuilder(id).append(" · ")
                .append(problems.isEmpty() ? status.label() : "INVALID (" + problems.size() + ")");
        if (session.dirty()) {
            text.append(" · unsaved");
        }
        List<UUID> others = module.editors().editing(KeyDraft.class, id, player.getUuid());
        if (!others.isEmpty()) {
            text.append(" · also open by ").append(others.size()).append(" other(s)");
        }
        return text.toString();
    }

    private List<String> problems(KeyDraft key) {
        List<String> problems = new ArrayList<>();
        unparsed().forEach(problem -> problems.add(problem.message()));
        problems.addAll(module.registry().keyStore().status(key).problems());
        return problems;
    }

    /** What stacking means for this key, under its two buttons. */
    private static String stackHint(KeyDraft key) {
        if (!key.stackable) {
            return "Each key item has its own serial: a copy is dead on sight. Items never stack.";
        }
        int maxStack = key.baseItemId == null ? 0 : KeyItemFactory.maxStack(key.baseItemId);
        return (maxStack == 1 ? "&eThis base item does not stack (MaxStack 1). &r" : "")
                + "Items of one grant share a batch and stack. Weaker against duplication: a copied item uses up "
                + "the batch instead of being dead on sight.";
    }

    private List<FormFields.Problem> unparsed() {
        List<FormFields.Problem> problems = new ArrayList<>();
        String name = session.input(NAME.key());
        if (name != null) {
            FormFields.nonBlank(NAME, name, MAX_NAME).collect(problems);
        }
        for (FormFields.Field field : LORE) {
            String line = session.input(field.key());
            if (line != null && line.length() > MAX_LORE) {
                problems.add(new FormFields.Problem(field.key(), FormFields.Kind.TOO_LONG,
                        field.label() + " is longer than " + MAX_LORE + " characters."));
            }
        }
        String uses = session.input(USES.key());
        if (uses != null) {
            FormFields.intInRange(USES, uses, 1, 1000).collect(problems);
        }
        String expiry = session.input(EXPIRY.key());
        if (expiry != null) {
            FormFields.longInRange(EXPIRY, expiry, 0, MAX_EXPIRY_DAYS).collect(problems);
        }
        return problems;
    }

    // ----- Events -------------------------------------------------------------

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
        if (!current()) {
            core.getMessageService().send(player, "&eThat editor was closed; open the key again from the Key Manager.");
            close(ref, store);
            return;
        }
        if (!player.hasPermission(Permissions.LOOTBOX_EDIT)) {
            core.getMessageService().sendKey(player, "no-permission");
            close(ref, store);
            return;
        }
        JsonObject payload = parse(data);
        String action = string(payload, "action");
        INPUTS.remember(payload, session);
        applyInputs();
        switch (action) {
            case "baseItem" -> pickBaseItem(ref, store);
            case "boldOn", "boldOff" -> restyle(ref, store, style -> style.withBold("boldOn".equals(action)));
            case "italicOn", "italicOff" -> restyle(ref, store, style -> style.withItalic("italicOn".equals(action)));
            case "colorClear" -> {
                session.forgetInput(COLOR.key());
                restyle(ref, store, style -> style.withColor(null));
            }
            case "stackOn", "stackOff" -> {
                KeyDraft key = session.working();
                boolean stackable = "stackOn".equals(action);
                if (key.stackable != stackable) {
                    key.stackable = stackable;
                    session.markDirty();
                }
                reopen(ref, store, page(PoolEditorPage.View.of(null)));
            }
            case "save" -> save(ref, store, null);
            case "revert" -> confirmRevert(ref, store);
            case "reloadDisk" -> loadFromDisk(ref, store, "&7Loaded the key file; your unsaved changes were dropped.");
            case "overwrite" -> confirmOverwrite(ref, store);
            case "publish" -> publish(ref, store);
            case "close" -> confirmClose(ref, store);
            default -> reopen(ref, store, page(view));
        }
    }

    /** Copies every field that parses into the working copy and forgets its text; the rest stays as typed. */
    private void applyInputs() {
        KeyDraft key = session.working();
        KeyText.Style style = KeyText.parse(key.name);
        KeyText.Style next = style;
        String name = session.input(NAME.key());
        if (name != null) {
            FormFields.Result<String> parsed = FormFields.nonBlank(NAME, name, MAX_NAME);
            if (parsed.ok()) {
                next = next.withText(parsed.value());
                session.forgetInput(NAME.key());
            }
        }
        String color = session.input(COLOR.key());
        if (color != null) {
            String picked = KeyText.color(color);
            String shown = style.color() == null ? DEFAULT_COLOR : style.color();
            if (picked != null && !picked.equals(shown)) {
                next = next.withColor(picked);
            }
            session.forgetInput(COLOR.key());
        }
        String composed = KeyText.compose(next);
        if (!composed.equals(key.name == null ? "" : key.name) && (name != null || color != null)
                && !next.equals(style)) {
            key.name = composed;
            session.markDirty();
        }

        boolean loreTyped = LORE.stream().anyMatch(field -> session.input(field.key()) != null);
        boolean loreOk = LORE.stream().allMatch(field -> {
            String line = session.input(field.key());
            return line == null || line.length() <= MAX_LORE;
        });
        if (loreTyped && loreOk) {
            List<String> current = key.lore == null ? List.of() : key.lore;
            List<String> typed = new ArrayList<>();
            for (int i = 0; i < LORE_LINES; i++) {
                String line = session.input(LORE.get(i).key());
                typed.add(line != null ? line : i < current.size() ? current.get(i) : "");
            }
            List<String> lore;
            if (current.size() > LORE_LINES) {
                lore = new ArrayList<>(typed);
                lore.addAll(current.subList(LORE_LINES, current.size()));
            } else {
                lore = KeyText.lore(typed);
            }
            if (!lore.equals(current)) {
                key.lore = lore;
                session.markDirty();
            }
            LORE.forEach(field -> session.forgetInput(field.key()));
        }

        String uses = session.input(USES.key());
        if (uses != null) {
            FormFields.Result<Integer> parsed = FormFields.intInRange(USES, uses, 1, 1000);
            if (parsed.ok()) {
                if (parsed.value() != key.uses) {
                    key.uses = parsed.value();
                    session.markDirty();
                }
                session.forgetInput(USES.key());
            }
        }
        String expiry = session.input(EXPIRY.key());
        if (expiry != null) {
            FormFields.Result<Long> parsed = FormFields.longInRange(EXPIRY, expiry, 0, MAX_EXPIRY_DAYS);
            if (parsed.ok()) {
                if (parsed.value() != key.expiresAfterDays) {
                    key.expiresAfterDays = parsed.value();
                    session.markDirty();
                }
                session.forgetInput(EXPIRY.key());
            }
        }
    }

    private void restyle(Ref<EntityStore> ref, Store<EntityStore> store,
            java.util.function.UnaryOperator<KeyText.Style> change) {
        KeyDraft key = session.working();
        KeyText.Style style = KeyText.parse(key.name);
        String composed = KeyText.compose(change.apply(style));
        if (!composed.equals(key.name)) {
            key.name = composed;
            session.markDirty();
        }
        reopen(ref, store, page(PoolEditorPage.View.of(null)));
    }

    private void pickBaseItem(Ref<EntityStore> ref, Store<EntityStore> store) {
        KeyDraft key = session.working();
        session.awaitReturn(itemId -> key.baseItemId = itemId);
        ItemPickerPage.Spec spec = new ItemPickerPage.Spec("Choose a Base Item",
                "Key: " + MysticText.stripMarkup(blank(key.name, session.draftId())), blank(key.baseItemId, ""),
                Permissions.LOOTBOX_EDIT, "");
        reopen(ref, store, ItemPickerPage.create(core, player, session, spec,
                (r, s) -> reopen(r, s, page(PoolEditorPage.View.of(null)))));
    }

    // ----- Saving -------------------------------------------------------------

    @FunctionalInterface
    private interface AfterSave {
        void run();
    }

    private void save(Ref<EntityStore> ref, Store<EntityStore> store, AfterSave then) {
        List<FormFields.Problem> unparsed = unparsed();
        if (!unparsed.isEmpty()) {
            reopen(ref, store, page(PoolEditorPage.View.of("&cNot saved: " + unparsed.get(0).message())));
            return;
        }
        long changes = session.changes();
        KeyDraft snapshot = DraftLifecycle.freeze(session.working(), KeyDraft.class);
        String expected = session.loadedHash();
        reopen(ref, store, page(PoolEditorPage.View.of("&7Saving...")));
        module.registry().keyStore().save(snapshot, expected, player.getUsername()).whenComplete((result, error) -> {
            if (error != null) {
                core.log(Level.WARNING, "[lootbox] saving key " + session.draftId() + " failed: " + error);
                openLater(PoolEditorPage.View.of("&cNot saved: the key file could not be written (see the server log)."));
            } else if (result instanceof DraftStore.Saved saved) {
                session.markSaved(saved.hash(), changes);
                if (then != null) {
                    then.run();
                } else {
                    openLater(PoolEditorPage.View.of("&aSaved to " + KeyManagerPage.keyFile(session.draftId())
                            + ". Nothing is published yet."));
                }
            } else if (result instanceof DraftStore.Conflict) {
                openLater(new PoolEditorPage.View("&eThe key file changed since you opened it, so nothing was saved. "
                        + "Load From Disk drops your changes; Overwrite replaces the file with yours.", true));
            } else if (result instanceof DraftStore.Refused refused) {
                openLater(PoolEditorPage.View.of("&cNot saved: " + refused.reason() + "."));
            }
        });
    }

    private void confirmOverwrite(Ref<EntityStore> ref, Store<EntityStore> store) {
        ConfirmActionPage.Spec spec = new ConfirmActionPage.Spec("Overwrite the key file?", List.of(
                "&7Someone (or a text editor) changed &f" + KeyManagerPage.keyFile(session.draftId())
                        + " &7after you opened it.",
                "&7Overwrite replaces their version with yours. The overwrite is audited."),
                "Overwrite", true, Permissions.LOOTBOX_EDIT);
        reopen(ref, store, new ConfirmActionPage(core, player, spec, (r, s) -> {
            if (!current()) {
                close(r, s);
                return;
            }
            long changes = session.changes();
            KeyDraft snapshot = DraftLifecycle.freeze(session.working(), KeyDraft.class);
            reopen(r, s, page(PoolEditorPage.View.of("&7Overwriting...")));
            module.registry().keyStore().overwrite(snapshot, player.getUsername()).whenComplete((result, error) -> {
                if (result instanceof DraftStore.Saved saved) {
                    session.markSaved(saved.hash(), changes);
                    openLater(PoolEditorPage.View.of("&aOverwrote the key file with your version."));
                } else {
                    if (error != null) {
                        core.log(Level.WARNING, "[lootbox] overwriting key " + session.draftId() + " failed: " + error);
                    }
                    openLater(new PoolEditorPage.View("&cNot overwritten: " + (result instanceof DraftStore.Refused refused
                            ? refused.reason() : "see the server log") + ".", true));
                }
            });
        }, (r, s) -> reopen(r, s, page(view))));
    }

    private void confirmRevert(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (!session.dirty()) {
            reopen(ref, store, page(PoolEditorPage.View.of("&7Nothing to revert.")));
            return;
        }
        ConfirmActionPage.Spec spec = new ConfirmActionPage.Spec("Discard your changes?", List.of(
                "&7Reloads &f" + KeyManagerPage.keyFile(session.draftId()) + " &7and drops everything you changed "
                        + "since the last save."), "Discard", true, Permissions.LOOTBOX_EDIT);
        reopen(ref, store, new ConfirmActionPage(core, player, spec,
                (r, s) -> {
                    if (current()) {
                        loadFromDisk(r, s, "&7Reverted to the saved key.");
                    } else {
                        close(r, s);
                    }
                },
                (r, s) -> reopen(r, s, page(view))));
    }

    private void loadFromDisk(Ref<EntityStore> ref, Store<EntityStore> store, String notice) {
        reopen(ref, store, page(PoolEditorPage.View.of("&7Loading the key file...")));
        module.registry().keyStore().load(session.draftId()).whenComplete((loaded, error) -> {
            if (error != null || loaded == null || loaded.isEmpty()) {
                if (error != null) {
                    core.log(Level.WARNING, "[lootbox] loading key " + session.draftId() + " failed: " + error);
                }
                openLater(PoolEditorPage.View.of("&cThe key file is gone or unreadable; nothing was loaded."));
                return;
            }
            session.reload(loaded.get().draft(), loaded.get().hash());
            openLater(PoolEditorPage.View.of(notice));
        });
    }

    // ----- Publishing ---------------------------------------------------------

    private void publish(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (!player.hasPermission(Permissions.LOOTBOX_PUBLISH)) {
            core.getMessageService().sendKey(player, "no-permission");
            reopen(ref, store, page(view));
            return;
        }
        KeyDraft key = session.working();
        List<String> problems = problems(key);
        if (!problems.isEmpty()) {
            reopen(ref, store, page(PoolEditorPage.View.of("&cFix the " + problems.size()
                    + " problem(s) listed on the right before publishing.")));
            return;
        }
        KeyDraft.Published live = module.registry().key(session.draftId()).orElse(null);
        if (live != null && !KeyDiff.changed(key, live.spec) && !session.dirty()) {
            reopen(ref, store, page(PoolEditorPage.View.of("&7Nothing to publish: this matches version "
                    + live.version + ".")));
            return;
        }
        List<String> lines = new ArrayList<>(KeyDiff.lines(key, live == null ? null : live.spec));
        lines.add(live == null ? "&7This becomes the key's first version."
                : "&7This becomes version " + (live.version + 1) + ". Keys already given out keep their current name "
                        + "and lore; keys issued from now on get this version.");
        if (session.dirty()) {
            lines.add("&7Your unsaved changes are saved to the key file first.");
        }
        ConfirmActionPage.Spec spec = new ConfirmActionPage.Spec("Publish " + MysticText.stripMarkup(
                blank(key.name, session.draftId())) + "?", lines, "Publish", false, Permissions.LOOTBOX_PUBLISH);
        reopen(ref, store, new ConfirmActionPage(core, player, spec, (r, s) -> {
            if (!current()) {
                close(r, s);
                return;
            }
            AfterSave publishNow = this::publishSaved;
            if (session.dirty() || session.isNew()) {
                save(r, s, publishNow);
            } else {
                reopen(r, s, page(PoolEditorPage.View.of("&7Publishing...")));
                publishNow.run();
            }
        }, (r, s) -> reopen(r, s, page(view))));
    }

    private void publishSaved() {
        String id = session.draftId();
        module.registry().publishKey(id, player.getUsername()).whenComplete((result, error) -> {
            if (error != null) {
                core.log(Level.WARNING, "[lootbox] publishing key " + id + " failed: " + error);
                openLater(PoolEditorPage.View.of("&cPublishing failed; see the server log. The key file is saved."));
                return;
            }
            openLater(PoolEditorPage.View.of(!result.ok() ? "&cNot published: " + String.join("; ", result.problems())
                    : result.unchanged() ? "&7Unchanged: version " + result.revision() + " stays published."
                    : "&aPublished version " + result.revision() + "."));
        });
    }

    // ----- Closing ------------------------------------------------------------

    private void confirmClose(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (!session.dirty()) {
            closeEditor(ref, store, null);
            return;
        }
        ConfirmActionPage.Spec spec = new ConfirmActionPage.Spec("Close without saving?", List.of(
                "&7You have unsaved changes to &f" + session.draftId() + "&7. Closing drops them; the key file keeps "
                        + "its last saved version."), "Discard and Close", true, null);
        reopen(ref, store, new ConfirmActionPage(core, player, spec,
                (r, s) -> closeEditor(r, s, "&7Closed " + session.draftId() + " without saving."),
                (r, s) -> reopen(r, s, page(view))));
    }

    private void closeEditor(Ref<EntityStore> ref, Store<EntityStore> store, String notice) {
        module.editors().end(session);
        reopen(ref, store, new KeyManagerPage(core, player, module, KeyManagerPage.State.selecting(session.draftId(), notice)));
    }

    // ----- Helpers ------------------------------------------------------------

    private boolean current() {
        return session.active() && module.editors().get(player.getUuid()).map(open -> open == session).orElse(false);
    }

    private KeyEditorPage page(PoolEditorPage.View next) {
        return new KeyEditorPage(core, player, module, session, next);
    }

    private void openLater(PoolEditorPage.View next) {
        if (current()) {
            core.platform().openPage(player, page(next));
        }
    }

    private static String blank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
