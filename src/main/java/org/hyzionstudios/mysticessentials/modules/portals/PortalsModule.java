package org.hyzionstudios.mysticessentials.modules.portals;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.hyzionstudios.mysticessentials.api.Permissions;
import org.hyzionstudios.mysticessentials.core.module.AbstractMysticModule;
import org.hyzionstudios.mysticessentials.core.util.Json;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommand;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommandSender;

import com.google.gson.reflect.TypeToken;
import com.hypixel.hytale.event.EventRegistration;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.meta.state.BlockMapMarkersResource;
import org.joml.Vector3i;

/**
 * Block-anchored portals (ported from the standalone PortalWorld mod). Any
 * block whose asset declares the {@code MysticPortal} interaction becomes a
 * portal: walking into it teleports the player to another world, refers them
 * to another server, or runs a command sequence; pressing Use (F) on it opens
 * the in-game config page for admins. Portal configuration is persisted to
 * {@code data/modules/portals/portals.json}; the blocks themselves come from
 * any asset pack that references the interaction type.
 *
 * <p>Portals can show a world-map marker (vanilla {@code MapMarkers} icons,
 * e.g. {@code Portal.png}). Markers are re-applied whenever a player joins a
 * world and removed when the portal's anchor block is broken or the portal is
 * deleted.</p>
 */
public final class PortalsModule extends AbstractMysticModule {

    private static final Type PORTAL_MAP_TYPE = new TypeToken<LinkedHashMap<String, Portal>>() {
    }.getType();

    /** How close (dx, dy, dz) a trigger block may be to an anchor to count as the same portal. */
    private static final int NEAR_X = 1;
    private static final int NEAR_Y = 2;
    private static final int NEAR_Z = 1;

    static final String DEFAULT_MARKER_ICON = "Portal.png";

    /**
     * Live module instance for {@link PortalInteraction}. Interactions are
     * instantiated by the asset system outside the module lifecycle, so they
     * reach the module through this holder; {@code null} while the module is
     * disabled, which turns portal blocks inert.
     */
    private static volatile PortalsModule active;

    private Map<String, Portal> portals = new LinkedHashMap<>();

    /** Worlds (by uuid string) that already have break/join listeners attached. */
    private final Set<String> listenedWorlds = ConcurrentHashMap.newKeySet();
    private final List<EventRegistration<?, ?>> worldRegistrations = new ArrayList<>();
    private ScheduledFuture<?> worldScanTask;

    public PortalsModule() {
        super("portals", "Portals", "1.0.0");
    }

    static PortalsModule active() {
        return active;
    }

    /** Core handle for the interaction/pages, which live outside the module lifecycle. */
    org.hyzionstudios.mysticessentials.core.MysticCore core() {
        return core;
    }

    void logInfo(String message) {
        log(message);
    }

    @Override
    public void onEnable() {
        portals = loadPortals();
        registerCommand(new PortalCommand());
        // Worlds load dynamically and world event registries are per world, so a
        // cheap repeating scan attaches break/join listeners to new worlds.
        worldScanTask = core.scheduler().runRepeating(this::attachWorldListeners, 1, 2, TimeUnit.SECONDS);
        core.scheduler().runLater(this::reapplyAllMarkers, 3, TimeUnit.SECONDS);
        active = this;
        log("Loaded " + portals.size() + " portal(s).");
    }

    @Override
    public void onDisable() {
        active = null;
        if (worldScanTask != null) {
            worldScanTask.cancel(false);
            worldScanTask = null;
        }
        synchronized (worldRegistrations) {
            for (EventRegistration<?, ?> registration : worldRegistrations) {
                try {
                    registration.unregister();
                } catch (Throwable ignored) {
                    // One-shot handle; already gone or engine shutting down.
                }
            }
            worldRegistrations.clear();
        }
        listenedWorlds.clear();
        savePortals();
    }

    // ----- Storage -----------------------------------------------------------

    private java.nio.file.Path portalFile() {
        return core.paths().moduleDataDir(id()).resolve("portals.json");
    }

