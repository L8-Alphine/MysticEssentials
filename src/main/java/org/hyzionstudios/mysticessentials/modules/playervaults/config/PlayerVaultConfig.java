package org.hyzionstudios.mysticessentials.modules.playervaults.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Typed model of {@code modules/playervaults/config.json}. Field names map
 * directly to JSON keys via Gson; the defaults here define the file written on
 * first run. The module ships <b>disabled</b>: {@link #enabled} must be flipped
 * here <i>and</i> {@code "playervaults": true} set in the main config's modules
 * map before it activates.
 *
 * <p>Grouping mirrors the module design bible: top-level vault limits, a
 * {@link CrossServer} block for Redis/locking, a {@link Saving} block for the
 * write/backup workflow, a {@link Ui} block, an {@link Admin} block, and an
 * {@link Editor} block for metadata-customization toggles.</p>
 */
public final class PlayerVaultConfig {

    /** Bumped when a migration is registered for the {@code playervaults} config id. */
    public int configVersion = 1;

    /** Master switch. When false the module registers nothing. */
    public boolean enabled = false;

    /** Vaults granted with no {@code vaults.vault.<n>} permission at all. */
    public int defaultVaults = 1;
    /** Rows granted with no {@code vaults.rows.<n>} permission at all. */
    public int defaultRows = 3;
    /** Hard ceiling on vault numbers regardless of permission. */
    public int maxVaults = 100;
    /** Hard ceiling on rows-per-vault regardless of permission (platform-safe cap). */
    public int maxRows = 6;
    /** Slots per row in the container UI. */
    public int slotsPerRow = 9;

    /** Metadata-editing toggles (each still also gated by an editor permission). */
    public boolean allowVaultRenaming = true;
    public boolean allowVaultColors = true;
    public boolean allowVaultIcons = true;
    public boolean allowVaultDescriptions = true;
    /** Allow any (non-blacklisted) item as an icon; when false only {@link #blockedIconItemIds} logic inverts. */
    public boolean allowAnyItemAsIcon = true;
    /** Consume one of the item when it is set as an icon (default: copy metadata only). */
    public boolean consumeIconItem = false;
    /** Show vaults the viewer lacks permission for as locked cards (vs hiding them). */
    public boolean showLockedVaults = true;
    /** Enforce {@link #blockedItemIds} on insert/move/restore/migration. */
    public boolean preventStorageOfBlacklistedItems = true;
    /** Item ids that may never be stored in a vault. */
    public List<String> blockedItemIds = new ArrayList<>();
    /** Item ids that may never be used as a vault icon. */
    public List<String> blockedIconItemIds = new ArrayList<>();
    /** Max characters in a custom vault name. */
    public int maxNameLength = 32;
    /** Max characters in a custom vault description. */
    public int maxDescriptionLength = 96;
    /** Item id shown on cards with no custom icon set ({@code ""} = empty slot). */
    public String defaultIconItemId = "Furniture_Crude_Chest_Small";
    /** Max item results shown in the editor's icon search picker. */
    public int iconPickerResultLimit = 45;

    public CrossServer crossServer = new CrossServer();
    public Saving saving = new Saving();
    public Ui ui = new Ui();
    public Admin admin = new Admin();

    public static final class CrossServer {
        /** Enable Redis-backed locks, cache, and pub/sub sync. */
        public boolean enabled = false;
        /** When {@link #enabled} and Redis is unavailable, refuse to open vaults (fail safe). */
        public boolean requireRedis = true;
        /** Take a distributed edit lock before opening a vault for editing. */
        public boolean lockVaults = true;
        /** Lock time-to-live; the lock auto-expires this long after the last renewal (crash safety). */
        public int lockTtlSeconds = 30;
        /** Renew the held lock this often while the vault UI is open (must be &lt; TTL). */
        public int lockRenewSeconds = 10;
        /** Allow read-only admin inspection even when the vault is locked elsewhere. */
        public boolean allowReadOnlyAdminViewWhenLocked = true;
        /** Allow admins to force-unlock a stuck lock. */
        public boolean allowAdminForceUnlock = true;
        /** Seconds a serialized vault/profile stays in the Redis cache. */
        public int cacheTtlSeconds = 300;
        /** Logical pub/sub channel for cross-server vault-update notices. */
        public String pubSubChannel = "vaults:updates";
    }

    public static final class Saving {
        /** Persist a vault when its UI closes. */
        public boolean saveOnClose = true;
        /** Auto-save the active vault every N seconds (0 disables; crash protection). */
        public int saveIntervalSeconds = 30;
        /** Write every accepted save straight to permanent storage (recommended for networks). */
        public boolean writeThrough = true;
        /** Keep timestamped backups. */
        public boolean saveBackups = true;
        /** Cap on retained backups per vault (oldest dropped first). */
        public int maxBackupsPerVault = 10;
        /** Store a conflict snapshot when a version mismatch is detected on save. */
        public boolean conflictSnapshots = true;
    }

    public static final class Ui {
        public boolean useCustomVaultListUi = true;
        public boolean useCustomEditorUi = true;
        public boolean useScrollableVaultContent = true;
        public boolean showVaultStats = true;
        public boolean showLastOpened = true;
    }

    public static final class Admin {
        public boolean logAdminOpens = true;
        public boolean logAdminEdits = true;
        /** Tell an online owner when staff opens their vault. */
        public boolean notifyOnlinePlayerWhenAdminOpensVault = false;
        /** EDIT or READ_ONLY — the mode {@code /pv <n> <player>} uses when none is specified. */
        public String defaultAdminMode = "EDIT";
        /** Cap on retained admin-log entries per player (oldest dropped first). */
        public int maxLogEntriesPerPlayer = 200;
    }
}
