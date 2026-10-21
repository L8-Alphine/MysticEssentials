package org.hyzionstudios.mysticessentials.modules.lootbox;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
import org.hyzionstudios.mysticessentials.modules.lootbox.model.KeyBatch;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.KeyDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.KeyLog;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.KeySerial;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.Opening;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.PendingInbox;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.PlayerLootState;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.PlayerLootState.Conversion;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.SecureDraw;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.ConversionRules;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.DraftLifecycle;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.KeyMarker;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.OpeningRules;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.PlayerLanes;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.StackRules;
import org.hyzionstudios.mysticessentials.modules.lootbox.storage.LootBoxStorage;
import org.hyzionstudios.mysticessentials.modules.lootbox.ui.editor.KeyDiff;

import com.google.gson.JsonElement;

/**
 * Engine-free checks of the LootBox keys track.
 *
 * <ul>
 *   <li><b>Grant history (P2-F2)</b> stays bounded and keeps every entry written
 *       at the same time.</li>
 *   <li><b>Conversion saga (P2-F3)</b>: a crash at every step of a deposit or a
 *       withdrawal, then recovery, never credits twice and never loses a key; a
 *       request sent twice converts once; a copied key deposits once; a revoke
 *       during a deposit wins; racing withdrawals never overdraw; recovery waits
 *       when the inventory cannot be read and never races a running conversion.</li>
 * </ul>
 *
 * <p>{@code ConversionService} needs a running server (PlayerRef, world
 * threads), so {@link Converter} drives the rules it applies
 * ({@link ConversionRules}) in the order {@code ConversionService} applies them,
 * through the real {@link PlayerLanes} and {@link LootBoxStorage} over an
 * in-memory {@link StorageService} that orders each document's operations the
 * way {@code StorageServiceImpl} does, as in {@code LootBoxSagaTest}. A simulated
 * crash stops a conversion at a chosen step; a new {@link Converter} over the
 * same {@link World} is the restarted server.</p>
 */
public final class LootBoxKeysTest {

    private static final String KEY = "mystic:royal";
    private static final long TIMEOUT_SECONDS = 10;

    /** Storage operations run here, so documents really are read and written from several threads. */
    private static final ExecutorService IO = Executors.newFixedThreadPool(4, task -> {
        Thread thread = new Thread(task, "keys-io");
        thread.setDaemon(true);
        return thread;
    });

    private LootBoxKeysTest() {
    }

    public static void main(String[] args) {
        try {
            grantHistoryIsBoundedAndNewestFirst();
            concurrentHistoryWritesAreAllKept();
            depositDecisionTable();
            withdrawalDecisionTable();
            depositCreditsTheRemainingUses();
            depositCrashAtEveryStep();
            depositWaitsWhenTheInventoryIsUnreadable();
            repeatedDepositRequestConvertsOnce();
            copiedKeyDepositsOnce();
            revokeDuringADepositWins();
            refusedKeysStayInTheInventory();
            withdrawalCrashAtEveryStep();
            withdrawalOverflowGoesToTheInbox();
            racingWithdrawalsNeverOverdraw();
            repeatedWithdrawalRequestDebitsOnce();
            recoveryLeavesARunningConversionAlone();
            serializedMarkersAreUnchanged();
            publishedKeysStayPublished();
            stackDecisionTable();
            racingSpendsNeverOverdrawABatch();
            batchOpeningRecoveryPrefersAbort();
            batchDepositCrashAtEveryStep();
            batchWithdrawalRecoveryTopsUpOnce();
        } finally {
            IO.shutdownNow();
        }
        System.out.println("LootBoxKeysTest: all checks passed.");
    }

    // ----- Grant history (P2-F2) ------------------------------------------------

    private static void grantHistoryIsBoundedAndNewestFirst() {
        KeyLog log = new KeyLog();
        for (int i = 0; i < 150; i++) {
            log.add(entry(KeyLog.GRANT, i), 100);
        }
        require(log.entries.size() == 100, "the history kept " + log.entries.size() + " entries, not 100");
        require(log.entries.get(0).count == 50, "the history dropped the newest entries instead of the oldest");
        List<KeyLog.Entry> newest = log.newest(40);
        require(newest.size() == 40 && newest.get(0).count == 149 && newest.get(39).count == 110,
                "newest(40) is not the 40 newest, newest first");
        require(new KeyLog().newest(10).isEmpty(), "an empty history listed entries");
        log.add(entry(KeyLog.REVOKE, 999), 0);
        require(log.entries.size() == 1 && log.entries.get(0).count == 999, "a bound below 1 did not keep the newest entry");
    }

