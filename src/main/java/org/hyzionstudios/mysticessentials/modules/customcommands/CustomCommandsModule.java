package org.hyzionstudios.mysticessentials.modules.customcommands;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.hyzionstudios.mysticessentials.core.module.AbstractMysticModule;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommandSender;

import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;

/**
 * Custom Commands module: server owners define commands in
 * {@code modules/customcommands/commands/*.json} — messages, command chains,
 * broadcasts, delays, conditions, notifications, and sounds, with aliases,
 * typed arguments, placeholders, permission modes, and persistent cooldowns.
 *
 * <p>Ships <b>disabled by default twice</b> (like the Tutorial module): in the
 * main config's modules map and via {@code enabled} in its own
 * {@code config.json}. While the runtime switch is off, everything replies
 * module-disabled except {@code /customcommands reload|validate|help}.</p>
 *
 * <p>Reload model: registered {@link DynamicCommandStub}s resolve definitions
 * at call time, so {@code /customcommands reload} re-reads every file and the
 * new behaviour applies instantly; genuinely new labels are registered on the
 * spot and stale ones are unregistered through their engine registration
 * handles (see {@link CustomCommandRegistrar}). With Redis enabled, reloads
 * and cooldown starts propagate across the network.</p>
 */
public final class CustomCommandsModule extends AbstractMysticModule {

    /** Redis channel for cross-server reload events (payload: originating actor). */
    public static final String REDIS_RELOAD_CHANNEL = "ccmd-reload";

    private CustomCommandsConfig config = new CustomCommandsConfig();
    private CustomCommandFileLoader fileLoader;
    private CustomCommandParser parser;
    private CustomCommandRegistrar registrar;
    private CustomCommandValidator validator;
    private CustomCommandRegistry registry;
    private CustomCommandExecutor executor;
    private CustomCommandPermissionService permissions;
    private CustomCommandStorage storage;
    private CustomCommandCooldownService cooldowns;
    private CustomCommandAuditLogger audit;

    private List<CustomCommand> loadedDefinitions = List.of();
    private List<CustomCommandValidator.Issue> lastValidation = List.of();
    private ScheduledFuture<?> statsFlushTask;

    /** Guards listeners and delayed continuations after onDisable. */
    private volatile boolean active;

    public CustomCommandsModule() {
        super("customcommands", "Custom Commands", "1.0.0");
    }

    // ----- Lifecycle ---------------------------------------------------------------

    @Override
    public void onEnable() {
        config = core.configManager().loadModuleConfig(id(), CustomCommandsConfig.class,
                new CustomCommandsConfig());

        fileLoader = new CustomCommandFileLoader(core, id());
        parser = new CustomCommandParser();
        registrar = new CustomCommandRegistrar(core, this);
        validator = new CustomCommandValidator(registrar);
        registry = new CustomCommandRegistry();
        executor = new CustomCommandExecutor(core, this);
        permissions = new CustomCommandPermissionService();
        storage = new CustomCommandStorage(core);
        cooldowns = new CustomCommandCooldownService(core, storage, () -> config);
        audit = new CustomCommandAuditLogger(core, storage, () -> config);

        loadDefinitions();
        registerCommand(new CustomCommandsAdminCommand(core, this));
        registerListeners();
        connectRedis();

        audit.loadCounters();
        statsFlushTask = core.scheduler().runRepeating(audit::flushCounters, 300, 300, TimeUnit.SECONDS);
        active = true;

        if (!config.enabled) {
            log("Loaded but switched off (set \"enabled\": true in modules/customcommands/config.json"
                    + " and run /customcommands reload).");
        }
    }

    @Override
    public void onReload() {
        reload("core-reload", false);
    }

    @Override
    public void onDisable() {
        active = false;
        if (statsFlushTask != null) {
            statsFlushTask.cancel(false);
            statsFlushTask = null;
        }
        // Drop the dynamic command stubs so a hot re-enable re-registers cleanly
        // (the admin command is unregistered by AbstractMysticModule).
        if (registrar != null) {
            registrar.unregisterAll();
        }
        if (audit != null) {
            audit.flushCounters();
        }
        if (cooldowns != null) {
            cooldowns.flushAll();
        }
    }

    private void registerListeners() {
        registerEvent(PlayerConnectEvent.class, (PlayerConnectEvent event) -> {
            if (active && config.enabled) {
                cooldowns.onJoin(event.getPlayerRef().getUuid());
            }
        });
        registerEvent(PlayerDisconnectEvent.class, (PlayerDisconnectEvent event) -> {
            if (active) {
                cooldowns.onQuit(event.getPlayerRef().getUuid());
            }
        });
    }

