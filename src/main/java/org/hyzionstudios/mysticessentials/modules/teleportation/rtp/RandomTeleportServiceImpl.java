package org.hyzionstudios.mysticessentials.modules.teleportation.rtp;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.hyzionstudios.mysticessentials.api.Permissions;
import org.hyzionstudios.mysticessentials.api.model.MysticLocation;
import org.hyzionstudios.mysticessentials.api.model.PlayerProfile;
import org.hyzionstudios.mysticessentials.api.rtp.RandomTeleportService;
import org.hyzionstudios.mysticessentials.api.rtp.RtpCancelReason;
import org.hyzionstudios.mysticessentials.api.rtp.RtpDestinationRequest;
import org.hyzionstudios.mysticessentials.api.rtp.RtpDestinationResult;
import org.hyzionstudios.mysticessentials.api.rtp.RtpDestinationValidator;
import org.hyzionstudios.mysticessentials.api.rtp.RtpExclusionProvider;
import org.hyzionstudios.mysticessentials.api.rtp.RtpProfile;
import org.hyzionstudios.mysticessentials.api.rtp.RtpRequest;
import org.hyzionstudios.mysticessentials.api.rtp.RtpResult;
import org.hyzionstudios.mysticessentials.api.rtp.RtpStatus;
import org.hyzionstudios.mysticessentials.api.rtp.event.RtpCancelledEvent;
import org.hyzionstudios.mysticessentials.api.rtp.event.RtpCompleteEvent;
import org.hyzionstudios.mysticessentials.api.rtp.event.RtpDestinationFoundEvent;
import org.hyzionstudios.mysticessentials.api.rtp.event.RtpFailedEvent;
import org.hyzionstudios.mysticessentials.api.rtp.event.RtpPreTeleportEvent;
import org.hyzionstudios.mysticessentials.api.rtp.event.RtpRequestEvent;
import org.hyzionstudios.mysticessentials.api.rtp.event.RtpSearchStartEvent;
import org.hyzionstudios.mysticessentials.api.rtp.event.RtpWarmupStartEvent;
import org.hyzionstudios.mysticessentials.api.service.TeleportService;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.platform.Conversions;

import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Full Random Teleport pipeline (spec §17). Resolves the destination profile,
 * runs permission / cooldown / limit / cost gates, drives a cancellable warmup,
 * queues a safe-destination search through the {@link RtpSearchEngine}, reserves
 * payment, revalidates and teleports, then commits payment, cooldown, usage, the
 * {@code /back} entry, events, and the audit record.
 *
 * <p>Every reserved payment is refunded on any failure or cancellation after the
 * reservation point, so a player is never charged for a teleport that did not
 * happen.</p>
 */
public final class RandomTeleportServiceImpl implements RandomTeleportService {

    private final MysticCore core;
    private final RtpSearchEngine engine;
    private final RtpAudit audit;
    private volatile RandomTeleportConfig config;

    /** Poll cadence for the warmup movement/damage watch. */
    private static final long WARMUP_CHECK_MS = 250L;
    /** How long completion/cancellation feedback remains visible. */
    private static final long TERMINAL_HUD_MS = 2_500L;

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> hudOwners = new ConcurrentHashMap<>();
    private final AtomicLong nextHudToken = new AtomicLong();

    public RandomTeleportServiceImpl(MysticCore core, RandomTeleportConfig config, RtpSearchEngine engine,
            RtpAudit audit) {
        this.core = core;
        this.engine = engine;
        this.audit = audit;
        configure(config);
    }

    /** Re-applies config and stamps each profile with its map-key id. */
    void configure(RandomTeleportConfig config) {
        config.profiles.forEach((id, profile) -> profile.id = id);
        this.config = config;
    }

    RandomTeleportConfig config() {
        return config;
    }

    /** @return {@code true} if the player currently has a warmup, queued search, or pending move. */
    public boolean isActive(UUID playerId) {
        return sessions.containsKey(playerId);
    }

    /** @return the phase ("warmup"/"search"/"teleport") and profile of the active session, if any. */
    public Optional<String> activeSummary(UUID playerId) {
        Session session = sessions.get(playerId);
        if (session == null) {
            return Optional.empty();
        }
        return Optional.of(session.phase.name().toLowerCase(java.util.Locale.ROOT) + ":" + session.profile.id);
    }

