package org.hyzionstudios.mysticessentials.modules.lootbox.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.message.MysticText;
import org.hyzionstudios.mysticessentials.modules.lootbox.api.event.OpeningCommittedEvent;
import org.hyzionstudios.mysticessentials.modules.lootbox.api.event.OpeningFlaggedEvent;
import org.hyzionstudios.mysticessentials.modules.lootbox.api.event.OpeningRequestedEvent;
import org.hyzionstudios.mysticessentials.modules.lootbox.config.LootBoxConfig;
import org.hyzionstudios.mysticessentials.modules.lootbox.hytale.InventoryAdapter;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.KeyBatch;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.KeySerial;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxRevision;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.Opening;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.PlayerLootState;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.OddsEngine;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.OddsFormatter;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.OddsTable;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.PlayerOdds;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.SecureDraw;
import org.hyzionstudios.mysticessentials.modules.lootbox.storage.LootBoxStorage;

import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * The opening saga (§4.3). The commit point is always ONE document write that
 * spends the key and records the outcome: the player document for a virtual key,
 * the serial for a physical key. Everything else (the opening document, the
 * receipt, delivery) follows from it and is recoverable.
 *
 * <p>All of a player's openings run in their {@link PlayerLanes lane}. Inventory
 * reads and removals hop to the player's world thread; nothing here blocks one.</p>
 */
public final class OpeningService {

    public enum Status {
        COMMITTED,
        /** The same confirmation arrived twice; the existing opening is returned. */
        DUPLICATE,
        /** The odds changed since the preview. */
        STALE,
        NO_KEY,
        BUSY,
        COOLDOWN,
        /** Pool disabled, unknown, or the policy profile turned opening off. */
        CLOSED,
        DENIED,
        ERROR
    }

    /** What a preview shows; {@code requestId} must come back with the confirmation. */
    public record Preview(LootBoxRevision revision, OddsTable table, int virtualKeys, int physicalKeys,
            String requestId, String crateId, List<String> blockers) {

        public int keys() {
            return virtualKeys + physicalKeys;
        }
    }

    /** Outcome of a confirmation. {@code delivery} completes once the reward settled. */
    public record OpenResult(Status status, String message, Opening opening, LootBoxRevision revision,
            OddsTable table, OddsTable.Entry entry, CompletableFuture<RewardService.Status> delivery) {

        static OpenResult of(Status status, String message) {
            return new OpenResult(status, message, null, null, null, null, null);
        }
    }

    private final MysticCore core;
    private final LootBoxStorage storage;
    private final LootBoxRegistry registry;
    private final KeyService keys;
    private final RewardService rewards;
    private final AuditService audit;
    private final PlayerLanes lanes;
    private final SecureDraw draw;
    private final IntegrationRegistry integrations;
    private volatile LootBoxConfig config;
    private volatile OddsFormatter formatter;
    private final Map<UUID, OpeningRules.Confirm> confirms = new ConcurrentHashMap<>();
    /** The network authority (P4-WP2), or {@code null} when this server runs alone. */
    private volatile NetworkAuthority network;

    public OpeningService(MysticCore core, LootBoxStorage storage, LootBoxRegistry registry, KeyService keys,
            RewardService rewards, AuditService audit, PlayerLanes lanes, SecureDraw draw,
            IntegrationRegistry integrations, LootBoxConfig config) {
        this.core = core;
        this.storage = storage;
        this.registry = registry;
        this.keys = keys;
        this.rewards = rewards;
        this.audit = audit;
        this.lanes = lanes;
        this.draw = draw;
        this.integrations = integrations;
        updateConfig(config);
    }

    /** Installs the network authority NETWORK pools open under (P4-WP2). */
    public void network(NetworkAuthority authority) {
        this.network = authority;
    }

    public void updateConfig(LootBoxConfig config) {
        this.config = config;
        this.formatter = formatterFor(config);
    }

    public static OddsFormatter formatterFor(LootBoxConfig config) {
        Locale locale = Locale.forLanguageTag(config.display.locale == null ? "en-US" : config.display.locale);
        return new OddsFormatter(config.display.minDecimals, config.display.maxDecimals, locale);
    }

    public OddsFormatter formatter() {
        return formatter;
    }

    public String policyProfile() {
        return config.policyProfile == null ? "FREE_GAMEPLAY_ONLY" : config.policyProfile.toUpperCase(Locale.ROOT);
    }

    // ----- Preview ------------------------------------------------------------

    public CompletableFuture<Preview> preview(PlayerRef player, String lootboxId, String crateId) {
        LootBoxRevision revision = registry.active(lootboxId).orElse(null);
        if (revision == null) {
            return CompletableFuture.completedFuture(
                    new Preview(null, null, 0, 0, null, crateId, List.of("This loot box does not exist.")));
        }
        Set<String> keyIds = new LinkedHashSet<>(revision.spec.keys);
        CompletableFuture<Integer> physical = countPhysical(player, keyIds);
        return storage.loadPlayer(player.getUuid()).thenCombine(physical, (state, held) -> {
            int virtual = 0;
            for (String keyId : keyIds) {
                virtual += state.virtualKeys.getOrDefault(keyId, 0);
            }
            OddsTable table = personalTable(player, revision, state);
            List<String> blockers = blockers(player, revision, state, virtual + held);
            if (table.blocked() != null) {
                blockers.add(0, table.blocked());
            }
            String requestId = draw.id();
            confirms.put(player.getUuid(), new OpeningRules.Confirm(lootboxId, table.hash(), requestId, crateId,
                    System.currentTimeMillis()));
            audit.counter("PREVIEW").incrementAndGet();
            return new Preview(revision, table, virtual, held, requestId, crateId, blockers);
        });
    }

