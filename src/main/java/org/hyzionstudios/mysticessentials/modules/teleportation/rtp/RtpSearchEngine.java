package org.hyzionstudios.mysticessentials.modules.teleportation.rtp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.hyzionstudios.mysticessentials.api.model.MysticLocation;
import org.hyzionstudios.mysticessentials.api.rtp.RtpDestinationResult;
import org.hyzionstudios.mysticessentials.api.rtp.RtpDestinationValidator;
import org.hyzionstudios.mysticessentials.api.rtp.RtpExclusionProvider;
import org.hyzionstudios.mysticessentials.api.rtp.RtpProfile;
import org.hyzionstudios.mysticessentials.api.rtp.event.RtpCandidateRejectedEvent;
import org.hyzionstudios.mysticessentials.core.MysticCore;

import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Central Random Teleport search queue and safe-destination engine (spec §6).
 *
 * <p>All state is mutated on a single repeating scheduler tick, except candidate
 * results which arrive asynchronously on world threads and hand back through an
 * {@code awaiting} flag — so the engine never blocks a world tick while scanning.
 * The engine enforces global and per-world concurrency caps, a per-tick candidate
 * budget, FIFO ordering with priority promotion, a per-search timeout and attempt
 * cap, and an optional pre-validated destination cache.</p>
 */
final class RtpSearchEngine {

    private final MysticCore core;
    private volatile RandomTeleportConfig config;

    private final List<RtpDestinationValidator> validators = new CopyOnWriteArrayList<>();
    private final List<RtpExclusionProvider> exclusions = new CopyOnWriteArrayList<>();

    private final Deque<Search> queue = new ConcurrentLinkedDeque<>();
    private final java.util.Set<Search> active = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Search> byPlayer = new ConcurrentHashMap<>();
    private final Map<String, Deque<CachedLocation>> cache = new ConcurrentHashMap<>();

    private ScheduledFuture<?> tickTask;

    RtpSearchEngine(MysticCore core, RandomTeleportConfig config) {
        this.core = core;
        this.config = config;
    }

    void configure(RandomTeleportConfig config) {
        this.config = config;
    }

    void start() {
        long interval = Math.max(20L, config.searchEngine.tickIntervalMillis);
        tickTask = core.scheduler().runRepeating(this::tick, interval, interval, TimeUnit.MILLISECONDS);
    }

    void stop() {
        if (tickTask != null) {
            tickTask.cancel(false);
            tickTask = null;
        }
        for (Search s : active) {
            s.future.complete(RtpDestinationResult.notFound(s.attempts.get(), "shutdown", copy(s.tally)));
        }
        for (Search s : queue) {
            s.future.complete(RtpDestinationResult.notFound(0, "shutdown", copy(s.tally)));
        }
        active.clear();
        queue.clear();
        byPlayer.clear();
    }

    void registerValidator(RtpDestinationValidator validator) {
        if (validator != null) {
            validators.add(validator);
        }
    }

    void registerExclusionProvider(RtpExclusionProvider provider) {
        if (provider != null) {
            exclusions.add(provider);
        }
    }

    // ----- Submission --------------------------------------------------------

