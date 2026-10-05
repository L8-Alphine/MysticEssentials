package org.hyzionstudios.mysticessentials.core;

import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.MysticessentialsPlugin;
import org.hyzionstudios.mysticessentials.api.MysticEssentialsAPI;
import org.hyzionstudios.mysticessentials.api.MysticEssentialsProvider;
import org.hyzionstudios.mysticessentials.api.Permissions;
import org.hyzionstudios.mysticessentials.api.event.EventBus;
import org.hyzionstudios.mysticessentials.api.item.ItemInspectionService;
import org.hyzionstudios.mysticessentials.api.module.ModuleManager;
import org.hyzionstudios.mysticessentials.api.notification.NotificationService;
import org.hyzionstudios.mysticessentials.api.service.AfkService;
import org.hyzionstudios.mysticessentials.api.service.AnnouncementService;
import org.hyzionstudios.mysticessentials.api.service.ChatService;
import org.hyzionstudios.mysticessentials.api.service.EconomyService;
import org.hyzionstudios.mysticessentials.api.service.MailService;
import org.hyzionstudios.mysticessentials.api.service.MessageService;
import org.hyzionstudios.mysticessentials.api.service.PermissionService;
import org.hyzionstudios.mysticessentials.api.service.PlaceholderService;
import org.hyzionstudios.mysticessentials.api.service.PlayerProfileService;
import org.hyzionstudios.mysticessentials.api.service.PlaytimeService;
import org.hyzionstudios.mysticessentials.api.service.SpawnService;
import org.hyzionstudios.mysticessentials.api.service.StorageService;
import org.hyzionstudios.mysticessentials.api.service.TeleportService;
import org.hyzionstudios.mysticessentials.api.service.WarpService;
import org.hyzionstudios.mysticessentials.api.ui.CustomUiService;
import org.hyzionstudios.mysticessentials.core.config.ConfigManager;
import org.hyzionstudios.mysticessentials.core.config.MainConfig;
import org.hyzionstudios.mysticessentials.core.economy.EconomyServiceImpl;
import org.hyzionstudios.mysticessentials.core.event.SimpleEventBus;
import org.hyzionstudios.mysticessentials.core.integration.ManagedAccountsBridge;
import org.hyzionstudios.mysticessentials.core.integration.ModerationBridge;
import org.hyzionstudios.mysticessentials.core.integration.PortalBridge;
import org.hyzionstudios.mysticessentials.core.integration.VanishBridge;
import org.hyzionstudios.mysticessentials.core.item.ItemInspectionServiceImpl;
import org.hyzionstudios.mysticessentials.core.item.ItemViewConfig;
import org.hyzionstudios.mysticessentials.core.license.LicenseCommand;
import org.hyzionstudios.mysticessentials.core.license.LicenseSupport;
import org.hyzionstudios.mysticessentials.core.message.MessageServiceImpl;
import org.hyzionstudios.mysticessentials.core.notification.NotificationCenterPage;
import org.hyzionstudios.mysticessentials.core.notification.NotificationConfig;
import org.hyzionstudios.mysticessentials.core.notification.NotificationServiceImpl;
import org.hyzionstudios.mysticessentials.core.migration.MigrationCommand;
import org.hyzionstudios.mysticessentials.core.module.ModuleManagerImpl;
import org.hyzionstudios.mysticessentials.core.network.NetworkPlayerService;
import org.hyzionstudios.mysticessentials.core.path.PathManager;
import org.hyzionstudios.mysticessentials.core.permission.PermissionServiceImpl;
import org.hyzionstudios.mysticessentials.core.placeholder.PlaceholderServiceImpl;
import org.hyzionstudios.mysticessentials.core.playerlist.PlayerListService;
import org.hyzionstudios.mysticessentials.core.profile.PlayerProfileServiceImpl;
import org.hyzionstudios.mysticessentials.core.profile.PlaytimeTracker;
import org.hyzionstudios.mysticessentials.core.scheduler.CooldownService;
import org.hyzionstudios.mysticessentials.core.scheduler.SchedulerService;
import org.hyzionstudios.mysticessentials.core.storage.RedisBridge;
import org.hyzionstudios.mysticessentials.core.storage.StorageServiceImpl;
import org.hyzionstudios.mysticessentials.core.teleport.TeleportServiceImpl;
import org.hyzionstudios.mysticessentials.core.ui.CustomUiServiceImpl;
import org.hyzionstudios.mysticessentials.core.update.UpdateNotifier;
import org.hyzionstudios.mysticessentials.core.util.Json;
import org.hyzionstudios.mysticessentials.modules.ModuleBootstrap;
import org.hyzionstudios.mysticessentials.platform.HytalePlatform;
import org.hyzionstudios.mysticessentials.platform.command.MysticArgTypes;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommand;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommandSender;

