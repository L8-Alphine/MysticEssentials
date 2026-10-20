package org.hyzionstudios.mysticessentials.modules.lootbox.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.api.Permissions;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.message.MysticText;
import org.hyzionstudios.mysticessentials.modules.lootbox.LootBoxModule;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxRevision;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.OddsEngine;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.DraftLifecycle;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.DraftStore;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.LootBoxRegistry;
import org.hyzionstudios.mysticessentials.modules.lootbox.ui.editor.ConfirmActionPage;
import org.hyzionstudios.mysticessentials.modules.lootbox.ui.editor.FormFields;
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
 * The staff Manager (P2-C): every pool with its lifecycle status, a header
 * search, a status filter, and the actions for the selected pool. New Pool and
 * Clone write draft files through the registry's {@link DraftStore}; Publish and
 * Archive ask first.
 *
 * <p>Every press checks its permission node again, because a button being
 * visible is not permission. The page keeps its search, filter, selection and
 * half-filled form in {@link State}; every event carries the inputs' current
 * values, so a rebuild never drops what was typed. Handlers reopen the page
 * synchronously, so the client never waits on the loading overlay. Draft
 * file work runs off the world thread, and its result reopens the Manager with
 * a notice.</p>
 */
public final class LootBoxManagerPage extends MysticPage {

    static final String MANAGER_UI = "MysticEssentials/LootBoxManager.ui";
    static final String MANAGER_ROW_UI = "MysticEssentials/LootBoxManagerRow.ui";

    /** Status filter values; {@code ALL} keeps every pool. */
    static final List<String> FILTERS = List.of("ALL", "DRAFT", "INVALID", "READY", "PUBLISHED", "DISABLED");

    private static final FormFields.Field SEARCH = FormFields.Field.text("search", "#SearchInput", "Search");
    private static final FormFields.Field FILTER = FormFields.Field.text("filter", "#StatusFilter", "Status");
    private static final FormFields.Field NEW_ID = FormFields.Field.text("newId", "#NewIdInput", "Pool id");
    private static final FormFields.Field NEW_NAME = FormFields.Field.text("newName", "#NewNameInput", "Display name");
    private static final FormFields INPUTS = FormFields.of(SEARCH, FILTER, NEW_ID, NEW_NAME);

    private static final int MAX_NAME_LENGTH = 64;

    /** What the page shows besides the pools themselves. */
    public record State(String query, String filter, String selected, Form form, String notice) {

        public static State initial() {
            return new State("", "ALL", null, null, null);
        }

        /** The Manager with {@code id} selected and an optional notice; where an editor returns to. */
        public static State selecting(String id, String notice) {
            return new State("", "ALL", id, null, notice);
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

        /** The search, filter and form text from an event, keeping the selection and the form's mode. */
        State withInputs(Map<String, String> inputs) {
            String nextFilter = inputs.getOrDefault(FILTER.key(), filter);
            Form nextForm = form == null ? null : new Form(form.cloneOf(),
                    inputs.getOrDefault(NEW_ID.key(), form.newId()), inputs.getOrDefault(NEW_NAME.key(), form.newName()));
            return new State(inputs.getOrDefault(SEARCH.key(), query),
                    FILTERS.contains(nextFilter) ? nextFilter : "ALL", selected, nextForm, null);
        }
    }

    /** The New Pool form; {@code cloneOf} is set when it copies an existing draft. */
    public record Form(String cloneOf, String newId, String newName) {
    }

    /** One pool as the list shows it. */
    private record Entry(String id, String name, LootBoxDraft draft, LootBoxRevision live, boolean disabled,
            DraftLifecycle.Status status, String filterKey, long crates) {
    }

    private final LootBoxModule module;
    private final State state;

    public LootBoxManagerPage(MysticCore core, PlayerRef player, LootBoxModule module, State state) {
        super(core, player, CustomPageLifetime.CanDismiss);
        this.module = module;
        this.state = state == null ? State.initial() : state;
    }