    private List<String> blockers(PlayerRef player, LootBoxRevision revision, PlayerLootState state, int keyCount) {
        List<String> blockers = new ArrayList<>();
        if ("DISABLED".equals(policyProfile()) || registry.isDisabled(revision.lootboxId)) {
            blockers.add("This loot box is closed right now.");
        } else if (ended(revision)) {
            blockers.add("These time-limited odds have ended.");
        }
        String node = revision.spec.permission;
        if (node != null && !node.isBlank() && !player.hasPermission(node)) {
            blockers.add("You don't have permission to open this.");
        }
        long wait = cooldownRemaining(state, revision);
        if (wait > 0) {
            blockers.add("You can open this again in " + TimeUnit.MILLISECONDS.toSeconds(wait + 999) + "s.");
        }
        if (keyCount <= 0) {
            blockers.add("You need a key to open this.");
        }
        return blockers;
    }

    /**
     * The odds {@code player} sees and draws from (Design Bible §07 steps 1–4):
     * the revision, then their conditions, owned unique rewards and pity. The
     * preview and the confirmation both build it from the player document they
     * just read, so the confirmation is stale whenever it changed in between.
     */
    public OddsTable personalTable(PlayerRef player, LootBoxRevision revision, PlayerLootState state) {
        PlayerOdds odds = EligibilityRules.evaluate(revision, state.pools.get(revision.lootboxId),
                conditionContext(player), integrations);
        return OddsEngine.table(revision, policyProfile(), odds);
    }

    /** {@code player}'s table for {@code revision}, or the table with nothing personal for the console ({@code null}). */
    public CompletableFuture<OddsTable> tableFor(PlayerRef player, LootBoxRevision revision) {
        if (player == null) {
            return CompletableFuture.completedFuture(OddsEngine.table(revision, policyProfile()));
        }
        return storage.loadPlayer(player.getUuid()).thenApply(state -> personalTable(player, revision, state));
    }

    /**
     * What conditions may read about {@code player}: permissions, and the world
     * and position the player last ticked in (readable off the world thread; the
     * confirmation re-reads them, and a move in between makes it stale).
     */
    private ConditionRules.Context conditionContext(PlayerRef player) {
        String world = core.platform().worldNameOf(player).orElse(null);
        double x = 0;
        double y = 0;
        double z = 0;
        boolean known = false;
        try {
            var position = player.getTransform().getPosition();
            x = position.x();
            y = position.y();
            z = position.z();
            known = world != null;
        } catch (RuntimeException e) {
            // Unknown position: REGION conditions answer UNAVAILABLE and fail closed.
        }
        return new ConditionRules.Context(player.getUuid(), player::hasPermission, world, x, y, z, known,
                System.currentTimeMillis());
    }

    /**
     * Whether the live revision's time-limited odds are over (P3-WP5): the
     * schedule sweep reverts or closes the pool within a minute, and until then
     * nobody opens on odds that ended.
     */
    private boolean ended(LootBoxRevision revision) {
        LootBoxRevision.State state = registry.state(revision.lootboxId).orElse(null);
        return state != null && state.activeRevision == revision.revision && state.endsAt > 0
                && System.currentTimeMillis() >= state.endsAt;
    }

    private long cooldownRemaining(PlayerLootState state, LootBoxRevision revision) {
        PlayerLootState.PoolState pool = state.pools.get(revision.lootboxId);
        if (pool == null || revision.spec.cooldownSeconds <= 0) {
            return 0;
        }
        long until = pool.lastOpenAt + TimeUnit.SECONDS.toMillis(revision.spec.cooldownSeconds);
        return Math.max(0, until - System.currentTimeMillis());
    }

    // ----- Open ---------------------------------------------------------------

    public CompletableFuture<OpenResult> open(PlayerRef player, String lootboxId, String snapshotHash,
            String requestId) {
        UUID uuid = player.getUuid();
        LootBoxRevision live = registry.active(lootboxId).orElse(null);
        boolean networked = live != null && NetworkRules.isNetwork(live.spec);
        return lanes.run(uuid, () -> networked ? openOnNetwork(player, lootboxId, snapshotHash, requestId)
                : openInLane(player, lootboxId, snapshotHash, requestId))
                .exceptionally(error -> {
                    core.log(Level.SEVERE, "[lootbox] opening of " + lootboxId + " by " + player.getUsername()
                            + " failed: " + error);
                    return OpenResult.of(Status.ERROR, "Something went wrong; nothing was spent.");
                });
    }

    /**
     * A NETWORK pool (P4-WP2/WP3): the whole opening runs under the player's
     * network lock, with every write fenced, after checking that this server's
     * revision is still the one in the shared store. Fails closed: when the lock
     * or the shared store is unavailable, nothing is read for spending.
     */
    private CompletableFuture<OpenResult> openOnNetwork(PlayerRef player, String lootboxId, String snapshotHash,
            String requestId) {
        NetworkAuthority authority = network;
        if (authority == null) {
            return done(Status.CLOSED, "Network loot boxes are unavailable on this server; nothing was spent.");
        }
        return authority.withLease(player.getUuid(), lease -> sharedRevisionChanged(lootboxId)
                .thenCompose(changed -> changed
                        ? done(Status.STALE, "The odds changed on another server. Check them and confirm again.")
                        : openInLane(player, lootboxId, snapshotHash, requestId)))
                .exceptionally(error -> {
                    NetworkAuthority.Unavailable unavailable = NetworkAuthority.unavailableIn(error);
                    if (unavailable != null) {
                        audit.counter("NETWORK_CLOSED").incrementAndGet();
                        return OpenResult.of(Status.CLOSED, "Network loot boxes are unavailable right now ("
                                + unavailable.getMessage() + "); nothing was spent.");
                    }
                    if (NetworkRules.fencedOut(error)) {
                        audit.counter("FENCED_OUT").incrementAndGet();
                        core.log(Level.WARNING, "[lootbox] an opening of " + lootboxId + " by "
                                + player.getUsername() + " was fenced out: another server took over.");
                        return OpenResult.of(Status.ERROR, "Another server took over this opening; "
                                + "/lootbox history shows what happened.");
                    }
                    throw error instanceof CompletionException completion ? completion
                            : new CompletionException(error);
                });
    }