import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.mysticlicensing.license.LicenseGate;
import com.mysticlicensing.license.MysticLicenseService;
import com.mysticlicensing.license.NoopMysticLicenseService;

/**
 * The non-disableable Core. Owns every shared service and drives the boot order:
 * paths &rarr; config &rarr; storage &rarr; integrations &rarr; profiles &rarr;
 * core commands &rarr; modules. Implements {@link MysticEssentialsAPI}; the same
 * instance is published through {@link MysticEssentialsProvider}.
 */
public final class MysticCore implements MysticEssentialsAPI {

    private final MysticessentialsPlugin plugin;
    private final PathManager paths;

    private HytalePlatform platform;
    private ConfigManager configManager;
    private SchedulerService scheduler;
    private CooldownService cooldowns;
    private SimpleEventBus eventBus;

    private StorageServiceImpl storageService;
    private RedisBridge redisBridge;
    private NetworkPlayerService networkPlayerService;
    private VanishBridge vanishBridge;
    private ModerationBridge moderationBridge;
    private ManagedAccountsBridge managedAccountsBridge;
    private PortalBridge portalBridge;
    private PlayerProfileServiceImpl playerProfileService;
    private PlaytimeTracker playtimeTracker;
    private MessageServiceImpl messageService;
    private PermissionServiceImpl permissionService;
    private PlaceholderServiceImpl placeholderService;
    private EconomyServiceImpl economyService;
    private TeleportServiceImpl teleportService;
    private UpdateNotifier updateNotifier;
    private PlayerListService playerListService;
    private ModuleManagerImpl moduleManager;

    /**
     * Shared infrastructure the chat module and any third-party mod use. Both
     * live on Core rather than inside a module because they outlive any one
     * module: an ItemView provider registered by MysticRPG must survive the chat
     * module being toggled, and a guild warning must send whether or not chat is
     * enabled.
     */
    private ItemInspectionServiceImpl itemInspectionService;
    private NotificationServiceImpl notificationService;
    private CustomUiServiceImpl customUiService;

    /**
     * Offline license gate. Never null after {@link #enable()} has run, and
     * {@link #license()} substitutes a no-op before that, so no caller has to
     * null-check it. A licensing failure disables only the modules that declare
     * a licensed feature.
     */
    private LicenseGate license;

    public MysticCore(MysticessentialsPlugin plugin) {
        this.plugin = plugin;
        // Anchor all files at mods/MysticEssentials (per design) instead of the
        // identifier-named plugin data dir (e.g. "org.hyzionstudios_mysticessentials").
        this.paths = new PathManager(PluginManager.MODS_PATH.resolve("MysticEssentials"));
    }

    // ----- Lifecycle ---------------------------------------------------------

