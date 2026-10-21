package org.hyzionstudios.mysticessentials.modules.lootbox.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.modules.lootbox.hytale.InventoryAdapter;
import org.hyzionstudios.mysticessentials.modules.lootbox.hytale.KeyItemFactory;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.KeyBatch;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.KeyDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.KeyLog;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.KeySerial;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.PendingInbox;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.PlayerLootState.Conversion;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.SecureDraw;
import org.hyzionstudios.mysticessentials.modules.lootbox.storage.LootBoxStorage;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Physical ↔ virtual key conversion (P2-F3): {@code /lootbox key deposit} turns
 * the held key item into virtual balance, {@code /lootbox key withdraw} turns
 * balance into key items with fresh serials. Each is a small saga in the
 * player's {@link PlayerLanes lane}, decided by {@link ConversionRules}; the
 * whole saga (world-thread hops included) runs inside the lane, so recovery,
 * which also runs there, never races one. A hop that does not answer within
 * {@link ConversionRules#HOP_TIMEOUT_MS} leaves the rest to recovery.
 *
 * <p>Recovery runs on join and in the 5-minute sweep, through
 * {@link OpeningService#recover}. Every conversion is audited and listed in the
 * key's history.</p>
 */
public final class ConversionService {

    public enum Status {
        DEPOSITED,
        WITHDRAWN,
        /** The same request arrived twice; nothing was done again. */
        DUPLICATE,
        /** An earlier opening or conversion is still finishing. */
        BUSY,
        NO_KEY,
        REFUSED,
        /** The world did not answer in time; recovery finishes it. */
        PENDING,
        ERROR
    }

    /** {@code count}: uses credited, or keys withdrawn; {@code pending}: keys that went to the inbox. */
    public record Result(Status status, String message, String keyId, int count, int pending, int balance) {

        static Result of(Status status, String message) {
            return new Result(status, message, null, 0, 0, 0);
        }
    }

    private final MysticCore core;
    private final LootBoxStorage storage;
    private final LootBoxRegistry registry;
    private final AuditService audit;
    private final PlayerLanes lanes;
    private final SecureDraw draw;
    private final KeyService keys;

    ConversionService(MysticCore core, LootBoxStorage storage, LootBoxRegistry registry, AuditService audit,
            PlayerLanes lanes, SecureDraw draw, KeyService keys) {
        this.core = core;
        this.storage = storage;
        this.registry = registry;
        this.audit = audit;
        this.lanes = lanes;
        this.draw = draw;
        this.keys = keys;
    }

    // ----- Deposit --------------------------------------------------------------

    /** Turns the key in {@code player}'s hand into virtual balance. {@code requestId} may be {@code null}. */
    public CompletableFuture<Result> deposit(PlayerRef player, String requestId) {
        return lanes.run(player.getUuid(), () -> depositInLane(player, requestId)).exceptionally(error -> {
            core.log(Level.SEVERE, "[lootbox] deposit by " + player.getUsername() + " failed: " + error);
            return Result.of(Status.ERROR, "Something went wrong; your key is safe and nothing was credited yet.");
        });
    }

    private CompletableFuture<Result> depositInLane(PlayerRef player, String requestId) {
        if (!keys.config().keys.allowConversion) {
            return done(Status.REFUSED, "Converting keys is turned off on this server.");
        }
        UUID uuid = player.getUuid();
        return storage.loadPlayer(uuid).thenCompose(state -> {
            Conversion earlier = ConversionRules.earlier(state, requestId);
            if (earlier != null) {
                return done(Status.DUPLICATE, "That deposit was already handled.");
            }
            if (ConversionRules.busy(state)) {
                return recoverInLane(player).thenApply(ignored -> Result.of(Status.BUSY,
                        "Your last opening or conversion was still finishing. Try again in a moment."));
            }
            return heldKey(player).thenCompose(hand -> {
                if (hand == null) {
                    return done(Status.NO_KEY, "Hold the key you want to deposit.");
                }
                InventoryAdapter.KeyStack held = hand.stack();
                if (!held.marker().verify(keys.secret())) {
                    return done(Status.REFUSED, "That key was not issued by this server.");
                }
                String keyId = held.marker().keyId();
                if (registry.key(keyId).isEmpty()) {
                    return done(Status.REFUSED, "That key is no longer in use.");
                }
                return startDeposit(player, requestId, hand);
            });
        });
    }

    /**
     * A serialized key deposits one item. A stack of batch items (P2-K) deposits
     * the whole held stack, as one hold of that many units on the batch.
     */
    private CompletableFuture<Result> startDeposit(PlayerRef player, String requestId, Hand hand) {
        UUID uuid = player.getUuid();
        InventoryAdapter.KeyStack held = hand.stack();
        String conversionId = draw.id();
        String keyId = held.marker().keyId();
        String serialId = held.marker().serial();
        boolean batch = KeyBatch.isBatch(serialId);
        int units = batch ? Math.max(1, held.quantity()) : 1;
        long now = System.currentTimeMillis();
        AtomicReference<String> problem = new AtomicReference<>();
        CompletableFuture<?> prepared = storage.updatePlayer(uuid, state -> {
            ConversionRules.prepareDeposit(state, conversionId, requestId, keyId, serialId, now).count = units;
            return state;
        });
        return prepared.thenCompose(ignored -> batch
                ? storage.updateBatch(serialId, entry -> {
                    String why = ConversionRules.reserveBatch(entry, keyId, conversionId, units, hand.total(), now);
                    problem.set(why);
                    return why == null ? entry : null;
                }).thenApply(entry -> (Object) entry)
                : storage.updateSerial(serialId, serial -> {
                    String why = ConversionRules.reserve(serial, keyId, conversionId, now);
                    problem.set(why);
                    return why == null ? serial : null;
                }).thenApply(serial -> (Object) serial)).thenCompose(reserved -> {
            if (problem.get() != null) {
                rejectDead(player, serialId, problem.get());
                return abort(uuid, conversionId, problem.get())
                        .thenApply(ignored -> Result.of(Status.REFUSED, refusal(problem.get())));
            }
            return take(player, held, batch ? units : 0).thenCompose(taken -> {
                if (taken == null) {
                    audit.record(uuid.toString(), "KEY_CONVERT_PENDING", conversionId, "deposit " + serialId);
                    return done(Status.PENDING, "Your deposit is still finishing. It completes within a few "
                            + "minutes, or when you next join.");
                }
                if (!taken) {
                    return releaseAndAbort(uuid, serialId, conversionId, "the key left the hand before it was taken")
                            .thenApply(ignored -> Result.of(Status.NO_KEY,
                                    "The key left your hand before it was deposited; nothing changed."));
                }
                return commitDeposit(uuid, player.getUsername(), conversionId, keyId, serialId);
            });
        });
    }

    /**
     * The commit point (serial CONVERTED, or the batch hold spent), then the
     * keyed credit. Recovery repeats it safely.
     */
    private CompletableFuture<Result> commitDeposit(UUID uuid, String name, String conversionId, String keyId,
            String serialId) {
        AtomicInteger uses = new AtomicInteger(-1);
        if (KeyBatch.isBatch(serialId)) {
            return storage.updateBatch(serialId, batch -> {
                int spent = StackRules.redeem(batch, conversionId);
                if (spent > 0) {
                    uses.set(spent);
                    return batch;
                }
                return null;
            }).thenCompose(batch -> uses.get() < 0
                    ? refusedCommit(uuid, conversionId, serialId)
                    : credit(uuid, name, conversionId, keyId, serialId, uses.get()));
        }
        return storage.updateSerial(serialId, serial -> {
            if (ConversionRules.convert(serial, conversionId)) {
                uses.set(serial.usesLeft);
                return serial;
            }
            if (ConversionRules.convertedBy(serial, conversionId)) {
                uses.set(serial.usesLeft);
            }
            return null;
        }).thenCompose(serial -> uses.get() < 0
                ? refusedCommit(uuid, conversionId, serialId)
                : credit(uuid, name, conversionId, keyId, serialId, uses.get()));
    }

    /** The item was taken but the ledger refused the commit: a revoke in between wins. */
    private CompletableFuture<Result> refusedCommit(UUID uuid, String conversionId, String serialId) {
        audit.record("server", "KEY_CONVERT_ANOMALY", conversionId, "deposit " + serialId
                + ": the key was taken but its ledger entry refused the deposit (revoked?)");
        return abort(uuid, conversionId, "the ledger refused the deposit")
                .thenApply(ignored -> Result.of(Status.REFUSED,
                        "That key was revoked while it was being deposited; nothing was credited."));
    }

    private CompletableFuture<Result> credit(UUID uuid, String name, String conversionId, String keyId,
            String serialId, int uses) {
        AtomicBoolean credited = new AtomicBoolean();
        return storage.updatePlayer(uuid, state -> {
            credited.set(ConversionRules.credit(state, conversionId, keyId, uses, System.currentTimeMillis()));
            return credited.get() ? state : null;
        }).thenCompose(state -> {
            int balance = state == null ? 0 : state.virtualKeys.getOrDefault(keyId, 0);
            if (!credited.get()) {
                return done(Status.DUPLICATE, "That deposit was already credited.");
            }
            audit.record(uuid.toString(), "KEY_DEPOSIT", serialId, keyId + " +" + uses + " virtual (" + conversionId + ")");
            KeyLog.Entry entry = KeyService.logEntry(KeyLog.DEPOSIT, name == null ? uuid.toString() : name,
                    uuid.toString(), name);
            entry.count = uses;
            entry.virtual = true;
            entry.serial = serialId;
            entry.source = ConversionRules.SOURCE;
            return keys.log(keyId, entry).thenApply(ignored -> new Result(Status.DEPOSITED, null, keyId, uses, 0, balance));
        });
    }

    // ----- Withdrawal -----------------------------------------------------------

    /** Turns {@code count} of {@code player}'s virtual {@code keyId} keys into key items. */
    public CompletableFuture<Result> withdraw(PlayerRef player, String keyId, int count, String requestId) {
        return lanes.run(player.getUuid(), () -> withdrawInLane(player, keyId, count, requestId)).exceptionally(error -> {
            core.log(Level.SEVERE, "[lootbox] withdrawal by " + player.getUsername() + " failed: " + error);
            return Result.of(Status.ERROR, "Something went wrong; see /lootbox pending, or ask staff.");
        });
    }

    private CompletableFuture<Result> withdrawInLane(PlayerRef player, String keyId, int count, String requestId) {
        if (!keys.config().keys.allowConversion) {
            return done(Status.REFUSED, "Converting keys is turned off on this server.");
        }
        KeyDraft.Published definition = registry.key(keyId).orElse(null);
        if (definition == null) {
            return done(Status.REFUSED, "There is no key called " + keyId + ".");
        }
        UUID uuid = player.getUuid();
        int max = Math.max(1, keys.config().keys.maxGrantPerCommand);
        return storage.loadPlayer(uuid).thenCompose(state -> {
            if (ConversionRules.earlier(state, requestId) != null) {
                return done(Status.DUPLICATE, "That withdrawal was already handled.");
            }
            if (ConversionRules.busy(state)) {
                return recoverInLane(player).thenApply(ignored -> Result.of(Status.BUSY,
                        "Your last opening or conversion was still finishing. Try again in a moment."));
            }
            String problem = ConversionRules.withdrawProblem(state.virtualKeys.getOrDefault(keyId, 0), count, max);
            if (problem != null) {
                return done(Status.NO_KEY, "You can't withdraw that: " + problem + ".");
            }
            String conversionId = draw.id();
            List<String> serials = new ArrayList<>();
            if (definition.spec.stackable) {
                // One batch of `count` items (P2-K).
                serials.add(KeyBatch.PREFIX + draw.id());
            } else {
                for (int i = 0; i < count; i++) {
                    serials.add(draw.id());
                }
            }
            AtomicReference<ConversionRules.Debit> debit = new AtomicReference<>();
            long now = System.currentTimeMillis();
            return storage.updatePlayer(uuid, fresh -> {
                debit.set(ConversionRules.debit(fresh, conversionId, requestId, keyId, count, serials, now));
                return debit.get() == ConversionRules.Debit.DEBITED ? fresh : null;
            }).thenCompose(debited -> {
                if (debit.get() != ConversionRules.Debit.DEBITED) {
                    return done(debit.get() == ConversionRules.Debit.ALREADY ? Status.DUPLICATE : Status.NO_KEY,
                            "You don't have " + count + " virtual " + keyId + " key(s) to withdraw.");
                }
                int balance = debited.virtualKeys.getOrDefault(keyId, 0);
                audit.record(uuid.toString(), "KEY_WITHDRAW", conversionId, keyId + " x" + count + " -> "
                        + String.join(",", serials));
                KeyLog.Entry entry = KeyService.logEntry(KeyLog.WITHDRAW, player.getUsername(), uuid.toString(),
                        player.getUsername());
                entry.count = count;
                entry.source = ConversionRules.SOURCE;
                return keys.log(keyId, entry)
                        .thenCompose(ignored -> deliver(player, debited.conversion(conversionId)))
                        .thenApply(pending -> pending < 0
                                ? new Result(Status.PENDING, "Your keys are on their way: they arrive within a few "
                                        + "minutes, or when you next join.", keyId, count, 0, balance)
                                : new Result(Status.WITHDRAWN, null, keyId, count, pending, balance));
            });
        });
    }

    /**
     * Issues the withdrawal's serials (only those not written yet), gives the
     * items, puts what did not fit in the inbox and settles the conversion.
     *
     * @return keys that went to the inbox, or {@code -1} when the world did not
     *         answer and recovery finishes the delivery
     */
    private CompletableFuture<Integer> deliver(PlayerRef player, Conversion conversion) {
        UUID uuid = player.getUuid();
        return issue(uuid, player.getUsername(), conversion).thenCompose(stacks -> {
            if (stacks == null) {
                return CompletableFuture.completedFuture(0);
            }
            return give(player, stacks).thenCompose(left -> {
                if (left == null) {
                    audit.record(uuid.toString(), "KEY_CONVERT_PENDING", conversion.conversionId, "withdraw");
                    return CompletableFuture.completedFuture(-1);
                }
                return keys.toInbox(uuid, left, conversion.conversionId).thenCompose(pending -> settle(uuid,
                        conversion.conversionId).thenApply(ignored -> pending));
            });
        });
    }

    /** Whether the withdrawal issues one batch of stackable keys (P2-K) rather than serials. */
    private static boolean batchWithdrawal(Conversion conversion) {
        return conversion.serials.size() == 1 && KeyBatch.isBatch(conversion.serials.get(0));
    }

    /**
     * Writes each serial of {@code conversion} unless it exists (a retry never
     * resets a key that was spent meanwhile) and builds its item from the stored
     * entry; for a batch, the batch and one stack of its items. {@code null} when
     * the key definition is gone, which leaves the conversion for a later pass.
     */
    private CompletableFuture<List<ItemStack>> issue(UUID owner, String actor, Conversion conversion) {
        KeyDraft.Published definition = registry.key(conversion.keyId).orElse(null);
        if (definition == null) {
            core.log(Level.WARNING, "[lootbox] withdrawal " + conversion.conversionId + " waits: key "
                    + conversion.keyId + " is not loaded");
            return CompletableFuture.completedFuture(null);
        }
        long now = System.currentTimeMillis();
        if (batchWithdrawal(conversion)) {
            KeyBatch fresh = StackRules.newBatch(conversion.serials.get(0), conversion.keyId, definition.version,
                    owner.toString(), conversion.count, ConversionRules.SOURCE, actor, now, 0);
            return storage.updateBatch(fresh.batch, existing -> existing == null ? fresh : null).thenApply(stored -> {
                KeyBatch batch = stored == null ? fresh : stored;
                return List.of(KeyItemFactory.create(definition, KeyMarker.signed(batch.keyId, batch.defVersion,
                        batch.batch, KeyService.SECRET_ID, keys.secret()), conversion.count));
            });
        }
        List<CompletableFuture<ItemStack>> stacks = new ArrayList<>();
        for (String serialId : conversion.serials) {
            KeySerial fresh = ConversionRules.withdrawnSerial(serialId, conversion.keyId, definition.version,
                    owner.toString(), actor, now);
            stacks.add(storage.updateSerial(serialId, existing -> existing == null ? fresh : null)
                    .thenApply(stored -> {
                        KeySerial entry = stored == null ? fresh : stored;
                        return KeyItemFactory.create(definition, KeyMarker.signed(entry.keyId, entry.defVersion,
                                entry.serial, KeyService.SECRET_ID, keys.secret()));
                    }));
        }
        return CompletableFuture.allOf(stacks.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> stacks.stream().map(CompletableFuture::join).toList());
    }

    private CompletableFuture<Void> settle(UUID uuid, String conversionId) {
        return storage.updatePlayer(uuid, state -> ConversionRules.settle(state, conversionId) ? state : null)
                .thenApply(state -> null);
    }

    // ----- Recovery -------------------------------------------------------------

    /**
     * Finishes the player's unfinished conversions. Called inside the player's
     * lane by {@link OpeningService#recover} (on join and in the 5-minute sweep).
     */
    CompletableFuture<Void> recoverInLane(PlayerRef player) {
        UUID uuid = player.getUuid();
        return storage.loadPlayer(uuid).thenCompose(state -> {
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            for (Conversion conversion : ConversionRules.unfinished(state)) {
                chain = chain.thenCompose(ignored -> Conversion.DEPOSIT.equals(conversion.kind)
                        ? recoverDeposit(player, conversion)
                        : recoverWithdrawal(player, conversion));
            }
            return chain;
        });
    }

    private CompletableFuture<Void> recoverDeposit(PlayerRef player, Conversion conversion) {
        UUID uuid = player.getUuid();
        String id = conversion.conversionId;
        if (conversion.serial == null) {
            return abort(uuid, id, "recovered: no serial recorded");
        }
        if (KeyBatch.isBatch(conversion.serial)) {
            return recoverBatchDeposit(player, conversion);
        }
        return storage.loadSerial(conversion.serial).thenCompose(serial -> keyHeld(player, conversion.serial)
                .thenCompose(held -> {
                    ConversionRules.DepositAction action = ConversionRules.decideDeposit(id, serial, held);
                    if (action != ConversionRules.DepositAction.WAIT) {
                        audit.record("server", "KEY_CONVERT_RECOVER", id, "deposit " + action.name());
                    }
                    return switch (action) {
                        case ABORT, ABORT_FOREIGN -> abort(uuid, id, "recovered: " + action.name());
                        case RELEASE_ABORT -> releaseAndAbort(uuid, conversion.serial, id, "recovered: key kept");
                        case CONVERT_CREDIT -> commitDeposit(uuid, player.getUsername(), id, conversion.keyId,
                                conversion.serial).thenApply(result -> (Void) null);
                        case CREDIT -> credit(uuid, player.getUsername(), id, conversion.keyId, conversion.serial,
                                serial.usesLeft).thenApply(result -> (Void) null);
                        case CREDIT_CLEAN_UP -> removeDead(player, conversion.serial)
                                .thenCompose(ignored -> credit(uuid, player.getUsername(), id, conversion.keyId,
                                        conversion.serial, serial.usesLeft))
                                .thenApply(result -> (Void) null);
                        case WAIT -> CompletableFuture.completedFuture(null);
                    };
                }));
    }

    /**
     * A deposit of batch items left unfinished (P2-K). Recovery prefers abort
     * ({@link StackRules#decideDeposit}): a hold is released, and the items
     * that may have been taken are given back; a spent hold is credited once.
     */
    private CompletableFuture<Void> recoverBatchDeposit(PlayerRef player, Conversion conversion) {
        UUID uuid = player.getUuid();
        String id = conversion.conversionId;
        return storage.loadBatch(conversion.serial).thenCompose(batch -> countHeld(player, conversion.serial)
                .thenCompose(held -> {
                    StackRules.Recovery action = StackRules.decideDeposit(id, batch, held);
                    if (action != StackRules.Recovery.WAIT) {
                        audit.record("server", "KEY_CONVERT_RECOVER", id, "batch deposit " + action.name());
                    }
                    return switch (action) {
                        case ABORT -> abort(uuid, id, "recovered: batch never held");
                        case RELEASE -> releaseAndAbort(uuid, conversion.serial, id, "recovered: keys kept");
                        case RELEASE_REGIVE -> {
                            int give = StackRules.regive(batch.reservation(id), held);
                            yield releaseAndAbort(uuid, conversion.serial, id, "recovered: keys given back")
                                    .thenCompose(ignored -> keys.regive(player, batch, give, id));
                        }
                        case COMMITTED -> credit(uuid, player.getUsername(), id, conversion.keyId, conversion.serial,
                                conversion.count).thenApply(result -> (Void) null);
                        case WAIT, NONE -> CompletableFuture.completedFuture(null);
                    };
                }));
    }

    private CompletableFuture<Void> recoverWithdrawal(PlayerRef player, Conversion conversion) {
        UUID uuid = player.getUuid();
        return issue(uuid, player.getUsername(), conversion).thenCompose(stacks -> {
            if (stacks == null) {
                return CompletableFuture.completedFuture(null);
            }
            if (batchWithdrawal(conversion)) {
                return recoverBatchWithdrawal(player, conversion);
            }
            return storage.loadPending(uuid).thenCompose(inbox -> {
                List<CompletableFuture<ConversionRules.Delivery>> decisions = new ArrayList<>();
                for (String serialId : conversion.serials) {
                    boolean inInbox = inbox.contains(ConversionRules.inboxId(serialId));
                    decisions.add(storage.loadSerial(serialId).thenCombine(keyHeld(player, serialId),
                            (serial, held) -> ConversionRules.decideWithdrawn(serial, held, inInbox)));
                }
                return CompletableFuture.allOf(decisions.toArray(CompletableFuture[]::new)).thenCompose(ignored -> {
                    List<ItemStack> missing = new ArrayList<>();
                    boolean waiting = false;
                    for (int i = 0; i < decisions.size(); i++) {
                        ConversionRules.Delivery delivery = decisions.get(i).join();
                        waiting |= delivery == ConversionRules.Delivery.WAIT;
                        if (delivery == ConversionRules.Delivery.TO_INBOX) {
                            missing.add(stacks.get(i));
                        }
                    }
                    if (waiting) {
                        return CompletableFuture.completedFuture(null);
                    }
                    audit.record("server", "KEY_CONVERT_RECOVER", conversion.conversionId, "withdraw "
                            + missing.size() + " to inbox");
                    return keys.toInbox(uuid, missing).thenCompose(n -> settle(uuid, conversion.conversionId));
                });
            });
        });
    }

    /**
     * A withdrawn batch left unfinished (P2-K): files in the inbox the batch's
     * unspent units that are neither in the inventory nor in the inbox
     * ({@link StackRules#missingWithdrawn}), once, then settles.
     */
    private CompletableFuture<Void> recoverBatchWithdrawal(PlayerRef player, Conversion conversion) {
        UUID uuid = player.getUuid();
        String batchId = conversion.serials.get(0);
        return storage.loadBatch(batchId).thenCompose(batch -> countHeld(player, batchId)
                .thenCombine(storage.loadPending(uuid), (held, inbox) -> {
                    int filed = 0;
                    for (PendingInbox.PendingReward item : inbox.items) {
                        if (item.fulfillmentId != null && item.fulfillmentId.startsWith(ConversionRules.inboxId(batchId) + "#")) {
                            filed += item.quantity;
                        }
                    }
                    return StackRules.missingWithdrawn(batch, held, filed);
                }).thenCompose(missing -> {
                    if (missing < 0) {
                        return CompletableFuture.completedFuture(null);
                    }
                    audit.record("server", "KEY_CONVERT_RECOVER", conversion.conversionId, "batch withdraw "
                            + missing + " to inbox");
                    KeyDraft.Published definition = registry.key(conversion.keyId).orElse(null);
                    if (missing == 0 || definition == null) {
                        return settle(uuid, conversion.conversionId);
                    }
                    ItemStack stack = KeyItemFactory.create(definition, KeyMarker.signed(batch.keyId, batch.defVersion,
                            batch.batch, KeyService.SECRET_ID, keys.secret()), missing);
                    return keys.toInbox(uuid, List.of(stack), conversion.conversionId + "#recovered")
                            .thenCompose(n -> settle(uuid, conversion.conversionId));
                }));
    }

    // ----- Documents and the world ----------------------------------------------

    private CompletableFuture<Void> abort(UUID uuid, String conversionId, String note) {
        audit.record(uuid.toString(), "KEY_CONVERT_ABORT", conversionId, note);
        return storage.updatePlayer(uuid, state -> ConversionRules.abort(state, conversionId, note) ? state : null)
                .thenApply(state -> null);
    }

    private CompletableFuture<Void> releaseAndAbort(UUID uuid, String serialId, String conversionId, String note) {
        CompletableFuture<?> released = KeyBatch.isBatch(serialId)
                ? storage.updateBatch(serialId, batch -> StackRules.release(batch, conversionId) ? batch : null)
                : storage.updateSerial(serialId, serial -> ConversionRules.release(serial, conversionId) ? serial : null);
        return released.thenCompose(ignored -> abort(uuid, conversionId, note));
    }

    /** The held key stack and how many items with its serial (or batch) the player holds in all. */
    private record Hand(InventoryAdapter.KeyStack stack, int total) {
    }

    /** The stack in the player's active hotbar slot if it carries a key marker, else {@code null}. */
    private CompletableFuture<Hand> heldKey(PlayerRef player) {
        CompletableFuture<Hand> held = new CompletableFuture<>();
        boolean dispatched = core.platform().runOnEntityThread(player, (store, entity, world) -> {
            try {
                InventoryAdapter.KeyStack stack = InventoryAdapter.heldKey(store, entity);
                held.complete(stack == null ? null
                        : new Hand(stack, InventoryAdapter.countSerial(store, entity, stack.marker().serial())));
            } catch (Throwable t) {
                held.complete(null);
            }
        });
        if (!dispatched) {
            held.complete(null);
        }
        return held.completeOnTimeout(null, ConversionRules.HOP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
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
        return held.completeOnTimeout(null, ConversionRules.HOP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Removes the held key: one item with its serial, or exactly {@code count}
     * items of a held batch stack (P2-K) when {@code count > 0}.
     *
     * @return {@code true} when removed, {@code false} when it is gone (nothing
     *         happened), {@code null} when the world did not answer or failed midway
     */
    private CompletableFuture<Boolean> take(PlayerRef player, InventoryAdapter.KeyStack held, int count) {
        CompletableFuture<Boolean> taken = new CompletableFuture<>();
        boolean dispatched = core.platform().runOnEntityThread(player, (store, entity, world) -> {
            try {
                taken.complete(count > 0 ? InventoryAdapter.takeCount(store, entity, held, count)
                        : InventoryAdapter.removeOne(store, entity, held));
            } catch (Throwable t) {
                taken.complete(null);
            }
        });
        if (!dispatched) {
            taken.complete(false);
        }
        return taken.completeOnTimeout(null, ConversionRules.HOP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    /** @return what did not fit, or {@code null} when the world did not answer */
    private CompletableFuture<List<ItemStack>> give(PlayerRef player, List<ItemStack> stacks) {
        CompletableFuture<List<ItemStack>> leftovers = new CompletableFuture<>();
        boolean dispatched = core.platform().runOnEntityThread(player, (store, entity, world) -> {
            try {
                List<ItemStack> left = new ArrayList<>();
                for (ItemStack stack : stacks) {
                    ItemStack remainder = InventoryAdapter.give(store, entity, stack);
                    if (remainder != null) {
                        left.add(remainder);
                    }
                }
                leftovers.complete(left);
            } catch (Throwable t) {
                leftovers.complete(null);
            }
        });
        if (!dispatched) {
            leftovers.complete(stacks);
        }
        return leftovers.completeOnTimeout(null, ConversionRules.HOP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    /** Whether a stack with {@code serial} is in the inventory; {@code null} when it could not be read. */
    private CompletableFuture<Boolean> keyHeld(PlayerRef player, String serial) {
        CompletableFuture<Boolean> held = new CompletableFuture<>();
        boolean dispatched = core.platform().runOnEntityThread(player, (store, entity, world) -> {
            try {
                held.complete(!InventoryAdapter.findSerial(store, entity, serial).isEmpty());
            } catch (Throwable t) {
                held.complete(null);
            }
        });
        if (!dispatched) {
            held.complete(null);
        }
        return held.completeOnTimeout(null, ConversionRules.HOP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private CompletableFuture<Void> removeDead(PlayerRef player, String serial) {
        CompletableFuture<Void> removed = new CompletableFuture<>();
        boolean dispatched = core.platform().runOnEntityThread(player, (store, entity, world) -> {
            try {
                InventoryAdapter.removeAll(store, entity, serial);
            } finally {
                removed.complete(null);
            }
        });
        if (!dispatched) {
            removed.complete(null);
        }
        return removed.completeOnTimeout(null, ConversionRules.HOP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    /** A valid marker on a spent, revoked or unknown serial is a copied or recycled key: remove it, as openings do. */
    private void rejectDead(PlayerRef player, String serial, String problem) {
        if (!"already used".equals(problem) && !"revoked key".equals(problem) && !"unknown key".equals(problem)) {
            return;
        }
        audit.record(player.getUuid().toString(), "KEY_REJECTED", serial, "deposit: " + problem);
        core.platform().runOnEntityThread(player, (store, entity, world) -> InventoryAdapter.removeAll(store, entity,
                serial));
    }

    private static String refusal(String problem) {
        return switch (problem) {
            case "expiring" -> "Keys that expire can't be deposited: virtual keys never expire.";
            case "expired" -> "That key has expired.";
            case "no uses left" -> "That key has no uses left.";
            case "already used", "revoked key", "unknown key" -> "That key no longer works, so it was removed.";
            case "reserved" -> "Some of these keys are being used right now. Try again in a moment.";
            default -> problem.startsWith("only ")
                    ? "Only " + problem.substring(5, problem.length() - 5).trim() + " of these keys still work: split the "
                            + "stack and deposit that many."
                    : "That key can't be deposited (" + problem.toLowerCase(Locale.ROOT) + ").";
        };
    }

    private static CompletableFuture<Result> done(Status status, String message) {
        return CompletableFuture.completedFuture(Result.of(status, message));
    }
}
