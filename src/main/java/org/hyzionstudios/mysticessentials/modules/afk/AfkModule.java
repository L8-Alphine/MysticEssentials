package org.hyzionstudios.mysticessentials.modules.afk;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.hyzionstudios.mysticessentials.api.model.MysticLocation;
import org.hyzionstudios.mysticessentials.api.service.AfkService;
import org.hyzionstudios.mysticessentials.core.module.AbstractMysticModule;
import org.hyzionstudios.mysticessentials.platform.Conversions;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommand;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommandSender;

import com.hypixel.hytale.server.core.event.events.player.PlayerChatEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerMouseButtonEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Idle-state tracking: manual {@code /afk}, automatic AFK after inactivity, and
 * the plumbing for the AFK Rewards submodule.
 *
 * <p>Activity is detected from three signals — movement (position polled by the
 * idle-check task, since the server exposes no player-move event), mouse/attack
 * clicks ({@code PlayerMouseButtonEvent}), and chat ({@code PlayerChatEvent}).
 * A player who is idle past the configured threshold, without the bypass
 * permission, is flagged AFK; any activity clears it.</p>
 */
public final class AfkModule extends AbstractMysticModule implements AfkService {

    /** Distance (blocks) a player must move for it to count as activity. */
    private static final double MOVE_EPSILON_SQ = 0.1 * 0.1;

    private static final class Activity {
        boolean afk;
        String reason;
        long lastActivityMillis = System.currentTimeMillis();
        MysticLocation lastPosition;
    }

    private static final class RewardState {
        double session;
        double dailyTotal;
        long dayIndex = -1;
    }

    private final Map<UUID, Activity> states = new ConcurrentHashMap<>();
    private final Map<UUID, RewardState> rewards = new ConcurrentHashMap<>();
    private AfkConfig config;
    private ScheduledFuture<?> idleTask;
    private ScheduledFuture<?> rewardTask;

    public AfkModule() {
        super("afk", "AFK", "1.0.0");
    }

    @Override
    public void onEnable() {
        config = core.configManager().loadModuleConfig(id(), AfkConfig.class, new AfkConfig());
        registerCommand(new AfkCommand());

        // Activity signals: clicks (Void-keyed event) and chat (async event).
        core.platform().onEvent(PlayerMouseButtonEvent.class,
                (PlayerMouseButtonEvent event) -> markActivity(event.getPlayerRefComponent().getUuid()));
        core.platform().onAsyncEvent(PlayerChatEvent.class, future -> future.thenApply(event -> {
            markActivity(event.getSender().getUuid());
            return event;
        }));

        if (config.autoAfkEnabled) {
            long interval = Math.max(1, config.checkIntervalSeconds);
            idleTask = core.scheduler().runRepeating(this::checkIdle, interval, interval, TimeUnit.SECONDS);
        }
        if (config.rewards != null && config.rewards.enabled) {
            long interval = Math.max(5, config.rewards.intervalSeconds);
            rewardTask = core.scheduler().runRepeating(this::grantRewards, interval, interval, TimeUnit.SECONDS);
            log("AFK Rewards enabled (" + interval + "s interval).");
        }
    }

    @Override
    public void onReload() {
        config = core.configManager().loadModuleConfig(id(), AfkConfig.class, new AfkConfig());
    }

    @Override
    public void onDisable() {
        if (idleTask != null) {
            idleTask.cancel(false);
            idleTask = null;
        }
        if (rewardTask != null) {
            rewardTask.cancel(false);
            rewardTask = null;
        }
        states.clear();
        rewards.clear();
    }

    // ----- AfkService --------------------------------------------------------

    @Override
    public boolean isAfk(UUID player) {
        Activity state = states.get(player);
        return state != null && state.afk;
    }

    @Override
    public void setAfk(UUID player, boolean afk, String reason) {
        Activity state = states.computeIfAbsent(player, k -> new Activity());
        state.afk = afk;
        state.reason = reason;
        state.lastActivityMillis = System.currentTimeMillis();
        core.platform().findPlayer(player).ifPresent(ref -> state.lastPosition = Conversions.capture(ref));
    }

    @Override
    public void markActivity(UUID player) {
        Activity state = states.computeIfAbsent(player, k -> new Activity());
        boolean wasAfk = state.afk;
        state.afk = false;
        state.reason = null;
        state.lastActivityMillis = System.currentTimeMillis();
        if (wasAfk) {
            core.platform().findPlayer(player).ifPresent(ref -> {
                core.getMessageService().sendKey(ref, "afk-returned");
                announce(ref.getUuid(), ref.getUsername() + " is no longer AFK");
            });
        }
    }

    @Override
    public long idleSeconds(UUID player) {
        Activity state = states.get(player);
        if (state == null || state.afk) {
            return 0;
        }
        return (System.currentTimeMillis() - state.lastActivityMillis) / 1000L;
    }

    // ----- Auto-AFK idle check -----------------------------------------------