    public void enable() {
        log(Level.INFO, "Starting Mystic Essentials Core v" + getVersion());
        try {
            paths.ensureBaseLayout();
        } catch (Exception e) {
            log(Level.SEVERE, "Failed to create data directories: " + e.getMessage());
        }

        platform = new HytalePlatform(this, plugin);
        MysticArgTypes.bind(this);
        scheduler = new SchedulerService(this);
        scheduler.start();
        cooldowns = new CooldownService();
        eventBus = new SimpleEventBus(this);

        configManager = new ConfigManager(this);
        configManager.load();
        MainConfig config = config();

        // Storage + Redis.
        storageService = new StorageServiceImpl(this);
        storageService.init(config);
        redisBridge = new RedisBridge(this);
        redisBridge.init(config.storage.redis);

        // Integrations.
        permissionService = new PermissionServiceImpl(this);
        permissionService.init(config.integrations.luckPerms);
        placeholderService = new PlaceholderServiceImpl(this);
        placeholderService.init(config.integrations.placeholderAPI);
        economyService = new EconomyServiceImpl(this);
        economyService.init(config.integrations.vaultUnlocked);
        vanishBridge = new VanishBridge(this);
        vanishBridge.init(config.integrations.mysticVanish);
        moderationBridge = new ModerationBridge(this);
        moderationBridge.init(config.integrations.mysticModeration);
        managedAccountsBridge = new ManagedAccountsBridge(this);
        managedAccountsBridge.init(config.integrations.mysticIdentity);
        networkPlayerService = new NetworkPlayerService(this);
        networkPlayerService.start();

        // Messages + profiles + teleport.
        messageService = new MessageServiceImpl(this);
        messageService.load();
        playerProfileService = new PlayerProfileServiceImpl(this);
        // Players already online (a runtime plugin reload) never fire a join event
        // for this instance: load their profiles now, or nothing would be cached,
        // credited or saved for them until they relog.
        for (PlayerRef online : platform.onlinePlayers()) {
            playerProfileService.load(online.getUuid(), online.getUsername());
        }
        playtimeTracker = new PlaytimeTracker(this);
        playtimeTracker.start();
        teleportService = new TeleportServiceImpl(this);
        updateNotifier = new UpdateNotifier(this);
        updateNotifier.start();

        // Shared item inspection + notifications. Registered before the modules so
        // a module's onEnable can already publish an ItemView provider or send.
        itemInspectionService = new ItemInspectionServiceImpl(this,
                loadItemViewConfig());
        notificationService = new NotificationServiceImpl(this, loadNotificationConfig());
        customUiService = new CustomUiServiceImpl(1000);

        // Licensing. Verified once, here, before any module asks about it. This
        // cannot fail the startup: the worst outcome is that licensed modules
        // stay off and one warning is logged.
        license = LicenseSupport.create(this);
        license.start();

        // Core commands + player lifecycle listeners (always available).
        registerCoreCommands();
        registerCoreListeners();

        // Modules.
        moduleManager = new ModuleManagerImpl(this);
        ModuleBootstrap.registerBuiltins(moduleManager);
        moduleManager.enableAll();

        // After the modules, so the first refresh already sees the AFK service.
        playerListService = new PlayerListService(this);
        playerListService.start();

        // After the modules too, so the portal's first manifest already sees mail, vaults and notes.
        portalBridge = new PortalBridge(this);
        portalBridge.init(config.integrations.mysticIdentity);

        MysticEssentialsProvider.register(this);
        log(Level.INFO, "Mystic Essentials is ready (storage=" + storageService.activeProvider() + ").");
    }

