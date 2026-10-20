package org.hyzionstudios.mysticessentials.modules.lootbox.ui.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.hyzionstudios.mysticessentials.api.Permissions;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.message.MysticText;
import org.hyzionstudios.mysticessentials.modules.lootbox.LootBoxModule;
import org.hyzionstudios.mysticessentials.modules.lootbox.hytale.BlockTargets;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.CrateInstance;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxRevision;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.CrateRules;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.CrateService;
import org.hyzionstudios.mysticessentials.modules.lootbox.ui.CrateManagerPage;
import org.hyzionstudios.mysticessentials.platform.ui.MysticPage;

import com.google.gson.JsonObject;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.LocalizableString;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * The crate editor (P2-G): the bible's §09 placement steps as tabs, so a crate
 * is made and maintained without typing a command.
 *
 * <ol>
 *   <li><b>Block</b> (01 + 02): the selected block, its world, position and
 *       rotation, "Use Looked-At Block", the crate id and an optional name.</li>
 *   <li><b>Check</b> (03): {@link CrateRules#check}'s report, read again from
 *       the world on every build.</li>
 *   <li><b>Pool</b> (04): a published pool; its keys come with it.</li>
 *   <li><b>Use</b> (05): {@code PREVIEW_THEN_CONFIRM} or {@code DIRECT_CONFIRM}.</li>
 *   <li><b>Protect</b>: per-crate protection that can only add to the server's.</li>
 *   <li><b>Finish</b> (07): the summary, Create or Save, and Test in World.</li>
 * </ol>
 *
 * <p>A crate the server turned off (its block changed or was destroyed) opens
 * on a <b>Repair</b> tab: turn it back on when the block matches again, record
 * the block that is there now, or remove the crate.</p>
 *
 * <p>The working copy lives in an {@link EditorSession}, so a rebuild keeps
 * unsaved edits; a save is refused when another editor saved since this one
 * loaded ({@link CrateRules#settingsHash}). Page events arrive on the player's
 * world thread, so the block reads and the conversion happen right in the
 * handler. Creating needs {@code crates.place}; changing an existing crate needs
 * {@code crates.manage}, plus {@code crates.place} to bind it to another pool.
 * Every press checks again.</p>
 */
public final class CrateEditorPage extends MysticPage {

    static final String EDITOR_UI = "MysticEssentials/LootBoxCrateEditor.ui";
    static final String LINE_UI = ConfirmActionPage.LINE_UI;

    /** The editor session id of a crate that does not exist yet. */
    public static final String NEW_ID = "+new";
    /** The reason recorded when staff turn a crate off; such a crate comes back without a block check. */
    public static final String STAFF_DISABLED = "turned off by staff";

    static final FormFields.Field CRATE_ID = FormFields.Field.text("crateId", "CrateIdInput", "Crate id");
    static final FormFields.Field NAME = FormFields.Field.text("crateName", "NameInput", "Name");
    static final FormFields.Field POOL = FormFields.Field.text("pool", "PoolDropdown", "Pool");
    static final FormFields INPUTS = FormFields.of(CRATE_ID, NAME, POOL);
    private static final int MAX_NAME_LENGTH = 64;

    static final EditorTabs.Tab REPAIR = new EditorTabs.Tab("repair", "Repair");
    static final EditorTabs.Tab BLOCK = new EditorTabs.Tab("block", "1 Block");
    static final EditorTabs.Tab CHECK = new EditorTabs.Tab("check", "2 Check");
    static final EditorTabs.Tab POOL_TAB = new EditorTabs.Tab("pool", "3 Pool");
    static final EditorTabs.Tab USE = new EditorTabs.Tab("use", "4 Use");
    static final EditorTabs.Tab PROTECT = new EditorTabs.Tab("protect", "5 Protect");
    static final EditorTabs.Tab FINISH = new EditorTabs.Tab("finish", "6 Finish");

    private static final Map<String, String> STEP_GROUPS = Map.of("repair", "#StepRepair", "block", "#StepBlock",
            "check", "#StepCheck", "pool", "#StepPool", "use", "#StepBehaviour", "protect", "#StepProtect",
            "finish", "#StepFinish");

    private final LootBoxModule module;
    private final EditorSession<CrateInstance> session;
    private final PoolEditorPage.View view;

    public CrateEditorPage(MysticCore core, PlayerRef player, LootBoxModule module, EditorSession<CrateInstance> session,
            PoolEditorPage.View view) {
        super(core, player, CustomPageLifetime.CanDismiss);
        this.module = module;
        this.session = session;
        this.view = view == null ? PoolEditorPage.View.of(null) : view;
    }

    // ----- Opening ------------------------------------------------------------

    /**
     * Starts an editor on {@code block} for a crate that does not exist yet (the
     * Crate Manager's Convert). The block is judged on every build, so an
     * incompatible block still opens: the Check step says why.
     */
    public static CrateEditorPage forNewCrate(MysticCore core, PlayerRef player, LootBoxModule module,
            BlockTargets.Block block) {
        EditorSession<CrateInstance> open = module.editors().get(player.getUuid(), CrateInstance.class)
                .filter(s -> s.active() && NEW_ID.equals(s.draftId())).orElse(null);
        if (open != null && open.working().x == block.x() && open.working().y == block.y()
                && open.working().z == block.z() && block.worldId().equals(open.working().worldId)) {
            return new CrateEditorPage(core, player, module, open, PoolEditorPage.View.of(
                    "&7Back to the crate you were setting up on this block."));
        }
        CrateInstance working = new CrateInstance();
        select(working, block);
        List<LootBoxRevision> pools = module.registry().publishedPools();
        if (pools.size() == 1) {
            working.lootboxId = pools.get(0).lootboxId;
        }
        working.createdAt = System.currentTimeMillis();
        EditorSession<CrateInstance> session = module.editors().start(player.getUuid(), NEW_ID, CrateInstance.class,
                working, null);
        CrateRules.Report report = module.crates().check(block, null);
        session.tab(report.ok() ? BLOCK.id() : CHECK.id());
        return new CrateEditorPage(core, player, module, session, PoolEditorPage.View.of(report.ok()
                ? "&7Selected &f" + block.key() + "&7. Work through the steps, then Create Crate on Finish."
                : "&cThis block can't be a crate: " + report.problem() + "."));
    }

    /**
     * Opens {@code crate} in the editor. The player's open editor on the same
     * crate is resumed (after Test in World, say); otherwise a fresh session
     * starts. A crate the server turned off opens on the Repair tab.
     */
    public static CrateEditorPage forCrate(MysticCore core, PlayerRef player, LootBoxModule module,
            CrateInstance crate, String notice) {
        EditorSession<CrateInstance> session = module.editors().get(player.getUuid(), CrateInstance.class)
                .filter(open -> open.active() && crate.crateId.equals(open.draftId())).orElse(null);
        if (session == null) {
            session = module.editors().start(player.getUuid(), crate.crateId, CrateInstance.class, crate.copy(),
                    CrateRules.settingsHash(crate));
            session.tab(crate.disabled && !disabledByStaff(crate) ? REPAIR.id() : BLOCK.id());
        }
        return new CrateEditorPage(core, player, module, session, PoolEditorPage.View.of(notice));
    }

    /** Whether staff turned {@code crate} off (rather than the server, after its block changed). */
    public static boolean disabledByStaff(CrateInstance crate) {
        return crate.disabled && crate.disabledReason != null && crate.disabledReason.startsWith(STAFF_DISABLED);
    }

    private static void select(CrateInstance working, BlockTargets.Block block) {
        working.worldId = block.worldId();
        working.worldName = block.world().getName();
        working.x = block.x();
        working.y = block.y();
        working.z = block.z();
        working.blockKey = block.key();
    }

    // ----- Build --------------------------------------------------------------

    /** What the build read from the world: the block now, and its report. */
    private record Spot(BlockTargets.Block block, CrateRules.Report report, boolean readable, boolean sameWorld) {
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event, Store<EntityStore> store) {
        cmd.append(EDITOR_UI);
        CrateInstance crate = session.working();
        CrateInstance live = isNew() ? null : module.crates().get(session.draftId()).orElse(null);
        Spot spot = read(store.getExternalData().getWorld(), crate, live);
        List<EditorTabs.Tab> tabs = tabs(live);
        String tab = EditorTabs.resolve(tabs, session.tab());

        cmd.set("#HeaderTitle.TextSpans", uiText("#HeaderTitle.TextSpans", isNew() ? "New Crate"
                : "Crate " + crate.crateId));
        cmd.set("#HeaderInfo.TextSpans", uiText("#HeaderInfo.TextSpans", headerInfo(crate, live)));
        boolean hasNotice = view.notice() != null && !view.notice().isBlank();
        cmd.set("#Notice.Visible", hasNotice);
        if (hasNotice) {
            cmd.set("#Notice.TextSpans", uiText("#Notice.TextSpans", view.notice()));
        }
        EditorTabs.render(cmd, event, "#Tabs", tabs, tab, t -> INPUTS.event("tab").put("tab", t.id()));
        for (Map.Entry<String, String> step : STEP_GROUPS.entrySet()) {
            cmd.set(step.getValue() + ".Visible", step.getKey().equals(tab));
        }

        buildBlock(cmd, event, crate, spot);
        buildCheck(cmd, spot);
        buildPool(cmd, event, crate);
        buildUse(cmd, event, crate);
        buildProtect(cmd, event, crate);
        buildFinish(cmd, crate, live, spot);
        if (live != null) {
            buildRepair(cmd, event, live, spot);
        }
        buildChecklist(cmd, crate, live, spot);

        int index = indexOf(tabs, tab);
        cmd.set("#BackButton.Visible", index > 0);
        cmd.set("#NextButton.Visible", index < tabs.size() - 1);
        cmd.set("#ReloadButton.Visible", view.conflict());
        cmd.set("#OverwriteButton.Visible", view.conflict());
        cmd.set("#TestButton.Visible", published(crate.lootboxId) != null);
        cmd.set("#CreateButton.Visible", isNew());
        cmd.set("#SaveButton.Visible", !isNew() && live != null);
        for (String[] button : new String[][] {{"#BackButton", "back"}, {"#NextButton", "next"},
                {"#ReloadButton", "reload"}, {"#OverwriteButton", "overwrite"}, {"#TestButton", "test"},
                {"#CreateButton", "create"}, {"#SaveButton", "save"}, {"#CloseButton", "close"}}) {
            event.addEventBinding(CustomUIEventBindingType.Activating, button[0], INPUTS.event(button[1]));
        }
    }

    private Spot read(World here, CrateInstance crate, CrateInstance live) {
        if (!CrateService.inWorld(crate, here)) {
            return new Spot(null, null, false, false);
        }
        BlockTargets.Block block = BlockTargets.at(here, crate.x, crate.y, crate.z);
        return new Spot(block, block == null ? null : module.crates().check(block, live), block != null, true);
    }

    private void buildBlock(UICommandBuilder cmd, UIEventBuilder event, CrateInstance crate, Spot spot) {
        set(cmd, "#CrateWorld", crate.worldName == null || crate.worldName.isBlank() ? crate.worldId : crate.worldName);
        set(cmd, "#CratePosition", crate.x + ", " + crate.y + ", " + crate.z);
        set(cmd, "#CrateBlock", crate.blockKey == null ? "-" : crate.blockKey);
        set(cmd, "#CrateRotation", spot.block() == null ? "-" : rotationLabel(spot.block().rotation()));
        String status;
        if (!spot.readable()) {
            status = spot.sameWorld() ? "&eIts chunk isn't loaded; go near it"
                    : "&eCan't check from here: go to " + crate.worldName;
        } else if (!spot.block().key().equals(crate.blockKey)) {
            status = "&cThe block is now " + spot.block().key();
        } else if (isNew()) {
            status = "&aSelected " + ago(crate.createdAt);
        } else {
            status = crate.disabled ? "&eBlock matches; crate is off (" + crate.disabledReason + ")" : "&aBlock matches";
        }
        set(cmd, "#CrateBlockStatus", status);
        set(cmd, "#CrateSelectHint", isNew()
                ? "Look at another block and press Use Looked-At Block to change the selection."
                : "To move a crate, remove it and convert the new block.");
        cmd.set("#ReselectButton.Visible", isNew());
        event.addEventBinding(CustomUIEventBindingType.Activating, "#ReselectButton", INPUTS.event("reselect"));
        cmd.set("#CrateIdInput.Visible", isNew());
        cmd.set("#CrateIdFixed.Visible", !isNew());
        if (isNew()) {
            FormFields.fill(cmd, CRATE_ID, session, crate.crateId == null || crate.crateId.isBlank()
                    ? CrateRules.suggestId(crate.lootboxId, module.crates()::exists) : crate.crateId);
        } else {
            set(cmd, "#CrateIdFixed", crate.crateId);
        }
        FormFields.fill(cmd, NAME, session, crate.name);
    }

    private void buildCheck(UICommandBuilder cmd, Spot spot) {
        CrateRules.Report report = spot.report();
        String verdict;
        List<String> lines = new ArrayList<>();
        if (report == null) {
            verdict = "&eThe block can't be checked from here. Stand near it (its chunk must be loaded).";
        } else if (!report.ok()) {
            verdict = "&cRefused: " + report.problem() + ".";
        } else {
            verdict = "&aCompatible &7as &f" + kindLabel(report.kind()) + (report.warnings().isEmpty()
                    ? "&7, with nothing to warn about." : "&7, with " + report.warnings().size() + " warning(s):");
            report.warnings().forEach(warning -> lines.add("&e! &7" + warning));
        }
        set(cmd, "#CrateVerdict", verdict);
        appendLines(cmd, "#CheckList", lines.isEmpty() ? List.of("&8Nothing listed.") : lines);
    }

    private void buildPool(UICommandBuilder cmd, UIEventBuilder event, CrateInstance crate) {
        List<DropdownEntryInfo> entries = new ArrayList<>();
        boolean listed = false;
        for (LootBoxRevision revision : module.registry().publishedPools()) {
            entries.add(entry(MysticText.stripMarkup(revision.spec.displayName) + " (" + revision.lootboxId + ")",
                    revision.lootboxId));
            listed |= revision.lootboxId.equals(crate.lootboxId);
        }
        if (crate.lootboxId == null || crate.lootboxId.isBlank()) {
            entries.add(0, entry("Choose a pool...", ""));
        } else if (!listed) {
            entries.add(0, entry(crate.lootboxId + " (not published)", crate.lootboxId));
        }
        cmd.set("#PoolDropdown.Entries", entries);
        cmd.set("#PoolDropdown.Value", crate.lootboxId == null ? "" : crate.lootboxId);
        event.addEventBinding(CustomUIEventBindingType.ValueChanged, "#PoolDropdown", INPUTS.event("pool"));

        LootBoxRevision live = published(crate.lootboxId);
        set(cmd, "#CratePoolRevision", live == null ? (crate.lootboxId == null || crate.lootboxId.isBlank()
                ? "&8No pool chosen" : "&cNot published: publish it in the pool Manager first")
                : "Revision " + live.revision + (module.registry().isDisabled(live.lootboxId) ? " &e(closed)" : ""));
        set(cmd, "#CratePoolRewards", live == null ? "-" : live.spec.rewards.size() + " rewards");
        set(cmd, "#CratePoolKeys", live == null ? "-" : keyNames(live));
    }

    private void buildUse(UICommandBuilder cmd, UIEventBuilder event, CrateInstance crate) {
        set(cmd, "#CrateBehaviour", crate.directConfirm() ? "Straight to the confirm screen"
                : "Preview first, then confirm (default)");
        cmd.set("#DirectOnButton.Visible", !crate.directConfirm());
        cmd.set("#DirectOffButton.Visible", crate.directConfirm());
        event.addEventBinding(CustomUIEventBindingType.Activating, "#DirectOnButton", INPUTS.event("directOn"));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#DirectOffButton", INPUTS.event("directOff"));
    }

    private void buildProtect(UICommandBuilder cmd, UIEventBuilder event, CrateInstance crate) {
        boolean serverBreak = module.config().crates.preventPlayerBreak;
        boolean serverDamage = module.config().crates.preventDamage;
        set(cmd, "#CrateBreakState", "Players can't break it: " + protectionLabel(serverBreak,
                crate.protection.preventPlayerBreak));
        set(cmd, "#CrateDamageState", "No damage, explosions included: " + protectionLabel(serverDamage,
                crate.protection.preventDamage));
        cmd.set("#BreakOnButton.Visible", !serverBreak && !crate.protection.preventPlayerBreak);
        cmd.set("#BreakOffButton.Visible", !serverBreak && crate.protection.preventPlayerBreak);
        cmd.set("#DamageOnButton.Visible", !serverDamage && !crate.protection.preventDamage);
        cmd.set("#DamageOffButton.Visible", !serverDamage && crate.protection.preventDamage);
        for (String[] button : new String[][] {{"#BreakOnButton", "breakOn"}, {"#BreakOffButton", "breakOff"},
                {"#DamageOnButton", "damageOn"}, {"#DamageOffButton", "damageOff"}}) {
            event.addEventBinding(CustomUIEventBindingType.Activating, button[0], INPUTS.event(button[1]));
        }
    }

    private void buildFinish(UICommandBuilder cmd, CrateInstance crate, CrateInstance live, Spot spot) {
        List<String> lines = new ArrayList<>();
        lines.add("&7Crate &f" + (isNew() ? blank(crate.crateId, "(id chosen on Create)") : crate.crateId)
                + (crate.name == null || crate.name.isBlank() ? "" : " &7named &f" + crate.name));
        lines.add("&7Block &f" + crate.blockKey + " &7at &f" + crate.position());
        LootBoxRevision pool = published(crate.lootboxId);
        lines.add(pool == null ? "&cNo published pool chosen yet." : "&7Opens &f" + MysticText.stripMarkup(
                pool.spec.displayName) + " &7(revision " + pool.revision + ", " + pool.spec.rewards.size() + " rewards)");
        lines.add("&7On Use: &f" + (crate.directConfirm() ? "straight to confirm" : "preview first"));
        lines.add("&7Protection: &fbreak " + onOff(CrateRules.preventsBreak(crate,
                module.config().crates.preventPlayerBreak)) + "&7, damage &f" + onOff(CrateRules.preventsDamage(crate,
                module.config().crates.preventDamage)));
        for (String problem : problems(crate, spot)) {
            lines.add("&c! " + problem);
        }
        if (!isNew() && live != null) {
            lines.add(session.dirty() ? "&eUnsaved changes: press Save." : "&aSaved.");
            if (live.disabled) {
                lines.add("&eThe crate is off: " + live.disabledReason + ". Players are told it's inactive.");
            }
        } else if (isNew() && problems(crate, spot).isEmpty()) {
            lines.add("&aReady: press Create Crate, then Test in World.");
        }
        appendLines(cmd, "#SummaryList", lines);
    }

    private void buildRepair(UICommandBuilder cmd, UIEventBuilder event, CrateInstance live, Spot spot) {
        CrateRules.RepairPlan plan = module.crates().repairPlan(live, spot.readable() ? spot.block() : null);
        set(cmd, "#CrateRepairReason", live.disabled ? "&eOff: " + live.disabledReason
                : "&aThis crate is on. Nothing to repair.");
        set(cmd, "#CrateRepairStatus", plan.summary());
        cmd.set("#ReenableButton.Visible", plan.actions().contains(CrateRules.RepairAction.REENABLE));
        cmd.set("#RebindBlockButton.Visible", plan.actions().contains(CrateRules.RepairAction.REBIND_BLOCK));
        for (String[] button : new String[][] {{"#ReenableButton", "reenable"}, {"#RebindBlockButton", "rebindBlock"},
                {"#RecheckButton", "recheck"}, {"#RepairRemoveButton", "repairRemove"}}) {
            event.addEventBinding(CustomUIEventBindingType.Activating, button[0], INPUTS.event(button[1]));
        }
    }

    private void buildChecklist(UICommandBuilder cmd, CrateInstance crate, CrateInstance live, Spot spot) {
        List<String> lines = new ArrayList<>();
        if (live != null && live.disabled) {
            lines.add("&e[!] &7Repair: off, " + live.disabledReason);
        }
        lines.add((spot.readable() ? (spot.block().key().equals(crate.blockKey) ? "&a[ok]" : "&c[x]") : "&e[?]")
                + " &71 Block: &f" + crate.blockKey);
        CrateRules.Report report = spot.report();
        lines.add(report == null ? "&e[?] &72 Check: stand near it" : !report.ok() ? "&c[x] &72 Check: refused"
                : report.warnings().isEmpty() ? "&a[ok] &72 Check: compatible"
                : "&e[!] &72 Check: " + report.warnings().size() + " warning(s)");
        LootBoxRevision pool = published(crate.lootboxId);
        lines.add((pool != null ? "&a[ok]" : "&c[x]") + " &73 Pool: &f" + (pool != null ? pool.lootboxId
                : crate.lootboxId == null || crate.lootboxId.isBlank() ? "none" : crate.lootboxId + " (unpublished)"));
        lines.add("&a[ok] &74 Use: &f" + (crate.directConfirm() ? "direct confirm" : "preview first"));
        lines.add("&a[ok] &75 Protect: &f" + (crate.protection.preventPlayerBreak || crate.protection.preventDamage
                ? "tightened" : "server default"));
        lines.add((isNew() ? "&8[ ]" : session.dirty() ? "&e[!]" : "&a[ok]") + " &76 Finish: &f"
                + (isNew() ? "not created yet" : session.dirty() ? "unsaved changes" : "saved"));
        appendLines(cmd, "#Checklist", lines);
    }

    // ----- Events -------------------------------------------------------------

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
        if (!current()) {
            core.getMessageService().send(player, "&eThat editor was closed; open the crate again from the Crate Manager.");
            close(ref, store);
            return;
        }
        if (!player.hasPermission(isNew() ? Permissions.LOOTBOX_CRATES_PLACE : Permissions.LOOTBOX_CRATES_MANAGE)) {
            core.getMessageService().sendKey(player, "no-permission");
            close(ref, store);
            return;
        }
        JsonObject payload = parse(data);
        String action = string(payload, "action");
        INPUTS.remember(payload, session);
        applyInputs();
        CrateInstance crate = session.working();
        switch (action) {
            case "tab" -> {
                session.tab(field(payload, "tab"));
                reopen(ref, store, page(null));
            }
            case "back", "next" -> {
                List<EditorTabs.Tab> tabs = tabs(isNew() ? null : module.crates().get(session.draftId()).orElse(null));
                int index = indexOf(tabs, EditorTabs.resolve(tabs, session.tab())) + ("next".equals(action) ? 1 : -1);
                session.tab(tabs.get(Math.max(0, Math.min(tabs.size() - 1, index))).id());
                reopen(ref, store, page(null));
            }
            case "reselect" -> reselect(ref, store);
            case "directOn", "directOff" -> edit(ref, store, () -> crate.openBehavior = "directOn".equals(action)
                    ? CrateInstance.OPEN_DIRECT_CONFIRM : CrateInstance.OPEN_PREVIEW_THEN_CONFIRM);
            case "breakOn", "breakOff" -> edit(ref, store,
                    () -> crate.protection.preventPlayerBreak = "breakOn".equals(action));
            case "damageOn", "damageOff" -> edit(ref, store,
                    () -> crate.protection.preventDamage = "damageOn".equals(action));
            case "create" -> create(ref, store);
            case "save" -> save(ref, store, session.loadedHash());
            case "reload" -> reload(ref, store, "&7Loaded the crate as it is now; your unsaved changes were dropped.");
            case "overwrite" -> save(ref, store, null);
            case "test" -> test(ref, store);
            case "reenable" -> repair(ref, store, CrateRules.RepairAction.REENABLE);
            case "rebindBlock" -> repair(ref, store, CrateRules.RepairAction.REBIND_BLOCK);
            case "repairRemove" -> confirmRemove(ref, store);
            case "close" -> confirmClose(ref, store);
            default -> reopen(ref, store, page(null));
        }
    }

    /** Copies the text inputs into the working copy; ids are checked on Create. */
    private void applyInputs() {
        CrateInstance crate = session.working();
        boolean changed = false;
        String name = session.input(NAME.key());
        if (name != null) {
            String next = name.trim().length() > MAX_NAME_LENGTH ? name.trim().substring(0, MAX_NAME_LENGTH) : name.trim();
            changed |= !next.equals(crate.name);
            crate.name = next;
            session.forgetInput(NAME.key());
        }
        String pool = session.input(POOL.key());
        if (pool != null) {
            if (!pool.isBlank() && !pool.equals(crate.lootboxId)) {
                crate.lootboxId = pool.toLowerCase(Locale.ROOT);
                changed = true;
            }
            session.forgetInput(POOL.key());
        }
        String id = session.input(CRATE_ID.key());
        if (id != null && isNew()) {
            String next = id.trim().toLowerCase(Locale.ROOT);
            changed |= !next.equals(crate.crateId == null ? "" : crate.crateId);
            crate.crateId = next;
            session.forgetInput(CRATE_ID.key());
        }
        if (changed) {
            session.markDirty();
        }
    }

    private void edit(Ref<EntityStore> ref, Store<EntityStore> store, Runnable change) {
        change.run();
        session.markDirty();
        reopen(ref, store, page(null));
    }

    /** Selects the block the player looks at instead (new crates only). We are on the world thread. */
    private void reselect(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (!isNew()) {
            reopen(ref, store, page(null));
            return;
        }
        double reach = module.config().crates.selectReach;
        BlockTargets.Block block = BlockTargets.target(ref, store, reach);
        if (block == null) {
            reopen(ref, store, page("&cLook at a block within " + (int) reach + " blocks, then press it again."));
            return;
        }
        CrateInstance existing = module.crates().at(block.world(), block.x(), block.y(), block.z()).orElse(null);
        if (existing != null) {
            reopen(ref, store, page("&eThat block is already crate &f" + existing.crateId
                    + "&e. Pick another, or edit that one from the Crate Manager."));
            return;
        }
        CrateInstance crate = session.working();
        select(crate, block);
        crate.createdAt = System.currentTimeMillis();
        session.markDirty();
        CrateRules.Report report = module.crates().check(block, null);
        reopen(ref, store, page(report.ok() ? "&7Selected &f" + block.key() + "&7."
                : "&cSelected &f" + block.key() + "&c, but it can't be a crate: " + report.problem() + "."));
    }

    /** Creates the crate from the working copy after judging the block again (bible §09 step 07). */
    private void create(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (!allowed(Permissions.LOOTBOX_CRATES_PLACE)) {
            return;
        }
        CrateInstance crate = session.working();
        World here = store.getExternalData().getWorld();
        if (!CrateService.inWorld(crate, here)) {
            reopen(ref, store, page("&cGo back to &f" + crate.worldName + " &cto create this crate; the block is "
                    + "checked again when it's made."));
            return;
        }
        List<String> problems = problems(crate, read(here, crate, null));
        if (!problems.isEmpty()) {
            session.tab(FINISH.id());
            reopen(ref, store, page("&c" + problems.get(0)));
            return;
        }
        BlockTargets.Block block = BlockTargets.at(here, crate.x, crate.y, crate.z);
        CrateService.ConvertResult result = module.crates().create(block, crate, player.getUsername());
        if (result.problem() != null) {
            reopen(ref, store, page("&cNot created: " + result.problem() + "."));
            return;
        }
        module.editors().end(session);
        CrateEditorPage created = forCrate(core, player, module, result.crate(), "&aCreated crate &f"
                + result.crate().crateId + "&a. Press Test in World, or use the block."
                + (result.warning() == null ? "" : " &e" + result.warning()));
        created.session.tab(FINISH.id());
        reopen(ref, store, created);
    }

    private void save(Ref<EntityStore> ref, Store<EntityStore> store, String expectedHash) {
        if (!allowed(Permissions.LOOTBOX_CRATES_MANAGE)) {
            return;
        }
        CrateInstance crate = session.working();
        CrateInstance live = module.crates().get(session.draftId()).orElse(null);
        if (live == null) {
            reopen(ref, store, page("&cThis crate was removed meanwhile; close the editor."));
            return;
        }
        if (!java.util.Objects.equals(live.lootboxId, crate.lootboxId)) {
            if (!allowed(Permissions.LOOTBOX_CRATES_PLACE)) {
                return;
            }
            if (published(crate.lootboxId) == null) {
                session.tab(POOL_TAB.id());
                reopen(ref, store, page("&c" + crate.lootboxId + " has no published revision; publish it first."));
                return;
            }
        }
        long changes = session.changes();
        CrateService.SaveResult result = module.crates().update(live.crateId, crate, expectedHash, player.getUsername());
        switch (result) {
            case CrateService.Saved saved -> {
                session.markSaved(saved.hash(), changes);
                reopen(ref, store, page(expectedHash == null ? "&aOverwrote the crate with your version."
                        : "&aSaved."));
            }
            case CrateService.Conflict conflict -> reopen(ref, store, new CrateEditorPage(core, player, module,
                    session, new PoolEditorPage.View("&eSomeone saved this crate since you opened it (it now opens &f"
                            + conflict.current().lootboxId + "&e). Load Current drops your changes; Overwrite keeps "
                            + "yours.", true)));
            case CrateService.Refused refused -> reopen(ref, store, page("&cNot saved: " + refused.reason() + "."));
        }
    }

    private void reload(Ref<EntityStore> ref, Store<EntityStore> store, String notice) {
        CrateInstance live = module.crates().get(session.draftId()).orElse(null);
        if (live == null) {
            reopen(ref, store, page("&cThis crate was removed meanwhile; close the editor."));
            return;
        }
        session.reload(live.copy(), CrateRules.settingsHash(live));
        reopen(ref, store, page(notice));
    }

    /** Opens the crate the way a player's Use would, with the unsaved behaviour. */
    private void test(Ref<EntityStore> ref, Store<EntityStore> store) {
        CrateInstance crate = session.working();
        LootBoxRevision pool = published(crate.lootboxId);
        if (pool == null) {
            session.tab(POOL_TAB.id());
            reopen(ref, store, page("&cChoose a published pool first."));
            return;
        }
        if (crate.disabled) {
            core.getMessageService().send(player, "&eThis crate is off, so players get \"This crate is inactive.\" "
                    + "Showing what it would open.");
        }
        if (session.dirty() || isNew()) {
            core.getMessageService().send(player, isNew()
                    ? "&7Your setup is kept: look at the same block and press Convert in the Crate Manager to go on."
                    : "&7Your unsaved changes are kept; press Edit in the Crate Manager to go on.");
        }
        close(ref, store);
        module.ui().openPreview(player, pool.lootboxId, isNew() ? null : crate.crateId, crate.directConfirm());
    }

    private void repair(Ref<EntityStore> ref, Store<EntityStore> store, CrateRules.RepairAction action) {
        if (!allowed(Permissions.LOOTBOX_CRATES_MANAGE)) {
            return;
        }
        CrateInstance live = module.crates().get(session.draftId()).orElse(null);
        if (live == null) {
            reopen(ref, store, page("&cThis crate was removed meanwhile; close the editor."));
            return;
        }
        World here = store.getExternalData().getWorld();
        BlockTargets.Block current = CrateService.inWorld(live, here) ? BlockTargets.at(here, live.x, live.y, live.z)
                : null;
        String refused = module.crates().repair(live, current, action, player.getUsername());
        if (refused != null) {
            reopen(ref, store, page("&e" + refused));
            return;
        }
        session.reload(live.copy(), CrateRules.settingsHash(live));
        session.tab(FINISH.id());
        reopen(ref, store, page(action == CrateRules.RepairAction.REENABLE ? "&aThe crate is on again."
                : "&aRecorded &f" + live.blockKey + " &aand turned the crate back on."));
    }

    private void confirmRemove(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (!allowed(Permissions.LOOTBOX_CRATES_MANAGE)) {
            return;
        }
        String id = session.draftId();
        ConfirmActionPage.Spec spec = new ConfirmActionPage.Spec("Remove " + id + "?", List.of(
                "&7Removes the crate. The block stays where it is and goes back to its normal behaviour.",
                "&7Players' keys and receipts are untouched."), "Remove", true, Permissions.LOOTBOX_CRATES_MANAGE);
        reopen(ref, store, new ConfirmActionPage(core, player, spec, (r, s) -> {
            boolean removed = module.crates().remove(id, player.getUsername()).isPresent();
            module.editors().end(session);
            reopen(r, s, new CrateManagerPage(core, player, module, CrateManagerPage.State.selecting(null,
                    removed ? "&7Removed crate &f" + id + "&7." : "&c" + id + " was already gone.")));
        }, (r, s) -> reopen(r, s, page(null))));
    }

    private void confirmClose(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (!session.dirty()) {
            closeEditor(ref, store, null);
            return;
        }
        ConfirmActionPage.Spec spec = new ConfirmActionPage.Spec("Close without saving?", List.of(
                isNew() ? "&7The crate hasn't been created; closing drops the selection and settings."
                        : "&7You have unsaved changes to &f" + session.draftId() + "&7. Closing drops them."),
                "Discard and Close", true, null);
        reopen(ref, store, new ConfirmActionPage(core, player, spec, (r, s) -> closeEditor(r, s,
                "&7Closed without saving."), (r, s) -> reopen(r, s, page(null))));
    }

    private void closeEditor(Ref<EntityStore> ref, Store<EntityStore> store, String notice) {
        module.editors().end(session);
        reopen(ref, store, new CrateManagerPage(core, player, module,
                CrateManagerPage.State.selecting(isNew() ? null : session.draftId(), notice)));
    }

    // ----- Helpers ------------------------------------------------------------

    /** What still stops a new crate from being created, or a saved one from opening. */
    private List<String> problems(CrateInstance crate, Spot spot) {
        List<String> problems = new ArrayList<>();
        if (spot.report() != null && !spot.report().ok()) {
            problems.add("The block can't be a crate: " + spot.report().problem() + ".");
        } else if (spot.readable() && !spot.block().key().equals(crate.blockKey)) {
            problems.add("The block changed to " + spot.block().key() + (isNew()
                    ? "; select it again." : "; repair the crate."));
        } else if (!spot.readable() && isNew()) {
            problems.add("Stand near the block so it can be checked.");
        }
        if (published(crate.lootboxId) == null) {
            problems.add(crate.lootboxId == null || crate.lootboxId.isBlank() ? "Choose a pool on step 3."
                    : crate.lootboxId + " has no published revision.");
        }
        if (isNew() && crate.crateId != null && !crate.crateId.isBlank()
                && (!CrateRules.CRATE_ID.matcher(crate.crateId).matches() || module.crates().exists(crate.crateId))) {
            problems.add("Crate id '" + crate.crateId + "' is taken or invalid: 3-48 of a-z, 0-9 and _.");
        }
        return problems;
    }

    private List<EditorTabs.Tab> tabs(CrateInstance live) {
        List<EditorTabs.Tab> tabs = new ArrayList<>();
        if (live != null && live.disabled && !disabledByStaff(live)) {
            tabs.add(REPAIR);
        }
        tabs.addAll(List.of(BLOCK, CHECK, POOL_TAB, USE, PROTECT, FINISH));
        return tabs;
    }

    private String headerInfo(CrateInstance crate, CrateInstance live) {
        StringBuilder text = new StringBuilder(isNew() ? "not created yet" : live == null ? "removed"
                : live.disabled ? "off" : "active");
        if (session.dirty()) {
            text.append(" · unsaved");
        }
        if (!isNew()) {
            List<UUID> others = module.editors().editing(CrateInstance.class, crate.crateId, player.getUuid());
            if (!others.isEmpty()) {
                text.append(" · also open by ").append(others.size()).append(" other(s)");
            }
        }
        return text.toString();
    }

    private LootBoxRevision published(String lootboxId) {
        return lootboxId == null || lootboxId.isBlank() ? null : module.registry().active(lootboxId).orElse(null);
    }

    private String keyNames(LootBoxRevision revision) {
        List<String> names = new ArrayList<>();
        for (String keyId : revision.spec.keys) {
            names.add(module.registry().key(keyId).map(key -> MysticText.stripMarkup(key.spec.name)).orElse(keyId));
        }
        return names.isEmpty() ? "none linked" : String.join(" or ", names);
    }

    private static String protectionLabel(boolean server, boolean crate) {
        return server ? "&aon for every crate (server)" : crate ? "&aon for this crate" : "&7off (server default)";
    }

    private static String kindLabel(String kind) {
        return CrateInstance.KIND_SHIPPED.equals(kind) ? "a Mystic crate block" : "an interactive block";
    }

    private static String rotationLabel(int rotation) {
        return rotation == 0 ? "0 (default)" : Integer.toString(rotation);
    }

    private static String onOff(boolean on) {
        return on ? "on" : "off";
    }

    private static String ago(long at) {
        long seconds = Math.max(0, (System.currentTimeMillis() - at) / 1000L);
        return seconds < 60 ? seconds + "s ago" : seconds / 60 + "m ago";
    }

    private static String blank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int indexOf(List<EditorTabs.Tab> tabs, String id) {
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).id().equals(id)) {
                return i;
            }
        }
        return 0;
    }

    private static void appendLines(UICommandBuilder cmd, String list, List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String row = list + "[" + i + "]";
            cmd.append(list, LINE_UI);
            cmd.set(row + " #Line.TextSpans", uiText(row + " #Line.TextSpans", lines.get(i)));
        }
    }

    private static void set(UICommandBuilder cmd, String id, String text) {
        cmd.set(id + ".TextSpans", uiText(id + ".TextSpans", text));
    }

    private static DropdownEntryInfo entry(String label, String value) {
        return new DropdownEntryInfo(LocalizableString.fromString(label), value);
    }

    private boolean isNew() {
        return NEW_ID.equals(session.draftId());
    }

    private boolean current() {
        return session.active() && module.editors().get(player.getUuid()).map(open -> open == session).orElse(false);
    }

    private CrateEditorPage page(String notice) {
        return new CrateEditorPage(core, player, module, session, PoolEditorPage.View.of(notice));
    }

    private boolean allowed(String node) {
        if (player.hasPermission(node)) {
            return true;
        }
        core.getMessageService().sendKey(player, "no-permission");
        return false;
    }
}