    /** Remaining cooldown seconds for a profile (0 if ready or unknown). */
    public long cooldownRemaining(UUID playerId, String profileId) {
        RtpProfile profile = config.profiles.get(profileId);
        return profile == null ? 0 : remainingCooldown(playerId, profile);
    }

    // ----- Public API --------------------------------------------------------

    @Override
    public CompletableFuture<RtpResult> teleport(RtpRequest request) {
        CompletableFuture<RtpResult> out = new CompletableFuture<>();
        RandomTeleportConfig cfg = config;
        UUID uuid = request.getPlayerId();

        if (!cfg.enabled) {
            return complete(out, RtpResult.failure(RtpStatus.DISABLED, null, "rtp disabled"));
        }
        Optional<PlayerRef> playerOpt = core.platform().findPlayer(uuid);
        if (playerOpt.isEmpty()) {
            return complete(out, RtpResult.failure(RtpStatus.FAILED, null, "offline"));
        }
        PlayerRef player = playerOpt.get();

        RtpProfile profile = resolveProfile(cfg, request, player);
        if (profile == null) {
            message(player, request, "rtp-no-destination", Map.of());
            return complete(out, RtpResult.failure(RtpStatus.NO_PROFILE, null, "no profile resolved"));
        }
        if (!profile.enabled) {
            message(player, request, "rtp-profile-disabled", Map.of("profile", profile.id));
            return complete(out, RtpResult.failure(RtpStatus.PROFILE_DISABLED, profile.id, "disabled"));
        }
        if (!worldEnabled(cfg, profile.world)) {
            message(player, request, "rtp-world-disabled", Map.of("world", profile.world));
            return complete(out, RtpResult.failure(RtpStatus.WORLD_UNAVAILABLE, profile.id, profile.world));
        }

        RtpRequestEvent requestEvent =
                core.getEventBus().publish(new RtpRequestEvent(uuid, profile.id, request.getActorId()));
        if (requestEvent.isCancelled()) {
            return complete(out, RtpResult.cancelled(profile.id, RtpCancelReason.COMMAND));
        }

        boolean adminForce = request.isForce();
        if (!adminForce && !canUse(player, profile)) {
            message(player, request, "rtp-no-permission", Map.of("profile", profile.id));
            return complete(out, RtpResult.failure(RtpStatus.NO_PERMISSION, profile.id, "no permission"));
        }
        if (sessions.containsKey(uuid)) {
            message(player, request, "rtp-already-active", Map.of());
            return complete(out, RtpResult.failure(RtpStatus.ALREADY_ACTIVE, profile.id, "already active"));
        }

        boolean bypassCooldown = adminForce || player.hasPermission(Permissions.RTP_BYPASS_COOLDOWN);
        long remaining = bypassCooldown ? 0 : remainingCooldown(uuid, profile);
        if (remaining > 0) {
            message(player, request, "rtp-on-cooldown", Map.of("seconds", Long.toString(remaining)));
            return complete(out, RtpResult.failure(RtpStatus.ON_COOLDOWN, profile.id, remaining + "s"));
        }
        if (!adminForce && limitReached(player, profile)) {
            message(player, request, "rtp-limit-reached", Map.of("profile", profile.id));
            return complete(out, RtpResult.failure(RtpStatus.LIMIT_REACHED, profile.id, "limit reached"));
        }

        double cost = costFor(profile);
        boolean bypassCost = adminForce || request.isBypassCost() || player.hasPermission(Permissions.RTP_BYPASS_COST);
        double charge = (cost > 0 && !bypassCost) ? cost : 0;
        if (charge > 0 && !core.getEconomyService().has(uuid, charge)) {
            message(player, request, "rtp-not-enough-money",
                    Map.of("cost", core.getEconomyService().format(charge)));
            return complete(out, RtpResult.failure(RtpStatus.NOT_ENOUGH_MONEY, profile.id, "insufficient funds"));
        }

        Session session = new Session(profile, request, out, charge, System.nanoTime(),
                nextHudToken.incrementAndGet());
        sessions.put(uuid, session);

        int warmup = (adminForce || player.hasPermission(Permissions.RTP_BYPASS_WARMUP))
                ? 0 : Math.max(0, profile.warmupSeconds);
        if (warmup <= 0) {
            beginSearch(player, session);
        } else {
            core.getEventBus().publish(new RtpWarmupStartEvent(uuid, profile.id, warmup));
            message(player, request, "rtp-warmup", Map.of("seconds", Integer.toString(warmup)));
            startWarmup(player, session, warmup);
        }
        return out;
    }

