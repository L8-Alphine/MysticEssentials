package com.alphine.mysticessentials.hologram;

import com.alphine.mysticessentials.MysticEssentialsCommon;
import com.alphine.mysticessentials.hologram.model.HologramDefinition;
import com.alphine.mysticessentials.mixin.accessor.BillboardConstraintsAccessor;
import com.alphine.mysticessentials.mixin.accessor.DisplayAccessors;
import com.alphine.mysticessentials.mixin.accessor.TextDisplayAccessors;
import com.alphine.mysticessentials.placeholders.PlaceholderContext;
import com.alphine.mysticessentials.placeholders.PlaceholderService;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

public final class HologramManager {

    private static final String TAG_ROOT = "mysticessentials:hologram";
    private static final String TAG_PREFIX = "mysticessentials:holo:"; // + name

    private final MysticEssentialsCommon common;

    private final Map<String, Runtime> runtimes = new HashMap<>();
    private final Set<String> dirty = new HashSet<>();
    /**
     * IMPORTANT: chunk index MUST include dimension, otherwise overworld/nether with same ChunkPos collide.
     */
    private final Map<ChunkKey, Set<String>> holosByChunk = new HashMap<>();

    public HologramManager(MysticEssentialsCommon common) {
        this.common = common;
    }

    private static ServerLevel level(MinecraftServer server, String dim) {
        ResourceLocation id = ResourceLocation.tryParse(dim);
        if (id == null) return null;
        ResourceKey<Level> key = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, id);
        return server.getLevel(key);
    }

    public void markDirty(String name) {
        if (name != null) dirty.add(name);
    }

    /**
     * Rebuild runtime cache + chunk index.
     * DO NOT spawn here. We adopt on chunk-load and spawn missing lines safely.
     */
    public void reloadAll(@Nullable MinecraftServer server) {
        runtimes.clear();
        dirty.clear();
        holosByChunk.clear();

        if (common.holograms == null) return;

        for (HologramDefinition def : common.holograms.list()) {
            if (def == null || def.name == null) continue;

            runtimes.put(def.name, new Runtime(def.name));
            dirty.add(def.name);

            holosByChunk.computeIfAbsent(ChunkKey.of(def), k -> new HashSet<>()).add(def.name);
        }

    }

    public void tick(MinecraftServer server) {
        if (common.holograms == null) return;

        for (HologramDefinition def : common.holograms.list()) {
            if (def == null || def.name == null) continue;
            runtimes.computeIfAbsent(def.name, Runtime::new);
        }

        if (!dirty.isEmpty()) {
            var it = dirty.iterator();
            while (it.hasNext()) {
                String name = it.next();
                it.remove();

                HologramDefinition def = common.holograms.get(name);
                Runtime rt = runtimes.get(name);
                if (rt == null) continue;

                if (def == null || !def.enabled) {
                    // NOTE: do NOT discard entities on disable, or you lose stable editing.
                    // Just stop tracking (optional). If you truly want remove-on-disable, call purge.
                    rt.stopTracking();
                    continue;
                }

                rt.ensureSpawned(server, def, common.placeholders);
                rt.applyDefinition(server, def, common.placeholders);
            }
        }

        // placeholder update loop
        for (Runtime rt : runtimes.values()) {
            HologramDefinition def = common.holograms.get(rt.name);
            if (def == null || !def.enabled) continue;
            if (!def.updates.placeholdersEnabled) continue;

            int interval = Math.max(0, def.updates.textUpdateIntervalTicks);
            if (interval <= 0) continue;

            if (++rt.placeholderTick >= interval) {
                rt.placeholderTick = 0;
                rt.applyLines(server, def, common.placeholders);
            }
        }
    }

    /**
     * Called by platform chunk-load events.
     * IMPORTANT: DO NOT PURGE HERE. We only adopt + spawn missing.
     */
    public void onChunkLoaded(MinecraftServer server, ServerLevel lvl, ChunkPos pos) {
        if (common.holograms == null) return;

        Set<String> names = holosByChunk.get(ChunkKey.of(lvl, pos));
        if (names == null || names.isEmpty()) return;

        for (String name : names) {
            HologramDefinition def = common.holograms.get(name);
            if (def == null || !def.enabled) continue;

            Runtime rt = runtimes.computeIfAbsent(name, Runtime::new);

            // adopt if needed
            if (rt.lineEntities.isEmpty()) {
                rt.recoverFromLoadedChunk(lvl, def);
            }

            // always re-apply/spawn on load
            dirty.add(name);
        }

        tick(server);
    }

    public void deleteNow(MinecraftServer server, String name) {
        if (name == null || name.isBlank()) return;

        // Try remove entities even if definition is already gone from store
        HologramDefinition def = (common.holograms != null) ? common.holograms.get(name) : null;

        // 1) Kill known runtime entities (fast path)
        Runtime rt = runtimes.remove(name);
        if (rt != null) {
            rt.despawn(server);
        }

        // 2) Best-effort: purge any tagged displays still in the world
        //    (covers crash recovery / missing runtime UUIDs)
        if (def != null && def.anchor != null && def.anchor.dimension != null) {
            ServerLevel lvl = level(server, def.anchor.dimension);
            if (lvl != null) {
                int cx = ((int) Math.floor(def.anchor.x)) >> 4;
                int cz = ((int) Math.floor(def.anchor.z)) >> 4;
                lvl.getChunk(cx, cz); // force-load so entities exist to delete

                double spacing = (def.style != null) ? Math.max(0.01, def.style.lineSpacing) : 0.25;
                int lines = (def.lines != null) ? def.lines.size() : 0;

                double radius = 6.0;
                double top = def.anchor.y + 6.0;
                double bottom = def.anchor.y - (lines * spacing) - 6.0;

                AABB box = new AABB(
                        def.anchor.x - radius, bottom, def.anchor.z - radius,
                        def.anchor.x + radius, top, def.anchor.z + radius
                );

                String tag = TAG_PREFIX + name;

                for (Display.TextDisplay td : lvl.getEntitiesOfClass(
                        Display.TextDisplay.class,
                        box,
                        e -> e.getTags().contains(TAG_ROOT) && e.getTags().contains(tag)
                )) {
                    td.discard();
                }
            }

            // 3) Remove from chunk index
            holosByChunk.computeIfPresent(ChunkKey.of(def), (k, set) -> {
                set.remove(name);
                return set.isEmpty() ? null : set;
            });
        } else {
            System.out.println("[HologramManager] Warning: could not fully delete hologram '" + name + "'; definition missing.");
        }

        dirty.remove(name);
    }

    // Call this after reloadAll(server) if you want holograms to instantly appear
    // even if their chunks are already loaded.
    public void scanLoadedChunksAndRecover(MinecraftServer server) {
        if (common.holograms == null) return;

        for (ServerLevel lvl : server.getAllLevels()) {
            String dim = lvl.dimension().location().toString();

            // Iterate only holograms we know about (cheap) and check if their chunk is loaded
            for (HologramDefinition def : common.holograms.list()) {
                if (def == null || def.name == null || def.anchor == null) continue;
                if (!def.enabled) continue;
                if (def.anchor.dimension == null || !def.anchor.dimension.equals(dim)) continue;

                ChunkPos pos = new ChunkPos(
                        ((int) Math.floor(def.anchor.x)) >> 4,
                        ((int) Math.floor(def.anchor.z)) >> 4
                );

                // Only trigger if chunk is currently loaded (avoid force-loading)
                if (!lvl.hasChunk(pos.x, pos.z)) continue;

                // Drive same path as real chunk-load events
                onChunkLoaded(server, lvl, pos);
            }
        }
    }

    /**
     * Refresh ONE hologram immediately:
     * - ensure anchor chunk is loaded
     * - adopt saved entities (near anchor / whole chunk fallback)
     * - spawn missing lines
     * - apply definition
     */
    public void refreshNow(MinecraftServer server, String name) {
        if (name == null || common.holograms == null) return;

        HologramDefinition def = common.holograms.get(name);
        if (def == null || !def.enabled) return;

        ServerLevel lvl = level(server, def.anchor.dimension);
        if (lvl == null) return;

        // Force-load the correct chunk (safe; you asked for immediate refresh)
        int cx = ((int) Math.floor(def.anchor.x)) >> 4;
        int cz = ((int) Math.floor(def.anchor.z)) >> 4;
        lvl.getChunk(cx, cz);

        Runtime rt = runtimes.computeIfAbsent(def.name, Runtime::new);

        // Re-adopt if the runtime is empty (or you can always re-adopt if you want)
        rt.recoverFromLoadedChunk(lvl, def);

        // Spawn/apply immediately
        rt.ensureSpawned(server, def, common.placeholders);
        rt.applyDefinition(server, def, common.placeholders);
    }

    /**
     * Shutdown: stop ticking/tracking, but DO NOT discard entities.
     * Discarding is what breaks UUID-based editing persistence.
     */
    public void shutdown(MinecraftServer server) {
        runtimes.clear();
        dirty.clear();
        holosByChunk.clear();
    }

    private static final class Runtime {
        final String name;
        final List<UUID> lineEntities = new ArrayList<>();
        int placeholderTick = 0;

        Runtime(String name) {
            this.name = name;
        }

        private static void applyOne(MinecraftServer server, Display.TextDisplay td,
                                     HologramDefinition def, int i,
                                     PlaceholderService svc) {
            var data = td.getEntityData();

            byte billboardId =
                    ((BillboardConstraintsAccessor) (Object) parseBillboard(def.transform.billboard))
                            .me$id();
            data.set(DisplayAccessors.me$billboard(), billboardId);

            data.set(DisplayAccessors.me$translation(), new Vector3f(0f, 0f, 0f));
            data.set(DisplayAccessors.me$leftRot(), new Quaternionf());
            data.set(DisplayAccessors.me$rightRot(), new Quaternionf());

            float s = def.transform.scale;
            data.set(DisplayAccessors.me$scale(), new Vector3f(s, s, s));

            String raw = (i < def.lines.size()) ? def.lines.get(i) : "";

            // --- PLACEHOLDERS ---
            String parsed = raw;
            try {
                if (svc != null && def.updates != null) {
                    PlaceholderContext ctx = PlaceholderContext.of(server, null);
                    parsed = svc.applyAll(
                            raw,
                            ctx,
                            def.updates.enablePercentPlaceholders,
                            def.updates.enableBracePlaceholders
                    );
                }
            } catch (Throwable t) {
                System.out.println("[HologramManager] Failed to apply placeholders for hologram '"
                        + def.name + "' line " + i + ": " + t.getMessage());
                parsed = raw;
            }

            data.set(TextDisplayAccessors.me$text(), Component.literal(ampToSection(parsed)));
            data.set(TextDisplayAccessors.me$lineWidth(), 32767);

            int op = Math.max(0, Math.min(255, def.style.textOpacity));
            data.set(TextDisplayAccessors.me$textOpacity(), (byte) op);

            data.set(TextDisplayAccessors.me$background(), parseBackground(def.style.background));

            byte flags = 0;
            if (def.style.shadow) flags |= 1;
            if (def.visibility.seeThroughBlocks) flags |= 2;
            if (def.style.defaultBackground) flags |= 4;
            data.set(TextDisplayAccessors.me$flags(), flags);
        }

        private static Display.BillboardConstraints parseBillboard(String mode) {
            if (mode == null) return Display.BillboardConstraints.CENTER;
            return switch (mode.toUpperCase(Locale.ROOT)) {
                case "FIXED" -> Display.BillboardConstraints.FIXED;
                case "VERTICAL" -> Display.BillboardConstraints.VERTICAL;
                case "HORIZONTAL" -> Display.BillboardConstraints.HORIZONTAL;
                default -> Display.BillboardConstraints.CENTER;
            };
        }

        private static int parseBackground(String bg) {
            if (bg == null) return 0x40000000;
            if (bg.equalsIgnoreCase("transparent")) return 0x00000000;
            if (bg.startsWith("#") && bg.length() == 7) {
                int rgb = Integer.parseInt(bg.substring(1), 16);
                return (0xFF << 24) | rgb;
            }
            return 0x40000000;
        }

        private static Entity findEntity(MinecraftServer server, UUID id) {
            for (ServerLevel lvl : server.getAllLevels()) {
                Entity e = lvl.getEntity(id);
                if (e != null) return e;
            }
            return null;
        }

        private static String ampToSection(String s) {
            return s == null ? "" : s.replace('&', '§');
        }

        void stopTracking() {
            lineEntities.clear();
        }

        /**
         * Recover/adopt saved TextDisplays for this hologram from a nearby AABB.
         * This is more robust than scanning the whole chunk.
         */
        void recoverNearAnchor(ServerLevel lvl, HologramDefinition def) {
            if (!lvl.dimension().location().toString().equals(def.anchor.dimension)) return;

            double spacing = Math.max(0.01, def.style.lineSpacing);
            double radius = 6.0;
            double top = def.anchor.y + 6.0;
            double bottom = def.anchor.y - (def.lines.size() * spacing) - 6.0;

            AABB box = new AABB(
                    def.anchor.x - radius, bottom, def.anchor.z - radius,
                    def.anchor.x + radius, top, def.anchor.z + radius
            );

            String tag = TAG_PREFIX + def.name;

            List<Display.TextDisplay> found = lvl.getEntitiesOfClass(
                    Display.TextDisplay.class,
                    box,
                    td -> td.getTags().contains(TAG_ROOT) && td.getTags().contains(tag)
            );

            if (found.isEmpty()) return;

            // stable ordering: top -> bottom
            found.sort((a, b) -> Double.compare(b.getY(), a.getY()));

            lineEntities.clear();
            for (Display.TextDisplay td : found) {
                lineEntities.add(td.getUUID());
            }

            // trim extras if config is shorter now
            while (lineEntities.size() > def.lines.size()) {
                UUID extra = lineEntities.remove(lineEntities.size() - 1);
                Entity e = lvl.getEntity(extra);
                if (e != null) e.discard();
            }
        }

        void recoverWholeChunk(ServerLevel lvl, HologramDefinition def) {
            // must match dimension
            if (def.anchor == null || def.anchor.dimension == null) return;
            if (!lvl.dimension().location().toString().equals(def.anchor.dimension)) return;

            // anchor chunk
            ChunkPos cpos = new ChunkPos(
                    ((int) Math.floor(def.anchor.x)) >> 4,
                    ((int) Math.floor(def.anchor.z)) >> 4
            );

            // full chunk bounds (inclusive-ish; +1 on max edges)
            AABB box = new AABB(
                    cpos.getMinBlockX(), lvl.getMinBuildHeight(), cpos.getMinBlockZ(),
                    cpos.getMaxBlockX() + 1, lvl.getMaxBuildHeight(), cpos.getMaxBlockZ() + 1
            );

            String tag = TAG_PREFIX + def.name;

            List<Display.TextDisplay> found = lvl.getEntitiesOfClass(
                    Display.TextDisplay.class,
                    box,
                    td -> td.getTags().contains(TAG_ROOT) && td.getTags().contains(tag)
            );

            if (found.isEmpty()) return;

            // stable ordering: top -> bottom (higher Y first)
            found.sort((a, b) -> Double.compare(b.getY(), a.getY()));

            // adopt
            lineEntities.clear();
            for (Display.TextDisplay td : found) {
                lineEntities.add(td.getUUID());
            }

            // if there are more saved entities than config lines, delete extras
            while (lineEntities.size() > def.lines.size()) {
                UUID extra = lineEntities.remove(lineEntities.size() - 1);
                Entity e = lvl.getEntity(extra);
                if (e != null) e.discard();
            }
        }

        void recoverFromLoadedChunk(ServerLevel lvl, HologramDefinition def) {
            int before = lineEntities.size();

            // best behavior: recover tight around anchor
            recoverNearAnchor(lvl, def);

            // fallback: if nothing was adopted, scan the chunk
            if (lineEntities.size() == before) {
                recoverWholeChunk(lvl, def);
            }
        }

        void ensureSpawned(MinecraftServer server, HologramDefinition def, PlaceholderService svc) {
            int want = def.lines.size();

            // try adopt first if empty
            if (lineEntities.isEmpty()) {
                ServerLevel lvl = level(server, def.anchor.dimension);
                if (lvl != null) {
                    // force-load the hologram’s actual chunk
                    int cx = ((int) Math.floor(def.anchor.x)) >> 4;
                    int cz = ((int) Math.floor(def.anchor.z)) >> 4;
                    lvl.getChunk(cx, cz);

                    recoverNearAnchor(lvl, def);
                }
            }

            int have = lineEntities.size();

            // Remove extras (config got smaller)
            while (have > want) {
                UUID id = lineEntities.remove(have - 1);
                Entity e = findEntity(server, id);
                if (e != null) e.discard();
                have--;
            }

            // Spawn missing
            while (have < want) {
                ServerLevel lvl = level(server, def.anchor.dimension);
                if (lvl == null) return; // dimension not ready yet

                int i = have;
                double x = def.anchor.x;
                double y = def.anchor.y - (i * def.style.lineSpacing);
                double z = def.anchor.z;

                // FIX: load correct chunk (your old code loaded chunk(0,0))
                int cx = ((int) Math.floor(x)) >> 4;
                int cz = ((int) Math.floor(z)) >> 4;
                lvl.getChunk(cx, cz);

                Display.TextDisplay td = EntityType.TEXT_DISPLAY.create(lvl);
                if (td == null) return;

                td.addTag(TAG_ROOT);
                td.addTag(TAG_PREFIX + def.name);

                td.moveTo(x, y, z, def.transform.yaw, def.transform.pitch);

                // initial data BEFORE spawn
                applyOne(server, td, def, i, svc);

                lvl.addFreshEntity(td);
                lineEntities.add(td.getUUID());
                have++;
            }
        }

        void applyDefinition(MinecraftServer server, HologramDefinition def, PlaceholderService svc) {
            applyLines(server, def, svc);
        }

        void applyLines(MinecraftServer server, HologramDefinition def, PlaceholderService svc) {
            ServerLevel lvl = level(server, def.anchor.dimension);
            if (lvl == null) return;

            double baseX = def.anchor.x;
            double baseY = def.anchor.y;
            double baseZ = def.anchor.z;

            for (int i = 0; i < lineEntities.size(); i++) {
                Entity e = findEntity(server, lineEntities.get(i));
                if (!(e instanceof Display.TextDisplay td)) continue;

                double y = baseY - (i * def.style.lineSpacing);
                td.moveTo(baseX, y, baseZ, def.transform.yaw, def.transform.pitch);

                applyOne(server, td, def, i, svc);

                td.hurtMarked = true;
            }
        }

        public void despawn(MinecraftServer server) {
            for (UUID id : lineEntities) {
                Entity e = findEntity(server, id);
                if (e != null) e.discard();
            }
            lineEntities.clear();
        }
    }

    private record ChunkKey(String dim, int x, int z) {
        static ChunkKey of(HologramDefinition def) {
            int cx = ((int) Math.floor(def.anchor.x)) >> 4;
            int cz = ((int) Math.floor(def.anchor.z)) >> 4;
            return new ChunkKey(def.anchor.dimension, cx, cz);
        }

        static ChunkKey of(ServerLevel lvl, ChunkPos pos) {
            return new ChunkKey(lvl.dimension().location().toString(), pos.x, pos.z);
        }
    }
}
