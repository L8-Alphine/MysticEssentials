package org.hyzionstudios.mysticessentials.core.teleport;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.api.model.MysticLocation;
import org.hyzionstudios.mysticessentials.api.model.TeleportRequest;
import org.hyzionstudios.mysticessentials.api.service.TeleportService;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.modules.teleportation.TeleportationConfig;
import org.hyzionstudios.mysticessentials.platform.Conversions;

import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Central teleport pipeline: cost check &rarr; cooldown check &rarr; record back
 * location &rarr; warmup (with movement cancellation) &rarr; ECS move on the
 * player's world thread &rarr; apply cooldown.
 *
 * <p>During a warmup the player's position is polled; if they move (and
 * {@code cancelOnMove} is set) the teleport is cancelled. The physical move
 * itself is delegated to {@code HytalePlatform.teleportEntity}, which runs on the
 * entity's world thread via the verified {@code PendingTeleport} component.</p>
 */
public final class TeleportServiceImpl implements TeleportService {

    private static final String BYPASS_WARMUP_PERMISSION =
            org.hyzionstudios.mysticessentials.api.Permissions.TELEPORT_BYPASS_WARMUP;
    private static final String BYPASS_COOLDOWN_PERMISSION =
            org.hyzionstudios.mysticessentials.api.Permissions.TELEPORT_BYPASS_COOLDOWN;

    /** Poll cadence for the warmup movement check. */
    private static final long WARMUP_CHECK_MS = 250L;
    /** Squared distance (blocks²) a player may drift before it counts as "moved". */
    private static final double MOVE_EPSILON_SQ = 0.2 * 0.2;

    private final MysticCore core;
    private final AtomicReference<WorldAccessRules> worldAccess =
            new AtomicReference<>(WorldAccessRules.allowAll());

    public TeleportServiceImpl(MysticCore core) {
        this.core = core;
    }

    /** Replaces world allow/deny rules with an immutable, thread-safe snapshot. */
    public void configure(TeleportationConfig config) {
        worldAccess.set(WorldAccessRules.from(config));
    }

    @Override
    public CompletableFuture<Result> teleport(PlayerRef player, TeleportRequest request) {
        UUID uuid = player.getUuid();
        boolean bypassCooldown = player.hasPermission(BYPASS_COOLDOWN_PERMISSION);
        boolean bypassWarmup = player.hasPermission(BYPASS_WARMUP_PERMISSION);

        if (request.getCost() > 0 && !core.getEconomyService().has(uuid, request.getCost())) {
            core.getMessageService().sendKey(player, "not-enough-money",
                    Map.of("cost", core.getEconomyService().format(request.getCost())));
            return CompletableFuture.completedFuture(Result.NOT_ENOUGH_MONEY);
        }
        if (!bypassCooldown && request.getCooldownKey() != null
                && core.cooldowns().isActive(uuid, request.getCooldownKey())) {
            core.getMessageService().sendKey(player, "teleport-on-cooldown",
                    Map.of("seconds", Long.toString(core.cooldowns().remaining(uuid, request.getCooldownKey()))));
            return CompletableFuture.completedFuture(Result.ON_COOLDOWN);
        }

        MysticLocation destination = resolveDestination(request);
        if (destination == null || destination.getWorld() == null) {
            core.getMessageService().sendKey(player, "teleport-invalid-destination");
            return CompletableFuture.completedFuture(Result.INVALID_DESTINATION);
        }
        if (!worldAccess.get().allows(destination.getWorld())) {
            core.getMessageService().sendKey(player, "teleport-world-disabled",
                    Map.of("world", destination.getWorld()));
            return CompletableFuture.completedFuture(Result.INVALID_DESTINATION);
        }

        if (request.isRecordBackLocation()) {
            recordBackLocation(uuid, player);
        }
        if (request.getCost() > 0) {
            core.getEconomyService().withdraw(uuid, request.getCost());
            core.getMessageService().sendKey(player, "teleport-cost-charged",
                    Map.of("cost", core.getEconomyService().format(request.getCost())));
        }

        CompletableFuture<Result> outcome = new CompletableFuture<>();
        int warmup = bypassWarmup ? 0 : request.getWarmupSeconds();
        if (warmup <= 0) {
            finish(player, request, destination, outcome);
        } else {
            startWarmup(player, request, destination, outcome, warmup);
        }
        return outcome;
    }