    // ----- Build --------------------------------------------------------------

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event, Store<EntityStore> store) {
        cmd.append(MANAGER_UI);
        LootBoxRegistry registry = module.registry();
        List<Entry> all = entries(registry);
        List<Entry> shown = all.stream()
                .filter(entry -> "ALL".equals(state.filter()) || state.filter().equals(entry.filterKey()))
                .filter(entry -> matchesSearch(state.query(), entry.id(), entry.name()))
                .toList();
        Entry selected = shown.stream().filter(entry -> entry.id().equals(state.selected())).findFirst()
                .orElse(shown.isEmpty() ? null : shown.get(0));

        int errors = registry.draftErrors().size();
        cmd.set("#HeaderInfo.TextSpans", uiText("#HeaderInfo.TextSpans", (shown.size() == all.size()
                ? all.size() + " pools" : shown.size() + " of " + all.size() + " pools") + " · "
                + registry.keys().size() + " published keys · " + module.crates().list().size() + " crates"
                + (errors > 0 ? " · " + errors + " draft file problem(s), see the server log" : "")));

        cmd.set("#SearchInput.Value", state.query());
        cmd.set("#StatusFilter.Entries", List.of(
                entry("All pools", "ALL"), entry("Draft", "DRAFT"), entry("Invalid", "INVALID"),
                entry("Ready to publish", "READY"), entry("Published", "PUBLISHED"), entry("Disabled", "DISABLED")));
        cmd.set("#StatusFilter.Value", state.filter());
        event.addEventBinding(CustomUIEventBindingType.ValueChanged, "#SearchInput", INPUTS.event("search"));
        event.addEventBinding(CustomUIEventBindingType.ValueChanged, "#StatusFilter", INPUTS.event("filter"));

        boolean canEdit = player.hasPermission(Permissions.LOOTBOX_EDIT);
        cmd.set("#CreateButton.Visible", canEdit && state.form() == null);
        event.addEventBinding(CustomUIEventBindingType.Activating, "#CreateButton", INPUTS.event("newPool"));
        cmd.set("#KeysButton.Visible", canEdit || player.hasPermission(Permissions.LOOTBOX_KEYS)
                || player.hasPermission(Permissions.LOOTBOX_PUBLISH));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#KeysButton", INPUTS.event("keys"));
        buildForm(cmd, event);

        boolean hasNotice = state.notice() != null && !state.notice().isBlank();
        cmd.set("#Notice.Visible", hasNotice);
        if (hasNotice) {
            cmd.set("#Notice.TextSpans", uiText("#Notice.TextSpans", state.notice()));
        }

        cmd.set("#ListEmpty.Visible", shown.isEmpty());
        for (int i = 0; i < shown.size(); i++) {
            Entry entry = shown.get(i);
            String row = "#PoolList[" + i + "]";
            cmd.append("#PoolList", MANAGER_ROW_UI);
            cmd.set(row + " #Selected.Visible", entry == selected);
            String icon = iconOf(entry);
            if (icon != null) {
                cmd.set(row + " #Icon.ItemId", icon);
            }
            cmd.set(row + " #Name.TextSpans", uiText(row + " #Name.TextSpans", entry.name() + " &8(" + entry.id() + ")"));
            cmd.set(row + " #Meta.TextSpans", uiText(row + " #Meta.TextSpans", meta(entry)));
            cmd.set(row + " #Status.TextSpans", uiText(row + " #Status.TextSpans", chip(entry)));
            event.addEventBinding(CustomUIEventBindingType.Activating, row,
                    INPUTS.event("select").put("id", entry.id()));
        }

        buildSelection(cmd, event, selected);
        event.addEventBinding(CustomUIEventBindingType.Activating, "#ReloadButton", INPUTS.event("reload"));
        cmd.set("#TemplatesButton.Visible", canEdit);
        cmd.set("#AuditButton.Visible", player.hasPermission(Permissions.LOOTBOX_AUDIT));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#TemplatesButton", INPUTS.event("templates"));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#AuditButton", INPUTS.event("audit"));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton", new EventData().put("action", "close"));
    }

    private void buildForm(UICommandBuilder cmd, UIEventBuilder event) {
        Form form = state.form();
        cmd.set("#CreatePanel.Visible", form != null);
        if (form == null) {
            return;
        }
        boolean cloning = form.cloneOf() != null;
        cmd.set("#FormTitle.TextSpans", uiText("#FormTitle.TextSpans",
                cloning ? "Clone " + form.cloneOf() : "New pool"));
        cmd.set("#NewNameColumn.Visible", !cloning);
        cmd.set("#NewIdInput.Value", form.newId() == null ? "" : form.newId());
        cmd.set("#NewNameInput.Value", form.newName() == null ? "" : form.newName());
        cmd.set("#FormHint.TextSpans", uiText("#FormHint.TextSpans", cloning
                ? "Copies the whole draft file under the new id. Nothing is published."
                : "3-48 characters: a-z, 0-9 and _. Writes an empty draft; nothing is published."));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#FormConfirmButton", INPUTS.event("formConfirm"));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#FormCancelButton", INPUTS.event("formCancel"));
    }

