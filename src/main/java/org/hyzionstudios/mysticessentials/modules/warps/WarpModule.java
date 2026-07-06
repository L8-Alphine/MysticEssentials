package org.hyzionstudios.mysticessentials.modules.warps;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import org.hyzionstudios.mysticessentials.api.model.MysticLocation;
import org.hyzionstudios.mysticessentials.api.model.TeleportRequest;
import org.hyzionstudios.mysticessentials.api.model.Warp;
import org.hyzionstudios.mysticessentials.api.service.WarpService;
import org.hyzionstudios.mysticessentials.core.module.AbstractMysticModule;
import org.hyzionstudios.mysticessentials.core.util.Json;
import org.hyzionstudios.mysticessentials.platform.Conversions;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommand;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommandSender;

import com.google.gson.reflect.TypeToken;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Server warps and the Player Warps submodule. Server warps are persisted to
 * {@code data/modules/warps/server.json}; player warps are persisted to
 * {@code data/modules/warps/playerwarps.json} in one globally-named map so the
 * whole collection can be browsed in the Player Warps UI. Movement is delegated
 * to the Core teleport service; paid warps use the economy service.
 */
public final class WarpModule extends AbstractMysticModule implements WarpService {

    private static final Type WARP_MAP_TYPE = new TypeToken<LinkedHashMap<String, Warp>>() {
    }.getType();
    static final String ADMIN_PERMISSION = org.hyzionstudios.mysticessentials.api.Permissions.WARP_SET;
    static final String PWARP_ADMIN_PERMISSION = org.hyzionstudios.mysticessentials.api.Permissions.PLAYERWARP_ADMIN;

    private Map<String, Warp> serverWarps = new LinkedHashMap<>();
    private Map<String, Warp> playerWarps = new LinkedHashMap<>();

    public WarpModule() {
        super("warps", "Warps", "1.0.0");
    }

    @Override
    public List<String> hardDependencies() {
        return List.of("teleportation");
    }

    @Override
    public void onEnable() {
        serverWarps = loadWarpMap(serverWarpFile(), "server warps");
        playerWarps = loadWarpMap(playerWarpFile(), "player warps");
        registerCommand(new WarpCommand());
        registerCommand(new WarpsCommand());
        registerCommand(new SetWarpCommand());
        registerCommand(new DelWarpCommand());
        registerCommand(new PlayerWarpCommand());
    }

    @Override
    public void onDisable() {
        saveServerWarps();
        savePlayerWarps();
    }

