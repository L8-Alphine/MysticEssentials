package com.alphine.mysticessentials.util;

import com.alphine.mysticessentials.config.MEConfig;
import com.alphine.mysticessentials.perm.Perms;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.storage.PlayerDataStore;
import com.alphine.mysticessentials.storage.PlayerDataStore.LastLoc;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Common AFK service:
 * - Call markActive*() from movement/chat/interaction/etc. events
 * - Call tick(server) once per second from your adapter
 * - Use toggleAfk(player, message) for /afk command
 * - Call reloadPools() if config changes
 */
public final class AfkService {

    // -------- runtime state (not persisted) --------
    private static final class State {
        boolean afk = false;
        String poolName = null;
        long lastActiveMs = System.currentTimeMillis();
        long afkSinceMs = 0L; // when we actually went AFK
        final Map<String, Long> nextRewardAtEpochSec = new HashMap<>();
    }

    private final Map<UUID, State> state = new ConcurrentHashMap<>();

    private MEConfig cfg; // live reference to INSTANCE
    private final PlayerDataStore pdata;

    private enum ActivityType { MOVE, INTERACT, CHAT }

    public AfkService(MEConfig cfg, PlayerDataStore pdata) {
        this.cfg = Objects.requireNonNull(cfg, "config");
        this.pdata = Objects.requireNonNull(pdata, "player data");
    }

    // ---------------- public API ----------------

    /** Re-read pools/settings after config save/reload. */
    public void reloadPools() {
        this.cfg = MEConfig.INSTANCE != null ? MEConfig.INSTANCE : this.cfg;
        // nothing else needed; we always read from cfg.afk at runtime
    }

    /** Mark activity from movement/interaction/chat */
    public void markActiveMovement(ServerPlayer p)    { markActive(p, ActivityType.MOVE); }
    public void markActiveInteraction(ServerPlayer p) { markActive(p, ActivityType.INTERACT); }
    /** For chat; pool.allowChatInside can permit staying AFK while chatting. */
    public void markActiveChat(ServerPlayer p)        { markActive(p, ActivityType.CHAT); }

    /** For join: reset lastActive; do not force AFK. */
    public void onJoin(ServerPlayer p) {
        State s = state.computeIfAbsent(p.getUUID(), k -> new State());
        s.lastActiveMs = System.currentTimeMillis();
    }

    /** For quit: drop ephemeral state (safe). */
    public void onQuit(UUID id) {
        state.remove(id);
    }

    /**
     * Command toggle: if not AFK -> enter AFK (optionally set custom message)
     * If AFK -> exit AFK and return to saved location (or fallback)
     * @return true if now AFK, false if un-AFK
     */
    public boolean toggleAfk(ServerPlayer p, String message) {
        State s = state.computeIfAbsent(p.getUUID(), k -> new State());
        if (s.afk) {
            exitAfk(p, s, false);
            return false;
        }
        if (message != null && !message.isBlank() && Perms.has(p, PermNodes.AFK_MESSAGE_SET, 0)) {
            pdata.setAfkMessage(p.getUUID(), message);
        }
        enterAfk(p, s, true);
        return true;
    }

