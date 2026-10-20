package org.hyzionstudios.mysticessentials.modules.lootbox.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Persisted settings for {@code modules/lootbox/config.json}. Pools and keys are
 * not configured here: they are authored as draft files under
 * {@code modules/lootbox/lootboxes/} and {@code modules/lootbox/keys/} and frozen
 * into immutable revisions by {@code /lootbox publish}.
 *
 * <p>Ships disabled: it must be enabled in both the main config's modules map and
 * here.</p>
 */
public final class LootBoxConfig {

    public int configVersion = 1;

    /** Master switch. The main config's {@code modules.lootbox} must also be true. */
    public boolean enabled = false;

    /**
     * {@code FREE_GAMEPLAY_ONLY} (default; rejects grants marked PAID),
     * {@code PAID_RANDOM_RESTRICTED} (only with {@link #allowPaidRandomProfile}), or
     * {@code DISABLED} (previews and receipts stay readable, opening is off).
     */
    public String policyProfile = "FREE_GAMEPLAY_ONLY";

    /**
     * Must be true before {@code PAID_RANDOM_RESTRICTED} takes effect. Never enable on
     * an All Ages server: paid random items are prohibited there.
     */
    public boolean allowPaidRandomProfile = false;

    public Command command = new Command();
    public Opening opening = new Opening();
    public Keys keys = new Keys();
    public Crates crates = new Crates();
    public Rewards rewards = new Rewards();
    public Simulation simulation = new Simulation();
    public Display display = new Display();
    public Audit audit = new Audit();

    public static final class Command {
        public String name = "lootbox";
        public List<String> aliases = new ArrayList<>(List.of("lb"));
    }

    public static final class Opening {
        /** Minimum gap between two openings by the same player. */
        public long minIntervalMs = 1500L;
        /** How long a confirm request id is remembered for duplicate suppression. */
        public long requestDedupeMinutes = 1440L;
        /** Length of the reveal animation; 0 reveals immediately. */
        public long revealDurationMs = 4000L;
        /** Reveal animation frame rate (bounded by spike S7's measurement). */
        public int revealFramesPerSecond = 10;
        public boolean allowSkip = true;
        /** How many receipts each player keeps for /lootbox history. */
        public int historySize = 50;
    }

    public static final class Keys {
        /** {@code VIRTUAL_FIRST} or {@code PHYSICAL_FIRST}: which key a player spends when holding both. */
        public String consumeOrder = "VIRTUAL_FIRST";
        /** Physical keys granted to an offline player: {@code PENDING_INBOX} or {@code VIRTUAL_CREDIT}. */
        public String offlineGrant = "PENDING_INBOX";
        /** Source recorded for console grants. Never wire store deliveries to key grants on All Ages servers. */
        public String consoleGrantSource = "ADMIN_GRANT";
        /** Most keys one {@code /lootbox key give} may issue. */
        public int maxGrantPerCommand = 64;
    }

    public static final class Crates {
        /** Players cannot break crate blocks; staff remove them with /lootbox crate remove. */
        public boolean preventPlayerBreak = true;
        /** Cancel damage to crate blocks, including unattributed explosions. */
        public boolean preventDamage = true;
        /** How far /lootbox crate convert looks for the targeted block. */
        public double selectReach = 10.0;

        /** How often idle particles are refreshed while players are near a crate (at least 500). */
        public long idleEffectIntervalMs = 2000L;
        /** How far from a crate players see its particles. */
        public double effectRadius = 24.0;
        /** Effects a crate starts with when it is made in the crate editor; blank means none. */
        public String defaultIdleParticle = "Block_Treasure_Sparks";
        public String defaultOpenParticle = "Block_Break_Treasure";
        public String defaultOpenSound = "SFX_Chest_Legendary_Open";
        /**
         * Floating crate names through a nameplate holder entity. UNVERIFIED in game:
         * the nameplate spike (S4) was never run, so this is off until someone has
         * seen it work. Nameplates are global; they can't be shown per player.
         */
        public boolean nameplates = false;
        /** Height of the floating name above the crate block's base, in blocks. */
        public double nameplateHeight = 1.25;
        /**
         * Let plain blocks (no Use interaction) become crates by swapping them for a
         * generated look-alike in mods/MysticEssentialsGenerated/. UNVERIFIED in game:
         * the server side passed spike S2, but nobody has seen a swapped block in a
         * client yet. Off: plain blocks are refused, as before. Existing generated
         * crates keep working and still restore their block when this is off.
         */
        public boolean generatedVariants = false;
    }

    public static final class Rewards {
        /** Where a reward that does not fit goes: {@code PENDING_INBOX} (default). */
        public String itemOverflow = "PENDING_INBOX";
        public int pendingExpiryDays = 30;
        /** Templates a COMMAND reward may render to. A rendered command must match its pattern. */
        public List<CommandPattern> commandAllowlist = new ArrayList<>(List.of(
                new CommandPattern("examplegrant-v1", "^examplegrant [0-9a-f-]{36} [0-9]{1,4}$")));
        /** Broadcast template for rewards marked {@code announce}. Params: {player} {reward} {pool}. */
        public String announceTemplate = "&d{player} &7opened &f{pool} &7and won &6{reward}&7!";
    }

    public static final class CommandPattern {
        public String id;
        public String pattern;

        public CommandPattern() {
        }

        public CommandPattern(String id, String pattern) {
            this.id = id;
            this.pattern = pattern;
        }
    }

    public static final class Simulation {
        public long maxTrials = 1_000_000L;
    }

    public static final class Display {
        public int minDecimals = 2;
        public int maxDecimals = 8;
        /** BCP 47 tag for decimal formatting, e.g. {@code en-US} or {@code de-DE}. */
        public String locale = "en-US";
    }

    public static final class Audit {
        public int retentionDays = 400;
    }
}