    /**
     * Polls the player during the warmup window. Cancels on movement (when
     * {@code cancelOnMove}), on damage (when {@code cancelOnDamage}), or if the
     * player disconnects; otherwise performs the move once the warmup elapses.
     *
     * <p>Damage is detected via {@code DamageDataComponent.getLastDamageTime()}
     * (read on the entity's world thread): a baseline is captured at start and any
     * later timestamp means the player took damage. {@code Instant.MIN} is the
     * "never damaged" baseline so a first-ever hit still cancels.</p>
     */
    private void startWarmup(PlayerRef player, TeleportRequest request, MysticLocation destination,
            CompletableFuture<Result> outcome, int warmupSeconds) {
        UUID uuid = player.getUuid();
        MysticLocation start = safeCapture(player);
        long endAt = System.currentTimeMillis() + warmupSeconds * 1000L;
        AtomicBoolean settled = new AtomicBoolean(false);
        AtomicInteger lastHudSecond = new AtomicInteger(0);
        ScheduledFuture<?>[] holder = new ScheduledFuture<?>[1];
        showWarmupHud(player, warmupSeconds, lastHudSecond);

        AtomicReference<Instant> damageBaseline = new AtomicReference<>();
        if (request.isCancelOnDamage()) {
            core.platform().lastDamageTime(player)
                    .thenAccept(instant -> damageBaseline.set(instant == null ? Instant.MIN : instant));
        }

        holder[0] = core.scheduler().runRepeating(() -> {
            if (settled.get()) {
                return;
            }
            Optional<PlayerRef> live = core.platform().findPlayer(uuid);
            if (live.isEmpty()) {
                settle(settled, holder, () -> outcome.complete(Result.FAILED));
                return;
            }
            long nowMs = System.currentTimeMillis();
            if (nowMs >= endAt) {
                PlayerRef ref = live.get();
                settle(settled, holder, () -> {
                    hideWarmupHud(ref);
                    core.getMessageService().sendKey(ref, "teleport-starting");
                    finish(ref, request, destination, outcome);
                });
                return;
            }
            showWarmupHud(live.get(), remainingHudSeconds(endAt, nowMs), lastHudSecond);
            if (request.isCancelOnMove() && start != null) {
                MysticLocation now = safeCapture(live.get());
                if (now != null && hasMoved(start, now)) {
                    core.getMessageService().sendKey(live.get(), "teleport-cancelled-move");
                    PlayerRef ref = live.get();
                    settle(settled, holder, () -> {
                        hideWarmupHud(ref);
                        outcome.complete(Result.CANCELLED_MOVED);
                    });
                    return;
                }
            }
            if (request.isCancelOnDamage()) {
                Instant baseline = damageBaseline.get();
                if (baseline != null) {
                    PlayerRef ref = live.get();
                    core.platform().lastDamageTime(ref).thenAccept(current -> {
                        if (!settled.get() && current != null && current.isAfter(baseline)) {
                            core.getMessageService().sendKey(ref, "teleport-cancelled-damage");
                            settle(settled, holder, () -> {
                                hideWarmupHud(ref);
                                outcome.complete(Result.CANCELLED_DAMAGED);
                            });
                        }
                    });
                }
            }
        }, WARMUP_CHECK_MS, WARMUP_CHECK_MS, TimeUnit.MILLISECONDS);
    }

    /** Runs {@code action} at most once and cancels the warmup poll task. */
    private void settle(AtomicBoolean settled, ScheduledFuture<?>[] holder, Runnable action) {
        if (settled.compareAndSet(false, true)) {
            if (holder[0] != null) {
                holder[0].cancel(false);
            }
            action.run();
        }
    }

    private void showWarmupHud(PlayerRef player, int seconds, AtomicInteger lastHudSecond) {
        if (seconds <= 0 || lastHudSecond.getAndSet(seconds) == seconds) {
            return;
        }
        String text = core.getMessageService().plainFromKey("teleport-hud-warmup",
                Map.of("seconds", Integer.toString(seconds)));
        core.platform().showHud(player, new TeleportWarmupHud(player, text));
    }

    private void hideWarmupHud(PlayerRef player) {
        core.platform().removeHud(player, TeleportWarmupHud.KEY);
    }