    private void checkIdle() {
        long thresholdMillis = Math.max(1, config.autoAfkSeconds) * 1000L;
        long now = System.currentTimeMillis();
        Set<UUID> online = new HashSet<>();

        for (PlayerRef player : core.platform().onlinePlayers()) {
            UUID uuid = player.getUuid();
            online.add(uuid);
            if (config.bypassPermission != null && player.hasPermission(config.bypassPermission)) {
                continue;
            }
            Activity state = states.computeIfAbsent(uuid, k -> new Activity());
            MysticLocation current = safeCapture(player);

            if (current != null && state.lastPosition != null && hasMoved(state.lastPosition, current)) {
                state.lastPosition = current;
                markActivity(uuid);
                continue;
            }
            if (current != null) {
                state.lastPosition = current;
            }
            if (!state.afk && now - state.lastActivityMillis >= thresholdMillis) {
                state.afk = true;
                state.reason = "auto";
                core.getMessageService().sendKey(player, "afk-now");
                announce(player.getUuid(), player.getUsername() + " is now AFK");
            }
        }
        // Drop tracking for players who are no longer online.
        states.keySet().retainAll(online);
    }

    private MysticLocation safeCapture(PlayerRef player) {
        try {
            return Conversions.capture(player);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean hasMoved(MysticLocation a, MysticLocation b) {
        if (a.getWorld() != null && !a.getWorld().equals(b.getWorld())) {
            return true;
        }
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return (dx * dx + dy * dy + dz * dz) > MOVE_EPSILON_SQ;
    }

    /** Broadcasts an AFK status line, unless the player is vanished. */
    private void announce(UUID player, String message) {
        if (config.announce && core.getAnnouncementService() != null
                && !core.vanish().isVanished(player)) {
            core.getAnnouncementService().broadcast("&8* &7" + message);
        }
    }

    // ----- AFK Rewards submodule ---------------------------------------------

    private void grantRewards() {
        AfkConfig.Rewards r = config.rewards;
        long today = System.currentTimeMillis() / 86_400_000L;

        for (PlayerRef player : core.platform().onlinePlayers()) {
            UUID uuid = player.getUuid();
            if (!isAfk(uuid)) {
                continue; // only reward players who are actually AFK
            }
            if (r.permission != null && !player.hasPermission(r.permission)) {
                continue;
            }
            RewardState state = rewards.computeIfAbsent(uuid, k -> new RewardState());
            if (state.dayIndex != today) {
                state.dayIndex = today;
                state.dailyTotal = 0;
            }
            double remaining = remainingReward(r, state);
            if (remaining <= 0) {
                continue;
            }
            if (r.requireInZone && !inZone(player, r)) {
                continue;
            }
            // Anti-abuse: no reward if recently damaged / in combat.
            core.platform().lastDamageTime(player).thenAccept(last -> {
                if (last != null && Duration.between(last, Instant.now()).getSeconds() < r.noRewardWithinCombatSeconds) {
                    return;
                }
                double amount = Math.min(r.amountPerInterval, remaining);
                if (amount <= 0 || !isAfk(uuid)) {
                    return;
                }
                core.getEconomyService().deposit(uuid, amount);
                state.session += amount;
                state.dailyTotal += amount;
                core.getMessageService().sendKey(player, "afk-reward",
                        Map.of("amount", core.getEconomyService().format(amount)));
            });
        }
        rewards.keySet().retainAll(onlineUuids());
    }

    private double remainingReward(AfkConfig.Rewards r, RewardState state) {
        double sessionRemaining = r.maxSessionReward <= 0 ? Double.MAX_VALUE : r.maxSessionReward - state.session;
        double dailyRemaining = r.maxDailyReward <= 0 ? Double.MAX_VALUE : r.maxDailyReward - state.dailyTotal;
        return Math.min(sessionRemaining, dailyRemaining);
    }

    private boolean inZone(PlayerRef player, AfkConfig.Rewards r) {
        if (r.zoneCornerA == null || r.zoneCornerB == null) {
            return false;
        }
        MysticLocation pos = safeCapture(player);
        if (pos == null || pos.getWorld() == null || !pos.getWorld().equals(r.zoneCornerA.getWorld())) {
            return false;
        }
        return between(pos.getX(), r.zoneCornerA.getX(), r.zoneCornerB.getX())
                && between(pos.getY(), r.zoneCornerA.getY(), r.zoneCornerB.getY())
                && between(pos.getZ(), r.zoneCornerA.getZ(), r.zoneCornerB.getZ());
    }

    private static boolean between(double v, double a, double b) {
        return v >= Math.min(a, b) && v <= Math.max(a, b);
    }

    private Set<UUID> onlineUuids() {
        Set<UUID> ids = new HashSet<>();
        for (PlayerRef player : core.platform().onlinePlayers()) {
            ids.add(player.getUuid());
        }
        return ids;
    }

    // ----- Command -----------------------------------------------------------

    private final class AfkCommand extends MysticCommand {
        AfkCommand() {
            super(AfkModule.this.core, "afk", "Toggle your AFK status.");
            allowExtraArguments();
            requirePermission("mysticessentials.afk.use");
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            UUID uuid = sender.uuid();
            if (isAfk(uuid)) {
                markActivity(uuid);
                return;
            }
            String reason = sender.args().length > 0 ? String.join(" ", sender.args()) : null;
            setAfk(uuid, true, reason);
            announce(uuid, sender.name() + " is now AFK" + (reason == null ? "" : ": " + reason));
            sender.replyKey("afk-now");
        }
    }
}
