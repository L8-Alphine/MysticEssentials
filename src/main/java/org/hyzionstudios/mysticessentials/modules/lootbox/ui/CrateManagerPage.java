package org.hyzionstudios.mysticessentials.modules.lootbox.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.hyzionstudios.mysticessentials.api.Permissions;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.message.MysticText;
import org.hyzionstudios.mysticessentials.modules.lootbox.LootBoxModule;
import org.hyzionstudios.mysticessentials.modules.lootbox.hytale.BlockTargets;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.CrateInstance;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxRevision;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.CrateService;
import org.hyzionstudios.mysticessentials.modules.lootbox.ui.editor.ConfirmActionPage;
import org.hyzionstudios.mysticessentials.modules.lootbox.ui.editor.CrateEditorPage;
import org.hyzionstudios.mysticessentials.modules.lootbox.ui.editor.FormFields;
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
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * The Crate Manager (P2-G): every crate with its world, position, pool and
 * status, a header search, a status filter, and the actions for the selected
 * crate. Reached from the pool Manager's Crates button. "Convert Looked-At
 * Block" raycasts on the world thread (page events arrive there) and opens the
 * crate editor on the block; Verify checks this world's crates the same way.
 *
 * <p>It works like {@link KeyManagerPage}: every press re-checks its node
 * ({@code crates.place} to convert, {@code crates.manage} for the rest), and
 * handlers reopen synchronously.</p>
 */
public final class CrateManagerPage extends MysticPage {

    static final String CRATES_UI = "MysticEssentials/LootBoxCrateManager.ui";
    static final List<String> FILTERS = List.of("ALL", "ACTIVE", "DISABLED", "HERE");

    private static final FormFields.Field SEARCH = FormFields.Field.text("search", "SearchInput", "Search");
    private static final FormFields.Field FILTER = FormFields.Field.text("filter", "StatusFilter", "Status");
    private static final FormFields INPUTS = FormFields.of(SEARCH, FILTER);

    /** What the page shows besides the crates themselves. */
    public record State(String query, String filter, String selected, String notice) {

        public static State initial() {
            return new State("", "ALL", null, null);
        }

        /** The Crate Manager with {@code crateId} selected; where the crate editor returns to. */
        public static State selecting(String crateId, String notice) {
            return new State("", "ALL", crateId, notice);
        }

        State withSelected(String id) {
            return new State(query, filter, id, notice);
        }

        State withNotice(String text) {
            return new State(query, filter, selected, text);
        }

        State withInputs(Map<String, String> inputs) {
            String nextFilter = inputs.getOrDefault(FILTER.key(), filter);
            return new State(inputs.getOrDefault(SEARCH.key(), query), FILTERS.contains(nextFilter) ? nextFilter : "ALL",
                    selected, null);
        }
    }

    private final LootBoxModule module;
    private final State state;

    public CrateManagerPage(MysticCore core, PlayerRef player, LootBoxModule module, State state) {
        super(core, player, CustomPageLifetime.CanDismiss);
        this.module = module;
        this.state = state == null ? State.initial() : state;
    }

    /** Whether {@code player} may open the Crate Manager at all. */
    public static boolean mayOpen(PlayerRef player) {
        return player.hasPermission(Permissions.LOOTBOX_CRATES_MANAGE)
                || player.hasPermission(Permissions.LOOTBOX_CRATES_PLACE);
    }

