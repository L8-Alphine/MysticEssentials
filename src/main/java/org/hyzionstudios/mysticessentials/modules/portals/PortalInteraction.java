package org.hyzionstudios.mysticessentials.modules.portals;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.teleport.PendingTeleport;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * The {@code MysticPortal} block interaction. Reference it from any block
 * asset to make that block a portal:
 *
 * <pre>{@code
 * "Interactions": {
 *   "CollisionEnter": { "Interactions": [ { "Type": "MysticPortal" } ] },
 *   "Use":            { "Interactions": [ { "Type": "MysticPortal" } ] }
 * }
 * }</pre>
 *
 * <p>{@code CollisionEnter} runs the configured portal action; {@code Use}
 * opens the config page for players with the portal admin permission. The
 * optional {@code WorldName} / {@code Host} / {@code Port} codec fields seed
 * the initial configuration of a portal the first time a block of that type is
 * triggered, so asset packs can ship pre-wired portal blocks.</p>
 */
public final class PortalInteraction extends SimpleInstantInteraction {

    public static final String TYPE_ID = "MysticPortal";

    public static final BuilderCodec<PortalInteraction> CODEC = BuilderCodec
            .builder(PortalInteraction.class, PortalInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Mystic Essentials portal. Use (F) opens the admin config page; "
                    + "CollisionEnter runs the configured action (world/server/command). "
                    + "Optional WorldName or Host+Port seed a newly created portal.")
            .append(new KeyedCodec<>("WorldName", Codec.STRING),
                    (o, v) -> o.seedWorld = v == null ? "" : v, o -> o.seedWorld)
            .documentation("Optional: target world pre-set on newly created portals of this block type.")
            .add()
            .append(new KeyedCodec<>("Host", Codec.STRING),
                    (o, v) -> o.seedHost = v == null ? "" : v, o -> o.seedHost)
            .documentation("Optional: server host pre-set on newly created portals of this block type.")
            .add()
            .append(new KeyedCodec<>("Port", Codec.INTEGER),
                    (o, v) -> o.seedPort = v == null ? 0 : v, o -> o.seedPort)
            .documentation("Optional: server port pre-set on newly created portals of this block type.")
            .add()
            .build();

    /** Ignore repeated CollisionEnter triggers on the same portal within this window. */
    private static final long TRIGGER_COOLDOWN_MS = 2500L;
    /** Ignore any further teleport attempts while one is in flight. */
    private static final long TELEPORT_LOCK_MS = 1500L;
    private static final long TICK_MS = 50L;

    private static final ConcurrentHashMap<UUID, TriggerState> LAST_TRIGGER = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long> TELEPORT_LOCK = new ConcurrentHashMap<>();

    private String seedWorld = "";
    private String seedHost = "";
    private int seedPort;

    @Override
    public WaitForDataFrom getWaitForDataFrom() {
        return WaitForDataFrom.Server;
    }

    @Override
    protected void firstRun(InteractionType type, InteractionContext context, CooldownHandler cooldownHandler) {
        PortalsModule portals = PortalsModule.active();
        if (portals == null) {
            return;
        }
        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
        Ref<EntityStore> ref = context.getEntity();
        if (commandBuffer == null || ref == null) {
            return;
        }
        Player player = commandBuffer.getComponent(ref, Player.getComponentType());
        if (player == null || player.isWaitingForClientReady()) {
            return;
        }
        // Don't stack actions while a teleport is already underway.
        Archetype<EntityStore> archetype = commandBuffer.getArchetype(ref);
        if (archetype != null && (archetype.contains(Teleport.getComponentType())
                || archetype.contains(PendingTeleport.getComponentType()))) {
            return;
        }
        PlayerRef playerRef = commandBuffer.getComponent(ref, PlayerRef.getComponentType());
        BlockPosition block = context.getTargetBlock();
        if (playerRef == null || block == null) {
            return;
        }
        String worldId = playerRef.getWorldUuid() == null ? "" : playerRef.getWorldUuid().toString();

        if (type == InteractionType.Use) {
            if (portals.canConfigure(playerRef.getUuid())) {
                Portal portal = portals.findAt(worldId, block.x, block.y, block.z)
                        .orElseGet(() -> seeded(portals, portals.getOrCreate(worldId, block.x, block.y, block.z)));
                portals.openConfigUi(playerRef, portal);
            }
            return;
        }
        if (type != InteractionType.CollisionEnter) {
            return;
        }

        Portal portal = portals.findAt(worldId, block.x, block.y, block.z)
                .orElseGet(() -> seeded(portals, portals.getOrCreate(worldId, block.x, block.y, block.z)));
        if (!shouldTrigger(playerRef.getUuid(), portal.key())) {
            return;
        }
        if (!portals.canUse(playerRef.getUuid(), portal)) {
            portals.core().getMessageService().sendKey(playerRef, "portal-no-permission");
            return;
        }
        switch (portal.getType()) {
            case WORLD -> enterWorldPortal(portals, commandBuffer, ref, playerRef, portal);
            case SERVER -> enterServerPortal(portals, playerRef, portal);
            case COMMAND -> enterCommandPortal(portals, playerRef, portal, worldId, block);
        }
    }

