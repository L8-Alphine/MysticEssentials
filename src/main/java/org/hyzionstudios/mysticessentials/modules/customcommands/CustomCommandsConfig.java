package org.hyzionstudios.mysticessentials.modules.customcommands;

import java.util.ArrayList;
import java.util.List;

/**
 * Typed model of {@code modules/customcommands/config.json} — the module's
 * global settings. Field names map to JSON keys via Gson; defaults define the
 * file generated on first run.
 *
 * <p>Ships with {@code enabled=false}: like the Tutorial module, Custom
 * Commands is disabled by default twice — in the main config's modules map and
 * here. Flip both, then restart (or {@code /customcommands reload} when only
 * this flag changed).</p>
 */
public final class CustomCommandsConfig {

    public int configVersion = 1;

    /** Runtime master switch; when off, stubs and admin commands reply module-disabled. */
    public boolean enabled = false;

    /**
     * Server display name for the {@code {server_name}} placeholder and
     * {@code server} conditions. Blank = fall back to the Redis
     * {@code storage.redis.serverId} from the main config.
     */
    public String serverName = "";

    /** Generate the example command files (rules/discord/vote/store) on first startup. */
    public boolean generateExamples = true;

    /**
     * Allow a custom command to shadow an already-registered command (native,
     * Mystic Essentials core, or another plugin). Default off: conflicting
     * definitions are reported by {@code /customcommands validate} and skipped.
     */
    public boolean allowOverrideExisting = false;

    public Safety safety = new Safety();
    public Cooldowns cooldowns = new Cooldowns();
    public CrossServer crossServer = new CrossServer();
    public Logging logging = new Logging();

    /** Hard limits that keep owner-authored command chains from harming the server. */
    public static final class Safety {
        /** Max actions executed by one invocation, including nested/recursive calls. */
        public int maxActionsPerChain = 32;
        /** Max nesting depth when custom commands call other custom commands. */
        public int maxExecutionDepth = 8;
        /** Commands that {@code command} actions may never dispatch (no leading slash). */
        public List<String> blockedCommands = new ArrayList<>(List.of("stop", "shutdown", "op", "deop"));
    }

    public static final class Cooldowns {
        /** Persist cooldowns through the storage abstraction so they survive restarts. */
        public boolean persist = true;
    }

    /** Effective only when Redis is enabled in the main config. */
    public static final class CrossServer {
        /** Broadcast cooldown starts to the network so shared-storage servers stay in sync. */
        public boolean syncCooldowns = true;
        /** A {@code /customcommands reload} on one server reloads the others too. */
        public boolean syncReloads = true;
    }

    public static final class Logging {
        /** Log a console line for every custom command execution. */
        public boolean usage = false;
        /** Append detailed execution records to {@code logs/customcommands.log}. */
        public boolean audit = false;
    }
}
