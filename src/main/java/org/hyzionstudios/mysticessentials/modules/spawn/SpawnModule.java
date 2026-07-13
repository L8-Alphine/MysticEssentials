package org.hyzionstudios.mysticessentials.modules.spawn;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import org.hyzionstudios.mysticessentials.api.Permissions;
import org.hyzionstudios.mysticessentials.api.model.Home;
import org.hyzionstudios.mysticessentials.api.model.MysticLocation;
import org.hyzionstudios.mysticessentials.api.model.PlayerProfile;
import org.hyzionstudios.mysticessentials.api.model.TeleportRequest;
import org.hyzionstudios.mysticessentials.api.service.SpawnService;
import org.hyzionstudios.mysticessentials.api.service.TeleportService;
import org.hyzionstudios.mysticessentials.core.module.AbstractMysticModule;
import org.hyzionstudios.mysticessentials.core.util.Json;
import org.hyzionstudios.mysticessentials.platform.Conversions;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommand;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommandSender;

import com.google.gson.JsonObject;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Owns global/world spawn and the Homes submodule. Home locations live in the
 * player profile under module data ({@code moduleData.homes}); spawn locations
 * live in the module config. Movement is delegated to the Core teleport service.
 *
 * <p>Commands use positional usage variants (base command + variant with a
 * required arg) so the syntax is {@code /sethome <name>}, never
 * {@code --name=}.</p>
 */
public final class SpawnModule extends AbstractMysticModule implements SpawnService {

    private static final String HOME_DATA_KEY = "homes";

    private SpawnConfig config;

    public SpawnModule() {
        super("spawn", "Spawn", "1.0.0");
    }

    @Override
    public List<String> hardDependencies() {
        return List.of("teleportation");
    }

    @Override
    public void onEnable() {
        config = core.configManager().loadModuleConfig(id(), SpawnConfig.class, new SpawnConfig());
        normalizeConfig();
        syncGlobalSpawnProvider();
        registerCommand(new SpawnCommand());
        registerCommand(new SetSpawnCommand());
        registerCommand(new SetWorldSpawnCommand());
        registerCommand(new HomeCommand());
        registerCommand(new SetHomeCommand());
        registerCommand(new DelHomeCommand());
        registerCommand(new RenameHomeCommand());
        registerCommand(new HomesCommand());
        // First-join / on-join teleport to spawn (respawn-point interception has no
        // hook in 0.5.6 — respawn is ECS-driven with no event to override it).
        if (config.teleportOnFirstJoin || config.teleportOnJoin) {
            registerEvent(
                    com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent.class,
                    (com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent event) ->
                            onJoin(event.getPlayerRef()));
        }
    }

    private void onJoin(PlayerRef player) {
        Optional<MysticLocation> spawn = getGlobalSpawn();
        if (spawn.isEmpty()) {
            return;
        }
        core.getPlayerProfileService().load(player.getUuid(), player.getUsername()).thenAccept(profile -> {
            boolean shouldTeleport = (profile.isFirstJoin() && config.teleportOnFirstJoin)
                    || (!profile.isFirstJoin() && config.teleportOnJoin);
            if (shouldTeleport) {
                core.getTeleportService().teleportNow(player, spawn.get());
            }
        });
    }

    @Override
    public void onReload() {
        config = core.configManager().loadModuleConfig(id(), SpawnConfig.class, new SpawnConfig());
        normalizeConfig();
        syncGlobalSpawnProvider();
    }

    @Override
    public void onDisable() {
        saveConfig();
    }

    private void saveConfig() {
        try {
            Json.writeFile(core.paths().moduleConfigFile(id()), config);
        } catch (Exception e) {
            log("Failed to save spawn config: " + e.getMessage());
        }
    }

    // ----- SpawnService ------------------------------------------------------

    @Override
    public Optional<MysticLocation> getGlobalSpawn() {
        return config.globalSpawnEnabled ? Optional.ofNullable(config.globalSpawn) : Optional.empty();
    }

    @Override
    public void setGlobalSpawn(MysticLocation location) {
        config.globalSpawn = location;
        saveConfig();
        syncGlobalSpawnProvider();
    }

    @Override
    public Optional<MysticLocation> getWorldSpawn(String world) {
        return config.perWorldSpawnEnabled ? Optional.ofNullable(config.worldSpawns.get(world)) : Optional.empty();
    }

    @Override
    public void setWorldSpawn(String world, MysticLocation location) {
        config.worldSpawns.put(world, location);
        saveConfig();
    }

