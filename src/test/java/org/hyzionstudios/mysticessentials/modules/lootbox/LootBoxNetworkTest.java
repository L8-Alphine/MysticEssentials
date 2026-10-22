package org.hyzionstudios.mysticessentials.modules.lootbox;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

import org.hyzionstudios.mysticessentials.api.service.StorageService;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.Opening;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.PlayerLootState;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.SecureDraw;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.NetworkAuthority;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.NetworkRules;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.OpeningRules;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.PlayerLanes;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.RetentionRules;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.StatsRules;
import org.hyzionstudios.mysticessentials.modules.lootbox.storage.LootBoxStorage;

import com.google.gson.JsonElement;

/**
 * Chaos checks of network openings (P4-WP6), engine-free and deterministic.
 *
 * <p>Two "servers" share one {@link SharedStore} (atomic read-modify-write
 * across servers, like the SQL provider's {@code updateShared}; it can be taken
 * down, or lose the acknowledgement of a write that did land) and one
 * {@link FakeLock} (a Redis lock whose TTL runs on a manual clock; it can be
 * taken down). Each server has its own {@link LootBoxStorage}, {@link PlayerLanes}
 * and {@link NetworkAuthority}, which are the production classes. {@link Server}
 * drives the virtual-key opening saga the way {@code OpeningService} does on a
 * NETWORK pool ({@code openOnNetwork} → {@code openInLane} → {@code prepare} →
 * {@code commitVirtual} → {@code finishCommit} → delivery; {@code recover} under
 * the lease), with the same {@link OpeningRules}. Delivery is an action claimed
 * on the opening document before it takes effect, as {@code RewardService} does.</p>
 *
 * <p>Every scenario ends with the same two invariants: no key is spent twice
 * (the balance equals grants minus commits, and every commit is one opening),
 * and no committed reward is lost (after recovery every committed opening's
 * reward took effect exactly once).</p>
 */
public final class LootBoxNetworkTest {

    private static final String POOL = "network_crate";
    private static final String KEY = "mystic:net";
    private static final long TIMEOUT_SECONDS = 10;
    private static final ExecutorService IO = Executors.newFixedThreadPool(6, task -> {
        Thread thread = new Thread(task, "network-io");
        thread.setDaemon(true);
        return thread;
    });

    private LootBoxNetworkTest() {
    }

    public static void main(String[] args) {
        try {
            rulesFailClosedAndFence();
            twoServersShareOneBalance();
            duplicatePacketsOpenOnce();
            redisDownClosesNetworkPoolsOnly();
            sqlDownSpendsNothing();
            killedMidCommitIsFinishedElsewhere();
            partitionBeforeCommitIsFencedOut();
            lostAcknowledgementIsRecoveredOnce();
            staleDeliveryIsFencedOut();
            statisticsAndRetention();
        } finally {
            IO.shutdownNow();
        }
        System.out.println("LootBoxNetworkTest: all checks passed.");
    }

    // ----- Rules ----------------------------------------------------------------

    private static void rulesFailClosedAndFence() {
        require(NetworkRules.unavailable(false, true, true, true) != null, "network off must refuse");
        require(NetworkRules.unavailable(true, true, false, true) != null, "a store that isn't shared must refuse");
        require(NetworkRules.unavailable(true, false, true, true) != null, "Redis down must refuse");
        require(NetworkRules.unavailable(true, true, true, false) != null, "a different secret must refuse");
        require(NetworkRules.unavailable(true, true, true, true) == null, "everything up must allow");
        NetworkRules.admit(5, 5);
        NetworkRules.admit(5, 7);
        NetworkRules.admit(9, 0);
        boolean refused = false;
        try {
            NetworkRules.admit(7, 5);
        } catch (NetworkRules.FencedOut e) {
            refused = true;
        }
        require(refused, "an older lease must be fenced out");
        require(NetworkRules.after(5, 7) == 7 && NetworkRules.after(9, 0) == 9, "the stored fence only grows");
        require(NetworkRules.validateScope("network") == null && NetworkRules.validateScope("REGION") != null,
                "scope validation");
        require(NetworkRules.lockKey("hyzion", new UUID(0, 1)).equals(
                "hyzion:lootbox:lock:00000000-0000-0000-0000-000000000001"), "lock key layout");
    }