    /**
     * Commit-time check of a NETWORK pool against the shared store (P4-WP3):
     * true, after refreshing the caches, when another server published,
     * rolled back or closed it since this server cached it.
     */
    private CompletableFuture<Boolean> sharedRevisionChanged(String lootboxId) {
        LootBoxRevision local = registry.active(lootboxId).orElse(null);
        return storage.loadState(lootboxId).thenCompose(state -> {
            if (local == null || state == null || state.activeRevision != local.revision
                    || state.disabled != registry.isDisabled(lootboxId)) {
                return registry.refreshPool(lootboxId).thenApply(ignored -> true);
            }
            return storage.loadRevision(lootboxId, state.activeRevision).thenCompose(shared -> {
                if (shared != null && Objects.equals(shared.contentHash, local.contentHash)) {
                    return CompletableFuture.completedFuture(false);
                }
                return registry.refreshPool(lootboxId).thenApply(ignored -> true);
            });
        });
    }

    private CompletableFuture<OpenResult> openInLane(PlayerRef player, String lootboxId, String snapshotHash,
            String requestId) {
        UUID uuid = player.getUuid();
        return storage.loadPlayer(uuid).thenCompose(state -> {
            String earlier = OpeningRules.earlierOpening(state, requestId);
            if (earlier != null) {
                return storage.loadOpening(earlier).thenCompose(opening -> existingResult(opening));
            }
            OpeningRules.Confirm confirm = confirms.get(uuid);
            if (!OpeningRules.confirmCurrent(confirm, lootboxId, requestId, System.currentTimeMillis())) {
                return done(Status.STALE, "Please review the odds again and confirm.");
            }
            if (state.activeOpening != null) {
                recover(player);
                return done(Status.BUSY, "Your previous opening is still finishing. Try again in a moment.");
            }
            long now = System.currentTimeMillis();
            if (now - state.lastOpenAt < config.opening.minIntervalMs) {
                return done(Status.COOLDOWN, "You're opening too fast.");
            }
            LootBoxRevision revision = registry.active(lootboxId).orElse(null);
            if (revision == null || registry.isDisabled(lootboxId) || "DISABLED".equals(policyProfile())) {
                return done(Status.CLOSED, "This loot box is closed right now.");
            }
            if (ended(revision)) {
                return done(Status.CLOSED, "These time-limited odds have ended.");
            }
            String node = revision.spec.permission;
            if (node != null && !node.isBlank() && !player.hasPermission(node)) {
                return done(Status.DENIED, "You don't have permission to open this.");
            }
            long wait = cooldownRemaining(state, revision);
            if (wait > 0) {
                return done(Status.COOLDOWN, "You can open this again in "
                        + TimeUnit.MILLISECONDS.toSeconds(wait + 999) + "s.");
            }
            OddsTable table = personalTable(player, revision, state);
            if (!OpeningRules.snapshotCurrent(snapshotHash, table)) {
                audit.counter("STALE").incrementAndGet();
                return done(Status.STALE, "The odds changed while you were looking. Check them and confirm again.");
            }
            if (table.blocked() != null) {
                return done(Status.DENIED, table.blocked());
            }
            OpeningRequestedEvent requested = core.getEventBus().publish(
                    new OpeningRequestedEvent(uuid, lootboxId, revision.revision, confirm.crateId()));
            if (requested.isCancelled()) {
                return done(Status.DENIED, requested.reason().isBlank() ? "You can't open this right now."
                        : requested.reason());
            }
            confirms.remove(uuid);
            return chooseKeyAndOpen(player, state, revision, table, requestId, confirm.crateId());
        });
    }

    private CompletableFuture<OpenResult> chooseKeyAndOpen(PlayerRef player, PlayerLootState state,
            LootBoxRevision revision, OddsTable table, String requestId, String crateId) {
        String virtualKey = OpeningRules.firstVirtualKey(revision.spec.keys, state);
        boolean physicalFirst = "PHYSICAL_FIRST".equalsIgnoreCase(config.keys.consumeOrder);
        if (virtualKey != null && !physicalFirst) {
            return prepare(player, revision, table, requestId, crateId)
                    .thenCompose(opening -> commitVirtual(player, opening, revision, table, virtualKey));
        }
        String fallbackVirtual = virtualKey;
        Set<String> keyIds = new LinkedHashSet<>(revision.spec.keys);
        return findPhysical(player, keyIds).thenCompose(stacks -> {
            if (!stacks.isEmpty()) {
                return prepare(player, revision, table, requestId, crateId)
                        .thenCompose(opening -> commitPhysical(player, opening, revision, table, stacks,
                                fallbackVirtual));
            }
            if (fallbackVirtual != null) {
                return prepare(player, revision, table, requestId, crateId)
                        .thenCompose(opening -> commitVirtual(player, opening, revision, table, fallbackVirtual));
            }
            return done(Status.NO_KEY, "You need a key to open this.");
        });
    }