    @Override
    public Optional<MysticLocation> resolveRespawn(UUID player) {
        // Respawn priority (design): Bed -> Home -> World Spawn -> Global Spawn -> Default.
        // Bed handling belongs to the Hytale beds module; here we cover Home/World/Global.
        List<Home> homes = getHomes(player);
        if (!homes.isEmpty()) {
            return Optional.of(homes.get(0).getLocation());
        }
        if (config.perWorldSpawnEnabled) {
            String world = core.platform().findPlayer(player)
                    .map(PlayerRef::getWorldUuid)
                    .map(Conversions::resolveWorldName)
                    .orElse(null);
            if (world != null) {
                MysticLocation worldSpawn = config.worldSpawns.get(world);
                if (worldSpawn != null) {
                    return Optional.of(worldSpawn);
                }
            }
        }
        return getGlobalSpawn();
    }

    // ----- Homes submodule ---------------------------------------------------

    @Override
    public List<Home> getHomes(UUID player) {
        List<Home> result = new ArrayList<>();
        JsonObject homes = homesObject(player);
        if (homes != null) {
            for (String name : homes.keySet()) {
                MysticLocation loc = Json.fromJson(homes.get(name), MysticLocation.class);
                result.add(new Home(name, loc));
            }
        }
        return result;
    }

    @Override
    public Optional<Home> getHome(UUID player, String name) {
        JsonObject homes = homesObject(player);
        if (homes != null && homes.has(name)) {
            return Optional.of(new Home(name, Json.fromJson(homes.get(name), MysticLocation.class)));
        }
        return Optional.empty();
    }

    @Override
    public boolean setHome(UUID player, String name, MysticLocation location) {
        JsonObject homes = homesObject(player);
        if (homes == null) {
            return false;
        }
        if (!homes.has(name) && homes.size() >= homeLimit(player)) {
            return false;
        }
        homes.add(name, Json.toTree(location));
        core.getPlayerProfileService().getCached(player).ifPresent(core.getPlayerProfileService()::save);
        return true;
    }

    @Override
    public boolean deleteHome(UUID player, String name) {
        JsonObject homes = homesObject(player);
        if (homes == null || !homes.has(name)) {
            return false;
        }
        homes.remove(name);
        core.getPlayerProfileService().getCached(player).ifPresent(core.getPlayerProfileService()::save);
        return true;
    }

    @Override
    public boolean renameHome(UUID player, String oldName, String newName) {
        JsonObject homes = homesObject(player);
        if (homes == null || !homes.has(oldName)
                || newName == null || newName.isBlank() || homes.has(newName)) {
            return false;
        }
        com.google.gson.JsonElement location = homes.get(oldName);
        homes.remove(oldName);
        homes.add(newName.trim(), location);
        core.getPlayerProfileService().getCached(player).ifPresent(core.getPlayerProfileService()::save);
        return true;
    }

    @Override
    public int homeLimit(UUID player) {
        OptionalInt limit = core.getPermissionService().limit(player, Permissions.HOME_LIMIT_BASE, true);
        return limit.orElse(config.defaultHomeLimit);
    }

    private JsonObject homesObject(UUID player) {
        Optional<PlayerProfile> profile = core.getPlayerProfileService().getCached(player);
        if (profile.isEmpty()) {
            return null;
        }
        return profile.get().getModuleData().computeIfAbsent(HOME_DATA_KEY, k -> new JsonObject());
    }

    // ----- Spawn behaviour ----------------------------------------------------

    /**
     * Sends the player to spawn: the global spawn when set (cross-world safe),
     * otherwise this world's spawn from config. Reports failures — a stored
     * spawn whose world is not loaded no longer fails silently.
     */
    private void teleportToSpawn(MysticCommandSender sender, PlayerRef player) {
        MysticLocation destination = getGlobalSpawn().orElse(null);
        String label = "Spawn";
        if (destination == null && config.perWorldSpawnEnabled) {
            String world = Conversions.resolveWorldName(player.getWorldUuid());
            destination = world == null ? null : config.worldSpawns.get(world);
            label = "World spawn";
        }
        if (destination == null) {
            sender.replyKey("spawn-not-set");
            return;
        }
        String finalLabel = label;
        MysticLocation finalDestination = destination;
        core.getTeleportService().teleport(player, TeleportRequest.builder()
                .type("spawn").target(destination).cooldownKey("spawn").cooldownSeconds(3).build())
                .thenAccept(result -> {
                    if (result == TeleportService.Result.INVALID_DESTINATION) {
                        core.getMessageService().sendKey(player, "spawn-world-not-loaded", Map.of(
                                "label", finalLabel,
                                "world", finalDestination.getWorld()));
                    }
                });
    }