    // ----- Build --------------------------------------------------------------

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event, Store<EntityStore> store) {
        cmd.append(CRATES_UI);
        World here = store.getExternalData().getWorld();
        List<CrateInstance> all = module.crates().list();
        List<CrateInstance> shown = all.stream()
                .filter(crate -> switch (state.filter()) {
                    case "ACTIVE" -> !crate.disabled;
                    case "DISABLED" -> crate.disabled;
                    case "HERE" -> CrateService.inWorld(crate, here);
                    default -> true;
                })
                .filter(crate -> matchesSearch(state.query(), crate.crateId, crate.lootboxId, crate.name,
                        crate.worldName, crate.blockKey, poolName(crate)))
                .toList();
        CrateInstance selected = shown.stream().filter(crate -> crate.crateId.equals(state.selected())).findFirst()
                .orElse(shown.isEmpty() ? null : shown.get(0));

        long disabled = all.stream().filter(crate -> crate.disabled).count();
        cmd.set("#HeaderInfo.TextSpans", uiText("#HeaderInfo.TextSpans", (shown.size() == all.size()
                ? all.size() + " crates" : shown.size() + " of " + all.size() + " crates")
                + (disabled > 0 ? " · " + disabled + " disabled" : "") + " · you are in " + here.getName()));
        cmd.set("#SearchInput.Value", state.query());
        cmd.set("#StatusFilter.Entries", List.of(entry("All crates", "ALL"), entry("Active", "ACTIVE"),
                entry("Disabled", "DISABLED"), entry("This world", "HERE")));
        cmd.set("#StatusFilter.Value", state.filter());
        event.addEventBinding(CustomUIEventBindingType.ValueChanged, "#SearchInput", INPUTS.event("search"));
        event.addEventBinding(CustomUIEventBindingType.ValueChanged, "#StatusFilter", INPUTS.event("filter"));

        boolean canPlace = player.hasPermission(Permissions.LOOTBOX_CRATES_PLACE);
        boolean canManage = player.hasPermission(Permissions.LOOTBOX_CRATES_MANAGE);
        cmd.set("#ConvertButton.Visible", canPlace);
        event.addEventBinding(CustomUIEventBindingType.Activating, "#ConvertButton", INPUTS.event("convert"));

        boolean hasNotice = state.notice() != null && !state.notice().isBlank();
        cmd.set("#Notice.Visible", hasNotice);
        if (hasNotice) {
            cmd.set("#Notice.TextSpans", uiText("#Notice.TextSpans", state.notice()));
        }

        cmd.set("#ListEmpty.Visible", shown.isEmpty());
        for (int i = 0; i < shown.size(); i++) {
            CrateInstance crate = shown.get(i);
            String row = "#CrateList[" + i + "]";
            cmd.append("#CrateList", LootBoxManagerPage.MANAGER_ROW_UI);
            cmd.set(row + " #Selected.Visible", crate == selected);
            String icon = crate.originalBlockKey != null ? crate.originalBlockKey : crate.blockKey;
            if (icon != null && ItemCatalog.exists(icon)) {
                cmd.set(row + " #Icon.ItemId", icon);
            }
            cmd.set(row + " #Name.TextSpans", uiText(row + " #Name.TextSpans", title(crate)));
            cmd.set(row + " #Meta.TextSpans", uiText(row + " #Meta.TextSpans", meta(crate)));
            cmd.set(row + " #Status.TextSpans", uiText(row + " #Status.TextSpans", chip(crate)));
            event.addEventBinding(CustomUIEventBindingType.Activating, row,
                    INPUTS.event("select").put("id", crate.crateId));
        }

        cmd.set("#SelectionBar.Visible", selected != null);
        if (selected != null) {
            cmd.set("#SelectedName.TextSpans", uiText("#SelectedName.TextSpans", title(selected)));
            cmd.set("#SelectedMeta.TextSpans", uiText("#SelectedMeta.TextSpans", selectionMeta(selected)));
            cmd.set("#EditButton.Visible", canManage);
            cmd.set("#DisableButton.Visible", canManage && !selected.disabled);
            cmd.set("#EnableButton.Visible", canManage && selected.disabled);
            cmd.set("#RemoveButton.Visible", canManage);
            for (String[] button : new String[][] {{"#EditButton", "edit"}, {"#DisableButton", "disable"},
                    {"#EnableButton", "enable"}, {"#RemoveButton", "remove"}}) {
                event.addEventBinding(CustomUIEventBindingType.Activating, button[0],
                        INPUTS.event(button[1]).put("id", selected.crateId));
            }
        }
        cmd.set("#VerifyButton.Visible", canManage);
        event.addEventBinding(CustomUIEventBindingType.Activating, "#PoolsButton", INPUTS.event("pools"));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#VerifyButton", INPUTS.event("verify"));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton", new EventData().put("action", "close"));
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
        if (!mayOpen(player)) {
            core.getMessageService().sendKey(player, "no-permission");
            close(ref, store);
            return;
        }
        State next = state.withInputs(INPUTS.read(payload));
        String id = field(payload, "id");
        switch (action) {
            case "search", "filter" -> reopen(ref, store, page(next));
            case "select" -> reopen(ref, store, page(next.withSelected(id)));
            case "convert" -> {
                if (allowed(Permissions.LOOTBOX_CRATES_PLACE)) {
                    convert(ref, store, next);
                }
            }
            case "edit" -> {
                if (allowed(Permissions.LOOTBOX_CRATES_MANAGE)) {
                    edit(ref, store, next.withSelected(id), id, null);
                }
            }
            case "disable" -> {
                if (allowed(Permissions.LOOTBOX_CRATES_MANAGE)) {
                    module.crates().get(id).ifPresentOrElse(crate -> {
                        module.crates().disable(crate, CrateEditorPage.STAFF_DISABLED + " (" + player.getUsername() + ")",
                                player.getUsername());
                        reopen(ref, store, page(next.withSelected(id).withNotice("&7Disabled &f" + id
                                + "&7. Using it now says it's inactive.")));
                    }, () -> reopen(ref, store, page(next.withNotice("&c" + id + " no longer exists."))));
                }
            }
            case "enable" -> {
                if (allowed(Permissions.LOOTBOX_CRATES_MANAGE)) {
                    enable(ref, store, next.withSelected(id), id);
                }
            }
            case "remove" -> {
                if (allowed(Permissions.LOOTBOX_CRATES_MANAGE)) {
                    confirmRemove(ref, store, next.withSelected(id), id);
                }
            }
            case "verify" -> {
                if (allowed(Permissions.LOOTBOX_CRATES_MANAGE)) {
                    World world = store.getExternalData().getWorld();
                    CrateService.Verified verified = module.crates().verify(world);
                    verified.lines().stream().filter(line -> line.contains("DISABLED"))
                            .forEach(line -> core.getMessageService().send(player, "&e" + line));
                    reopen(ref, store, page(next.withNotice("&7Checked &f" + verified.checked() + " &7crate(s) in &f"
                            + world.getName() + "&7: &a" + verified.ok() + " ok"
                            + (verified.newlyDisabled() > 0 ? "&7, &e" + verified.newlyDisabled() + " disabled (block changed)" : "")
                            + (verified.unloaded() > 0 ? "&7, &f" + verified.unloaded() + " &7in chunks not loaded" : "")
                            + "&7.")));
                }
            }
            case "pools" -> reopen(ref, store, new LootBoxManagerPage(core, player, module,
                    LootBoxManagerPage.State.initial()));
            default -> reopen(ref, store, page(next));
        }
    }

    /** Raycasts the block the player looks at (we are on the world thread) and opens the editor on it. */
    private void convert(Ref<EntityStore> ref, Store<EntityStore> store, State next) {
        double reach = module.config().crates.selectReach;
        BlockTargets.Block block = BlockTargets.target(ref, store, reach);
        if (block == null) {
            reopen(ref, store, page(next.withNotice("&cLook at a block within " + (int) reach
                    + " blocks, then press Convert again.")));
            return;
        }
        CrateInstance existing = module.crates().at(block.world(), block.x(), block.y(), block.z()).orElse(null);
        if (existing != null) {
            reopen(ref, store, page(next.withSelected(existing.crateId).withNotice("&eThat block is already crate &f"
                    + existing.crateId + "&e. Press Edit to change it.")));
            return;
        }
        reopen(ref, store, CrateEditorPage.forNewCrate(core, player, module, block));
    }

    private void edit(Ref<EntityStore> ref, Store<EntityStore> store, State next, String id, String notice) {
        CrateInstance crate = module.crates().get(id).orElse(null);
        if (crate == null) {
            reopen(ref, store, page(next.withNotice("&c" + id + " no longer exists.")));
            return;
        }
        reopen(ref, store, CrateEditorPage.forCrate(core, player, module, crate, notice));
    }

    /**
     * A crate staff turned off comes straight back. One the server turned off
     * (its block changed or was destroyed) opens the repair step instead, so the
     * block is checked before it can open again.
     */
    private void enable(Ref<EntityStore> ref, Store<EntityStore> store, State next, String id) {
        CrateInstance crate = module.crates().get(id).orElse(null);
        if (crate == null) {
            reopen(ref, store, page(next.withNotice("&c" + id + " no longer exists.")));
            return;
        }
        if (!crate.disabled || CrateEditorPage.disabledByStaff(crate)) {
            module.crates().enable(crate, player.getUsername());
            reopen(ref, store, page(next.withNotice("&aCrate &f" + id + " &ais active again.")));
            return;
        }
        edit(ref, store, next, id, "&eThe server turned this crate off: " + crate.disabledReason
                + ". Check its block below before turning it back on.");
    }

    private void confirmRemove(Ref<EntityStore> ref, Store<EntityStore> store, State next, String id) {
        CrateInstance crate = module.crates().get(id).orElse(null);
        if (crate == null) {
            reopen(ref, store, page(next.withNotice("&c" + id + " no longer exists.")));
            return;
        }
        List<String> lines = new ArrayList<>();
        lines.add("&7Removes crate &f" + crate.crateId + " &7at &f" + crate.position() + "&7.");
        lines.add(CrateInstance.KIND_GENERATED.equals(crate.kind)
                ? "&7Its original block (&f" + crate.originalBlockKey + "&7) is put back, so stand near it."
                : "&7The block stays where it is and goes back to its normal behaviour.");
        lines.add("&7Players' keys and receipts are untouched.");
        ConfirmActionPage.Spec spec = new ConfirmActionPage.Spec("Remove " + crate.crateId + "?", lines, "Remove",
                true, Permissions.LOOTBOX_CRATES_MANAGE);
        reopen(ref, store, new ConfirmActionPage(core, player, spec, (r, s) -> reopen(r, s, page(
                switch (module.crates().remove(id, s.getExternalData().getWorld(), false, player.getUsername())) {
                    case CrateService.Removed done -> next.withSelected(null).withNotice("&7Removed crate &f" + id
                            + (done.note() == null ? "" : " &8(" + done.note() + ")") + "&7.");
                    case CrateService.NotRemoved not -> next.withNotice("&cNot removed: " + not.reason() + ".");
                })),
                (r, s) -> reopen(r, s, page(next))));
    }

    // ----- Helpers ------------------------------------------------------------

    private String poolName(CrateInstance crate) {
        LootBoxRevision live = module.registry().active(crate.lootboxId).orElse(null);
        return live == null ? crate.lootboxId : MysticText.stripMarkup(live.spec.displayName);
    }

    private String title(CrateInstance crate) {
        String name = crate.name != null && !crate.name.isBlank() ? MysticText.stripMarkup(crate.name) : poolName(crate);
        return name + " &8(" + crate.crateId + ")";
    }

    private static String meta(CrateInstance crate) {
        return crate.position() + " · pool " + crate.lootboxId + " · " + crate.blockKey;
    }

    private String chip(CrateInstance crate) {
        if (crate.disabled) {
            return "&eDisabled";
        }
        if (module.registry().active(crate.lootboxId).isEmpty()) {
            return "&cPool not published";
        }
        if (module.registry().isDisabled(crate.lootboxId)) {
            return "&ePool closed";
        }
        return "&aActive" + (crate.directConfirm() ? " &8· direct" : "");
    }

    private String selectionMeta(CrateInstance crate) {
        StringBuilder text = new StringBuilder("&7" + crate.position() + " &8· &7opens &f" + crate.lootboxId);
        if (crate.disabled) {
            text.append(" &8· &e").append(crate.disabledReason);
        }
        text.append(" &8· &7").append(crate.directConfirm() ? "straight to confirm" : "preview first");
        return text.toString();
    }

    private static DropdownEntryInfo entry(String label, String value) {
        return new DropdownEntryInfo(LocalizableString.fromString(label), value);
    }

    private CrateManagerPage page(State next) {
        return new CrateManagerPage(core, player, module, next);
    }

    private boolean allowed(String node) {
        if (player.hasPermission(node)) {
            return true;
        }
        core.getMessageService().sendKey(player, "no-permission");
        return false;
    }
}