    /** Draws the outcome and records the opening as PREPARED before any key is touched. */
    private CompletableFuture<Opening> prepare(PlayerRef player, LootBoxRevision revision, OddsTable table,
            String requestId, String crateId) {
        OddsEngine.Draw drawn = OddsEngine.draw(table, draw);
        long roll = drawn.roll();
        OddsTable.Entry entry = drawn.entry();
        LootBoxDraft.RewardSpec reward = revision.spec.rewards.get(entry.index());
        Opening opening = new Opening();
        opening.openingId = draw.id();
        opening.player = player.getUuid().toString();
        opening.playerName = player.getUsername();
        opening.requestId = requestId;
        opening.lootboxId = revision.lootboxId;
        opening.poolName = revision.spec.displayName;
        opening.revision = revision.revision;
        opening.snapshotHash = table.hash();
        opening.roll = roll;
        opening.totalWeight = table.total();
        opening.outcomeId = reward.id;
        opening.outcomeName = reward.name;
        opening.probability = formatter.percent(entry.weight(), table.total());
        opening.crateId = crateId;
        opening.createdAt = System.currentTimeMillis();
        RewardActions.plan(opening, reward, null);
        for (OddsEngine.BonusResult bonus : drawn.bonus()) {
            Opening.BonusDraw record = new Opening.BonusDraw();
            record.rollId = bonus.table().rollId();
            record.label = bonus.table().label();
            record.roll = bonus.roll();
            record.total = bonus.table().total();
            if (bonus.entry() != null) {
                LootBoxDraft.RewardSpec won = revision.spec.bonusRolls().get(bonus.table().rollIndex()).rewards
                        .get(bonus.entry().index());
                record.outcomeId = won.id;
                record.outcomeName = won.name;
                record.probability = formatter.percent(bonus.entry().weight(), bonus.table().total());
                RewardActions.plan(opening, won, record.rollId);
            } else if (bonus.table().total() > 0) {
                record.probability = formatter.percent(bonus.table().none(), bonus.table().total());
            }
            opening.bonus.add(record);
        }
        audit.record(player.getUuid().toString(), "OPEN_REQUEST", opening.openingId,
                revision.lootboxId + "@" + revision.revision + " odds " + table.oddsId());
        return storage.saveOpening(opening).thenCompose(saved -> storage.updatePlayer(player.getUuid(), state -> {
            OpeningRules.recordPrepared(state, opening, config.opening.requestDedupeMinutes);
            return state;
        })).thenApply(state -> opening);
    }

    private CompletableFuture<OpenResult> commitVirtual(PlayerRef player, Opening opening, LootBoxRevision revision,
            OddsTable table, String keyId) {
        opening.keyKind = Opening.KEY_VIRTUAL;
        opening.keyId = keyId;
        AtomicBoolean committed = new AtomicBoolean();
        return storage.updatePlayer(player.getUuid(), state -> {
            OpeningRules.Debit debit = OpeningRules.spendVirtual(state, keyId, opening, revision.spec.displayName,
                    config.opening.historySize, System.currentTimeMillis(),
                    EligibilityRules.effects(revision.spec, opening));
            committed.set(debit != OpeningRules.Debit.NO_KEY);
            return debit == OpeningRules.Debit.ALREADY_COMMITTED ? null : state;
        }).thenCompose(state -> committed.get()
                ? finishCommit(player, opening, revision, table)
                : abort(opening, Status.NO_KEY, "no virtual key left"));
    }

    private CompletableFuture<OpenResult> commitPhysical(PlayerRef player, Opening opening, LootBoxRevision revision,
            OddsTable table, List<InventoryAdapter.KeyStack> stacks, String fallbackVirtual) {
        opening.keyKind = Opening.KEY_PHYSICAL;
        return reserveFirst(player, opening, stacks, 0, null).thenCompose(reserved -> {
            if (reserved == null) {
                if (fallbackVirtual != null) {
                    return commitVirtual(player, opening, revision, table, fallbackVirtual);
                }
                return abort(opening, Status.NO_KEY, "no spendable physical key");
            }
            InventoryAdapter.KeyStack stack = reserved.stack();
            opening.keyId = stack.marker().keyId();
            opening.keySerial = stack.marker().serial();
            opening.state = Opening.RESERVED;
            // A batch item (P2-K) always has one use: one item leaves the stack.
            boolean lastUse = reserved.serial() == null || OpeningRules.lastUse(reserved.serial());
            return storage.saveOpening(opening)
                    .thenCompose(saved -> takeKey(player, stack, lastUse))
                    .thenCompose(taken -> {
                        if (!taken) {
                            return releaseReservation(opening.keySerial, opening.openingId)
                                    .thenCompose(released -> abort(opening, Status.NO_KEY,
                                            "the key left the inventory before it was spent"));
                        }
                        return redeem(opening).thenCompose(redeemed -> {
                            if (!redeemed) {
                                opening.state = Opening.REVIEW;
                                opening.note = "key taken but its serial refused the commit";
                                audit.record("server", "OPEN_ANOMALY", opening.openingId, opening.note);
                                return storage.saveOpening(opening).thenApply(saved -> new OpenResult(Status.ERROR,
                                        "Your opening needs staff review (receipt " + opening.openingId + ").",
                                        opening, revision, table, null, null));
                            }
                            return storage.updatePlayer(player.getUuid(), state -> {
                                if (!state.hasCommit(opening.openingId)) {
                                    recordCommit(state, opening, revision);
                                }
                                return state;
                            }).thenCompose(state -> finishCommit(player, opening, revision, table));
                        });
                    });
        });
    }

    /** {@code serial} is {@code null} for a batch of stackable keys (P2-K). */
    private record Reserved(InventoryAdapter.KeyStack stack, KeySerial serial) {
    }

    /** Reserves the first stack whose serial (or batch) may be spent. */
    private CompletableFuture<Reserved> reserveFirst(PlayerRef player, Opening opening,
            List<InventoryAdapter.KeyStack> stacks, int index, String lastProblem) {
        if (index >= stacks.size()) {
            return CompletableFuture.completedFuture(null);
        }
        InventoryAdapter.KeyStack stack = stacks.get(index);
        if (KeyBatch.isBatch(stack.marker().serial())) {
            return reserveBatch(player, opening, stacks, index);
        }
        long now = System.currentTimeMillis();
        AtomicReference<String> problem = new AtomicReference<>();
        return storage.updateSerial(stack.marker().serial(), serial -> {
            String why = OpeningRules.reserve(serial, stack.marker().keyId(), opening.openingId, now);
            problem.set(why);
            return why == null ? serial : null;
        }).thenCompose(serial -> {
            if (problem.get() == null && serial != null && opening.openingId.equals(serial.reservedBy)) {
                return CompletableFuture.completedFuture(new Reserved(stack, serial));
            }
            flagIfSuspicious(player, opening.lootboxId, stack, problem.get());
            return reserveFirst(player, opening, stacks, index + 1, problem.get());
        });
    }

