package org.hyzionstudios.mysticessentials.modules.lootbox.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.api.Permissions;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.message.MysticText;
import org.hyzionstudios.mysticessentials.modules.lootbox.LootBoxModule;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.KeyDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxRevision;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.DraftLifecycle;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.DraftStore;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.LootBoxRegistry;
import org.hyzionstudios.mysticessentials.modules.lootbox.ui.editor.ConfirmActionPage;
import org.hyzionstudios.mysticessentials.modules.lootbox.ui.editor.FormFields;
import org.hyzionstudios.mysticessentials.modules.lootbox.ui.editor.KeyDiff;
import org.hyzionstudios.mysticessentials.modules.lootbox.validate.DraftValidator;
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
 * The Key Manager (P2-F1): every key definition with its draft status, a header
 * search, a status filter, and the actions for the selected key. Reached from
 * the pool Manager's Keys button. New Key and Clone write key drafts through
 * the registry's key {@link DraftStore}; Publish and Archive ask first. It works
 * like {@link LootBoxManagerPage}: every press re-checks its node, handlers
 * reopen synchronously, and file work reopens the page with a notice.
 */
public final class KeyManagerPage extends MysticPage {

    static final String KEYS_UI = "MysticEssentials/LootBoxKeyManager.ui";
    static final List<String> FILTERS = List.of("ALL", "DRAFT", "INVALID", "READY", "PUBLISHED");

    private static final FormFields.Field SEARCH = FormFields.Field.text("search", "#SearchInput", "Search");
    private static final FormFields.Field FILTER = FormFields.Field.text("filter", "#StatusFilter", "Status");
    private static final FormFields.Field NEW_ID = FormFields.Field.text("newId", "#NewIdInput", "Key id");
    private static final FormFields.Field NEW_NAME = FormFields.Field.text("newName", "#NewNameInput", "Item name");
    private static final FormFields INPUTS = FormFields.of(SEARCH, FILTER, NEW_ID, NEW_NAME);
    private static final int MAX_NAME_LENGTH = 96;

    /** What the page shows besides the keys themselves. */
    public record State(String query, String filter, String selected, Form form, String notice) {

        public static State initial() {
            return new State("", "ALL", null, null, null);
        }

        /** The Key Manager with {@code keyId} selected; where the key editor returns to. */
        public static State selecting(String keyId, String notice) {
            return new State("", "ALL", keyId, null, notice);
        }

        State withSelected(String id) {
            return new State(query, filter, id, form, notice);
        }

        State withForm(Form next) {
            return new State(query, filter, selected, next, notice);
        }

        State withNotice(String text) {
            return new State(query, filter, selected, form, text);
        }

        State withInputs(Map<String, String> inputs) {
            String nextFilter = inputs.getOrDefault(FILTER.key(), filter);
            Form nextForm = form == null ? null : new Form(form.cloneOf(),
                    inputs.getOrDefault(NEW_ID.key(), form.newId()), inputs.getOrDefault(NEW_NAME.key(), form.newName()));
            return new State(inputs.getOrDefault(SEARCH.key(), query),
                    FILTERS.contains(nextFilter) ? nextFilter : "ALL", selected, nextForm, null);
        }
    }

    /** The New Key form; {@code cloneOf} is set when it copies an existing draft. */
    public record Form(String cloneOf, String newId, String newName) {
    }

    private record Entry(String id, String name, KeyDraft draft, KeyDraft.Published live, DraftLifecycle.Status status,
            String filterKey, List<String> opens) {
    }

    private final LootBoxModule module;
    private final State state;

    public KeyManagerPage(MysticCore core, PlayerRef player, LootBoxModule module, State state) {
        super(core, player, CustomPageLifetime.CanDismiss);
        this.module = module;
        this.state = state == null ? State.initial() : state;
    }

