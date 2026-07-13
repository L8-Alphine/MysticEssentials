package org.hyzionstudios.mysticessentials.modules.afk;

import java.util.ArrayList;
import java.util.List;

import org.hyzionstudios.mysticessentials.api.model.MysticLocation;

/** Persisted settings for {@code modules/afk/config.json}, including AFK Rewards. */
public final class AfkConfig {

    public boolean autoAfkEnabled = true;
    /** Idle seconds before a player is flagged AFK automatically. */
    public int autoAfkSeconds = 300;
    /**
     * @deprecated the idle/zone poll now runs on a fixed 1s tick so zone
     *     entry/exit registers promptly; this interval is no longer used.
     */
    @Deprecated
    public int checkIntervalSeconds = 10;
    /** Players with this permission are never auto-AFK'd. */
    public String bypassPermission = "mysticessentials.afk.bypass.auto";
    /** Announce AFK state changes to the server. */
    public boolean announce = true;

    // ----- AFK Rewards submodule (opt-in) ------------------------------------

    public Rewards rewards = new Rewards();

    /**
     * A named cuboid reward zone spanning two corners in one world. The lowest
     * corner Y is treated as the zone floor when players are teleported in, so
     * set a corner at floor level.
     */
    public static final class Zone {
        public String name;
        public MysticLocation cornerA;
        public MysticLocation cornerB;
        /** Only players with this permission may use/earn in this zone; null/blank = everyone. */
        public String permission;
        /** Zone-specific weighted reward pool; when non-empty it replaces {@code rewards.rewardPool} here. */
        public List<RewardEntry> rewardPool;

        public Zone() {
        }

        public Zone(String name, MysticLocation cornerA, MysticLocation cornerB) {
            this.name = name;
            this.cornerA = cornerA;
            this.cornerB = cornerB;
        }

        public boolean contains(MysticLocation pos) {
            if (pos == null || cornerA == null || cornerB == null
                    || pos.getWorld() == null || !pos.getWorld().equals(cornerA.getWorld())) {
                return false;
            }
            return between(pos.getX(), cornerA.getX(), cornerB.getX())
                    && between(pos.getY(), cornerA.getY(), cornerB.getY())
                    && between(pos.getZ(), cornerA.getZ(), cornerB.getZ());
        }

        private static boolean between(double v, double a, double b) {
            return v >= Math.min(a, b) && v <= Math.max(a, b);
        }
    }

    /**
     * One weighted entry in the reward pool. {@code type} selects the payload:
     * <ul>
     *   <li>{@code money} — deposits {@code amount} (counts against the
     *       session/daily caps).</li>
     *   <li>{@code item} — gives {@code quantity} of {@code itemId} (overflow
     *       drops, like /give).</li>
     *   <li>{@code command} — runs {@code command} as the console;
     *       {@code {player}} and {@code {uuid}} placeholders are replaced.</li>
     * </ul>
     * {@code message} (optional) is sent to the player instead of the default
     * reward message; supports the usual colour codes.
     */
    public static final class RewardEntry {
        public String type = "money";
        /** Relative selection weight; entries with weight &le; 0 never roll. */
        public double weight = 1;
        public double amount;
        public String itemId;
        public int quantity = 1;
        public String command;
        public String message;
    }

    public static final class Rewards {
        /** Master toggle for the AFK Rewards submodule. */
        public boolean enabled = false;
        /** Only players with this permission earn AFK rewards. */
        public String permission = "mysticessentials.afk.rewards";
        /** How often (seconds) rewards are granted while AFK. */
        public int intervalSeconds = 60;
        /** Flat money payout per interval; used only when {@link #rewardPool} is empty. */
        public double amountPerInterval = 5.0;
        /**
         * Weighted reward pool. When non-empty, one entry is rolled per
         * interval (replacing the flat {@code amountPerInterval} payout).
         * Money entries stop rolling once the session/daily money caps are
         * reached; {@code maxRollsPerDay} caps all entry types.
         */
        public List<RewardEntry> rewardPool = new ArrayList<>();
        /** Max pool rolls per player per day across all entry types; {@code 0} = unlimited. */
        public int maxRollsPerDay = 0;
        /** Caps to curb abuse; {@code 0} disables a cap. */
        public double maxSessionReward = 500.0;
        public double maxDailyReward = 2000.0;
        /** Require the player to be inside a reward zone to earn. */
        public boolean requireInZone = false;
        /**
         * Named reward zones, managed in-game via {@code /afkzone}. While a
         * player is AFK <i>inside</i> a zone, movement does not clear their AFK
         * state (so pools/currents that nudge players keep working); movement
         * anywhere else always clears AFK.
         */
        public List<Zone> zones = new ArrayList<>();
        /**
         * Name of the zone auto-AFK teleports idle players into when several
         * zones exist. Null/blank means one is chosen automatically (same-world
         * preferred, then at random). Managed in-game via {@code /afkzone default}.
         */
        public String defaultZone;
        /** Keep players AFK while they drift around inside a zone. */
        public boolean stayAfkWhileMovingInZone = true;
        /**
         * When zones exist, {@code /afk} saves the player's location and
         * teleports them to a random spot inside a permitted zone; leaving AFK
         * (toggle, activity, or walking out) teleports them back. The saved
         * location is persisted in the player profile, so it survives restarts
         * and is restored on the next join.
         */
        public boolean teleportToZoneOnAfk = true;
        /** No reward if the player took damage / was in combat within this many seconds. */
        public int noRewardWithinCombatSeconds = 15;

        /** @deprecated pre-zone-list corners; converted into {@link #zones} on load. */
        @Deprecated
        public MysticLocation zoneCornerA;
        /** @deprecated pre-zone-list corners; converted into {@link #zones} on load. */
        @Deprecated
        public MysticLocation zoneCornerB;
    }
}