    private void buildSelection(UICommandBuilder cmd, UIEventBuilder event, Entry selected) {
        cmd.set("#SelectionBar.Visible", selected != null);
        if (selected == null) {
            return;
        }
        cmd.set("#SelectedName.TextSpans", uiText("#SelectedName.TextSpans",
                selected.name() + " &8(" + selected.id() + ")"));
        cmd.set("#SelectedMeta.TextSpans", uiText("#SelectedMeta.TextSpans", selectionMeta(selected)));
        DraftLifecycle.State lifecycle = selected.status() == null ? null : selected.status().state();
        boolean published = selected.live() != null;
        cmd.set("#PublishButton.Visible", lifecycle == DraftLifecycle.State.READY
                && player.hasPermission(Permissions.LOOTBOX_PUBLISH));
        cmd.set("#SimulateButton.Visible", published && player.hasPermission(Permissions.LOOTBOX_SIMULATE));
        boolean admin = player.hasPermission(Permissions.LOOTBOX_ADMIN);
        cmd.set("#ClosePoolButton.Visible", published && !selected.disabled() && admin);
        cmd.set("#ReopenPoolButton.Visible", published && selected.disabled() && admin);
        boolean editable = selected.draft() != null && player.hasPermission(Permissions.LOOTBOX_EDIT);
        cmd.set("#EditButton.Visible", editable);
        cmd.set("#CloneButton.Visible", editable && state.form() == null);
        cmd.set("#ArchiveButton.Visible", editable);
        bind(event, "#EditButton", "edit", selected.id());
        bind(event, "#PublishButton", "publish", selected.id());
        bind(event, "#SimulateButton", "simulate", selected.id());
        bind(event, "#ClosePoolButton", "closePool", selected.id());
        bind(event, "#ReopenPoolButton", "reopenPool", selected.id());
        bind(event, "#CloneButton", "clone", selected.id());
        bind(event, "#ArchiveButton", "archive", selected.id());
    }

