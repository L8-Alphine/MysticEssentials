package org.hyzionstudios.mysticessentials.modules.lootbox.ui;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.api.Permissions;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.message.MysticText;
import org.hyzionstudios.mysticessentials.modules.lootbox.LootBoxModule;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.CrateInstance;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.KeyDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxTemplate;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.CrateRules;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.CrateService;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.DraftLifecycle;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.DraftStore;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.TemplateLibrary;
import org.hyzionstudios.mysticessentials.modules.lootbox.ui.editor.ConfirmActionPage;
import org.hyzionstudios.mysticessentials.modules.lootbox.ui.editor.FormFields;
import org.hyzionstudios.mysticessentials.modules.lootbox.ui.editor.TemplateApply;
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
 * The Template Library (P2-H): saved key looks, reward sets and crate looks, as
 * files under {@code modules/lootbox/templates/}. Save one from a key or pool
 * draft or a placed crate, apply a key look to a key draft, insert a reward set
 * into a pool draft, apply a crate look to a crate, rename, or delete (which
 * moves the file to {@code templates/_archive/}).
 *
 * <p>A crate is live, not a draft: applying a crate look goes through
 * {@code CrateService.update} with the settings hash read when the change was
 * shown, so a crate someone saved meanwhile is refused. It also needs
 * {@code crates.manage}, like the crate editor's Save.</p>
 *
 * <p>Applying never touches an open editor: it loads the target draft from
 * disk, shows what changes, and writes it through the registry's
 * {@link DraftStore} with the hash it loaded, so a draft that changed meanwhile
 * is refused, never overwritten. An editor that still holds the old copy finds
 * the file changed on its next save. Every press needs {@code edit}, checked
 * again when it arrives; inserting COMMAND rewards also needs
 * {@code rewards.command}. File work runs off the world thread and reopens the
 * page with a notice.</p>
 */
public final class TemplateLibraryPage extends MysticPage {

    static final String TEMPLATES_UI = "MysticEssentials/LootBoxTemplates.ui";
    static final String ROW_UI = "MysticEssentials/LootBoxTemplateRow.ui";

    static final List<String> FILTERS = List.of("ALL", LootBoxTemplate.KEY_LOOK, LootBoxTemplate.REWARD_SET,
            LootBoxTemplate.CRATE_LOOK);
    private static final String RENAME = "RENAME";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private static final FormFields.Field KIND = FormFields.Field.text("kind", "#KindFilter", "Kind");
    private static final FormFields.Field TARGET = FormFields.Field.text("target", "#TargetPicker", "Target");
    private static final FormFields.Field SOURCE = FormFields.Field.text("source", "#FormSource", "From draft");
    private static final FormFields.Field NEW_ID = FormFields.Field.text("newId", "#FormIdInput", "Template id");
    private static final FormFields.Field NEW_NAME = FormFields.Field.text("newName", "#FormNameInput", "Name");
    private static final FormFields INPUTS = FormFields.of(KIND, TARGET, SOURCE, NEW_ID, NEW_NAME);

    /** One template file as listed, with the hash a rename saves against. */
    public record Entry(LootBoxTemplate template, String hash) {
    }

    /**
     * The save / rename form.
     *
     * @param mode   {@code KEY_LOOK}, {@code REWARD_SET} or {@code CRATE_LOOK} to save a new template,
     *               {@code RENAME} to rename
     * @param source the draft a new template copies
     */
    public record Form(String mode, String source, String newId, String newName) {
    }