    java.util.concurrent.CompletableFuture<RtpDestinationResult> submit(RtpProfile profile, UUID player,
            int priority) {
        RandomTeleportConfig cfg = config;
        if (queue.size() + active.size() >= cfg.searchEngine.maximumQueueSize) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    RtpDestinationResult.notFound(0, "queue_full", Map.of()));
        }
        Search s = new Search(profile, player, priority,
                System.currentTimeMillis() + Math.max(1, profile.searchTimeoutSeconds) * 1000L,
                Math.max(1, profile.maximumSearchAttempts));
        seedFromCache(s, cfg);
        if (player != null) {
            Search previous = byPlayer.put(player, s);
            if (previous != null && !previous.future.isDone()) {
                previous.future.complete(RtpDestinationResult.notFound(previous.attempts.get(),
                        "superseded", copy(previous.tally)));
            }
        }
        queue.add(s);
        return s.future;
    }

    boolean cancel(UUID player) {
        Search s = byPlayer.remove(player);
        if (s == null) {
            return false;
        }
        boolean wasPending = !s.future.isDone();
        s.future.complete(RtpDestinationResult.notFound(s.attempts.get(), "cancelled", copy(s.tally)));
        detach(s);
        return wasPending;
    }

    // ----- Tick loop ---------------------------------------------------------

    private void tick() {
        RandomTeleportConfig cfg = config;
        long now = System.currentTimeMillis();

        for (Search s : active) {
            if (s.future.isDone()) {
                detach(s);
            } else if (now > s.deadline) {
                finish(s, RtpDestinationResult.notFound(s.attempts.get(), "timeout", copy(s.tally)));
            } else if (s.attempts.get() >= s.maxAttempts && !s.awaiting.get()) {
                finish(s, RtpDestinationResult.notFound(s.attempts.get(), "max_attempts", copy(s.tally)));
            }
        }

        promote(cfg);

        int budget = Math.max(1, cfg.searchEngine.candidateChecksPerTick);
        for (Search s : active) {
            if (budget <= 0) {
                break;
            }
            if (s.future.isDone() || s.awaiting.get() || s.attempts.get() >= s.maxAttempts) {
                continue;
            }
            budget--;
            dispatch(s);
        }
    }

    private void promote(RandomTeleportConfig cfg) {
        while (active.size() < cfg.searchEngine.maximumConcurrentSearches) {
            Search next = pollBestQueued(cfg);
            if (next == null) {
                break;
            }
            if (next.future.isDone()) {
                continue;
            }
            active.add(next);
        }
    }

    /** Highest-priority queued search whose world is below the per-world concurrency cap. */
    private Search pollBestQueued(RandomTeleportConfig cfg) {
        Search best = null;
        for (Search s : queue) {
            if (s.future.isDone()) {
                continue;
            }
            if (worldActive(s.profile.world) >= cfg.searchEngine.maximumConcurrentSearchesPerWorld) {
                continue;
            }
            if (best == null || s.priority > best.priority
                    || (s.priority == best.priority && s.submittedAt < best.submittedAt)) {
                best = s;
            }
        }
        if (best != null) {
            queue.remove(best);
        }
        return best;
    }

    private int worldActive(String world) {
        int count = 0;
        for (Search s : active) {
            if (s.profile.world.equalsIgnoreCase(world)) {
                count++;
            }
        }
        return count;
    }

    private void dispatch(Search s) {
        RtpProfile p = s.profile;
        double[] candidate = s.seeded.poll();
        if (candidate == null) {
            candidate = RtpShapeSampler.sample(p, s.rng);
        }
        if (candidate == null) {
            s.attempts.incrementAndGet();
            bump(s, "shape");
            return;
        }
        int blockX = (int) Math.floor(candidate[0]);
        int blockZ = (int) Math.floor(candidate[1]);
        double fx = candidate[0];
        double fz = candidate[1];
        s.awaiting.set(true);
        core.platform().sampleGround(p.world, blockX, blockZ,
                        Math.max(1, p.safety.requiredHeadroom), p.safety.allowLiquids, p.minimumY, p.maximumY)
                .whenComplete((opt, err) -> onCandidate(s, fx, fz, opt, err));
    }

    private void onCandidate(Search s, double x, double z, Optional<MysticLocation> opt, Throwable err) {
        int n = s.attempts.incrementAndGet();
        try {
            if (err == null && opt != null && opt.isPresent()) {
                MysticLocation loc = opt.get();
                String reject = postCheck(s, loc);
                if (reject == null) {
                    if (finish(s, RtpDestinationResult.found(loc, n, copy(s.tally)))) {
                        cachePush(s.profile.id, x, z);
                    }
                    return;
                }
                bump(s, reject);
                fireRejected(s, x, z, reject);
            } else {
                bump(s, "unsafe_terrain");
                fireRejected(s, x, z, "unsafe_terrain");
            }
        } finally {
            s.awaiting.set(false);
        }
    }

    /** Non-block filters layered on the platform's block-safety probe. @return null if accepted. */
    private String postCheck(Search s, MysticLocation loc) {
        RtpProfile p = s.profile;
        if (p.filters.minimumDistanceFromSpawn > 0) {
            double cx = p.center == null ? 0 : p.center.x;
            double cz = p.center == null ? 0 : p.center.z;
            double dx = loc.getX() - cx;
            double dz = loc.getZ() - cz;
            if (dx * dx + dz * dz < (double) p.filters.minimumDistanceFromSpawn * p.filters.minimumDistanceFromSpawn) {
                return "too_close_to_spawn";
            }
        }
        for (RtpExclusionProvider provider : exclusions) {
            try {
                if (provider.isExcluded(loc.getWorld(), loc.getX(), loc.getZ(), p, s.player)) {
                    return "excluded:" + provider.name();
                }
            } catch (Throwable t) {
                // A misbehaving provider must not break the search.
            }
        }
        for (RtpDestinationValidator validator : validators) {
            try {
                Optional<String> reject = validator.reject(p, loc);
                if (reject.isPresent()) {
                    return reject.get();
                }
            } catch (Throwable t) {
                // Ignore a throwing validator.
            }
        }
        return null;
    }

    private boolean finish(Search s, RtpDestinationResult result) {
        boolean completed = s.future.complete(result);
        detach(s);
        return completed;
    }

    private void detach(Search s) {
        active.remove(s);
        queue.remove(s);
        if (s.player != null) {
            byPlayer.remove(s.player, s);
        }
    }

    private void bump(Search s, String reason) {
        s.tally.merge(reason, 1, Integer::sum);
    }

    private void fireRejected(Search s, double x, double z, String reason) {
        if (s.player != null) {
            core.getEventBus().publish(new RtpCandidateRejectedEvent(s.player, s.profile.id, x, z, reason));
        }
    }

    // ----- Cache -------------------------------------------------------------

    private void seedFromCache(Search s, RandomTeleportConfig cfg) {
        if (!cfg.searchEngine.cache.enabled) {
            return;
        }
        Deque<CachedLocation> pool = cache.get(s.profile.id);
        if (pool == null) {
            return;
        }
        long expiryMs = cfg.searchEngine.cache.expirationMinutes * 60_000L;
        long now = System.currentTimeMillis();
        int seeded = 0;
        while (seeded < 4) {
            CachedLocation entry = pool.pollFirst();
            if (entry == null) {
                break;
            }
            if (now - entry.timestamp <= expiryMs) {
                s.seeded.add(new double[] {entry.x, entry.z});
                seeded++;
            }
        }
    }

    private void cachePush(String profileId, double x, double z) {
        RandomTeleportConfig cfg = config;
        if (!cfg.searchEngine.cache.enabled) {
            return;
        }
        Deque<CachedLocation> pool = cache.computeIfAbsent(profileId, k -> new ConcurrentLinkedDeque<>());
        pool.addLast(new CachedLocation(x, z, System.currentTimeMillis()));
        while (pool.size() > cfg.searchEngine.cache.targetLocationsPerProfile) {
            pool.pollFirst();
        }
    }

    void clearCache(String profileId) {
        if (profileId == null || profileId.isBlank()) {
            cache.clear();
        } else {
            cache.remove(profileId);
        }
    }

    // ----- Introspection for /rtpadmin queue ---------------------------------

    int activeCount() {
        return active.size();
    }

    int queuedCount() {
        return queue.size();
    }

    /** @return the number of searches ahead of {@code player} (0 if active/absent). */
    int queuePosition(UUID player) {
        Search s = byPlayer.get(player);
        if (s == null || s.future.isDone() || active.contains(s)) {
            return 0;
        }
        int position = 1;
        for (Search other : queue) {
            if (other == s) {
                continue;
            }
            if (!other.future.isDone() && (other.priority > s.priority
                    || (other.priority == s.priority && other.submittedAt < s.submittedAt))) {
                position++;
            }
        }
        return position;
    }

    List<String> describeQueue() {
        List<String> lines = new ArrayList<>();
        List<Search> snapshot = new ArrayList<>(active);
        snapshot.addAll(queue);
        snapshot.sort(Comparator.comparingLong((Search s) -> s.submittedAt));
        for (Search s : snapshot) {
            String who = s.player == null ? "(test)" : core.platform().findPlayer(s.player)
                    .map(PlayerRef::getUsername).orElse(s.player.toString());
            lines.add((active.contains(s) ? "[active] " : "[queued] ") + who
                    + " -> " + s.profile.id + " (" + s.attempts.get() + " tries)");
        }
        return lines;
    }

    private static Map<String, Integer> copy(Map<String, Integer> tally) {
        return Map.copyOf(tally);
    }

    // ----- Inner types -------------------------------------------------------

    private static final class Search {
        final RtpProfile profile;
        final UUID player;
        final int priority;
        final long deadline;
        final int maxAttempts;
        final long submittedAt = System.nanoTime();
        final java.util.concurrent.CompletableFuture<RtpDestinationResult> future =
                new java.util.concurrent.CompletableFuture<>();
        final Random rng = new Random();
        final Map<String, Integer> tally = new ConcurrentHashMap<>();
        final AtomicInteger attempts = new AtomicInteger();
        final AtomicBoolean awaiting = new AtomicBoolean(false);
        final Deque<double[]> seeded = new ConcurrentLinkedDeque<>();

        Search(RtpProfile profile, UUID player, int priority, long deadline, int maxAttempts) {
            this.profile = profile;
            this.player = player;
            this.priority = priority;
            this.deadline = deadline;
            this.maxAttempts = maxAttempts;
        }
    }

    private record CachedLocation(double x, double z, long timestamp) {
    }
}