    @Override
    public CompletableFuture<RtpDestinationResult> findDestination(RtpDestinationRequest request) {
        RtpProfile profile = config.profiles.get(request.profileId());
        if (profile == null) {
            return CompletableFuture.completedFuture(
                    RtpDestinationResult.notFound(0, "unknown_profile", Map.of()));
        }
        int priority = 0;
        if (request.requestingPlayer() != null) {
            priority = core.platform().findPlayer(request.requestingPlayer())
                    .map(RtpLimits::priority).orElse(0);
        }
        return engine.submit(profile, request.requestingPlayer(), priority);
    }

    @Override
    public Optional<RtpProfile> getProfile(String profileId) {
        return Optional.ofNullable(config.profiles.get(profileId));
    }

    @Override
    public Collection<RtpProfile> getAvailableProfiles(PlayerRef player) {
        RandomTeleportConfig cfg = config;
        List<RtpProfile> available = new ArrayList<>();
        for (RtpProfile profile : cfg.profiles.values()) {
            if (profile.enabled && worldEnabled(cfg, profile.world) && canUse(player, profile)) {
                available.add(profile);
            }
        }
        return available;
    }

    @Override
    public boolean cancel(UUID playerId, RtpCancelReason reason) {
        Session session = sessions.get(playerId);
        if (session == null || !session.cancelled.compareAndSet(false, true)) {
            return false;
        }
        session.cancelReason = reason;
        ScheduledFuture<?> warmupTask = session.warmupTask;
        if (warmupTask != null) {
            warmupTask.cancel(false);
        }
        engine.cancel(playerId);
        refund(playerId, session);
        sessions.remove(playerId, session);
        core.platform().findPlayer(playerId).ifPresent(p -> {
            if (!session.request.isSilent()) {
                core.getMessageService().sendKey(p, "rtp-cancelled");
            }
            showTerminalHud(p, session, "rtp-hud-cancelled", Map.of());
        });
        core.getEventBus().publish(new RtpCancelledEvent(playerId, session.profile.id, reason));
        session.out.complete(RtpResult.cancelled(session.profile.id, reason));
        return true;
    }

    @Override
    public void registerValidator(RtpDestinationValidator validator) {
        engine.registerValidator(validator);
    }

    @Override
    public void registerExclusionProvider(RtpExclusionProvider provider) {
        engine.registerExclusionProvider(provider);
    }

    // ----- Warmup ------------------------------------------------------------

    private void startWarmup(PlayerRef player, Session session, int warmupSeconds) {
        UUID uuid = player.getUuid();
        RandomTeleportConfig cfg = config;
        MysticLocation start = safeCapture(player);
        long endAt = System.currentTimeMillis() + warmupSeconds * 1000L;
        double toleranceSq = cfg.warmup.movementTolerance * cfg.warmup.movementTolerance;
        showHud(player, session, "rtp-hud-warmup",
                Map.of("seconds", Integer.toString(warmupSeconds)));

        java.util.concurrent.atomic.AtomicReference<java.time.Instant> damageBaseline =
                new java.util.concurrent.atomic.AtomicReference<>();
        if (cfg.warmup.cancelOnDamage) {
            core.platform().lastDamageTime(player)
                    .thenAccept(instant -> damageBaseline.set(instant == null ? java.time.Instant.MIN : instant));
        }

        session.warmupTask = core.scheduler().runRepeating(() -> {
            if (session.cancelled.get()) {
                return;
            }
            Optional<PlayerRef> live = core.platform().findPlayer(uuid);
            if (live.isEmpty()) {
                if (cfg.warmup.cancelOnLogout) {
                    cancel(uuid, RtpCancelReason.LOGOUT);
                }
                return;
            }
            PlayerRef ref = live.get();
            if (System.currentTimeMillis() >= endAt) {
                ScheduledFuture<?> task = session.warmupTask;
                if (task != null) {
                    task.cancel(false);
                }
                beginSearch(ref, session);
                return;
            }
            showHud(ref, session, "rtp-hud-warmup",
                    Map.of("seconds", Integer.toString(remainingSeconds(endAt))));
            if (cfg.warmup.cancelOnMovement && start != null) {
                MysticLocation now = safeCapture(ref);
                if (now != null && moved(start, now, toleranceSq, cfg.warmup.cancelOnWorldChange)) {
                    cancel(uuid, Objects.equals(start.getWorld(), now.getWorld())
                            ? RtpCancelReason.MOVEMENT : RtpCancelReason.WORLD_CHANGE);
                    return;
                }
            }
            if (cfg.warmup.cancelOnDamage && damageBaseline.get() != null) {
                java.time.Instant baseline = damageBaseline.get();
                core.platform().lastDamageTime(ref).thenAccept(current -> {
                    if (!session.cancelled.get() && current != null && current.isAfter(baseline)) {
                        cancel(uuid, RtpCancelReason.DAMAGE_TAKEN);
                    }
                });
            }
        }, WARMUP_CHECK_MS, WARMUP_CHECK_MS, TimeUnit.MILLISECONDS);
    }