    private Map<String, Portal> loadPortals() {
        try {
            com.google.gson.JsonElement element = Json.readFile(portalFile());
            Map<String, Portal> loaded = element == null ? null : Json.gson().fromJson(element, PORTAL_MAP_TYPE);
            if (loaded == null) {
                return new LinkedHashMap<>();
            }
            loaded.values().forEach(portal -> {
                if (portal.getId().isBlank()) {
                    portal.setId(generateId(portal.getWorld(), portal.getX(), portal.getY(), portal.getZ()));
                }
            });
            return loaded;
        } catch (Exception e) {
            log("Failed to load portals: " + e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    void savePortals() {
        try {
            Json.writeFile(portalFile(), portals);
        } catch (Exception e) {
            log("Failed to save portals: " + e.getMessage());
        }
    }

    private static String generateId(String world, int x, int y, int z) {
        return "portal_" + Integer.toHexString(Objects.hash(world, x, y, z, System.nanoTime()));
    }

    // ----- Lookup ------------------------------------------------------------

    synchronized Optional<Portal> get(String worldId, int x, int y, int z) {
        return Optional.ofNullable(portals.get(Portal.key(worldId, x, y, z)));
    }

    Optional<Portal> byId(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return snapshot().stream().filter(p -> p.getId().equalsIgnoreCase(id.trim())).findFirst();
    }

    synchronized List<Portal> snapshot() {
        return new ArrayList<>(portals.values());
    }

    /**
     * Finds the portal whose anchor is closest to a trigger position, exact
     * match first. Multi-block portals fire collisions from blocks around the
     * anchor, so anything within the near box maps to the anchor; an ambiguous
     * tie (two anchors equally close) resolves to none.
     */
    synchronized Optional<Portal> findAt(String worldId, int x, int y, int z) {
        Portal exact = portals.get(Portal.key(worldId, x, y, z));
        if (exact != null) {
            return Optional.of(exact);
        }
        Portal best = null;
        int bestScore = Integer.MAX_VALUE;
        int secondScore = Integer.MAX_VALUE;
        for (Portal portal : portals.values()) {
            if (!portal.getWorld().equalsIgnoreCase(worldId)) {
                continue;
            }
            int dx = Math.abs(portal.getX() - x);
            int dy = Math.abs(portal.getY() - y);
            int dz = Math.abs(portal.getZ() - z);
            if (dx > NEAR_X || dy > NEAR_Y || dz > NEAR_Z) {
                continue;
            }
            int score = dx + dy * 2 + dz;
            if (score < bestScore) {
                secondScore = bestScore;
                best = portal;
                bestScore = score;
            } else if (score < secondScore) {
                secondScore = score;
            }
        }
        return best != null && bestScore < secondScore ? Optional.of(best) : Optional.empty();
    }

    /** Nearest portal to a position in a world within {@code radius} blocks (any axis). */
    synchronized Optional<Portal> findNearest(String worldId, double x, double y, double z, int radius) {
        Portal best = null;
        double bestDist = Double.MAX_VALUE;
        for (Portal portal : portals.values()) {
            if (!portal.getWorld().equalsIgnoreCase(worldId)) {
                continue;
            }
            double dx = portal.getX() - x;
            double dy = portal.getY() - y;
            double dz = portal.getZ() - z;
            if (Math.abs(dx) > radius || Math.abs(dy) > radius || Math.abs(dz) > radius) {
                continue;
            }
            double dist = dx * dx + dy * dy + dz * dz;
            if (dist < bestDist) {
                best = portal;
                bestDist = dist;
            }
        }
        return Optional.ofNullable(best);
    }

    /** Existing portal at the trigger position, or a new unconfigured one anchored there. */
    synchronized Portal getOrCreate(String worldId, int x, int y, int z) {
        Portal existing = findAt(worldId, x, y, z).orElse(null);
        if (existing != null) {
            return existing;
        }
        Portal portal = new Portal(generateId(worldId, x, y, z), worldId, x, y, z);
        portals.put(portal.key(), portal);
        savePortals();
        log("Created portal " + portal.getId() + " at " + worldId + " " + x + "," + y + "," + z);
        return portal;
    }

    synchronized void save(Portal portal) {
        portals.put(portal.key(), portal);
        savePortals();
        core.platform().world(portal.getWorld()).ifPresent(world -> applyMarker(world, portal));
    }

    synchronized boolean delete(Portal portal) {
        boolean removed = portals.remove(portal.key()) != null;
        if (removed) {
            savePortals();
            core.platform().world(portal.getWorld()).ifPresent(world ->
                    removeMarkerAt(world, portal.getX(), portal.getY(), portal.getZ()));
        }
        return removed;
    }

    boolean canConfigure(UUID uuid) {
        return core.getPermissionService().has(uuid, Permissions.PORTAL_ADMIN);
    }

    boolean canUse(UUID uuid, Portal portal) {
        return portal.getPermission().isBlank()
                || core.getPermissionService().has(uuid, portal.getPermission());
    }

    // ----- World listeners: break cleanup + marker reapply --------------------

    private void attachWorldListeners() {
        Universe universe;
        try {
            universe = Universe.get();
        } catch (Throwable t) {
            return;
        }
        if (universe == null) {
            return;
        }
        for (World world : universe.getWorlds().values()) {
            if (world == null) {
                continue;
            }
            String worldId = worldUuid(world);
            if (worldId.isBlank() || !listenedWorlds.add(worldId)) {
                continue;
            }
            try {
                World target = world;
                synchronized (worldRegistrations) {
                    worldRegistrations.add(target.getEventRegistry().registerGlobal(
                            BreakBlockEvent.class, event -> onBreakBlock(target, event)));
                    worldRegistrations.add(target.getEventRegistry().registerGlobal(
                            AddPlayerToWorldEvent.class, event -> scheduleMarkerReapply(target)));
                }
                scheduleMarkerReapply(world);
            } catch (Throwable t) {
                listenedWorlds.remove(worldId);
                log("Failed to attach portal listeners to world " + world.getName() + ": " + t);
            }
        }
    }

    private void onBreakBlock(World world, BreakBlockEvent event) {
        try {
            Vector3i pos = event.getTargetBlock();
            if (pos == null) {
                return;
            }
            Portal portal;
            synchronized (this) {
                portal = portals.get(Portal.key(worldUuid(world), pos.x(), pos.y(), pos.z()));
                if (portal == null) {
                    portal = portals.get(Portal.key(world.getName(), pos.x(), pos.y(), pos.z()));
                }
                if (portal == null) {
                    return;
                }
                portals.remove(portal.key());
                savePortals();
            }
            removeMarkerAt(world, portal.getX(), portal.getY(), portal.getZ());
            log("Removed portal " + portal.getId() + " (anchor block broken).");
        } catch (Throwable t) {
            log("Portal break cleanup failed: " + t);
        }
    }

    // ----- Map markers -------------------------------------------------------

    /** Adds/refreshes or removes the world-map marker to match the portal config. */
    private void applyMarker(World world, Portal portal) {
        try {
            BlockMapMarkersResource markers = markers(world);
            if (markers == null) {
                return;
            }
            Vector3i pos = new Vector3i(portal.getX(), portal.getY(), portal.getZ());
            markers.removeMarker(pos);
            if (portal.isMarkerEnabled()) {
                String text = portal.getMarkerText().isBlank() ? portal.getName() : portal.getMarkerText();
                if (text.isBlank()) {
                    text = portal.getId();
                }
                String icon = portal.getMarkerIcon().isBlank() ? DEFAULT_MARKER_ICON : portal.getMarkerIcon();
                markers.addMarker(pos, text, icon);
            }
        } catch (Throwable t) {
            log("Failed to apply map marker for " + portal.getId() + ": " + t);
        }
    }

    private void removeMarkerAt(World world, int x, int y, int z) {
        try {
            BlockMapMarkersResource markers = markers(world);
            if (markers != null) {
                markers.removeMarker(new Vector3i(x, y, z));
            }
        } catch (Throwable t) {
            log("Failed to remove map marker: " + t);
        }
    }

    private static BlockMapMarkersResource markers(World world) {
        try {
            var store = world.getChunkStore().getStore();
            return store == null ? null
                    : store.getResource(BlockMapMarkersResource.getResourceType());
        } catch (Throwable t) {
            return null;
        }
    }

    /** Marker state lives per chunk store; re-assert it shortly after joins/loads. */
    private void scheduleMarkerReapply(World world) {
        core.scheduler().runLater(() -> reapplyWorldMarkers(world), 500, TimeUnit.MILLISECONDS);
    }

    private void reapplyAllMarkers() {
        try {
            Universe universe = Universe.get();
            if (universe == null) {
                return;
            }
            for (World world : universe.getWorlds().values()) {
                if (world != null) {
                    reapplyWorldMarkers(world);
                }
            }
        } catch (Throwable ignored) {
            // Universe not ready yet; the world scan will retry via joins.
        }
    }

    private void reapplyWorldMarkers(World world) {
        String uuid = worldUuid(world);
        String name;
        try {
            name = world.getName() == null ? "" : world.getName();
        } catch (Throwable t) {
            name = "";
        }
        for (Portal portal : snapshot()) {
            if (portal.getWorld().equalsIgnoreCase(uuid) || portal.getWorld().equalsIgnoreCase(name)) {
                applyMarker(world, portal);
            }
        }
    }

    static String worldUuid(World world) {
        try {
            if (world == null || world.getWorldConfig() == null) {
                return "";
            }
            UUID uuid = world.getWorldConfig().getUuid();
            return uuid == null ? "" : uuid.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** Human-friendly world label for a stored world id (uuid → name when resolvable). */
    String worldLabel(String worldId) {
        return core.platform().world(worldId)
                .map(world -> {
                    try {
                        return world.getName() == null || world.getName().isBlank() ? worldId : world.getName();
                    } catch (Throwable t) {
                        return worldId;
                    }
                })
                .orElse(worldId);
    }

    // ----- UI ----------------------------------------------------------------

    void openConfigUi(PlayerRef player, Portal portal) {
        core.platform().openPage(player, new PortalPages.PortalAdminPage(core, this, player, portal.key()));
    }

    // ----- Commands ----------------------------------------------------------

    private com.hypixel.hytale.server.core.command.system.suggestion.SuggestionProvider portalIdSuggestions() {
        return (commandSender, input, index, result) ->
                snapshot().forEach(portal -> result.suggest(portal.getId()));
    }

    private String describe(Portal portal) {
        String action = switch (portal.getType()) {
            case WORLD -> portal.getTargetWorld().isBlank() ? "world <unset>"
                    : "world " + portal.getTargetWorld();
            case SERVER -> portal.getHost().isBlank() ? "server <unset>"
                    : "server " + portal.getHost() + ":" + portal.getPort();
            case COMMAND -> portal.getCommand().isBlank() ? "command <unset>" : "command";
        };
        String name = portal.getName().isBlank() ? portal.getId() : portal.getName();
        return name + " &7(" + action + ") &8@ &7" + worldLabel(portal.getWorld())
                + " " + portal.getX() + "," + portal.getY() + "," + portal.getZ();
    }

    /** {@code /portal} — list, edit (nearest), and remove portals. */
    private final class PortalCommand extends MysticCommand {
        PortalCommand() {
            super(PortalsModule.this.core, "portal", "Manage Mystic portals.");
            addAliases("portals");
            requirePermission(Permissions.PORTAL_ADMIN);
            addSubCommand(new PortalListCommand());
            addSubCommand(new PortalEditCommand());
            addSubCommand(new PortalRemoveCommand());
        }

        @Override
        protected void run(MysticCommandSender sender) {
            sender.replyKey("portal-help");
        }
    }

    private final class PortalListCommand extends MysticCommand {
        PortalListCommand() {
            super(PortalsModule.this.core, "list", "List all portals.");
            requirePermission(Permissions.PORTAL_ADMIN);
        }

        @Override
        protected void run(MysticCommandSender sender) {
            List<Portal> all = snapshot();
            if (all.isEmpty()) {
                sender.replyKey("portal-none");
                return;
            }
            sender.replyKey("portal-list-header", Map.of("count", Integer.toString(all.size())));
            for (Portal portal : all) {
                sender.replyKey("portal-list-entry", Map.of("entry", describe(portal)));
            }
        }
    }

    private final class PortalEditCommand extends MysticCommand {
        PortalEditCommand() {
            super(PortalsModule.this.core, "edit", "Open the config page for the nearest portal.");
            requirePermission(Permissions.PORTAL_ADMIN);
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            PlayerRef player = sender.player().orElseThrow();
            var transform = player.getTransform();
            String worldId = player.getWorldUuid() == null ? "" : player.getWorldUuid().toString();
            Optional<Portal> portal = findNearest(worldId,
                    transform.getPosition().x(), transform.getPosition().y(), transform.getPosition().z(), 8);
            if (portal.isEmpty()) {
                sender.replyKey("portal-none-near");
                return;
            }
            openConfigUi(player, portal.get());
        }
    }

    private final class PortalRemoveCommand extends MysticCommand {
        private final RequiredArg<String> id = withRequiredArg("id", "Portal id", ArgTypes.STRING)
                .suggest(portalIdSuggestions());

        PortalRemoveCommand() {
            super(PortalsModule.this.core, "remove", "Delete a portal by id.");
            requirePermission(Permissions.PORTAL_ADMIN);
        }

        @Override
        protected void run(MysticCommandSender sender) {
            String portalId = sender.get(id);
            Optional<Portal> portal = byId(portalId);
            if (portal.isEmpty() || !delete(portal.get())) {
                sender.replyKey("portal-unknown", Map.of("portal", portalId));
                return;
            }
            sender.replyKey("portal-removed", Map.of("portal", portalId));
        }
    }
}
