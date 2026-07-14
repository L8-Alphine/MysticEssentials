package org.hyzionstudios.mysticessentials.modules.teleportation.rtp;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.api.rtp.RtpRequest;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.util.Json;

import com.google.gson.JsonObject;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Owns the Random Teleport subsystem's lifecycle inside the Teleportation
 * module: loads {@code modules/teleportation/rtp.json}, wires the search engine,
 * service, and audit log together, and handles administrator-queued login
 * teleports (spec §9). The Teleportation module registers the {@code /rtp} and
 * {@code /rtpadmin} commands against this subsystem and forwards player-connect
 * events here.
 */
public final class RtpSubsystem {

    /** Storage namespace for pending "teleport on next login" requests. */
    private static final String PENDING_NS = "rtp_pending";
    /** Seconds to wait after a queued player joins before running their RTP. */
    private static final long LOGIN_TELEPORT_DELAY_SECONDS = 3;

    private final MysticCore core;

    private RandomTeleportConfig config;
    private RtpSearchEngine engine;
    private RandomTeleportServiceImpl service;
    private RtpAudit audit;

    public RtpSubsystem(MysticCore core) {
        this.core = core;
    }

    /**
     * Boots the subsystem and registers {@code /rtp} and {@code /rtpadmin}
     * through the supplied registrar (the module's tracked
     * {@code registerCommand}, so the commands are dropped on module disable).
     */
    public void enable(java.util.function.Consumer<
            org.hyzionstudios.mysticessentials.platform.command.MysticCommand> commandRegistrar) {
        loadConfig();
        audit = new RtpAudit(core);
        engine = new RtpSearchEngine(core, config);
        service = new RandomTeleportServiceImpl(core, config, engine, audit);
        engine.start();
        commandRegistrar.accept(new RtpCommand(core, this));
        commandRegistrar.accept(new RtpAdminCommand(core, this));
        core.log(Level.INFO, "[teleportation] Random Teleport ready (" + config.profiles.size()
                + " profile(s), enabled=" + config.enabled + ").");
    }

    public void reload() {
        loadConfig();
        if (engine != null) {
            engine.configure(config);
        }
        if (service != null) {
            service.configure(config);
        }
    }

    public void disable() {
        if (service != null) {
            service.shutdown();
        }
        if (engine != null) {
            engine.stop();
        }
    }

    private void loadConfig() {
        Path file = core.paths().moduleExtraConfigFile("teleportation", "rtp.json");
        try {
            RandomTeleportConfig loaded = Json.readFile(file, RandomTeleportConfig.class);
            if (loaded == null) {
                config = RandomTeleportConfig.withDefaults();
                Json.writeFile(file, config);
                core.log(Level.INFO, "Generated default modules/teleportation/rtp.json");
            } else {
                config = loaded;
            }
        } catch (Exception e) {
            core.log(Level.SEVERE, "Failed to load rtp.json (" + e.getMessage()
                    + "); using defaults. The file was NOT overwritten.");
            config = RandomTeleportConfig.withDefaults();
        }
        config.profiles.forEach((id, profile) -> profile.id = id);
    }

    /** Persists the in-memory config back to {@code rtp.json} (used by {@code /rtpadmin} edits). */
    public void saveConfig() {
        Path file = core.paths().moduleExtraConfigFile("teleportation", "rtp.json");
        try {
            Json.writeFile(file, config);
        } catch (Exception e) {
            core.log(Level.WARNING, "Failed to save rtp.json: " + e.getMessage());
        }
    }

    public RandomTeleportServiceImpl service() {
        return service;
    }

    public RandomTeleportConfig config() {
        return config;
    }

    public RtpSearchEngine engine() {
        return engine;
    }

    // ----- Offline queue-login (spec §9) -------------------------------------

    /** Queues an RTP to run the next time {@code target} logs in. */
    public void queueLogin(UUID target, String profileId, UUID actor) {
        JsonObject payload = new JsonObject();
        payload.addProperty("profile", profileId);
        if (actor != null) {
            payload.addProperty("actor", actor.toString());
        }
        core.getStorageService().save(PENDING_NS, target.toString(), payload);
    }

    /** Runs (and clears) any pending login teleport for a player who just joined. */
    public void onPlayerConnect(PlayerRef player) {
        if (config == null || !config.enabled || service == null) {
            return;
        }
        UUID uuid = player.getUuid();
        core.getStorageService().load(PENDING_NS, uuid.toString()).thenAccept(element -> {
            if (element == null || !element.isJsonObject()) {
                return;
            }
            JsonObject payload = element.getAsJsonObject();
            String profileId = payload.has("profile") ? payload.get("profile").getAsString() : null;
            UUID actor = payload.has("actor") ? tryUuid(payload.get("actor").getAsString()) : null;
            core.getStorageService().delete(PENDING_NS, uuid.toString());
            if (profileId == null || service.getProfile(profileId).isEmpty()) {
                return;
            }
            // Delay so the player and their world are fully loaded; the search then
            // revalidates the destination as normal.
            core.scheduler().runLater(() -> core.platform().findPlayer(uuid).ifPresent(live ->
                            service.teleport(RtpRequest.builder(uuid).profileId(profileId).actor(actor)
                                    .force(true).build())),
                    LOGIN_TELEPORT_DELAY_SECONDS, TimeUnit.SECONDS);
        });
    }

    private static UUID tryUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
