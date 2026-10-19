package org.hyzionstudios.mysticessentials.modules.lootbox;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import org.hyzionstudios.mysticessentials.api.service.StorageService;
import org.hyzionstudios.mysticessentials.modules.lootbox.config.LootBoxConfig;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.KeySerial;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxRevision;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.Opening;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.PlayerLootState;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.OddsEngine;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.OddsTable;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.SecureDraw;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.DeliveryRules;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.OpeningRules;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.PlayerLanes;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.RecoveryPolicy;
import org.hyzionstudios.mysticessentials.modules.lootbox.storage.LootBoxStorage;

import com.google.gson.JsonElement;

/**
 * Engine-free checks of the opening saga (implementation plan §4.3 and §6,
 * "Saga"): a confirmation whose odds were republished is refused before anything
 * is spent, a confirmation sent twice yields one opening, a multi-use key spends
 * one use per opening and is REDEEMED only at zero, a virtual balance never goes
 * negative even when openings race, recovery after a crash between the commit
 * and the delivery follows {@link RecoveryPolicy} and delivers exactly once,
 * recovery leaves a delivery this server is still running to finish, and two
 * staff regrants racing on one reviewed opening hand the reward out once.
 *
 * <p>{@code OpeningService} needs a running server (PlayerRef, world threads), so
 * {@link Saga} drives the rules it applies ({@link OpeningRules},
 * {@link DeliveryRules}, {@link RecoveryPolicy}) in the order
 * {@code OpeningService} and {@code RewardService} apply them, through the real
 * {@link PlayerLanes} and {@link LootBoxStorage} over an in-memory store that
 * orders each document's operations the way {@code StorageServiceImpl} does. A
 * simulated crash stops a saga at a chosen step; a new {@link Saga} over the same
 * {@link World} is the restarted server.</p>
 */
public final class LootBoxSagaTest {

    private static final String POOL_ID = "avalon_supply";
    private static final String KEY = "mystic:royal";
    private static final String PROFILE = "FREE_GAMEPLAY_ONLY";
    private static final long TIMEOUT_SECONDS = 10;

    /** Storage operations run here, so documents really are read and written from several threads. */
    private static final ExecutorService IO = Executors.newFixedThreadPool(4, task -> {
        Thread thread = new Thread(task, "saga-io");
        thread.setDaemon(true);
        return thread;
    });

    private LootBoxSagaTest() {
    }

    public static void main(String[] args) {
        try {
            staleSnapshotIsRefusedAndSpendsNothing();
            repeatedConfirmationYieldsOneOpening();
            multiUseKeySpendsOneUsePerOpening();
            virtualBalanceNeverGoesNegative();
            onlyReplaySafeActionsRunAgain();
            recoveryAfterACrashDeliversOnce();
            recoveryLeavesARunningDeliveryAlone();
            racingRegrantsGiveTheRewardOnce();
        } finally {
            IO.shutdownNow();
        }
        System.out.println("LootBoxSagaTest: all checks passed.");
    }

    // ----- Stale snapshot -----------------------------------------------------

    private static void staleSnapshotIsRefusedAndSpendsNothing() {
        World world = new World(revision(3, 4500, 3000, 2500));
        Saga saga = new Saga(world);
        UUID player = UUID.randomUUID();
        grantVirtual(world, player, 2);
        String serial = issueSerial(world, player, 1);

        Preview preview = saga.preview(player);
        world.publish(revision(4, 5000, 3000, 2000));
        Map<String, String> before = world.memory.snapshot();
        Result stale = await(saga.open(player, preview));
        require(stale.outcome() == Outcome.STALE, "a confirmation of republished odds was " + stale.outcome());
        require(world.memory.snapshot().equals(before), "a stale confirmation wrote to storage");
        require(world.held(player).contains(serial), "a stale confirmation took the key item");

        // Nothing else stood in the way: confirming the new odds opens.
        Result fresh = await(saga.open(player, saga.preview(player)));
        require(fresh.outcome() == Outcome.COMMITTED && fresh.opening().revision == 4,
                "a confirmation of the current odds was " + fresh.outcome());
        await(fresh.delivery());
        require(balance(world, player) == 1, "one opening left a balance of " + balance(world, player));

        OpeningRules.Confirm confirm = new OpeningRules.Confirm(POOL_ID, "hash", "req", null, 1_000L);
        long expiry = 1_000L + OpeningRules.CONFIRM_TTL_MS;
        require(OpeningRules.confirmCurrent(confirm, POOL_ID, "req", expiry), "a confirm was refused within its TTL");
        require(!OpeningRules.confirmCurrent(confirm, POOL_ID, "req", expiry + 1), "an expired confirm passed");
        require(!OpeningRules.confirmCurrent(confirm, POOL_ID, "other", 1_000L), "another request id passed");
        require(!OpeningRules.confirmCurrent(confirm, "other_pool", "req", 1_000L), "a confirm for another pool passed");
        require(!OpeningRules.confirmCurrent(confirm, POOL_ID, null, 1_000L), "a confirm without a request id passed");
        require(!OpeningRules.confirmCurrent(null, POOL_ID, "req", 1_000L), "a confirm without a preview passed");
        require(!OpeningRules.snapshotCurrent(null, OddsEngine.table(world.active, PROFILE)),
                "a confirm without a snapshot hash passed");
    }

    // ----- Duplicate confirmations --------------------------------------------

    private static void repeatedConfirmationYieldsOneOpening() {
        World world = new World(revision(3, 4500, 3000, 2500));
        Saga saga = new Saga(world);
        UUID player = UUID.randomUUID();
        grantVirtual(world, player, 3);

        // The same confirmation twice, the second queued before the first finished.
        Preview preview = saga.preview(player);
        CompletableFuture<Result> first = saga.open(player, preview);
        CompletableFuture<Result> again = saga.open(player, preview);
        Result committed = await(first);
        Result duplicate = await(again);
        require(committed.outcome() == Outcome.COMMITTED, "the first confirmation was " + committed.outcome());
        require(duplicate.outcome() == Outcome.DUPLICATE, "the repeated confirmation was " + duplicate.outcome());
        String openingId = committed.opening().openingId;
        require(duplicate.opening() != null && openingId.equals(duplicate.opening().openingId),
                "the repeated confirmation did not return the first opening");
        await(committed.delivery());
        Result resent = await(saga.open(player, preview));
        require(resent.outcome() == Outcome.DUPLICATE && openingId.equals(resent.opening().openingId),
                "a confirmation resent later was " + resent.outcome());

        PlayerLootState state = stateOf(world, player);
        require(balance(world, player) == 2, "one opening left a balance of " + balance(world, player));
        require(state.commits.size() == 1 && state.receipts.size() == 1 && state.requests.size() == 1,
                "commits/receipts/requests: " + state.commits.size() + "/" + state.receipts.size() + "/"
                        + state.requests.size());
        require(world.memory.count("lootbox_open") == 1,
                world.memory.count("lootbox_open") + " opening documents for one confirmation");
        require(world.effects(openingId + "#0") == 1 && world.totalEffects() == 1,
                "rewards handed out: " + world.totalEffects());

        // The commit write charges once even when it runs twice, for either kind of key.
        PlayerLootState document = new PlayerLootState();
        document.virtualKeys.put(KEY, 2);
        Opening opening = opening("op1", Opening.KEY_VIRTUAL);
        require(OpeningRules.spendVirtual(document, KEY, opening, "Pool", 50, 1L) == OpeningRules.Debit.SPENT,
                "the first debit was refused");
        require(OpeningRules.spendVirtual(document, KEY, opening, "Pool", 50, 2L)
                == OpeningRules.Debit.ALREADY_COMMITTED, "a repeated debit was not recognised");
        require(document.virtualKeys.get(KEY) == 1 && document.commits.size() == 1
                && document.openDeliveries.equals(List.of("op1")), "a repeated debit charged or recorded twice");
        KeySerial serial = serial(2);
        require(OpeningRules.reserve(serial, KEY, "op1", 1L) == null, "an issued serial could not be reserved");
        require(OpeningRules.redeem(serial, "op1", "iron_sword"), "the reserved serial was not redeemed");
        require(!OpeningRules.redeem(serial, "op1", "iron_sword") && serial.usesLeft == 1,
                "a repeated serial commit spent another use");

        // Request ids are remembered for the dedupe window, then forgotten; the list stays bounded.
        long window = new LootBoxConfig().opening.requestDedupeMinutes;
        long later = TimeUnit.MINUTES.toMillis(window) + 1;
        PlayerLootState requests = new PlayerLootState();
        OpeningRules.recordPrepared(requests, prepared("old", "req-old", 0L), window);
        OpeningRules.recordPrepared(requests, prepared("new", "req-new", later), window);
        require("new".equals(OpeningRules.earlierOpening(requests, "req-new")), "a fresh request id was forgotten");
        require(OpeningRules.earlierOpening(requests, "req-old") == null, "a request id outlived the dedupe window");
        require(OpeningRules.earlierOpening(requests, null) == null, "a missing request id matched");
        int extra = PlayerLootState.MAX_REQUESTS + 5;
        for (int i = 0; i < extra; i++) {
            OpeningRules.recordPrepared(requests, prepared("o" + i, "r" + i, later + i), window);
        }
        require(requests.requests.size() == PlayerLootState.MAX_REQUESTS,
                requests.requests.size() + " request records kept");
        require(OpeningRules.earlierOpening(requests, "r0") == null
                && OpeningRules.earlierOpening(requests, "r" + (extra - 1)) != null,
                "the bound dropped other than the oldest request records");
    }