    /**
     * Holds one unit of a batch (P2-K) for this opening, remembering how many of
     * its items the player holds, which recovery compares against.
     */
    private CompletableFuture<Reserved> reserveBatch(PlayerRef player, Opening opening,
            List<InventoryAdapter.KeyStack> stacks, int index) {
        InventoryAdapter.KeyStack stack = stacks.get(index);
        String batchId = stack.marker().serial();
        int heldBefore = stacks.stream().filter(s -> batchId.equals(s.marker().serial()))
                .mapToInt(InventoryAdapter.KeyStack::quantity).sum();
        long now = System.currentTimeMillis();
        AtomicReference<String> problem = new AtomicReference<>();
        return storage.updateBatch(batchId, batch -> {
            String why = StackRules.reserve(batch, stack.marker().keyId(), opening.openingId, 1, heldBefore, now);
            problem.set(why);
            return why == null ? batch : null;
        }).thenCompose(batch -> {
            if (problem.get() == null && batch != null && batch.reservation(opening.openingId) != null) {
                return CompletableFuture.completedFuture(new Reserved(stack, null));
            }
            flagIfSuspicious(player, opening.lootboxId, stack, problem.get());
            return reserveFirst(player, opening, stacks, index + 1, problem.get());
        });
    }

    /** A valid marker on an unspendable serial is a copied or recycled key. */
    private void flagIfSuspicious(PlayerRef player, String lootboxId, InventoryAdapter.KeyStack stack, String problem) {
        // "reserved": a batch's last units are held by another opening, so its items are not dead (P2-K).
        if (problem == null || "expired".equals(problem) || "wrong key".equals(problem) || "reserved".equals(problem)) {
            return;
        }
        audit.record(player.getUuid().toString(), "KEY_REJECTED", stack.marker().serial(), problem);
        core.getEventBus().publish(new OpeningFlaggedEvent(player.getUuid(), lootboxId, "key " + problem,
                stack.marker().serial()));
        if ("already used".equals(problem) || "revoked key".equals(problem) || "unknown key".equals(problem)) {
            core.platform().runOnEntityThread(player, (store, entity, world) ->
                    InventoryAdapter.removeAll(store, entity, stack.marker().serial()));
        }
    }

    private CompletableFuture<Boolean> takeKey(PlayerRef player, InventoryAdapter.KeyStack stack, boolean lastUse) {
        CompletableFuture<Boolean> taken = new CompletableFuture<>();
        boolean dispatched = core.platform().runOnEntityThread(player, (store, entity, world) -> {
            try {
                taken.complete(lastUse ? InventoryAdapter.removeOne(store, entity, stack)
                        : !InventoryAdapter.findSerial(store, entity, stack.marker().serial()).isEmpty());
            } catch (Throwable t) {
                taken.complete(false);
            }
        });
        if (!dispatched) {
            taken.complete(false);
        }
        return taken;
    }

    /**
     * The physical-key commit point: RESERVED(this) → one use spent, outcome
     * recorded. For a batch (P2-K): this opening's hold → one unit spent.
     */
    private CompletableFuture<Boolean> redeem(Opening opening) {
        AtomicBoolean redeemed = new AtomicBoolean();
        if (KeyBatch.isBatch(opening.keySerial)) {
            return storage.updateBatch(opening.keySerial, batch -> {
                redeemed.set(StackRules.redeem(batch, opening.openingId) > 0);
                return redeemed.get() ? batch : null;
            }).thenApply(batch -> redeemed.get());
        }
        return storage.updateSerial(opening.keySerial, serial -> {
            redeemed.set(OpeningRules.redeem(serial, opening.openingId, opening.outcomeId));
            return redeemed.get() ? serial : null;
        }).thenApply(serial -> redeemed.get());
    }

    private CompletableFuture<Void> releaseReservation(String serialId, String openingId) {
        if (KeyBatch.isBatch(serialId)) {
            return storage.updateBatch(serialId, batch -> StackRules.release(batch, openingId) ? batch : null)
                    .thenApply(batch -> null);
        }
        return storage.updateSerial(serialId, serial -> OpeningRules.release(serial, openingId) ? serial : null)
                .thenApply(serial -> null);
    }

    /** Appends the commit record, receipt and bookkeeping to the player document (caller holds the update). */
    private void recordCommit(PlayerLootState state, Opening opening, LootBoxRevision revision) {
        OpeningRules.recordCommit(state, opening, revision.spec.displayName, config.opening.historySize,
                System.currentTimeMillis(), EligibilityRules.effects(revision.spec, opening));
    }

    private CompletableFuture<OpenResult> finishCommit(PlayerRef player, Opening opening, LootBoxRevision revision,
            OddsTable table) {
        opening.state = Opening.COMMITTED;
        opening.committedAt = System.currentTimeMillis();
        OddsTable.Entry entry = table.entry(opening.outcomeId);
        LootBoxDraft.RewardSpec reward = revision.spec.rewards.get(entry.index());
        return storage.saveOpening(opening).thenApply(saved -> {
            audit.record(opening.player, "OPEN_COMMIT", opening.openingId, opening.lootboxId + "@" + opening.revision
                    + " -> " + opening.outcomeId + " (" + opening.probability + ") key " + opening.keyKind
                    + (opening.keySerial == null ? "" : " " + opening.keySerial));
            core.getEventBus().publish(new OpeningCommittedEvent(opening.openingId, player.getUuid(),
                    opening.lootboxId, opening.revision, opening.outcomeId, opening.probability, table.oddsId()));
            CompletableFuture<RewardService.Status> delivery = rewards.deliver(opening, revision, false)
                    .exceptionally(error -> {
                        rewards.logFailure(opening, error);
                        return RewardService.Status.REVIEW;
                    });
            if (reward.announce) {
                delivery.thenRun(() -> announce(player, revision, reward));
            }
            for (Opening.BonusDraw bonus : opening.bonus) {
                LootBoxDraft.RewardSpec won = bonus.outcomeId == null ? null
                        : RewardActions.find(revision.spec, bonus.outcomeId, bonus.rollId);
                if (won != null && won.announce) {
                    delivery.thenRun(() -> announce(player, revision, won));
                }
            }
            return new OpenResult(Status.COMMITTED, null, opening, revision, table, entry, delivery);
        });
    }

