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
     * A named reward zone: the X/Z footprint spanned by two corners in one
     * world, at any height (see {@link #contains}). The corner Y values are kept
     * only as the world reference and the fallback floor used when
     * {@link Rewards.SafeTeleport} is disabled.
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

        /**
         * A zone is its X/Z footprint at any height — corners are captured at
         * the admin's foot level while walking the perimeter, so their Y values
         * only ever described where that admin happened to stand, not the height
         * of the area. Testing Y made players standing on higher ground inside
         * the footprint read as outside the zone.
         */
        public boolean contains(MysticLocation pos) {
            if (pos == null || cornerA == null || cornerB == null
                    || pos.getWorld() == null || !pos.getWorld().equals(cornerA.getWorld())) {
                return false;
            }
            return between(pos.getX(), cornerA.getX(), cornerB.getX())
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
        /** Ground-safety probing for the random spot players are dropped on inside a zone. */
        public SafeTeleport safeTeleport = new SafeTeleport();
        /** No reward if the player took damage / was in combat within this many seconds. */
        public int noRewardWithinCombatSeconds = 15;

        /**
         * Safety rules for the zone teleport. A random column inside the zone's
         * footprint is scanned for the spot where the player can stand closest
         * to the zone's corner height: a solid floor block that is not blocked,
         * {@link #requiredHeadroom} air blocks above it, and no blocked fluid in
         * the floor or body. The corner Y is a reference height only, never the
         * landing Y — corners are captured at foot level while walking, so on
         * uneven ground they sit below the surface and would drop players
         * inside terrain.
         */
        public static final class SafeTeleport {
            /** Probe the terrain; when disabled players land on the zone floor Y instead. */
            public boolean enabled = true;
            /** Columns tried per teleport before giving up on the zone. */
            public int attempts = 12;
            /** Air blocks required above the floor for the player's body. */
            public int requiredHeadroom = 2;
            /**
             * How far above/below the zone's corner height a landing spot may
             * sit. The column scan keeps the candidate closest to that height,
             * so a roofed or cave zone lands players on its floor instead of on
             * top of the structure, while uneven ground inside the footprint
             * still resolves. Raise it for zones spanning tall terrain.
             */
            public int verticalSearchRange = 24;
            /**
             * Block-type asset ids that may not be the floor a player lands on
             * (the file name of the block asset, e.g. {@code Fluid_Lava}).
             * Unknown ids are reported once at load and ignored.
             */
            public List<String> blockedBlocks = new ArrayList<>(List.of(
                    "Fluid_Lava", "Trap_Spike", "Plant_Cactus"));
            /**
             * Fluid asset ids that may not be in the floor or body blocks (e.g.
             * {@code Lava}, {@code Poison}). Fluids left off this list are
             * allowed, so water AFK pools still work.
             */
            public List<String> blockedFluids = new ArrayList<>(List.of(
                    "Lava", "Lava_Source", "Fire", "Poison", "Poison_Source", "Tar", "Tar_Source"));
        }

        /** @deprecated pre-zone-list corners; converted into {@link #zones} on load. */
        @Deprecated
        public MysticLocation zoneCornerA;
        /** @deprecated pre-zone-list corners; converted into {@link #zones} on load. */
        @Deprecated
        public MysticLocation zoneCornerB;
    }
}