    /** Rejects spawn/home anchors in temporary (delete-on-restart) worlds. */
    private boolean guardTemporaryWorld(MysticCommandSender sender, PlayerRef player, String what) {
        if (core.platform().isInTemporaryWorld(player)) {
            sender.replyKey("spawn-temp-world", Map.of("target", what));
            return true;
        }
        return false;
    }

    private void normalizeConfig() {
        if (config.worldSpawns == null) {
            config.worldSpawns = new java.util.LinkedHashMap<>();
        }
    }

    private void syncGlobalSpawnProvider() {
        if (!config.syncGlobalSpawnToWorldProvider || !config.globalSpawnEnabled || config.globalSpawn == null) {
            return;
        }
        if (!core.platform().syncWorldSpawnProvider(config.globalSpawn)) {
            log("Global spawn set, but its world is not loaded; Hytale respawn provider sync was skipped.");
        }
    }

    // ----- Commands ----------------------------------------------------------

    private final class SpawnCommand extends MysticCommand {
        SpawnCommand() {
            super(SpawnModule.this.core, "spawn", "Teleport to spawn.");
            requirePermission(Permissions.SPAWN_USE);
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            teleportToSpawn(sender, sender.player().orElseThrow());
        }
    }

    private final class SetSpawnCommand extends MysticCommand {
        SetSpawnCommand() {
            super(SpawnModule.this.core, "setspawn", "Set the global spawn.");
            requirePermission(Permissions.SPAWN_SET);
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            PlayerRef player = sender.player().orElseThrow();
            if (guardTemporaryWorld(sender, player, "the global spawn")) {
                return;
            }
            setGlobalSpawn(Conversions.capture(player));
            sender.replyKey("spawn-global-set");
        }
    }

    private final class SetWorldSpawnCommand extends MysticCommand {
        SetWorldSpawnCommand() {
            super(SpawnModule.this.core, "setworldspawn", "Set this world's spawn.");
            requirePermission(Permissions.SPAWN_SET);
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            PlayerRef player = sender.player().orElseThrow();
            if (guardTemporaryWorld(sender, player, "a world spawn")) {
                return;
            }
            MysticLocation location = Conversions.capture(player);
            setWorldSpawn(location.getWorld(), location);
            sender.replyKey("spawn-world-set", Map.of("world", location.getWorld()));
        }
    }

    /** Suggests the sender's own home names. */
    private com.hypixel.hytale.server.core.command.system.suggestion.SuggestionProvider homeSuggestions() {
        return (commandSender, input, index, result) ->
                getHomes(commandSender.getUuid()).forEach(h -> result.suggest(h.getName()));
    }

    void openHomesUi(PlayerRef player) {
        openHomesUi(player, null);
    }

    void openHomesUi(PlayerRef player, String selectedName) {
        core.platform().openPage(player, new HomePages.HomesPage(core, this, player, selectedName));
    }

    void teleportHome(PlayerRef player, Home home) {
        core.getTeleportService().teleport(player, TeleportRequest.builder()
                .type("home").target(home.getLocation())
                .cooldownKey("home").cooldownSeconds(3).build());
    }

    /** Moves an existing home to the player's current location. */
    boolean relocateHome(PlayerRef player, String name) {
        JsonObject homes = homesObject(player.getUuid());
        if (homes == null || !homes.has(name)) {
            return false;
        }
        homes.add(name, Json.toTree(Conversions.capture(player)));
        core.getPlayerProfileService().getCached(player.getUuid())
                .ifPresent(core.getPlayerProfileService()::save);
        return true;
    }

    private void teleportToNamedHome(MysticCommandSender sender, String homeName) {
        Optional<Home> home = getHome(sender.uuid(), homeName);
        if (home.isEmpty()) {
            sender.replyKey("home-missing-use-list", Map.of("home", homeName));
            return;
        }
        teleportHome(sender.player().orElseThrow(), home.get());
    }

    private void setHomeAt(MysticCommandSender sender, String homeName) {
        PlayerRef player = sender.player().orElseThrow();
        if (guardTemporaryWorld(sender, player, "a home")) {
            return;
        }
        boolean created = setHome(sender.uuid(), homeName, Conversions.capture(player));
        if (created) {
            sender.replyKey("home-set", Map.of("home", homeName));
        } else {
            sender.replyKey("home-limit", Map.of("limit", Integer.toString(homeLimit(sender.uuid()))));
        }
    }