    // ----- Build --------------------------------------------------------------

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event, Store<EntityStore> store) {
        cmd.append(KEYS_UI);
        LootBoxRegistry registry = module.registry();
        List<Entry> all = entries(registry);
        List<Entry> shown = all.stream()
                .filter(entry -> "ALL".equals(state.filter()) || state.filter().equals(entry.filterKey()))
                .filter(entry -> matchesSearch(state.query(), entry.id(), entry.name()))
                .toList();
        Entry selected = shown.stream().filter(entry -> entry.id().equals(state.selected())).findFirst()
                .orElse(shown.isEmpty() ? null : shown.get(0));

        long published = all.stream().filter(entry -> entry.live() != null).count();
        cmd.set("#HeaderInfo.TextSpans", uiText("#HeaderInfo.TextSpans", (shown.size() == all.size()
                ? all.size() + " keys" : shown.size() + " of " + all.size() + " keys") + " · " + published + " published"));
        cmd.set("#SearchInput.Value", state.query());
        cmd.set("#StatusFilter.Entries", List.of(entry("All keys", "ALL"), entry("Draft", "DRAFT"),
                entry("Invalid", "INVALID"), entry("Ready to publish", "READY"), entry("Published", "PUBLISHED")));
        cmd.set("#StatusFilter.Value", state.filter());
        event.addEventBinding(CustomUIEventBindingType.ValueChanged, "#SearchInput", INPUTS.event("search"));
        event.addEventBinding(CustomUIEventBindingType.ValueChanged, "#StatusFilter", INPUTS.event("filter"));

        boolean canEdit = player.hasPermission(Permissions.LOOTBOX_EDIT);
        cmd.set("#CreateButton.Visible", canEdit && state.form() == null);
        event.addEventBinding(CustomUIEventBindingType.Activating, "#CreateButton", INPUTS.event("newKey"));
        buildForm(cmd, event);

        boolean hasNotice = state.notice() != null && !state.notice().isBlank();
        cmd.set("#Notice.Visible", hasNotice);
        if (hasNotice) {
            cmd.set("#Notice.TextSpans", uiText("#Notice.TextSpans", state.notice()));
        }

        cmd.set("#ListEmpty.Visible", shown.isEmpty());
        for (int i = 0; i < shown.size(); i++) {
            Entry entry = shown.get(i);
            String row = "#KeyList[" + i + "]";
            cmd.append("#KeyList", LootBoxManagerPage.MANAGER_ROW_UI);
            cmd.set(row + " #Selected.Visible", entry == selected);
            String base = baseItem(entry);
            if (base != null && ItemCatalog.exists(base)) {
                cmd.set(row + " #Icon.ItemId", base);
            }
            cmd.set(row + " #Name.TextSpans", uiText(row + " #Name.TextSpans", entry.name() + " &8(" + entry.id() + ")"));
            cmd.set(row + " #Meta.TextSpans", uiText(row + " #Meta.TextSpans", meta(entry)));
            cmd.set(row + " #Status.TextSpans", uiText(row + " #Status.TextSpans", chip(entry)));
            event.addEventBinding(CustomUIEventBindingType.Activating, row, INPUTS.event("select").put("id", entry.id()));
        }

        cmd.set("#SelectionBar.Visible", selected != null);
        if (selected != null) {
            cmd.set("#SelectedName.TextSpans", uiText("#SelectedName.TextSpans",
                    selected.name() + " &8(" + selected.id() + ")"));
            cmd.set("#SelectedMeta.TextSpans", uiText("#SelectedMeta.TextSpans", selectionMeta(selected)));
            boolean editable = selected.draft() != null && canEdit;
            cmd.set("#EditButton.Visible", editable);
            cmd.set("#PublishButton.Visible", selected.status() != null
                    && selected.status().state() == DraftLifecycle.State.READY
                    && player.hasPermission(Permissions.LOOTBOX_PUBLISH));
            cmd.set("#CloneButton.Visible", editable && state.form() == null);
            cmd.set("#ArchiveButton.Visible", editable);
            cmd.set("#GrantsButton.Visible", selected.live() != null && player.hasPermission(Permissions.LOOTBOX_KEYS));
            for (String[] button : new String[][] {{"#EditButton", "edit"}, {"#PublishButton", "publish"},
                    {"#CloneButton", "clone"}, {"#ArchiveButton", "archive"}, {"#GrantsButton", "grants"}}) {
                event.addEventBinding(CustomUIEventBindingType.Activating, button[0],
                        INPUTS.event(button[1]).put("id", selected.id()));
            }
        }
        event.addEventBinding(CustomUIEventBindingType.Activating, "#PoolsButton", INPUTS.event("pools"));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#ReloadButton", INPUTS.event("reload"));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton", new EventData().put("action", "close"));
    }

    private void buildForm(UICommandBuilder cmd, UIEventBuilder event) {
        Form form = state.form();
        cmd.set("#CreatePanel.Visible", form != null);
        if (form == null) {
            return;
        }
        boolean cloning = form.cloneOf() != null;
        cmd.set("#FormTitle.TextSpans", uiText("#FormTitle.TextSpans", cloning ? "Clone " + form.cloneOf() : "New key"));
        cmd.set("#NewNameColumn.Visible", !cloning);
        cmd.set("#NewIdInput.Value", form.newId() == null ? "" : form.newId());
        cmd.set("#NewNameInput.Value", form.newName() == null ? "" : form.newName());
        cmd.set("#FormHint.TextSpans", uiText("#FormHint.TextSpans", cloning
                ? "Copies the whole key draft under the new id. Nothing is published."
                : "namespace:name with a-z, 0-9 and _; a bare name gets mystic:. Nothing is published."));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#FormConfirmButton", INPUTS.event("formConfirm"));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#FormCancelButton", INPUTS.event("formCancel"));
    }

    // ----- Events -------------------------------------------------------------

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
        JsonObject payload = parse(data);
        String action = string(payload, "action");
        if ("close".equals(action)) {
            close(ref, store);
            return;
        }
        State next = state.withInputs(INPUTS.read(payload));
        String id = field(payload, "id");
        switch (action) {
            case "search", "filter" -> reopen(ref, store, page(next));
            case "select" -> reopen(ref, store, page(next.withSelected(id)));
            case "newKey" -> {
                if (allowed(Permissions.LOOTBOX_EDIT)) {
                    reopen(ref, store, page(next.withForm(new Form(null, "", ""))));
                }
            }
            case "clone" -> {
                if (allowed(Permissions.LOOTBOX_EDIT)) {
                    reopen(ref, store, page(next.withSelected(id).withForm(new Form(id, id + "_copy", ""))));
                }
            }
            case "formCancel" -> reopen(ref, store, page(next.withForm(null)));
            case "formConfirm" -> {
                if (allowed(Permissions.LOOTBOX_EDIT)) {
                    submitForm(ref, store, next);
                }
            }
            case "edit" -> {
                if (allowed(Permissions.LOOTBOX_EDIT)) {
                    reopen(ref, store, page(next.withSelected(id).withNotice("&7Opening &f" + id + "&7...")));
                    module.ui().openKeyEditor(player, id, null);
                }
            }
            case "publish" -> {
                if (allowed(Permissions.LOOTBOX_PUBLISH)) {
                    confirmPublish(ref, store, next.withSelected(id), id);
                }
            }
            case "archive" -> {
                if (allowed(Permissions.LOOTBOX_EDIT)) {
                    confirmArchive(ref, store, next.withSelected(id), id);
                }
            }
            case "grants" -> {
                if (allowed(Permissions.LOOTBOX_KEYS)) {
                    if (module.registry().key(id).isEmpty()) {
                        reopen(ref, store, page(next.withSelected(id).withNotice("&c" + id
                                + " is not published; publish it before giving it out.")));
                    } else {
                        KeyGrantsPage.open(core, player, module, ref, store, id);
                    }
                }
            }
            case "pools" -> reopen(ref, store, new LootBoxManagerPage(core, player, module, LootBoxManagerPage.State.initial()));
            case "reload" -> {
                if (allowed(Permissions.LOOTBOX_ADMIN)) {
                    module.onReload();
                    reopen(ref, store, page(next.withNotice("&7Reloaded &f" + module.registry().keyDrafts().size()
                            + " &7key draft(s) from disk.")));
                }
            }
            default -> reopen(ref, store, page(next));
        }
    }

    private void submitForm(Ref<EntityStore> ref, Store<EntityStore> store, State next) {
        Form form = next.form();
        if (form == null) {
            reopen(ref, store, page(next));
            return;
        }
        List<FormFields.Problem> problems = new ArrayList<>();
        String raw = form.newId() == null ? "" : form.newId().trim();
        String newId = FormFields.id(NEW_ID, raw.isEmpty() ? "" : DraftValidator.normaliseKeyId(raw), DraftValidator.KEY_ID,
                "namespace:name with a-z, 0-9 and _").collect(problems);
        String newName = form.cloneOf() != null ? null
                : FormFields.nonBlank(NEW_NAME, form.newName(), MAX_NAME_LENGTH).collect(problems);
        if (!problems.isEmpty()) {
            reopen(ref, store, page(next.withNotice("&c" + problems.get(0).message())));
            return;
        }
        String actor = player.getUsername();
        DraftStore<KeyDraft> keys = module.registry().keyStore();
        CompletableFuture<DraftStore.Result> work = form.cloneOf() != null
                ? keys.clone(form.cloneOf(), newId, actor) : keys.create(newId, newName, actor);
        reopen(ref, store, page(next.withNotice("&7" + (form.cloneOf() != null ? "Cloning " : "Creating ") + "&f"
                + newId + "&7...")));
        work.whenComplete((result, error) -> {
            if (error != null || !(result instanceof DraftStore.Saved)) {
                String reason = error != null ? "could not write the key file (see the server log)"
                        : result instanceof DraftStore.Refused refused ? refused.reason()
                        : "a key with that id appeared meanwhile";
                if (error != null) {
                    core.log(Level.WARNING, "[lootbox] creating key " + newId + " failed: " + error);
                }
                openLater(next.withNotice("&cCould not create &f" + newId + "&c: " + reason + "."));
                return;
            }
            if (form.cloneOf() != null) {
                openLater(next.withForm(null).withSelected(newId).withNotice(
                        "&aCloned &f" + form.cloneOf() + " &aas &f" + newId + "&a. Nothing is published yet."));
            } else {
                module.ui().openKeyEditor(player, newId, "&aCreated &f" + newId + "&a. Choose its base item, then publish it.");
            }
        });
    }

    private void confirmPublish(Ref<EntityStore> ref, Store<EntityStore> store, State next, String id) {
        KeyDraft draft = module.registry().keyDrafts().get(id);
        if (draft == null) {
            reopen(ref, store, page(next.withNotice("&c" + id + " has no draft file to publish.")));
            return;
        }
        KeyDraft.Published live = module.registry().key(id).orElse(null);
        List<String> lines = new ArrayList<>(KeyDiff.lines(draft, live == null ? null : live.spec));
        lines.add(live == null ? "&7This becomes the key's first version."
                : "&7This becomes version " + (live.version + 1) + ". Keys already given out keep their current name and "
                        + "lore; keys issued from now on get this version.");
        ConfirmActionPage.Spec spec = new ConfirmActionPage.Spec("Publish " + name(draft, live, id) + "?", lines,
                "Publish", false, Permissions.LOOTBOX_PUBLISH);
        reopen(ref, store, new ConfirmActionPage(core, player, spec, (r, s) -> {
            reopen(r, s, page(next.withNotice("&7Publishing &f" + id + "&7...")));
            module.registry().publishKey(id, player.getUsername()).whenComplete((result, error) -> {
                if (error != null) {
                    core.log(Level.WARNING, "[lootbox] publishing key " + id + " failed: " + error);
                    openLater(next.withNotice("&cPublishing &f" + id + " &cfailed; see the server log."));
                    return;
                }
                openLater(next.withNotice(!result.ok() ? "&cNot published: " + String.join("; ", result.problems())
                        : result.unchanged() ? "&7" + id + " is unchanged since version " + result.revision() + "."
                        : "&aPublished &f" + id + " &aversion &f" + result.revision() + "&a."));
            });
        }, (r, s) -> reopen(r, s, page(next))));
    }

    private void confirmArchive(Ref<EntityStore> ref, Store<EntityStore> store, State next, String id) {
        KeyDraft draft = module.registry().keyDrafts().get(id);
        if (draft == null) {
            reopen(ref, store, page(next.withNotice("&c" + id + " has no draft file to archive.")));
            return;
        }
        KeyDraft.Published live = module.registry().key(id).orElse(null);
        List<String> opens = opens(module.registry(), id);
        List<String> lines = new ArrayList<>();
        lines.add("&7Moves &f" + keyFile(id) + " &7to &fkeys/_archive/&7. Nothing is deleted; move the file back to restore it.");
        lines.add(live == null ? "&7It was never published."
                : "&eVersion " + live.version + " stays published: keys already given out keep working.");
        if (!opens.isEmpty()) {
            lines.add("&e" + opens.size() + " pool(s) link it: " + String.join(", ", opens) + ".");
        }
        ConfirmActionPage.Spec spec = new ConfirmActionPage.Spec("Archive " + name(draft, live, id) + "?", lines,
                "Archive", true, Permissions.LOOTBOX_EDIT);
        reopen(ref, store, new ConfirmActionPage(core, player, spec, (r, s) -> {
            reopen(r, s, page(next.withNotice("&7Archiving &f" + id + "&7...")));
            module.registry().keyStore().archive(id, player.getUsername()).whenComplete((result, error) -> {
                if (error != null || !(result instanceof DraftStore.Archived archived)) {
                    String reason = error != null ? "see the server log"
                            : result instanceof DraftStore.Refused refused ? refused.reason() : "unknown";
                    if (error != null) {
                        core.log(Level.WARNING, "[lootbox] archiving key " + id + " failed: " + error);
                    }
                    openLater(next.withNotice("&cCould not archive &f" + id + "&c: " + reason + "."));
                    return;
                }
                openLater(next.withSelected(null).withNotice("&7Archived &f" + id + " &7to &fkeys/_archive/"
                        + archived.path().getFileName() + "&7."));
            });
        }, (r, s) -> reopen(r, s, page(next))));
    }

    // ----- Helpers ------------------------------------------------------------

    private List<Entry> entries(LootBoxRegistry registry) {
        Set<String> ids = new TreeSet<>(registry.keyDrafts().keySet());
        registry.keys().forEach(published -> ids.add(published.spec.id));
        List<Entry> entries = new ArrayList<>(ids.size());
        for (String id : ids) {
            KeyDraft draft = registry.keyDrafts().get(id);
            KeyDraft.Published live = registry.key(id).orElse(null);
            DraftLifecycle.Status status = draft == null ? null : registry.keyStore().status(draft);
            String filterKey = status != null ? status.state().name() : DraftLifecycle.State.PUBLISHED.name();
            entries.add(new Entry(id, name(draft, live, id), draft, live, status, filterKey, opens(registry, id)));
        }
        return entries;
    }

    /** Pools whose draft or live revision this key opens. */
    public static List<String> opens(LootBoxRegistry registry, String keyId) {
        Set<String> pools = new TreeSet<>();
        for (LootBoxDraft draft : registry.drafts().values()) {
            if (draft.keys != null && draft.keys.contains(keyId)) {
                pools.add(draft.id);
            }
        }
        for (LootBoxRevision revision : registry.publishedPools()) {
            if (revision.spec.keys != null && revision.spec.keys.contains(keyId)) {
                pools.add(revision.lootboxId);
            }
        }
        return new ArrayList<>(pools);
    }

    private static String name(KeyDraft draft, KeyDraft.Published live, String id) {
        String raw = draft != null && draft.name != null && !draft.name.isBlank() ? draft.name
                : live != null && live.spec.name != null ? live.spec.name : id;
        return MysticText.stripMarkup(raw);
    }

    private static String baseItem(Entry entry) {
        KeyDraft spec = entry.draft() != null ? entry.draft() : entry.live() != null ? entry.live().spec : null;
        return spec == null || spec.baseItemId == null || spec.baseItemId.isBlank() ? null : spec.baseItemId;
    }

    private static String meta(Entry entry) {
        KeyDraft spec = entry.draft() != null ? entry.draft() : entry.live().spec;
        return (spec.baseItemId == null || spec.baseItemId.isBlank() ? "no base item" : "base " + spec.baseItemId)
                + " · " + spec.uses + (spec.uses == 1 ? " use" : " uses") + " · "
                + (entry.live() == null ? "never published" : "v" + entry.live().version) + " · opens "
                + entry.opens().size() + " pool(s)";
    }

    private static String chip(Entry entry) {
        if (entry.status() == null) {
            return "&aPublished &8· no draft";
        }
        return switch (entry.status().state()) {
            case DRAFT -> "&7Draft";
            case INVALID -> "&cInvalid (" + entry.status().problems().size() + ")";
            case READY -> entry.live() == null ? "&6Ready to publish" : "&6Changed &8· ready";
            case PUBLISHED -> "&aPublished";
            case DISABLED -> "&eDisabled";
            case ARCHIVED -> "&8Archived";
        };
    }

    private static String selectionMeta(Entry entry) {
        StringBuilder text = new StringBuilder(entry.live() == null ? "&7Never published"
                : "&7Version " + entry.live().version + " published by " + entry.live().publishedBy);
        if (entry.status() != null && !entry.status().problems().isEmpty()
                && entry.status().state() != DraftLifecycle.State.READY) {
            text.append(" &8· &c").append(entry.status().problems().get(0));
        } else if (entry.draft() == null) {
            text.append(" &8· the draft file was archived or removed");
        }
        if (!entry.opens().isEmpty()) {
            text.append(" &8· &7opens ").append(String.join(", ", entry.opens()));
        }
        return text.toString();
    }

    public static String keyFile(String id) {
        return "keys/" + id.replace(':', '.') + ".json";
    }

    private static DropdownEntryInfo entry(String label, String value) {
        return new DropdownEntryInfo(LocalizableString.fromString(label), value);
    }

    private KeyManagerPage page(State next) {
        return new KeyManagerPage(core, player, module, next);
    }

    private void openLater(State next) {
        core.platform().openPage(player, page(next));
    }

    private boolean allowed(String node) {
        if (player.hasPermission(node)) {
            return true;
        }
        core.getMessageService().sendKey(player, "no-permission");
        return false;
    }
}
