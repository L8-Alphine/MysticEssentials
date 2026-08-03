package org.hyzionstudios.mysticessentials.core;

import java.util.Map;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.MysticessentialsPlugin;
import org.hyzionstudios.mysticessentials.api.MysticEssentialsAPI;
import org.hyzionstudios.mysticessentials.api.MysticEssentialsProvider;
import org.hyzionstudios.mysticessentials.api.event.EventBus;
import org.hyzionstudios.mysticessentials.api.module.ModuleManager;
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
import org.hyzionstudios.mysticessentials.core.config.ConfigManager;
import org.hyzionstudios.mysticessentials.core.config.MainConfig;
import org.hyzionstudios.mysticessentials.core.economy.EconomyServiceImpl;
import org.hyzionstudios.mysticessentials.core.event.SimpleEventBus;
import org.hyzionstudios.mysticessentials.core.item.ItemInspectionServiceImpl;
import org.hyzionstudios.mysticessentials.core.message.MessageServiceImpl;
import org.hyzionstudios.mysticessentials.core.notification.NotificationServiceImpl;
import org.hyzionstudios.mysticessentials.core.migration.MigrationCommand;
import org.hyzionstudios.mysticessentials.core.module.ModuleManagerImpl;
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
import org.hyzionstudios.mysticessentials.core.update.UpdateNotifier;
import org.hyzionstudios.mysticessentials.modules.ModuleBootstrap;
import org.hyzionstudios.mysticessentials.platform.HytalePlatform;
import org.hyzionstudios.mysticessentials.platform.command.MysticArgTypes;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommand;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommandSender;

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
    private org.hyzionstudios.mysticessentials.core.integration.VanishBridge vanishBridge;
    private org.hyzionstudios.mysticessentials.core.integration.ModerationBridge moderationBridge;
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
    private org.hyzionstudios.mysticessentials.core.ui.CustomUiServiceImpl customUiService;

    /**
     * Offline license gate. Never null after {@link #enable()} has run, and
     * {@link #license()} substitutes a no-op before that, so no caller has to
     * null-check it. A licensing failure disables only the modules that declare
     * a licensed feature.
     */
    private com.mysticlicensing.license.LicenseGate license;

    public MysticCore(MysticessentialsPlugin plugin) {
        this.plugin = plugin;
        // Anchor all files at mods/MysticEssentials (per design) instead of the
        // identifier-named plugin data dir (e.g. "org.hyzionstudios_mysticessentials").
        this.paths = new PathManager(
                com.hypixel.hytale.server.core.plugin.PluginManager.MODS_PATH.resolve("MysticEssentials"));
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
        vanishBridge = new org.hyzionstudios.mysticessentials.core.integration.VanishBridge(this);
        vanishBridge.init(config.integrations.mysticVanish);
        moderationBridge = new org.hyzionstudios.mysticessentials.core.integration.ModerationBridge(this);
        moderationBridge.init(config.integrations.mysticModeration);

        // Messages + profiles + teleport.
        messageService = new MessageServiceImpl(this);
        messageService.load();
        playerProfileService = new PlayerProfileServiceImpl(this);
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
        customUiService = new org.hyzionstudios.mysticessentials.core.ui.CustomUiServiceImpl(1000);

        // Licensing. Verified once, here, before any module asks about it. This
        // cannot fail the startup: the worst outcome is that licensed modules
        // stay off and one warning is logged.
        license = org.hyzionstudios.mysticessentials.core.license.LicenseSupport.create(this);
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

        MysticEssentialsProvider.register(this);
        log(Level.INFO, "Mystic Essentials is ready (storage=" + storageService.activeProvider() + ").");
    }

    public void disable() {
        log(Level.INFO, "Shutting down Mystic Essentials...");
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
        }

        @Override
        protected boolean canGeneratePermission() {
            return false;
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
                    new org.hyzionstudios.mysticessentials.core.notification.NotificationCenterPage(
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
        platform.onEvent(com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent.class,
                (com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent event) -> {
                    var ref = event.getPlayerRef();
                    playerProfileService.load(ref.getUuid(), ref.getUsername());
                    playtimeTracker.onJoin(ref.getUuid());
                    updateNotifier.notifyOnJoin(ref);
                });
        platform.onEvent(com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent.class,
                (com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent event) -> {
                    var ref = event.getPlayerRef();
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
            addSubCommand(new MigrationCommand(MysticCore.this));
            addSubCommand(new org.hyzionstudios.mysticessentials.core.license.LicenseCommand(
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
            requirePermission(org.hyzionstudios.mysticessentials.api.Permissions.RELOAD);
        }

        @Override
        protected void run(MysticCommandSender sender) {
            configManager.load();
            messageService.load();
            updateNotifier.reload();
            reloadSharedServices();
            // Honour module enable/disable changes in config, not just reload the
            // already-running ones — this is the hot load/unload path.
            moduleManager.syncFromConfig();
            playerListService.reload();
            sender.replyKey("reload-success");
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
    public com.mysticlicensing.license.MysticLicenseService license() {
        com.mysticlicensing.license.LicenseGate current = license;
        return current == null
                ? com.mysticlicensing.license.NoopMysticLicenseService.INSTANCE
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

    /** Vanish integration (MysticVanish); fails open when absent. */
    public org.hyzionstudios.mysticessentials.core.integration.VanishBridge vanish() {
        return vanishBridge;
    }

    /** Moderation integration (MysticModeration); fails open when absent. */
    public org.hyzionstudios.mysticessentials.core.integration.ModerationBridge moderation() {
        return moderationBridge;
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
        if (itemInspectionService != null) {
            itemInspectionService.updateConfig(loadItemViewConfig());
        }
        if (notificationService != null) {
            notificationService.updateConfig(loadNotificationConfig());
        }
    }

    private org.hyzionstudios.mysticessentials.core.item.ItemViewConfig loadItemViewConfig() {
        return loadSharedConfig("chat", "item-view.json",
                org.hyzionstudios.mysticessentials.core.item.ItemViewConfig.class,
                new org.hyzionstudios.mysticessentials.core.item.ItemViewConfig())
                .normalized();
    }

    private org.hyzionstudios.mysticessentials.core.notification.NotificationConfig
            loadNotificationConfig() {
        return loadSharedConfig("core", "notifications.json",
                org.hyzionstudios.mysticessentials.core.notification.NotificationConfig.class,
                new org.hyzionstudios.mysticessentials.core.notification.NotificationConfig())
                .normalized();
    }

    /**
     * Loads a shared config file, writing the defaults on first run. A corrupt
     * file logs and yields the defaults rather than aborting startup — losing a
     * customised notification profile is recoverable; failing to boot is not.
     */
    private <T> T loadSharedConfig(String module, String fileName, Class<T> type, T defaults) {
        java.nio.file.Path file = paths.moduleExtraConfigFile(module, fileName);
        try {
            T loaded = org.hyzionstudios.mysticessentials.core.util.Json.readFile(file, type);
            if (loaded != null) {
                return loaded;
            }
            org.hyzionstudios.mysticessentials.core.util.Json.writeFile(file,
                    org.hyzionstudios.mysticessentials.core.util.Json.toTree(defaults));
            log(Level.INFO, "Generated default modules/" + module + "/" + fileName);
        } catch (Exception e) {
            log(Level.WARNING, "Failed to load " + fileName + " (using defaults): "
                    + e.getMessage());
        }
        return defaults;
    }

    /** Logs through the plugin's Hytale logger. */
    public void log(Level level, String message) {
        plugin.getLogger().at(level).log(message);
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
    public org.hyzionstudios.mysticessentials.api.item.ItemInspectionService
            getItemInspectionService() {
        return itemInspectionService;
    }

    @Override
    public org.hyzionstudios.mysticessentials.api.notification.NotificationService
            getNotificationService() {
        return notificationService;
    }

    @Override
    public org.hyzionstudios.mysticessentials.api.ui.CustomUiService getCustomUiService() {
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