    /**
     * What the page shows.
     *
     * @param entries the folder as last listed; {@code null} while it is being read
     * @param target  the draft chosen in the Apply picker
     */
    public record State(String filter, String selected, Form form, String target, List<Entry> entries,
            List<String> errors, String notice) {

        public static State initial() {
            return new State("ALL", null, null, null, null, List.of(), null);
        }

        State withSelected(String id) {
            return new State(filter, id, form, target, entries, errors, notice);
        }

        State withForm(Form next) {
            return new State(filter, selected, next, target, entries, errors, notice);
        }

        State withNotice(String text) {
            return new State(filter, selected, form, target, entries, errors, text);
        }

        State withListing(DraftStore.Listing<LootBoxTemplate> listing) {
            List<Entry> next = new ArrayList<>();
            listing.drafts().values().forEach(loaded -> next.add(new Entry(loaded.draft(), loaded.hash())));
            return new State(filter, selected, form, target, List.copyOf(next), listing.errors(), notice);
        }

        State withInputs(Map<String, String> inputs) {
            String nextFilter = inputs.getOrDefault(KIND.key(), filter);
            Form nextForm = form == null ? null : new Form(form.mode(), inputs.getOrDefault(SOURCE.key(), form.source()),
                    inputs.getOrDefault(NEW_ID.key(), form.newId()), inputs.getOrDefault(NEW_NAME.key(), form.newName()));
            return new State(FILTERS.contains(nextFilter) ? nextFilter : "ALL", selected, nextForm,
                    inputs.getOrDefault(TARGET.key(), target), entries, errors, null);
        }
    }

    private final LootBoxModule module;
    private final State state;

    public TemplateLibraryPage(MysticCore core, PlayerRef player, LootBoxModule module, State state) {
        super(core, player, CustomPageLifetime.CanDismiss);
        this.module = module;
        this.state = state == null ? State.initial() : state;
    }

    /** Opens the library and lists its folder. Call after checking {@code edit}. */
    static void open(MysticCore core, PlayerRef player, LootBoxModule module, Ref<EntityStore> ref,
            Store<EntityStore> store) {
        TemplateLibraryPage page = new TemplateLibraryPage(core, player, module, State.initial());
        reopen(ref, store, page);
        page.relist(State.initial(), null);
    }