    private static int remainingHudSeconds(long endAt, long nowMs) {
        return Math.max(1, (int) Math.ceil((endAt - nowMs) / 1000.0D));
    }

    private MysticLocation safeCapture(PlayerRef player) {
        try {
            return Conversions.capture(player);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean hasMoved(MysticLocation start, MysticLocation now) {
        if (!Objects.equals(start.getWorld(), now.getWorld())) {
            return true;
        }
        double dx = start.getX() - now.getX();
        double dy = start.getY() - now.getY();
        double dz = start.getZ() - now.getZ();
        return (dx * dx + dy * dy + dz * dz) > MOVE_EPSILON_SQ;
    }

    @Override
    public CompletableFuture<Result> teleportNow(PlayerRef player, MysticLocation destination) {
        if (destination == null || destination.getWorld() == null
                || !worldAccess.get().allows(destination.getWorld())) {
            core.getMessageService().sendKey(player, "teleport-world-disabled",
                    Map.of("world", destination == null || destination.getWorld() == null
                            ? "unknown" : destination.getWorld()));
            return CompletableFuture.completedFuture(Result.INVALID_DESTINATION);
        }
        CompletableFuture<Result> outcome = new CompletableFuture<>();
        dispatchMove(player, destination, outcome);
        return outcome;
    }

    @Override
    public long remainingCooldown(UUID player, String cooldownKey) {
        return core.cooldowns().remaining(player, cooldownKey);
    }

    private void finish(PlayerRef player, TeleportRequest request, MysticLocation destination,
            CompletableFuture<Result> outcome) {
        dispatchMove(player, destination, outcome);
        outcome.thenAccept(result -> {
            if (result == Result.SUCCESS) {
                core.getMessageService().sendKey(player, "teleport-success");
            }
        });
        if (!player.hasPermission(BYPASS_COOLDOWN_PERMISSION)
                && request.getCooldownKey() != null && request.getCooldownSeconds() > 0) {
            core.cooldowns().set(player.getUuid(), request.getCooldownKey(), request.getCooldownSeconds());
        }
    }

    private void dispatchMove(PlayerRef player, MysticLocation destination, CompletableFuture<Result> outcome) {
        core.platform().teleportEntity(player, destination).whenComplete((result, error) -> {
            if (error != null) {
                core.log(Level.SEVERE, "Teleport move failed: " + error);
                outcome.complete(Result.FAILED);
            } else {
                outcome.complete(result);
            }
        });
    }

    private MysticLocation resolveDestination(TeleportRequest request) {
        if (request.getTarget() != null) {
            return request.getTarget();
        }
        if (request.getTargetPlayer() != null) {
            Optional<PlayerRef> target = core.platform().findPlayer(request.getTargetPlayer());
            return target.map(Conversions::capture).orElse(null);
        }
        return null;
    }

    private void recordBackLocation(UUID uuid, PlayerRef player) {
        core.getPlayerProfileService().getCached(uuid).ifPresent(profile ->
                profile.setLastTeleportedLocation(Conversions.capture(player)));
    }

    private record WorldAccessRules(Set<String> whitelist, Set<String> blacklist) {
        static WorldAccessRules allowAll() {
            return new WorldAccessRules(Set.of(), Set.of());
        }

        static WorldAccessRules from(TeleportationConfig config) {
            if (config == null) {
                return allowAll();
            }
            return new WorldAccessRules(
                    normalize(config.worldWhitelist),
                    normalize(config.worldBlacklist));
        }

        boolean allows(String world) {
            String key = normalize(world);
            if (key.isEmpty()) {
                return false;
            }
            if (blacklist.contains(key)) {
                return false;
            }
            return whitelist.isEmpty() || whitelist.contains(key);
        }

        private static Set<String> normalize(Collection<String> worlds) {
            if (worlds == null || worlds.isEmpty()) {
                return Set.of();
            }
            Set<String> normalized = new HashSet<>();
            for (String world : worlds) {
                String key = normalize(world);
                if (!key.isEmpty()) {
                    normalized.add(key);
                }
            }
            return Set.copyOf(normalized);
        }

        private static String normalize(String world) {
            return world == null ? "" : world.trim().toLowerCase(Locale.ROOT);
        }
    }
}