    private void connectRedis() {
        cooldowns.connectRedis();
        if (core.redis().isEnabled() && config.crossServer.syncReloads) {
            core.redis().subscribe(REDIS_RELOAD_CHANNEL, actor -> {
                if (active) {
                    reload(actor + " (remote)", true);
                }
            });
        }
    }

    // ----- Loading & reloading ---------------------------------------------------------

    /** Re-reads config + definition files, revalidates, and resyncs the stubs. */
    public void reload(String actor, boolean fromRemote) {
        config = core.configManager().loadModuleConfig(id(), CustomCommandsConfig.class,
                new CustomCommandsConfig());
        loadDefinitions();
        audit.logAdmin(actor, "reloaded (" + registry.size() + " active command(s))");
        if (!fromRemote && core.redis().isEnabled() && config.crossServer.syncReloads) {
            core.redis().publish(REDIS_RELOAD_CHANNEL, actor);
        }
    }

    private void loadDefinitions() {
        List<CustomCommand> definitions = fileLoader.load(config.generateExamples);
        for (CustomCommand definition : definitions) {
            parser.compile(definition);
        }
        lastValidation = validator.validate(definitions, config);
        List<CustomCommand> registrable = definitions.stream()
                .filter(definition -> CustomCommandValidator.isRegistrable(definition, lastValidation))
                .toList();
        loadedDefinitions = definitions;
        // A conflicting alias is skipped, not fatal — the command keeps its name
        // and non-conflicting aliases (a single taken alias used to sink it all).
        registry.rebuild(registrable, validator.skippableLabels(registrable, config));
        registrar.syncStubs(registry.labels(), label -> registry.byLabel(label)
                .map(definition -> definition.description).orElse(""));

        long errors = lastValidation.stream()
                .filter(issue -> issue.severity() == CustomCommandValidator.Severity.ERROR).count()
                + fileLoader.fileErrors().size();
        log("Loaded " + registry.size() + " custom command(s) ("
                + registry.labels().size() + " label(s) incl. aliases"
                + (errors > 0 ? ", " + errors + " validation error(s) — /customcommands validate" : "")
                + ").");
    }

    // ----- Dispatch (from the dynamic stubs) -----------------------------------------------

    /** Routes one typed {@code /<label> ...} invocation to the executor. */
    void dispatch(String label, MysticCommandSender sender) {
        if (!active || !config.enabled) {
            sender.replyKey("module-disabled");
            return;
        }
        Optional<CustomCommand> definition = registry.byLabel(label);
        if (definition.isEmpty()) {
            // A stub whose label was removed but whose unregistration did not
            // take effect; behave like an unknown command.
            sender.replyKey("customcommands-unknown", java.util.Map.of("command", label));
            return;
        }
        executor.invoke(definition.get(), sender.raw().sender(), sender.player().orElse(null),
                label, sender.argString(), false);
    }

    /** Looks a definition up by name or alias across everything loaded (valid or not). */
    Optional<CustomCommand> findDefinition(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String needle = name.toLowerCase(Locale.ROOT);
        return loadedDefinitions.stream()
                .filter(definition -> definition.labels().contains(needle))
                .findFirst();
    }

    // ----- Internals exposed to the customcommands package & admin command ---------------------

    public CustomCommandsConfig config() {
        return config;
    }

    public CustomCommandRegistry registry() {
        return registry;
    }

    public CustomCommandExecutor executor() {
        return executor;
    }

    public CustomCommandPermissionService permissions() {
        return permissions;
    }

    public CustomCommandCooldownService cooldowns() {
        return cooldowns;
    }

    public CustomCommandAuditLogger audit() {
        return audit;
    }

    public CustomCommandFileLoader fileLoader() {
        return fileLoader;
    }

    public List<CustomCommand> loadedDefinitions() {
        return List.copyOf(loadedDefinitions);
    }

    public List<CustomCommandValidator.Issue> lastValidation() {
        return List.copyOf(lastValidation);
    }

    public boolean isActive() {
        return active;
    }

    /** {@code {server_name}} value: module config, else the Redis server id, else "server". */
    public String serverName() {
        if (config.serverName != null && !config.serverName.isBlank()) {
            return config.serverName;
        }
        try {
            String redisId = core.config().storage.redis.serverId;
            if (redisId != null && !redisId.isBlank()) {
                return redisId;
            }
        } catch (RuntimeException ignored) {
            // Fall through to the generic default.
        }
        return "server";
    }
}