    // ----- Build --------------------------------------------------------------

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event, Store<EntityStore> store) {
        cmd.append(TEMPLATES_UI);
        List<Entry> all = state.entries() == null ? List.of() : state.entries();
        List<Entry> shown = all.stream()
                .filter(entry -> "ALL".equals(state.filter()) || state.filter().equals(entry.template().kind))
                .toList();
        Entry selected = shown.stream().filter(entry -> entry.template().id.equals(state.selected())).findFirst()
                .orElse(shown.isEmpty() ? null : shown.get(0));

        long looks = count(all, LootBoxTemplate.KEY_LOOK);
        long crateLooks = count(all, LootBoxTemplate.CRATE_LOOK);
        cmd.set("#HeaderInfo.TextSpans", uiText("#HeaderInfo.TextSpans", state.entries() == null ? "Reading templates/..."
                : all.size() + " template(s) · " + looks + " key look(s) · " + count(all, LootBoxTemplate.REWARD_SET)
                        + " reward set(s) · " + crateLooks + " crate look(s)"
                        + (state.errors().isEmpty() ? "" : " · " + state.errors().size()
                                + " unreadable file(s), see the server log")));
        cmd.set("#KindFilter.Entries", List.of(entry("All templates", "ALL"), entry("Key looks", LootBoxTemplate.KEY_LOOK),
                entry("Reward sets", LootBoxTemplate.REWARD_SET), entry("Crate looks", LootBoxTemplate.CRATE_LOOK)));
        cmd.set("#KindFilter.Value", state.filter());
        event.addEventBinding(CustomUIEventBindingType.ValueChanged, "#KindFilter", INPUTS.event("filter"));
        cmd.set("#SaveKeyLookButton.Visible", state.form() == null);
        cmd.set("#SaveRewardSetButton.Visible", state.form() == null);
        cmd.set("#SaveCrateLookButton.Visible", state.form() == null);
        event.addEventBinding(CustomUIEventBindingType.Activating, "#SaveKeyLookButton", INPUTS.event("newKeyLook"));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#SaveRewardSetButton",
                INPUTS.event("newRewardSet"));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#SaveCrateLookButton",
                INPUTS.event("newCrateLook"));
        buildForm(cmd, event);

        boolean hasNotice = state.notice() != null && !state.notice().isBlank();
        cmd.set("#Notice.Visible", hasNotice);
        if (hasNotice) {
            cmd.set("#Notice.TextSpans", uiText("#Notice.TextSpans", state.notice()));
        }

        cmd.set("#ListEmpty.Visible", state.entries() != null && shown.isEmpty());
        for (int i = 0; i < shown.size(); i++) {
            LootBoxTemplate template = shown.get(i).template();
            String row = "#TemplateList[" + i + "]";
            cmd.append("#TemplateList", ROW_UI);
            cmd.set(row + " #Selected.Visible", shown.get(i) == selected);
            String icon = iconOf(template);
            if (icon != null) {
                cmd.set(row + " #Icon.ItemId", icon);
            }
            cmd.set(row + " #TplName.TextSpans", uiText(row + " #TplName.TextSpans",
                    MysticText.stripMarkup(blank(template.name, template.id)) + " &8(" + template.id + ")"));
            cmd.set(row + " #TplMeta.TextSpans", uiText(row + " #TplMeta.TextSpans",
                    MysticText.stripMarkup(TemplateLibrary.summary(template))));
            cmd.set(row + " #TplSide.TextSpans", uiText(row + " #TplSide.TextSpans", saved(template)));
            event.addEventBinding(CustomUIEventBindingType.Activating, row,
                    INPUTS.event("select").put("id", template.id));
        }
        buildSelection(cmd, event, state.form() == null ? selected : null);

        event.addEventBinding(CustomUIEventBindingType.Activating, "#BackButton", INPUTS.event("back"));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton", new EventData().put("action", "close"));
    }

    private void buildForm(UICommandBuilder cmd, UIEventBuilder event) {
        Form form = state.form();
        cmd.set("#FormPanel.Visible", form != null);
        if (form == null) {
            return;
        }
        boolean renaming = RENAME.equals(form.mode());
        boolean keyLook = LootBoxTemplate.KEY_LOOK.equals(form.mode());
        boolean crateLook = LootBoxTemplate.CRATE_LOOK.equals(form.mode());
        cmd.set("#FormTitle.TextSpans", uiText("#FormTitle.TextSpans", renaming ? "Rename " + form.newId()
                : keyLook ? "Save a key look" : crateLook ? "Save a crate look" : "Save a reward set"));
        cmd.set("#FormSourceColumn.Visible", !renaming);
        cmd.set("#FormIdColumn.Visible", !renaming);
        if (!renaming) {
            List<String> sources = sources(form.mode());
            List<DropdownEntryInfo> entries = new ArrayList<>();
            sources.forEach(id -> entries.add(entry(id, id)));
            cmd.set("#FormSource.Entries", entries);
            if (form.source() != null) {
                cmd.set("#FormSource.Value", form.source());
            }
            event.addEventBinding(CustomUIEventBindingType.ValueChanged, "#FormSource", INPUTS.event("formSource"));
            cmd.set("#FormIdInput.Value", form.newId() == null ? "" : form.newId());
        }
        cmd.set("#FormNameInput.Value", form.newName() == null ? "" : form.newName());
        cmd.set("#FormHint.TextSpans", uiText("#FormHint.TextSpans", renaming
                ? "Only the name changes; the id and file stay."
                : keyLook ? "Copies the key's base item, name, lore, uses and expiry into templates/<id>.json."
                : crateLook ? "Copies the crate's particles, opening sound and floating name into templates/<id>.json."
                : "Copies every reward of the pool, in order, into templates/<id>.json."));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#FormConfirmButton", INPUTS.event("formConfirm"));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#FormCancelButton", INPUTS.event("formCancel"));
    }

    private void buildSelection(UICommandBuilder cmd, UIEventBuilder event, Entry selected) {
        cmd.set("#SelectionPanel.Visible", selected != null);
        if (selected == null) {
            return;
        }
        LootBoxTemplate template = selected.template();
        boolean keyLook = LootBoxTemplate.KEY_LOOK.equals(template.kind);
        boolean crateLook = LootBoxTemplate.CRATE_LOOK.equals(template.kind);
        cmd.set("#SelectedName.TextSpans", uiText("#SelectedName.TextSpans",
                MysticText.stripMarkup(blank(template.name, template.id)) + " &8(" + template.id + ")"));
        cmd.set("#SelectedMeta.TextSpans", uiText("#SelectedMeta.TextSpans", MysticText.stripMarkup(
                TemplateLibrary.summary(template)) + " · from " + blank(template.source, "unknown") + " · "
                + saved(template)));
        cmd.set("#TplDetail.TextSpans", uiText("#TplDetail.TextSpans", detail(template)));

        List<String> targets = sources(template.kind);
        String target = targets.contains(state.target()) ? state.target() : targets.isEmpty() ? null : targets.get(0);
        boolean any = target != null;
        cmd.set("#TargetPicker.Visible", any);
        cmd.set("#TplNoTarget.Visible", !any);
        if (any) {
            List<DropdownEntryInfo> entries = new ArrayList<>();
            targets.forEach(id -> entries.add(entry(id, id)));
            cmd.set("#TargetPicker.Entries", entries);
            cmd.set("#TargetPicker.Value", target);
        } else {
            cmd.set("#TplNoTarget.TextSpans", uiText("#TplNoTarget.TextSpans", keyLook
                    ? "There is no key draft to apply it to." : crateLook ? "There is no placed crate to apply it to."
                    : "There is no pool draft to insert it into."));
        }
        cmd.set("#ApplyButton.Visible", any && (keyLook || crateLook));
        cmd.set("#InsertButton.Visible", any && !keyLook && !crateLook);
        bind(event, "#ApplyButton", "apply", template.id);
        bind(event, "#InsertButton", "insert", template.id);
        bind(event, "#RenameButton", "rename", template.id);
        bind(event, "#DeleteButton", "delete", template.id);
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
        if ("back".equals(action)) {
            reopen(ref, store, new LootBoxManagerPage(core, player, module, LootBoxManagerPage.State.initial()));
            return;
        }
        if (!allowed(Permissions.LOOTBOX_EDIT)) {
            return;
        }
        State next = state.withInputs(INPUTS.read(payload));
        String id = field(payload, "id");
        switch (action) {
            case "filter" -> reopen(ref, store, page(next));
            case "select" -> reopen(ref, store, page(next.withSelected(id)));
            case "newKeyLook", "newRewardSet", "newCrateLook" -> {
                String kind = switch (action) {
                    case "newKeyLook" -> LootBoxTemplate.KEY_LOOK;
                    case "newCrateLook" -> LootBoxTemplate.CRATE_LOOK;
                    default -> LootBoxTemplate.REWARD_SET;
                };
                List<String> sources = sources(kind);
                if (sources.isEmpty()) {
                    reopen(ref, store, page(next.withNotice(LootBoxTemplate.CRATE_LOOK.equals(kind)
                            ? "&cThere is no placed crate to save from."
                            : "&cThere is no " + (LootBoxTemplate.KEY_LOOK.equals(kind) ? "key" : "pool")
                                    + " draft to save from.")));
                    return;
                }
                String source = sources.get(0);
                reopen(ref, store, page(next.withForm(new Form(kind, source,
                        TemplateLibrary.suggestId(source, kind), ""))));
            }
            case "formSource" -> reopen(ref, store, page(next.withForm(resuggest(state.form(), next.form()))));
            case "formCancel" -> reopen(ref, store, page(next.withForm(null)));
            case "formConfirm" -> submit(ref, store, next);
            case "rename" -> {
                Entry entry = find(id);
                if (entry == null) {
                    reopen(ref, store, page(next.withNotice("&c" + id + " is gone; the list was read again.")));
                    relist(next, null);
                    return;
                }
                reopen(ref, store, page(next.withSelected(id).withForm(new Form(RENAME, null, id,
                        MysticText.stripMarkup(blank(entry.template().name, id))))));
            }
            case "delete" -> confirmDelete(ref, store, next.withSelected(id), id);
            case "apply", "insert" -> prepareApply(ref, store, next.withSelected(id), id);
            default -> reopen(ref, store, page(next));
        }
    }

    /** Keeps a suggested id in step with the source, unless the player typed their own. */
    private static Form resuggest(Form before, Form after) {
        if (before == null || after == null) {
            return after;
        }
        String suggested = TemplateLibrary.suggestId(before.source(), before.mode());
        boolean untouched = after.newId() == null || after.newId().isBlank() || after.newId().equals(suggested);
        return untouched ? new Form(after.mode(), after.source(), TemplateLibrary.suggestId(after.source(), after.mode()),
                after.newName()) : after;
    }

    private void submit(Ref<EntityStore> ref, Store<EntityStore> store, State next) {
        Form form = next.form();
        if (form == null) {
            reopen(ref, store, page(next));
            return;
        }
        List<FormFields.Problem> problems = new ArrayList<>();
        String name = FormFields.nonBlank(NEW_NAME, form.newName(), TemplateLibrary.MAX_NAME_LENGTH).collect(problems);
        if (RENAME.equals(form.mode())) {
            if (!problems.isEmpty()) {
                reopen(ref, store, page(next.withNotice("&c" + problems.get(0).message())));
                return;
            }
            rename(ref, store, next, form.newId(), name);
            return;
        }
        String newId = FormFields.id(NEW_ID, form.newId() == null ? "" : form.newId().trim().toLowerCase(Locale.ROOT),
                DraftValidator.LOOTBOX_ID, "3-48 characters: a-z, 0-9 and _").collect(problems);
        if (!problems.isEmpty()) {
            reopen(ref, store, page(next.withNotice("&c" + problems.get(0).message())));
            return;
        }
        boolean keyLook = LootBoxTemplate.KEY_LOOK.equals(form.mode());
        String source = form.source();
        String actor = player.getUsername();
        long now = System.currentTimeMillis();
        reopen(ref, store, page(next.withNotice("&7Saving &f" + newId + "&7...")));
        CompletableFuture<LootBoxTemplate> built = LootBoxTemplate.CRATE_LOOK.equals(form.mode())
                ? CompletableFuture.completedFuture(module.crates().get(source)
                        .map(crate -> TemplateLibrary.crateLook(newId, name, crate, actor, now)).orElse(null))
                : keyLook
                ? module.registry().keyStore().load(source).thenApply(found -> found.map(loaded ->
                        TemplateLibrary.keyLook(newId, name, loaded.draft(), actor, now)).orElse(null))
                : module.registry().poolStore().load(source).thenApply(found -> found.map(loaded ->
                        TemplateLibrary.rewardSet(newId, name, loaded.draft(), actor, now)).orElse(null));
        built.thenCompose(template -> {
            if (template == null) {
                return CompletableFuture.completedFuture((DraftStore.Result) new DraftStore.Refused(
                        (LootBoxTemplate.CRATE_LOOK.equals(form.mode()) ? "the crate " : "the draft ") + source
                                + " is gone"));
            }
            List<String> wrong = TemplateLibrary.problems(template);
            return wrong.isEmpty() ? module.templates().store().save(template, null, actor)
                    : CompletableFuture.completedFuture((DraftStore.Result) new DraftStore.Refused(wrong.get(0)));
        }).whenComplete((result, error) -> {
            if (result instanceof DraftStore.Saved) {
                relist(next.withForm(null).withSelected(newId), "&aSaved template &f" + newId + " &afrom &f" + source
                        + "&a as templates/" + newId + ".json.");
                return;
            }
            relist(next, "&cCould not save &f" + newId + "&c: " + reason(result, error,
                    "a template with that id already exists; pick another id") + ".");
        });
    }

    private void rename(Ref<EntityStore> ref, Store<EntityStore> store, State next, String id, String name) {
        Entry entry = find(id);
        if (entry == null) {
            reopen(ref, store, page(next.withForm(null).withNotice("&c" + id + " is gone.")));
            return;
        }
        LootBoxTemplate renamed = DraftLifecycle.freeze(entry.template(), LootBoxTemplate.class);
        renamed.name = name;
        reopen(ref, store, page(next.withNotice("&7Renaming &f" + id + "&7...")));
        module.templates().store().save(renamed, entry.hash(), player.getUsername()).whenComplete((result, error) -> {
            if (result instanceof DraftStore.Saved) {
                relist(next.withForm(null), "&aRenamed &f" + id + " &ato &f" + name + "&a.");
                return;
            }
            relist(next.withForm(null), "&cCould not rename &f" + id + "&c: " + reason(result, error,
                    "its file changed since the library read it; the list is fresh now, so try again") + ".");
        });
    }

    private void confirmDelete(Ref<EntityStore> ref, Store<EntityStore> store, State next, String id) {
        Entry entry = find(id);
        if (entry == null) {
            reopen(ref, store, page(next.withNotice("&c" + id + " is gone.")));
            return;
        }
        List<String> lines = List.of(
                "&7Moves &ftemplates/" + id + ".json &7to &ftemplates/_archive/&7. Move it back to restore it.",
                "&7Drafts it was applied to keep their copies; nothing else changes.");
        ConfirmActionPage.Spec spec = new ConfirmActionPage.Spec("Delete template "
                + MysticText.stripMarkup(blank(entry.template().name, id)) + "?", lines, "Delete", true,
                Permissions.LOOTBOX_EDIT);
        reopen(ref, store, new ConfirmActionPage(core, player, spec, (r, s) -> {
            reopen(r, s, page(next.withNotice("&7Deleting &f" + id + "&7...")));
            module.templates().store().archive(id, player.getUsername()).whenComplete((result, error) -> {
                if (result instanceof DraftStore.Archived archived) {
                    relist(next.withSelected(null), "&7Deleted &f" + id + "&7; the file is now &ftemplates/_archive/"
                            + archived.path().getFileName() + "&7.");
                    return;
                }
                relist(next, "&cCould not delete &f" + id + "&c: " + reason(result, error, "unknown") + ".");
            });
        }, (r, s) -> reopen(r, s, page(next))));
    }

    /**
     * Loads the target draft from disk, works out what the template changes, and
     * asks before writing it back with the hash it loaded.
     */
    private void prepareApply(Ref<EntityStore> ref, Store<EntityStore> store, State next, String id) {
        Entry entry = find(id);
        if (entry == null) {
            reopen(ref, store, page(next.withNotice("&c" + id + " is gone.")));
            return;
        }
        LootBoxTemplate template = entry.template();
        List<String> broken = TemplateLibrary.problems(template);
        if (!broken.isEmpty()) {
            reopen(ref, store, page(next.withNotice("&c" + id + " can't be applied: " + broken.get(0)
                    + ". Fix templates/" + id + ".json or delete it.")));
            return;
        }
        boolean keyLook = LootBoxTemplate.KEY_LOOK.equals(template.kind);
        List<String> targets = sources(template.kind);
        String target = targets.contains(next.target()) ? next.target() : targets.isEmpty() ? null : targets.get(0);
        if (target == null) {
            reopen(ref, store, page(next.withNotice("&cThere is no draft to apply it to.")));
            return;
        }
        if (!keyLook && template.rewards.stream().anyMatch(r -> "COMMAND".equalsIgnoreCase(r.type))
                && !allowed(Permissions.LOOTBOX_REWARDS_COMMAND)) {
            reopen(ref, store, page(next.withNotice("&cThe set has COMMAND rewards, which need rewards.command.")));
            return;
        }
        if (LootBoxTemplate.CRATE_LOOK.equals(template.kind)) {
            // A live crate, not a draft file: same gate as the crate editor's Save.
            if (!allowed(Permissions.LOOTBOX_CRATES_MANAGE)) {
                return;
            }
            CrateInstance live = module.crates().get(target).orElse(null);
            if (live == null) {
                reopen(ref, store, page(next.withNotice("&cThe crate " + target + " was removed meanwhile.")));
                return;
            }
            CrateInstance after = TemplateApply.crateLook(template, live);
            confirmApply(next, template, target, "crate", CrateInstance.class, TemplateApply.crateLookLines(live, after),
                    CrateRules.settingsHash(live), hash -> CompletableFuture.completedFuture(crateResult(
                            module.crates().update(target, after, hash, player.getUsername()))),
                    "look of crate " + target);
            return;
        }
        reopen(ref, store, page(next.withNotice("&7Reading &f" + target + "&7...")));
        if (keyLook) {
            module.registry().keyStore().load(target).whenComplete((found, error) -> {
                if (error != null || found == null || found.isEmpty()) {
                    relist(next, "&cCould not read the key draft &f" + target + "&c.");
                    return;
                }
                KeyDraft after = TemplateApply.keyLook(template, found.get().draft());
                List<String> lines = new ArrayList<>(TemplateApply.keyLookLines(found.get().draft(), after));
                confirmApply(next, template, target, "key", KeyDraft.class, lines, found.get().hash(),
                        hash -> module.registry().keyStore().save(after, hash, player.getUsername()),
                        "look of key " + target);
            });
        } else {
            module.registry().poolStore().load(target).whenComplete((found, error) -> {
                if (error != null || found == null || found.isEmpty()) {
                    relist(next, "&cCould not read the pool draft &f" + target + "&c.");
                    return;
                }
                TemplateApply.Insertion insertion = TemplateApply.rewardSet(template, found.get().draft());
                confirmApply(next, template, target, "pool", LootBoxDraft.class, insertion.lines(), found.get().hash(),
                        hash -> module.registry().poolStore().save(insertion.pool(), hash, player.getUsername()),
                        insertion.added().size() + " reward(s) into pool " + target
                                + (insertion.renamed().isEmpty() ? "" : ", renamed " + insertion.renamed()));
            });
        }
    }

    /**
     * Opens the confirmation, whose Confirm writes with the loaded hash. Runs on a
     * storage thread for drafts and on the world thread for crates.
     */
    private void confirmApply(State next, LootBoxTemplate template, String target, String noun, Class<?> type,
            List<String> changes, String loadedHash, Function<String, CompletableFuture<DraftStore.Result>> write,
            String what) {
        List<String> lines = new ArrayList<>(changes);
        boolean crate = LootBoxTemplate.CRATE_LOOK.equals(template.kind);
        List<UUID> editing = module.editors().editing(type, target, null);
        boolean mine = editing.contains(player.getUuid());
        int others = editing.size() - (mine ? 1 : 0);
        if (mine || others > 0) {
            lines.add("&e" + (mine ? "You have it open in an editor" + (others > 0 ? ", and so do " + others
                    + " other(s)" : "") : others + " other editor(s) have it open")
                    + (crate ? ": the next save there finds the crate changed and offers Load Current."
                            : ": the next save there finds the file changed and offers Load From Disk."));
        }
        lines.add(crate ? "&7Changes the placed crate at once; its pool, place and protection stay."
                : "&7Writes the draft file only; nothing is published.");
        boolean look = !LootBoxTemplate.REWARD_SET.equals(template.kind);
        ConfirmActionPage.Spec spec = new ConfirmActionPage.Spec((look ? "Apply " : "Insert ")
                + MysticText.stripMarkup(blank(template.name, template.id)) + (look ? " to " : " into ") + target + "?",
                lines, look ? "Apply Look" : "Insert Rewards", false, Permissions.LOOTBOX_EDIT);
        core.platform().openPage(player, new ConfirmActionPage(core, player, spec, (r, s) -> {
            boolean commands = !look && template.rewards.stream().anyMatch(rw -> "COMMAND".equalsIgnoreCase(rw.type));
            if ((commands && !allowed(Permissions.LOOTBOX_REWARDS_COMMAND))
                    || (crate && !allowed(Permissions.LOOTBOX_CRATES_MANAGE))) {
                reopen(r, s, page(next));
                return;
            }
            reopen(r, s, page(next.withNotice("&7Writing &f" + target + "&7...")));
            write.apply(loadedHash).whenComplete((result, error) -> {
                if (result instanceof DraftStore.Saved) {
                    module.audit().record(player.getUsername(), "TEMPLATE_APPLY", target,
                            "template " + template.id + ": " + what);
                    relist(next, crate ? "&aApplied &f" + template.id + " &ato crate &f" + target
                            + "&a. Players near it see the new look now."
                            : "&a" + (look ? "Applied &f" + template.id + " &ato key &f" + target
                                    : "Inserted &f" + template.id + " &ainto pool &f" + target)
                                    + "&a. Open it from the " + (look ? "Key Manager" : "Manager")
                                    + " to check and publish.");
                    return;
                }
                relist(next, "&cNothing was written to the " + noun + (crate ? " &f" : " draft &f") + target + "&c: "
                        + reason(result, error, "it changed since you looked; press " + (look ? "Apply Look"
                                : "Insert Rewards") + " again to see the new version") + ".");
            });
        }, (r, s) -> reopen(r, s, page(next))));
    }

    // ----- Helpers ------------------------------------------------------------

    /** Lists the folder off the world thread and reopens the page with it and an optional notice. */
    private void relist(State next, String notice) {
        module.templates().store().list().whenComplete((listing, error) -> {
            if (error != null) {
                core.log(Level.WARNING, "[lootbox] listing templates failed: " + error);
                openLater(next.withNotice("&cCould not read templates/; see the server log."));
                return;
            }
            listing.errors().forEach(problem -> core.log(Level.WARNING, "[lootbox] template " + problem));
            openLater(next.withListing(listing).withNotice(notice));
        });
    }

    private Entry find(String id) {
        if (id == null || state.entries() == null) {
            return null;
        }
        return state.entries().stream().filter(entry -> id.equals(entry.template().id)).findFirst().orElse(null);
    }

    /**
     * What a template of {@code kind} is saved from or applied to: key drafts for
     * key looks, placed crates for crate looks, else pool drafts.
     */
    private List<String> sources(String kind) {
        if (LootBoxTemplate.CRATE_LOOK.equals(kind)) {
            TreeSet<String> crates = new TreeSet<>();
            module.crates().list().forEach(crate -> crates.add(crate.crateId));
            return List.copyOf(crates);
        }
        return List.copyOf(new TreeSet<>(LootBoxTemplate.KEY_LOOK.equals(kind)
                ? module.registry().keyDrafts().keySet() : module.registry().drafts().keySet()));
    }

    private static long count(List<Entry> entries, String kind) {
        return entries.stream().filter(entry -> kind.equals(entry.template().kind)).count();
    }

    /** A crate save's outcome in the draft store's terms, so crate looks share the confirmation's ending. */
    private static DraftStore.Result crateResult(CrateService.SaveResult result) {
        return switch (result) {
            case CrateService.Saved saved -> new DraftStore.Saved(saved.hash());
            case CrateService.Conflict conflict -> new DraftStore.Conflict(CrateRules.settingsHash(conflict.current()));
            case CrateService.Refused refused -> new DraftStore.Refused(refused.reason());
        };
    }

    private static String detail(LootBoxTemplate template) {
        if (LootBoxTemplate.KEY_LOOK.equals(template.kind)) {
            LootBoxTemplate.KeyLook look = template.key;
            if (look == null) {
                return "&7Empty.";
            }
            List<String> lore = look.lore == null ? List.of() : look.lore;
            return "&r" + blank(look.name, "&7(no name)") + " &r&8· &7" + (lore.isEmpty() ? "no lore"
                    : lore.size() + " lore line(s): &r" + String.join(" &8/ &r", lore));
        }
        if (LootBoxTemplate.CRATE_LOOK.equals(template.kind)) {
            return template.crate == null ? "&7Empty." : "&7" + TemplateLibrary.effects(template.crate);
        }
        return "&7" + TemplateApply.rewardNames(template, 6);
    }

    private static String saved(LootBoxTemplate template) {
        return "by " + blank(template.createdBy, "?")
                + (template.createdAt > 0 ? " on " + DATE.format(Instant.ofEpochMilli(template.createdAt)) : "");
    }

    private static String iconOf(LootBoxTemplate template) {
        if (template.key != null && template.key.baseItemId != null && ItemCatalog.exists(template.key.baseItemId)) {
            return template.key.baseItemId;
        }
        if (template.rewards != null) {
            for (LootBoxDraft.RewardSpec reward : template.rewards) {
                if (reward.item != null && reward.item.itemId != null && ItemCatalog.exists(reward.item.itemId)) {
                    return reward.item.itemId;
                }
            }
        }
        return null;
    }

    private String reason(Object result, Throwable error, String conflict) {
        if (error != null) {
            core.log(Level.WARNING, "[lootbox] template action failed: " + error);
            return "see the server log";
        }
        return result instanceof DraftStore.Refused refused ? refused.reason()
                : result instanceof DraftStore.Conflict ? conflict : "unknown";
    }

    private static String blank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static DropdownEntryInfo entry(String label, String value) {
        return new DropdownEntryInfo(LocalizableString.fromString(label), value);
    }

    private TemplateLibraryPage page(State next) {
        return new TemplateLibraryPage(core, player, module, next);
    }

    /** Reopens the page from a background completion; {@code openPage} hops to the player's world thread. */
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
