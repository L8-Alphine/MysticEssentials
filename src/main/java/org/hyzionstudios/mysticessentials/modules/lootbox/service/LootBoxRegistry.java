package org.hyzionstudios.mysticessentials.modules.lootbox.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.util.Json;
import org.hyzionstudios.mysticessentials.modules.lootbox.api.event.LootBoxRevisionPublishedEvent;
import org.hyzionstudios.mysticessentials.modules.lootbox.config.LootBoxConfig;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.KeyDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxRevision;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.OddsEngine;
import org.hyzionstudios.mysticessentials.modules.lootbox.storage.LootBoxStorage;
import org.hyzionstudios.mysticessentials.modules.lootbox.validate.DraftValidator;

import com.hypixel.hytale.server.core.asset.type.item.config.Item;

/**
 * Drafts on disk, published revisions in storage (§4.1). Drafts are the files
 * operators edit; publishing validates one, freezes it into a new immutable
 * revision and switches the pool's active revision in one write. Openings keep
 * pointing at the revision they drew from.
 *
 * <p>The active revisions and key definitions are cached in memory: openings and
 * previews read them on world threads and must not wait for storage. So are the
 * drafts, read and written through a {@link DraftStore} per folder; a save,
 * create, clone or archive updates the cached draft as it reaches the disk.</p>
 */
public final class LootBoxRegistry {

    /** Outcome of a publish, rollback or key publish. */
    public record PublishResult(boolean ok, int revision, String hash, List<String> problems, boolean unchanged) {

        static PublishResult failed(List<String> problems) {
            return new PublishResult(false, 0, "", problems, false);
        }

        static PublishResult failed(String problem) {
            return failed(List.of(problem));
        }
    }

    private final MysticCore core;
    private final LootBoxStorage storage;
    private final AuditService audit;
    private final IntegrationRegistry integrations;
    private volatile LootBoxConfig config;

    private final Map<String, LootBoxRevision> active = new ConcurrentHashMap<>();
    /** Published revisions waiting for their start (P3-WP5). */
    private final Map<String, LootBoxRevision> scheduled = new ConcurrentHashMap<>();
    /** Announces a changed pool to the other servers (P4-WP3); {@code null} on a single server. */
    private volatile java.util.function.Consumer<String> changes;
    private final Map<String, LootBoxRevision.State> states = new ConcurrentHashMap<>();
    private final Map<String, KeyDraft.Published> keys = new ConcurrentHashMap<>();
    private volatile Map<String, LootBoxDraft> drafts = Map.of();
    private volatile Map<String, KeyDraft> keyDrafts = Map.of();
    private volatile List<String> poolErrors = List.of();
    private volatile List<String> keyErrors = List.of();
    private final DraftStore<LootBoxDraft> poolStore;
    private final DraftStore<KeyDraft> keyStore;

    public LootBoxRegistry(MysticCore core, LootBoxStorage storage, AuditService audit,
            IntegrationRegistry integrations, LootBoxConfig config) {
        this.core = core;
        this.storage = storage;
        this.audit = audit;
        this.integrations = integrations;
        this.config = config;
        DraftLifecycle<LootBoxDraft> pools = DraftLifecycle.pools(() -> environment(true), id -> {
            LootBoxRevision revision = active.get(id);
            return revision == null ? null : revision.contentHash;
        }, this::isDisabled);
        DraftLifecycle<KeyDraft> keyLifecycle = DraftLifecycle.keys(LootBoxRegistry::itemExists, id -> {
            KeyDraft.Published published = keys.get(id);
            return published == null ? null : published.spec;
        });
        // The listeners run under each store's lock, so the cached maps follow the disk in order.
        this.poolStore = new DraftStore<>(DraftStore.POOLS, draftsDir(), pools, audit::record,
                new DraftStore.Listener<>() {
                    @Override
                    public void changed(String id, LootBoxDraft draft) {
                        drafts = withDraft(drafts, id, draft);
                    }

                    @Override
                    public void listed(DraftStore.Listing<LootBoxDraft> listing) {
                        drafts = draftsOf(listing);
                        poolErrors = listing.errors();
                    }
                }, ForkJoinPool.commonPool());
        this.keyStore = new DraftStore<>(DraftStore.KEYS, keysDir(), keyLifecycle, audit::record,
                new DraftStore.Listener<>() {
                    @Override
                    public void changed(String id, KeyDraft draft) {
                        keyDrafts = withDraft(keyDrafts, id, draft);
                    }

                    @Override
                    public void listed(DraftStore.Listing<KeyDraft> listing) {
                        keyDrafts = draftsOf(listing);
                        keyErrors = listing.errors();
                    }
                }, ForkJoinPool.commonPool());
    }

