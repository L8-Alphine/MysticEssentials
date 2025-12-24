package com.alphine.mysticessentials.npc;

import com.alphine.mysticessentials.MysticEssentialsCommon;
import com.alphine.mysticessentials.npc.model.NpcDefinition;
import com.alphine.mysticessentials.npc.skin.NpcSkinService;
import com.alphine.mysticessentials.util.MessagesUtil;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

public final class NpcManager {

    private final MysticEssentialsCommon common;

    /** npcName -> runtime */
    private final Map<String, Runtime> runtimes = new HashMap<>();

    /** dirty NPC defs */
    private final Set<String> dirty = new HashSet<>();

    /** background executor for skin HTTP calls */
    private final ExecutorService skinExecutor;

    public NpcManager(MysticEssentialsCommon common) {
        this.common = common;

        boolean enableSkins = common.cfg != null
                && common.cfg.npcs != null
                && common.cfg.npcs.enabled
                && common.cfg.npcs.skin != null
                && common.cfg.npcs.skin.enabled;

        if (enableSkins) {
            this.skinExecutor = Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "MysticEssentials-NpcSkin");
                t.setDaemon(true);
                return t;
            });
        } else {
            this.skinExecutor = null;
        }
    }

    public void shutdown(MinecraftServer server) {
        // Despawn all runtime entities and clear state
        for (Runtime rt : runtimes.values()) {
            rt.despawn(server);
        }
        runtimes.clear();
        dirty.clear();
    }

    public void markDirty(String name) {
        if (name != null) dirty.add(name);
    }

    public void reloadAll(MinecraftServer server) {
        for (Runtime rt : runtimes.values()) rt.despawn(server);
        runtimes.clear();
        dirty.clear();

        if (common.npcs == null) return;

        for (NpcDefinition def : common.npcs.list()) {
            runtimes.put(def.name, new Runtime(def.name));
            dirty.add(def.name);
        }
    }

    public void tick(MinecraftServer server) {
        // Respect NPC toggle
        if (common.cfg == null || common.cfg.npcs == null || !common.cfg.npcs.enabled) return;
        if (common.npcs == null) return;

        // Ensure runtimes exist
        for (NpcDefinition def : common.npcs.list()) {
            runtimes.computeIfAbsent(def.name, Runtime::new);
        }

        // Apply dirty
        if (!dirty.isEmpty()) {
            var it = dirty.iterator();
            while (it.hasNext()) {
                String name = it.next();
                it.remove();

                NpcDefinition def = common.npcs.get(name);
                Runtime rt = runtimes.get(name);
                if (rt == null) continue;

                if (def == null || !def.enabled) {
                    rt.despawn(server);
                    continue;
                }

                rt.ensureSpawned(server, def);
                rt.applyDefinition(server, def);

                // If nameplate uses hologram binding, force hologram anchor to BOUND
                if (def.nameplate.enabled && def.nameplate.hologram != null && !def.nameplate.hologram.isBlank()) {
                    var holo = common.holograms.get(def.nameplate.hologram);
                    if (holo != null) {
                        holo.anchor.type = "BOUND";
                        // Position updates happen every tick below
                        common.hologramManager.markDirty(holo.name);
                    }
                }
            }
        }

        // Continuous: nameplate hologram follows NPC
        for (Runtime rt : runtimes.values()) {
            NpcDefinition def = common.npcs.get(rt.name);
            if (def == null || !def.enabled) continue;
            if (!def.nameplate.enabled) continue;
            if (def.nameplate.hologram == null || def.nameplate.hologram.isBlank()) continue;

            Entity npcEnt = rt.find(server);
            if (npcEnt == null) continue;

            var holo = common.holograms.get(def.nameplate.hologram);
            if (holo == null) continue;

            // sync hologram position to npc head offset
            holo.anchor.dimension = npcEnt.level().dimension().location().toString();
            holo.anchor.x = npcEnt.getX() + def.nameplate.offset.x;
            holo.anchor.y = npcEnt.getY() + def.nameplate.offset.y;
            holo.anchor.z = npcEnt.getZ() + def.nameplate.offset.z;

            common.hologramManager.markDirty(holo.name);
        }

        // Look-at-player behavior
        for (Runtime rt : runtimes.values()) {
            NpcDefinition def = common.npcs.get(rt.name);
            if (def == null || !def.enabled) continue;
            if (!def.behavior.lookAtPlayer.enabled) continue;

            if (++rt.lookTick < Math.max(1, def.behavior.lookAtPlayer.updateIntervalTicks)) continue;
            rt.lookTick = 0;

            Entity e = rt.find(server);
            if (e == null) continue;

            // Find nearest player in same dimension within range
            ServerLevel lvl = (ServerLevel) e.level();
            double range = def.behavior.lookAtPlayer.rangeBlocks;
            double best = range * range;
            ServerPlayer target = null;

            for (ServerPlayer p : lvl.players()) {
                double d2 = p.distanceToSqr(e);
                if (d2 <= best) {
                    best = d2;
                    target = p;
                }
            }

            if (target != null) {
                e.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, target.position());
            } else if (def.behavior.lookAtPlayer.returnToOriginalWhenOutOfRange) {
                e.setYRot(def.anchor.yaw);
                e.setXRot(def.anchor.pitch);
            }
        }

        // Pathing behaviour
        for (Runtime rt : runtimes.values()) {
            NpcDefinition def = common.npcs.get(rt.name);
            if (def == null || !def.enabled) continue;
            if (def.behavior == null || def.behavior.path == null || !def.behavior.path.enabled) continue;

            Entity e = rt.find(server);
            if (e == null) continue;

            rt.tickPath(e, def);
        }
    }

    private static ServerLevel level(MinecraftServer server, String dim) {
        ResourceLocation id = ResourceLocation.tryParse(dim);
        if (id == null) return null;
        ResourceKey<Level> key = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, id);
        return server.getLevel(key);
    }

    private final class Runtime {
        final String name;
        UUID entityId;
        int lookTick = 0;

        // per-player dialogue index
        final Map<UUID, Integer> dialogueIndex = new HashMap<>();

        // pathing state
        int pathIndex = 0;
        int pathWaitTicks = 0;

        // skin async state (PLAYER NPCs)
        volatile boolean skinRequested = false;
        volatile NpcSkinService.ResolvedSkin pendingSkin;

        Runtime(String name) {
            this.name = name;
        }

        void despawn(MinecraftServer server) {
            Entity e = find(server);
            if (e != null) e.discard();
            entityId = null;
        }

        Entity find(MinecraftServer server) {
            if (entityId == null) return null;
            for (ServerLevel lvl : server.getAllLevels()) {
                Entity e = lvl.getEntity(entityId);
                if (e != null) return e;
            }
            return null;
        }

        void ensureSpawned(MinecraftServer server, NpcDefinition def) {
            if ("ENTITY".equalsIgnoreCase(def.type.kind)) {
                ensureEntityNpc(server, def);
            } else if ("PLAYER".equalsIgnoreCase(def.type.kind)) {
                ensurePlayerNpc(server, def);
            } else {
                despawn(server);
            }
        }

        private void ensureEntityNpc(MinecraftServer server, NpcDefinition def) {
            Entity e = find(server);
            if (e != null && e.level().dimension().location().toString().equals(def.anchor.dimension)) {
                return;
            }
            despawn(server);

            ServerLevel lvl = level(server, def.anchor.dimension);
            if (lvl == null) return;

            EntityType<?> type = EntityType.byString(def.type.entityType).orElse(EntityType.VILLAGER);
            Entity spawned = type.create(lvl);
            if (spawned == null) return;

            spawned.moveTo(def.anchor.x, def.anchor.y, def.anchor.z, def.anchor.yaw, def.anchor.pitch);
            lvl.addFreshEntity(spawned);

            entityId = spawned.getUUID();
        }

        /**
         * PLAYER NPCs: skin is resolved on a background executor, then spawn happens on the main thread
         * the next tick when {@link #pendingSkin} is non-null.
         */
        private void ensurePlayerNpc(MinecraftServer server, NpcDefinition def) {
            if (common.npcPlatform == null) {
                System.out.println("[MysticEssentials] NPC platform not available; cannot spawn PLAYER-type NPCs.");
                return;
            }

            // Already spawned & still alive
            if (find(server) != null) return;

            // Figure out if skin subsystem is actually enabled
            boolean skinEnabled = common.cfg != null
                    && common.cfg.npcs != null
                    && common.cfg.npcs.skin != null
                    && common.cfg.npcs.skin.enabled
                    && common.npcSkinService != null
                    && skinExecutor != null;

            // If skin system is not enabled/available -> just spawn immediately with no skin
            if (!skinEnabled) {
                UUID spawned = common.npcPlatform.spawnPlayerNpc(server, def, entityId, null);
                if (spawned != null) {
                    entityId = spawned;
                }
                return;
            }

            // If we already have a resolved skin ready -> spawn now
            if (pendingSkin != null) {
                NpcSkinService.ResolvedSkin skin = pendingSkin;
                UUID spawned = common.npcPlatform.spawnPlayerNpc(server, def, entityId, skin);
                if (spawned != null) {
                    entityId = spawned;
                }
                pendingSkin = null; // consume once
                return;
            }

            // Skin request already in-flight?
            if (skinRequested) return;

            // Kick off async resolve (once)
            skinRequested = true;
            skinExecutor.submit(() -> {
                NpcSkinService.ResolvedSkin skin = common.npcSkinService
                        .resolve(server, def.type.playerProfile, null);

                if (skin != null) {
                    pendingSkin = skin; // main thread will see this next tick
                } else {
                    // failed: allow retries
                    skinRequested = false;
                }
            });
        }

        void applyDefinition(MinecraftServer server, NpcDefinition def) {
            Entity e = find(server);
            if (e == null) return;

            // teleport to anchor if moved / wrong dimension
            ServerLevel lvl = level(server, def.anchor.dimension);
            if (lvl != null && e.level() != lvl) {
                // simplest: despawn and respawn in correct dimension
                despawn(server);
                ensureSpawned(server, def);
                e = find(server);
                if (e == null) return;
            }

            e.moveTo(def.anchor.x, def.anchor.y, def.anchor.z, def.anchor.yaw, def.anchor.pitch);

            // ENTITY-type behavior for LivingEntity
            if (e instanceof LivingEntity le && "ENTITY".equalsIgnoreCase(def.type.kind)) {
                le.getBrain().clearMemories();

                if (e instanceof Mob mob) {
                    mob.setPersistenceRequired();
                    mob.setNoAi(def.behavior.entityOptions.noAI);
                }

                le.setSilent(def.behavior.entityOptions.silent);
                le.setInvulnerable(def.behavior.entityOptions.invulnerable);

                le.setCustomNameVisible(!def.nameplate.enabled || !def.nameplate.hideEntityName);
                le.setCustomName(Component.literal(def.display.displayName));

                // Armor stand niceties
                if (le instanceof net.minecraft.world.entity.decoration.ArmorStand stand) {
                    stand.setShowArms(true);
                    stand.setNoBasePlate(true);
                }

                // equipment
                if (def.equipment != null && def.equipment.items != null) {
                    var registries = server.registryAccess();
                    for (var entry : def.equipment.items.entrySet()) {
                        String slotName = entry.getKey();
                        var slot = NpcDefinition.toEquipmentSlot(slotName);
                        if (slot == null) continue;

                        if (!def.equipment.isVisible(slotName)) {
                            le.setItemSlot(slot, net.minecraft.world.item.ItemStack.EMPTY);
                            continue;
                        }

                        var stack = com.alphine.mysticessentials.npc.util.NpcItemCodec.decode(entry.getValue(), registries);
                        if (stack.isEmpty()) {
                            le.setItemSlot(slot, net.minecraft.world.item.ItemStack.EMPTY);
                        } else {
                            le.setItemSlot(slot, stack.copy());
                        }
                    }
                }
            }

            // glowing
            e.setGlowingTag(def.visuals.glowing);
        }

        String nextDialogue(ServerPlayer player, NpcDefinition def) {
            if (def.dialogue == null || !def.dialogue.enabled) return null;
            var lines = def.dialogue.lines;
            if (lines == null || lines.isEmpty()) return null;

            int size = lines.size();
            String mode = def.dialogue.mode != null ? def.dialogue.mode : "SEQUENTIAL";

            if ("RANDOM".equalsIgnoreCase(mode)) {
                int idx = ThreadLocalRandom.current().nextInt(size);
                return lines.get(idx);
            } else {
                UUID id = player.getUUID();
                int idx = dialogueIndex.getOrDefault(id, 0);
                if (idx < 0 || idx >= size) idx = 0;
                String line = lines.get(idx);
                int next = (idx + 1) % size;
                dialogueIndex.put(id, next);
                return line;
            }
        }

        void tickPath(Entity e, NpcDefinition def) {
            var path = def.behavior.path;
            if (path == null || !path.enabled || path.waypoints == null || path.waypoints.isEmpty()) return;
            if (!(e instanceof LivingEntity)) return;

            if (pathIndex < 0 || pathIndex >= path.waypoints.size()) pathIndex = 0;
            var wp = path.waypoints.get(pathIndex);

            // Optional dimension safety
            String dim = e.level().dimension().location().toString();
            if (wp.dimension != null && !wp.dimension.isBlank() && !wp.dimension.equals(dim)) {
                return;
            }

            if (pathWaitTicks > 0) {
                pathWaitTicks--;
                return;
            }

            double speedPerTick = path.speedBlocksPerSecond / 20.0;
            if (speedPerTick <= 0) {
                // Teleport style
                e.moveTo(wp.x, wp.y, wp.z, wp.yaw, wp.pitch);
                pathIndex = (pathIndex + 1) % path.waypoints.size();
                pathWaitTicks = Math.max(0, wp.waitTicks);
                return;
            }

            double dx = wp.x - e.getX();
            double dy = wp.y - e.getY();
            double dz = wp.z - e.getZ();
            double dist2 = dx * dx + dy * dy + dz * dz;
            double step = speedPerTick;

            if (dist2 <= step * step) {
                e.moveTo(wp.x, wp.y, wp.z, wp.yaw, wp.pitch);
                pathIndex = (pathIndex + 1) % path.waypoints.size();
                pathWaitTicks = Math.max(0, wp.waitTicks);
            } else {
                double dist = Math.sqrt(dist2);
                double nx = e.getX() + dx / dist * step;
                double ny = e.getY() + dy / dist * step;
                double nz = e.getZ() + dz / dist * step;

                e.moveTo(nx, ny, nz, e.getYRot(), e.getXRot());
            }
        }
    }

    public boolean isNpcEntity(Entity e) {
        if (e == null) return false;
        UUID id = e.getUUID();
        for (Runtime rt : runtimes.values()) {
            if (id.equals(rt.entityId)) return true;
        }
        return false;
    }

    public NpcDefinition findNpcByEntity(Entity entity) {
        if (entity == null || common.npcs == null) return null;
        UUID uuid = entity.getUUID();

        for (Runtime rt : runtimes.values()) {
            if (rt.entityId != null && rt.entityId.equals(uuid)) {
                return common.npcs.get(rt.name);
            }
        }
        return null;
    }

    public boolean triggerInteraction(ServerPlayer player, Entity npcEntity,
                                      NpcDefinition def, String clickRaw) {
        if (def == null || def.interactions == null) return false;

        String click = clickRaw.toUpperCase(Locale.ROOT);
        List<String> combined = new ArrayList<>();

        var specific = def.interactions.get(click);
        if (specific != null) combined.addAll(specific);

        if (!"ANY".equals(click)) {
            var any = def.interactions.get("ANY");
            if (any != null) combined.addAll(any);
        }

        // --- shared placeholders (for dialogue + commands) ---
        String playerName = player.getName().getString();
        String npcName = (def.name != null ? def.name : "npc");
        String world = npcEntity != null
                ? npcEntity.level().dimension().location().toString()
                : player.serverLevel().dimension().location().toString();

        double x = (npcEntity != null ? npcEntity.getX() : player.getX());
        double y = (npcEntity != null ? npcEntity.getY() : player.getY());
        double z = (npcEntity != null ? npcEntity.getZ() : player.getZ());

        String sx = String.format(Locale.ROOT, "%.2f", x);
        String sy = String.format(Locale.ROOT, "%.2f", y);
        String sz = String.format(Locale.ROOT, "%.2f", z);

        // --- dialogue first ---
        Runtime rt = runtimes.get(def.name);
        if (rt != null) {
            String line = rt.nextDialogue(player, def);
            if (line != null && !line.isBlank()) {
                String dlg = line
                        .replace("{player}", playerName)
                        .replace("%player%", playerName)
                        .replace("{player_uuid}", player.getUUID().toString())
                        .replace("{npc}", npcName)
                        .replace("{world}", world)
                        .replace("{x}", sx)
                        .replace("{y}", sy)
                        .replace("{z}", sz)
                        .replace("{click}", click);

                Component msg = MessagesUtil.styled(dlg);
                player.sendSystemMessage(msg);
            }
        }

        // No commands bound, but dialogue may have run → still counts as “handled”
        if (combined.isEmpty()) return def.dialogue != null && def.dialogue.enabled;

        // --- command execution with same placeholders ---
        CommandSourceStack src = player.createCommandSourceStack();

        for (String raw : combined) {
            if (raw == null || raw.isBlank()) continue;

            String cmd = raw
                    .replace("{player}", playerName)
                    .replace("%player%", playerName)
                    .replace("{player_uuid}", player.getUUID().toString())
                    .replace("{npc}", npcName)
                    .replace("{world}", world)
                    .replace("{x}", sx)
                    .replace("{y}", sy)
                    .replace("{z}", sz)
                    .replace("{click}", click);

            player.server.getCommands().performPrefixedCommand(src, cmd);
        }

        return true;
    }
}