    private Map<String, Warp> loadWarpMap(java.nio.file.Path file, String label) {
        try {
            com.google.gson.JsonElement element = Json.readFile(file);
            Map<String, Warp> loaded = element == null ? null : Json.gson().fromJson(element, WARP_MAP_TYPE);
            return loaded != null ? loaded : new LinkedHashMap<>();
        } catch (Exception e) {
            log("Failed to load " + label + ": " + e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private void saveServerWarps() {
        try {
            Json.writeFile(serverWarpFile(), serverWarps);
        } catch (Exception e) {
            log("Failed to save server warps: " + e.getMessage());
        }
    }

    private void savePlayerWarps() {
        try {
            Json.writeFile(playerWarpFile(), playerWarps);
        } catch (Exception e) {
            log("Failed to save player warps: " + e.getMessage());
        }
    }

    private java.nio.file.Path serverWarpFile() {
        return core.paths().moduleDataDir(id()).resolve("server.json");
    }

    private java.nio.file.Path playerWarpFile() {
        return core.paths().moduleDataDir(id()).resolve("playerwarps.json");
    }

    private static String key(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).trim();
    }

    // ----- WarpService: server warps -----------------------------------------

    @Override
    public Optional<Warp> getWarp(String name) {
        return Optional.ofNullable(serverWarps.get(key(name)));
    }

    @Override
    public List<Warp> listServerWarps(UUID viewer) {
        List<Warp> visible = new ArrayList<>();
        for (Warp warp : serverWarps.values()) {
            if (canSee(viewer, warp)) {
                visible.add(warp);
            }
        }
        return visible;
    }

    private boolean canSee(UUID viewer, Warp warp) {
        return switch (warp.getVisibility()) {
            case PUBLIC -> true;
            case HIDDEN -> false;
            case PERMISSION -> warp.getPermission() == null
                    || core.getPermissionService().has(viewer, warp.getPermission());
        };
    }

    @Override
    public void setServerWarp(Warp warp) {
        serverWarps.put(key(warp.getName()), warp);
        saveServerWarps();
    }

    @Override
    public boolean deleteServerWarp(String name) {
        boolean removed = serverWarps.remove(key(name)) != null;
        if (removed) {
            saveServerWarps();
        }
        return removed;
    }

    // ----- WarpService: player warps -----------------------------------------

    @Override
    public List<Warp> getPlayerWarps(UUID owner) {
        List<Warp> result = new ArrayList<>();
        String ownerId = owner.toString();
        for (Warp warp : playerWarps.values()) {
            if (ownerId.equals(warp.getOwner())) {
                result.add(warp);
            }
        }
        return result;
    }

    @Override
    public List<Warp> listAllPlayerWarps() {
        return new ArrayList<>(playerWarps.values());
    }

    @Override
    public Optional<Warp> getPlayerWarp(String name) {
        return Optional.ofNullable(playerWarps.get(key(name)));
    }

    @Override
    public boolean createPlayerWarp(UUID owner, String name, MysticLocation location) {
        String id = key(name);
        if (id.isBlank() || playerWarps.containsKey(id)
                || getPlayerWarps(owner).size() >= playerWarpLimit(owner)) {
            return false;
        }
        Warp warp = new Warp(name, location);
        warp.setOwner(owner.toString());
        warp.setOwnerName(core.platform().findPlayer(owner).map(PlayerRef::getUsername).orElse(null));
        playerWarps.put(id, warp);
        savePlayerWarps();
        return true;
    }

    @Override
    public boolean deletePlayerWarp(UUID owner, String name) {
        Warp warp = playerWarps.get(key(name));
        if (warp == null || !owner.toString().equals(warp.getOwner())) {
            return false;
        }
        playerWarps.remove(key(name));
        savePlayerWarps();
        return true;
    }

    /** Admin path: removes any player warp regardless of owner. */
    boolean deleteAnyPlayerWarp(String name) {
        boolean removed = playerWarps.remove(key(name)) != null;
        if (removed) {
            savePlayerWarps();
        }
        return removed;
    }

    @Override
    public boolean renamePlayerWarp(UUID owner, String oldName, String newName) {
        String oldId = key(oldName);
        String newId = key(newName);
        Warp warp = playerWarps.get(oldId);
        if (warp == null || !owner.toString().equals(warp.getOwner())
                || newId.isBlank() || playerWarps.containsKey(newId)) {
            return false;
        }
        playerWarps.remove(oldId);
        warp.setName(newName.trim());
        playerWarps.put(newId, warp);
        savePlayerWarps();
        return true;
    }

    @Override
    public int playerWarpLimit(UUID owner) {
        OptionalInt limit = core.getPermissionService().limit(owner,
                org.hyzionstudios.mysticessentials.api.Permissions.PLAYERWARP_LIMIT_BASE, true);
        return limit.orElse(1);
    }

    /** Owner's own player warp by name (used by the manager UI). */
    Optional<Warp> getOwnPlayerWarp(UUID owner, String name) {
        return getPlayerWarp(name).filter(warp -> owner.toString().equals(warp.getOwner()));
    }

    boolean updatePlayerWarpDetails(UUID owner, String name, String description, double cost) {
        Warp warp = getOwnPlayerWarp(owner, name).orElse(null);
        if (warp == null) {
            return false;
        }
        warp.setDescription(blankToNull(description));
        warp.setCost(Math.max(0.0, cost));
        savePlayerWarps();
        return true;
    }

    boolean relocatePlayerWarp(PlayerRef player, String name) {
        Warp warp = getOwnPlayerWarp(player.getUuid(), name).orElse(null);
        if (warp == null) {
            return false;
        }
        warp.setLocation(Conversions.capture(player));
        savePlayerWarps();
        return true;
    }

    // ----- Teleporting & UI hooks ---------------------------------------------

    void warpPlayer(PlayerRef player, Warp warp) {
        core.getTeleportService().teleport(player, TeleportRequest.builder()
                .type("warp").target(warp.getLocation())
                .cost(warp.getCost())
                .cooldownKey("warp").cooldownSeconds(3).build());
    }

    private void warpTo(MysticCommandSender sender, Warp warp) {
        warpPlayer(sender.player().orElseThrow(), warp);
    }

    void openWarpsUi(PlayerRef player) {
        core.platform().openPage(player, new WarpPages.WarpsPage(core, this, player));
    }

    void openPlayerWarpsUi(PlayerRef player) {
        core.platform().openPage(player, new WarpPages.PlayerWarpsPage(core, this, player));
    }

    void openPlayerWarpManagerUi(PlayerRef player) {
        openPlayerWarpManagerUi(player, null);
    }

    void openPlayerWarpManagerUi(PlayerRef player, String selectedName) {
        core.platform().openPage(player,
                new WarpPages.PlayerWarpManagerPage(core, this, player, selectedName));
    }

    boolean canSetWarps(PlayerRef player) {
        return player.hasPermission(ADMIN_PERMISSION);
    }

    /** Outcome of an admin-UI warp save. */
    enum SaveResult {
        SAVED,
        RENAMED,
        NAME_TAKEN,
        INVALID
    }

    /**
     * Creates, updates, or renames a server warp from the admin UI.
     * {@code editingName} is the warp the admin page was opened for (null when
     * creating). If the name field differs from {@code editingName}, the warp is
     * RENAMED in place — same location and details, new name — unless another
     * warp already uses the new name. When {@code captureLocation} is false and
     * the warp already exists, only the details change; a brand-new warp always
     * captures the admin's location.
     */
    SaveResult saveServerWarpFromUi(PlayerRef player, String editingName, String name, String description,
            String permission, double cost, Warp.Visibility visibility, boolean captureLocation) {
        if (!canSetWarps(player) || name == null || name.isBlank()) {
            return SaveResult.INVALID;
        }
        String newKey = key(name);
        Warp editing = editingName == null ? null : serverWarps.get(key(editingName));
        boolean renamed = false;
        Warp warp;
        if (editing != null && !newKey.equals(key(editingName))) {
            if (serverWarps.containsKey(newKey)) {
                return SaveResult.NAME_TAKEN;
            }
            serverWarps.remove(key(editingName));
            editing.setName(name.trim());
            warp = editing;
            renamed = true;
        } else if (editing != null) {
            warp = editing;
        } else {
            Warp existing = serverWarps.get(newKey);
            if (existing == null) {
                warp = new Warp(name.trim(), Conversions.capture(player));
            } else {
                warp = existing;
            }
        }
        if (captureLocation || warp.getLocation() == null) {
            warp.setLocation(Conversions.capture(player));
        }
        warp.setDescription(blankToNull(description));
        warp.setPermission(blankToNull(permission));
        warp.setVisibility(visibility != null ? visibility
                : warp.getPermission() == null ? Warp.Visibility.PUBLIC : Warp.Visibility.PERMISSION);
        warp.setCost(Math.max(0.0, cost));
        serverWarps.put(newKey, warp);
        saveServerWarps();
        return renamed ? SaveResult.RENAMED : SaveResult.SAVED;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    // ----- Commands ----------------------------------------------------------

    /** Suggests server-warp names the sender can see. */
    private com.hypixel.hytale.server.core.command.system.suggestion.SuggestionProvider visibleWarpSuggestions() {
        return (commandSender, input, index, result) ->
                listServerWarps(commandSender.getUuid()).forEach(w -> result.suggest(w.getName()));
    }

    /** Suggests all server-warp names (admin commands). */
    private com.hypixel.hytale.server.core.command.system.suggestion.SuggestionProvider allWarpSuggestions() {
        return (commandSender, input, index, result) ->
                serverWarps.values().forEach(w -> result.suggest(w.getName()));
    }

    /** Suggests every player-warp name. */
    private com.hypixel.hytale.server.core.command.system.suggestion.SuggestionProvider playerWarpSuggestions() {
        return (commandSender, input, index, result) ->
                playerWarps.values().forEach(w -> result.suggest(w.getName()));
    }

    /** {@code /warp} opens the UI; {@code /warp <name>} teleports (positional variant). */
    private final class WarpCommand extends MysticCommand {
        WarpCommand() {
            super(WarpModule.this.core, "warp", "Teleport to a server warp.");
            requirePermission(org.hyzionstudios.mysticessentials.api.Permissions.WARP_USE);
            addUsageVariant(new WarpNamedVariant());
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            openWarpsUi(sender.player().orElseThrow());
        }
    }

    private final class WarpNamedVariant extends MysticCommand {
        private final RequiredArg<String> name = withRequiredArg("name", "Warp name", ArgTypes.STRING)
                .suggest(visibleWarpSuggestions());

        WarpNamedVariant() {
            super(WarpModule.this.core, "Teleport to a named server warp.");
            requirePermission(org.hyzionstudios.mysticessentials.api.Permissions.WARP_USE);
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            String warpName = sender.get(name);
            Optional<Warp> warp = getWarp(warpName);
            if (warp.isEmpty() || !canSee(sender.uuid(), warp.get())) {
                sender.replyKey("warp-unknown", Map.of("warp", warpName));
                return;
            }
            warpTo(sender, warp.get());
        }
    }

    private final class WarpsCommand extends MysticCommand {
        WarpsCommand() {
            super(WarpModule.this.core, "warps", "List available warps.");
            requirePermission(org.hyzionstudios.mysticessentials.api.Permissions.WARP_USE);
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (sender.isPlayer()) {
                openWarpsUi(sender.player().orElseThrow());
                return;
            }
            List<Warp> warps = listServerWarps(sender.uuid());
            if (warps.isEmpty()) {
                sender.replyKey("warp-none");
                return;
            }
            sender.replyKey("warp-list",
                    Map.of("warps", String.join(", ", warps.stream().map(Warp::getName).toList())));
        }
    }

    private final class SetWarpCommand extends MysticCommand {
        private final RequiredArg<String> name = withRequiredArg("name", "Warp name", ArgTypes.STRING);

        SetWarpCommand() {
            super(WarpModule.this.core, "setwarp", "Create or update a server warp.");
            requirePermission(ADMIN_PERMISSION);
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            PlayerRef player = sender.player().orElseThrow();
            if (core.platform().isInTemporaryWorld(player)) {
                sender.replyKey("warp-temp-world");
                return;
            }
            String warpName = sender.get(name);
            Warp existing = serverWarps.get(key(warpName));
            if (existing != null) {
                existing.setLocation(Conversions.capture(player));
                setServerWarp(existing);
            } else {
                setServerWarp(new Warp(warpName, Conversions.capture(player)));
            }
            sender.replyKey("warp-saved", Map.of("warp", warpName));
        }
    }

    private final class DelWarpCommand extends MysticCommand {
        private final RequiredArg<String> name = withRequiredArg("name", "Warp name", ArgTypes.STRING)
                .suggest(allWarpSuggestions());

        DelWarpCommand() {
            super(WarpModule.this.core, "delwarp", "Delete a server warp.");
            requirePermission(ADMIN_PERMISSION);
        }

        @Override
        protected void run(MysticCommandSender sender) {
            String warpName = sender.get(name);
            sender.replyKey(deleteServerWarp(warpName) ? "warp-deleted" : "warp-unknown",
                    Map.of("warp", warpName));
        }
    }

    /**
     * {@code /pwarp} — no args opens the browse-all UI; {@code /pwarp <name>}
     * teleports (positional variant); real subcommands {@code create <name>},
     * {@code delete <name>}, and {@code manage} handle management.
     */
    private final class PlayerWarpCommand extends MysticCommand {
        PlayerWarpCommand() {
            super(WarpModule.this.core, "pwarp", "Browse, create, and manage player warps.");
            addAliases("pwarps");
            addAliases("playerwarp");
            addAliases("playerwarps");
            requirePermission(org.hyzionstudios.mysticessentials.api.Permissions.PLAYERWARP_USE);
            addUsageVariant(new PlayerWarpNamedVariant());
            addSubCommand(new PlayerWarpCreateCommand());
            addSubCommand(new PlayerWarpDeleteCommand());
            addSubCommand(new PlayerWarpManageCommand());
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            openPlayerWarpsUi(sender.player().orElseThrow());
        }
    }

    private final class PlayerWarpNamedVariant extends MysticCommand {
        private final RequiredArg<String> name = withRequiredArg("name", "Player-warp name",
                ArgTypes.STRING).suggest(playerWarpSuggestions());

        PlayerWarpNamedVariant() {
            super(WarpModule.this.core, "Teleport to a player warp.");
            requirePermission(org.hyzionstudios.mysticessentials.api.Permissions.PLAYERWARP_USE);
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            String warpName = sender.get(name);
            Optional<Warp> warp = getPlayerWarp(warpName);
            if (warp.isEmpty()) {
                sender.replyKey("pwarp-unknown", Map.of("warp", warpName));
                return;
            }
            warpTo(sender, warp.get());
        }
    }

    private final class PlayerWarpCreateCommand extends MysticCommand {
        private final RequiredArg<String> name =
                withRequiredArg("name", "New player-warp name", ArgTypes.STRING);

        PlayerWarpCreateCommand() {
            super(WarpModule.this.core, "create", "Create a player warp at your location.");
            requirePermission(org.hyzionstudios.mysticessentials.api.Permissions.PLAYERWARP_CREATE);
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            PlayerRef player = sender.player().orElseThrow();
            if (core.platform().isInTemporaryWorld(player)) {
                sender.replyKey("pwarp-temp-world");
                return;
            }
            String warpName = sender.get(name);
            if (createPlayerWarp(sender.uuid(), warpName, Conversions.capture(player))) {
                sender.replyKey("pwarp-created", Map.of("warp", warpName));
                openPlayerWarpManagerUi(player, warpName);
            } else if (getPlayerWarp(warpName).isPresent()) {
                sender.replyKey("pwarp-name-taken");
            } else {
                sender.replyKey("pwarp-limit", Map.of("limit", Integer.toString(playerWarpLimit(sender.uuid()))));
            }
        }
    }

    private final class PlayerWarpDeleteCommand extends MysticCommand {
        private final RequiredArg<String> name = withRequiredArg("name", "Player-warp name",
                ArgTypes.STRING).suggest(playerWarpSuggestions());

        PlayerWarpDeleteCommand() {
            super(WarpModule.this.core, "delete", "Delete one of your player warps.");
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            String warpName = sender.get(name);
            boolean deleted = deletePlayerWarp(sender.uuid(), warpName)
                    || (sender.hasPermission(PWARP_ADMIN_PERMISSION) && deleteAnyPlayerWarp(warpName));
            sender.replyKey(deleted ? "pwarp-deleted" : "pwarp-not-owned",
                    Map.of("warp", warpName));
        }
    }

    private final class PlayerWarpManageCommand extends MysticCommand {
        PlayerWarpManageCommand() {
            super(WarpModule.this.core, "manage", "Open the player-warp manager.");
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            openPlayerWarpManagerUi(sender.player().orElseThrow());
        }
    }
}