    public void updateConfig(LootBoxConfig config) {
        this.config = config;
    }

    public Path draftsDir() {
        return core.paths().moduleConfigDir("lootbox").resolve("lootboxes");
    }

    public Path keysDir() {
        return core.paths().moduleConfigDir("lootbox").resolve("keys");
    }

    // ----- Loading ------------------------------------------------------------

    /** Writes the example drafts the first time the folders are created. */
    public void writeExamplesIfMissing() {
        try {
            if (!Files.isDirectory(draftsDir())) {
                Files.createDirectories(draftsDir());
                Json.writeFile(draftsDir().resolve("example_supply.json"), Examples.pool());
            }
            if (!Files.isDirectory(keysDir())) {
                Files.createDirectories(keysDir());
                Json.writeFile(keysDir().resolve("example_key.json"), Examples.key());
            }
        } catch (IOException e) {
            core.log(Level.WARNING, "[lootbox] could not write example drafts: " + e.getMessage());
        }
    }

    /**
     * Re-reads every draft file and waits for it, so callers can report the counts
     * straight after. Never touches published revisions. Code on a world thread uses
     * {@link #refreshDrafts()} instead.
     */
    public void reloadDrafts() {
        try {
            refreshDrafts().join();
        } catch (RuntimeException e) {
            core.log(Level.WARNING, "[lootbox] could not re-read the drafts: " + e);
        }
    }

    /** Re-reads every draft file off the calling thread and logs the files that could not be used. */
    public CompletableFuture<Void> refreshDrafts() {
        return poolStore.list().thenAcceptBoth(keyStore.list(), (pools, keyFiles) -> {
            pools.errors().forEach(error -> core.log(Level.WARNING, "[lootbox] draft " + error));
            keyFiles.errors().forEach(error -> core.log(Level.WARNING, "[lootbox] draft " + error));
        });
    }