    /** {@code /home} opens the UI; {@code /home <name>} teleports (positional variant). */
    private final class HomeCommand extends MysticCommand {
        HomeCommand() {
            super(SpawnModule.this.core, "home", "Teleport to a home, or open the Homes UI.");
            requirePermission(Permissions.HOME_USE);
            addUsageVariant(new HomeNamedVariant());
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            openHomesUi(sender.player().orElseThrow());
        }
    }

    private final class HomeNamedVariant extends MysticCommand {
        private final RequiredArg<String> name =
                withRequiredArg("name", "Home name", ArgTypes.STRING).suggest(homeSuggestions());

        HomeNamedVariant() {
            super(SpawnModule.this.core, "Teleport to a home.");
            requirePermission(Permissions.HOME_USE);
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            teleportToNamedHome(sender, sender.get(name));
        }
    }

    /** {@code /sethome} creates "home"; {@code /sethome <name>} names it (positional variant). */
    private final class SetHomeCommand extends MysticCommand {
        SetHomeCommand() {
            super(SpawnModule.this.core, "sethome", "Create a home.");
            requirePermission(Permissions.HOME_SET);
            addUsageVariant(new SetHomeNamedVariant());
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            setHomeAt(sender, "home");
        }
    }

    private final class SetHomeNamedVariant extends MysticCommand {
        private final RequiredArg<String> name = withRequiredArg("name", "Home name", ArgTypes.STRING);

        SetHomeNamedVariant() {
            super(SpawnModule.this.core, "Create a named home.");
            requirePermission(Permissions.HOME_SET);
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            setHomeAt(sender, sender.get(name));
        }
    }

    /** {@code /delhome} deletes "home"; {@code /delhome <name>} a named one (positional variant). */
    private final class DelHomeCommand extends MysticCommand {
        DelHomeCommand() {
            super(SpawnModule.this.core, "delhome", "Delete a home.");
            requirePermission(Permissions.HOME_SET);
            addUsageVariant(new DelHomeNamedVariant());
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            deleteNamed(sender, "home");
        }

        private void deleteNamed(MysticCommandSender sender, String homeName) {
            sender.replyKey(deleteHome(sender.uuid(), homeName)
                    ? "home-deleted"
                    : "home-missing", Map.of("home", homeName));
        }
    }

    private final class DelHomeNamedVariant extends MysticCommand {
        private final RequiredArg<String> name = withRequiredArg("name", "Home name", ArgTypes.STRING)
                .suggest(homeSuggestions());

        DelHomeNamedVariant() {
            super(SpawnModule.this.core, "Delete a named home.");
            requirePermission(Permissions.HOME_SET);
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            String homeName = sender.get(name);
            sender.replyKey(deleteHome(sender.uuid(), homeName)
                    ? "home-deleted"
                    : "home-missing", Map.of("home", homeName));
        }
    }

    private final class RenameHomeCommand extends MysticCommand {
        private final RequiredArg<String> oldName =
                withRequiredArg("oldname", "Current home name", ArgTypes.STRING).suggest(homeSuggestions());
        private final RequiredArg<String> newName =
                withRequiredArg("newname", "New home name", ArgTypes.STRING);

        RenameHomeCommand() {
            super(SpawnModule.this.core, "renamehome", "Rename one of your homes.");
            requirePermission(Permissions.HOME_SET);
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            String from = sender.get(oldName);
            String to = sender.get(newName);
            if (renameHome(sender.uuid(), from, to)) {
                sender.replyKey("home-renamed", Map.of("old", from, "new", to));
            } else if (getHome(sender.uuid(), from).isEmpty()) {
                sender.replyKey("home-missing", Map.of("home", from));
            } else {
                sender.replyKey("home-name-taken", Map.of("home", to));
            }
        }
    }

    private final class HomesCommand extends MysticCommand {
        HomesCommand() {
            super(SpawnModule.this.core, "homes", "Open the Homes UI.");
            requirePermission(Permissions.HOME_USE);
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            if (sender.player().isPresent()) {
                openHomesUi(sender.player().orElseThrow());
                return;
            }
            List<Home> homes = getHomes(sender.uuid());
            if (homes.isEmpty()) {
                sender.replyKey("home-none");
                return;
            }
            sender.replyKey("home-list", Map.of(
                    "count", Integer.toString(homes.size()),
                    "limit", Integer.toString(homeLimit(sender.uuid())),
                    "homes", String.join(", ", homes.stream().map(Home::getName).toList())));
        }
    }
}