    private CompletableFuture<OpenResult> abort(Opening opening, Status status, String reason) {
        opening.state = Opening.ABORTED;
        opening.note = reason;
        audit.record(opening.player, "OPEN_ABORT", opening.openingId, reason);
        UUID player = UUID.fromString(opening.player);
        return storage.saveOpening(opening).thenCompose(saved -> storage.updatePlayer(player, state -> {
            if (!opening.openingId.equals(state.activeOpening)) {
                return null;
            }
            state.activeOpening = null;
            return state;
        })).thenApply(state -> OpenResult.of(status, status == Status.NO_KEY
                ? "You need a key to open this." : "Nothing was spent."));
    }

    private CompletableFuture<OpenResult> existingResult(Opening opening) {
        if (opening == null) {
            return done(Status.ERROR, "That opening could not be found.");
        }
        LootBoxRevision revision = registry.active(opening.lootboxId)
                .filter(r -> r.revision == opening.revision).orElse(null);
        CompletableFuture<LootBoxRevision> loaded = revision != null ? CompletableFuture.completedFuture(revision)
                : storage.loadRevision(opening.lootboxId, opening.revision);
        return loaded.thenApply(rev -> {
            if (rev == null || Opening.ABORTED.equals(opening.state)) {
                return OpenResult.of(Status.DUPLICATE, "Nothing was spent.");
            }
            OddsTable table = OddsEngine.table(rev, policyProfile());
            return new OpenResult(Status.DUPLICATE, null, opening, rev, table, table.entry(opening.outcomeId),
                    CompletableFuture.completedFuture(RewardService.Status.DELIVERED));
        });
    }

    private static CompletableFuture<OpenResult> done(Status status, String message) {
        return CompletableFuture.completedFuture(OpenResult.of(status, message));
    }

    private void announce(PlayerRef player, LootBoxRevision revision, LootBoxDraft.RewardSpec reward) {
        String template = config.rewards.announceTemplate;
        if (template == null || template.isBlank()) {
            return;
        }
        String text = template.replace("{player}", player.getUsername())
                .replace("{reward}", MysticText.stripMarkup(reward.name))
                .replace("{pool}", MysticText.stripMarkup(revision.spec.displayName));
        for (PlayerRef online : core.platform().onlinePlayers()) {
            core.getMessageService().send(online, text);
        }
    }

    // ----- Keys held ----------------------------------------------------------

    private CompletableFuture<List<InventoryAdapter.KeyStack>> findPhysical(PlayerRef player, Set<String> keyIds) {
        CompletableFuture<List<InventoryAdapter.KeyStack>> found = new CompletableFuture<>();
        boolean dispatched = core.platform().runOnEntityThread(player, (store, entity, world) -> {
            try {
                found.complete(InventoryAdapter.findKeys(store, entity, keyIds, keys.secret()));
            } catch (Throwable t) {
                found.complete(List.of());
            }
        });
        if (!dispatched) {
            found.complete(List.of());
        }
        return found;
    }

    private CompletableFuture<Integer> countPhysical(PlayerRef player, Set<String> keyIds) {
        return findPhysical(player, keyIds).thenApply(stacks -> stacks.stream()
                .mapToInt(InventoryAdapter.KeyStack::quantity).sum());
    }

    // ----- Recovery -----------------------------------------------------------

    /**
     * Finishes whatever this player left unfinished: an opening between draw and
     * commit (decided by {@link RecoveryPolicy}), deliveries that never settled,
     * and key conversions ({@link ConversionRules}). Runs in the player's lane, so
     * it never races a new opening or conversion. A delivery this server is still
     * running is left to finish ({@link DeliveryRules#resumes}).
     */
    public CompletableFuture<Void> recover(PlayerRef player) {
        UUID uuid = player.getUuid();
        NetworkAuthority authority = network;
        boolean networked = authority != null && authority.enabled();
        return lanes.run(uuid, () -> networked
                // On a network, recovery decides the fate of openings another server may have
                // started: it runs under the player's lock, fenced, or not at all (P4-WP2).
                ? authority.withLease(uuid, lease -> recoverInLane(player))
                : recoverInLane(player)).exceptionally(error -> {
                    NetworkAuthority.Unavailable unavailable = NetworkAuthority.unavailableIn(error);
                    if (unavailable != null) {
                        core.log(Level.FINE, "[lootbox] recovery for " + player.getUsername()
                                + " postponed: " + unavailable.getMessage());
                    } else {
                        core.log(Level.WARNING, "[lootbox] recovery for " + player.getUsername() + " failed: "
                                + error);
                    }
                    return null;
                });
    }

    private CompletableFuture<Void> recoverInLane(PlayerRef player) {
        UUID uuid = player.getUuid();
        return storage.loadPlayer(uuid).thenCompose(state -> {
            CompletableFuture<Void> active = state.activeOpening == null ? CompletableFuture.completedFuture(null)
                    : storage.loadOpening(state.activeOpening).thenCompose(opening -> recoverActive(player, opening,
                            state.activeOpening));
            return active.thenCompose(ignored -> storage.loadPlayer(uuid)).thenCompose(this::resumeDeliveries)
                    .thenCompose(ignored -> keys.conversions().recoverInLane(player));
        });
    }

