package org.hyzionstudios.mysticessentials.modules.playervaults.service;

import org.hyzionstudios.mysticessentials.api.Permissions;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.modules.playervaults.config.PlayerVaultConfig;

import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Resolves permission-based vault and row limits, and gates editor/admin
 * actions. Numeric limits use the "highest granted number wins" rule, mirroring
 * the Core {@code PermissionService.limit} probe: granting
 * {@code mysticessentials.vaults.vault.5} yields access to vaults 1–5.
 *
 * <p>All limits are clamped to the config ceilings ({@code maxVaults},
 * {@code maxRows}) so a stray {@code .*} or a huge granted number can never
 * produce an unsafe container. Wildcard nodes ({@code vault.*}, {@code rows.*})
 * resolve to the config maximum.</p>
 */
public final class PlayerVaultPermissionService {

    /** Upper bound for the numeric-permission probe, independent of config caps. */
    private static final int PROBE_LIMIT = 256;

    private final MysticCore core;
    private PlayerVaultConfig config;

    public PlayerVaultPermissionService(MysticCore core, PlayerVaultConfig config) {
        this.core = core;
        this.config = config;
    }

    public void updateConfig(PlayerVaultConfig config) {
        this.config = config;
    }

    /** @return how many vaults (1..N) this player may access, clamped to {@code maxVaults}. */
    public int allowedVaultCount(PlayerRef player) {
        return resolveHighest(player, Permissions.VAULTS_VAULT_BASE, config.defaultVaults, config.maxVaults);
    }

    /** @return the rows this player's vaults expose, clamped to {@code maxRows}. */
    public int allowedRows(PlayerRef player) {
        return resolveHighest(player, Permissions.VAULTS_ROWS_BASE, config.defaultRows, config.maxRows);
    }

    /** @return {@code true} if {@code vaultNumber} is within range and the player has the number permission. */
    public boolean canAccessVault(PlayerRef player, int vaultNumber) {
        if (vaultNumber < 1 || vaultNumber > config.maxVaults) {
            return false;
        }
        return vaultNumber <= allowedVaultCount(player);
    }

    // ----- Editor gates ---------------------------------------------------------

    public boolean canEditName(PlayerRef player) {
        return config.allowVaultRenaming && has(player, Permissions.VAULTS_EDITOR_NAME);
    }

    public boolean canEditColor(PlayerRef player) {
        return config.allowVaultColors && has(player, Permissions.VAULTS_EDITOR_COLOR);
    }

    public boolean canEditHexColor(PlayerRef player) {
        return canEditColor(player) && has(player, Permissions.VAULTS_EDITOR_COLOR_HEX);
    }

    public boolean canEditIcon(PlayerRef player) {
        return config.allowVaultIcons && has(player, Permissions.VAULTS_EDITOR_ICON);
    }

    public boolean canEditDescription(PlayerRef player) {
        return config.allowVaultDescriptions && has(player, Permissions.VAULTS_EDITOR_DESCRIPTION);
    }

    public boolean canResetMetadata(PlayerRef player) {
        return has(player, Permissions.VAULTS_EDITOR_RESET);
    }

    public boolean canOpenEditor(PlayerRef player) {
        return has(player, Permissions.VAULTS_EDITOR)
                || canEditName(player) || canEditColor(player)
                || canEditIcon(player) || canEditDescription(player);
    }

    // ----- Admin gates ----------------------------------------------------------

    public boolean canAdminOpen(PlayerRef player) {
        return hasAdmin(player, Permissions.VAULTS_ADMIN_OPEN);
    }

    public boolean canAdminOpenOffline(PlayerRef player) {
        return hasAdmin(player, Permissions.VAULTS_ADMIN_OPEN_OFFLINE);
    }

    public boolean canAdminEdit(PlayerRef player) {
        return hasAdmin(player, Permissions.VAULTS_ADMIN_EDIT);
    }

    public boolean canAdminReadOnly(PlayerRef player) {
        return hasAdmin(player, Permissions.VAULTS_ADMIN_READONLY);
    }

    public boolean canForceUnlock(PlayerRef player) {
        return config.crossServer.allowAdminForceUnlock && hasAdmin(player, Permissions.VAULTS_ADMIN_UNLOCK);
    }

    public boolean canRestore(PlayerRef player) {
        return hasAdmin(player, Permissions.VAULTS_ADMIN_RESTORE);
    }

    public boolean canViewLogs(PlayerRef player) {
        return hasAdmin(player, Permissions.VAULTS_ADMIN_VIEWLOGS);
    }

    /** View/recover items beyond the owner's current row/vault limits (overflow recovery). */
    public boolean canBypassLimit(PlayerRef player) {
        return hasAdmin(player, Permissions.VAULTS_ADMIN_BYPASSLIMIT);
    }

    // ----- Internals ------------------------------------------------------------

    private boolean has(PlayerRef player, String node) {
        try {
            return player.hasPermission(node);
        } catch (Throwable t) {
            return false;
        }
    }

    /** An action passes if the specific node OR the {@code vaults.admin} parent is granted. */
    private boolean hasAdmin(PlayerRef player, String node) {
        return has(player, Permissions.VAULTS_ADMIN) || has(player, node);
    }

    /**
     * Probes {@code <base>.<n>} for the highest granted number, honouring
     * {@code <base>.*} as the config max, falling back to {@code fallback} when
     * nothing is granted, and clamping the result into {@code [1, cap]}.
     */
    private int resolveHighest(PlayerRef player, String base, int fallback, int cap) {
        int clampedCap = Math.max(1, cap);
        if (has(player, base + ".*")) {
            return clampedCap;
        }
        int highest = 0;
        int probeCeiling = Math.min(PROBE_LIMIT, clampedCap);
        for (int n = 1; n <= probeCeiling; n++) {
            if (has(player, base + "." + n)) {
                highest = n;
            }
        }
        int resolved = highest > 0 ? highest : fallback;
        return Math.max(1, Math.min(resolved, clampedCap));
    }
}