    public void disable() {
        log(Level.INFO, "Shutting down Mystic Essentials...");
        if (portalBridge != null) {
            portalBridge.close();
        }
        MysticEssentialsProvider.unregister();
        if (playerListService != null) {
            playerListService.stop();
        }
        if (updateNotifier != null) {
            updateNotifier.stop();
        }
        if (moduleManager != null) {
            moduleManager.disableAll();
        }
        // Credit the final slice of every open session before profiles are saved.
        if (playtimeTracker != null) {
            playtimeTracker.stop();
        }
        if (playerProfileService != null) {
            try {
                playerProfileService.saveAll().join();
            } catch (Throwable t) {
                log(Level.WARNING, "Error saving profiles on shutdown: " + t);
            }
        }
        if (placeholderService != null) {
            placeholderService.shutdown();
        }
        if (networkPlayerService != null) {
            networkPlayerService.stop();
        }
        if (redisBridge != null) {
            redisBridge.shutdown();
        }
        if (storageService != null) {
            storageService.shutdown();
        }
        if (scheduler != null) {
            scheduler.shutdown();
        }
        log(Level.INFO, "Mystic Essentials shut down.");
    }

    private void registerCoreCommands() {
        platform.registerCommand(new CoreCommand());
        // Notifications are Core infrastructure, not a module feature, so the
        // Notification Center is available even with every module disabled.
        platform.registerCommand(new NotificationsCommand());
    }

    /** {@code /notifications} — opens the Notification Center. */
    private final class NotificationsCommand extends MysticCommand {
        NotificationsCommand() {
            super(MysticCore.this, "notifications", "Review notifications you may have missed.");
            addAliases("notifs");
            allowExtraArguments();
            requireNoPermission();
        }

        @Override
        protected void run(MysticCommandSender sender) {
            var player = sender.player().orElse(null);
            if (player == null) {
                sender.replyKey("player-only");
                return;
            }
            if (notificationService == null) {
                sender.reply("&cNotifications are unavailable on this server.");
                return;
            }
            if (!platform.openPage(player,
                    new NotificationCenterPage(
                            MysticCore.this, player, notificationService))) {
                sender.reply("&cCould not open the notification UI — see the server log.");
            }
        }
    }

    /**
     * Loads a player's profile on connect and persists/evicts it on disconnect.
     * Uses the verified {@code Void}-keyed player events, both of which expose the
     * universe {@code PlayerRef}.
     */
    private void registerCoreListeners() {
        platform.onEvent(PlayerConnectEvent.class,
                (PlayerConnectEvent event) -> {
                    var ref = event.getPlayerRef();
                    networkPlayerService.onJoin(ref);
                    playtimeTracker.onJoin(ref.getUuid());
                    // The notice is kept in notification history, which lives in the
                    // profile: send it once the profile is loaded.
                    playerProfileService.load(ref.getUuid(), ref.getUsername())
                            .whenComplete((profile, failure) -> updateNotifier.notifyOnJoin(ref));
                });
        platform.onEvent(PlayerDisconnectEvent.class,
                (PlayerDisconnectEvent event) -> {
                    var ref = event.getPlayerRef();
                    networkPlayerService.onQuit(ref);
                    // Credit the session before the profile is persisted and evicted.
                    playtimeTracker.onQuit(ref.getUuid());
                    // Notification history and preferences live in the profile, so
                    // they must be flushed before it is written out and dropped.
                    if (notificationService != null) {
                        notificationService.unload(ref.getUuid());
                    }
                    if (customUiService != null) {
                        customUiService.sessions().close(ref.getUuid());
                        customUiService.actions().clearPlayer(ref.getUuid());
                    }
                    playerProfileService.unload(ref.getUuid());
                });
    }

    /** {@code /mysticessentials} — version info, with a real {@code reload} subcommand. */
    private final class CoreCommand extends MysticCommand {
        CoreCommand() {
            super(MysticCore.this, "mysticessentials", "Mystic Essentials core command.");
            addAliases("mystic", "me");
            addSubCommand(new ReloadCommand());
            addSubCommand(new NetworkCommand());
            addSubCommand(new MigrationCommand(MysticCore.this));
            addSubCommand(new LicenseCommand(
                    MysticCore.this, license));
        }

        @Override
        protected void run(MysticCommandSender sender) {
            sender.replyKey("core-info-version", Map.of("version", getVersion()));
            sender.replyKey("core-info-status", Map.of(
                    "storage", storageService.activeProvider(),
                    "modules", Integer.toString(moduleManager.getModules().size())));
            sender.replyKey("core-info-help");
        }
    }

