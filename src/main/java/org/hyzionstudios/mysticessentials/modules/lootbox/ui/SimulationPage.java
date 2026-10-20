package org.hyzionstudios.mysticessentials.modules.lootbox.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.api.Permissions;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.message.MysticText;
import org.hyzionstudios.mysticessentials.modules.lootbox.LootBoxModule;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxRevision;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.OddsEngine;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.OddsFormatter;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.SimulationReport;
import org.hyzionstudios.mysticessentials.modules.lootbox.ui.editor.FormFields;
import org.hyzionstudios.mysticessentials.platform.ui.MysticPage;

import com.google.gson.JsonObject;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * The Simulation screen (P2-H): draws from a pool's live revision through
 * {@code OpeningService.simulate}, which awards nothing, spends no key and
 * records nothing, then shows every reward's exact chance against the observed
 * share with bars, counts and the gap. The rarest reward gets a small-sample
 * warning when too few draws expect it ({@link SimulationReport}).
 *
 * <p>The draw runs on a worker thread and reopens the page with the results;
 * each player runs one simulation at a time, capped by
 * {@code simulation.maxTrials}. Run re-checks {@code simulate} on every press.</p>
 */
public final class SimulationPage extends MysticPage {

    static final String SIMULATION_UI = "MysticEssentials/LootBoxSimulation.ui";
    static final String ROW_UI = "MysticEssentials/LootBoxSimulationRow.ui";

    /** What the Manager's Simulate button draws, unless the cap is lower. */
    static final long DEFAULT_DRAWS = 100_000L;
    private static final int BAR_HEIGHT = 6;

    private static final FormFields.Field DRAWS = FormFields.Field.text("draws", "#DrawsInput", "Draws");
    private static final FormFields INPUTS = FormFields.of(DRAWS);

    /** Players whose simulation is still drawing; one each, so a double press cannot stack runs. */
    private static final Set<UUID> RUNNING = ConcurrentHashMap.newKeySet();

    /**
     * What the page shows.
     *
     * @param draws     the draw count as typed
     * @param report    the last run's results, or {@code null}
     * @param elapsedMs how long the last run took
     */
    public record State(String lootboxId, String draws, SimulationReport report, long elapsedMs, String notice) {

        static State of(String lootboxId, long draws) {
            return new State(lootboxId, Long.toString(draws), null, 0, null);
        }

        State withDraws(String text) {
            return new State(lootboxId, text, report, elapsedMs, notice);
        }

        State withReport(SimulationReport next, long elapsed) {
            return new State(lootboxId, draws, next, elapsed, null);
        }

        State withNotice(String text) {
            return new State(lootboxId, draws, report, elapsedMs, text);
        }
    }

    private final LootBoxModule module;
    private final State state;

    public SimulationPage(MysticCore core, PlayerRef player, LootBoxModule module, State state) {
        super(core, player, CustomPageLifetime.CanDismiss);
        this.module = module;
        this.state = state;
    }

    /**
     * Opens the screen for {@code lootboxId} and starts the default run at once,
     * as the Manager's Simulate button did before this screen existed. Call from
     * a handler that has already checked {@code simulate}.
     */
    static void openAndRun(MysticCore core, PlayerRef player, LootBoxModule module, Ref<EntityStore> ref,
            Store<EntityStore> store, String lootboxId) {
        long draws = Math.min(DEFAULT_DRAWS, maxDraws(module));
        new SimulationPage(core, player, module, State.of(lootboxId, draws)).run(ref, store, State.of(lootboxId, draws));
    }