    // ----- Multi-use physical keys --------------------------------------------

    private static void multiUseKeySpendsOneUsePerOpening() {
        World world = new World(revision(3, 4500, 3000, 2500));
        Saga saga = new Saga(world);
        UUID player = UUID.randomUUID();
        String serial = issueSerial(world, player, 3);

        List<String> openings = new ArrayList<>();
        for (int use = 1; use <= 3; use++) {
            Result result = await(saga.open(player, saga.preview(player)));
            require(result.outcome() == Outcome.COMMITTED, "use " + use + " was " + result.outcome());
            await(result.delivery());
            String openingId = result.opening().openingId;
            openings.add(openingId);
            KeySerial stored = serialOf(world, serial);
            require(stored.usesLeft == 3 - use, "use " + use + " left " + stored.usesLeft + " uses");
            require(stored.state.equals(use < 3 ? KeySerial.ISSUED : KeySerial.REDEEMED),
                    "use " + use + " left the serial " + stored.state);
            require(openingId.equals(stored.redeemedBy) && stored.reservedBy == null,
                    "use " + use + " did not record its opening on the serial");
            require(world.held(player).contains(serial) == use < 3,
                    "after use " + use + " the key item is " + (use < 3 ? "gone" : "still held"));
        }
        require(await(saga.open(player, saga.preview(player))).outcome() == Outcome.NO_KEY,
                "a used-up key opened again");

        // A copy of the spent item carries the same serial and is dead.
        world.held(player).add(serial);
        Result copied = await(saga.open(player, saga.preview(player)));
        require(copied.outcome() == Outcome.NO_KEY, "a copied key was " + copied.outcome());
        KeySerial spent = serialOf(world, serial);
        require(KeySerial.REDEEMED.equals(spent.state) && spent.usesLeft == 0 && openings.get(2).equals(spent.redeemedBy),
                "a copied key changed its serial");
        PlayerLootState state = stateOf(world, player);
        require(state.commits.size() == 3 && state.activeOpening == null
                && state.commits.stream().allMatch(c -> Opening.KEY_PHYSICAL.equals(c.keyKind)),
                "three uses recorded " + state.commits.size() + " commits");
        require(world.totalEffects() == 3, "three uses handed out " + world.totalEffects() + " rewards");

        // Reservation rules: one holder at a time, released untouched, redeemed only by its holder.
        KeySerial two = serial(2);
        require(OpeningRules.lastUse(serial(1)) && !OpeningRules.lastUse(two), "last use misjudged");
        require(OpeningRules.reserve(two, KEY, "op", 5L) == null && KeySerial.RESERVED.equals(two.state)
                && "op".equals(two.reservedBy), "an issued serial was not reserved");
        require(OpeningRules.reserve(two, KEY, "other", 5L) != null, "a reserved serial was reserved again");
        require(!OpeningRules.redeem(two, "other", "x") && !OpeningRules.release(two, "other"),
                "another opening spent or released the reservation");
        require(OpeningRules.release(two, "op") && KeySerial.ISSUED.equals(two.state) && two.usesLeft == 2
                && two.reservedBy == null, "a released reservation cost a use");
        require(!OpeningRules.redeem(two, "op", "x"), "a serial was redeemed without a reservation");
        require("wrong key".equals(OpeningRules.reserve(two, "mystic:other", "op", 5L)), "a key opened another pool");
        two.expiresAt = 5L;
        require("expired".equals(OpeningRules.reserve(two, KEY, "op", 5L)) && KeySerial.ISSUED.equals(two.state),
                "an expired key was reserved");
    }

    // ----- Virtual balances ---------------------------------------------------

    private static void virtualBalanceNeverGoesNegative() {
        // Two openings on one player's lane with a single key between them. Both are
        // past the confirm gate, so only the balance stands between them and a second key.
        World world = new World(revision(3, 4500, 3000, 2500));
        Saga saga = new Saga(world);
        UUID player = UUID.randomUUID();
        grantVirtual(world, player, 1);
        LootBoxRevision revision = world.active;
        OddsTable table = OddsEngine.table(revision, PROFILE);
        List<CompletableFuture<Result>> lane = new ArrayList<>();
        for (String requestId : List.of("req-a", "req-b")) {
            lane.add(saga.lanes.run(player, () -> world.storage.loadPlayer(player)
                    .thenCompose(state -> saga.chooseKeyAndOpen(player, state, revision, table, requestId))));
        }
        List<Outcome> outcomes = new ArrayList<>();
        for (CompletableFuture<Result> opening : lane) {
            Result result = await(opening);
            outcomes.add(result.outcome());
            await(result.delivery());
        }
        Collections.sort(outcomes);
        require(outcomes.equals(List.of(Outcome.COMMITTED, Outcome.NO_KEY)), "two openings for one key: " + outcomes);
        PlayerLootState state = stateOf(world, player);
        require(!state.virtualKeys.containsKey(KEY) && state.commits.size() == 1 && state.activeOpening == null,
                "one key: balance " + state.virtualKeys + ", " + state.commits.size() + " commits");

        // Without the lane: sixteen openings that all read a balance of 3 race their
        // commit writes on the one player document.
        World raced = new World(revision(3, 4500, 3000, 2500));
        Saga racing = new Saga(raced);
        UUID racer = UUID.randomUUID();
        grantVirtual(raced, racer, 3);
        PlayerLootState read = stateOf(raced, racer);
        List<CompletableFuture<Result>> attempts = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            attempts.add(racing.chooseKeyAndOpen(racer, read, raced.active, table, "race-" + i));
        }
        int spent = 0;
        for (CompletableFuture<Result> attempt : attempts) {
            Result result = await(attempt);
            await(result.delivery());
            spent += result.outcome() == Outcome.COMMITTED ? 1 : 0;
            require(result.outcome() == Outcome.COMMITTED || result.outcome() == Outcome.NO_KEY,
                    "a racing opening was " + result.outcome());
        }
        require(spent == 3, spent + " openings committed on a balance of 3");
        require(racing.balancesAfterCommit.size() == 16 && racing.balancesAfterCommit.stream().allMatch(b -> b >= 0),
                "balances seen after the commit writes: " + racing.balancesAfterCommit);
        PlayerLootState after = stateOf(raced, racer);
        require(!after.virtualKeys.containsKey(KEY) && after.commits.size() == 3,
                "after the race: balance " + after.virtualKeys + ", " + after.commits.size() + " commits");

