package org.hyzionstudios.mysticessentials.core.profile;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.hyzionstudios.mysticessentials.api.model.PlayerProfile;
import org.hyzionstudios.mysticessentials.api.service.AfkService;
import org.hyzionstudios.mysticessentials.api.service.PlaytimeService;
import org.hyzionstudios.mysticessentials.core.MysticCore;

import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Default {@link PlaytimeService}. Keeps a per-session clock for every online
 * player and folds elapsed time into their profile's playtime counters, split
 * into idle/active by the AFK module's current verdict.
 *
 * <p>Accrual runs on a scheduler tick (and again on demand whenever a value is
 * read or the player quits), so the counters are never more than one tick
 * behind and a crash loses at most one interval. Sub-second remainders are
 * carried between accruals, so a long session does not drift.</p>
 */
public final class PlaytimeTracker implements PlaytimeService {

    /** How often elapsed time is folded into profiles. */
    private static final long ACCRUAL_INTERVAL_SECONDS = 30;

    private final MysticCore core;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private ScheduledFuture<?> task;

    public PlaytimeTracker(MysticCore core) {
        this.core = core;
    }

    /** Per-session clock. All mutation happens under the session's own monitor. */
    private static final class Session {
        private final long startedMillis;
        private long lastAccrualMillis;
        /** Elapsed milliseconds not yet worth a whole second. */
        private long carryMillis;

        Session(long now) {
            this.startedMillis = now;
            this.lastAccrualMillis = now;
        }
    }

    /** Starts the accrual tick and adopts anyone already online (e.g. after a reload). */
    public void start() {
        long now = System.currentTimeMillis();
        for (PlayerRef player : core.platform().onlinePlayers()) {
            sessions.putIfAbsent(player.getUuid(), new Session(now));
        }
        task = core.scheduler().runRepeating(this::accrueAll,
                ACCRUAL_INTERVAL_SECONDS, ACCRUAL_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /** Flushes every session and stops ticking. */
    public void stop() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
        accrueAll();
        sessions.clear();
    }

    /** Begins counting for a player who just connected. */
    public void onJoin(UUID player) {
        sessions.put(player, new Session(System.currentTimeMillis()));
    }

    /** Credits the final slice of the session and stops counting. */
    public void onQuit(UUID player) {
        accrue(player);
        sessions.remove(player);
    }

    @Override
    public long totalPlaytimeSeconds(UUID player) {
        accrue(player);
        PlayerProfile profile = profile(player);
        return profile == null ? 0 : profile.getTotalPlaytimeSeconds();
    }

    @Override
    public long activePlaytimeSeconds(UUID player) {
        accrue(player);
        PlayerProfile profile = profile(player);
        return profile == null ? 0 : profile.getActivePlaytimeSeconds();
    }

    @Override
    public long idlePlaytimeSeconds(UUID player) {
        accrue(player);
        PlayerProfile profile = profile(player);
        return profile == null ? 0 : profile.getIdlePlaytimeSeconds();
    }

    @Override
    public long sessionSeconds(UUID player) {
        Session session = sessions.get(player);
        if (session == null) {
            return 0;
        }
        return Math.max(0, (System.currentTimeMillis() - session.startedMillis) / 1000);
    }

    private void accrueAll() {
        for (UUID player : sessions.keySet()) {
            accrue(player);
        }
    }

    /**
     * Folds the time since this session's last accrual into the player's profile.
     * Does nothing (and keeps the clock running) while the profile is still
     * loading, so that time is credited on a later pass rather than lost.
     */
    private void accrue(UUID player) {
        Session session = player == null ? null : sessions.get(player);
        if (session == null) {
            return;
        }
        PlayerProfile profile = profile(player);
        if (profile == null) {
            return;
        }
        boolean idle = isAfk(player);
        synchronized (session) {
            long now = System.currentTimeMillis();
            long elapsed = now - session.lastAccrualMillis + session.carryMillis;
            if (elapsed <= 0) {
                // Clock moved backwards (time sync): resync without crediting.
                session.lastAccrualMillis = now;
                session.carryMillis = 0;
                return;
            }
            long seconds = elapsed / 1000;
            session.carryMillis = elapsed % 1000;
            session.lastAccrualMillis = now;
            if (seconds <= 0) {
                return;
            }
            profile.setTotalPlaytimeSeconds(profile.getTotalPlaytimeSeconds() + seconds);
            if (idle) {
                profile.setIdlePlaytimeSeconds(profile.getIdlePlaytimeSeconds() + seconds);
            } else {
                profile.setActivePlaytimeSeconds(profile.getActivePlaytimeSeconds() + seconds);
            }
        }
    }

    private PlayerProfile profile(UUID player) {
        if (player == null) {
            return null;
        }
        return core.getPlayerProfileService().getCached(player).orElse(null);
    }

    /** AFK state, or {@code false} when the AFK module is disabled. */
    private boolean isAfk(UUID player) {
        AfkService afk = core.getAfkService();
        if (afk == null) {
            return false;
        }
        try {
            return afk.isAfk(player);
        } catch (Throwable t) {
            return false;
        }
    }
}
