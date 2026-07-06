package org.hyzionstudios.mysticessentials.modules.afk;

import org.hyzionstudios.mysticessentials.api.model.MysticLocation;

/** Persisted settings for {@code modules/afk/config.json}, including AFK Rewards. */
public final class AfkConfig {

    public boolean autoAfkEnabled = true;
    /** Idle seconds before a player is flagged AFK automatically. */
    public int autoAfkSeconds = 300;
    /** How often the idle check runs. */
    public int checkIntervalSeconds = 10;
    /** Players with this permission are never auto-AFK'd. */
    public String bypassPermission = "mysticessentials.afk.bypass.auto";
    /** Announce AFK state changes to the server. */
    public boolean announce = true;

    // ----- AFK Rewards submodule (opt-in) ------------------------------------

    public Rewards rewards = new Rewards();

    public static final class Rewards {
        /** Master toggle for the AFK Rewards submodule. */
        public boolean enabled = false;
        /** Only players with this permission earn AFK rewards. */
        public String permission = "mysticessentials.afk.rewards";
        /** How often (seconds) rewards are granted while AFK. */
        public int intervalSeconds = 60;
        /** Amount paid per interval (VaultUnlocked). */
        public double amountPerInterval = 5.0;
        /** Caps to curb abuse; {@code 0} disables a cap. */
        public double maxSessionReward = 500.0;
        public double maxDailyReward = 2000.0;
        /** Require the player to be inside the reward zone (two corner points). */
        public boolean requireInZone = false;
        public MysticLocation zoneCornerA;
        public MysticLocation zoneCornerB;
        /** No reward if the player took damage / was in combat within this many seconds. */
        public int noRewardWithinCombatSeconds = 15;
    }
}
