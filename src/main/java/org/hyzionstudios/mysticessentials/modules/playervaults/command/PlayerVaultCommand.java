package org.hyzionstudios.mysticessentials.modules.playervaults.command;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.hyzionstudios.mysticessentials.api.Permissions;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.modules.playervaults.PlayerVaultModule;
import org.hyzionstudios.mysticessentials.modules.playervaults.api.VaultOpenMode;
import org.hyzionstudios.mysticessentials.modules.playervaults.config.PlayerVaultConfig;
import org.hyzionstudios.mysticessentials.modules.playervaults.model.VaultAdminLogEntry;
import org.hyzionstudios.mysticessentials.modules.playervaults.service.PlayerVaultPermissionService;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommand;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommandSender;

import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * The single {@code /playervault} entry point (aliases {@code /pv}, {@code /vault},
 * {@code /vaults}, {@code /playervaults}). Parsing follows the module design
 * rules, resolved manually so the numeric-first grammar is unambiguous:
 *
 * <ul>
 *   <li>no args → open the caller's vault list;</li>
 *   <li>{@code <n>} → open the caller's vault {@code n};</li>
 *   <li>{@code <n> <player>} → staff-open another player's vault (online or offline);</li>
 *   <li>{@code edit <n>} → open the metadata editor;</li>
 *   <li>{@code list [player]} → open the list (own, or a target's for staff);</li>
 *   <li>{@code admin unlock|restore|logs …} → recovery tools;</li>
 *   <li>{@code reload} → reload the module config.</li>
 * </ul>
 *
 * <p>Player identity is always resolved to a UUID; names are lookup helpers only,
 * so offline access is UUID-safe.</p>
 */
public final class PlayerVaultCommand extends MysticCommand {

    private final PlayerVaultModule module;

    public PlayerVaultCommand(MysticCore core, PlayerVaultModule module) {
        super(core, "playervault", "Open and manage player vaults.");
        this.module = module;
        addAliases("pv", "vault", "vaults", "playervaults");
        requirePermission(Permissions.VAULTS_COMMAND);
        allowExtraArguments();
    }

    private PlayerVaultConfig config() {
        return module.config();
    }

    private PlayerVaultPermissionService perms() {
        return module.permissions();
    }

    @Override
    protected void run(MysticCommandSender sender) {
        if (!sender.isPlayer()) {
            sender.replyKey("player-only");
            return;
        }
        PlayerRef viewer = sender.player().orElseThrow();
        String[] args = sender.args();
        if (args.length == 0) {
            module.ui().openVaultList(viewer);
            return;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "edit" -> handleEdit(sender, viewer, args);
            case "list" -> handleList(sender, viewer, args);
            case "admin" -> handleAdmin(sender, viewer, args);
            case "reload" -> handleReload(sender);
            default -> handleNumeric(sender, viewer, args);
        }
    }

    // ----- /pv <n> [player] -----------------------------------------------------

    private void handleNumeric(MysticCommandSender sender, PlayerRef viewer, String[] args) {
        Integer number = parseVault(args[0]);
        if (number == null) {
            sender.reply("&cUsage: &f/pv [number] [player] &7| &fedit <n> &7| &flist [player] &7| &fadmin ...");
            return;
        }
        if (number < 1 || number > config().maxVaults) {
            sender.replyKey("vault-invalid-number", Map.of("max", Integer.toString(config().maxVaults)));
            return;
        }
        if (args.length >= 2) {
            openAdminTarget(sender, viewer, number, args[1]);
        } else {
            module.ui().openOwnVault(viewer, number);
        }
    }

    // ----- /pv edit <n> ---------------------------------------------------------

    private void handleEdit(MysticCommandSender sender, PlayerRef viewer, String[] args) {
        if (!sender.hasPermission(Permissions.VAULTS_COMMAND_EDIT) && !perms().canOpenEditor(viewer)) {
            sender.replyKey("no-permission");
            return;
        }
        if (args.length < 2) {
            sender.reply("&cUsage: &f/pv edit <number>");
            return;
        }
        Integer number = parseVault(args[1]);
        if (number == null || number < 1 || number > config().maxVaults) {
            sender.replyKey("vault-invalid-number", Map.of("max", Integer.toString(config().maxVaults)));
            return;
        }
        if (!perms().canAccessVault(viewer, number)) {
            sender.replyKey("vault-no-access", Map.of("vault", Integer.toString(number)));
            return;
        }
        module.ui().openEditor(viewer, viewer.getUuid(), viewer.getUsername(), number, false);
    }

    // ----- /pv list [player] ----------------------------------------------------

    private void handleList(MysticCommandSender sender, PlayerRef viewer, String[] args) {
        if (args.length < 2) {
            module.ui().openVaultList(viewer);
            return;
        }
        if (!perms().canAdminOpen(viewer)) {
            sender.replyKey("no-permission");
            return;
        }
        resolveTarget(sender, viewer, args[1], (uuid, name, online) ->
                module.ui().openVaultListFor(viewer, uuid, name, true));
    }

    // ----- /pv admin ... --------------------------------------------------------

    private void handleAdmin(MysticCommandSender sender, PlayerRef viewer, String[] args) {
        if (args.length < 2) {
            sender.reply("&cUsage: &f/pv admin <unlock|restore|logs> ...");
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "unlock" -> handleUnlock(sender, viewer, args);
            case "restore" -> handleRestore(sender, viewer, args);
            case "logs" -> handleLogs(sender, viewer, args);
            default -> sender.reply("&cUnknown admin action. Use &funlock&c, &frestore&c, or &flogs&c.");
        }
    }

    /** {@code /pv admin unlock <player> <n>} */
    private void handleUnlock(MysticCommandSender sender, PlayerRef viewer, String[] args) {
        if (!perms().canForceUnlock(viewer)) {
            sender.replyKey("no-permission");
            return;
        }
        if (args.length < 4) {
            sender.reply("&cUsage: &f/pv admin unlock <player> <number>");
            return;
        }
        Integer number = parseVault(args[3]);
        if (number == null) {
            sender.replyKey("vault-invalid-number", Map.of("max", Integer.toString(config().maxVaults)));
            return;
        }
        resolveTarget(sender, viewer, args[2], (uuid, name, online) -> {
            boolean released = module.lockService().forceRelease(uuid, number);
            module.logService().record(VaultAdminLogEntry.create(viewer.getUuid(), viewer.getUsername(),
                    uuid, number, "ADMIN_UNLOCK", module.logService().serverId()));
            if (released) {
                sender.replyKey("vault-admin-unlocked",
                        Map.of("player", name, "vault", Integer.toString(number)));
            } else {
                sender.replyKey("vault-admin-unlock-none");
            }
        });
    }

    /** {@code /pv admin restore <player> <n> <backupId>} */
    private void handleRestore(MysticCommandSender sender, PlayerRef viewer, String[] args) {
        if (!perms().canRestore(viewer)) {
            sender.replyKey("no-permission");
            return;
        }
        if (args.length < 5) {
            sender.reply("&cUsage: &f/pv admin restore <player> <number> <backupId>");
            return;
        }
        Integer number = parseVault(args[3]);
        if (number == null) {
            sender.replyKey("vault-invalid-number", Map.of("max", Integer.toString(config().maxVaults)));
            return;
        }
        String backupId = args[4];
        resolveTarget(sender, viewer, args[2], (uuid, name, online) ->
                module.ui().restoreBackup(viewer, uuid, name, number, backupId));
    }

    /** {@code /pv admin logs <player>} */
    private void handleLogs(MysticCommandSender sender, PlayerRef viewer, String[] args) {
        if (!perms().canViewLogs(viewer)) {
            sender.replyKey("no-permission");
            return;
        }
        if (args.length < 3) {
            sender.reply("&cUsage: &f/pv admin logs <player>");
            return;
        }
        resolveTarget(sender, viewer, args[2], (uuid, name, online) ->
                module.logService().list(uuid).thenAccept(entries -> {
                    if (entries.isEmpty()) {
                        sender.replyKey("vault-logs-empty");
                        return;
                    }
                    sender.replyKey("vault-logs-header",
                            Map.of("player", name, "count", Integer.toString(entries.size())));
                    int shown = 0;
                    for (VaultAdminLogEntry entry : entries) {
                        if (shown++ >= 15) {
                            break;
                        }
                        sender.replyKey("vault-logs-entry", Map.of(
                                "time", shortTime(entry.timestamp),
                                "actor", entry.actorName == null ? "System" : entry.actorName,
                                "action", entry.action == null ? "?" : entry.action,
                                "vault", Integer.toString(entry.vaultNumber),
                                "server", entry.serverId == null ? "?" : entry.serverId));
                    }
                }));
    }

    // ----- /pv reload -----------------------------------------------------------

    private void handleReload(MysticCommandSender sender) {
        if (!sender.hasPermission(Permissions.VAULTS_COMMAND_RELOAD) && !sender.hasPermission(Permissions.RELOAD)) {
            sender.replyKey("no-permission");
            return;
        }
        module.onReload();
        sender.replyKey("vault-reloaded");
    }

    // ----- Admin open resolution ------------------------------------------------

    private void openAdminTarget(MysticCommandSender sender, PlayerRef viewer, int number, String targetName) {
        if (!perms().canAdminOpen(viewer)) {
            sender.replyKey("no-permission");
            return;
        }
        VaultOpenMode mode = resolveAdminMode(viewer);
        resolveTarget(sender, viewer, targetName, (uuid, name, online) ->
                module.ui().openAdminVault(viewer, uuid, name, number, mode, online));
    }

    private VaultOpenMode resolveAdminMode(PlayerRef viewer) {
        boolean canEdit = perms().canAdminEdit(viewer);
        boolean preferReadOnly = "READ_ONLY".equalsIgnoreCase(config().admin.defaultAdminMode);
        if (canEdit && !preferReadOnly) {
            return VaultOpenMode.ADMIN_EDIT;
        }
        if (perms().canAdminReadOnly(viewer)) {
            return VaultOpenMode.ADMIN_READONLY;
        }
        return canEdit ? VaultOpenMode.ADMIN_EDIT : VaultOpenMode.ADMIN_READONLY;
    }

    // ----- Target resolution (online, then offline by UUID) ---------------------

    @FunctionalInterface
    private interface TargetAction {
        void run(UUID uuid, String name, boolean online);
    }

    private void resolveTarget(MysticCommandSender sender, PlayerRef viewer, String targetName,
            TargetAction action) {
        Optional<PlayerRef> online = core.platform().findPlayerByName(targetName);
        if (online.isPresent()) {
            action.run(online.get().getUuid(), online.get().getUsername(), true);
            return;
        }
        if (!perms().canAdminOpenOffline(viewer)) {
            sender.replyKey("player-not-found");
            return;
        }
        core.getPlayerProfileService().resolveUuid(targetName).thenAccept(resolved -> {
            if (resolved.isPresent()) {
                action.run(resolved.get(), targetName, false);
            } else {
                sender.replyKey("vault-player-never-joined", Map.of("player", targetName));
            }
        });
    }

    // ----- Helpers --------------------------------------------------------------

    private static Integer parseVault(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String shortTime(long epochMillis) {
        return java.time.Instant.ofEpochMilli(epochMillis)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDate().toString();
    }
}