    private CompletableFuture<Void> recoverActive(PlayerRef player, Opening opening, String openingId) {
        UUID uuid = player.getUuid();
        if (opening == null) {
            return clearActive(uuid, openingId);
        }
        if (Opening.KEY_VIRTUAL.equals(opening.keyKind) || opening.keySerial == null) {
            return storage.loadPlayer(uuid).thenCompose(state -> state.hasCommit(opening.openingId)
                    ? ensureCommitted(opening)
                    : abort(opening, Status.ERROR, "recovered: virtual key was never spent").thenApply(r -> null));
        }
        if (KeyBatch.isBatch(opening.keySerial)) {
            return recoverBatch(player, opening);
        }
        return storage.loadSerial(opening.keySerial).thenCompose(serial -> keyHeld(player, opening.keySerial)
                .thenCompose(held -> {
                    RecoveryPolicy.Action action = RecoveryPolicy.decide(opening.state, serial, opening.openingId, held);
                    audit.record("server", "RECOVER", opening.openingId, action.name());
                    return switch (action) {
                        case ABORT, ABORT_FOREIGN -> abort(opening, Status.ERROR, "recovered: " + action)
                                .thenApply(r -> (Void) null);
                        case ABORT_RELEASE -> releaseReservation(opening.keySerial, opening.openingId)
                                .thenCompose(r -> abort(opening, Status.ERROR, "recovered: key kept"))
                                .thenApply(r -> (Void) null);
                        case COMMIT -> redeem(opening).thenCompose(ok -> ok ? commitRecovered(uuid, opening)
                                : abort(opening, Status.ERROR, "recovered: commit refused").thenApply(r -> (Void) null));
                        case ENSURE_COMMITTED_CLEAN_UP -> removeDeadKey(player, opening.keySerial)
                                .thenCompose(r -> commitRecovered(uuid, opening));
                        case ENSURE_COMMITTED -> commitRecovered(uuid, opening);
                        case NONE -> clearActive(uuid, opening.openingId);
                    };
                }));
    }

    /**
     * A stackable-key opening left unfinished (P2-K). Recovery prefers abort
     * ({@link StackRules#decideOpening}): the hold is released, and when the
     * player's count of the batch dropped, the item that may have been taken is
     * given back. Spent units are committed as usual.
     */
    private CompletableFuture<Void> recoverBatch(PlayerRef player, Opening opening) {
        UUID uuid = player.getUuid();
        return storage.loadBatch(opening.keySerial).thenCompose(batch -> countHeld(player, opening.keySerial)
                .thenCompose(held -> {
                    StackRules.Recovery action = StackRules.decideOpening(opening.state, batch, opening.openingId, held);
                    audit.record("server", "RECOVER", opening.openingId, "batch " + action.name());
                    return switch (action) {
                        case ABORT -> abort(opening, Status.ERROR, "recovered: batch never held")
                                .thenApply(r -> (Void) null);
                        case RELEASE -> releaseReservation(opening.keySerial, opening.openingId)
                                .thenCompose(r -> abort(opening, Status.ERROR, "recovered: key kept"))
                                .thenApply(r -> (Void) null);
                        case RELEASE_REGIVE -> {
                            int give = StackRules.regive(batch.reservation(opening.openingId), held);
                            yield releaseReservation(opening.keySerial, opening.openingId)
                                    .thenCompose(r -> keys.regive(player, batch, give, opening.openingId))
                                    .thenCompose(r -> abort(opening, Status.ERROR, "recovered: key given back"))
                                    .thenApply(r -> (Void) null);
                        }
                        case COMMITTED -> commitRecovered(uuid, opening);
                        case WAIT, NONE -> clearActive(uuid, opening.openingId);
                    };
                }));
    }

    /** The player's items of {@code batch}; {@code null} when the inventory could not be read. */
    private CompletableFuture<Integer> countHeld(PlayerRef player, String batch) {
        CompletableFuture<Integer> held = new CompletableFuture<>();
        boolean dispatched = core.platform().runOnEntityThread(player, (store, entity, world) -> {
            try {
                held.complete(InventoryAdapter.countSerial(store, entity, batch));
            } catch (Throwable t) {
                held.complete(null);
            }
        });
        if (!dispatched) {
            held.complete(null);
        }
        return held;
    }

    private CompletableFuture<Void> commitRecovered(UUID uuid, Opening opening) {
        return revisionOf(opening).thenCompose(revision -> storage.updatePlayer(uuid, state -> {
            if (!state.hasCommit(opening.openingId) && revision != null) {
                recordCommit(state, opening, revision);
            } else if (opening.openingId.equals(state.activeOpening)) {
                state.activeOpening = null;
            }
            return state;
        })).thenCompose(state -> ensureCommitted(opening));
    }

    private CompletableFuture<Void> ensureCommitted(Opening opening) {
        if (Opening.PREPARED.equals(opening.state) || Opening.RESERVED.equals(opening.state)) {
            opening.state = Opening.COMMITTED;
            opening.committedAt = System.currentTimeMillis();
            audit.record("server", "OPEN_COMMIT", opening.openingId, "recovered");
        }
        return storage.saveOpening(opening).thenCompose(saved -> clearActive(UUID.fromString(opening.player),
                opening.openingId));
    }

    private CompletableFuture<Void> clearActive(UUID uuid, String openingId) {
        return storage.updatePlayer(uuid, state -> {
            if (openingId == null || !openingId.equals(state.activeOpening)) {
                return null;
            }
            state.activeOpening = null;
            return state;
        }).thenApply(state -> null);
    }