    // ----- Search + teleport -------------------------------------------------

    private void beginSearch(PlayerRef player, Session session) {
        UUID uuid = player.getUuid();
        if (session.cancelled.get()) {
            return;
        }
        session.phase = Session.Phase.SEARCH;
        if (session.charge > 0) {
            if (!core.getEconomyService().withdraw(uuid, session.charge)) {
                message(player, session.request, "rtp-not-enough-money",
                        Map.of("cost", core.getEconomyService().format(session.charge)));
                finishFailure(uuid, session, RtpStatus.NOT_ENOUGH_MONEY, "reserve failed");
                return;
            }
            session.reserved = session.charge;
        }
        core.getEventBus().publish(new RtpSearchStartEvent(uuid, session.profile.id));
        if (!session.request.isSilent()) {
            core.getMessageService().sendKey(player, "rtp-searching");
        }
        showHud(player, session, "rtp-hud-searching", Map.of());
        int priority = RtpLimits.priority(player);
        engine.submit(session.profile, uuid, priority)
                .whenComplete((result, error) -> onSearchDone(uuid, session, result, error));
    }

    private void onSearchDone(UUID uuid, Session session, RtpDestinationResult result, Throwable error) {
        if (session.cancelled.get() || sessions.get(uuid) != session) {
            return;
        }
        if (error != null || result == null || !result.found()) {
            String reason = result == null ? "error" : result.failureReason();
            RtpStatus status = "queue_full".equals(reason) ? RtpStatus.QUEUE_FULL : RtpStatus.NO_DESTINATION;
            core.platform().findPlayer(uuid).ifPresent(p ->
                    message(p, session.request, "rtp-no-destination", Map.of()));
            finishFailure(uuid, session, status, reason);
            return;
        }

        RtpDestinationFoundEvent found = core.getEventBus().publish(
                new RtpDestinationFoundEvent(uuid, session.profile.id, result.location(), result.attempts()));
        if (found.isCancelled() || found.destination() == null) {
            finishFailure(uuid, session, RtpStatus.NO_DESTINATION, "destination vetoed");
            return;
        }
        session.attempts = result.attempts();
        revalidateAndTeleport(uuid, session, found.destination());
    }

    private void revalidateAndTeleport(UUID uuid, Session session, MysticLocation candidate) {
        RtpProfile p = session.profile;
        if (!config.searchEngine.cache.revalidateBeforeTeleport) {
            preTeleport(uuid, session, candidate);
            return;
        }
        int blockX = (int) Math.floor(candidate.getX());
        int blockZ = (int) Math.floor(candidate.getZ());
        core.platform().sampleGround(p.world, blockX, blockZ,
                        Math.max(1, p.safety.requiredHeadroom), p.safety.allowLiquids, p.minimumY, p.maximumY)
                .whenComplete((opt, err) -> {
                    if (session.cancelled.get()) {
                        return;
                    }
                    if (err != null || opt == null || opt.isEmpty()) {
                        core.platform().findPlayer(uuid).ifPresent(pl ->
                                message(pl, session.request, "rtp-no-destination", Map.of()));
                        finishFailure(uuid, session, RtpStatus.NO_DESTINATION, "revalidation failed");
                        return;
                    }
                    preTeleport(uuid, session, opt.get());
                });
    }