    /** Loads every published pool and key into memory. */
    public CompletableFuture<Void> loadPublished() {
        CompletableFuture<Void> pools = storage.listStates().thenCompose(ids -> {
            List<CompletableFuture<Void>> loads = new ArrayList<>();
            for (String id : ids) {
                loads.add(storage.loadState(id).thenCompose(state -> {
                    if (state == null) {
                        return CompletableFuture.completedFuture(null);
                    }
                    states.put(id, state);
                    if (state.scheduledRevision > 0) {
                        storage.loadRevision(id, state.scheduledRevision).thenAccept(revision -> {
                            if (revision != null) {
                                scheduled.put(id, revision);
                            }
                        });
                    }
                    if (state.activeRevision <= 0) {
                        return CompletableFuture.completedFuture(null);
                    }
                    return storage.loadRevision(id, state.activeRevision).thenAccept(revision -> {
                        if (revision != null) {
                            active.put(id, revision);
                        } else {
                            core.log(Level.SEVERE, "[lootbox] pool '" + id + "' points at missing revision "
                                    + state.activeRevision);
                        }
                    });
                }));
            }
            return CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new));
        });
        CompletableFuture<Void> keyDefs = storage.listKeyDefinitions().thenCompose(ids -> {
            List<CompletableFuture<Void>> loads = new ArrayList<>();
            for (String storageKey : ids) {
                String keyId = storageKey.replaceFirst("\\.", ":");
                loads.add(storage.loadKeyDefinition(keyId).thenAccept(published -> {
                    if (published != null && published.spec != null) {
                        keys.put(published.spec.id, published);
                    }
                }));
            }
            return CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new));
        });
        return CompletableFuture.allOf(pools, keyDefs);
    }

    // ----- Queries ------------------------------------------------------------

    public Map<String, LootBoxDraft> drafts() {
        return drafts;
    }

    public Map<String, KeyDraft> keyDrafts() {
        return keyDrafts;
    }

    /** Draft files the last full read could not use. */
    public List<String> draftErrors() {
        List<String> errors = new ArrayList<>(poolErrors);
        errors.addAll(keyErrors);
        return errors;
    }

    /** Pool drafts on disk: load, save, create, clone and archive (the Phase 2 editor). */
    public DraftStore<LootBoxDraft> poolStore() {
        return poolStore;
    }

    /** Key drafts on disk. */
    public DraftStore<KeyDraft> keyStore() {
        return keyStore;
    }

    /** A pool draft's lifecycle state, from the cached draft with no file work; empty when it has no draft. */
    public Optional<DraftLifecycle.Status> status(String lootboxId) {
        LootBoxDraft draft = lootboxId == null ? null : drafts.get(lootboxId);
        return Optional.ofNullable(draft == null ? null : poolStore.status(draft));
    }

    /** The registered reward and condition providers (P3-WP1). */
    public IntegrationRegistry integrations() {
        return integrations;
    }

    public Optional<LootBoxRevision> active(String lootboxId) {
        return Optional.ofNullable(lootboxId == null ? null : active.get(lootboxId));
    }

    public boolean isDisabled(String lootboxId) {
        LootBoxRevision.State state = lootboxId == null ? null : states.get(lootboxId);
        return state != null && state.disabled;
    }

    public Optional<LootBoxRevision.State> state(String lootboxId) {
        return Optional.ofNullable(states.get(lootboxId));
    }

    /** Every published pool, sorted by id. */
    public List<LootBoxRevision> publishedPools() {
        List<LootBoxRevision> pools = new ArrayList<>(active.values());
        pools.sort((a, b) -> a.lootboxId.compareTo(b.lootboxId));
        return pools;
    }

    public Optional<KeyDraft.Published> key(String keyId) {
        return Optional.ofNullable(keyId == null ? null : keys.get(keyId));
    }

    public Collection<KeyDraft.Published> keys() {
        return keys.values();
    }

    public static boolean itemExists(String itemId) {
        try {
            return itemId != null && !itemId.isBlank() && Item.getAssetMap().getAsset(itemId) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    // ----- Publishing ---------------------------------------------------------

    public CompletableFuture<PublishResult> publish(String lootboxId, String actor, boolean mayPublishCommands) {
        LootBoxDraft draft = drafts.get(lootboxId);
        if (draft == null) {
            return CompletableFuture.completedFuture(PublishResult.failed(
                    "no draft named '" + lootboxId + "' in " + draftsDir().getFileName()));
        }
        LootBoxDraft frozen = DraftLifecycle.freeze(draft, LootBoxDraft.class);
        List<String> problems = DraftValidator.validate(frozen, environment(mayPublishCommands));
        if (!problems.isEmpty()) {
            return CompletableFuture.completedFuture(PublishResult.failed(problems));
        }
        List<Long> weights = OddsEngine.resolveWeights(frozen);
        List<LootBoxRevision.BonusWeights> bonusWeights = OddsEngine.resolveBonus(frozen);
        String contentHash = DraftLifecycle.poolContentHash(frozen, weights);
        LootBoxRevision current = active.get(lootboxId);
        if (current != null && contentHash.equals(current.contentHash)) {
            return CompletableFuture.completedFuture(
                    new PublishResult(true, current.revision, contentHash, List.of(), true));
        }
        LootBoxRevision waiting = scheduled.get(lootboxId);
        if (waiting != null && contentHash.equals(waiting.contentHash)) {
            return CompletableFuture.completedFuture(
                    new PublishResult(true, waiting.revision, contentHash, List.of(), true));
        }
        long now = System.currentTimeMillis();
        long startsAt = ScheduleRules.startsLater(frozen, now) ? ScheduleRules.instant(frozen.startsAt) : 0;
        return freeze(lootboxId, frozen, weights, bonusWeights, contentHash, actor, null, startsAt);
    }

    /** Republishes revision {@code from}'s content as a new revision, live now. */
    public CompletableFuture<PublishResult> rollback(String lootboxId, int from, String actor) {
        return storage.loadRevision(lootboxId, from).thenCompose(old -> {
            if (old == null) {
                return CompletableFuture.completedFuture(
                        PublishResult.failed("revision " + from + " of '" + lootboxId + "' does not exist"));
            }
            return freeze(lootboxId, old.spec, old.weights, old.bonusWeights, old.contentHash, actor, from, 0);
        });
    }

    /**
     * Freezes {@code spec} into a new immutable revision. With {@code startsAt}
     * (epoch millis, &gt; 0) it is scheduled rather than made live (P3-WP5); the
     * schedule sweep switches to it then.
     */
    private CompletableFuture<PublishResult> freeze(String lootboxId, LootBoxDraft spec, List<Long> weights,
            List<LootBoxRevision.BonusWeights> bonusWeights, String contentHash, String actor,
            Integer rolledBackFrom, long startsAt) {
        AtomicInteger next = new AtomicInteger();
        return storage.updateState(lootboxId, state -> {
            LootBoxRevision.State s = state == null ? new LootBoxRevision.State() : state;
            s.lootboxId = lootboxId;
            s.latestRevision = s.latestRevision + 1;
            next.set(s.latestRevision);
            return s;
        }).thenCompose(reserved -> {
            LootBoxRevision revision = new LootBoxRevision();
            revision.lootboxId = lootboxId;
            revision.revision = next.get();
            revision.spec = spec;
            revision.weights = new ArrayList<>(weights);
            revision.bonusWeights = new ArrayList<>(bonusWeights == null ? List.of() : bonusWeights);
            revision.contentHash = contentHash;
            revision.publishedBy = actor;
            revision.publishedAt = System.currentTimeMillis();
            revision.rolledBackFrom = rolledBackFrom;
            return storage.saveRevision(revision).thenCompose(saved -> storage.updateState(lootboxId, state -> {
                LootBoxRevision.State s = state == null ? new LootBoxRevision.State() : state;
                if (startsAt > 0) {
                    s.scheduledRevision = revision.revision;
                    s.scheduledAt = startsAt;
                } else {
                    ScheduleRules.activate(s, revision, s.activeRevision, revision.publishedAt);
                }
                s.updatedBy = actor;
                s.updatedAt = revision.publishedAt;
                return s;
            })).thenApply(state -> {
                states.put(lootboxId, state);
                String hash = OddsEngine.table(revision, config.policyProfile).oddsId();
                if (startsAt > 0) {
                    scheduled.put(lootboxId, revision);
                    audit.record(actor, "SCHEDULE", lootboxId, "revision " + revision.revision + " starts "
                            + ScheduleRules.shown(startsAt) + " odds " + hash);
                    broadcast(lootboxId);
                    return new PublishResult(true, revision.revision, hash, List.of(), false);
                }
                active.put(lootboxId, revision);
                audit.record(actor, rolledBackFrom == null ? "PUBLISH" : "ROLLBACK", lootboxId,
                        "revision " + revision.revision + (rolledBackFrom == null ? "" : " from " + rolledBackFrom)
                                + " odds " + hash);
                core.getEventBus().publish(new LootBoxRevisionPublishedEvent(lootboxId, revision.revision,
                        contentHash, actor, rolledBackFrom));
                broadcast(lootboxId);
                return new PublishResult(true, revision.revision, hash, List.of(), false);
            });
        });
    }

    // ----- Schedule (P3-WP5) ----------------------------------------------------

    /** The scheduled (not yet live) revision of a pool, if any. */
    public Optional<LootBoxRevision> scheduled(String lootboxId) {
        return Optional.ofNullable(lootboxId == null ? null : scheduled.get(lootboxId));
    }

    /**
     * Performs whatever the pools' schedules make due at {@code now}: a scheduled
     * revision starting, time-limited odds ending (revert or close), a scheduled
     * rollback. Each action is claimed inside one update of the pool's state, so
     * when several servers sweep the same pools only one performs it; the others
     * refresh their caches from what it wrote.
     */
    public CompletableFuture<Void> runSchedule(long now) {
        List<CompletableFuture<Void>> runs = new ArrayList<>();
        for (Map.Entry<String, LootBoxRevision.State> entry : states.entrySet()) {
            ScheduleRules.Due due = ScheduleRules.due(entry.getValue(), now);
            if (due != ScheduleRules.Due.NONE) {
                runs.add(runDue(entry.getKey(), due, now).exceptionally(error -> {
                    core.log(Level.WARNING, "[lootbox] schedule of " + entry.getKey() + " failed: " + error);
                    return null;
                }));
            }
        }
        return CompletableFuture.allOf(runs.toArray(CompletableFuture[]::new));
    }

    private CompletableFuture<Void> runDue(String lootboxId, ScheduleRules.Due due, long now) {
        LootBoxRevision.State cached = states.get(lootboxId);
        CompletableFuture<LootBoxRevision> activating = due == ScheduleRules.Due.ACTIVATE
                ? storage.loadRevision(lootboxId, cached.scheduledRevision)
                : CompletableFuture.completedFuture(null);
        return activating.thenCompose(revision -> {
            AtomicReference<ScheduleRules.Claim> claimed = new AtomicReference<>();
            return storage.updateState(lootboxId, state -> {
                ScheduleRules.Claim claim = ScheduleRules.claim(state, due, revision, now);
                claimed.set(claim);
                if (claim == null) {
                    return null;
                }
                state.updatedBy = "schedule";
                state.updatedAt = now;
                return state;
            }).thenCompose(state -> {
                ScheduleRules.Claim claim = claimed.get();
                if (claim == null) {
                    // Another server acted first (or nothing is due any more): take what it wrote.
                    return refreshPool(lootboxId);
                }
                states.put(lootboxId, state);
                switch (claim.due()) {
                    case ACTIVATE -> {
                        active.put(lootboxId, revision);
                        scheduled.remove(lootboxId);
                        audit.record("schedule", "SCHEDULE_START", lootboxId, "revision " + revision.revision
                                + " odds " + OddsEngine.table(revision, config.policyProfile).oddsId());
                        core.getEventBus().publish(new LootBoxRevisionPublishedEvent(lootboxId, revision.revision,
                                revision.contentHash, "schedule", null));
                        broadcast(lootboxId);
                        return CompletableFuture.completedFuture(null);
                    }
                    case END_CLOSE -> {
                        audit.record("schedule", "SCHEDULE_END", lootboxId, "time-limited odds ended; pool closed");
                        broadcast(lootboxId);
                        return CompletableFuture.completedFuture(null);
                    }
                    case END_REVERT, ROLLBACK -> {
                        audit.record("schedule", claim.due() == ScheduleRules.Due.END_REVERT ? "SCHEDULE_END"
                                : "SCHEDULE_ROLLBACK", lootboxId, "republishing revision " + claim.revision());
                        return rollback(lootboxId, claim.revision(), "schedule").thenAccept(result -> {
                            if (!result.ok()) {
                                core.log(Level.SEVERE, "[lootbox] scheduled rollback of " + lootboxId
                                        + " to revision " + claim.revision() + " failed: " + result.problems());
                            }
                        });
                    }
                    default -> {
                        scheduled.remove(lootboxId);
                        core.log(Level.SEVERE, "[lootbox] scheduled revision " + claim.revision() + " of "
                                + lootboxId + " is missing; the schedule was dropped.");
                        broadcast(lootboxId);
                        return CompletableFuture.completedFuture(null);
                    }
                }
            });
        });
    }

    /** {@code /lootbox rollback <id> <rev> at <instant>}: republish {@code to} at {@code at}. */
    public CompletableFuture<Boolean> scheduleRollback(String lootboxId, int to, long at, String actor) {
        if (!states.containsKey(lootboxId)) {
            return CompletableFuture.completedFuture(false);
        }
        return storage.loadRevision(lootboxId, to).thenCompose(revision -> {
            if (revision == null) {
                return CompletableFuture.completedFuture(false);
            }
            return storage.updateState(lootboxId, state -> {
                if (state == null) {
                    return null;
                }
                state.rollbackTo = to;
                state.rollbackAt = at;
                state.updatedBy = actor;
                state.updatedAt = System.currentTimeMillis();
                return state;
            }).thenApply(state -> {
                if (state == null) {
                    return false;
                }
                states.put(lootboxId, state);
                audit.record(actor, "SCHEDULE", lootboxId, "rollback to revision " + to + " at "
                        + ScheduleRules.shown(at));
                broadcast(lootboxId);
                return true;
            });
        });
    }

    /** Drops a pool's scheduled revision and scheduled rollback; the live odds and their end stay. */
    public CompletableFuture<Boolean> cancelSchedule(String lootboxId, String actor) {
        if (!states.containsKey(lootboxId)) {
            return CompletableFuture.completedFuture(false);
        }
        return storage.updateState(lootboxId, state -> {
            if (state == null) {
                return null;
            }
            state.scheduledRevision = 0;
            state.scheduledAt = 0;
            state.rollbackTo = 0;
            state.rollbackAt = 0;
            state.updatedBy = actor;
            state.updatedAt = System.currentTimeMillis();
            return state;
        }).thenApply(state -> {
            if (state == null) {
                return false;
            }
            states.put(lootboxId, state);
            scheduled.remove(lootboxId);
            audit.record(actor, "SCHEDULE_CANCEL", lootboxId, "");
            broadcast(lootboxId);
            return true;
        });
    }

    /**
     * Re-reads one pool's state and active (and scheduled) revision from storage
     * into the caches: after another server changed it (P4-WP3), or after this
     * server lost a schedule claim to another one.
     */
    public CompletableFuture<Void> refreshPool(String lootboxId) {
        return storage.loadState(lootboxId).thenCompose(state -> {
            if (state == null) {
                states.remove(lootboxId);
                active.remove(lootboxId);
                scheduled.remove(lootboxId);
                return CompletableFuture.completedFuture(null);
            }
            states.put(lootboxId, state);
            CompletableFuture<Void> live = state.activeRevision <= 0 ? CompletableFuture.completedFuture(null)
                    : cachedOrLoad(lootboxId, state.activeRevision, active.get(lootboxId))
                            .thenAccept(revision -> {
                                if (revision != null) {
                                    active.put(lootboxId, revision);
                                }
                            });
            if (state.scheduledRevision <= 0) {
                scheduled.remove(lootboxId);
            }
            CompletableFuture<Void> waiting = state.scheduledRevision <= 0 ? CompletableFuture.completedFuture(null)
                    : cachedOrLoad(lootboxId, state.scheduledRevision, scheduled.get(lootboxId))
                            .thenAccept(revision -> {
                                if (revision != null) {
                                    scheduled.put(lootboxId, revision);
                                }
                            });
            return CompletableFuture.allOf(live, waiting);
        });
    }

    private CompletableFuture<LootBoxRevision> cachedOrLoad(String lootboxId, int revision, LootBoxRevision cached) {
        return cached != null && cached.revision == revision ? CompletableFuture.completedFuture(cached)
                : storage.loadRevision(lootboxId, revision);
    }

    /** Tells the other servers that this pool changed (P4-WP3); a no-op until a broadcaster is set. */
    private void broadcast(String lootboxId) {
        java.util.function.Consumer<String> sink = changes;
        if (sink != null) {
            try {
                sink.accept(lootboxId);
            } catch (RuntimeException e) {
                core.log(Level.WARNING, "[lootbox] could not announce the change of " + lootboxId + ": " + e);
            }
        }
    }

    /** Where pool changes are announced to other servers (P4-WP3), or {@code null}. */
    public void onPoolChange(java.util.function.Consumer<String> sink) {
        this.changes = sink;
    }

    public CompletableFuture<Boolean> setDisabled(String lootboxId, boolean disabled, String actor) {
        if (!states.containsKey(lootboxId)) {
            return CompletableFuture.completedFuture(false);
        }
        return storage.updateState(lootboxId, state -> {
            if (state == null) {
                return null;
            }
            state.disabled = disabled;
            state.updatedBy = actor;
            state.updatedAt = System.currentTimeMillis();
            return state;
        }).thenApply(state -> {
            if (state == null) {
                return false;
            }
            states.put(lootboxId, state);
            audit.record(actor, disabled ? "DISABLE" : "ENABLE", lootboxId, "");
            broadcast(lootboxId);
            return true;
        });
    }

    public CompletableFuture<PublishResult> publishKey(String rawKeyId, String actor) {
        String keyId = DraftValidator.normaliseKeyId(rawKeyId);
        KeyDraft draft = keyDrafts.get(keyId);
        if (draft == null) {
            return CompletableFuture.completedFuture(PublishResult.failed(
                    "no key draft with id '" + keyId + "' in " + keysDir().getFileName()));
        }
        List<String> problems = DraftValidator.validateKey(draft, LootBoxRegistry::itemExists);
        if (!problems.isEmpty()) {
            return CompletableFuture.completedFuture(PublishResult.failed(problems));
        }
        KeyDraft frozen = DraftLifecycle.freeze(draft, KeyDraft.class);
        KeyDraft.Published current = keys.get(keyId);
        if (current != null && DraftLifecycle.keySpecHash(current.spec).equals(DraftLifecycle.keySpecHash(frozen))) {
            return CompletableFuture.completedFuture(new PublishResult(true, current.version, "", List.of(), true));
        }
        return storage.updateKeyDefinition(keyId, old -> {
            KeyDraft.Published published = new KeyDraft.Published();
            published.version = old == null ? 1 : old.version + 1;
            published.spec = frozen;
            published.publishedBy = actor;
            published.publishedAt = System.currentTimeMillis();
            return published;
        }).thenApply(published -> {
            keys.put(keyId, published);
            audit.record(actor, "KEY_PUBLISH", keyId, "version " + published.version);
            return new PublishResult(true, published.version, "", List.of(), false);
        });
    }

    /** The publish gates' lookups; the editor also reads them to grey out reward types the server cannot deliver. */
    public DraftValidator.Environment environment(boolean mayPublishCommands) {
        Map<String, String> patterns = new LinkedHashMap<>();
        if (config.rewards.commandAllowlist != null) {
            for (LootBoxConfig.CommandPattern pattern : config.rewards.commandAllowlist) {
                if (pattern != null && pattern.id != null) {
                    patterns.put(pattern.id, pattern.pattern);
                }
            }
        }
        boolean economy;
        try {
            economy = core.getEconomyService() != null && core.getEconomyService().isAvailable();
        } catch (Throwable t) {
            economy = false;
        }
        boolean grants = core.getPermissionService() != null && core.getPermissionService().canGrant();
        return new DraftValidator.Environment(LootBoxRegistry::itemExists, keys::containsKey, patterns,
                economy, grants, mayPublishCommands, integrations);
    }

    private static <T> Map<String, T> withDraft(Map<String, T> current, String id, T draft) {
        Map<String, T> next = new HashMap<>(current);
        if (draft == null) {
            next.remove(id);
        } else {
            next.put(id, draft);
        }
        return Map.copyOf(next);
    }

    private static <T> Map<String, T> draftsOf(DraftStore.Listing<T> listing) {
        Map<String, T> next = new LinkedHashMap<>();
        listing.drafts().forEach((id, loaded) -> next.put(id, loaded.draft()));
        return Map.copyOf(next);
    }
}
