package org.hyzionstudios.mysticessentials.modules.lootbox.ui.editor;

import java.util.ArrayList;
import java.util.List;

import org.hyzionstudios.mysticessentials.api.Permissions;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.modules.lootbox.LootBoxModule;
import org.hyzionstudios.mysticessentials.modules.lootbox.hytale.CrateVisuals;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.CrateInstance;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.CrateService;
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
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * The crate effect stack (crate editor step 6, Edit Effect Stack): any number
 * of particles and sounds on top of the crate's three effect slots, each with
 * its own phase, height, scale and idle interval.
 *
 * <p>It edits the crate editor's own {@link EditorSession}, so nothing is saved
 * here: Back to Crate returns to step 6, where Save or Create Crate keeps the
 * stack with the rest of the crate. The id dropdown is filled from the live
 * registries ({@link CrateVisuals#particleIds}), filtered by the search box,
 * because 0.7.2 ships 3070 particle systems: too many for one dropdown.</p>
 */
public final class CrateEffectsPage extends MysticPage {

    static final String PAGE_UI = "MysticEssentials/LootBoxCrateEffects.ui";
    static final String ROW_UI = "MysticEssentials/LootBoxCrateEffectRow.ui";

    static final FormFields.Field PHASE = FormFields.Field.text("effectPhase", "EffectPhaseDropdown", "When");
    static final FormFields.Field SEARCH = FormFields.Field.text("effectSearch", "EffectSearchInput", "Search");
    static final FormFields.Field EFFECT_ID = FormFields.Field.text("effectId", "EffectIdDropdown", "Effect");
    static final FormFields.Field HEIGHT = FormFields.Field.text("effectHeight", "EffectHeightDropdown", "Height");
    static final FormFields.Field SCALE = FormFields.Field.text("effectScale", "EffectScaleDropdown", "Scale");
    static final FormFields.Field INTERVAL = FormFields.Field.text("effectInterval", "EffectIntervalDropdown",
            "Repeats every");
    static final FormFields INPUTS = FormFields.of(PHASE, SEARCH, EFFECT_ID, HEIGHT, SCALE, INTERVAL);

    /** How many matching ids the dropdown offers at once. */
    private static final int MATCH_LIMIT = 60;
    private static final String NONE = "none";
    private static final double[] HEIGHTS = {0.0, 0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 2.5, 3.0};
    private static final double[] SCALES = {0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 3.0};
    private static final long[] INTERVALS = {1000L, 2000L, 3000L, 5000L, 8000L, 12000L, 20000L};

    private final LootBoxModule module;
    private final EditorSession<CrateInstance> session;
    private final String notice;

    public CrateEffectsPage(MysticCore core, PlayerRef player, LootBoxModule module,
            EditorSession<CrateInstance> session, String notice) {
        super(core, player, CustomPageLifetime.CanDismiss);
        this.module = module;
        this.session = session;
        this.notice = notice;
    }

    // ----- Build --------------------------------------------------------------

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event, Store<EntityStore> store) {
        cmd.append(PAGE_UI);
        CrateInstance crate = session.working();
        List<CrateInstance.Effect> stack = crate.visuals.extras;
        int max = Math.max(0, module.config().crates.maxStackedEffects);
        int selected = selected();

        cmd.set("#HeaderInfo.TextSpans", uiText("#HeaderInfo.TextSpans", "&7Crate &f"
                + (CrateEditorPage.NEW_ID.equals(session.draftId()) ? "(new)" : crate.crateId)
                + " &7· &f" + stack.size() + "&7/" + max + " effects"));
        boolean hasNotice = notice != null && !notice.isBlank();
        cmd.set("#Notice.Visible", hasNotice);
        if (hasNotice) {
            cmd.set("#Notice.TextSpans", uiText("#Notice.TextSpans", notice));
        }

        for (int i = 0; i < stack.size(); i++) {
            CrateInstance.Effect effect = stack.get(i);
            String row = "#EffectList[" + i + "]";
            cmd.append("#EffectList", ROW_UI);
            cmd.set(row + " #Selected.Visible", i == selected);
            boolean loaded = loaded(effect);
            cmd.set(row + " #Name.TextSpans", uiText(row + " #Name.TextSpans",
                    (loaded ? "&f" : "&c") + (effect.id.isEmpty() ? "(choose an id)" : effect.id)));
            cmd.set(row + " #Meta.TextSpans", uiText(row + " #Meta.TextSpans", "&7" + meta(effect, i >= max)));
            event.addEventBinding(CustomUIEventBindingType.Activating, row,
                    INPUTS.event("select").put("row", Integer.toString(i)));
        }

        boolean any = selected >= 0;
        cmd.set("#EffectFields.Visible", any);
        cmd.set("#EffectEmpty.Visible", !any);
        cmd.set("#RemoveButton.Visible", any);
        cmd.set("#AddParticleButton.Visible", stack.size() < max);
        cmd.set("#AddSoundButton.Visible", stack.size() < max);
        if (any) {
            buildFields(cmd, event, stack.get(selected));
        }
        for (String[] button : new String[][] {{"#AddParticleButton", "addParticle"}, {"#AddSoundButton", "addSound"},
                {"#RemoveButton", "remove"}, {"#PreviewButton", "preview"}, {"#DoneButton", "done"}}) {
            event.addEventBinding(CustomUIEventBindingType.Activating, button[0], INPUTS.event(button[1]));
        }
    }

    private void buildFields(UICommandBuilder cmd, UIEventBuilder event, CrateInstance.Effect effect) {
        boolean sound = CrateInstance.Effect.KIND_SOUND.equals(effect.kind);
        List<DropdownEntryInfo> phases = new ArrayList<>();
        phases.add(entry("When it opens", CrateInstance.Effect.PHASE_OPEN));
        if (!sound) {
            phases.add(entry("While players are near (idle)", CrateInstance.Effect.PHASE_IDLE));
        }
        dropdown(cmd, event, PHASE, phases, effect.phase);

        String query = session.input(SEARCH.key());
        FormFields.fill(cmd, SEARCH, session, "");
        // A button, not ValueChanged: rebuilding the page on every keystroke would
        // drop the text field's focus after each character.
        event.addEventBinding(CustomUIEventBindingType.Activating, "#EffectSearchButton", INPUTS.event("search"));
        List<String> matches = sound ? CrateVisuals.soundIds(query, MATCH_LIMIT)
                : CrateVisuals.particleIds(query, MATCH_LIMIT);
        List<DropdownEntryInfo> ids = new ArrayList<>();
        ids.add(entry("Choose " + (sound ? "a sound" : "a particle") + (matches.size() == MATCH_LIMIT
                ? " (first " + MATCH_LIMIT + " matches; search to narrow)" : ""), NONE));
        if (!effect.id.isEmpty() && !matches.contains(effect.id)) {
            ids.add(entry(effect.id + (loaded(effect) ? "" : " (not loaded)"), effect.id));
        }
        matches.forEach(id -> ids.add(entry(id, id)));
        dropdown(cmd, event, EFFECT_ID, ids, effect.id.isEmpty() ? NONE : effect.id);

        List<DropdownEntryInfo> heights = new ArrayList<>();
        for (double h : withCurrent(HEIGHTS, effect.height)) {
            heights.add(entry(h + " blocks", Double.toString(h)));
        }
        dropdown(cmd, event, HEIGHT, heights, Double.toString(effect.height));

        cmd.set("#EffectScaleGroup.Visible", !sound);
        cmd.set("#EffectIntervalGroup.Visible", !sound && CrateInstance.Effect.PHASE_IDLE.equals(effect.phase));
        if (!sound) {
            List<DropdownEntryInfo> scales = new ArrayList<>();
            for (double s : withCurrent(SCALES, effect.scale)) {
                scales.add(entry("x" + s, Double.toString(s)));
            }
            dropdown(cmd, event, SCALE, scales, Double.toString(effect.scale));
            List<DropdownEntryInfo> intervals = new ArrayList<>();
            List<Long> options = new ArrayList<>();
            for (long ms : INTERVALS) {
                options.add(ms);
            }
            if (!options.contains(effect.intervalMs)) {
                options.add(effect.intervalMs);
                options.sort(Long::compare);
            }
            options.forEach(ms -> intervals.add(entry(ms / 1000.0 + " s", Long.toString(ms))));
            dropdown(cmd, event, INTERVAL, intervals, Long.toString(effect.intervalMs));
        }
        cmd.set("#EffectHint.TextSpans", uiText("#EffectHint.TextSpans", sound
                ? "&7Sounds play once at the crate when an opening commits, for nearby players with effects on."
                : CrateInstance.Effect.PHASE_IDLE.equals(effect.phase)
                        ? "&7Idle particles repeat no faster than crates.idleEffectIntervalMs, within the server's "
                                + "per-world and per-player particle budget."
                        : "&7Plays once at the crate when an opening commits, for nearby players with effects on."));
    }

    private static void dropdown(UICommandBuilder cmd, UIEventBuilder event, FormFields.Field field,
            List<DropdownEntryInfo> entries, String value) {
        String selector = "#" + field.elementId();
        cmd.set(selector + ".Entries", entries);
        cmd.set(selector + ".Value", value);
        event.addEventBinding(CustomUIEventBindingType.ValueChanged, selector, INPUTS.event("field"));
    }

    // ----- Events -------------------------------------------------------------

    @Override
    protected void onEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
        if (!current()) {
            core.getMessageService().send(player, "&eThat editor was closed; open the crate again from the Crate Manager.");
            close(ref, store);
            return;
        }
        boolean isNew = CrateEditorPage.NEW_ID.equals(session.draftId());
        if (!player.hasPermission(isNew ? Permissions.LOOTBOX_CRATES_PLACE : Permissions.LOOTBOX_CRATES_MANAGE)) {
            core.getMessageService().sendKey(player, "no-permission");
            close(ref, store);
            return;
        }
        JsonObject payload = parse(data);
        String action = string(payload, "action");
        INPUTS.remember(payload, session);
        applyInputs();
        List<CrateInstance.Effect> stack = session.working().visuals.extras;
        switch (action) {
            case "select" -> {
                session.selectRow(parseIndex(field(payload, "row")));
                session.forgetInput(SEARCH.key());
                reopen(ref, store, page(null));
            }
            case "addParticle", "addSound" -> {
                if (stack.size() >= Math.max(0, module.config().crates.maxStackedEffects)) {
                    reopen(ref, store, page("&eThe stack is full (crates.maxStackedEffects)."));
                    return;
                }
                CrateInstance.Effect effect = new CrateInstance.Effect();
                effect.kind = "addSound".equals(action) ? CrateInstance.Effect.KIND_SOUND
                        : CrateInstance.Effect.KIND_PARTICLE;
                effect.normalise();
                stack.add(effect);
                session.selectRow(stack.size() - 1);
                session.forgetInput(SEARCH.key());
                session.markDirty();
                reopen(ref, store, page("&7Added. Search for an id, then pick it from the list."));
            }
            case "remove" -> {
                int index = selected();
                if (index >= 0) {
                    stack.remove(index);
                    session.selectRow(Math.min(index, stack.size() - 1));
                    session.forgetInput(SEARCH.key());
                    session.markDirty();
                }
                reopen(ref, store, page(null));
            }
            case "preview" -> preview(ref, store);
            case "done" -> reopen(ref, store, new CrateEditorPage(core, player, module, session,
                    PoolEditorPage.View.of(session.dirty() ? "&7Effect stack changed: Save (or Create Crate) keeps it."
                            : null)));
            default -> reopen(ref, store, page(null));
        }
    }

    /** Applies the selected effect's dropdowns to it; the search text stays for the next build. */
    private void applyInputs() {
        int index = selected();
        if (index < 0) {
            forgetFields();
            return;
        }
        CrateInstance.Effect effect = session.working().visuals.extras.get(index);
        String before = effect.label();
        String phase = take(PHASE);
        if (phase != null) {
            effect.phase = phase;
        }
        String id = take(EFFECT_ID);
        if (id != null) {
            effect.id = NONE.equals(id) ? "" : id;
        }
        String height = take(HEIGHT);
        if (height != null) {
            effect.height = parseDouble(height, effect.height);
        }
        String scale = take(SCALE);
        if (scale != null) {
            effect.scale = parseDouble(scale, effect.scale);
        }
        String interval = take(INTERVAL);
        if (interval != null) {
            effect.intervalMs = (long) parseDouble(interval, effect.intervalMs);
        }
        effect.normalise();
        if (!before.equals(effect.label())) {
            session.markDirty();
        }
    }

    private String take(FormFields.Field field) {
        String value = session.input(field.key());
        session.forgetInput(field.key());
        return value;
    }

    private void forgetFields() {
        for (FormFields.Field field : new FormFields.Field[] {PHASE, EFFECT_ID, HEIGHT, SCALE, INTERVAL}) {
            session.forgetInput(field.key());
        }
    }

    /** Plays the whole working copy (slots and stack) to this player alone; the crate must be in their world. */
    private void preview(Ref<EntityStore> ref, Store<EntityStore> store) {
        CrateInstance crate = session.working();
        if (!CrateService.inWorld(crate, store.getExternalData().getWorld())) {
            reopen(ref, store, page("&eGo to &f" + crate.worldName + " &eto preview the effects at the crate."));
            return;
        }
        String played = module.visuals().preview(ref, store, crate);
        reopen(ref, store, page("&7Played " + played + " at the crate, for you only."));
    }

    // ----- Helpers ------------------------------------------------------------

    private int selected() {
        int size = session.working().visuals.extras.size();
        if (size == 0) {
            return -1;
        }
        int row = session.selectedRow();
        return row < 0 || row >= size ? 0 : row;
    }

    private static boolean loaded(CrateInstance.Effect effect) {
        return CrateInstance.Effect.KIND_SOUND.equals(effect.kind) ? CrateVisuals.soundExists(effect.id)
                : CrateVisuals.particleExists(effect.id);
    }

    private static String meta(CrateInstance.Effect effect, boolean overCap) {
        StringBuilder text = new StringBuilder(CrateInstance.Effect.KIND_SOUND.equals(effect.kind) ? "sound" : "particle")
                .append(" · ").append(CrateInstance.Effect.PHASE_IDLE.equals(effect.phase) ? "idle" : "on open")
                .append(" · ").append(effect.height).append(" up");
        if (CrateInstance.Effect.KIND_PARTICLE.equals(effect.kind)) {
            text.append(" · x").append(effect.scale);
            if (CrateInstance.Effect.PHASE_IDLE.equals(effect.phase)) {
                text.append(" · every ").append(effect.intervalMs / 1000.0).append("s");
            }
        }
        if (overCap) {
            text.append(" · &cover crates.maxStackedEffects, not played");
        }
        return text.toString();
    }

    private static double[] withCurrent(double[] options, double current) {
        for (double option : options) {
            if (option == current) {
                return options;
            }
        }
        double[] all = java.util.Arrays.copyOf(options, options.length + 1);
        all[options.length] = current;
        java.util.Arrays.sort(all);
        return all;
    }

    private static int parseIndex(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException | NullPointerException e) {
            return -1;
        }
    }

    private static DropdownEntryInfo entry(String label, String value) {
        return new DropdownEntryInfo(LocalizableString.fromString(label), value);
    }

    private boolean current() {
        return session.active() && module.editors().get(player.getUuid()).map(open -> open == session).orElse(false);
    }

    private CrateEffectsPage page(String message) {
        return new CrateEffectsPage(core, player, module, session, message);
    }
}