    private static void concurrentHistoryWritesAreAllKept() {
        MemoryStorage memory = new MemoryStorage();
        LootBoxStorage storage = new LootBoxStorage(() -> memory);
        List<CompletableFuture<KeyLog>> writes = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            writes.add(storage.appendKeyLog(KEY, entry(KeyLog.GRANT, i), 100));
        }
        CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new)).join();
        KeyLog log = await(storage.loadKeyLog(KEY));
        require(log.entries.size() == 60, "racing history writes kept " + log.entries.size() + " of 60");
        require(log.entries.stream().map(e -> e.count).distinct().count() == 60, "a racing history write was lost");
        require(await(storage.loadKeyLog("mystic:other")).entries.isEmpty(), "another key's history is not empty");
    }

    // ----- Conversion: the decisions alone (P2-F3) ----------------------------------

    private static void depositDecisionTable() {
        String id = "conv";
        require(ConversionRules.decideDeposit(id, null, true) == ConversionRules.DepositAction.ABORT,
                "a vanished serial was not aborted");
        require(ConversionRules.decideDeposit(id, serial(KeySerial.ISSUED, 1), true) == ConversionRules.DepositAction.ABORT,
                "an unreserved serial was not aborted");
        KeySerial reserved = serial(KeySerial.RESERVED, 1);
        reserved.reservedBy = id;
        require(ConversionRules.decideDeposit(id, reserved, true) == ConversionRules.DepositAction.RELEASE_ABORT,
                "a reserved key still held was not released");
        require(ConversionRules.decideDeposit(id, reserved, false) == ConversionRules.DepositAction.CONVERT_CREDIT,
                "a reserved key that left was not converted");
        require(ConversionRules.decideDeposit(id, reserved, null) == ConversionRules.DepositAction.WAIT,
                "recovery guessed about an unreadable inventory");
        reserved.reservedBy = "another opening";
        require(ConversionRules.decideDeposit(id, reserved, false) == ConversionRules.DepositAction.ABORT_FOREIGN,
                "another reservation was taken over");
        KeySerial converted = serial(KeySerial.CONVERTED, 2);
        converted.redeemedBy = id;
        require(ConversionRules.decideDeposit(id, converted, false) == ConversionRules.DepositAction.CREDIT,
                "a converted serial was not credited");
        require(ConversionRules.decideDeposit(id, converted, true) == ConversionRules.DepositAction.CREDIT_CLEAN_UP,
                "a converted serial's dead key was not removed");
        require(ConversionRules.decideDeposit(id, converted, null) == ConversionRules.DepositAction.CREDIT,
                "a converted serial waited on the inventory");
        for (String state : List.of(KeySerial.REDEEMED, KeySerial.REVOKED)) {
            require(ConversionRules.decideDeposit(id, serial(state, 0), false) == ConversionRules.DepositAction.ABORT_FOREIGN,
                    "a " + state + " serial was not left alone");
        }
        converted.redeemedBy = "another conversion";
        require(ConversionRules.decideDeposit(id, converted, false) == ConversionRules.DepositAction.ABORT_FOREIGN,
                "another conversion's serial was credited");
    }

    private static void withdrawalDecisionTable() {
        KeySerial issued = serial(KeySerial.ISSUED, 1);
        require(ConversionRules.decideWithdrawn(issued, true, false) == ConversionRules.Delivery.SKIP, "a held key was given again");
        require(ConversionRules.decideWithdrawn(issued, false, true) == ConversionRules.Delivery.SKIP,
                "a key in the inbox was filed again");
        require(ConversionRules.decideWithdrawn(issued, null, true) == ConversionRules.Delivery.SKIP,
                "a key in the inbox waited on the inventory");
        require(ConversionRules.decideWithdrawn(issued, false, false) == ConversionRules.Delivery.TO_INBOX,
                "a key that is nowhere was not filed");
        require(ConversionRules.decideWithdrawn(issued, null, false) == ConversionRules.Delivery.WAIT,
                "recovery guessed about an unreadable inventory");
        for (String state : List.of(KeySerial.REDEEMED, KeySerial.REVOKED, KeySerial.RESERVED, KeySerial.CONVERTED)) {
            require(ConversionRules.decideWithdrawn(serial(state, 0), false, false) == ConversionRules.Delivery.SKIP,
                    "a " + state + " key was handed out again");
        }
        require(ConversionRules.withdrawProblem(3, 0, 64) != null && ConversionRules.withdrawProblem(3, 65, 64) != null,
                "a count outside 1..max passed");
        require(ConversionRules.withdrawProblem(3, 4, 64) != null && ConversionRules.withdrawProblem(3, 3, 64) == null,
                "the balance check is off");
        KeySerial fresh = ConversionRules.withdrawnSerial("s", KEY, 4, "owner", "actor", 10L);
        require(fresh.usesLeft == 1 && fresh.expiresAt == 0 && KeySerial.ISSUED.equals(fresh.state)
                && ConversionRules.SOURCE.equals(fresh.source), "a withdrawn key is worth more or less than one opening");

        // The keyed writes refuse a repeat on their own, whoever repeats them.
        PlayerLootState state = new PlayerLootState();
        ConversionRules.prepareDeposit(state, "d", null, KEY, "s", 1L);
        require(ConversionRules.credit(state, "d", KEY, 2, 1L) && !ConversionRules.credit(state, "d", KEY, 2, 1L)
                && state.virtualKeys.get(KEY) == 2, "a deposit was credited twice");
        require(!ConversionRules.abort(state, "d", "late"), "a credited deposit was aborted");
        require(ConversionRules.debit(state, "w", null, KEY, 1, List.of("x"), 1L) == ConversionRules.Debit.DEBITED
                && ConversionRules.debit(state, "w", null, KEY, 1, List.of("x"), 1L) == ConversionRules.Debit.ALREADY
                && state.virtualKeys.get(KEY) == 1, "a withdrawal was debited twice");
        require(ConversionRules.settle(state, "w") && !ConversionRules.settle(state, "w"), "a withdrawal settled twice");

        // Finished conversions are dropped beyond the bound; an unfinished one never is.
        PlayerLootState full = new PlayerLootState();
        ConversionRules.prepareDeposit(full, "open", null, KEY, "s", 0L);
        for (int i = 0; i < PlayerLootState.MAX_CONVERSIONS + 20; i++) {
            ConversionRules.prepareDeposit(full, "done-" + i, null, KEY, "s", i);
            ConversionRules.abort(full, "done-" + i, "test");
        }
        require(full.conversions.size() == PlayerLootState.MAX_CONVERSIONS && full.conversion("open") != null
                && full.conversion("done-" + (PlayerLootState.MAX_CONVERSIONS + 19)) != null,
                "the conversion list dropped an unfinished or a recent record");
    }

    // ----- Deposit ----------------------------------------------------------------

    private static void depositCreditsTheRemainingUses() {
        World world = new World();
        UUID player = UUID.randomUUID();
        String serial = world.issue(player, 3, 0);
        world.hand.put(player, serial);
        Converter converter = new Converter(world);
        Outcome outcome = await(converter.deposit(player, "req-1"));
        require(outcome == Outcome.DEPOSITED, "a deposit ended " + outcome);
        require(balance(world, player) == 3, "a 3-use key credited " + balance(world, player));
        require(!world.holds(player, serial), "the deposited key stayed in the inventory");
        KeySerial stored = serialOf(world, serial);
        require(KeySerial.CONVERTED.equals(stored.state) && stored.usesLeft == 3 && stored.reservedBy == null,
                "the serial is not CONVERTED with the uses it credited");
        Conversion conversion = only(stateOf(world, player).conversions);
        require(Conversion.CREDITED.equals(conversion.state) && conversion.count == 3, "the conversion is not CREDITED");
        require(OpeningRules.spendProblem(stored, KEY, System.currentTimeMillis()) != null,
                "a deposited key can still open something");
    }

    private enum Crash {
        NONE,
        AFTER_PREPARE,
        AFTER_RESERVE,
        AFTER_TAKE,
        AFTER_CONVERT,
        AFTER_DEBIT,
        AFTER_ISSUE,
        AFTER_GIVE
    }

    private static void depositCrashAtEveryStep() {
        depositCrash(Crash.AFTER_PREPARE, false, false);
        depositCrash(Crash.AFTER_RESERVE, false, false);
        depositCrash(Crash.AFTER_TAKE, false, true);
        depositCrash(Crash.AFTER_TAKE, true, false);
        depositCrash(Crash.AFTER_CONVERT, false, true);
        depositCrash(Crash.AFTER_CONVERT, true, true);
    }

    /**
     * Crashes a deposit of a 2-use key at {@code crash}; {@code keyCameBack}
     * puts the item back as the engine losing its removal would.
     */
    private static void depositCrash(Crash crash, boolean keyCameBack, boolean credited) {
        World world = new World();
        UUID player = UUID.randomUUID();
        String serial = world.issue(player, 2, 0);
        world.hand.put(player, serial);
        Converter crashing = new Converter(world);
        crashing.crash = crash;
        require(await(crashing.deposit(player, "req")) == Outcome.CRASHED, "no crash at " + crash);
        if (keyCameBack) {
            world.give(player, serial);
        }
        Converter restarted = new Converter(world);
        await(restarted.recover(player));
        String label = crash + (keyCameBack ? " (key came back)" : "");
        require(balance(world, player) == (credited ? 2 : 0), label + ": recovery left a balance of " + balance(world, player));
        require(world.holds(player, serial) == !credited, label + ": the key item is " + (credited ? "still there" : "gone"));
        KeySerial stored = serialOf(world, serial);
        require(credited ? KeySerial.CONVERTED.equals(stored.state)
                : KeySerial.ISSUED.equals(stored.state) && stored.reservedBy == null && stored.usesLeft == 2,
                label + ": the serial is " + stored.state);
        Conversion conversion = only(stateOf(world, player).conversions);
        require((credited ? Conversion.CREDITED : Conversion.ABORTED).equals(conversion.state),
                label + ": the conversion is " + conversion.state);

        // The next pass and the next join find nothing left to do.
        await(restarted.recover(player));
        Converter rejoined = new Converter(world);
        await(rejoined.recover(player));
        require(restarted.decisions.size() == 1 && rejoined.decisions.isEmpty(),
                label + ": a finished conversion was recovered again");
        require(balance(world, player) == (credited ? 2 : 0), label + ": a second pass changed the balance");

        if (credited) {
            // Two servers recovering the same crash at once (the player moved between
            // them) still credit once: the credit is keyed by the conversion id.
            World again = new World();
            UUID other = UUID.randomUUID();
            String key = again.issue(other, 2, 0);
            again.hand.put(other, key);
            Converter crashed = new Converter(again);
            crashed.crash = crash;
            await(crashed.deposit(other, "req"));
            if (keyCameBack) {
                again.give(other, key);
            }
            CompletableFuture<Void> release = again.memory.hold(LootBoxStorageNs.PLAYER, other.toString());
            CompletableFuture<Void> one = new Converter(again).recover(other);
            CompletableFuture<Void> two = new Converter(again).recover(other);
            release.complete(null);
            await(one);
            await(two);
            require(balance(again, other) == 2, label + ": two recoveries at once credited " + balance(again, other));
        } else {
            // The kept key still deposits normally.
            world.hand.put(player, serial);
            require(await(rejoined.deposit(player, "req-2")) == Outcome.DEPOSITED, label + ": the kept key did not deposit");
            require(balance(world, player) == 2, label + ": the kept key credited " + balance(world, player));
        }
    }

    private static void depositWaitsWhenTheInventoryIsUnreadable() {
        World world = new World();
        UUID player = UUID.randomUUID();
        String serial = world.issue(player, 1, 0);
        world.hand.put(player, serial);
        Converter crashing = new Converter(world);
        crashing.crash = Crash.AFTER_TAKE;
        require(await(crashing.deposit(player, "req")) == Outcome.CRASHED, "no crash");
        world.readable = false;
        Converter blind = new Converter(world);
        await(blind.recover(player));
        require(KeySerial.RESERVED.equals(serialOf(world, serial).state) && balance(world, player) == 0
                && only(stateOf(world, player).conversions).unfinished(), "recovery acted without seeing the inventory");
        Outcome busy = await(blind.deposit(player, "req-2"));
        require(busy == Outcome.BUSY, "a new deposit ran beside an unfinished one: " + busy);
        world.readable = true;
        await(new Converter(world).recover(player));
        require(KeySerial.CONVERTED.equals(serialOf(world, serial).state) && balance(world, player) == 1,
                "recovery did not finish once it could see");
    }

    private static void repeatedDepositRequestConvertsOnce() {
        World world = new World();
        UUID player = UUID.randomUUID();
        String serial = world.issue(player, 1, 0);
        String second = world.issue(player, 1, 0);
        world.hand.put(player, serial);
        Converter converter = new Converter(world);
        // Both arrive before either is handled, as a double click does.
        CompletableFuture<Void> release = world.memory.hold(LootBoxStorageNs.PLAYER, player.toString());
        CompletableFuture<Outcome> first = converter.deposit(player, "same-request");
        CompletableFuture<Outcome> again = converter.deposit(player, "same-request");
        release.complete(null);
        require(await(first) == Outcome.DEPOSITED, "the first request did not deposit");
        require(await(again) == Outcome.DUPLICATE, "the repeated request was not recognised");
        require(balance(world, player) == 1 && world.holds(player, second), "a repeated request converted another key");
        require(stateOf(world, player).conversions.size() == 1, "a repeated request recorded a second conversion");
    }

    private static void copiedKeyDepositsOnce() {
        World world = new World();
        UUID player = UUID.randomUUID();
        String serial = world.issue(player, 1, 0);
        world.give(player, serial); // a duplicated item: two stacks, one serial
        world.hand.put(player, serial);
        Converter converter = new Converter(world);
        require(await(converter.deposit(player, "a")) == Outcome.DEPOSITED, "the key did not deposit");
        world.hand.put(player, serial);
        Outcome copy = await(converter.deposit(player, "b"));
        require(copy == Outcome.REFUSED, "the copy was " + copy);
        require(balance(world, player) == 1, "a copied key credited twice: " + balance(world, player));
        require(!world.holds(player, serial), "the dead copy stayed in the inventory");
        require(Conversion.ABORTED.equals(stateOf(world, player).conversions.get(1).state), "the copy's conversion is open");
    }

    private static void revokeDuringADepositWins() {
        World world = new World();
        UUID player = UUID.randomUUID();
        String serial = world.issue(player, 1, 0);
        world.hand.put(player, serial);
        Converter converter = new Converter(world);
        converter.afterTake = () -> world.storage.updateSerial(serial, entry -> {
            entry.state = KeySerial.REVOKED; // KeyService.revoke on a RESERVED serial
            return entry;
        });
        require(await(converter.deposit(player, "req")) == Outcome.REFUSED, "a revoked deposit was not refused");
        require(balance(world, player) == 0 && KeySerial.REVOKED.equals(serialOf(world, serial).state),
                "a key revoked during its deposit was credited");
        require(Conversion.ABORTED.equals(only(stateOf(world, player).conversions).state), "the conversion stayed open");
    }

    private static void refusedKeysStayInTheInventory() {
        World world = new World();
        UUID player = UUID.randomUUID();
        String expiring = world.issue(player, 1, System.currentTimeMillis() + TimeUnit.DAYS.toMillis(7));
        world.hand.put(player, expiring);
        Converter converter = new Converter(world);
        require(await(converter.deposit(player, "req")) == Outcome.REFUSED, "an expiring key was deposited");
        require(world.holds(player, expiring) && KeySerial.ISSUED.equals(serialOf(world, expiring).state)
                && balance(world, player) == 0, "a refused key was touched");
        world.hand.remove(player);
        require(await(converter.deposit(player, "req-2")) == Outcome.NO_KEY, "an empty hand deposited something");
    }

    // ----- Withdrawal -------------------------------------------------------------

    private static void withdrawalCrashAtEveryStep() {
        withdrawalCrash(Crash.AFTER_DEBIT, false, false);
        withdrawalCrash(Crash.AFTER_ISSUE, false, false);
        withdrawalCrash(Crash.AFTER_GIVE, false, false);
        withdrawalCrash(Crash.AFTER_GIVE, true, false);
        withdrawalCrash(Crash.AFTER_GIVE, false, true);
    }

    /**
     * Crashes a withdrawal of 3 keys from a balance of 5 at {@code crash}.
     * {@code giveLost}: the engine lost the give; {@code spentOne}: the player
     * spent one key before recovery ran.
     */
    private static void withdrawalCrash(Crash crash, boolean giveLost, boolean spentOne) {
        World world = new World();
        UUID player = UUID.randomUUID();
        grantVirtual(world, player, 5);
        Converter crashing = new Converter(world);
        crashing.crash = crash;
        require(await(crashing.withdraw(player, 3, "req")) == Outcome.CRASHED, "no crash at " + crash);
        String label = crash + (giveLost ? " (give lost)" : "") + (spentOne ? " (one spent)" : "");
        Conversion conversion = only(stateOf(world, player).conversions);
        require(Conversion.DEBITED.equals(conversion.state) && balance(world, player) == 2,
                label + ": the debit was not one complete write");
        if (giveLost) {
            conversion.serials.forEach(serial -> world.take(player, serial));
        }
        String spent = conversion.serials.get(0);
        if (spentOne) {
            world.take(player, spent);
            await(world.storage.updateSerial(spent, entry -> {
                entry.state = KeySerial.REDEEMED; // an opening spent it
                entry.usesLeft = 0;
                return entry;
            }));
        }

        Converter restarted = new Converter(world);
        await(restarted.recover(player));
        require(balance(world, player) == 2, label + ": recovery changed the balance to " + balance(world, player));
        require(Conversion.DELIVERED.equals(only(stateOf(world, player).conversions).state),
                label + ": the withdrawal was not settled");
        PendingInbox inbox = await(world.storage.loadPending(player));
        for (String serial : conversion.serials) {
            KeySerial stored = serialOf(world, serial);
            require(stored != null && stored.usesLeft == (serial.equals(spent) && spentOne ? 0 : 1),
                    label + ": serial " + serial + " was not issued once with one use");
            int places = world.count(player, serial) + (inbox.contains(ConversionRules.inboxId(serial)) ? 1 : 0);
            int expected = serial.equals(spent) && spentOne ? 0 : 1;
            require(places == expected, label + ": withdrawn key " + serial + " is in " + places + " places");
        }

        // The next pass and the next join file nothing more.
        int filed = inbox.items.size();
        await(restarted.recover(player));
        await(new Converter(world).recover(player));
        require(await(world.storage.loadPending(player)).items.size() == filed, label + ": a second pass filed keys again");
        require(balance(world, player) == 2, label + ": a second pass changed the balance");
    }

    private static void withdrawalOverflowGoesToTheInbox() {
        World world = new World();
        UUID player = UUID.randomUUID();
        grantVirtual(world, player, 4);
        world.capacity.put(player, 1);
        Converter converter = new Converter(world);
        require(await(converter.withdraw(player, 4, "req")) == Outcome.WITHDRAWN, "the withdrawal failed");
        Conversion conversion = only(stateOf(world, player).conversions);
        PendingInbox inbox = await(world.storage.loadPending(player));
        require(world.count(player) == 1 && inbox.items.size() == 3, "the overflow did not go to the inbox");
        for (String serial : conversion.serials) {
            int places = world.count(player, serial) + (inbox.contains(ConversionRules.inboxId(serial)) ? 1 : 0);
            require(places == 1, "withdrawn key " + serial + " is in " + places + " places");
        }
        require(balance(world, player) == 0 && !stateOf(world, player).virtualKeys.containsKey(KEY),
                "a fully withdrawn balance was not removed");
    }

    private static void racingWithdrawalsNeverOverdraw() {
        World world = new World();
        UUID player = UUID.randomUUID();
        grantVirtual(world, player, 5);
        Converter converter = new Converter(world);
        List<CompletableFuture<Outcome>> queued = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            queued.add(converter.withdraw(player, 2, "req-" + i));
        }
        long withdrawn = queued.stream().map(LootBoxKeysTest::await).filter(o -> o == Outcome.WITHDRAWN).count();
        require(withdrawn == 2, withdrawn + " withdrawals of 2 succeeded from a balance of 5");
        require(balance(world, player) == 1, "queued withdrawals left " + balance(world, player));
        require(world.count(player) == 4, "queued withdrawals gave " + world.count(player) + " keys for 4 debited");

        // The commit point alone, raced outside any lane (as two servers sharing the document would):
        // every debit sees the one before it, so the balance never goes negative.
        World shared = new World();
        UUID other = UUID.randomUUID();
        grantVirtual(shared, other, 5);
        CompletableFuture<Void> release = shared.memory.hold(LootBoxStorageNs.PLAYER, other.toString());
        List<CompletableFuture<ConversionRules.Debit>> racing = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String id = "conv-" + i;
            AtomicReference<ConversionRules.Debit> debit = new AtomicReference<>();
            racing.add(shared.storage.updatePlayer(other, state -> {
                debit.set(ConversionRules.debit(state, id, null, KEY, 1, List.of("s-" + id), 1L));
                return debit.get() == ConversionRules.Debit.DEBITED ? state : null;
            }).thenApply(state -> debit.get()));
        }
        release.complete(null);
        long debited = racing.stream().map(LootBoxKeysTest::await).filter(d -> d == ConversionRules.Debit.DEBITED).count();
        require(debited == 5 && balance(shared, other) == 0, debited + " racing debits of 1 passed a balance of 5");
    }

    private static void repeatedWithdrawalRequestDebitsOnce() {
        World world = new World();
        UUID player = UUID.randomUUID();
        grantVirtual(world, player, 3);
        Converter converter = new Converter(world);
        CompletableFuture<Void> release = world.memory.hold(LootBoxStorageNs.PLAYER, player.toString());
        CompletableFuture<Outcome> first = converter.withdraw(player, 1, "same");
        CompletableFuture<Outcome> again = converter.withdraw(player, 1, "same");
        release.complete(null);
        require(await(first) == Outcome.WITHDRAWN && await(again) == Outcome.DUPLICATE,
                "a repeated withdrawal request was not recognised");
        require(balance(world, player) == 2 && world.count(player) == 1, "a repeated request debited twice");
    }

    private static void recoveryLeavesARunningConversionAlone() {
        World world = new World();
        UUID player = UUID.randomUUID();
        String serial = world.issue(player, 1, 0);
        world.hand.put(player, serial);
        Converter converter = new Converter(world);
        Pause pause = new Pause();
        converter.pause.set(pause);
        CompletableFuture<Outcome> deposit = converter.deposit(player, "req");
        await(pause.reached());
        // The 5-minute sweep fires while the item is being taken.
        CompletableFuture<Void> sweep = converter.recover(player);
        require(!sweep.isDone(), "recovery ran beside a conversion in the same lane");
        pause.resume().complete(null);
        require(await(deposit) == Outcome.DEPOSITED, "the paused deposit did not finish");
        await(sweep);
        require(converter.decisions.isEmpty(), "recovery found the finished deposit unfinished: " + converter.decisions);
        require(balance(world, player) == 1, "the deposit credited " + balance(world, player));
    }

    // ----- Stackable keys (P2-K) ------------------------------------------------------

    /** The default path's marker is signed over exactly the Phase 1 fields; a batch id is part of what is signed. */
    private static void serializedMarkersAreUnchanged() {
        byte[] secret = new byte[32];
        for (int i = 0; i < secret.length; i++) {
            secret[i] = (byte) (i * 7 + 3);
        }
        KeyMarker serialized = KeyMarker.signed(KEY, 3, "serial1", 1, secret);
        require(serialized.signature().equals(hmac(secret, KEY + "|3|serial1|1")),
                "a serialized key's marker is no longer signed over keyId|version|serial|secretId");
        KeyMarker batch = KeyMarker.signed(KEY, 3, KeyBatch.PREFIX + "abc", 1, secret);
        require(batch.verify(secret), "a batch marker did not verify");
        require(!new KeyMarker(KEY, 3, "abc", 1, batch.signature()).verify(secret),
                "a batch marker's signature survived dropping the batch prefix");
        require(!new KeyMarker(KEY, 3, KeyBatch.PREFIX + "abd", 1, batch.signature()).verify(secret),
                "a batch marker's signature survived another batch id");
        require(KeyBatch.isBatch(KeyBatch.PREFIX + "x") && !KeyBatch.isBatch(new SecureDraw().id()) && !KeyBatch.isBatch(null),
                "a serial could be mistaken for a batch");
    }

    private static String hmac(byte[] secret, String payload) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret, "HmacSHA256"));
            return java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new AssertionError(e);
        }
    }

    /** A key published before P2-K (no stackable field) still answers "unchanged" and shows no diff. */
    private static void publishedKeysStayPublished() {
        String before = "{\"id\":\"mystic:royal\",\"baseItemId\":\"Ingredient_Bar_Iron\",\"name\":\"Royal Key\","
                + "\"lore\":[\"Opens royal crates\"],\"uses\":1,\"expiresAfterDays\":0}";
        KeyDraft published = org.hyzionstudios.mysticessentials.core.util.Json.gson().fromJson(before, KeyDraft.class);
        KeyDraft draft = DraftLifecycle.freeze(published, KeyDraft.class);
        require(!published.stackable, "a key published before P2-K reads as stackable");
        require(DraftLifecycle.keySpecHash(published).equals(DraftLifecycle.keySpecHash(draft)),
                "an unchanged pre-P2-K key would publish again");
        require(!KeyDiff.changed(draft, published), "an unchanged pre-P2-K key shows changes");
        draft.stackable = true;
        require(!DraftLifecycle.keySpecHash(published).equals(DraftLifecycle.keySpecHash(draft))
                && KeyDiff.changed(draft, published), "switching stacking on is not a change");
        require(String.join(" ", KeyDiff.lines(draft, published)).contains("stack"), "the diff does not say stacking changed");
    }

    private static void stackDecisionTable() {
        KeyBatch batch = StackRules.newBatch(KeyBatch.PREFIX + "b", KEY, 1, "owner", 2, "ADMIN_GRANT", "staff", 0L, 0);
        require(StackRules.spendProblem(batch, KEY, 1, 0L) == null, "a fresh batch can't be spent");
        require("wrong key".equals(StackRules.spendProblem(batch, "mystic:other", 1, 0L)), "another key's batch passed");
        require(StackRules.reserve(batch, KEY, "op1", 1, 2, 0L) == null && StackRules.reserve(batch, KEY, "op1", 1, 2, 0L) == null
                && batch.reservations.size() == 1, "a repeated reserve held twice");
        require(StackRules.reserve(batch, KEY, "op2", 1, 2, 0L) == null && batch.available() == 0, "two holds of two units");
        require("reserved".equals(StackRules.spendProblem(batch, KEY, 1, 0L)), "a fully held batch reads as dead");
        require(StackRules.redeem(batch, "op1") == 1 && StackRules.redeem(batch, "op1") == 0 && batch.remaining == 1,
                "a redeem spent twice");
        require(StackRules.release(batch, "op2") && !StackRules.release(batch, "op2") && batch.available() == 1,
                "a release was not undone exactly once");
        require(StackRules.redeem(batch, "op3") == 0, "a redeem without a hold spent");
        require(StackRules.reserve(batch, KEY, "op4", 2, 1, 0L) != null, "a hold of more than is left passed");

        KeyBatch held = StackRules.newBatch(KeyBatch.PREFIX + "h", KEY, 1, "owner", 3, "ADMIN_GRANT", "staff", 0L, 0);
        StackRules.reserve(held, KEY, "op", 1, 3, 0L);
        require(StackRules.decideOpening(Opening.RESERVED, held, "op", 3) == StackRules.Recovery.RELEASE,
                "an untouched stack was not released");
        require(StackRules.decideOpening(Opening.RESERVED, held, "op", 2) == StackRules.Recovery.RELEASE_REGIVE
                && StackRules.regive(held.reservation("op"), 2) == 1, "a smaller stack was not given back one item");
        require(StackRules.regive(held.reservation("op"), 0) == 1, "a regive returned more than the hold");
        require(StackRules.decideOpening(Opening.RESERVED, held, "op", null) == StackRules.Recovery.RELEASE,
                "an unreadable inventory did not abort");
        require(StackRules.decideOpening(Opening.COMMITTED, held, "op", 3) == StackRules.Recovery.COMMITTED,
                "a committed opening was rolled back");
        require(StackRules.decideOpening(Opening.ABORTED, held, "op", 3) == StackRules.Recovery.NONE,
                "an aborted opening was recovered");
        require(StackRules.decideOpening(Opening.PREPARED, held, "other", 3) == StackRules.Recovery.ABORT,
                "an opening without a hold was not aborted");
        StackRules.redeem(held, "op");
        require(StackRules.decideOpening(Opening.RESERVED, held, "op", 3) == StackRules.Recovery.COMMITTED,
                "a spent hold was not committed");
        StackRules.reserve(held, KEY, "dep", 2, 2, 0L);
        require(StackRules.decideDeposit("dep", held, null) == StackRules.Recovery.WAIT, "a deposit guessed");
        require(StackRules.decideDeposit("dep", held, 2) == StackRules.Recovery.RELEASE
                && StackRules.decideDeposit("dep", held, 0) == StackRules.Recovery.RELEASE_REGIVE,
                "a batch deposit's recovery is off");
        held.revoked = true;
        require(StackRules.decideDeposit("dep", held, 0) == StackRules.Recovery.RELEASE
                && StackRules.decideOpening(Opening.RESERVED, held, "dep", 0) == StackRules.Recovery.RELEASE,
                "a revoked batch was given back");
        require("revoked key".equals(StackRules.spendProblem(held, KEY, 1, 0L)), "a revoked batch could be spent");
        KeyBatch expiring = StackRules.newBatch(KeyBatch.PREFIX + "e", KEY, 1, "owner", 1, "ADMIN_GRANT", "staff", 0L, 50L);
        require("expired".equals(StackRules.spendProblem(expiring, KEY, 1, 50L)), "an expired batch could be spent");
        require("expiring".equals(ConversionRules.reserveBatch(expiring, KEY, "c", 1, 1, 10L)),
                "an expiring batch could be deposited");
        KeyBatch dupes = StackRules.newBatch(KeyBatch.PREFIX + "d", KEY, 1, "owner", 3, "ADMIN_GRANT", "staff", 0L, 0);
        require("only 3 left".equals(ConversionRules.reserveBatch(dupes, KEY, "c", 5, 5, 0L)) && dupes.reservations.isEmpty(),
                "a stack larger than its batch was deposited");
        require(StackRules.missingWithdrawn(dupes, 1, 1) == 1 && StackRules.missingWithdrawn(dupes, null, 0) == -1
                && StackRules.missingWithdrawn(dupes, 5, 0) == 0, "the withdrawal top-up is off");
    }

    private static void racingSpendsNeverOverdrawABatch() {
        World world = new World();
        String id = KeyBatch.PREFIX + "race";
        await(world.storage.saveBatch(StackRules.newBatch(id, KEY, 1, "owner", 3, "ADMIN_GRANT", "staff", 0L, 0)));
        CompletableFuture<Void> release = world.memory.hold("lootbox_batch", id);
        List<CompletableFuture<Integer>> spends = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String holder = "op" + i;
            AtomicReference<String> problem = new AtomicReference<>();
            // OpeningService.reserveBatch then redeem, from eight players at once.
            spends.add(world.storage.updateBatch(id, batch -> {
                problem.set(StackRules.reserve(batch, KEY, holder, 1, 1, 0L));
                return problem.get() == null ? batch : null;
            }).thenCompose(held -> {
                AtomicInteger spent = new AtomicInteger();
                return problem.get() != null ? CompletableFuture.completedFuture(0)
                        : world.storage.updateBatch(id, batch -> {
                            spent.set(StackRules.redeem(batch, holder));
                            return spent.get() > 0 ? batch : null;
                        }).thenApply(batch -> spent.get());
            }));
        }
        release.complete(null);
        int spent = spends.stream().mapToInt(LootBoxKeysTest::await).sum();
        KeyBatch after = await(world.storage.loadBatch(id));
        require(spent == 3 && after.remaining == 0 && after.reservations.isEmpty() && after.spentBy.size() == 3,
                spent + " of 8 racing spends passed a batch of 3");
    }

    /** One opening of a batch item, crashed after each step, then recovered. */
    private static void batchOpeningRecoveryPrefersAbort() {
        for (Crash crash : List.of(Crash.AFTER_RESERVE, Crash.AFTER_TAKE, Crash.AFTER_CONVERT)) {
            World world = new World();
            UUID player = UUID.randomUUID();
            String id = KeyBatch.PREFIX + "open";
            await(world.storage.saveBatch(StackRules.newBatch(id, KEY, 1, player.toString(), 3, "ADMIN_GRANT", "staff", 0L, 0)));
            world.stacks.put(player, 3);
            String openingId = "op";
            await(world.storage.updateBatch(id, batch -> {
                StackRules.reserve(batch, KEY, openingId, 1, world.stacks.get(player), 0L);
                return batch;
            }));
            String state = Opening.RESERVED;
            if (crash != Crash.AFTER_RESERVE) {
                world.stacks.merge(player, -1, Integer::sum); // InventoryAdapter.removeOne
            }
            if (crash == Crash.AFTER_CONVERT) {
                await(world.storage.updateBatch(id, batch -> StackRules.redeem(batch, openingId) > 0 ? batch : null));
            }
            // OpeningService.recoverBatch
            KeyBatch found = await(world.storage.loadBatch(id));
            StackRules.Recovery action = StackRules.decideOpening(state, found, openingId, world.stacks.get(player));
            int give = StackRules.regive(found.reservation(openingId), world.stacks.get(player));
            if (action == StackRules.Recovery.RELEASE || action == StackRules.Recovery.RELEASE_REGIVE) {
                await(world.storage.updateBatch(id, batch -> StackRules.release(batch, openingId) ? batch : null));
            }
            if (action == StackRules.Recovery.RELEASE_REGIVE) {
                world.stacks.merge(player, give, Integer::sum); // KeyService.regive
            }
            KeyBatch after = await(world.storage.loadBatch(id));
            boolean committed = crash == Crash.AFTER_CONVERT;
            require(action == (committed ? StackRules.Recovery.COMMITTED
                    : crash == Crash.AFTER_TAKE ? StackRules.Recovery.RELEASE_REGIVE : StackRules.Recovery.RELEASE),
                    crash + ": recovery decided " + action);
            require(after.reservations.isEmpty() && after.remaining == (committed ? 2 : 3),
                    crash + ": the batch was left at " + after.remaining + " with " + after.reservations.size() + " holds");
            require(world.stacks.get(player) == after.remaining,
                    crash + ": the player holds " + world.stacks.get(player) + " items for " + after.remaining + " openings");
        }
    }

    /** A deposit of a held stack of 4 batch items, crashed after each step, then recovered. */
    private static void batchDepositCrashAtEveryStep() {
        for (Crash crash : List.of(Crash.AFTER_PREPARE, Crash.AFTER_RESERVE, Crash.AFTER_TAKE, Crash.AFTER_CONVERT)) {
            World world = new World();
            UUID player = UUID.randomUUID();
            String id = KeyBatch.PREFIX + "dep";
            await(world.storage.saveBatch(StackRules.newBatch(id, KEY, 1, player.toString(), 4, "ADMIN_GRANT", "staff", 0L, 0)));
            world.stacks.put(player, 4);
            String conversionId = "conv";
            // ConversionService.startDeposit, as far as the crash lets it go.
            await(world.storage.updatePlayer(player, state -> {
                ConversionRules.prepareDeposit(state, conversionId, null, KEY, id, 0L).count = 4;
                return state;
            }));
            if (crash != Crash.AFTER_PREPARE) {
                await(world.storage.updateBatch(id, batch -> ConversionRules.reserveBatch(batch, KEY, conversionId, 4,
                        world.stacks.get(player), 0L) == null ? batch : null));
            }
            if (crash == Crash.AFTER_TAKE || crash == Crash.AFTER_CONVERT) {
                world.stacks.put(player, 0); // InventoryAdapter.takeCount
            }
            if (crash == Crash.AFTER_CONVERT) {
                await(world.storage.updateBatch(id, batch -> StackRules.redeem(batch, conversionId) > 0 ? batch : null));
            }
            // ConversionService.recoverBatchDeposit, twice: a second pass changes nothing.
            for (int pass = 0; pass < 2; pass++) {
                Conversion open = ConversionRules.unfinished(stateOf(world, player)).stream().findFirst().orElse(null);
                if (open == null) {
                    continue;
                }
                KeyBatch found = await(world.storage.loadBatch(id));
                int heldNow = world.stacks.get(player);
                StackRules.Recovery action = StackRules.decideDeposit(conversionId, found, heldNow);
                int give = StackRules.regive(found.reservation(conversionId), heldNow);
                if (action == StackRules.Recovery.RELEASE || action == StackRules.Recovery.RELEASE_REGIVE) {
                    await(world.storage.updateBatch(id, batch -> StackRules.release(batch, conversionId) ? batch : null));
                }
                if (action == StackRules.Recovery.RELEASE_REGIVE) {
                    world.stacks.merge(player, give, Integer::sum);
                }
                if (action == StackRules.Recovery.COMMITTED) {
                    await(world.storage.updatePlayer(player, state -> ConversionRules.credit(state, conversionId, KEY,
                            open.count, 0L) ? state : null));
                } else {
                    await(world.storage.updatePlayer(player, state -> ConversionRules.abort(state, conversionId, "test")
                            ? state : null));
                }
            }
            KeyBatch after = await(world.storage.loadBatch(id));
            boolean credited = crash == Crash.AFTER_CONVERT;
            require(balance(world, player) == (credited ? 4 : 0), crash + ": the deposit credited " + balance(world, player));
            require(after.reservations.isEmpty() && after.remaining == (credited ? 0 : 4)
                    && world.stacks.get(player) == after.remaining,
                    crash + ": " + world.stacks.get(player) + " items for " + after.remaining + " openings left");
            require(only(stateOf(world, player).conversions).state.equals(credited ? Conversion.CREDITED : Conversion.ABORTED),
                    crash + ": the conversion is " + only(stateOf(world, player).conversions).state);
        }
    }

    /** A withdrawn batch whose give was lost: recovery files the missing items once. */
    private static void batchWithdrawalRecoveryTopsUpOnce() {
        World world = new World();
        UUID player = UUID.randomUUID();
        grantVirtual(world, player, 5);
        String id = KeyBatch.PREFIX + "w";
        await(world.storage.updatePlayer(player, state -> ConversionRules.debit(state, "conv", null, KEY, 5, List.of(id),
                0L) == ConversionRules.Debit.DEBITED ? state : null));
        await(world.storage.updateBatch(id, existing -> existing == null
                ? StackRules.newBatch(id, KEY, 1, player.toString(), 5, ConversionRules.SOURCE, "player", 0L, 0) : null));
        world.stacks.put(player, 2); // only 2 of the 5 made it before the crash
        for (int pass = 0; pass < 2; pass++) {
            // ConversionService.recoverBatchWithdrawal
            PendingInbox inbox = await(world.storage.loadPending(player));
            int filed = inbox.items.stream().filter(item -> item.fulfillmentId.startsWith(ConversionRules.inboxId(id) + "#"))
                    .mapToInt(item -> item.quantity).sum();
            int missing = StackRules.missingWithdrawn(await(world.storage.loadBatch(id)), world.stacks.get(player), filed);
            if (missing > 0) {
                await(world.storage.updatePending(player, pending -> {
                    String entryId = ConversionRules.inboxId(id) + "#conv#recovered";
                    if (!pending.contains(entryId)) {
                        PendingInbox.PendingReward entry = new PendingInbox.PendingReward();
                        entry.fulfillmentId = entryId;
                        entry.quantity = missing;
                        pending.items.add(entry);
                    }
                    return pending;
                }));
            }
            await(world.storage.updatePlayer(player, state -> ConversionRules.settle(state, "conv") ? state : null));
        }
        PendingInbox inbox = await(world.storage.loadPending(player));
        require(inbox.items.size() == 1 && inbox.items.get(0).quantity == 3, "the lost items were not filed exactly once");
        require(balance(world, player) == 0 && Conversion.DELIVERED.equals(only(stateOf(world, player).conversions).state),
                "the withdrawal did not settle");
    }

    // ----- The conversion, without the engine ---------------------------------------

    private enum Outcome {
        DEPOSITED,
        WITHDRAWN,
        DUPLICATE,
        BUSY,
        NO_KEY,
        REFUSED,
        CRASHED
    }

    /** The namespaces {@code LootBoxStorage} uses, for holding a document in the in-memory store. */
    private static final class LootBoxStorageNs {
        static final String PLAYER = "lootbox_player";
    }

    /** Holds a deposit after its reservation until {@code resume} completes, as its world-thread hop does. */
    private record Pause(CompletableFuture<Void> reached, CompletableFuture<Void> resume) {

        Pause() {
            this(new CompletableFuture<>(), new CompletableFuture<>());
        }
    }

    /** What outlives a server: documents and the players' key items (one entry per stack, by serial). */
    private static final class World {

        final MemoryStorage memory = new MemoryStorage();
        final LootBoxStorage storage = new LootBoxStorage(() -> memory);
        final Map<UUID, List<String>> inventory = new ConcurrentHashMap<>();
        /** The serial in each player's active hotbar slot. */
        final Map<UUID, String> hand = new ConcurrentHashMap<>();
        /** Inventory slots each player has free for new items; unlimited when absent. */
        final Map<UUID, Integer> capacity = new ConcurrentHashMap<>();
        /** Whether the world answers inventory reads ({@code false}: the player's world is busy). */
        volatile boolean readable = true;
        /** Items of the one batch a stackable-key check uses, per player. */
        final Map<UUID, Integer> stacks = new ConcurrentHashMap<>();

        List<String> items(UUID player) {
            return inventory.computeIfAbsent(player, uuid -> Collections.synchronizedList(new ArrayList<>()));
        }

        /** {@code KeyService.issueSerial}: the ledger entry first, then the item. */
        String issue(UUID player, int uses, long expiresAt) {
            KeySerial serial = serial(KeySerial.ISSUED, uses);
            serial.serial = "serial-" + UUID.randomUUID();
            serial.owner = player.toString();
            serial.expiresAt = expiresAt;
            await(storage.saveSerial(serial));
            give(player, serial.serial);
            return serial.serial;
        }

        /** @return whether it fit */
        boolean give(UUID player, String serial) {
            int free = capacity.getOrDefault(player, Integer.MAX_VALUE);
            if (free <= 0) {
                return false;
            }
            if (free != Integer.MAX_VALUE) {
                capacity.put(player, free - 1);
            }
            items(player).add(serial);
            return true;
        }

        /** {@code InventoryAdapter.removeOne}: one stack with {@code serial}. */
        boolean take(UUID player, String serial) {
            return items(player).remove(serial);
        }

        boolean holds(UUID player, String serial) {
            return items(player).contains(serial);
        }

        int count(UUID player, String serial) {
            synchronized (items(player)) {
                return (int) items(player).stream().filter(serial::equals).count();
            }
        }

        int count(UUID player) {
            return items(player).size();
        }

        /** {@code ConversionService.keyHeld}: {@code null} when the world does not answer. */
        Boolean held(UUID player, String serial) {
            return readable ? holds(player, serial) : null;
        }
    }

    /**
     * One server run. Each step names the {@code ConversionService} method it
     * follows; the engine's part is the {@link World}'s inventory.
     */
    private static final class Converter {

        final World world;
        final LootBoxStorage storage;
        final PlayerLanes lanes = new PlayerLanes();
        final SecureDraw draw = new SecureDraw();
        /** The recovery decisions, in order. */
        final List<String> decisions = new CopyOnWriteArrayList<>();
        /** Taken by the next deposit to pass its reservation. */
        final AtomicReference<Pause> pause = new AtomicReference<>();
        /** Runs between taking the item and the commit, e.g. a staff revoke. */
        volatile Supplier<CompletableFuture<?>> afterTake = () -> CompletableFuture.completedFuture(null);
        volatile Crash crash = Crash.NONE;

        Converter(World world) {
            this.world = world;
            this.storage = world.storage;
        }

        // ----- deposit → depositInLane → startDeposit → commitDeposit → credit -----

        CompletableFuture<Outcome> deposit(UUID player, String requestId) {
            return lanes.run(player, () -> storage.loadPlayer(player).thenCompose(state -> {
                if (ConversionRules.earlier(state, requestId) != null) {
                    return done(Outcome.DUPLICATE);
                }
                if (ConversionRules.busy(state)) {
                    return recoverInLane(player).thenApply(ignored -> Outcome.BUSY);
                }
                String serialId = world.hand.get(player);
                if (serialId == null || !world.holds(player, serialId)) {
                    return done(Outcome.NO_KEY);
                }
                String conversionId = draw.id();
                long now = System.currentTimeMillis();
                AtomicReference<String> problem = new AtomicReference<>();
                return storage.updatePlayer(player, fresh -> {
                    ConversionRules.prepareDeposit(fresh, conversionId, requestId, KEY, serialId, now);
                    return fresh;
                }).thenCompose(prepared -> {
                    if (crash == Crash.AFTER_PREPARE) {
                        return done(Outcome.CRASHED);
                    }
                    return storage.updateSerial(serialId, serial -> {
                        String why = ConversionRules.reserve(serial, KEY, conversionId, now);
                        problem.set(why);
                        return why == null ? serial : null;
                    }).thenCompose(reserved -> {
                        if (problem.get() != null) {
                            // rejectDead: a spent, revoked or unknown serial is a dead copy.
                            if (!"expiring".equals(problem.get()) && !"expired".equals(problem.get())) {
                                while (world.take(player, serialId)) {
                                    // remove every stack
                                }
                            }
                            return abort(player, conversionId).thenApply(ignored -> Outcome.REFUSED);
                        }
                        if (crash == Crash.AFTER_RESERVE) {
                            return done(Outcome.CRASHED);
                        }
                        return paused().thenCompose(ignored -> {
                            if (!world.take(player, serialId)) {
                                return releaseAndAbort(player, serialId, conversionId).thenApply(i -> Outcome.NO_KEY);
                            }
                            if (crash == Crash.AFTER_TAKE) {
                                return done(Outcome.CRASHED);
                            }
                            return afterTake.get().thenCompose(i -> commitDeposit(player, conversionId, serialId));
                        });
                    });
                });
            }));
        }

        CompletableFuture<Void> paused() {
            Pause next = pause.getAndSet(null);
            if (next == null) {
                return CompletableFuture.completedFuture(null);
            }
            next.reached().complete(null);
            return next.resume();
        }

        CompletableFuture<Outcome> commitDeposit(UUID player, String conversionId, String serialId) {
            AtomicInteger uses = new AtomicInteger(-1);
            return storage.updateSerial(serialId, serial -> {
                if (ConversionRules.convert(serial, conversionId)) {
                    uses.set(serial.usesLeft);
                    return serial;
                }
                if (ConversionRules.convertedBy(serial, conversionId)) {
                    uses.set(serial.usesLeft);
                }
                return null;
            }).thenCompose(serial -> {
                if (uses.get() < 0) {
                    return abort(player, conversionId).thenApply(ignored -> Outcome.REFUSED);
                }
                if (crash == Crash.AFTER_CONVERT) {
                    return done(Outcome.CRASHED);
                }
                return credit(player, conversionId, uses.get());
            });
        }

        CompletableFuture<Outcome> credit(UUID player, String conversionId, int uses) {
            AtomicBoolean credited = new AtomicBoolean();
            return storage.updatePlayer(player, state -> {
                credited.set(ConversionRules.credit(state, conversionId, KEY, uses, System.currentTimeMillis()));
                return credited.get() ? state : null;
            }).thenApply(state -> credited.get() ? Outcome.DEPOSITED : Outcome.DUPLICATE);
        }

        // ----- withdraw → withdrawInLane → deliver → issue / give / toInbox / settle -----

        CompletableFuture<Outcome> withdraw(UUID player, int count, String requestId) {
            return lanes.run(player, () -> storage.loadPlayer(player).thenCompose(state -> {
                if (ConversionRules.earlier(state, requestId) != null) {
                    return done(Outcome.DUPLICATE);
                }
                if (ConversionRules.busy(state)) {
                    return recoverInLane(player).thenApply(ignored -> Outcome.BUSY);
                }
                if (ConversionRules.withdrawProblem(state.virtualKeys.getOrDefault(KEY, 0), count, 64) != null) {
                    return done(Outcome.NO_KEY);
                }
                String conversionId = draw.id();
                List<String> serials = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    serials.add(draw.id());
                }
                AtomicReference<ConversionRules.Debit> debit = new AtomicReference<>();
                long now = System.currentTimeMillis();
                return storage.updatePlayer(player, fresh -> {
                    debit.set(ConversionRules.debit(fresh, conversionId, requestId, KEY, count, serials, now));
                    return debit.get() == ConversionRules.Debit.DEBITED ? fresh : null;
                }).thenCompose(debited -> {
                    if (debit.get() != ConversionRules.Debit.DEBITED) {
                        return done(debit.get() == ConversionRules.Debit.ALREADY ? Outcome.DUPLICATE : Outcome.NO_KEY);
                    }
                    if (crash == Crash.AFTER_DEBIT) {
                        return done(Outcome.CRASHED);
                    }
                    Conversion conversion = debited.conversion(conversionId);
                    return issue(player, conversion).thenCompose(issued -> {
                        if (crash == Crash.AFTER_ISSUE) {
                            return done(Outcome.CRASHED);
                        }
                        List<String> left = new ArrayList<>();
                        for (String serial : conversion.serials) {
                            if (!world.give(player, serial)) {
                                left.add(serial);
                            }
                        }
                        if (crash == Crash.AFTER_GIVE) {
                            return done(Outcome.CRASHED);
                        }
                        return toInbox(player, left).thenCompose(n -> settle(player, conversionId))
                                .thenApply(ignored -> Outcome.WITHDRAWN);
                    });
                });
            }));
        }

        /** Writes each serial only if absent, so a retry never resets one that was spent. */
        CompletableFuture<Void> issue(UUID player, Conversion conversion) {
            List<CompletableFuture<KeySerial>> writes = new ArrayList<>();
            for (String serialId : conversion.serials) {
                KeySerial fresh = ConversionRules.withdrawnSerial(serialId, KEY, 1, player.toString(), "player",
                        System.currentTimeMillis());
                writes.add(storage.updateSerial(serialId, existing -> existing == null ? fresh : null));
            }
            return CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new));
        }

        /** {@code KeyService.toInbox}: once per serial. */
        CompletableFuture<Integer> toInbox(UUID player, List<String> serials) {
            if (serials.isEmpty()) {
                return CompletableFuture.completedFuture(0);
            }
            return storage.updatePending(player, inbox -> {
                for (String serial : serials) {
                    String id = ConversionRules.inboxId(serial);
                    if (!inbox.contains(id)) {
                        PendingInbox.PendingReward entry = new PendingInbox.PendingReward();
                        entry.fulfillmentId = id;
                        entry.label = KEY;
                        inbox.items.add(entry);
                    }
                }
                return inbox;
            }).thenApply(inbox -> serials.size());
        }

        CompletableFuture<Void> settle(UUID player, String conversionId) {
            return storage.updatePlayer(player, state -> ConversionRules.settle(state, conversionId) ? state : null)
                    .thenApply(state -> null);
        }

        // ----- recoverInLane → recoverDeposit / recoverWithdrawal -----------------

        CompletableFuture<Void> recover(UUID player) {
            return lanes.run(player, () -> recoverInLane(player));
        }

        CompletableFuture<Void> recoverInLane(UUID player) {
            return storage.loadPlayer(player).thenCompose(state -> {
                CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
                for (Conversion conversion : ConversionRules.unfinished(state)) {
                    chain = chain.thenCompose(ignored -> Conversion.DEPOSIT.equals(conversion.kind)
                            ? recoverDeposit(player, conversion) : recoverWithdrawal(player, conversion));
                }
                return chain;
            });
        }

        CompletableFuture<Void> recoverDeposit(UUID player, Conversion conversion) {
            String id = conversion.conversionId;
            return storage.loadSerial(conversion.serial).thenCompose(serial -> {
                ConversionRules.DepositAction action = ConversionRules.decideDeposit(id, serial,
                        world.held(player, conversion.serial));
                if (action != ConversionRules.DepositAction.WAIT) {
                    decisions.add(action.name());
                }
                return switch (action) {
                    case ABORT, ABORT_FOREIGN -> abort(player, id);
                    case RELEASE_ABORT -> releaseAndAbort(player, conversion.serial, id);
                    case CONVERT_CREDIT -> commitDeposit(player, id, conversion.serial).thenApply(o -> (Void) null);
                    case CREDIT -> credit(player, id, serial.usesLeft).thenApply(o -> (Void) null);
                    case CREDIT_CLEAN_UP -> {
                        while (world.take(player, conversion.serial)) {
                            // InventoryAdapter.removeAll
                        }
                        yield credit(player, id, serial.usesLeft).thenApply(o -> (Void) null);
                    }
                    case WAIT -> CompletableFuture.completedFuture(null);
                };
            });
        }

        CompletableFuture<Void> recoverWithdrawal(UUID player, Conversion conversion) {
            return issue(player, conversion).thenCompose(ignored -> storage.loadPending(player)).thenCompose(inbox -> {
                List<CompletableFuture<ConversionRules.Delivery>> decided = new ArrayList<>();
                for (String serialId : conversion.serials) {
                    boolean inInbox = inbox.contains(ConversionRules.inboxId(serialId));
                    decided.add(storage.loadSerial(serialId).thenApply(serial ->
                            ConversionRules.decideWithdrawn(serial, world.held(player, serialId), inInbox)));
                }
                return CompletableFuture.allOf(decided.toArray(CompletableFuture[]::new)).thenCompose(all -> {
                    List<String> missing = new ArrayList<>();
                    for (int i = 0; i < decided.size(); i++) {
                        ConversionRules.Delivery delivery = decided.get(i).join();
                        if (delivery == ConversionRules.Delivery.WAIT) {
                            return CompletableFuture.completedFuture(null);
                        }
                        if (delivery == ConversionRules.Delivery.TO_INBOX) {
                            missing.add(conversion.serials.get(i));
                        }
                    }
                    decisions.add("WITHDRAW " + missing.size());
                    return toInbox(player, missing).thenCompose(n -> settle(player, conversion.conversionId));
                });
            });
        }

        CompletableFuture<Void> abort(UUID player, String conversionId) {
            return storage.updatePlayer(player, state -> ConversionRules.abort(state, conversionId, "aborted") ? state : null)
                    .thenApply(state -> null);
        }

        CompletableFuture<Void> releaseAndAbort(UUID player, String serialId, String conversionId) {
            return storage.updateSerial(serialId, serial -> ConversionRules.release(serial, conversionId) ? serial : null)
                    .thenCompose(serial -> abort(player, conversionId));
        }

        private static CompletableFuture<Outcome> done(Outcome outcome) {
            return CompletableFuture.completedFuture(outcome);
        }
    }

    // ----- Fixtures -----------------------------------------------------------

    private static KeySerial serial(String state, int uses) {
        KeySerial serial = new KeySerial();
        serial.serial = "serial";
        serial.keyId = KEY;
        serial.state = state;
        serial.usesLeft = uses;
        return serial;
    }

    /** {@code KeyService.grantVirtual}. */
    private static void grantVirtual(World world, UUID player, int amount) {
        await(world.storage.updatePlayer(player, state -> {
            state.virtualKeys.merge(KEY, amount, Integer::sum);
            return state;
        }));
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

    private static <T> T only(List<T> list) {
        require(list.size() == 1, "expected exactly one entry, found " + list.size());
        return list.get(0);
    }

    private static KeyLog.Entry entry(String action, int count) {
        KeyLog.Entry entry = new KeyLog.Entry();
        entry.action = action;
        entry.count = count;
        entry.at = count;
        return entry;
    }

    /**
     * {@link StorageService} in memory. Operations run on {@link #IO} and wait for
     * every earlier operation on the same document, as in {@code StorageServiceImpl},
     * and documents are copied in and out so no caller shares the stored tree.
     */
    static final class MemoryStorage implements StorageService {

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

        /**
         * Holds the document's later operations until the returned future completes,
         * so requests sent a moment apart are queued together, as a double click is.
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