    /**
     * Call once per second from platform adapter (Paper scheduler / Fabric/NeoForge tick).
     * Handles auto-AFK, reward clocks, region leave, and idle kick.
     */
    public void tick(MinecraftServer server) {
        final long nowMs = System.currentTimeMillis();
        final long nowSec = nowMs / 1000L;

        // snapshot to avoid CME if players join/quit in loop
        List<ServerPlayer> players = new ArrayList<>(server.getPlayerList().getPlayers());
        for (ServerPlayer p : players) {
            State s = state.computeIfAbsent(p.getUUID(), k -> new State());

            // ---- Auto-AFK ----
            if (!s.afk && cfg.afk.enabled) {
                if (cfg.afk.respectExemptPermission && Perms.has(p, PermNodes.AFK_EXEMPT, 2)) {
                    // exempt from auto
                } else if (cfg.afk.respectAutoEnablePermission && !Perms.has(p, PermNodes.AFK_AUTO_ENABLE, 0)) {
                    // requires permission to auto AFK - skip
                } else {
                    if (nowMs - s.lastActiveMs >= (long) cfg.afk.autoAfkSeconds * 1000L) {
                        enterAfk(p, s, true);
                    }
                }
            }

            if (!s.afk) continue;

            // ---- Pool region tracking & rewards ----
            MEConfig.AfkPool pool = poolOf(s.poolName);
            if (pool != null) {
                if (!isInsidePool(p, pool)) {
                    // left area → un-AFK and return
                    exitAfk(p, s, true);
                    continue;
                }
                // rewards due?
                if (pool.rewards != null) {
                    for (MEConfig.AfkReward tr : pool.rewards) {
                        if (tr == null || tr.id == null || tr.id.isBlank() || tr.everySeconds <= 0) continue;
                        long due = s.nextRewardAtEpochSec.getOrDefault(tr.id, Long.MAX_VALUE);
                        if (nowSec >= due) {
                            giveRewards(server, p, tr);
                            s.nextRewardAtEpochSec.put(tr.id, nowSec + tr.everySeconds);
                        }
                    }
                }
            }

            // ---- AFK kick ----
            if (cfg.afk.idleKickSeconds > 0 && !Perms.has(p, PermNodes.AFK_KICK_BYPASS, 2)) {
                long afkFor = (s.afkSinceMs > 0 ? nowMs - s.afkSinceMs : 0);
                if (afkFor / 1000L >= cfg.afk.idleKickSeconds) {
                    // kick
                    p.connection.disconnect(net.minecraft.network.chat.Component.literal(cfg.afk.idleKickReason));
                }
            }
        }
    }

    // ---------------- internals ----------------

    private void markActive(ServerPlayer p, ActivityType type) {
        State s = state.computeIfAbsent(p.getUUID(), k -> new State());
        final long now = System.currentTimeMillis();

        if (!s.afk) {
            s.lastActiveMs = now;
            return;
        }

        // If player is AFK:
        boolean poolsAvailable = hasActivePoolsFor(p);
        if (!poolsAvailable) {
            // No active/eligible pools -> any activity cancels AFK.
            exitAfk(p, s, false);
            return;
        }

        MEConfig.AfkPool pool = poolOf(s.poolName);
        boolean insideAssigned = pool != null && isInsidePool(p, pool);

        if (!insideAssigned) {
            // Active pools exist but player is not inside their assigned pool -> activity cancels AFK and returns them.
            exitAfk(p, s, false);
            return;
        }

        // Inside the assigned pool: respect per-activity allowances
        boolean allow;
        switch (type) {
            case MOVE ->     allow = pool.allowMoveInside;
            case INTERACT -> allow = pool.allowInteractInside;
            case CHAT ->     allow = pool.allowChatInside;
            default ->       allow = false;
        }

        if (allow) {
            // Keep AFK, but bump lastActive to keep idle-kick math sane.
            s.lastActiveMs = now;
        } else {
            // Not allowed activity while AFK in pool -> cancel AFK
            exitAfk(p, s, false);
        }
    }

