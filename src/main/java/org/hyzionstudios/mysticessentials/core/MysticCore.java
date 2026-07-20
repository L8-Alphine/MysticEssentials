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
import org.hyzionstudios.mysticessentials.api.service.SpawnService;
import org.hyzionstudios.mysticessentials.api.service.StorageService;
import org.hyzionstudios.mysticessentials.api.service.TeleportService;
import org.hyzionstudios.mysticessentials.api.service.WarpService;
import org.hyzionstudios.mysticessentials.core.config.ConfigManager;
import org.hyzionstudios.mysticessentials.core.config.MainConfig;
import org.hyzionstudios.mysticessentials.core.economy.EconomyServiceImpl;
import org.hyzionstudios.mysticessentials.core.event.SimpleEventBus;
import org.hyzionstudios.mysticessentials.core.message.MessageServiceImpl;
import org.hyzionstudios.mysticessentials.core.migration.MigrationCommand;
import org.hyzionstudios.mysticessentials.core.module.ModuleManagerImpl;
import org.hyzionstudios.mysticessentials.core.path.PathManager;
import org.hyzionstudios.mysticessentials.core.permission.PermissionServiceImpl;
import org.hyzionstudios.mysticessentials.core.placeholder.PlaceholderServiceImpl;
import org.hyzionstudios.mysticessentials.core.profile.PlayerProfileServiceImpl;
import org.hyzionstudios.mysticessentials.core.scheduler.CooldownService;
import org.hyzionstudios.mysticessentials.core.scheduler.SchedulerService;
import org.hyzionstudios.mysticessentials.core.storage.RedisBridge;
import org.hyzionstudios.mysticessentials.core.storage.StorageServiceImpl;
import org.hyzionstudios.mysticessentials.core.teleport.TeleportServiceImpl;
import org.hyzionstudios.mysticessentials.core.update.UpdateNotifier;
import org.hyzionstudios.mysticessentials.modules.ModuleBootstrap;
import org.hyzionstudios.mysticessentials.platform.HytalePlatform;
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
    private MessageServiceImpl messageService;
    private PermissionServiceImpl permissionService;
    private PlaceholderServiceImpl placeholderService;
    private EconomyServiceImpl economyService;
    private TeleportServiceImpl teleportService;
    private UpdateNotifier updateNotifier;
    private ModuleManagerImpl moduleManager;

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
        teleportService = new TeleportServiceImpl(this);
        updateNotifier = new UpdateNotifier(this);
        updateNotifier.start();

        // Core commands + player lifecycle listeners (always available).
        registerCoreCommands();
        registerCoreListeners();

        // Modules.
        moduleManager = new ModuleManagerImpl(this);
        ModuleBootstrap.registerBuiltins(moduleManager);
        moduleManager.enableAll();

        MysticEssentialsProvider.register(this);
        log(Level.INFO, "Mystic Essentials is ready (storage=" + storageService.activeProvider() + ").");
    }

    public void disable() {
        log(Level.INFO, "Shutting down Mystic Essentials...");
        MysticEssentialsProvider.unregister();
        if (updateNotifier != null) {
            updateNotifier.stop();
        }
        if (moduleManager != null) {
            moduleManager.disableAll();
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
                    updateNotifier.notifyOnJoin(ref);
                });
        platform.onEvent(com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent.class,
                (com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent event) -> {
                    var ref = event.getPlayerRef();
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
            // Honour module enable/disable changes in config, not just reload the
            // already-running ones — this is the hot load/unload path.
            moduleManager.syncFromConfig();
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

    /** Logs through the plugin's Hytale logger. */
    public void log(Level level, String message) {
        plugin.getLogger().at(level).log(message);
    }

    // ----- MysticEssentialsAPI ----------------------------------------------

    @Override
    public String getVersion() {
        return "1.0.0";
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
