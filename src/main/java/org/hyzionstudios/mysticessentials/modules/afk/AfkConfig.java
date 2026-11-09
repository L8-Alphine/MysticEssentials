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

    /** A named cuboid reward zone spanning two corners in one world. */
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

        /** Tests the whole blocks between both corners, inclusive on all three axes. */
        public boolean contains(MysticLocation pos) {
            if (pos == null || cornerA == null || cornerB == null
                    || pos.getWorld() == null || !pos.getWorld().equals(cornerA.getWorld())) {
                return false;
            }
            int x = (int) Math.floor(pos.getX());
            int y = (int) Math.floor(pos.getY());
            int z = (int) Math.floor(pos.getZ());
            return x >= minBlock(cornerA.getX(), cornerB.getX()) && x <= maxBlock(cornerA.getX(), cornerB.getX())
                    && y >= minBlock(cornerA.getY(), cornerB.getY()) && y <= maxBlock(cornerA.getY(), cornerB.getY())
                    && z >= minBlock(cornerA.getZ(), cornerB.getZ()) && z <= maxBlock(cornerA.getZ(), cornerB.getZ());
        }

        /*
         * The zone covers WHOLE blocks: every block from the one the first
         * corner was set in to the one the second was set in, inclusive. That
         * is exactly the box /afkzone pos1 and pos2 draw while you mark it out.
         * The corners are stored as the admin's exact foot position, and the
         * zone used to test those raw floats -- so the edge blocks you stood
         * on were only partly inside, a player at the edge of a marked block
         * could read as outside, and the size message rounded a third way.
         * Reading the stored floats as the blocks they sit in fixes existing
         * zones too, with no change to the saved data.
         */

        /** The lowest block coordinate the zone covers on one axis. */
        public static int minBlock(double a, double b) {
            return (int) Math.floor(Math.min(a, b));
        }

        /** The highest block coordinate the zone covers on one axis, inclusive. */
        public static int maxBlock(double a, double b) {
            return (int) Math.floor(Math.max(a, b));
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
         * Safety rules for the zone teleport. A random X/Z column inside the
         * zone is scanned, within the selected Y bounds, for the spot where the
         * player can stand closest to the cuboid's midpoint: a solid floor block
         * that is not blocked, {@link #requiredHeadroom} air blocks above it,
         * and no blocked fluid in the floor or body.
         */
        public static final class SafeTeleport {
            /** Probe the terrain; when disabled players land on the zone floor Y instead. */
            public boolean enabled = true;
            /** Columns tried per teleport before giving up on the zone. */
            public int attempts = 12;
            /** Air blocks required above the floor for the player's body. */
            public int requiredHeadroom = 2;
            /**
             * How far above/below the zone's midpoint a landing spot may sit.
             * The scan is also clamped to the zone's selected Y bounds. Raise
             * this for zones taller than twice the configured range.
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
             * Fluid asset ids that may not be in the floor or the feet block
             * (e.g. {@code Lava}, {@code Poison}). Fluids left off this list are
             * allowed there, so a shallow water AFK pool still works. The cells
             * above the feet must hold no fluid at all whatever this says: a
             * spot with water over the head is never chosen, because an AFK
             * player cannot swim up and would slowly drown.
             */
            public List<String> blockedFluids = new ArrayList<>(List.of(
                    "Lava", "Lava_Source", "Fire", "Poison", "Poison_Source", "Tar", "Tar_Source"));
        }

        /** Legacy pre-zone-list corner, read only to convert it into {@link #zones} on load. */
        public MysticLocation zoneCornerA;
        /** Legacy pre-zone-list corner, read only to convert it into {@link #zones} on load. */
        public MysticLocation zoneCornerB;
    }
}