    private void preTeleport(UUID uuid, Session session, MysticLocation destination) {
        if (session.cancelled.get()) {
            return;
        }
        RtpPreTeleportEvent pre = core.getEventBus().publish(
                new RtpPreTeleportEvent(uuid, session.profile.id, destination));
        if (pre.isCancelled()) {
            finishFailure(uuid, session, RtpStatus.CANCELLED, "pre-teleport vetoed");
            return;
        }
        Optional<PlayerRef> playerOpt = core.platform().findPlayer(uuid);
        if (playerOpt.isEmpty()) {
            finishFailure(uuid, session, RtpStatus.FAILED, "offline");
            return;
        }
        PlayerRef player = playerOpt.get();
        session.phase = Session.Phase.TELEPORT;
        showHud(player, session, "rtp-hud-teleporting", Map.of());
        recordBackLocation(uuid, player);

        core.platform().teleportEntity(player, destination).whenComplete((moveResult, moveError) -> {
            if (moveError != null || moveResult != TeleportService.Result.SUCCESS) {
                finishFailure(uuid, session, RtpStatus.FAILED,
                        moveError != null ? moveError.toString() : String.valueOf(moveResult));
                return;
            }
            onTeleportSuccess(uuid, session, destination);
        });
    }

    private void onTeleportSuccess(UUID uuid, Session session, MysticLocation destination) {
        RtpProfile p = session.profile;
        core.platform().findPlayer(uuid).ifPresent(player -> {
            int protection = Math.max(p.arrivalProtection.invulnerabilitySeconds,
                    p.arrivalProtection.preventFallDamageSeconds);
            core.platform().applyArrivalProtection(player, protection);
        });

        // Cooldown, usage, and /back-history commit on the persisted profile.
        core.getPlayerProfileService().getCached(uuid).ifPresent(profile -> {
            synchronized (profile) {
                int cooldown = core.platform().findPlayer(uuid)
                        .map(pl -> RtpLimits.cooldownSeconds(pl, p)).orElse(Math.max(0, p.cooldownSeconds));
                if (cooldown > 0) {
                    RtpPlayerData.setCooldown(profile, p.id, cooldown);
                    core.cooldowns().set(uuid, "rtp:" + p.id, cooldown);
                }
                RtpPlayerData.recordUse(profile, p.id);
                RtpPlayerData.recordSuccess(profile, p.id);
            }
            core.getPlayerProfileService().save(profile);
        });

        long durationMs = (System.nanoTime() - session.startNanos) / 1_000_000L;
        RtpResult success = RtpResult.success(p.id, destination, session.attempts, durationMs);
        // Payment commits simply by leaving the reservation withdrawn.
        session.reserved = 0;
        sessions.remove(uuid, session);

        core.getEventBus().publish(new RtpCompleteEvent(uuid, p.id, destination, session.attempts, durationMs));
        core.platform().findPlayer(uuid).ifPresent(player -> {
            showTerminalHud(player, session, "rtp-hud-success",
                    Map.of("profile", p.displayNameOrId()));
            if (!session.request.isSilent()) {
                core.getMessageService().sendKey(player, "rtp-success", Map.of(
                        "profile", p.displayNameOrId(),
                        "x", Integer.toString((int) Math.floor(destination.getX())),
                        "y", Integer.toString((int) Math.floor(destination.getY())),
                        "z", Integer.toString((int) Math.floor(destination.getZ()))));
            }
            audit.record(uuid, player.getUsername(), session.request.getActorId(),
                    actorName(session.request), success, session.charge, session.request.isForce());
        });
        session.out.complete(success);
    }

    private void finishFailure(UUID uuid, Session session, RtpStatus status, String detail) {
        if (!session.cancelled.compareAndSet(false, true)) {
            return;
        }
        refund(uuid, session);
        sessions.remove(uuid, session);
        RtpResult result = RtpResult.failure(status, session.profile.id, detail);
        core.getEventBus().publish(new RtpFailedEvent(uuid, session.profile.id, status, detail));
        core.platform().findPlayer(uuid).ifPresent(player -> {
            showTerminalHud(player, session, "rtp-hud-failed", Map.of());
            audit.record(uuid, player.getUsername(), session.request.getActorId(),
                    actorName(session.request), result, 0, session.request.isForce());
        });
        session.out.complete(result);
    }

    // ----- Helpers -----------------------------------------------------------

    private void refund(UUID uuid, Session session) {
        if (session.reserved > 0) {
            core.getEconomyService().deposit(uuid, session.reserved);
            session.reserved = 0;
        }
    }

    private void recordBackLocation(UUID uuid, PlayerRef player) {
        core.getPlayerProfileService().getCached(uuid).ifPresent(profile ->
                profile.setLastTeleportedLocation(Conversions.capture(player)));
    }