    private void enterAfk(ServerPlayer p, State s, boolean teleportToPool) {
        if (s.afk) return;
        s.afk = true;
        s.afkSinceMs = System.currentTimeMillis();
        s.poolName = null; // reset

        boolean poolsAvailable = teleportToPool && hasActivePoolsFor(p);

        if (poolsAvailable) {
            // save return location ONLY when a pool is available
            LastLoc loc = new LastLoc();
            loc.dim = dimKey(p).location().toString();
            Vec3 pos = p.position();
            loc.x = pos.x; loc.y = pos.y; loc.z = pos.z;
            loc.yaw = p.getYRot(); loc.pitch = p.getXRot();
            loc.when = System.currentTimeMillis();
            pdata.setAfkReturnLoc(p.getUUID(), loc);

            // pick first eligible pool and teleport
            for (Map.Entry<String, MEConfig.AfkPool> e : cfg.afk.pools.entrySet()) {
                String name = e.getKey();
                MEConfig.AfkPool pool = e.getValue();
                if (pool == null || !pool.enabled) continue;
                if (pool.requirePermission && !Perms.has(p, PermNodes.afkPoolNode(name), 0)) continue;
                if (!teleport(p, pool.teleport)) break; // fail → stay AFK without pool/teleport
                s.poolName = name;
                s.nextRewardAtEpochSec.clear();
                long nowSec = System.currentTimeMillis() / 1000L;
                if (pool.rewards != null) {
                    for (MEConfig.AfkReward tr : pool.rewards) {
                        if (tr == null || tr.id == null || tr.id.isBlank() || tr.everySeconds <= 0) continue;
                        s.nextRewardAtEpochSec.put(tr.id, nowSec + tr.everySeconds);
                    }
                }
                break;
            }
        } else {
            // no eligible pools: do not save location and do not teleport
            pdata.clearAfkReturnLoc(p.getUUID());
        }

        // messages: private + broadcast
        var args = Map.<String,Object>of("name", p.getGameProfile().getName());
        msgSelf(p, "afk.enter.self", args);          // "You're now AFK."
        broadcast(p, "afk.enter.broadcast", args);   // "<name> is now AFK."
    }

    private void exitAfk(ServerPlayer p, State s, boolean leftArea) {
        if (!s.afk) return;
        s.afk = false;
        s.nextRewardAtEpochSec.clear();
        s.afkSinceMs = 0L;

        // try to return if we saved a location (i.e., we had a pool)
        teleportBackOrFallback(p);

        // clear session message and saved loc (if any)
        pdata.clearAfkMessage(p.getUUID());
        pdata.clearAfkReturnLoc(p.getUUID());

        s.poolName = null;

        var args = Map.<String,Object>of("name", p.getGameProfile().getName());
        msgSelf(p, "afk.exit.self", args);            // "You're no longer AFK."
        broadcast(p, "afk.exit.broadcast", args);     // "<name> is no longer AFK."
    }


    private void teleportBackOrFallback(ServerPlayer p) {
        Optional<LastLoc> back = pdata.getAfkReturnLoc(p.getUUID());
        if (back.isPresent()) {
            LastLoc l = back.get();
            TeleportPoint tp = new TeleportPoint(l.dim, l.x, l.y, l.z, l.yaw, l.pitch);
            if (teleport(p, tp)) return;
        }
        // fallback
        teleport(p, cfg.afk.fallback);
    }