    /** Applies the block asset's seed fields to a freshly created, unconfigured portal. */
    private Portal seeded(PortalsModule portals, Portal portal) {
        boolean unconfigured = portal.getTargetWorld().isBlank() && portal.getHost().isBlank()
                && portal.getCommand().isBlank();
        if (!unconfigured) {
            return portal;
        }
        if (!seedHost.isBlank() && seedPort > 0) {
            portal.setType(Portal.Type.SERVER);
            portal.setHost(seedHost);
            portal.setPort(seedPort);
            portals.save(portal);
        } else if (!seedWorld.isBlank()) {
            portal.setType(Portal.Type.WORLD);
            portal.setTargetWorld(seedWorld);
            portals.save(portal);
        }
        return portal;
    }

    // ----- Actions -----------------------------------------------------------

    private static void enterWorldPortal(PortalsModule portals, CommandBuffer<EntityStore> commandBuffer,
            Ref<EntityStore> ref, PlayerRef playerRef, Portal portal) {
        String targetName = portal.getTargetWorld();
        if (targetName.isBlank() || isTeleportLocked(playerRef.getUuid())) {
            return;
        }
        World target = findWorld(targetName);
        if (target == null) {
            // Not loaded — kick off a load if the universe knows the world; the
            // next portal entry (after the cooldown) will find it loaded.
            try {
                Universe universe = Universe.get();
                if (universe != null && universe.isWorldLoadable(targetName)) {
                    universe.loadWorld(targetName);
                    portals.core().getMessageService().sendKey(playerRef, "portal-world-loading",
                            java.util.Map.of("world", targetName));
                    lockTeleport(playerRef.getUuid());
                    return;
                }
            } catch (Throwable ignored) {
                // Fall through to the unknown-world message.
            }
            portals.core().getMessageService().sendKey(playerRef, "portal-world-unknown",
                    java.util.Map.of("world", targetName));
            return;
        }
        Transform destination = portal.isUseLocation()
                ? locationTransform(portal)
                : spawnTransform(target, playerRef.getUuid());
        if (destination == null) {
            portals.core().getMessageService().sendKey(playerRef, "portal-world-unknown",
                    java.util.Map.of("world", targetName));
            return;
        }
        lockTeleport(playerRef.getUuid());
        commandBuffer.putComponent(ref, Teleport.getComponentType(),
                Teleport.createForPlayer(target, destination));
    }

    private static void enterServerPortal(PortalsModule portals, PlayerRef playerRef, Portal portal) {
        if (portal.getHost().isBlank() || portal.getPort() <= 0) {
            return;
        }
        try {
            playerRef.referToServer(portal.getHost(), portal.getPort(), null);
        } catch (Throwable t) {
            portals.logInfo("Portal " + portal.getId() + " server refer failed: " + t);
        }
    }

    private void enterCommandPortal(PortalsModule portals, PlayerRef playerRef, Portal portal,
            String worldId, BlockPosition block) {
        String raw = portal.getCommand();
        if (raw.isBlank()) {
            return;
        }
        String worldName = portals.worldLabel(worldId);
        String resolved = raw
                .replace("{PlayerUsername}", safe(playerRef.getUsername()))
                .replace("{PlayerUuid}", playerRef.getUuid() == null ? "" : playerRef.getUuid().toString())
                .replace("{PosX}", Integer.toString(block.x))
                .replace("{PosY}", Integer.toString(block.y))
                .replace("{PosZ}", Integer.toString(block.z))
                .replace("{WorldName}", safe(worldName));
        boolean asPlayer = "player".equals(portal.getCommandSender());
        runSequence(portals, playerRef, asPlayer, splitCommands(resolved), 0);
    }