    // ----- Build --------------------------------------------------------------

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event, Store<EntityStore> store) {
        cmd.append(SIMULATION_UI);
        LootBoxRevision live = module.registry().active(state.lootboxId()).orElse(null);
        cmd.set("#HeaderInfo.TextSpans", uiText("#HeaderInfo.TextSpans", header(live)));
        cmd.set("#DrawsInput.Value", state.draws() == null ? "" : state.draws());
        cmd.set("#SimHint.TextSpans", uiText("#SimHint.TextSpans", "1 to " + grouped(maxDraws(module))
                + " draws (simulation.maxTrials) from the live revision's odds. No key is spent, nobody receives a"
                + " reward and nothing is recorded."));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#RunButton", INPUTS.event("run"));

        boolean hasNotice = state.notice() != null && !state.notice().isBlank();
        cmd.set("#Notice.Visible", hasNotice);
        if (hasNotice) {
            cmd.set("#Notice.TextSpans", uiText("#Notice.TextSpans", state.notice()));
        }

        SimulationReport report = state.report();
        boolean warn = report != null && !report.warnings().isEmpty();
        cmd.set("#SimWarning.Visible", warn);
        if (warn) {
            cmd.set("#SimWarning.TextSpans", uiText("#SimWarning.TextSpans", String.join(" ", report.warnings())));
        }
        cmd.set("#ListEmpty.Visible", report == null || report.rows().isEmpty());
        cmd.set("#SimSummary.TextSpans", uiText("#SimSummary.TextSpans", summary(report, state.elapsedMs(), live)));
        if (report != null) {
            for (int i = 0; i < report.rows().size(); i++) {
                SimulationReport.Row row = report.rows().get(i);
                String path = "#ResultList[" + i + "]";
                cmd.append("#ResultList", ROW_UI);
                cmd.set(path + " #SimName.TextSpans", uiText(path + " #SimName.TextSpans",
                        row.name() + (row.rarity().isBlank() ? "" : " &8· &7" + row.rarity())));
                cmd.set(path + " #SimChance.TextSpans", uiText(path + " #SimChance.TextSpans", row.chance()));
                cmd.set(path + " #SimObserved.TextSpans", uiText(path + " #SimObserved.TextSpans",
                        row.observedText()));
                cmd.set(path + " #SimCount.TextSpans", uiText(path + " #SimCount.TextSpans", row.countText()));
                cmd.set(path + " #SimGap.TextSpans", uiText(path + " #SimGap.TextSpans", row.gapText()));
                bar(cmd, path + " #TheoryBar", row.theoryBar());
                bar(cmd, path + " #ObservedBar", row.observedBar());
            }
        }

        event.addEventBinding(CustomUIEventBindingType.Activating, "#BackButton", INPUTS.event("back"));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton",
                new EventData().put("action", "close"));
    }

    /** Sets a bar's width; a zero-width bar is hidden rather than given a zero anchor. */
    private static void bar(UICommandBuilder cmd, String selector, int width) {
        cmd.set(selector + ".Visible", width > 0);
        if (width > 0) {
            Anchor anchor = new Anchor();
            anchor.setWidth(Value.of(width));
            anchor.setHeight(Value.of(BAR_HEIGHT));
            cmd.setObject(selector + ".Anchor", anchor);
        }
    }

    // ----- Events -------------------------------------------------------------

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
        JsonObject payload = parse(data);
        String action = string(payload, "action");
        State next = state.withDraws(INPUTS.read(payload).getOrDefault(DRAWS.key(), state.draws())).withNotice(null);
        switch (action) {
            case "close" -> close(ref, store);
            case "back" -> reopen(ref, store, new LootBoxManagerPage(core, player, module,
                    LootBoxManagerPage.State.selecting(state.lootboxId(), null)));
            case "run" -> {
                if (allowed(Permissions.LOOTBOX_SIMULATE)) {
                    run(ref, store, next);
                }
            }
            default -> reopen(ref, store, page(next));
        }
    }

    private void run(Ref<EntityStore> ref, Store<EntityStore> store, State next) {
        long max = maxDraws(module);
        List<FormFields.Problem> problems = new ArrayList<>();
        String typed = next.draws() == null ? "" : next.draws().replace(",", "").replace("_", "").trim();
        Long draws = FormFields.longInRange(DRAWS, typed, 1, max).collect(problems);
        if (!problems.isEmpty()) {
            reopen(ref, store, page(next.withNotice("&c" + problems.get(0).message())));
            return;
        }
        LootBoxRevision revision = module.registry().active(next.lootboxId()).orElse(null);
        if (revision == null) {
            reopen(ref, store, page(next.withNotice("&c" + next.lootboxId()
                    + " has no published revision; publish it first, then simulate.")));
            return;
        }
        UUID uuid = player.getUuid();
        if (!RUNNING.add(uuid)) {
            reopen(ref, store, page(next.withNotice("&eYour last simulation is still drawing; wait for it.")));
            return;
        }
        State running = next.withDraws(Long.toString(draws));
        reopen(ref, store, page(running.withNotice("&7Drawing &f" + grouped(draws) + " &7times from &fr"
                + revision.revision + "&7... nothing is awarded.")));
        String policy = module.opening().policyProfile();
        OddsFormatter formatter = module.opening().formatter();
        long started = System.nanoTime();
        try {
            module.opening().simulate(revision, draws).whenComplete((counts, error) -> {
                RUNNING.remove(uuid);
                if (error != null) {
                    core.log(Level.WARNING, "[lootbox] simulating " + revision.lootboxId + " failed: " + error);
                    openLater(running.withNotice("&cThe simulation failed; see the server log."));
                    return;
                }
                long elapsed = (System.nanoTime() - started) / 1_000_000L;
                try {
                    openLater(running.withReport(SimulationReport.of(revision, OddsEngine.table(revision, policy),
                            counts, formatter, max), elapsed));
                } catch (RuntimeException e) {
                    core.log(Level.WARNING, "[lootbox] reading the simulation of " + revision.lootboxId
                            + " failed: " + e);
                    openLater(running.withNotice("&cThe draws finished but could not be shown; see the server log."));
                }
            });
        } catch (RuntimeException e) {
            RUNNING.remove(uuid);
            core.log(Level.WARNING, "[lootbox] simulating " + revision.lootboxId + " could not start: " + e);
            reopen(ref, store, page(running.withNotice("&cThe simulation could not start; see the server log.")));
        }
    }

    // ----- Helpers ------------------------------------------------------------

    private String header(LootBoxRevision live) {
        LootBoxDraft draft = module.registry().drafts().get(state.lootboxId());
        String raw = draft != null && draft.displayName != null && !draft.displayName.isBlank() ? draft.displayName
                : live != null && live.spec != null ? live.spec.displayName : state.lootboxId();
        String name = MysticText.stripMarkup(raw == null ? state.lootboxId() : raw);
        return name + " · " + (live == null ? "not published"
                : "live r" + live.revision + " · odds " + OddsEngine.table(live, module.opening().policyProfile()).oddsId());
    }

    private static String summary(SimulationReport report, long elapsedMs, LootBoxRevision live) {
        if (report == null) {
            return "Nothing drawn yet.";
        }
        String text = grouped(report.draws()) + " draws of r" + report.revision() + " (odds " + report.oddsId() + ") in "
                + String.format(Locale.ROOT, "%.1f", elapsedMs / 1000.0) + " s";
        if (live != null && live.revision != report.revision()) {
            text += "; the pool has since been republished as r" + live.revision + ", so run again";
        }
        return text + ". Nothing was awarded.";
    }

    private static long maxDraws(LootBoxModule module) {
        return Math.max(1, module.config().simulation.maxTrials);
    }

    private static String grouped(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private SimulationPage page(State next) {
        return new SimulationPage(core, player, module, next);
    }

    /** Reopens the page from the draw's completion; {@code openPage} hops to the player's world thread. */
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