    private CompletableFuture<Void> resumeDeliveries(PlayerLootState state) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (String openingId : List.copyOf(state.openDeliveries)) {
            chain = chain.thenCompose(ignored -> {
                Long runningSince = rewards.deliveringSince(openingId);
                return storage.loadOpening(openingId).thenCompose(opening -> {
                    if (!DeliveryRules.resumes(opening, runningSince, System.currentTimeMillis())) {
                        return CompletableFuture.completedFuture(null);
                    }
                    return revisionOf(opening).thenCompose(revision -> {
                        if (revision == null) {
                            return CompletableFuture.completedFuture(null);
                        }
                        return rewards.deliver(opening, revision, false).thenApply(status -> (Void) null);
                    });
                });
            });
        }
        return chain;
    }

    /**
     * {@code /lootbox reconcile <openingId> regrant|dismiss}. A staff command is no
     * player's request, so it is decided in the opening's player lane: two staff
     * members reconciling one opening take turns, and the second finds the first
     * regrant running ({@link DeliveryRules#reconcileProblem}). The lane does not
     * wait for the regrant's delivery, as with {@link #finishCommit}.
     */
    public CompletableFuture<String> reconcile(String openingId, boolean regrant, String actor) {
        return storage.loadOpening(openingId).thenCompose(found -> {
            if (found == null) {
                return CompletableFuture.completedFuture("unknown opening");
            }
            return lanes.run(UUID.fromString(found.player), () -> reconcileInLane(openingId, regrant, actor))
                    .thenCompose(reply -> reply);
        });
    }

    /** Decides and starts the reconcile; the inner future completes once a regrant settled. */
    private CompletableFuture<CompletableFuture<String>> reconcileInLane(String openingId, boolean regrant,
            String actor) {
        Long runningSince = rewards.deliveringSince(openingId);
        return storage.loadOpening(openingId).thenCompose(opening -> {
            String problem = DeliveryRules.reconcileProblem(opening, runningSince);
            if (problem != null) {
                return reply(problem);
            }
            audit.record(actor, regrant ? "RECONCILE_REGRANT" : "RECONCILE_DISMISS", openingId, "");
            if (!regrant) {
                for (Opening.Fulfillment action : opening.fulfillments) {
                    if (Opening.Fulfillment.REVIEW.equals(action.state)) {
                        action.state = Opening.Fulfillment.DISMISSED;
                        action.detail = "dismissed by " + actor;
                    }
                }
                opening.state = Opening.DELIVERED;
                return storage.saveOpening(opening).thenCompose(saved -> storage.updatePlayer(
                        UUID.fromString(opening.player), state -> {
                            state.openDeliveries.remove(openingId);
                            return state;
                        })).thenApply(state -> CompletableFuture.completedFuture("dismissed"));
            }
            return revisionOf(opening).thenApply(revision -> {
                if (rewardOf(revision, opening.outcomeId).isEmpty()) {
                    return CompletableFuture.completedFuture("its revision or reward is gone");
                }
                return rewards.deliver(opening, revision, true).thenApply(status -> status.label());
            });
        });
    }

    private static CompletableFuture<CompletableFuture<String>> reply(String text) {
        return CompletableFuture.completedFuture(CompletableFuture.completedFuture(text));
    }

    private CompletableFuture<LootBoxRevision> revisionOf(Opening opening) {
        LootBoxRevision cached = registry.active(opening.lootboxId).filter(r -> r.revision == opening.revision)
                .orElse(null);
        return cached != null ? CompletableFuture.completedFuture(cached)
                : storage.loadRevision(opening.lootboxId, opening.revision);
    }

    private static Optional<LootBoxDraft.RewardSpec> rewardOf(LootBoxRevision revision, String rewardId) {
        if (revision == null) {
            return Optional.empty();
        }
        return revision.spec.rewards.stream().filter(r -> r.id.equals(rewardId)).findFirst();
    }

    private CompletableFuture<Boolean> keyHeld(PlayerRef player, String serial) {
        CompletableFuture<Boolean> held = new CompletableFuture<>();
        boolean dispatched = core.platform().runOnEntityThread(player, (store, entity, world) ->
                held.complete(!InventoryAdapter.findSerial(store, entity, serial).isEmpty()));
        if (!dispatched) {
            // Cannot look: treat the key as still held, which only ever aborts (nothing lost).
            held.complete(true);
        }
        return held;
    }

    private CompletableFuture<Void> removeDeadKey(PlayerRef player, String serial) {
        CompletableFuture<Void> removed = new CompletableFuture<>();
        boolean dispatched = core.platform().runOnEntityThread(player, (store, entity, world) -> {
            InventoryAdapter.removeAll(store, entity, serial);
            removed.complete(null);
        });
        if (!dispatched) {
            removed.complete(null);
        }
        return removed;
    }

    // ----- History and simulation ---------------------------------------------

    public CompletableFuture<List<PlayerLootState.Receipt>> history(UUID player) {
        return storage.loadPlayer(player).thenApply(state -> List.copyOf(state.receipts));
    }

    // ----- Reduced motion (P2-L) ----------------------------------------------

    /** Whether {@code player} sees reveals without animation, after their own choice and the server default. */
    public CompletableFuture<Boolean> reducedMotion(UUID player) {
        return storage.loadPlayer(player).thenApply(state -> MotionRules.reduced(state,
                config.opening.reducedMotionDefault));
    }

    /** The player's setting as chat and the Catalog show it, e.g. "reduced (server default)". */
    public CompletableFuture<String> describeMotion(UUID player) {
        return storage.loadPlayer(player).thenApply(state -> MotionRules.describe(state,
                config.opening.reducedMotionDefault));
    }

    /** Stores {@code choice} in the player document. @return the setting as it now reads */
    public CompletableFuture<String> setMotion(UUID player, MotionRules.Choice choice) {
        return storage.updatePlayer(player, state -> {
            state.reducedMotion = choice.stored();
            return state;
        }).thenApply(state -> MotionRules.describe(state, config.opening.reducedMotionDefault));
    }

    /** Non-awarding draw test on a worker thread; production RNG, never seeded. */
    public CompletableFuture<long[]> simulate(LootBoxRevision revision, long trials) {
        long bounded = Math.max(1, Math.min(trials, config.simulation.maxTrials));
        OddsTable table = OddsEngine.table(revision, policyProfile());
        return CompletableFuture.supplyAsync(() -> {
            SecureDraw simulationDraw = new SecureDraw();
            long[] counts = new long[table.entries().size()];
            for (long i = 0; i < bounded; i++) {
                counts[table.pick(simulationDraw.roll(table.total())).index()]++;
            }
            return counts;
        });
    }
}
