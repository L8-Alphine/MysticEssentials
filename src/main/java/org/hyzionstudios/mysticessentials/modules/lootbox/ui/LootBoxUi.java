package org.hyzionstudios.mysticessentials.modules.lootbox.ui;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.message.MysticText;
import org.hyzionstudios.mysticessentials.modules.lootbox.LootBoxModule;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.KeyDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxRevision;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.OddsTable;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.OpeningService;
import org.hyzionstudios.mysticessentials.modules.lootbox.ui.editor.EditorSession;
import org.hyzionstudios.mysticessentials.modules.lootbox.ui.editor.KeyEditorPage;
import org.hyzionstudios.mysticessentials.modules.lootbox.ui.editor.PoolEditorPage;

import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Opens LootBox screens and carries out what their buttons ask for. Pages are
 * opened through {@code platform().openPage}, which always hops to the player's
 * world thread, so these methods may be called from any thread.
 */
public final class LootBoxUi {

    private final MysticCore core;
    private final LootBoxModule module;

    public LootBoxUi(MysticCore core, LootBoxModule module) {
        this.core = core;
        this.module = module;
    }

    public void openCatalog(PlayerRef player) {
        module.pending().inbox(player.getUuid()).thenAccept(inbox -> core.platform().openPage(player,
                new LootBoxPages.CatalogPage(core, player, module, inbox.items.size())));
    }

    public void openPreview(PlayerRef player, String lootboxId, String crateId) {
        module.opening().preview(player, lootboxId, crateId).thenAccept(preview -> {
            if (preview.revision() == null) {
                core.getMessageService().sendKey(player, "lootbox-unknown-pool", Map.of("pool", String.valueOf(lootboxId)));
                return;
            }
            core.platform().openPage(player, new LootBoxPages.PreviewPage(core, player, module, preview));
        });
    }

    public void openHistory(PlayerRef viewer, UUID owner, String ownerName) {
        module.opening().history(owner).thenAccept(receipts -> core.platform().openPage(viewer,
                new LootBoxPages.HistoryPage(core, viewer, module, ownerName, receipts)));
    }

    public void openPending(PlayerRef player) {
        module.pending().inbox(player.getUuid()).thenAccept(inbox -> core.platform().openPage(player,
                new LootBoxPages.PendingPage(core, player, module, inbox)));
    }

    public void openManager(PlayerRef player) {
        core.platform().openPage(player, new LootBoxManagerPage(core, player, module, LootBoxManagerPage.State.initial()));
    }

    /**
     * Loads key draft {@code keyId} off the world thread and opens it in the key
     * editor, ending any editor the player had open. Warns once about fields the
     * editor would drop on save (P2-D4).
     *
     * @param notice shown at the top of the editor, or {@code null}
     */
    public void openKeyEditor(PlayerRef player, String keyId, String notice) {
        module.registry().keyStore().load(keyId).whenComplete((loaded, error) -> {
            if (error != null || loaded == null || loaded.isEmpty()) {
                if (error != null) {
                    core.log(Level.WARNING, "[lootbox] opening key draft " + keyId + " failed: " + error);
                }
                core.platform().openPage(player, new KeyManagerPage(core, player, module,
                        KeyManagerPage.State.selecting(keyId, "&cCould not open &f" + keyId
                                + "&c: its key file is missing or unreadable.")));
                return;
            }
            var draft = loaded.get();
            EditorSession<KeyDraft> session = module.editors().start(player.getUuid(), keyId, KeyDraft.class,
                    draft.draft(), draft.hash());
            String warning = draft.hadUnknownFields()
                    ? "&eThis file has fields the editor doesn't know (" + String.join(", ", draft.unknownFields())
                            + "); saving here drops them."
                    : null;
            core.platform().openPage(player, new KeyEditorPage(core, player, module, session,
                    PoolEditorPage.View.of(notice != null ? notice : warning)));
        });
    }

    /**
     * Loads pool draft {@code lootboxId} off the world thread and opens it in the
     * editor, ending any editor the player had open. A file with fields the model
     * does not know gets a one-time warning, because saving here drops them (P2-D4).
     *
     * @param notice shown at the top of the editor, or {@code null}
     */
    public void openPoolEditor(PlayerRef player, String lootboxId, String notice) {
        module.registry().poolStore().load(lootboxId).whenComplete((loaded, error) -> {
            if (error != null || loaded == null || loaded.isEmpty()) {
                if (error != null) {
                    core.log(Level.WARNING, "[lootbox] opening draft " + lootboxId + " failed: " + error);
                }
                core.platform().openPage(player, new LootBoxManagerPage(core, player, module,
                        LootBoxManagerPage.State.selecting(lootboxId, "&cCould not open &f" + lootboxId
                                + "&c: its draft file is missing or unreadable.")));
                return;
            }
            var draft = loaded.get();
            EditorSession<LootBoxDraft> session = module.editors().start(player.getUuid(), lootboxId,
                    LootBoxDraft.class, draft.draft(), draft.hash());
            String warning = draft.hadUnknownFields()
                    ? "&eThis file has fields the editor doesn't know (" + String.join(", ", draft.unknownFields())
                            + "); saving here drops them."
                    : null;
            core.platform().openPage(player, new PoolEditorPage(core, player, module, session,
                    PoolEditorPage.View.of(notice != null ? notice : warning)));
        });
    }