    private long remainingCooldown(UUID uuid, RtpProfile profile) {
        long inMemory = core.cooldowns().remaining(uuid, "rtp:" + profile.id);
        long persisted = core.getPlayerProfileService().getCached(uuid)
                .map(profile2 -> {
                    synchronized (profile2) {
                        return RtpPlayerData.remainingCooldown(profile2, profile.id);
                    }
                })
                .orElse(0L);
        return Math.max(inMemory, persisted);
    }

    private boolean limitReached(PlayerRef player, RtpProfile profile) {
        int daily = RtpLimits.dailyLimit(player, profile);
        int hourly = RtpLimits.hourlyLimit(player, profile);
        if (daily <= 0 && hourly <= 0) {
            return false;
        }
        return core.getPlayerProfileService().getCached(player.getUuid())
                .map(profile2 -> {
                    synchronized (profile2) {
                        if (daily > 0 && RtpPlayerData.usageToday(profile2, profile.id) >= daily) {
                            return true;
                        }
                        return hourly > 0 && RtpPlayerData.usageThisHour(profile2, profile.id) >= hourly;
                    }
                })
                .orElse(false);
    }

    private double costFor(RtpProfile profile) {
        return profile.cost != null && profile.cost.enabled ? Math.max(0, profile.cost.amount) : 0;
    }

    /** Base permission plus an optional per-profile permission requirement. */
    private boolean canUse(PlayerRef player, RtpProfile profile) {
        if (!player.hasPermission(Permissions.RTP_USE)) {
            return false;
        }
        String required = profile.filters == null ? null : profile.filters.permission;
        return required == null || required.isBlank() || player.hasPermission(required);
    }

    private boolean worldEnabled(RandomTeleportConfig cfg, String world) {
        if (world == null) {
            return false;
        }
        if (containsIgnoreCase(cfg.randomTeleport.disabledWorlds, world)) {
            return false;
        }
        return cfg.randomTeleport.enabledWorlds.isEmpty()
                || containsIgnoreCase(cfg.randomTeleport.enabledWorlds, world);
    }