    private final class ReloadCommand extends MysticCommand {
        ReloadCommand() {
            super(MysticCore.this, "reload", "Reload Mystic Essentials configuration.");
            requirePermission(Permissions.RELOAD);
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!configManager.reload()) {
                sender.reply("&cconfig.json could not be read, so nothing was reloaded."
                        + " Fix the file (see the server log) and reload again.");
                return;
            }
            messageService.load();
            updateNotifier.reload();
            reloadIntegrations();
            reloadSharedServices();
            // Honour module enable/disable changes in config, not just reload the
            // already-running ones — this is the hot load/unload path.
            moduleManager.syncFromConfig();
            playerListService.reload();
            sender.replyKey("reload-success");
        }
    }

    /**
     * {@code /mystic network} — what this server advertises to the Redis network
     * and which other servers it can currently see, so a failed cross-server
     * teleport can be traced to the roster, the address, or the transfer itself.
     */
    private final class NetworkCommand extends MysticCommand {
        NetworkCommand() {
            super(MysticCore.this, "network", "Show the Redis network roster and this server's advertised address.");
            requirePermission(Permissions.NETWORK);
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!networkPlayerService.isNetworked()) {
                sender.replyKey("network-status-disabled");
                return;
            }
            String network = String.valueOf(redisBridge.networkId());
            String host = networkPlayerService.advertisedHost();
            int port = networkPlayerService.advertisedPort();
            if (host.isEmpty() || port <= 0) {
                sender.replyKey("network-status-local-none", Map.of(
                        "server", networkPlayerService.localServerId(), "network", network));
            } else {
                String source = "host " + (networkPlayerService.isAdvertisedHostConfigured() ? "configured" : "auto-detected")
                        + ", port " + (networkPlayerService.isAdvertisedPortConfigured() ? "configured" : "auto-detected");
                sender.replyKey("network-status-local", Map.of(
                        "server", networkPlayerService.localServerId(), "network", network,
                        "host", host, "port", Integer.toString(port), "source", source));
                if (!networkPlayerService.hasProxy() && isPrivateAddressLiteral(host)) {
                    sender.replyKey("network-status-private-hint", Map.of(
                            "host", host, "server", networkPlayerService.localServerId()));
                }
            }
            if (networkPlayerService.hasProxy()) {
                sender.replyKey("network-status-proxy", Map.of(
                        "host", config().storage.redis.proxyHost.trim(),
                        "port", Integer.toString(config().storage.redis.proxyPort)));
            }
            var remotes = networkPlayerService.remoteServers();
            if (remotes.isEmpty()) {
                sender.replyKey("network-status-remote-none", Map.of("network", network));
                return;
            }
            sender.replyKey("network-status-remote-header", Map.of("count", Integer.toString(remotes.size())));
            long now = System.currentTimeMillis();
            for (var remote : remotes) {
                Map<String, String> params = Map.of(
                        "server", remote.serverId(),
                        "host", remote.host() == null ? "" : remote.host(),
                        "port", Integer.toString(remote.port()),
                        "players", Integer.toString(remote.playerCount()),
                        "age", Long.toString(Math.max(0, (now - remote.seenAtMillis()) / 1000L)));
                sender.replyKey(remote.hasEndpoint() ? "network-status-remote" : "network-status-remote-noendpoint",
                        params);
                if (!networkPlayerService.hasProxy() && remote.hasEndpoint()
                        && isPrivateAddressLiteral(remote.host())) {
                    sender.replyKey("network-status-private-hint", Map.of(
                            "host", remote.host(), "server", remote.serverId()));
                }
            }
        }

        /**
         * Whether {@code host} is a literal loopback/RFC 1918 IPv4 address. Only
         * literals are examined — resolving a hostname here would block on DNS.
         */
        private static boolean isPrivateAddressLiteral(String host) {
            if (host == null || !host.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
                return false;
            }
            String[] parts = host.split("\\.");
            int a = Integer.parseInt(parts[0]);
            int b = Integer.parseInt(parts[1]);
            return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b >= 16 && b <= 31);
        }
    }

    // ----- Infrastructure accessors (internal) -------------------------------

    public MysticessentialsPlugin plugin() {
        return plugin;
    }

    public PathManager paths() {
        return paths;
    }

    /**
     * The license gate, or a no-op grant-nothing service if licensing has not
     * been set up yet. Callers can rely on this never being null and never
     * throwing; see {@code mystic-license-core/README.md} for the failure policy.
     */
    public MysticLicenseService license() {
        LicenseGate current = license;
        return current == null
                ? NoopMysticLicenseService.INSTANCE
                : current;
    }

    public HytalePlatform platform() {
        return platform;
    }

    public SchedulerService scheduler() {
        return scheduler;
    }

    public CooldownService cooldowns() {
        return cooldowns;
    }

    public ConfigManager configManager() {
        return configManager;
    }

    public MainConfig config() {
        return configManager.get();
    }

    public RedisBridge redis() {
        return redisBridge;
    }

    /** Redis-backed player presence and safe server handoff. */
    public NetworkPlayerService networkPlayers() {
        return networkPlayerService;
    }

    /** Vanish integration (MysticVanish); fails open when absent. */
    public VanishBridge vanish() {
        return vanishBridge;
    }

    /** Moderation integration (MysticModeration); fails open when absent. */
    public ModerationBridge moderation() {
        return moderationBridge;
    }

    /** Managed-account policy (MysticIdentity); fails open when absent. */
    public ManagedAccountsBridge managedAccounts() {
        return managedAccountsBridge;
    }

    /** Shared item inspection. Never null after {@link #enable()} has run. */
    public ItemInspectionServiceImpl itemInspection() {
        return itemInspectionService;
    }

    /** The shared notification engine. Never null after {@link #enable()} has run. */
    public NotificationServiceImpl notifications() {
        return notificationService;
    }

    /**
     * Reloads the shared item-view and notification configuration. Called by
     * {@code /mystic reload} alongside the module reloads so their settings do not
     * drift from everything else on the server.
     */
    public void reloadSharedServices() {
        // An unreadable file keeps the running settings instead of resetting them.
        if (itemInspectionService != null) {
            ItemViewConfig itemView = loadSharedConfig("chat", "item-view.json",
                    ItemViewConfig.class, new ItemViewConfig(), null);
            if (itemView != null) {
                itemInspectionService.updateConfig(itemView.normalized());
            }
        }
        if (notificationService != null) {
            NotificationConfig notifications = loadSharedConfig("core", "notifications.json",
                    NotificationConfig.class, new NotificationConfig(), null);
            if (notifications != null) {
                notificationService.updateConfig(notifications.normalized());
            }
        }
    }

    /** Re-applies every optional bridge and the Redis transport after a config reload. */
    private void reloadIntegrations() {
        MainConfig config = config();
        permissionService.init(config.integrations.luckPerms);
        placeholderService.init(config.integrations.placeholderAPI);
        economyService.init(config.integrations.vaultUnlocked);
        vanishBridge.init(config.integrations.mysticVanish);
        moderationBridge.init(config.integrations.mysticModeration);
        managedAccountsBridge.init(config.integrations.mysticIdentity);
        if (portalBridge != null) {
            portalBridge.init(config.integrations.mysticIdentity);
        }
        redisBridge.reconfigure(config.storage.redis);
        networkPlayerService.reload();
    }

    private ItemViewConfig loadItemViewConfig() {
        ItemViewConfig defaults = new ItemViewConfig();
        return loadSharedConfig("chat", "item-view.json", ItemViewConfig.class, defaults, defaults)
                .normalized();
    }

    private NotificationConfig loadNotificationConfig() {
        NotificationConfig defaults = new NotificationConfig();
        return loadSharedConfig("core", "notifications.json", NotificationConfig.class, defaults, defaults)
                .normalized();
    }

    /**
     * Loads a shared config file, writing the defaults on first run. A corrupt
     * file logs and yields {@code onFailure} (the defaults at startup) rather than
     * aborting startup — losing a customised notification profile is
     * recoverable; failing to boot is not.
     */
    private <T> T loadSharedConfig(String module, String fileName, Class<T> type, T defaults,
            T onFailure) {
        Path file = paths.moduleExtraConfigFile(module, fileName);
        try {
            T loaded = Json.readFile(file, type);
            if (loaded != null) {
                return loaded;
            }
            Json.writeFile(file, Json.toTree(defaults));
            log(Level.INFO, "Generated default modules/" + module + "/" + fileName);
        } catch (Exception e) {
            log(Level.WARNING, "Failed to load " + fileName
                    + (onFailure == defaults ? " (using defaults): " : " (keeping current settings): ")
                    + e.getMessage());
            return onFailure;
        }
        return defaults;
    }

    /** Logs through the plugin's Hytale logger. */
    public void log(Level level, String message) {
        plugin.getLogger().at(level).log(message);
    }

    /** Logs {@code message} with {@code cause}'s stack trace. */
    public void log(Level level, String message, Throwable cause) {
        plugin.getLogger().at(level).withCause(cause).log(message);
    }

    // ----- MysticEssentialsAPI ----------------------------------------------

    @Override
    public String getVersion() {
        // The server has already parsed the version from this JAR's manifest.
        // Reading that value keeps every version display and update comparison
        // tied to the artifact that is actually installed instead of a second,
        // easily-forgotten constant in the code.
        return plugin.getManifest().getVersion().toString();
    }

    @Override
    public ModuleManager getModuleManager() {
        return moduleManager;
    }

    @Override
    public StorageService getStorageService() {
        return storageService;
    }

    @Override
    public PlayerProfileService getPlayerProfileService() {
        return playerProfileService;
    }

    @Override
    public PlaytimeService getPlaytimeService() {
        return playtimeTracker;
    }

    @Override
    public MessageService getMessageService() {
        return messageService;
    }

    @Override
    public PlaceholderService getPlaceholderService() {
        return placeholderService;
    }

    @Override
    public EconomyService getEconomyService() {
        return economyService;
    }

    @Override
    public PermissionService getPermissionService() {
        return permissionService;
    }

    @Override
    public TeleportService getTeleportService() {
        return teleportService;
    }

    @Override
    public EventBus getEventBus() {
        return eventBus;
    }

    @Override
    public ItemInspectionService getItemInspectionService() {
        return itemInspectionService;
    }

    @Override
    public NotificationService getNotificationService() {
        return notificationService;
    }

    @Override
    public CustomUiService getCustomUiService() {
        return customUiService;
    }

    @Override
    public SpawnService getSpawnService() {
        return service("spawn", SpawnService.class);
    }

    @Override
    public WarpService getWarpService() {
        return service("warps", WarpService.class);
    }

    @Override
    public MailService getMailService() {
        return service("mail", MailService.class);
    }

    @Override
    public AfkService getAfkService() {
        return service("afk", AfkService.class);
    }

    @Override
    public ChatService getChatService() {
        return service("chat", ChatService.class);
    }

    @Override
    public AnnouncementService getAnnouncementService() {
        return service("announcements", AnnouncementService.class);
    }

    /** Resolves a module-owned service, or {@code null} if the module is disabled. */
    private <T> T service(String moduleId, Class<T> type) {
        if (moduleManager == null || !moduleManager.isEnabled(moduleId)) {
            return null;
        }
        return moduleManager.getModule(moduleId)
                .filter(type::isInstance)
                .map(type::cast)
                .orElse(null);
    }
}