    /** What happens after a confirmation comes back from the opening saga. */
    public void afterOpen(PlayerRef player, String lootboxId, String crateId, OpeningService.OpenResult result) {
        switch (result.status()) {
            case COMMITTED, DUPLICATE -> {
                if (result.opening() == null || result.entry() == null) {
                    core.getMessageService().send(player, "&e" + (result.message() == null ? "Nothing was spent."
                            : result.message()));
                    return;
                }
                LootBoxDraft.RewardSpec reward = result.revision().spec.rewards.get(result.entry().index());
                core.getMessageService().sendKey(player, "lootbox-opened", Map.of(
                        "pool", MysticText.stripMarkup(result.revision().spec.displayName),
                        "reward", MysticText.stripMarkup(reward.name),
                        "chance", result.opening().probability,
                        "receipt", result.opening().openingId));
                core.platform().openPage(player, new LootBoxPages.RevealPage(core, player, module, result));
            }
            default -> {
                core.getMessageService().send(player, "&c" + (result.message() == null ? "Nothing was spent."
                        : result.message()));
                openPreview(player, lootboxId, crateId);
            }
        }
    }

    public void claim(PlayerRef player, String fulfillmentId) {
        module.pending().claim(player, fulfillmentId).thenAccept(claimed -> {
            core.getMessageService().sendKey(player, "lootbox-pending-claimed", Map.of(
                    "count", Integer.toString(claimed.claimed()), "remaining", Integer.toString(claimed.remaining())));
            openPending(player);
        });
    }

    public void sendPublishResult(PlayerRef player, String lootboxId,
            org.hyzionstudios.mysticessentials.modules.lootbox.service.LootBoxRegistry.PublishResult result) {
        if (result.ok()) {
            core.getMessageService().sendKey(player, result.unchanged() ? "lootbox-publish-unchanged" : "lootbox-published",
                    Map.of("pool", lootboxId, "revision", Integer.toString(result.revision()), "hash", result.hash()));
        } else {
            core.getMessageService().sendKey(player, "lootbox-publish-failed",
                    Map.of("pool", lootboxId, "reason", String.join("; ", result.problems())));
        }
    }

    public void simulate(PlayerRef player, String lootboxId, long trials) {
        LootBoxRevision revision = module.registry().active(lootboxId).orElse(null);
        if (revision == null) {
            core.getMessageService().send(player, "&c" + lootboxId + " has no published revision to simulate.");
            return;
        }
        module.opening().simulate(revision, trials).thenAccept(counts -> {
            for (String line : simulationLines(revision, counts)) {
                core.getMessageService().send(player, line);
            }
        });
    }

    /** Theoretical against observed for each outcome, with a small-sample warning. */
    public List<String> simulationLines(LootBoxRevision revision, long[] counts) {
        OddsTable table = org.hyzionstudios.mysticessentials.modules.lootbox.odds.OddsEngine.table(revision,
                module.opening().policyProfile());
        long trials = 0;
        for (long count : counts) {
            trials += count;
        }
        List<String> lines = new java.util.ArrayList<>();
        lines.add("&d&lSimulation &7" + revision.lootboxId + " r" + revision.revision + " &8- &f" + trials
                + " draws &8(nothing awarded)");
        long rarestExpected = Long.MAX_VALUE;
        for (OddsTable.Entry entry : table.entries()) {
            double expected = (double) trials * entry.weight() / table.total();
            rarestExpected = Math.min(rarestExpected, (long) expected);
            double observed = trials == 0 ? 0 : 100.0 * counts[entry.index()] / trials;
            lines.add("&7- &f" + MysticText.stripMarkup(revision.spec.rewards.get(entry.index()).name) + " &8: &6"
                    + module.opening().formatter().percent(entry.weight(), table.total()) + " &7expected, &f"
                    + String.format(java.util.Locale.ROOT, "%.4f%%", observed) + " &7observed (" + counts[entry.index()]
                    + ")");
        }
        if (rarestExpected < 10) {
            lines.add("&eToo few draws to say anything about the rarest outcome; run more.");
        }
        return lines;
    }
}