    // ----- Scenarios ------------------------------------------------------------

    /** Two servers open against one balance at once: every key is spent once, and only once. */
    private static void twoServersShareOneBalance() {
        Network net = new Network();
        Server a = net.server("a");
        Server b = net.server("b");
        UUID player = UUID.randomUUID();
        net.grant(a, player, 5);
        List<CompletableFuture<Outcome>> attempts = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            attempts.add(a.open(player, "a-" + i, true));
            attempts.add(b.open(player, "b-" + i, true));
        }
        int committed = 0;
        for (CompletableFuture<Outcome> attempt : attempts) {
            Outcome outcome = await(attempt);
            require(outcome == Outcome.COMMITTED || outcome == Outcome.NO_KEY || outcome == Outcome.CLOSED,
                    "unexpected outcome " + outcome + " (" + a.lastError + " / " + b.lastError + ")");
            committed += outcome == Outcome.COMMITTED ? 1 : 0;
        }
        require(committed == 5, "5 keys must open 5 times across both servers, not " + committed);
        net.verify(player, 5);
    }

    /** The same confirmation sent twice, to the same and to the other server, opens once. */
    private static void duplicatePacketsOpenOnce() {
        Network net = new Network();
        Server a = net.server("a");
        Server b = net.server("b");
        UUID player = UUID.randomUUID();
        net.grant(a, player, 3);
        Outcome first = await(a.open(player, "req-1", true));
        Outcome again = await(a.open(player, "req-1", true));
        Outcome elsewhere = await(b.open(player, "req-1", true));
        require(first == Outcome.COMMITTED, "the first confirmation must open: " + first);
        require(again == Outcome.DUPLICATE && elsewhere == Outcome.DUPLICATE,
                "a repeated confirmation must return the first opening: " + again + ", " + elsewhere);
        require(net.balance(a, player) == 2, "a duplicate must not spend a second key");
        net.verify(player, 3);
    }

    /** Redis down: NETWORK openings refuse before anything is read for spending; LOCAL ones still work. */
    private static void redisDownClosesNetworkPoolsOnly() {
        Network net = new Network();
        Server a = net.server("a");
        UUID player = UUID.randomUUID();
        net.grant(a, player, 2);
        net.lock.down = true;
        Map<String, String> before = net.store.snapshot();
        require(await(a.open(player, "n-1", true)) == Outcome.CLOSED, "a network opening must fail closed");
        require(before.equals(net.store.snapshot()), "a closed network opening must write nothing");
        require(await(a.open(player, "l-1", false)) == Outcome.COMMITTED, "a local opening must still work");
        net.lock.down = false;
        require(await(a.open(player, "n-2", true)) == Outcome.COMMITTED, "network openings resume with Redis");
        net.verify(player, 2);
    }

    /** SQL down before or during an opening: nothing is spent, and recovery cleans up once it is back. */
    private static void sqlDownSpendsNothing() {
        Network net = new Network();
        Server a = net.server("a");
        UUID player = UUID.randomUUID();
        net.grant(a, player, 2);
        net.store.down = true;
        require(await(a.open(player, "s-1", true)) == Outcome.CLOSED,
                "without the shared store no fencing token is handed out, so the opening must close");
        net.store.down = false;
        require(net.balance(a, player) == 2, "nothing may be spent while SQL is down");

        // Down between prepare and the commit write: the commit fails, the key stays.
        a.failBeforeCommit = () -> net.store.down = true;
        Outcome failed = await(a.open(player, "s-2", true));
        require(failed == Outcome.ERROR, "a commit that could not be written must fail: " + failed);
        net.store.down = false;
        a.failBeforeCommit = null;
        require(net.balance(a, player) == 2, "a failed commit must not spend");
        await(a.recover(player));
        require(net.stateOf(a, player).activeOpening == null, "recovery must clear the unspent opening");
        require(await(a.open(player, "s-3", true)) == Outcome.COMMITTED, "opening works again afterwards");
        net.verify(player, 2);
    }

    /** A server dies right after the commit write: another server's recovery delivers the reward, once. */
    private static void killedMidCommitIsFinishedElsewhere() {
        Network net = new Network();
        Server a = net.server("a");
        Server b = net.server("b");
        UUID player = UUID.randomUUID();
        net.grant(a, player, 1);
        a.dieAfterCommit = true;
        CompletableFuture<Outcome> dying = a.open(player, "k-1", true);
        await(a.committed);
        require(!dying.isDone(), "the dead server never answers");
        require(net.balance(b, player) == 0, "the commit landed: the key is spent");
        require(net.effects.isEmpty(), "nothing was delivered before the crash");
        net.lock.expireAll(); // the dead server's lock runs out
        await(b.recover(player));
        net.verify(player, 1);
        require(net.effects.size() == 1, "the committed reward must be delivered by the other server");
    }

    /**
     * A partition right before the commit (the saga's step 6): the lock expires,
     * the other server recovers the opening (never spent, so it is aborted), and
     * when the partitioned server comes back its commit is fenced out.
     */
    private static void partitionBeforeCommitIsFencedOut() {
        Network net = new Network();
        Server a = net.server("a");
        Server b = net.server("b");
        UUID player = UUID.randomUUID();
        net.grant(a, player, 1);
        CompletableFuture<Void> heal = new CompletableFuture<>();
        a.pauseBeforeCommit = heal;
        CompletableFuture<Outcome> partitioned = a.open(player, "p-1", true);
        await(a.paused);
        net.lock.expireAll();
        await(b.recover(player));
        PlayerLootState recovered = net.stateOf(b, player);
        require(recovered.activeOpening == null && recovered.virtualKeys.getOrDefault(KEY, 0) == 1,
                "recovery must abort the unspent opening and keep the key");
        heal.complete(null);
        require(await(partitioned) == Outcome.FENCED, "the stale server's commit must be fenced out");
        require(net.balance(b, player) == 1, "the fenced-out commit must not spend the key");
        require(await(b.open(player, "p-2", true)) == Outcome.COMMITTED, "the key still opens normally");
        net.verify(player, 1);
    }

    /**
     * The commit lands but its acknowledgement is lost (a partition during step
     * 6): the server reports a failure, and recovery finds the commit and
     * delivers it exactly once; the key is spent exactly once.
     */
    private static void lostAcknowledgementIsRecoveredOnce() {
        Network net = new Network();
        Server a = net.server("a");
        Server b = net.server("b");
        UUID player = UUID.randomUUID();
        net.grant(a, player, 2);
        a.loseCommitAck = true;
        require(await(a.open(player, "x-1", true)) == Outcome.ERROR, "a lost acknowledgement looks like a failure");
        a.loseCommitAck = false;
        require(net.balance(b, player) == 1, "the commit did land: one key spent");
        require(net.effects.isEmpty(), "the failed server delivered nothing");
        await(b.recover(player));
        require(net.effects.size() == 1, "recovery must deliver the committed reward");
        await(a.recover(player));
        require(net.effects.size() == 1 && net.effectCount() == 1, "a second recovery must not deliver again");
        net.verify(player, 2);
    }

    /**
     * A server commits, then stalls before claiming the delivery; its lock
     * expires, the other server delivers, and the stale server's claim is fenced
     * out, so the reward takes effect once.
     */
    private static void staleDeliveryIsFencedOut() {
        Network net = new Network();
        Server a = net.server("a");
        Server b = net.server("b");
        UUID player = UUID.randomUUID();
        net.grant(a, player, 1);
        CompletableFuture<Void> resume = new CompletableFuture<>();
        a.pauseBeforeDelivery = resume;
        CompletableFuture<Outcome> stalled = a.open(player, "d-1", true);
        await(a.paused);
        net.lock.expireAll();
        await(b.recover(player));
        require(net.effects.size() == 1, "the other server must deliver the committed reward");
        resume.complete(null);
        Outcome outcome = await(stalled);
        require(outcome == Outcome.FENCED, "the stale delivery must be fenced out: " + outcome);
        require(net.effectCount() == 1, "the reward must take effect once, not " + net.effectCount());
        net.verify(player, 1);
    }

    // ----- P4-WP4 / WP5 ---------------------------------------------------------

    private static void statisticsAndRetention() {
        require(Math.abs(StatsRules.z(50, 100, 0.5)) < 1e-9, "z of an exact expectation must be 0");
        require(Math.abs(StatsRules.z(60, 100, 0.5) - 2.0) < 1e-9, "z(60 of 100 at 50%) must be 2");
        require(StatsRules.z(5, 0, 0.5) == 0 && StatsRules.z(5, 10, 0) == 0, "degenerate z must be 0");
        require(!StatsRules.unusualLuck(29, 29, 0.01, 30, 4.5), "too few openings must never flag");
        require(StatsRules.unusualLuck(100, 10, 0.01, 30, 4.5), "10 rare wins in 100 at 1% must flag");
        require(!StatsRules.unusualLuck(100, 2, 0.01, 30, 4.5), "2 rare wins in 100 at 1% is luck, not a flag");
        require(!StatsRules.unusualLuck(100, 0, 0.5, 30, 4.5), "bad luck is never flagged");
        String bucket = StatsRules.bucket("crate", 3, 0L);
        require(bucket.equals("crate@3@19700101") && "19700101".equals(StatsRules.dayOf(bucket)),
                "stats bucket layout: " + bucket);

        require(RetentionRules.effectiveDays(30, "FREE_GAMEPLAY_ONLY") == 30, "free profile keeps the setting");
        require(RetentionRules.effectiveDays(30, "paid_random_restricted") == 365, "paid profile keeps a year");
        require(RetentionRules.effectiveDays(0, "FREE_GAMEPLAY_ONLY") == 1, "retention is at least a day");
        Opening settled = new Opening();
        settled.state = Opening.DELIVERED;
        settled.createdAt = 1_000;
        require(RetentionRules.sweepable(settled, 2_000), "an old settled opening may be swept");
        require(!RetentionRules.sweepable(settled, 500), "a recent opening must be kept");
        settled.state = Opening.REVIEW;
        require(!RetentionRules.sweepable(settled, 2_000), "an opening under review is never swept");
        settled.state = Opening.COMMITTED;
        require(!RetentionRules.sweepable(settled, 2_000), "an undelivered opening is never swept");

        String uuid = "123e4567-e89b-12d3-a456-426614174000";
        byte[] salt = {1, 2, 3};
        String shown = RetentionRules.pseudonymise("OPEN_COMMIT by " + uuid + " for " + uuid.toUpperCase(), salt);
        require(!shown.toLowerCase().contains(uuid), "an export without identifiers must not contain the UUID");
        String alias = RetentionRules.pseudonym(uuid, salt);
        require(shown.equals("OPEN_COMMIT by " + alias + " for " + alias), "pseudonyms must be stable: " + shown);
        require(!alias.equals(RetentionRules.pseudonym(uuid, new byte[] {9})), "the pseudonym depends on the key");
    }

    // ----- Harness ----------------------------------------------------------------

    enum Outcome {
        COMMITTED, DUPLICATE, NO_KEY, BUSY, CLOSED, FENCED, ERROR
    }

    /** The shared world: one store, one lock, the rewards that took effect. */
    private static final class Network {
        final SharedStore store = new SharedStore();
        final FakeLock lock = new FakeLock();
        /** fulfillment id → times it took effect (must end at 1 for every committed opening). */
        final Map<String, AtomicLong> effects = new ConcurrentHashMap<>();
        final List<Server> servers = new ArrayList<>();
        int granted;

        Server server(String name) {
            Server server = new Server(this, name);
            servers.add(server);
            return server;
        }

        void grant(Server via, UUID player, int keys) {
            granted += keys;
            await(via.storage.updatePlayer(player, state -> {
                state.virtualKeys.merge(KEY, keys, Integer::sum);
                return state;
            }));
        }

        PlayerLootState stateOf(Server via, UUID player) {
            return await(via.storage.loadPlayer(player));
        }

        int balance(Server via, UUID player) {
            return stateOf(via, player).virtualKeys.getOrDefault(KEY, 0);
        }

        long effectCount() {
            return effects.values().stream().mapToLong(AtomicLong::get).sum();
        }

        /** The two invariants, checked after a final recovery by a healthy server. */
        void verify(UUID player, int grantedKeys) {
            Server healthy = servers.get(servers.size() - 1);
            store.down = false;
            lock.down = false;
            lock.expireAll();
            await(healthy.recover(player));
            PlayerLootState state = stateOf(healthy, player);
            Set<String> commits = ConcurrentHashMap.newKeySet();
            state.commits.forEach(commit -> commits.add(commit.openingId));
            require(commits.size() == state.commits.size(), "an opening was committed twice");
            int balance = state.virtualKeys.getOrDefault(KEY, 0);
            require(balance >= 0, "the balance went negative");
            require(balance + commits.size() == grantedKeys, "double spend or lost key: " + grantedKeys
                    + " granted, " + commits.size() + " committed, " + balance + " left");
            for (String openingId : commits) {
                AtomicLong count = effects.get(openingId + "#0");
                require(count != null && count.get() == 1, "committed opening " + openingId
                        + " must take effect exactly once, took " + (count == null ? 0 : count.get()));
            }
            require(effects.size() == commits.size(), "a reward took effect without a commit");
            require(state.activeOpening == null && state.openDeliveries.isEmpty(),
                    "recovery must leave nothing half-done");
        }
    }

    /**
     * One server: the production {@link LootBoxStorage}, {@link PlayerLanes} and
     * {@link NetworkAuthority}, and the virtual-key saga of {@code OpeningService}.
     */
    private static final class Server {
        final Network net;
        final String name;
        final LootBoxStorage storage;
        final PlayerLanes lanes = new PlayerLanes();
        final NetworkAuthority authority;
        final SecureDraw draw = new SecureDraw();
        /** Completed when a saga reaches its pause point or commit (for the test to act). */
        final CompletableFuture<Void> paused = new CompletableFuture<>();
        final CompletableFuture<Void> committed = new CompletableFuture<>();
        volatile CompletableFuture<Void> pauseBeforeCommit;
        volatile CompletableFuture<Void> pauseBeforeDelivery;
        volatile Runnable failBeforeCommit;
        volatile boolean dieAfterCommit;
        volatile boolean loseCommitAck;
        /** Why the last ERROR outcome happened, for failure messages. */
        volatile Throwable lastError;

        Server(Network net, String name) {
            this.net = net;
            this.name = name;
            this.storage = new LootBoxStorage(() -> net.store);
            this.authority = new NetworkAuthority(storage, net.lock, IO, null,
                    () -> new NetworkAuthority.Timing(30, 10, 3000, 2), () -> true, () -> "test", () -> name,
                    System::currentTimeMillis);
        }

        /** {@code OpeningService.open}: NETWORK pools under the lease, LOCAL ones without. */
        CompletableFuture<Outcome> open(UUID player, String requestId, boolean network) {
            return lanes.run(player, () -> {
                if (!network) {
                    return openInLane(player, requestId);
                }
                return authority.withLease(player, lease -> openInLane(player, requestId))
                        .exceptionally(error -> {
                            if (NetworkAuthority.unavailableIn(error) != null) {
                                return Outcome.CLOSED;
                            }
                            if (NetworkRules.fencedOut(error)) {
                                return Outcome.FENCED;
                            }
                            throw error instanceof CompletionException c ? c : new CompletionException(error);
                        });
            }).exceptionally(error -> {
                lastError = error;
                return Outcome.ERROR;
            });
        }

        CompletableFuture<Outcome> openInLane(UUID player, String requestId) {
            return storage.loadPlayer(player).thenCompose(state -> {
                if (OpeningRules.earlierOpening(state, requestId) != null) {
                    return done(Outcome.DUPLICATE);
                }
                if (state.activeOpening != null) {
                    return done(Outcome.BUSY);
                }
                String key = OpeningRules.firstVirtualKey(List.of(KEY), state);
                if (key == null) {
                    return done(Outcome.NO_KEY);
                }
                return prepare(player, requestId).thenCompose(opening -> commitVirtual(player, opening, key));
            });
        }

        /** {@code prepare}: the opening is PREPARED (and remembered by its request) before any key is touched. */
        CompletableFuture<Opening> prepare(UUID player, String requestId) {
            Opening opening = new Opening();
            opening.openingId = name + "-" + draw.id();
            opening.player = player.toString();
            opening.requestId = requestId;
            opening.lootboxId = POOL;
            opening.revision = 1;
            opening.outcomeId = "prize";
            opening.outcomeName = "Prize";
            opening.snapshotHash = "0123456789abcdef";
            opening.probability = "1/1";
            opening.createdAt = System.currentTimeMillis();
            Opening.Fulfillment action = new Opening.Fulfillment();
            action.id = opening.openingId + "#0";
            action.type = "EXTENSION";
            opening.fulfillments.add(action);
            return storage.saveOpening(opening).thenCompose(saved -> storage.updatePlayer(player, state -> {
                OpeningRules.recordPrepared(state, opening, 1440);
                return state;
            })).thenApply(state -> opening);
        }

        /** {@code commitVirtual}: the commit point, one player-document update. */
        CompletableFuture<Outcome> commitVirtual(UUID player, Opening opening, String key) {
            CompletableFuture<Void> gate = pauseBeforeCommit;
            CompletableFuture<Void> before = CompletableFuture.completedFuture(null);
            if (gate != null) {
                pauseBeforeCommit = null;
                paused.complete(null);
                before = gate;
            }
            Runnable fail = failBeforeCommit;
            if (fail != null) {
                fail.run();
            }
            AtomicBoolean spent = new AtomicBoolean();
            boolean loseAck = loseCommitAck;
            return before.thenCompose(ignored -> {
                if (loseAck) {
                    net.store.loseNextAck("lootbox_player");
                }
                return storage.updatePlayer(player, state -> {
                    OpeningRules.Debit debit = OpeningRules.spendVirtual(state, key, opening, "Network Crate", 50,
                            System.currentTimeMillis());
                    spent.set(debit != OpeningRules.Debit.NO_KEY);
                    return debit == OpeningRules.Debit.ALREADY_COMMITTED ? null : state;
                });
            }).thenCompose(state -> {
                if (!spent.get()) {
                    opening.state = Opening.ABORTED;
                    return storage.saveOpening(opening).thenApply(saved -> Outcome.NO_KEY);
                }
                committed.complete(null);
                if (dieAfterCommit) {
                    return new CompletableFuture<>(); // the process is gone; nothing more happens here
                }
                return finishCommit(opening);
            });
        }

        /** {@code finishCommit} and the delivery. */
        CompletableFuture<Outcome> finishCommit(Opening opening) {
            opening.state = Opening.COMMITTED;
            opening.committedAt = System.currentTimeMillis();
            return storage.saveOpening(opening).thenCompose(saved -> {
                CompletableFuture<Void> gate = pauseBeforeDelivery;
                if (gate == null) {
                    return deliver(opening.openingId);
                }
                pauseBeforeDelivery = null;
                paused.complete(null);
                return gate.thenCompose(ignored -> deliver(opening.openingId));
            }).thenApply(ignored -> Outcome.COMMITTED);
        }

        /**
         * {@code RewardService.deliver}: claims the action on the opening (fenced),
         * takes effect only after a successful claim, then settles the opening and
         * the player's open deliveries.
         */
        CompletableFuture<Void> deliver(String openingId) {
            AtomicBoolean claimed = new AtomicBoolean();
            return storage.updateOpening(openingId, opening -> {
                if (opening == null || Opening.DELIVERED.equals(opening.state)) {
                    return null;
                }
                Opening.Fulfillment action = opening.fulfillments.get(0);
                if (Opening.Fulfillment.DELIVERED.equals(action.state)) {
                    return null;
                }
                action.state = Opening.Fulfillment.ATTEMPTED;
                claimed.set(true);
                return opening;
            }).thenCompose(opening -> {
                if (!claimed.get()) {
                    return CompletableFuture.completedFuture(opening);
                }
                // The effect: idempotent per fulfillment id would hide a double, so count raw effects.
                net.effects.computeIfAbsent(openingId + "#0", id -> new AtomicLong()).incrementAndGet();
                return storage.updateOpening(openingId, stored -> {
                    stored.fulfillments.get(0).state = Opening.Fulfillment.DELIVERED;
                    stored.state = Opening.DELIVERED;
                    return stored;
                });
            }).thenCompose(opening -> storage.updatePlayer(UUID.fromString(opening.player), state -> {
                return state.openDeliveries.remove(openingId) ? state : null;
            })).thenApply(state -> null);
        }

        /** {@code OpeningService.recover}: under the lease on a network. */
        CompletableFuture<Void> recover(UUID player) {
            return lanes.run(player, () -> authority.withLease(player, lease -> storage.loadPlayer(player)
                    .thenCompose(state -> {
                        CompletableFuture<Void> active = state.activeOpening == null
                                ? CompletableFuture.completedFuture(null)
                                : recoverActive(player, state.activeOpening);
                        return active.thenCompose(ignored -> storage.loadPlayer(player))
                                .thenCompose(this::resumeDeliveries);
                    })));
        }

        /** {@code recoverActive} for a virtual key: committed → finish, never spent → abort. */
        CompletableFuture<Void> recoverActive(UUID player, String openingId) {
            return storage.loadPlayer(player).thenCompose(state -> {
                if (state.hasCommit(openingId)) {
                    return CompletableFuture.completedFuture(null);
                }
                return storage.updateOpening(openingId, opening -> {
                    if (opening == null) {
                        return null;
                    }
                    opening.state = Opening.ABORTED;
                    return opening;
                }).thenCompose(ignored -> storage.updatePlayer(player, current -> {
                    if (!openingId.equals(current.activeOpening)) {
                        return null;
                    }
                    current.activeOpening = null;
                    return current;
                })).thenApply(ignored -> null);
            });
        }

        /**
         * {@code resumeDeliveries}: an action that was claimed but never settled is
         * redone only for a replay-safe provider; this harness's provider is, so a
         * dead server's claim is redone here and counted, which the invariant
         * would catch if both had taken effect.
         */
        CompletableFuture<Void> resumeDeliveries(PlayerLootState state) {
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            for (String openingId : List.copyOf(state.openDeliveries)) {
                chain = chain.thenCompose(ignored -> storage.loadOpening(openingId).thenCompose(opening -> {
                    if (opening != null && Opening.Fulfillment.ATTEMPTED.equals(opening.fulfillments.get(0).state)
                            && net.effects.containsKey(openingId + "#0")) {
                        // The effect happened before the crash: settle without repeating it.
                        return storage.updateOpening(openingId, stored -> {
                            stored.fulfillments.get(0).state = Opening.Fulfillment.DELIVERED;
                            stored.state = Opening.DELIVERED;
                            return stored;
                        }).thenCompose(saved -> storage.updatePlayer(UUID.fromString(saved.player),
                                current -> current.openDeliveries.remove(openingId) ? current : null))
                                .thenApply(ignored2 -> (Void) null);
                    }
                    return deliver(openingId);
                }));
            }
            return chain;
        }

        private static CompletableFuture<Outcome> done(Outcome outcome) {
            return CompletableFuture.completedFuture(outcome);
        }
    }

    /**
     * The shared SQL store: every document operation is atomic across servers
     * ({@code updateShared} holds one lock for the read, the updater and the
     * write, like the provider's {@code SELECT ... FOR UPDATE} transaction). It
     * can be down, or apply a write and then lose its acknowledgement.
     */
    private static final class SharedStore implements StorageService {
        private final Map<String, JsonElement> documents = new ConcurrentHashMap<>();
        private final Object lock = new Object();
        volatile boolean down;
        private final Set<String> loseAck = ConcurrentHashMap.newKeySet();

        void loseNextAck(String namespace) {
            loseAck.add(namespace);
        }

        Map<String, String> snapshot() {
            Map<String, String> copy = new java.util.TreeMap<>();
            documents.forEach((id, value) -> copy.put(id, value.toString()));
            return copy;
        }

        @Override
        public String activeProvider() {
            return "shared-memory";
        }

        @Override
        public boolean sharedAcrossServers() {
            return true;
        }

        private <T> CompletableFuture<T> run(java.util.function.Supplier<T> operation) {
            return CompletableFuture.supplyAsync(() -> {
                if (down) {
                    throw new IllegalStateException("SQL is down");
                }
                synchronized (lock) {
                    return operation.get();
                }
            }, IO);
        }

        @Override
        public CompletableFuture<JsonElement> load(String namespace, String key) {
            return run(() -> copy(documents.get(namespace + "/" + key)));
        }

        @Override
        public CompletableFuture<Void> save(String namespace, String key, JsonElement value) {
            JsonElement stored = value.deepCopy();
            return run(() -> {
                documents.put(namespace + "/" + key, stored);
                return null;
            });
        }

        @Override
        public CompletableFuture<Boolean> delete(String namespace, String key) {
            return run(() -> documents.remove(namespace + "/" + key) != null);
        }

        @Override
        public CompletableFuture<Boolean> exists(String namespace, String key) {
            return run(() -> documents.containsKey(namespace + "/" + key));
        }

        @Override
        public CompletableFuture<List<String>> listKeys(String namespace) {
            String prefix = namespace + "/";
            return run(() -> documents.keySet().stream().filter(id -> id.startsWith(prefix))
                    .map(id -> id.substring(prefix.length())).sorted().toList());
        }

        @Override
        public CompletableFuture<JsonElement> update(String namespace, String key, UnaryOperator<JsonElement> updater) {
            return updateShared(namespace, key, updater);
        }

        @Override
        public CompletableFuture<JsonElement> updateShared(String namespace, String key,
                UnaryOperator<JsonElement> updater) {
            String id = namespace + "/" + key;
            boolean lose = loseAck.remove(namespace);
            return run(() -> {
                JsonElement current = copy(documents.get(id));
                JsonElement next = updater.apply(current); // an exception here writes nothing (rollback)
                if (next != null) {
                    documents.put(id, next.deepCopy());
                }
                if (lose) {
                    throw new IllegalStateException("the write landed but its acknowledgement was lost");
                }
                return next == null ? current : next;
            });
        }

        private static JsonElement copy(JsonElement element) {
            return element == null ? null : element.deepCopy();
        }
    }

    /** The Redis lock: SET NX with a TTL on a manual clock; can be down. */
    private static final class FakeLock implements NetworkAuthority.Lock {
        private record Held(String payload, long expiresAt) {
        }

        private final Map<String, Held> locks = new ConcurrentHashMap<>();
        private final AtomicLong clock = new AtomicLong();
        volatile boolean down;

        /** Every lock's TTL runs out (a dead or partitioned holder stops renewing). */
        void expireAll() {
            clock.addAndGet(TimeUnit.HOURS.toMillis(1));
        }

        @Override
        public boolean available() {
            return !down;
        }

        @Override
        public synchronized boolean acquire(String key, String payload, long ttlSeconds) {
            if (down) {
                return false;
            }
            Held held = locks.get(key);
            if (held != null && held.expiresAt() > clock.get()) {
                return false;
            }
            locks.put(key, new Held(payload, clock.get() + TimeUnit.SECONDS.toMillis(ttlSeconds)));
            return true;
        }

        @Override
        public synchronized boolean renew(String key, String payload, long ttlSeconds) {
            Held held = locks.get(key);
            if (down || held == null || !held.payload().equals(payload) || held.expiresAt() <= clock.get()) {
                return false;
            }
            locks.put(key, new Held(payload, clock.get() + TimeUnit.SECONDS.toMillis(ttlSeconds)));
            return true;
        }

        @Override
        public synchronized boolean release(String key, String payload) {
            Held held = locks.get(key);
            if (down || held == null || !held.payload().equals(payload)) {
                return false;
            }
            locks.remove(key);
            return true;
        }
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new AssertionError("a step failed: " + e.getCause(), e.getCause());
        } catch (TimeoutException e) {
            throw new AssertionError("a step did not finish within " + TIMEOUT_SECONDS + "s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