        // A broken stored balance counts as none and is never charged.
        PlayerLootState broken = new PlayerLootState();
        broken.virtualKeys.put(KEY, -1);
        broken.activeOpening = "op";
        require(OpeningRules.firstVirtualKey(List.of(KEY), broken) == null, "a negative balance offered a key");
        require(OpeningRules.spendVirtual(broken, KEY, opening("op", Opening.KEY_VIRTUAL), "Pool", 50, 1L)
                == OpeningRules.Debit.NO_KEY, "a negative balance was charged");
        require(broken.virtualKeys.get(KEY) == -1 && broken.commits.isEmpty() && broken.activeOpening == null,
                "a refused debit changed more than the active opening");
    }

    // ----- Delivery rules -----------------------------------------------------

    private static void onlyReplaySafeActionsRunAgain() {
        boolean item = DeliveryRules.replaySafe(reward("ITEM", false));
        boolean command = DeliveryRules.replaySafe(reward("COMMAND", false));
        boolean idempotent = DeliveryRules.replaySafe(reward("command", true));
        boolean permission = DeliveryRules.replaySafe(reward("permission", false));
        require(!item && !command && idempotent && permission, "replay-safe types misjudged");
        require(DeliveryRules.step(action(Opening.Fulfillment.PLANNED), item, false) == DeliveryRules.Step.RUN,
                "a planned action did not run");
        for (String settled : List.of(Opening.Fulfillment.DELIVERED, Opening.Fulfillment.PENDING_INBOX,
                Opening.Fulfillment.DISMISSED)) {
            require(DeliveryRules.step(action(settled), true, true) == DeliveryRules.Step.SETTLED,
                    "a " + settled + " action would run again");
        }
        String attempted = Opening.Fulfillment.ATTEMPTED;
        require(DeliveryRules.step(action(attempted), item, false) == DeliveryRules.Step.REVIEW,
                "an interrupted item would be handed out again");
        require(DeliveryRules.step(action(attempted), command, false) == DeliveryRules.Step.REVIEW,
                "an interrupted command would run again");
        require(DeliveryRules.step(action(attempted), idempotent, false) == DeliveryRules.Step.RUN
                && DeliveryRules.step(action(attempted), permission, false) == DeliveryRules.Step.RUN,
                "an interrupted replay-safe action was not retried");
        require(DeliveryRules.step(action(Opening.Fulfillment.REVIEW), item, false) == DeliveryRules.Step.REVIEW
                && DeliveryRules.step(action(Opening.Fulfillment.REVIEW), item, true) == DeliveryRules.Step.RUN,
                "only a forced regrant may re-run a reviewed action");
        long now = 1_000_000L;
        require(!DeliveryRules.resumes(null, null, now) && !DeliveryRules.resumes(inState(Opening.DELIVERED), null, now)
                && !DeliveryRules.resumes(inState(Opening.ABORTED), null, now)
                && DeliveryRules.resumes(inState(Opening.PREPARED), null, now)
                && DeliveryRules.resumes(inState(Opening.DELIVERING), null, now), "resumed the wrong openings");
        long stalled = now - DeliveryRules.STALLED_MS;
        require(!DeliveryRules.resumes(inState(Opening.DELIVERING), now, now)
                && !DeliveryRules.resumes(inState(Opening.DELIVERING), stalled + 1, now)
                && DeliveryRules.resumes(inState(Opening.DELIVERING), stalled, now)
                && !DeliveryRules.resumes(inState(Opening.DELIVERED), stalled, now),
                "a delivery running on this server was resumed, or a stalled one was not");
        require(DeliveryRules.reconcileProblem(inState(Opening.REVIEW), null) == null,
                "staff may not reconcile an opening under review");
        require("already being delivered".equals(DeliveryRules.reconcileProblem(inState(Opening.REVIEW), now))
                && "already being delivered".equals(DeliveryRules.reconcileProblem(inState(Opening.REVIEW), stalled)),
                "staff may reconcile an opening this server is delivering");
        require(DeliveryRules.reconcileProblem(inState(Opening.DELIVERED), null) != null
                && DeliveryRules.reconcileProblem(inState(Opening.DELIVERING), null) != null
                && "unknown opening".equals(DeliveryRules.reconcileProblem(null, null)),
                "staff may reconcile an opening that is not under review");

        PlayerLootState state = new PlayerLootState();
        state.openDeliveries.addAll(List.of("a", "b"));
        state.receipts.add(receipt("a"));
        state.receipts.add(receipt("b"));
        DeliveryRules.settle(state, "a", "Delivered");
        require(state.openDeliveries.equals(List.of("b")) && "Delivered".equals(state.receipts.get(0).status)
                && "Delivering".equals(state.receipts.get(1).status), "settling touched the wrong opening");
    }

    // ----- Recovery -----------------------------------------------------------

    private static void recoveryAfterACrashDeliversOnce() {
        physicalKeyCrashAfterCommit(false);
        physicalKeyCrashAfterCommit(true);
        virtualKeyCrashAfterCommit();
        crashBeforeTheKeyLeftKeepsIt();
        crashAfterTheKeyLeftCommits();
        crashMidDeliveryGoesToReview();
    }

    /** The serial commit is written and nothing after it: no player-document commit, the opening still RESERVED. */
    private static void physicalKeyCrashAfterCommit(boolean keyCameBack) {
        World world = new World(revision(3, 4500, 3000, 2500));
        UUID player = UUID.randomUUID();
        String serial = issueSerial(world, player, 1);
        Saga crashing = new Saga(world);
        crashing.crash = Crash.AFTER_COMMIT;
        require(await(crashing.open(player, crashing.preview(player))).outcome() == Outcome.CRASHED, "no crash");
        PlayerLootState left = stateOf(world, player);
        String openingId = left.activeOpening;
        require(openingId != null && left.commits.isEmpty() && left.openDeliveries.isEmpty(),
                "the crash left more than the serial commit");
        require(KeySerial.REDEEMED.equals(serialOf(world, serial).state)
                && Opening.RESERVED.equals(openingOf(world, openingId).state), "the crash point was not reached");
        if (keyCameBack) {
            // The engine lost the inventory write in the crash.
            world.held(player).add(serial);
        }

        Saga restarted = new Saga(world);
        await(restarted.recover(player));
        RecoveryPolicy.Action expected = keyCameBack ? RecoveryPolicy.Action.ENSURE_COMMITTED_CLEAN_UP
                : RecoveryPolicy.Action.ENSURE_COMMITTED;
        require(restarted.decisions.equals(List.of(expected)), "recovery decided " + restarted.decisions);
        require(!world.held(player).contains(serial), "the dead key stack stayed in the inventory");
        requireDeliveredOnce(world, player, openingId);

        // The next pass and the next join find nothing left to do.
        await(restarted.recover(player));
        Saga rejoined = new Saga(world);
        await(rejoined.recover(player));
        require(restarted.decisions.size() == 1 && rejoined.decisions.isEmpty(), "a finished opening was recovered again");
        requireDeliveredOnce(world, player, openingId);
    }

    /** The player document is the commit: recovery only has to finish the delivery. */
    private static void virtualKeyCrashAfterCommit() {
        World world = new World(revision(3, 4500, 3000, 2500));
        UUID player = UUID.randomUUID();
        grantVirtual(world, player, 1);
        Saga crashing = new Saga(world);
        crashing.crash = Crash.AFTER_COMMIT;
        require(await(crashing.open(player, crashing.preview(player))).outcome() == Outcome.CRASHED, "no crash");
        PlayerLootState left = stateOf(world, player);
        require(left.commits.size() == 1 && left.activeOpening == null && left.openDeliveries.size() == 1
                && !left.virtualKeys.containsKey(KEY), "the virtual commit was not one complete write");
        String openingId = left.commits.get(0).openingId;
        require(Opening.PREPARED.equals(openingOf(world, openingId).state), "the crash point was not reached");

        Saga restarted = new Saga(world);
        await(restarted.recover(player));
        require(restarted.decisions.isEmpty(), "a committed virtual opening went through the physical-key table");
        requireDeliveredOnce(world, player, openingId);
        await(restarted.recover(player));
        await(new Saga(world).recover(player));
        requireDeliveredOnce(world, player, openingId);
        require(!stateOf(world, player).virtualKeys.containsKey(KEY), "recovery changed the balance");
    }

    /** RESERVED with the item still held: the player keeps the key and gets nothing. */
    private static void crashBeforeTheKeyLeftKeepsIt() {
        World world = new World(revision(3, 4500, 3000, 2500));
        UUID player = UUID.randomUUID();
        String serial = issueSerial(world, player, 2);
        Saga crashing = new Saga(world);
        crashing.crash = Crash.AFTER_RESERVE;
        require(await(crashing.open(player, crashing.preview(player))).outcome() == Outcome.CRASHED, "no crash");
        String openingId = stateOf(world, player).activeOpening;
        require(KeySerial.RESERVED.equals(serialOf(world, serial).state), "the crash point was not reached");

        Saga restarted = new Saga(world);
        await(restarted.recover(player));
        require(restarted.decisions.equals(List.of(RecoveryPolicy.Action.ABORT_RELEASE)),
                "recovery decided " + restarted.decisions);
        KeySerial released = serialOf(world, serial);
        require(KeySerial.ISSUED.equals(released.state) && released.usesLeft == 2 && released.reservedBy == null
                && released.redeemedBy == null, "the released key lost a use or kept its reservation");
        require(world.held(player).contains(serial), "the kept key left the inventory");
        require(Opening.ABORTED.equals(openingOf(world, openingId).state), "the opening was not aborted");
        PlayerLootState state = stateOf(world, player);
        require(state.commits.isEmpty() && state.activeOpening == null && state.openDeliveries.isEmpty()
                && world.totalEffects() == 0, "an aborted opening was committed or delivered");

        Result next = await(restarted.open(player, restarted.preview(player)));
        require(next.outcome() == Outcome.COMMITTED, "the kept key did not open: " + next.outcome());
        await(next.delivery());
        require(serialOf(world, serial).usesLeft == 1, "the kept key did not spend exactly one use");
    }

    /** RESERVED with the item gone: the recorded outcome commits and is delivered once. */
    private static void crashAfterTheKeyLeftCommits() {
        World world = new World(revision(3, 4500, 3000, 2500));
        UUID player = UUID.randomUUID();
        String serial = issueSerial(world, player, 1);
        Saga crashing = new Saga(world);
        crashing.crash = Crash.AFTER_TAKE;
        require(await(crashing.open(player, crashing.preview(player))).outcome() == Outcome.CRASHED, "no crash");
        String openingId = stateOf(world, player).activeOpening;
        require(KeySerial.RESERVED.equals(serialOf(world, serial).state) && !world.held(player).contains(serial),
                "the crash point was not reached");

        Saga restarted = new Saga(world);
        await(restarted.recover(player));
        require(restarted.decisions.equals(List.of(RecoveryPolicy.Action.COMMIT)),
                "recovery decided " + restarted.decisions);
        KeySerial redeemed = serialOf(world, serial);
        require(KeySerial.REDEEMED.equals(redeemed.state) && openingId.equals(redeemed.redeemedBy),
                "recovery did not commit the serial");
        requireDeliveredOnce(world, player, openingId);
        await(restarted.recover(player));
        await(new Saga(world).recover(player));
        requireDeliveredOnce(world, player, openingId);
    }

    /** The reward took effect but was never recorded: it is not handed out again, staff review it. */
    private static void crashMidDeliveryGoesToReview() {
        World world = new World(revision(3, 4500, 3000, 2500));
        UUID player = UUID.randomUUID();
        grantVirtual(world, player, 1);
        Saga crashing = new Saga(world);
        crashing.crash = Crash.DURING_DELIVERY;
        Result result = await(crashing.open(player, crashing.preview(player)));
        require(result.outcome() == Outcome.COMMITTED, "the opening was " + result.outcome());
        await(result.delivery());
        String openingId = result.opening().openingId;
        require(Opening.Fulfillment.ATTEMPTED.equals(openingOf(world, openingId).fulfillments.get(0).state)
                && stateOf(world, player).openDeliveries.contains(openingId), "the crash point was not reached");

        Saga restarted = new Saga(world);
        await(restarted.recover(player));
        await(restarted.recover(player));
        await(new Saga(world).recover(player));
        require(world.effects(openingId + "#0") == 1, "an interrupted item reward was handed out "
                + world.effects(openingId + "#0") + " times");
        Opening reviewed = openingOf(world, openingId);
        require(Opening.REVIEW.equals(reviewed.state)
                && Opening.Fulfillment.REVIEW.equals(reviewed.fulfillments.get(0).state),
                "the interrupted delivery is " + reviewed.state + ", not in review");
        PlayerLootState state = stateOf(world, player);
        require(state.openDeliveries.isEmpty() && "Under review".equals(state.receipts.get(0).status),
                "the receipt does not show the review");
    }

    // ----- Recovery during a delivery -----------------------------------------

    /**
     * The lane lets go once the OpenResult is built, so the 5-minute pass or a
     * rejoin can come while the delivery sits between its ATTEMPTED mark and
     * settling (the world-thread hop of a give). Recovery leaves that delivery to
     * finish: an ITEM is not sent to REVIEW, and a replay-safe reward does not run
     * a second time next to it.
     */
    private static void recoveryLeavesARunningDeliveryAlone() {
        for (String type : List.of("ITEM", "PERMISSION")) {
            World world = new World(rewardType(revision(3, 4500, 3000, 2500), type));
            UUID player = UUID.randomUUID();
            grantVirtual(world, player, 1);
            Saga saga = new Saga(world);
            Pause pause = new Pause();
            saga.pause.set(pause);
            Result result = await(saga.open(player, saga.preview(player)));
            require(result.outcome() == Outcome.COMMITTED, "the opening was " + result.outcome());
            await(pause.reached());
            String openingId = result.opening().openingId;
            require(Opening.Fulfillment.ATTEMPTED.equals(openingOf(world, openingId).fulfillments.get(0).state)
                    && stateOf(world, player).openDeliveries.contains(openingId), "the delivery was not caught in flight");

            // The 5-minute pass and a rejoin, on the server that is still delivering.
            await(saga.recover(player));
            await(saga.recover(player));
            require(saga.reviewed.isEmpty(), "recovery sent an in-flight " + type + " reward to REVIEW");
            require(world.effects(openingId + "#0") == 0, "recovery ran an in-flight " + type + " reward itself");
            pause.resume().complete(null);
            await(result.delivery());
            requireDeliveredOnce(world, player, openingId);
            require(saga.reviewed.isEmpty(), "a delivered " + type + " reward went through REVIEW");
        }

        // A delivery that never finishes (a world task the platform dropped) is taken
        // over once it has run past the stall bound. Whether the item was given is
        // unknown, so it goes to REVIEW instead of being handed out again.
        World world = new World(revision(3, 4500, 3000, 2500));
        UUID player = UUID.randomUUID();
        grantVirtual(world, player, 1);
        Saga saga = new Saga(world);
        Pause dropped = new Pause();
        saga.pause.set(dropped);
        Result result = await(saga.open(player, saga.preview(player)));
        await(dropped.reached());
        String openingId = result.opening().openingId;
        await(saga.recover(player));
        require(saga.reviewed.isEmpty(), "a delivery within the stall bound was taken over");
        saga.delivering.computeIfPresent(openingId, (id, since) -> since - DeliveryRules.STALLED_MS);
        await(saga.recover(player));
        require(saga.reviewed.equals(List.of(openingId + "#0")) && world.effects(openingId + "#0") == 0,
                "a stalled ITEM delivery was " + (saga.reviewed.isEmpty() ? "left alone" : "handed out again"));
        PlayerLootState state = stateOf(world, player);
        require(Opening.REVIEW.equals(openingOf(world, openingId).state) && state.openDeliveries.isEmpty()
                && "Under review".equals(state.receipts.get(0).status), "the stalled delivery is not in review");
    }

    // ----- Staff regrants -----------------------------------------------------

    /**
     * Two staff members run {@code /lootbox reconcile <id> regrant} on one REVIEW
     * opening at the same time, and both commands read REVIEW before either
     * delivery writes its first mark. The decision runs in the player's lane, so
     * they take turns: the first starts the delivery, the second finds it running
     * (or already settled) and is refused instead of handing the reward out again.
     */
    private static void racingRegrantsGiveTheRewardOnce() {
        World world = new World(revision(3, 4500, 3000, 2500));
        UUID player = UUID.randomUUID();
        grantVirtual(world, player, 1);
        Saga crashing = new Saga(world);
        crashing.crash = Crash.DURING_DELIVERY;
        Result result = await(crashing.open(player, crashing.preview(player)));
        await(result.delivery());
        String openingId = result.opening().openingId;
        Saga restarted = new Saga(world);
        await(restarted.recover(player));
        require(Opening.REVIEW.equals(openingOf(world, openingId).state), "the opening did not reach REVIEW");
        // Staff judged the interrupted give lost: one regrant hands it out again.
        int before = world.effects(openingId + "#0");

        // Both commands' loads of the opening run before either delivery's first mark.
        CompletableFuture<Void> together = world.memory.hold("lootbox_open", openingId);
        CompletableFuture<String> first = restarted.regrant(openingId);
        CompletableFuture<String> second = restarted.regrant(openingId);
        together.complete(null);
        List<String> replies = List.of(await(first), await(second));
        int given = world.effects(openingId + "#0") - before;
        require(given == 1, "two racing regrants handed the reward out " + given + " times: " + replies);
        require(replies.stream().filter("Delivered"::equals).count() == 1,
                "the racing regrants replied " + replies);
        Opening regranted = openingOf(world, openingId);
        PlayerLootState state = stateOf(world, player);
        require(Opening.DELIVERED.equals(regranted.state)
                && Opening.Fulfillment.DELIVERED.equals(regranted.fulfillments.get(0).state)
                && state.openDeliveries.isEmpty() && "Delivered".equals(state.receipts.get(0).status),
                "the regranted opening is " + regranted.state);
        String later = await(restarted.regrant(openingId));
        require(later.startsWith("not under review") && world.effects(openingId + "#0") - before == 1,
                "a regrant of a delivered opening replied " + later);
    }

    private static void requireDeliveredOnce(World world, UUID player, String openingId) {
        require(world.effects(openingId + "#0") == 1 && world.totalEffects() == 1,
                "the reward took effect " + world.effects(openingId + "#0") + " times");
        require(Opening.DELIVERED.equals(openingOf(world, openingId).state), "the opening is not DELIVERED");
        PlayerLootState state = stateOf(world, player);
        long commits = state.commits.stream().filter(c -> openingId.equals(c.openingId)).count();
        require(commits == 1, commits + " commit records for one opening");
        require(state.activeOpening == null && state.openDeliveries.isEmpty(), "the opening is still open");
        require("Delivered".equals(state.receipts.get(0).status), "the receipt says " + state.receipts.get(0).status);
    }

    // ----- The saga, step for step --------------------------------------------

    /** The OpenResult statuses these steps reach, plus CRASHED for a simulated crash. */
    private enum Outcome {
        COMMITTED, DUPLICATE, STALE, BUSY, NO_KEY, ERROR, CRASHED
    }

    /** Where a simulated crash stops a saga. Every write before it stays. */
    private enum Crash {
        NONE,
        /** The serial is RESERVED; the key item is still in the inventory. */
        AFTER_RESERVE,
        /** The key item left the inventory; the serial is still RESERVED. */
        AFTER_TAKE,
        /** The commit point (serial or player document) was written, nothing after it. */
        AFTER_COMMIT,
        /** The reward took effect but was never recorded as DELIVERED. */
        DURING_DELIVERY
    }

    private record Preview(String hash, String requestId) {
    }

    /** Holds a delivery after its ATTEMPTED mark until {@code resume} completes, as a give's world-thread hop does. */
    private record Pause(CompletableFuture<Void> reached, CompletableFuture<Void> resume) {

        Pause() {
            this(new CompletableFuture<>(), new CompletableFuture<>());
        }
    }

    private record Result(Outcome outcome, Opening opening, CompletableFuture<Void> delivery) {

        static Result of(Outcome outcome) {
            return new Result(outcome, null, CompletableFuture.completedFuture(null));
        }
    }

    /** What outlives a server: documents, the players' key items and the rewards handed out. */
    private static final class World {

        final MemoryStorage memory = new MemoryStorage();
        final LootBoxStorage storage = new LootBoxStorage(() -> memory);
        final Map<Integer, LootBoxRevision> revisions = new ConcurrentHashMap<>();
        /** Serials of the key items each player holds. */
        final Map<UUID, Set<String>> inventory = new ConcurrentHashMap<>();
        /** fulfillmentId → times the reward took effect. */
        final Map<String, AtomicInteger> effects = new ConcurrentHashMap<>();
        volatile LootBoxRevision active;

        World(LootBoxRevision revision) {
            publish(revision);
        }

        void publish(LootBoxRevision revision) {
            revisions.put(revision.revision, revision);
            active = revision;
        }

        Set<String> held(UUID player) {
            return inventory.computeIfAbsent(player, uuid -> ConcurrentHashMap.newKeySet());
        }

        int effects(String fulfillmentId) {
            AtomicInteger count = effects.get(fulfillmentId);
            return count == null ? 0 : count.get();
        }

        int totalEffects() {
            return effects.values().stream().mapToInt(AtomicInteger::get).sum();
        }
    }

    /**
     * One server run. Each step names the {@code OpeningService} or
     * {@code RewardService} method it follows; the engine's part is the
     * {@link World}'s inventory and effect counts. Permission, cooldown, policy
     * and event checks need the engine and are left out.
     */
    private static final class Saga {

        final World world;
        final LootBoxStorage storage;
        final LootBoxConfig config = new LootBoxConfig();
        final PlayerLanes lanes = new PlayerLanes();
        final SecureDraw draw = new SecureDraw();
        final Map<UUID, OpeningRules.Confirm> confirms = new ConcurrentHashMap<>();
        /** RecoveryPolicy's decisions, in order. */
        final List<RecoveryPolicy.Action> decisions = new CopyOnWriteArrayList<>();
        /** The virtual balance stored after every commit write. */
        final List<Integer> balancesAfterCommit = new CopyOnWriteArrayList<>();
        /** Fulfillment ids marked REVIEW, each a {@code RewardDeliveryFailedEvent}. */
        final List<String> reviewed = new CopyOnWriteArrayList<>();
        /** Taken by the next delivery to pass its ATTEMPTED mark. */
        final AtomicReference<Pause> pause = new AtomicReference<>();
        /** {@code RewardService}'s running deliveries: openingId → when it started. */
        final Map<String, Long> delivering = new ConcurrentHashMap<>();
        volatile Crash crash = Crash.NONE;

        Saga(World world) {
            this.world = world;
            this.storage = world.storage;
        }

        /** {@code preview}: hands out the confirm the UI sends back. */
        Preview preview(UUID player) {
            OddsTable table = OddsEngine.table(world.active, PROFILE);
            String requestId = draw.id();
            confirms.put(player, new OpeningRules.Confirm(POOL_ID, table.hash(), requestId, null,
                    System.currentTimeMillis()));
            return new Preview(table.hash(), requestId);
        }

        /** {@code open} → {@code openInLane}. */
        CompletableFuture<Result> open(UUID player, Preview preview) {
            return lanes.run(player, () -> storage.loadPlayer(player).thenCompose(state -> {
                String earlier = OpeningRules.earlierOpening(state, preview.requestId());
                if (earlier != null) {
                    return storage.loadOpening(earlier).thenApply(opening -> new Result(Outcome.DUPLICATE, opening,
                            CompletableFuture.completedFuture(null)));
                }
                if (!OpeningRules.confirmCurrent(confirms.get(player), POOL_ID, preview.requestId(),
                        System.currentTimeMillis())) {
                    return done(Outcome.STALE);
                }
                if (state.activeOpening != null) {
                    return done(Outcome.BUSY);
                }
                LootBoxRevision revision = world.active;
                OddsTable table = OddsEngine.table(revision, PROFILE);
                if (!OpeningRules.snapshotCurrent(preview.hash(), table)) {
                    return done(Outcome.STALE);
                }
                confirms.remove(player);
                return chooseKeyAndOpen(player, state, revision, table, preview.requestId());
            }));
        }

        /** {@code chooseKeyAndOpen} with the default VIRTUAL_FIRST order. */
        CompletableFuture<Result> chooseKeyAndOpen(UUID player, PlayerLootState state, LootBoxRevision revision,
                OddsTable table, String requestId) {
            String virtualKey = OpeningRules.firstVirtualKey(revision.spec.keys, state);
            if (virtualKey != null) {
                return prepare(player, revision, table, requestId)
                        .thenCompose(opening -> commitVirtual(player, opening, revision, virtualKey));
            }
            List<String> serials = List.copyOf(world.held(player));
            if (!serials.isEmpty()) {
                return prepare(player, revision, table, requestId)
                        .thenCompose(opening -> commitPhysical(player, opening, revision, serials));
            }
            return done(Outcome.NO_KEY);
        }

        /** {@code prepare}: draws the outcome and records the opening as PREPARED. */
        CompletableFuture<Opening> prepare(UUID player, LootBoxRevision revision, OddsTable table, String requestId) {
            long roll = draw.roll(table.total());
            OddsTable.Entry entry = table.pick(roll);
            LootBoxDraft.RewardSpec reward = revision.spec.rewards.get(entry.index());
            Opening opening = new Opening();
            opening.openingId = draw.id();
            opening.player = player.toString();
            opening.requestId = requestId;
            opening.lootboxId = revision.lootboxId;
            opening.poolName = revision.spec.displayName;
            opening.revision = revision.revision;
            opening.snapshotHash = table.hash();
            opening.roll = roll;
            opening.totalWeight = table.total();
            opening.outcomeId = reward.id;
            opening.outcomeName = reward.name;
            opening.probability = entry.weight() + "/" + table.total();
            opening.createdAt = System.currentTimeMillis();
            Opening.Fulfillment action = new Opening.Fulfillment();
            action.id = opening.openingId + "#0";
            action.type = reward.type;
            opening.fulfillments.add(action);
            return storage.saveOpening(opening).thenCompose(saved -> storage.updatePlayer(player, state -> {
                OpeningRules.recordPrepared(state, opening, config.opening.requestDedupeMinutes);
                return state;
            })).thenApply(state -> opening);
        }

        /** {@code commitVirtual}: the player-document commit point. */
        CompletableFuture<Result> commitVirtual(UUID player, Opening opening, LootBoxRevision revision, String keyId) {
            opening.keyKind = Opening.KEY_VIRTUAL;
            opening.keyId = keyId;
            AtomicBoolean committed = new AtomicBoolean();
            return storage.updatePlayer(player, state -> {
                OpeningRules.Debit debit = OpeningRules.spendVirtual(state, keyId, opening, revision.spec.displayName,
                        config.opening.historySize, System.currentTimeMillis());
                committed.set(debit != OpeningRules.Debit.NO_KEY);
                return debit == OpeningRules.Debit.ALREADY_COMMITTED ? null : state;
            }).thenCompose(state -> {
                balancesAfterCommit.add(state.virtualKeys.getOrDefault(keyId, 0));
                if (!committed.get()) {
                    return abort(opening, Outcome.NO_KEY);
                }
                return crash == Crash.AFTER_COMMIT ? done(Outcome.CRASHED) : finishCommit(opening, revision);
            });
        }

        /** {@code commitPhysical}: reserve, take the item, then the serial commit point and its follow-up. */
        CompletableFuture<Result> commitPhysical(UUID player, Opening opening, LootBoxRevision revision,
                List<String> serials) {
            opening.keyKind = Opening.KEY_PHYSICAL;
            return reserveFirst(opening, serials, 0).thenCompose(serial -> {
                if (serial == null) {
                    return abort(opening, Outcome.NO_KEY);
                }
                opening.keyId = serial.keyId;
                opening.keySerial = serial.serial;
                opening.state = Opening.RESERVED;
                boolean lastUse = OpeningRules.lastUse(serial);
                return storage.saveOpening(opening).thenCompose(saved -> {
                    if (crash == Crash.AFTER_RESERVE) {
                        return done(Outcome.CRASHED);
                    }
                    boolean taken = lastUse ? world.held(player).remove(serial.serial)
                            : world.held(player).contains(serial.serial);
                    if (!taken) {
                        return releaseReservation(opening.keySerial, opening.openingId)
                                .thenCompose(released -> abort(opening, Outcome.NO_KEY));
                    }
                    if (crash == Crash.AFTER_TAKE) {
                        return done(Outcome.CRASHED);
                    }
                    return redeem(opening).thenCompose(redeemed -> {
                        if (!redeemed) {
                            return done(Outcome.ERROR);
                        }
                        if (crash == Crash.AFTER_COMMIT) {
                            return done(Outcome.CRASHED);
                        }
                        return storage.updatePlayer(player, state -> {
                            if (!state.hasCommit(opening.openingId)) {
                                recordCommit(state, opening, revision);
                            }
                            return state;
                        }).thenCompose(state -> finishCommit(opening, revision));
                    });
                });
            });
        }

        /** {@code reserveFirst}: the first held serial that may be spent, now RESERVED by this opening. */
        CompletableFuture<KeySerial> reserveFirst(Opening opening, List<String> serials, int index) {
            if (index >= serials.size()) {
                return CompletableFuture.completedFuture(null);
            }
            long now = System.currentTimeMillis();
            AtomicReference<String> problem = new AtomicReference<>();
            return storage.updateSerial(serials.get(index), serial -> {
                String why = OpeningRules.reserve(serial, KEY, opening.openingId, now);
                problem.set(why);
                return why == null ? serial : null;
            }).thenCompose(serial -> problem.get() == null && serial != null
                    && opening.openingId.equals(serial.reservedBy)
                            ? CompletableFuture.completedFuture(serial)
                            : reserveFirst(opening, serials, index + 1));
        }

        /** {@code redeem}: the physical-key commit point. */
        CompletableFuture<Boolean> redeem(Opening opening) {
            AtomicBoolean redeemed = new AtomicBoolean();
            return storage.updateSerial(opening.keySerial, serial -> {
                redeemed.set(OpeningRules.redeem(serial, opening.openingId, opening.outcomeId));
                return redeemed.get() ? serial : null;
            }).thenApply(serial -> redeemed.get());
        }

        CompletableFuture<Void> releaseReservation(String serialId, String openingId) {
            return storage.updateSerial(serialId, serial -> OpeningRules.release(serial, openingId) ? serial : null)
                    .thenApply(serial -> null);
        }

        void recordCommit(PlayerLootState state, Opening opening, LootBoxRevision revision) {
            OpeningRules.recordCommit(state, opening, revision.spec.displayName, config.opening.historySize,
                    System.currentTimeMillis());
        }

        /** {@code finishCommit}: marks the opening COMMITTED and starts a delivery the lane does not wait for. */
        CompletableFuture<Result> finishCommit(Opening opening, LootBoxRevision revision) {
            opening.state = Opening.COMMITTED;
            opening.committedAt = System.currentTimeMillis();
            LootBoxDraft.RewardSpec reward = rewardOf(revision, opening.outcomeId);
            return storage.saveOpening(opening)
                    .thenApply(saved -> new Result(Outcome.COMMITTED, opening, deliver(opening, reward, false)));
        }

        CompletableFuture<Result> abort(Opening opening, Outcome outcome) {
            opening.state = Opening.ABORTED;
            return storage.saveOpening(opening)
                    .thenCompose(saved -> clearActive(UUID.fromString(opening.player), opening.openingId))
                    .thenApply(cleared -> Result.of(outcome));
        }

        CompletableFuture<Void> clearActive(UUID player, String openingId) {
            return storage.updatePlayer(player, state -> {
                if (openingId == null || !openingId.equals(state.activeOpening)) {
                    return null;
                }
                state.activeOpening = null;
                return state;
            }).thenApply(state -> null);
        }

        /** {@code RewardService.deliver}: registered as running, every action as {@link DeliveryRules} says, then settle. */
        CompletableFuture<Void> deliver(Opening opening, LootBoxDraft.RewardSpec reward, boolean force) {
            long started = System.currentTimeMillis();
            delivering.put(opening.openingId, started);
            CompletableFuture<String> chain = CompletableFuture.completedFuture(Opening.DELIVERED);
            for (int i = 0; i < opening.fulfillments.size(); i++) {
                int index = i;
                chain = chain.thenCompose(worst -> {
                    if (worst == null) {
                        return CompletableFuture.<String>completedFuture(null);
                    }
                    return deliverOne(opening, index, reward, force)
                            .thenApply(state -> state == null ? null : Opening.REVIEW.equals(worst) ? worst : state);
                });
            }
            return chain.thenCompose(finalState -> finalState == null ? CompletableFuture.<Void>completedFuture(null)
                    : settle(opening, finalState))
                    .whenComplete((settled, error) -> delivering.remove(opening.openingId, started));
        }

        /** {@code RewardService.deliverOne}; {@code null} when the crash hit after the reward took effect. */
        CompletableFuture<String> deliverOne(Opening opening, int index, LootBoxDraft.RewardSpec reward,
                boolean force) {
            Opening.Fulfillment action = opening.fulfillments.get(index);
            DeliveryRules.Step step = DeliveryRules.step(action, DeliveryRules.replaySafe(reward), force);
            if (step == DeliveryRules.Step.SETTLED) {
                return CompletableFuture.completedFuture(Opening.DELIVERED);
            }
            if (step == DeliveryRules.Step.REVIEW) {
                return mark(opening, index, Opening.Fulfillment.REVIEW).thenApply(marked -> Opening.REVIEW);
            }
            return mark(opening, index, Opening.Fulfillment.ATTEMPTED).thenCompose(marked -> {
                Pause paused = pause.getAndSet(null);
                if (paused == null) {
                    return CompletableFuture.<Void>completedFuture(null);
                }
                paused.reached().complete(null);
                return paused.resume();
            }).thenCompose(resumed -> {
                world.effects.computeIfAbsent(action.id, id -> new AtomicInteger()).incrementAndGet();
                if (crash == Crash.DURING_DELIVERY) {
                    return CompletableFuture.<String>completedFuture(null);
                }
                return mark(opening, index, Opening.Fulfillment.DELIVERED).thenApply(done -> Opening.DELIVERED);
            });
        }

        /** {@code RewardService.mark}: the action's state, written to the opening document. */
        CompletableFuture<Void> mark(Opening opening, int index, String state) {
            Opening.Fulfillment local = opening.fulfillments.get(index);
            local.state = state;
            local.updatedAt = System.currentTimeMillis();
            if (Opening.Fulfillment.ATTEMPTED.equals(state)) {
                local.attempts++;
            } else if (Opening.Fulfillment.REVIEW.equals(state)) {
                reviewed.add(local.id);
            }
            return storage.updateOpening(opening.openingId, stored -> {
                if (stored == null || index >= stored.fulfillments.size()) {
                    return null;
                }
                Opening.Fulfillment action = stored.fulfillments.get(index);
                action.state = local.state;
                action.updatedAt = local.updatedAt;
                action.attempts = local.attempts;
                stored.state = Opening.DELIVERING;
                return stored;
            }).thenApply(stored -> null);
        }

        /** {@code RewardService.settle}. */
        CompletableFuture<Void> settle(Opening opening, String finalState) {
            opening.state = finalState;
            String label = Opening.REVIEW.equals(finalState) ? "Under review" : "Delivered";
            return storage.updateOpening(opening.openingId, stored -> {
                if (stored == null) {
                    return null;
                }
                stored.state = finalState;
                return stored;
            }).thenCompose(stored -> storage.updatePlayer(UUID.fromString(opening.player), state -> {
                DeliveryRules.settle(state, opening.openingId, label);
                return state;
            })).thenApply(state -> null);
        }

        /** {@code recover}: the unfinished opening first, then every delivery that never settled. */
        CompletableFuture<Void> recover(UUID player) {
            return lanes.run(player, () -> storage.loadPlayer(player).thenCompose(state -> {
                CompletableFuture<Void> active = state.activeOpening == null ? CompletableFuture.completedFuture(null)
                        : storage.loadOpening(state.activeOpening)
                                .thenCompose(opening -> recoverActive(player, opening, state.activeOpening));
                return active.thenCompose(ignored -> storage.loadPlayer(player)).thenCompose(this::resumeDeliveries);
            }));
        }

        /** {@code recoverActive}: physical keys go through {@link RecoveryPolicy}. */
        CompletableFuture<Void> recoverActive(UUID player, Opening opening, String openingId) {
            if (opening == null) {
                return clearActive(player, openingId);
            }
            if (Opening.KEY_VIRTUAL.equals(opening.keyKind) || opening.keySerial == null) {
                return storage.loadPlayer(player).thenCompose(state -> state.hasCommit(opening.openingId)
                        ? ensureCommitted(opening)
                        : abort(opening, Outcome.ERROR).thenApply(result -> (Void) null));
            }
            return storage.loadSerial(opening.keySerial).thenCompose(serial -> {
                boolean held = world.held(player).contains(opening.keySerial);
                RecoveryPolicy.Action action = RecoveryPolicy.decide(opening.state, serial, opening.openingId, held);
                decisions.add(action);
                return switch (action) {
                    case ABORT, ABORT_FOREIGN -> abort(opening, Outcome.ERROR).thenApply(result -> (Void) null);
                    case ABORT_RELEASE -> releaseReservation(opening.keySerial, opening.openingId)
                            .thenCompose(released -> abort(opening, Outcome.ERROR))
                            .thenApply(result -> (Void) null);
                    case COMMIT -> redeem(opening).thenCompose(ok -> ok ? commitRecovered(player, opening)
                            : abort(opening, Outcome.ERROR).thenApply(result -> (Void) null));
                    case ENSURE_COMMITTED_CLEAN_UP -> {
                        world.held(player).remove(opening.keySerial);
                        yield commitRecovered(player, opening);
                    }
                    case ENSURE_COMMITTED -> commitRecovered(player, opening);
                    case NONE -> clearActive(player, opening.openingId);
                };
            });
        }

        /** {@code commitRecovered}: the player-document follow-up, then the opening's own state. */
        CompletableFuture<Void> commitRecovered(UUID player, Opening opening) {
            LootBoxRevision revision = world.revisions.get(opening.revision);
            return storage.updatePlayer(player, state -> {
                if (!state.hasCommit(opening.openingId) && revision != null) {
                    recordCommit(state, opening, revision);
                } else if (opening.openingId.equals(state.activeOpening)) {
                    state.activeOpening = null;
                }
                return state;
            }).thenCompose(state -> ensureCommitted(opening));
        }

        CompletableFuture<Void> ensureCommitted(Opening opening) {
            if (Opening.PREPARED.equals(opening.state) || Opening.RESERVED.equals(opening.state)) {
                opening.state = Opening.COMMITTED;
                opening.committedAt = System.currentTimeMillis();
            }
            return storage.saveOpening(opening)
                    .thenCompose(saved -> clearActive(UUID.fromString(opening.player), opening.openingId));
        }

        /** {@code resumeDeliveries}. */
        CompletableFuture<Void> resumeDeliveries(PlayerLootState state) {
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            for (String openingId : List.copyOf(state.openDeliveries)) {
                chain = chain.thenCompose(ignored -> {
                    Long runningSince = delivering.get(openingId);
                    return storage.loadOpening(openingId).thenCompose(opening -> {
                        if (!DeliveryRules.resumes(opening, runningSince, System.currentTimeMillis())) {
                            return CompletableFuture.<Void>completedFuture(null);
                        }
                        LootBoxDraft.RewardSpec reward = rewardOf(world.revisions.get(opening.revision),
                                opening.outcomeId);
                        return reward == null ? CompletableFuture.<Void>completedFuture(null)
                                : deliver(opening, reward, false);
                    });
                });
            }
            return chain;
        }

        /** {@code reconcile} with regrant: staff re-run a reviewed delivery, decided in the player's lane. */
        CompletableFuture<String> regrant(String openingId) {
            return storage.loadOpening(openingId).thenCompose(found -> {
                if (found == null) {
                    return CompletableFuture.completedFuture("unknown opening");
                }
                return lanes.run(UUID.fromString(found.player), () -> regrantInLane(openingId))
                        .thenCompose(reply -> reply);
            });
        }

        /** {@code reconcileInLane}: the inner future completes once the regrant settled. */
        CompletableFuture<CompletableFuture<String>> regrantInLane(String openingId) {
            Long runningSince = delivering.get(openingId);
            return storage.loadOpening(openingId).thenApply(opening -> {
                String problem = DeliveryRules.reconcileProblem(opening, runningSince);
                if (problem != null) {
                    return CompletableFuture.completedFuture(problem);
                }
                LootBoxDraft.RewardSpec reward = rewardOf(world.revisions.get(opening.revision), opening.outcomeId);
                if (reward == null) {
                    return CompletableFuture.completedFuture("its revision or reward is gone");
                }
                return deliver(opening, reward, true)
                        .thenApply(settled -> Opening.REVIEW.equals(opening.state) ? "Under review" : "Delivered");
            });
        }

        private static CompletableFuture<Result> done(Outcome outcome) {
            return CompletableFuture.completedFuture(Result.of(outcome));
        }
    }

    /**
     * {@link StorageService} in memory. Operations run on {@link #IO} and wait for
     * every earlier operation on the same document, as in {@code StorageServiceImpl},
     * and documents are copied in and out so no caller shares the stored tree.
     */
    private static final class MemoryStorage implements StorageService {

        private final Map<String, JsonElement> documents = new ConcurrentHashMap<>();
        private final Map<String, CompletableFuture<Void>> queues = new ConcurrentHashMap<>();

        @Override
        public String activeProvider() {
            return "memory";
        }

        @Override
        public CompletableFuture<JsonElement> load(String namespace, String key) {
            String id = id(namespace, key);
            return inOrder(id, () -> copy(documents.get(id)));
        }

        @Override
        public CompletableFuture<Void> save(String namespace, String key, JsonElement value) {
            String id = id(namespace, key);
            JsonElement stored = value.deepCopy();
            return inOrder(id, () -> {
                documents.put(id, stored);
                return null;
            });
        }

        @Override
        public CompletableFuture<Boolean> delete(String namespace, String key) {
            String id = id(namespace, key);
            return inOrder(id, () -> documents.remove(id) != null);
        }

        @Override
        public CompletableFuture<Boolean> exists(String namespace, String key) {
            String id = id(namespace, key);
            return inOrder(id, () -> documents.containsKey(id));
        }

        @Override
        public CompletableFuture<List<String>> listKeys(String namespace) {
            String prefix = namespace + "/";
            return CompletableFuture.supplyAsync(() -> documents.keySet().stream().filter(id -> id.startsWith(prefix))
                    .map(id -> id.substring(prefix.length())).sorted().toList(), IO);
        }

        @Override
        public CompletableFuture<JsonElement> update(String namespace, String key, UnaryOperator<JsonElement> updater) {
            String id = id(namespace, key);
            return inOrder(id, () -> {
                JsonElement current = copy(documents.get(id));
                JsonElement next = updater.apply(current);
                if (next == null) {
                    return current;
                }
                documents.put(id, next.deepCopy());
                return next;
            });
        }

        /** Every document as text, sorted by id: equal snapshots mean nothing was written in between. */
        Map<String, String> snapshot() {
            Map<String, String> copy = new TreeMap<>();
            documents.forEach((id, value) -> copy.put(id, value.toString()));
            return copy;
        }

        long count(String namespace) {
            return documents.keySet().stream().filter(id -> id.startsWith(namespace + "/")).count();
        }

        /**
         * Holds the document's later operations until the returned future completes,
         * so reads sent a moment apart run back to back, as two commands arriving
         * together do, before anything either of them writes next.
         */
        CompletableFuture<Void> hold(String namespace, String key) {
            String id = id(namespace, key);
            CompletableFuture<Void> release = new CompletableFuture<>();
            CompletableFuture<Void> done = new CompletableFuture<>();
            CompletableFuture<Void> previous = queues.put(id, done);
            CompletableFuture<?> before = previous == null ? CompletableFuture.completedFuture(null) : previous;
            before.handle((ignored, failure) -> null).thenCompose(ignored -> release).whenComplete((value, failure) -> {
                done.complete(null);
                queues.remove(id, done);
            });
            return release;
        }

        private <T> CompletableFuture<T> inOrder(String id, Supplier<T> operation) {
            CompletableFuture<Void> done = new CompletableFuture<>();
            CompletableFuture<Void> previous = queues.put(id, done);
            CompletableFuture<?> before = previous == null ? CompletableFuture.completedFuture(null) : previous;
            CompletableFuture<T> result = before.handle((ignored, failure) -> null)
                    .thenApplyAsync(ignored -> operation.get(), IO);
            result.whenComplete((value, failure) -> {
                done.complete(null);
                queues.remove(id, done);
            });
            return result;
        }

        private static String id(String namespace, String key) {
            return namespace + "/" + key;
        }

        private static JsonElement copy(JsonElement element) {
            return element == null ? null : element.deepCopy();
        }
    }

    // ----- Fixtures -----------------------------------------------------------

    private static LootBoxRevision revision(int number, long... weights) {
        String[][] rewards = { { "iron_sword", "Iron Sword" }, { "gold_250", "250 Gold" },
                { "guardian_companion", "Guardian Companion" } };
        LootBoxDraft draft = new LootBoxDraft();
        draft.id = POOL_ID;
        draft.displayName = "Avalon Supply Crate";
        draft.keys = new ArrayList<>(List.of(KEY));
        for (int i = 0; i < weights.length; i++) {
            LootBoxDraft.RewardSpec reward = reward("ITEM", false);
            reward.id = rewards[i][0];
            reward.name = rewards[i][1];
            reward.weight = weights[i];
            reward.item = new LootBoxDraft.ItemSpec();
            reward.item.itemId = "Ingredient_Bar_Iron";
            draft.rewards.add(reward);
        }
        LootBoxRevision revision = new LootBoxRevision();
        revision.lootboxId = POOL_ID;
        revision.revision = number;
        revision.spec = draft;
        revision.weights = OddsEngine.resolveWeights(draft);
        revision.contentHash = "content-" + number;
        return revision;
    }

    /** Gives every reward of {@code revision} the type {@code type}. */
    private static LootBoxRevision rewardType(LootBoxRevision revision, String type) {
        for (LootBoxDraft.RewardSpec reward : revision.spec.rewards) {
            reward.type = type;
        }
        return revision;
    }

    private static LootBoxDraft.RewardSpec reward(String type, boolean commandIdempotent) {
        LootBoxDraft.RewardSpec reward = new LootBoxDraft.RewardSpec();
        reward.type = type;
        reward.commandIdempotent = commandIdempotent;
        return reward;
    }

    private static LootBoxDraft.RewardSpec rewardOf(LootBoxRevision revision, String rewardId) {
        if (revision == null) {
            return null;
        }
        for (LootBoxDraft.RewardSpec reward : revision.spec.rewards) {
            if (reward.id.equals(rewardId)) {
                return reward;
            }
        }
        return null;
    }

    /** {@code KeyService.grantVirtual}. */
    private static void grantVirtual(World world, UUID player, int amount) {
        await(world.storage.updatePlayer(player, state -> {
            state.virtualKeys.merge(KEY, amount, Integer::sum);
            return state;
        }));
    }

    /** {@code KeyService.issueSerial}: the ledger entry first, then the item in the inventory. */
    private static String issueSerial(World world, UUID player, int uses) {
        KeySerial serial = serial(uses);
        serial.serial = "serial-" + UUID.randomUUID();
        serial.owner = player.toString();
        await(world.storage.saveSerial(serial));
        world.held(player).add(serial.serial);
        return serial.serial;
    }

    private static KeySerial serial(int uses) {
        KeySerial serial = new KeySerial();
        serial.serial = "serial";
        serial.keyId = KEY;
        serial.usesLeft = uses;
        return serial;
    }

    private static Opening opening(String openingId, String keyKind) {
        Opening opening = new Opening();
        opening.openingId = openingId;
        opening.requestId = "req-" + openingId;
        opening.lootboxId = POOL_ID;
        opening.revision = 3;
        opening.outcomeId = "iron_sword";
        opening.outcomeName = "Iron Sword";
        opening.snapshotHash = "0123456789abcdef";
        opening.keyId = KEY;
        opening.keyKind = keyKind;
        return opening;
    }

    private static Opening prepared(String openingId, String requestId, long createdAt) {
        Opening opening = opening(openingId, Opening.KEY_VIRTUAL);
        opening.requestId = requestId;
        opening.createdAt = createdAt;
        return opening;
    }

    private static Opening inState(String state) {
        Opening opening = opening("op", Opening.KEY_VIRTUAL);
        opening.state = state;
        return opening;
    }

    private static Opening.Fulfillment action(String state) {
        Opening.Fulfillment action = new Opening.Fulfillment();
        action.id = "op#0";
        action.state = state;
        return action;
    }

    private static PlayerLootState.Receipt receipt(String openingId) {
        PlayerLootState.Receipt receipt = new PlayerLootState.Receipt();
        receipt.openingId = openingId;
        receipt.status = "Delivering";
        return receipt;
    }

    private static PlayerLootState stateOf(World world, UUID player) {
        return await(world.storage.loadPlayer(player));
    }

    private static int balance(World world, UUID player) {
        return stateOf(world, player).virtualKeys.getOrDefault(KEY, 0);
    }

    private static KeySerial serialOf(World world, String serial) {
        return await(world.storage.loadSerial(serial));
    }

    private static Opening openingOf(World world, String openingId) {
        return await(world.storage.loadOpening(openingId));
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new AssertionError("a saga step failed: " + e.getCause(), e.getCause());
        } catch (TimeoutException e) {
            throw new AssertionError("a saga step did not finish within " + TIMEOUT_SECONDS + "s");
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