    private static boolean containsIgnoreCase(Collection<String> values, String needle) {
        for (String value : values) {
            if (value != null && value.equalsIgnoreCase(needle)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolves the destination profile (spec §2 resolution order): explicit
     * profile &rarr; explicit world &rarr; selection mode (permission / current
     * world / per-world mapping) &rarr; default profile &rarr; a profile in the
     * default world.
     */
    private RtpProfile resolveProfile(RandomTeleportConfig cfg, RtpRequest request, PlayerRef player) {
        if (request.getProfileId() != null && !request.getProfileId().isBlank()) {
            return cfg.profiles.get(request.getProfileId());
        }
        if (request.getWorld() != null && !request.getWorld().isBlank()) {
            return profileForWorld(cfg, request.getWorld());
        }
        String currentWorld = core.platform().worldNameOf(player).orElse(null);
        RtpProfile resolved = switch (cfg.randomTeleport.defaultSelectionMode) {
            case PERMISSION_PROFILE -> highestPermittedProfile(cfg, player);
            case CURRENT_WORLD -> currentWorld != null && worldEnabled(cfg, currentWorld)
                    ? profileForWorld(cfg, currentWorld) : null;
            case PER_WORLD_PROFILE -> currentWorld == null ? null
                    : cfg.profiles.get(cfg.randomTeleport.worldProfiles.get(currentWorld));
            // DEFAULT_WORLD always targets the configured default, regardless of
            // where /rtp was run — resolve it up front so current-world fallback
            // cannot hijack it.
            case DEFAULT_WORLD -> defaultProfile(cfg);
        };
        if (resolved != null) {
            return resolved;
        }
        if (cfg.randomTeleport.allowCurrentWorldFallback && currentWorld != null && worldEnabled(cfg, currentWorld)) {
            RtpProfile currentWorldProfile = profileForWorld(cfg, currentWorld);
            if (currentWorldProfile != null) {
                return currentWorldProfile;
            }
        }
        return defaultProfile(cfg);
    }

    /** The configured default profile, falling back to any enabled profile in the default world. */
    private RtpProfile defaultProfile(RandomTeleportConfig cfg) {
        RtpProfile byName = cfg.profiles.get(cfg.randomTeleport.defaultProfile);
        if (byName != null) {
            return byName;
        }
        return profileForWorld(cfg, cfg.randomTeleport.defaultWorld);
    }

    private RtpProfile profileForWorld(RandomTeleportConfig cfg, String world) {
        String mapped = cfg.randomTeleport.worldProfiles.get(world);
        if (mapped != null && cfg.profiles.containsKey(mapped)) {
            return cfg.profiles.get(mapped);
        }
        for (RtpProfile profile : cfg.profiles.values()) {
            if (profile.enabled && world.equalsIgnoreCase(profile.world)) {
                return profile;
            }
        }
        return null;
    }

    private RtpProfile highestPermittedProfile(RandomTeleportConfig cfg, PlayerRef player) {
        RtpProfile best = null;
        for (RtpProfile profile : cfg.profiles.values()) {
            if (profile.enabled && worldEnabled(cfg, profile.world) && canUse(player, profile)
                    && (best == null || profile.priority > best.priority)) {
                best = profile;
            }
        }
        return best;
    }

    private MysticLocation safeCapture(PlayerRef player) {
        try {
            return Conversions.capture(player);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean moved(MysticLocation start, MysticLocation now, double toleranceSq,
            boolean worldChangeCancels) {
        if (!Objects.equals(start.getWorld(), now.getWorld())) {
            return worldChangeCancels;
        }
        double dx = start.getX() - now.getX();
        double dy = start.getY() - now.getY();
        double dz = start.getZ() - now.getZ();
        return (dx * dx + dy * dy + dz * dz) > toleranceSq;
    }

    private void message(PlayerRef player, RtpRequest request, String key, Map<String, String> params) {
        if (request.isSilent()) {
            return;
        }
        core.getMessageService().sendKey(player, key, params);
    }

    private void showHud(PlayerRef player, Session session, String key, Map<String, String> params) {
        if (session.request.isSilent()) {
            return;
        }
        String text = core.getMessageService().plainFromKey(key, params);
        if (text.equals(session.lastHudText)) {
            return;
        }
        session.lastHudText = text;
        hudOwners.put(player.getUuid(), session.hudToken);
        core.platform().showHud(player, new RtpStatusHud(player, text));
    }

    private void showTerminalHud(PlayerRef player, Session session, String key, Map<String, String> params) {
        if (session.request.isSilent()) {
            return;
        }
        showHud(player, session, key, params);
        UUID uuid = player.getUuid();
        core.scheduler().runLater(() -> {
            if (!hudOwners.remove(uuid, session.hudToken)) {
                return;
            }
            core.platform().findPlayer(uuid).ifPresent(live ->
                    core.platform().removeHud(live, RtpStatusHud.KEY));
        }, TERMINAL_HUD_MS, TimeUnit.MILLISECONDS);
    }

    private static int remainingSeconds(long endAt) {
        return Math.max(1, (int) Math.ceil((endAt - System.currentTimeMillis()) / 1000.0D));
    }

    private String actorName(RtpRequest request) {
        if (request.getActorId() == null) {
            return null;
        }
        return core.platform().findPlayer(request.getActorId())
                .map(PlayerRef::getUsername).orElse(request.getActorId().toString());
    }

    private CompletableFuture<RtpResult> complete(CompletableFuture<RtpResult> future, RtpResult result) {
        future.complete(result);
        return future;
    }

    void shutdown() {
        for (UUID uuid : new ArrayList<>(sessions.keySet())) {
            cancel(uuid, RtpCancelReason.SHUTDOWN);
        }
    }

    // ----- Session state -----------------------------------------------------

    private static final class Session {
        enum Phase { WARMUP, SEARCH, TELEPORT }

        final RtpProfile profile;
        final RtpRequest request;
        final CompletableFuture<RtpResult> out;
        final double charge;
        final long startNanos;
        final long hudToken;
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        volatile Phase phase = Phase.WARMUP;
        volatile RtpCancelReason cancelReason;
        volatile ScheduledFuture<?> warmupTask;
        volatile double reserved;
        volatile int attempts;
        volatile String lastHudText;

        Session(RtpProfile profile, RtpRequest request, CompletableFuture<RtpResult> out, double charge,
                long startNanos, long hudToken) {
            this.profile = profile;
            this.request = request;
            this.out = out;
            this.charge = charge;
            this.startNanos = startNanos;
            this.hudToken = hudToken;
        }
    }
}
