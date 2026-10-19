package org.hyzionstudios.mysticessentials.modules.lootbox.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
import org.hyzionstudios.mysticessentials.modules.lootbox.model.KeySerial;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxRevision;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.Opening;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.PlayerLootState;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.OddsEngine;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.OddsFormatter;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.OddsTable;
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

    private record Confirm(String lootboxId, String hash, String requestId, String crateId, long issuedAt) {
    }

    private static final long CONFIRM_TTL_MS = TimeUnit.MINUTES.toMillis(10);

    private final MysticCore core;
    private final LootBoxStorage storage;
    private final LootBoxRegistry registry;
    private final KeyService keys;
    private final RewardService rewards;
    private final AuditService audit;
    private final PlayerLanes lanes;
    private final SecureDraw draw;
    private volatile LootBoxConfig config;
    private volatile OddsFormatter formatter;
    private final Map<UUID, Confirm> confirms = new ConcurrentHashMap<>();

    public OpeningService(MysticCore core, LootBoxStorage storage, LootBoxRegistry registry, KeyService keys,
            RewardService rewards, AuditService audit, PlayerLanes lanes, SecureDraw draw, LootBoxConfig config) {
        this.core = core;
        this.storage = storage;
        this.registry = registry;
        this.keys = keys;
        this.rewards = rewards;
        this.audit = audit;
        this.lanes = lanes;
        this.draw = draw;
        updateConfig(config);
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
        OddsTable table = OddsEngine.table(revision, policyProfile());
        Set<String> keyIds = new LinkedHashSet<>(revision.spec.keys);
        CompletableFuture<Integer> physical = countPhysical(player, keyIds);
        return storage.loadPlayer(player.getUuid()).thenCombine(physical, (state, held) -> {
            int virtual = 0;
            for (String keyId : keyIds) {
                virtual += state.virtualKeys.getOrDefault(keyId, 0);
            }
            List<String> blockers = blockers(player, revision, state, virtual + held);
            String requestId = draw.id();
            confirms.put(player.getUuid(), new Confirm(lootboxId, table.hash(), requestId, crateId,
                    System.currentTimeMillis()));
            audit.counter("PREVIEW").incrementAndGet();
            return new Preview(revision, table, virtual, held, requestId, crateId, blockers);
        });
    }

    private List<String> blockers(PlayerRef player, LootBoxRevision revision, PlayerLootState state, int keyCount) {
        List<String> blockers = new ArrayList<>();
        if ("DISABLED".equals(policyProfile()) || registry.isDisabled(revision.lootboxId)) {
            blockers.add("This loot box is closed right now.");
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
        return lanes.run(uuid, () -> openInLane(player, lootboxId, snapshotHash, requestId))
                .exceptionally(error -> {
                    core.log(Level.SEVERE, "[lootbox] opening of " + lootboxId + " by " + player.getUsername()
                            + " failed: " + error);
                    return OpenResult.of(Status.ERROR, "Something went wrong; nothing was spent.");
                });
    }

    private CompletableFuture<OpenResult> openInLane(PlayerRef player, String lootboxId, String snapshotHash,
            String requestId) {
        UUID uuid = player.getUuid();
        return storage.loadPlayer(uuid).thenCompose(state -> {
            String earlier = requestId == null ? null : state.openingForRequest(requestId);
            if (earlier != null) {
                return storage.loadOpening(earlier).thenCompose(opening -> existingResult(opening));
            }
            Confirm confirm = confirms.get(uuid);
            if (confirm == null || requestId == null || !requestId.equals(confirm.requestId())
                    || !lootboxId.equals(confirm.lootboxId())
                    || System.currentTimeMillis() - confirm.issuedAt() > CONFIRM_TTL_MS) {
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
            String node = revision.spec.permission;
            if (node != null && !node.isBlank() && !player.hasPermission(node)) {
                return done(Status.DENIED, "You don't have permission to open this.");
            }
            long wait = cooldownRemaining(state, revision);
            if (wait > 0) {
                return done(Status.COOLDOWN, "You can open this again in "
                        + TimeUnit.MILLISECONDS.toSeconds(wait + 999) + "s.");
            }
            OddsTable table = OddsEngine.table(revision, policyProfile());
            if (snapshotHash == null || !snapshotHash.equals(table.hash())) {
                audit.counter("STALE").incrementAndGet();
                return done(Status.STALE, "The odds changed while you were looking. Check them and confirm again.");
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
        String virtualKey = null;
        for (String keyId : revision.spec.keys) {
            if (state.virtualKeys.getOrDefault(keyId, 0) > 0) {
                virtualKey = keyId;
                break;
            }
        }
        boolean physicalFirst = "PHYSICAL_FIRST".equalsIgnoreCase(config.keys.consumeOrder);
        if (virtualKey != null && !physicalFirst) {
            return prepare(player, revision, table, requestId, crateId)
                    .thenCompose(opening -> commitVirtual(player, opening, revision, table, virtualKeyOf(revision, state)));
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

    private static String virtualKeyOf(LootBoxRevision revision, PlayerLootState state) {
        for (String keyId : revision.spec.keys) {
            if (state.virtualKeys.getOrDefault(keyId, 0) > 0) {
                return keyId;
            }
        }
        return null;
    }

    /** Draws the outcome and records the opening as PREPARED before any key is touched. */
    private CompletableFuture<Opening> prepare(PlayerRef player, LootBoxRevision revision, OddsTable table,
            String requestId, String crateId) {
        long roll = draw.roll(table.total());
        OddsTable.Entry entry = table.pick(roll);
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
        Opening.Fulfillment action = new Opening.Fulfillment();
        action.id = opening.openingId + "#0";
        action.type = reward.type == null ? "" : reward.type.toUpperCase(Locale.ROOT);
        opening.fulfillments.add(action);
        audit.record(player.getUuid().toString(), "OPEN_REQUEST", opening.openingId,
                revision.lootboxId + "@" + revision.revision + " odds " + table.oddsId());
        return storage.saveOpening(opening).thenCompose(saved -> storage.updatePlayer(player.getUuid(), state -> {
            state.activeOpening = opening.openingId;
            PlayerLootState.RequestRecord request = new PlayerLootState.RequestRecord();
            request.requestId = requestId;
            request.openingId = opening.openingId;
            request.at = opening.createdAt;
            state.requests.add(request);
            long horizon = opening.createdAt - TimeUnit.MINUTES.toMillis(config.opening.requestDedupeMinutes);
            state.requests.removeIf(r -> r.at < horizon);
            while (state.requests.size() > PlayerLootState.MAX_REQUESTS) {
                state.requests.remove(0);
            }
            return state;
        })).thenApply(state -> opening);
    }

    private CompletableFuture<OpenResult> commitVirtual(PlayerRef player, Opening opening, LootBoxRevision revision,
            OddsTable table, String keyId) {
        opening.keyKind = Opening.KEY_VIRTUAL;
        opening.keyId = keyId;
        AtomicBoolean committed = new AtomicBoolean();
        return storage.updatePlayer(player.getUuid(), state -> {
            if (state.hasCommit(opening.openingId)) {
                committed.set(true);
                return null;
            }
            int balance = state.virtualKeys.getOrDefault(keyId, 0);
            if (balance < 1) {
                if (opening.openingId.equals(state.activeOpening)) {
                    state.activeOpening = null;
                }
                return state;
            }
            if (balance == 1) {
                state.virtualKeys.remove(keyId);
            } else {
                state.virtualKeys.put(keyId, balance - 1);
            }
            recordCommit(state, opening, revision);
            committed.set(true);
            return state;
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
            boolean lastUse = reserved.serial().usesLeft <= 1;
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

    private record Reserved(InventoryAdapter.KeyStack stack, KeySerial serial) {
    }

    /** Reserves the first stack whose serial may be spent. */
    private CompletableFuture<Reserved> reserveFirst(PlayerRef player, Opening opening,
            List<InventoryAdapter.KeyStack> stacks, int index, String lastProblem) {
        if (index >= stacks.size()) {
            return CompletableFuture.completedFuture(null);
        }
        InventoryAdapter.KeyStack stack = stacks.get(index);
        long now = System.currentTimeMillis();
        AtomicReference<String> problem = new AtomicReference<>();
        return storage.updateSerial(stack.marker().serial(), serial -> {
            String why = KeyService.spendProblem(serial, stack.marker().keyId(), now);
            if (why != null) {
                problem.set(why);
                return null;
            }
            serial.state = KeySerial.RESERVED;
            serial.reservedBy = opening.openingId;
            serial.reservedAt = now;
            return serial;
        }).thenCompose(serial -> {
            if (problem.get() == null && serial != null && opening.openingId.equals(serial.reservedBy)) {
                return CompletableFuture.completedFuture(new Reserved(stack, serial));
            }
            flagIfSuspicious(player, opening.lootboxId, stack, problem.get());
            return reserveFirst(player, opening, stacks, index + 1, problem.get());
        });
    }

    /** A valid marker on an unspendable serial is a copied or recycled key. */
    private void flagIfSuspicious(PlayerRef player, String lootboxId, InventoryAdapter.KeyStack stack, String problem) {
        if (problem == null || "expired".equals(problem) || "wrong key".equals(problem)) {
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

    /** The physical-key commit point: RESERVED(this) → one use spent, outcome recorded. */
    private CompletableFuture<Boolean> redeem(Opening opening) {
        AtomicBoolean redeemed = new AtomicBoolean();
        return storage.updateSerial(opening.keySerial, serial -> {
            if (serial == null || !KeySerial.RESERVED.equals(serial.state)
                    || !opening.openingId.equals(serial.reservedBy)) {
                return null;
            }
            serial.usesLeft = Math.max(0, serial.usesLeft - 1);
            serial.state = serial.usesLeft == 0 ? KeySerial.REDEEMED : KeySerial.ISSUED;
            serial.reservedBy = null;
            serial.redeemedBy = opening.openingId;
            serial.redeemedOutcome = opening.outcomeId;
            redeemed.set(true);
            return serial;
        }).thenApply(serial -> redeemed.get());
    }

    private CompletableFuture<Void> releaseReservation(String serialId, String openingId) {
        return storage.updateSerial(serialId, serial -> {
            if (serial == null || !KeySerial.RESERVED.equals(serial.state) || !openingId.equals(serial.reservedBy)) {
                return null;
            }
            serial.state = KeySerial.ISSUED;
            serial.reservedBy = null;
            return serial;
        }).thenApply(serial -> null);
    }

    /** Appends the commit record, receipt and bookkeeping to the player document (caller holds the update). */
    private void recordCommit(PlayerLootState state, Opening opening, LootBoxRevision revision) {
        long now = System.currentTimeMillis();
        PlayerLootState.CommitRecord commit = new PlayerLootState.CommitRecord();
        commit.openingId = opening.openingId;
        commit.lootboxId = opening.lootboxId;
        commit.revision = opening.revision;
        commit.outcomeId = opening.outcomeId;
        commit.snapshotHash = opening.snapshotHash;
        commit.keyId = opening.keyId;
        commit.keyKind = opening.keyKind;
        commit.at = now;
        state.commits.add(commit);
        while (state.commits.size() > PlayerLootState.MAX_COMMITS) {
            state.commits.remove(0);
        }
        PlayerLootState.PoolState pool = state.pools.computeIfAbsent(opening.lootboxId,
                id -> new PlayerLootState.PoolState());
        pool.lastOpenAt = now;
        pool.opens++;
        state.lastOpenAt = now;
        if (opening.openingId.equals(state.activeOpening)) {
            state.activeOpening = null;
        }
        if (!state.openDeliveries.contains(opening.openingId)) {
            state.openDeliveries.add(opening.openingId);
        }
        PlayerLootState.Receipt receipt = new PlayerLootState.Receipt();
        receipt.openingId = opening.openingId;
        receipt.at = now;
        receipt.lootboxId = opening.lootboxId;
        receipt.poolName = revision.spec.displayName;
        receipt.revision = opening.revision;
        receipt.rewardId = opening.outcomeId;
        receipt.rewardName = opening.outcomeName;
        receipt.keyLabel = opening.keyId + (Opening.KEY_PHYSICAL.equals(opening.keyKind) ? " (item)" : " (virtual)");
        receipt.probability = opening.probability;
        receipt.oddsId = opening.snapshotHash.substring(0, Math.min(8, opening.snapshotHash.length()));
        receipt.status = "Delivering";
        state.receipts.add(0, receipt);
        int keep = Math.max(1, config.opening.historySize);
        while (state.receipts.size() > keep) {
            state.receipts.remove(state.receipts.size() - 1);
        }
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
            CompletableFuture<RewardService.Status> delivery = rewards.deliver(opening, reward, false)
                    .exceptionally(error -> {
                        rewards.logFailure(opening, error);
                        return RewardService.Status.REVIEW;
                    });
            if (reward.announce) {
                delivery.thenRun(() -> announce(player, revision, reward));
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
     * commit (decided by {@link RecoveryPolicy}), and deliveries that never settled.
     * Runs in the player's lane, so it never races a new opening.
     */
    public CompletableFuture<Void> recover(PlayerRef player) {
        UUID uuid = player.getUuid();
        return lanes.run(uuid, () -> storage.loadPlayer(uuid).thenCompose(state -> {
            CompletableFuture<Void> active = state.activeOpening == null ? CompletableFuture.completedFuture(null)
                    : storage.loadOpening(state.activeOpening).thenCompose(opening -> recoverActive(player, opening,
                            state.activeOpening));
            return active.thenCompose(ignored -> storage.loadPlayer(uuid)).thenCompose(this::resumeDeliveries);
        })).exceptionally(error -> {
            core.log(Level.WARNING, "[lootbox] recovery for " + player.getUsername() + " failed: " + error);
            return null;
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
            chain = chain.thenCompose(ignored -> storage.loadOpening(openingId).thenCompose(opening -> {
                if (opening == null || opening.isTerminal()) {
                    return CompletableFuture.completedFuture(null);
                }
                return revisionOf(opening).thenCompose(revision -> {
                    LootBoxDraft.RewardSpec reward = rewardOf(revision, opening.outcomeId).orElse(null);
                    if (reward == null) {
                        return CompletableFuture.completedFuture(null);
                    }
                    return rewards.deliver(opening, reward, false).thenApply(status -> (Void) null);
                });
            }));
        }
        return chain;
    }

    /** {@code /lootbox reconcile <openingId> regrant|dismiss}. */
    public CompletableFuture<String> reconcile(String openingId, boolean regrant, String actor) {
        return storage.loadOpening(openingId).thenCompose(opening -> {
            if (opening == null) {
                return CompletableFuture.completedFuture("unknown opening");
            }
            if (!Opening.REVIEW.equals(opening.state)) {
                return CompletableFuture.completedFuture("not under review (" + opening.state + ")");
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
                        })).thenApply(state -> "dismissed");
            }
            return revisionOf(opening).thenCompose(revision -> {
                LootBoxDraft.RewardSpec reward = rewardOf(revision, opening.outcomeId).orElse(null);
                if (reward == null) {
                    return CompletableFuture.completedFuture("its revision or reward is gone");
                }
                return rewards.deliver(opening, reward, true).thenApply(status -> status.label());
            });
        });
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