    private static void bind(UIEventBuilder event, String button, String action, String id) {
        event.addEventBinding(CustomUIEventBindingType.Activating, button, INPUTS.event(action).put("id", id));
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
            case "newPool" -> {
                if (allowed(Permissions.LOOTBOX_EDIT)) {
                    reopen(ref, store, page(next.withForm(new Form(null, "", ""))));
                }
            }
            case "clone" -> {
                if (allowed(Permissions.LOOTBOX_EDIT)) {
                    reopen(ref, store, page(next.withSelected(id).withForm(new Form(id, copyId(id), ""))));
                }
            }
            case "edit" -> {
                if (allowed(Permissions.LOOTBOX_EDIT)) {
                    reopen(ref, store, page(next.withSelected(id).withNotice("&7Opening &f" + id + "&7...")));
                    module.ui().openPoolEditor(player, id, null);
                }
            }
            case "keys" -> {
                if (player.hasPermission(Permissions.LOOTBOX_EDIT) || player.hasPermission(Permissions.LOOTBOX_KEYS)
                        || player.hasPermission(Permissions.LOOTBOX_PUBLISH)) {
                    reopen(ref, store, new KeyManagerPage(core, player, module, KeyManagerPage.State.initial()));
                } else {
                    allowed(Permissions.LOOTBOX_KEYS);
                }
            }
            case "formCancel" -> reopen(ref, store, page(next.withForm(null)));
            case "formConfirm" -> {
                if (allowed(Permissions.LOOTBOX_EDIT)) {
                    submitForm(ref, store, next);
                }
            }
            case "publish" -> {
                if (allowed(Permissions.LOOTBOX_PUBLISH)) {
                    confirmPublish(ref, store, next.withSelected(id), id);
                }
            }
            case "simulate" -> {
                if (allowed(Permissions.LOOTBOX_SIMULATE)) {
                    SimulationPage.openAndRun(core, player, module, ref, store, id);
                }
            }
            case "closePool", "reopenPool" -> {
                if (allowed(Permissions.LOOTBOX_ADMIN)) {
                    toggle(ref, store, next.withSelected(id), id, "closePool".equals(action));
                }
            }
            case "archive" -> {
                if (allowed(Permissions.LOOTBOX_EDIT)) {
                    confirmArchive(ref, store, next.withSelected(id), id);
                }
            }
            case "reload" -> {
                if (allowed(Permissions.LOOTBOX_ADMIN)) {
                    module.onReload();
                    reopen(ref, store, page(next.withNotice("&7Reloaded &f" + module.registry().drafts().size()
                            + " &7pool and &f" + module.registry().keyDrafts().size() + " &7key draft(s) from disk.")));
                }
            }
            case "templates" -> {
                if (allowed(Permissions.LOOTBOX_EDIT)) {
                    TemplateLibraryPage.open(core, player, module, ref, store);
                }
            }
            case "audit" -> {
                if (allowed(Permissions.LOOTBOX_AUDIT)) {
                    AuditHistoryPage.open(core, player, module, ref, store);
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
        String newId = FormFields.id(NEW_ID, form.newId() == null ? "" : form.newId().trim().toLowerCase(Locale.ROOT),
                DraftValidator.LOOTBOX_ID, "3-48 characters: a-z, 0-9 and _").collect(problems);
        String newName = form.cloneOf() != null ? null
                : FormFields.nonBlank(NEW_NAME, form.newName(), MAX_NAME_LENGTH).collect(problems);
        if (!problems.isEmpty()) {
            reopen(ref, store, page(next.withNotice("&c" + problems.get(0).message())));
            return;
        }
        String actor = player.getUsername();
        DraftStore<LootBoxDraft> drafts = module.registry().poolStore();
        CompletableFuture<DraftStore.Result> work = form.cloneOf() != null
                ? drafts.clone(form.cloneOf(), newId, actor)
                : drafts.create(newId, newName, actor);
        String verb = form.cloneOf() != null ? "Cloning " + form.cloneOf() + " as " : "Creating ";
        reopen(ref, store, page(next.withNotice("&7" + verb + "&f" + newId + "&7...")));
        work.whenComplete((result, error) -> {
            if (error != null || !(result instanceof DraftStore.Saved)) {
                String reason = error != null ? "could not write the draft file (see the server log)"
                        : result instanceof DraftStore.Refused refused ? refused.reason()
                        : "a draft with that id appeared meanwhile";
                if (error != null) {
                    core.log(Level.WARNING, "[lootbox] " + verb + newId + " failed: " + error);
                }
                openLater(next.withNotice("&cCould not create &f" + newId + "&c: " + reason + "."));
                return;
            }
            if (form.cloneOf() != null) {
                openLater(next.withForm(null).withSelected(newId).withNotice(
                        "&aCloned &f" + form.cloneOf() + " &aas &f" + newId + "&a. Nothing is published yet."));
            } else {
                // A new pool goes straight into the editor; Close there comes back here.
                module.ui().openPoolEditor(player, newId, "&aCreated &f" + newId
                        + "&a. Link a key on Keys & Rules; add rewards in &f" + draftFile(newId) + "&a.");
            }
        });
    }

    private void confirmPublish(Ref<EntityStore> ref, Store<EntityStore> store, State next, String id) {
        LootBoxRegistry registry = module.registry();
        LootBoxDraft draft = registry.drafts().get(id);
        if (draft == null) {
            reopen(ref, store, page(next.withNotice("&c" + id + " has no draft file to publish.")));
            return;
        }
        LootBoxRevision live = registry.active(id).orElse(null);
        List<String> lines = new ArrayList<>();
        lines.add("&7Freezes &f" + draftFile(id) + " &7as a new, unchangeable revision"
                + (live == null ? " &7(the first one)." : " &7after &fr" + live.revision + "&7."));
        lines.add("&7" + draft.rewards.size() + " reward(s), " + draft.mode + " mode.");
        lines.add("&7Players see the new odds from their next preview; openings already made keep theirs.");
        if (draft.rewards.stream().anyMatch(r -> "COMMAND".equalsIgnoreCase(r.type))
                && !player.hasPermission(Permissions.LOOTBOX_REWARDS_COMMAND)) {
            lines.add("&eIt has COMMAND rewards, which you may not publish.");
        }
        ConfirmActionPage.Spec spec = new ConfirmActionPage.Spec("Publish " + name(draft, live, id) + "?", lines,
                "Publish", false, Permissions.LOOTBOX_PUBLISH);
        reopen(ref, store, new ConfirmActionPage(core, player, spec, (r, s) -> {
            reopen(r, s, page(next.withNotice("&7Publishing &f" + id + "&7...")));
            registry.publish(id, player.getUsername(), player.hasPermission(Permissions.LOOTBOX_REWARDS_COMMAND))
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            core.log(Level.WARNING, "[lootbox] publishing " + id + " failed: " + error);
                            openLater(next.withNotice("&cPublishing &f" + id + " &cfailed; see the server log."));
                            return;
                        }
                        module.ui().sendPublishResult(player, id, result);
                        openLater(next.withNotice(!result.ok()
                                ? "&cNot published: " + String.join("; ", result.problems())
                                : result.unchanged() ? "&7" + id + " is unchanged since revision " + result.revision() + "."
                                : "&aPublished &f" + id + " &arevision &f" + result.revision() + " &7(odds "
                                        + result.hash() + ")."));
                    });
        }, (r, s) -> reopen(r, s, page(next))));
    }

    private void confirmArchive(Ref<EntityStore> ref, Store<EntityStore> store, State next, String id) {
        LootBoxRegistry registry = module.registry();
        LootBoxDraft draft = registry.drafts().get(id);
        if (draft == null) {
            reopen(ref, store, page(next.withNotice("&c" + id + " has no draft file to archive.")));
            return;
        }
        LootBoxRevision live = registry.active(id).orElse(null);
        long crates = module.crates().list().stream().filter(c -> id.equals(c.lootboxId)).count();
        List<String> lines = new ArrayList<>();
        lines.add("&7Moves &f" + draftFile(id) + " &7to &flootboxes/_archive/&7. Nothing is deleted; move the file"
                + " back to restore it.");
        lines.add(live == null ? "&7It was never published."
                : "&eRevision r" + live.revision + " stays live" + (registry.isDisabled(id) ? " (closed)."
                        : "; close the pool too if players should stop opening it."));
        if (crates > 0) {
            lines.add("&e" + crates + " crate(s) stay bound to it.");
        }
        ConfirmActionPage.Spec spec = new ConfirmActionPage.Spec("Archive " + name(draft, live, id) + "?", lines,
                "Archive", true, Permissions.LOOTBOX_EDIT);
        reopen(ref, store, new ConfirmActionPage(core, player, spec, (r, s) -> {
            reopen(r, s, page(next.withNotice("&7Archiving &f" + id + "&7...")));
            registry.poolStore().archive(id, player.getUsername()).whenComplete((result, error) -> {
                if (error != null || !(result instanceof DraftStore.Archived archived)) {
                    String reason = error != null ? "see the server log"
                            : result instanceof DraftStore.Refused refused ? refused.reason() : "unknown";
                    if (error != null) {
                        core.log(Level.WARNING, "[lootbox] archiving " + id + " failed: " + error);
                    }
                    openLater(next.withNotice("&cCould not archive &f" + id + "&c: " + reason + "."));
                    return;
                }
                openLater(next.withSelected(null).withNotice("&7Archived &f" + id + " &7to &f_archive/"
                        + archived.path().getFileName() + "&7."));
            });
        }, (r, s) -> reopen(r, s, page(next))));
    }

    private void toggle(Ref<EntityStore> ref, Store<EntityStore> store, State next, String id, boolean close) {
        reopen(ref, store, page(next.withNotice("&7" + (close ? "Closing " : "Reopening ") + "&f" + id + "&7...")));
        module.registry().setDisabled(id, close, player.getUsername()).whenComplete((changed, error) -> {
            if (error != null) {
                core.log(Level.WARNING, "[lootbox] toggling " + id + " failed: " + error);
            }
            openLater(next.withNotice(error != null ? "&cCould not change &f" + id + "&c; see the server log."
                    : !Boolean.TRUE.equals(changed) ? "&c" + id + " has never been published."
                    : close ? "&e" + id + " is closed. Previews still show its odds; nobody can open it."
                    : "&a" + id + " is open again."));
        });
    }

    // ----- Helpers ------------------------------------------------------------

    private List<Entry> entries(LootBoxRegistry registry) {
        Set<String> ids = new TreeSet<>(registry.drafts().keySet());
        registry.publishedPools().forEach(revision -> ids.add(revision.lootboxId));
        List<Entry> entries = new ArrayList<>(ids.size());
        for (String id : ids) {
            LootBoxDraft draft = registry.drafts().get(id);
            LootBoxRevision live = registry.active(id).orElse(null);
            boolean disabled = registry.isDisabled(id);
            DraftLifecycle.Status status = registry.status(id).orElse(null);
            String filterKey = status != null ? status.state().name()
                    : disabled ? DraftLifecycle.State.DISABLED.name() : DraftLifecycle.State.PUBLISHED.name();
            long crates = module.crates().list().stream().filter(c -> id.equals(c.lootboxId)).count();
            entries.add(new Entry(id, name(draft, live, id), draft, live, disabled, status, filterKey, crates));
        }
        return entries;
    }

    private static String name(LootBoxDraft draft, LootBoxRevision live, String id) {
        String raw = draft != null && draft.displayName != null && !draft.displayName.isBlank() ? draft.displayName
                : live != null ? live.spec.displayName : id;
        return MysticText.stripMarkup(raw);
    }

    private static String iconOf(Entry entry) {
        LootBoxDraft spec = entry.draft() != null ? entry.draft() : entry.live() != null ? entry.live().spec : null;
        if (spec == null) {
            return null;
        }
        if (spec.icon != null && !spec.icon.isBlank() && ItemCatalog.exists(spec.icon)) {
            return spec.icon;
        }
        for (LootBoxDraft.RewardSpec reward : spec.rewards) {
            if (reward.item != null && reward.item.itemId != null && ItemCatalog.exists(reward.item.itemId)) {
                return reward.item.itemId;
            }
        }
        return null;
    }

    private static String meta(Entry entry) {
        return (entry.draft() == null ? "no draft file" : entry.draft().rewards.size() + " rewards in draft") + " · "
                + (entry.live() == null ? "never published" : "live revision " + entry.live().revision) + " · "
                + entry.crates() + " crate(s)";
    }

    /** The coloured status label: what the pool's draft is, and whether players can open it. */
    private static String chip(Entry entry) {
        if (entry.status() == null) {
            return entry.disabled() ? "&eDisabled &8· no draft" : "&aPublished &8· no draft";
        }
        return switch (entry.status().state()) {
            case DRAFT -> "&7Draft";
            case INVALID -> "&c" + entry.status().label().charAt(0)
                    + entry.status().label().substring(1).toLowerCase(Locale.ROOT);
            case READY -> entry.live() == null ? "&6Ready to publish" : "&6Changed &8· ready";
            case PUBLISHED -> "&aPublished";
            case DISABLED -> "&eDisabled";
            case ARCHIVED -> "&8Archived";
        };
    }

    private String selectionMeta(Entry entry) {
        StringBuilder text = new StringBuilder();
        if (entry.live() != null) {
            text.append("&7Live r").append(entry.live().revision).append(" · odds ")
                    .append(OddsEngine.table(entry.live(), module.opening().policyProfile()).oddsId());
        } else {
            text.append("&7Never published");
        }
        DraftLifecycle.Status status = entry.status();
        if (status != null && !status.problems().isEmpty()
                && (status.state() == DraftLifecycle.State.INVALID || status.state() == DraftLifecycle.State.DRAFT)) {
            text.append(" &8· &c").append(status.problems().get(0));
            if (status.problems().size() > 1) {
                text.append(" &8(+").append(status.problems().size() - 1).append(" more)");
            }
        } else if (entry.draft() == null) {
            text.append(" &8· the draft file was archived or removed");
        }
        return text.toString();
    }

    private static String copyId(String id) {
        String base = id.length() > 43 ? id.substring(0, 43) : id;
        return base + "_copy";
    }

    private static String draftFile(String id) {
        return "lootboxes/" + id + ".json";
    }

    private static DropdownEntryInfo entry(String label, String value) {
        return new DropdownEntryInfo(LocalizableString.fromString(label), value);
    }

    private LootBoxManagerPage page(State next) {
        return new LootBoxManagerPage(core, player, module, next);
    }

    /** Reopens the Manager from a background completion; {@code openPage} hops to the player's world thread. */
    private void openLater(State next) {
        core.platform().openPage(player, page(next));
    }

    /** Re-checked on every press; the button being visible is not permission. */
    private boolean allowed(String node) {
        if (player.hasPermission(node)) {
            return true;
        }
        core.getMessageService().sendKey(player, "no-permission");
        return false;
    }
}