    private boolean teleport(ServerPlayer p, MEConfig.TeleportPoint t) {
        try {
            ResourceLocation dimRl = ResourceLocation.tryParse(t.world);
            if (dimRl == null) return false; // << guard
            ResourceKey<Level> key = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dimRl);
            ServerLevel level = p.server.getLevel(key);
            if (level == null) return false;
            p.teleportTo(level, t.x, t.y, t.z, t.yaw, t.pitch);
            return true;
        } catch (Exception ignored) { return false; }
    }

    private boolean isInsidePool(ServerPlayer p, MEConfig.AfkPool pool) {
        if (pool == null || pool.region == null || pool.region.world == null) return false;
        String curWorld = dimKey(p).location().toString();
        if (!pool.region.world.equalsIgnoreCase(curWorld)) return false;
        Vec3 pos = p.position();
        int x = (int)Math.floor(pos.x);
        int y = (int)Math.floor(pos.y);
        int z = (int)Math.floor(pos.z);
        int minX = Math.min(pool.region.min.x, pool.region.max.x);
        int minY = Math.min(pool.region.min.y, pool.region.max.y);
        int minZ = Math.min(pool.region.min.z, pool.region.max.z);
        int maxX = Math.max(pool.region.min.x, pool.region.max.x);
        int maxY = Math.max(pool.region.min.y, pool.region.max.y);
        int maxZ = Math.max(pool.region.min.z, pool.region.max.z);
        return x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    private MEConfig.AfkPool poolOf(String name) {
        if (name == null) return null;
        return cfg.afk.pools.get(name);
    }

    private static ResourceKey<Level> dimKey(ServerPlayer p) {
        return p.level().dimension();
    }

    private void giveRewards(MinecraftServer server, ServerPlayer p, MEConfig.AfkReward tr) {
        // Commands
        if (tr.commands != null) {
            for (String raw : tr.commands) {
                if (raw == null || raw.isBlank()) continue;
                String cmd = raw.replace("%player%", p.getGameProfile().getName());
                server.getCommands().performPrefixedCommand(
                        server.createCommandSourceStack().withPermission(4),
                        cmd
                );
            }
        }
        // Items
        if (tr.items != null) {
            for (MEConfig.ItemSpec it : tr.items) {
                if (it == null || it.type == null || it.type.isBlank() || it.amount <= 0) continue;
                ItemStack stack = makeItem(it);
                if (!stack.isEmpty()) {
                    boolean added = p.getInventory().add(stack);
                    if (!added) {
                        // Drop at feet if inventory full
                        p.drop(stack, false);
                    }
                }
            }
        }
    }

    private void msgSelf(ServerPlayer p, String key, Map<String, Object> args) {
        p.sendSystemMessage(MessagesUtil.msg(key, args));
    }

    private void broadcast(ServerPlayer p, String key, Map<String, Object> args) {
        p.server.getPlayerList().broadcastSystemMessage(MessagesUtil.msg(key, args), false);
    }

    private ItemStack makeItem(MEConfig.ItemSpec it) {
        try {
            ResourceLocation rl = ResourceLocation.tryParse(it.type); // << was: new ResourceLocation(...)
            Optional<Item> oi = BuiltInRegistries.ITEM.getOptional(rl);
            if (oi.isEmpty()) return ItemStack.EMPTY;

            ItemStack st = new ItemStack(oi.get(), Math.max(1, it.amount));

            // Data Components (1.21+): attach arbitrary NBT as CUSTOM_DATA
            String nbt = (it.nbt == null || it.nbt.isBlank()) ? "{}" : it.nbt;
            try {
                st.set(DataComponents.CUSTOM_DATA, CustomData.of(TagParser.parseTag(nbt)));
            } catch (Exception ignored) {
                // ignore bad NBT, just give the base item
            }
            return st;
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    private boolean hasActivePoolsFor(ServerPlayer p) {
        if (cfg == null || cfg.afk == null || cfg.afk.pools == null || cfg.afk.pools.isEmpty()) return false;
        for (var e : cfg.afk.pools.entrySet()) {
            String name = e.getKey();
            MEConfig.AfkPool pool = e.getValue();
            if (pool == null || !pool.enabled) continue;
            if (pool.requirePermission && !Perms.has(p, PermNodes.afkPoolNode(name), 0)) continue;
            return true; // found at least one usable pool
        }
        return false;
    }

    // ---------------- convenience types ----------------

    /** Local TeleportPoint mirror to avoid importing other utils here. */
    private static final class TeleportPoint extends MEConfig.TeleportPoint {
        TeleportPoint(String world, double x, double y, double z, float yaw, float pitch) {
            super(world, x, y, z, yaw, pitch);
        }
    }


    // global helpers
    public boolean isAfk(UUID id) {
        State s = state.get(id);
        return s != null && s.afk;
    }

    /** The player’s current AFK message if set (empty means use default). */
    public Optional<String> currentAfkMessage(UUID id) {
        return pdata.getAfkMessage(id).filter(msg -> !msg.isBlank());
    }

    /** Formats the private notify string with config’s notifyFormat. */
    public String formatNotify(String senderName, String targetName, String message) {
        String fmt = cfg.afk.notifyFormat == null || cfg.afk.notifyFormat.isBlank()
                ? "<gray><sender> → <target>: <msg>" : cfg.afk.notifyFormat;
        return fmt.replace("<sender>", senderName)
                .replace("<target>", targetName)
                .replace("<msg>", message);
    }
}