    /**
     * Runs one command of the sequence and schedules the rest. A {@code wait N}
     * / {@code sleep N} entry pauses the sequence N ticks; other entries get a
     * two-tick spacing so teleports settle between commands.
     */
    private static void runSequence(PortalsModule portals, PlayerRef playerRef, boolean asPlayer,
            List<String> commands, int index) {
        if (index >= commands.size()) {
            return;
        }
        String entry = commands.get(index).trim();
        if (entry.isEmpty()) {
            runSequence(portals, playerRef, asPlayer, commands, index + 1);
            return;
        }
        Long waitTicks = parseWait(entry);
        if (waitTicks != null) {
            scheduleNext(portals, playerRef, asPlayer, commands, index + 1, waitTicks);
            return;
        }
        String command = entry.startsWith("/") ? entry.substring(1).trim() : entry;
        if (asPlayer) {
            portals.core().platform().dispatchPlayerCommand(playerRef, command);
        } else {
            portals.core().platform().dispatchConsoleCommand(command);
        }
        if (index + 1 < commands.size()) {
            scheduleNext(portals, playerRef, asPlayer, commands, index + 1, 2L);
        }
    }

    private static void scheduleNext(PortalsModule portals, PlayerRef playerRef, boolean asPlayer,
            List<String> commands, int index, long ticks) {
        portals.core().scheduler().runLater(
                () -> runSequence(portals, playerRef, asPlayer, commands, index),
                Math.max(0L, ticks) * TICK_MS, TimeUnit.MILLISECONDS);
    }

    // ----- Helpers -----------------------------------------------------------

    private static boolean shouldTrigger(UUID uuid, String portalKey) {
        long now = System.currentTimeMillis();
        TriggerState state = LAST_TRIGGER.get(uuid);
        if (state != null && portalKey.equals(state.portalKey()) && now - state.lastMs() < TRIGGER_COOLDOWN_MS) {
            return false;
        }
        LAST_TRIGGER.put(uuid, new TriggerState(portalKey, now));
        return true;
    }

    private static boolean isTeleportLocked(UUID uuid) {
        Long until = TELEPORT_LOCK.get(uuid);
        if (until == null) {
            return false;
        }
        if (System.currentTimeMillis() >= until) {
            TELEPORT_LOCK.remove(uuid);
            return false;
        }
        return true;
    }

    private static void lockTeleport(UUID uuid) {
        TELEPORT_LOCK.put(uuid, System.currentTimeMillis() + TELEPORT_LOCK_MS);
    }

    private static World findWorld(String name) {
        try {
            Universe universe = Universe.get();
            if (universe == null) {
                return null;
            }
            World exact = universe.getWorld(name);
            if (exact != null) {
                return exact;
            }
            for (World world : universe.getWorlds().values()) {
                if (world != null && name.equalsIgnoreCase(world.getName())) {
                    return world;
                }
            }
        } catch (Throwable ignored) {
            // Treated as world-not-found.
        }
        return null;
    }

    private static Transform spawnTransform(World world, UUID uuid) {
        try {
            var config = world.getWorldConfig();
            var provider = config == null ? null : config.getSpawnProvider();
            return provider == null ? null : provider.getSpawnPoint(world, uuid);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Transform locationTransform(Portal portal) {
        String facing = portal.getFacing();
        if (facing.isBlank()) {
            return new Transform(portal.getPosX(), portal.getPosY(), portal.getPosZ());
        }
        float yaw = switch (facing) {
            case "E" -> (float) (-Math.PI / 2.0);
            case "S" -> (float) Math.PI;
            case "W" -> (float) (Math.PI / 2.0);
            default -> 0.0f; // N
        };
        return new Transform(portal.getPosX(), portal.getPosY(), portal.getPosZ(), 0.0f, yaw, 0.0f);
    }

    /** Splits on {@code ;} or {@code ||} outside double quotes. */
    static List<String> splitCommands(String raw) {
        List<String> result = new ArrayList<>();
        if (raw == null) {
            return result;
        }
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"') {
                quoted = !quoted;
                current.append(c);
            } else if (!quoted && c == ';') {
                result.add(current.toString().trim());
                current.setLength(0);
            } else if (!quoted && c == '|' && i + 1 < raw.length() && raw.charAt(i + 1) == '|') {
                result.add(current.toString().trim());
                current.setLength(0);
                i++;
            } else {
                current.append(c);
            }
        }
        String tail = current.toString().trim();
        if (!tail.isEmpty()) {
            result.add(tail);
        }
        return result;
    }

    private static Long parseWait(String entry) {
        String lower = entry.toLowerCase(java.util.Locale.ROOT);
        if (!lower.startsWith("wait ") && !lower.startsWith("sleep ")) {
            return null;
        }
        try {
            return Math.max(0L, Long.parseLong(lower.substring(lower.indexOf(' ') + 1).trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private record TriggerState(String portalKey, long lastMs) {
    }
}
