package com.alphine.mysticessentials.teleport;

import com.alphine.mysticessentials.config.MEConfig;
import com.alphine.mysticessentials.util.MessagesUtil;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class WarmupManager {

    private static final class W {
        final UUID playerId;
        final Runnable task;
        final ResourceKey<Level> dim;
        final double x, y, z;
        int ticks; // remaining
        final boolean cancelOnMove;
        final boolean cancelOnDamage;

        W(UUID pid, Runnable task, ResourceKey<Level> dim,
          double x, double y, double z,
          int ticks, boolean cancelOnMove, boolean cancelOnDamage) {
            this.playerId = pid;
            this.task = task;
            this.dim = dim;
            this.x = x;
            this.y = y;
            this.z = z;
            this.ticks = ticks;
            this.cancelOnMove = cancelOnMove;
            this.cancelOnDamage = cancelOnDamage;
        }
    }

    private final Map<UUID, W> pending = new ConcurrentHashMap<>();

    /**
     * Starts a warmup countdown (NO bypass logic here).
     * If seconds <= 0, runs immediately.
     */
    public void start(MinecraftServer server, ServerPlayer p, int seconds, Runnable task) {
        if (seconds <= 0) {
            task.run();
            return;
        }

        // Replace any existing warmup for this player
        pending.remove(p.getUUID());

        var f = MEConfig.INSTANCE != null ? MEConfig.INSTANCE.features : null;
        boolean cancelOnMove   = (f == null) || f.cancelWarmupOnMove;
        boolean cancelOnDamage = (f == null) || f.cancelWarmupOnDamage;

        W w = new W(
                p.getUUID(),
                task,
                p.level().dimension(),
                p.getX(), p.getY(), p.getZ(),
                seconds * 20,
                cancelOnMove,
                cancelOnDamage
        );

        pending.put(p.getUUID(), w);
        p.displayClientMessage(MessagesUtil.msg("warmup.wait", Map.of("seconds", seconds)), false);
    }

    /**
     * Legacy compatibility: old call sites can keep using this.
     * Bypass decision uses the provided CommandSourceStack.
     */
    public void startOrBypass(MinecraftServer server, ServerPlayer p, CommandSourceStack src, int seconds, Runnable task) {
        if (seconds <= 0) {
            task.run();
            return;
        }
        // Use src-based bypass, not p.createCommandSourceStack()
        if (src != null && com.alphine.mysticessentials.perm.Bypass.warmup(src)) {
            task.run();
            return;
        }
        start(server, p, seconds, task);
    }

    public boolean isPending(UUID playerId) {
        return pending.containsKey(playerId);
    }

    /** Call from your damage hook to cancel if configured. */
    public void onDamaged(ServerPlayer p) {
        W w = pending.get(p.getUUID());
        if (w != null && w.cancelOnDamage) {
            cancel(p, MessagesUtil.msg("warmup.cancel.damage"));
        }
    }

    public void cancel(ServerPlayer p, Component msg) {
        if (pending.remove(p.getUUID()) != null && msg != null) {
            p.displayClientMessage(msg, false);
        }
    }

    public void cancel(ServerPlayer p, String rawMsg) {
        if (pending.remove(p.getUUID()) != null && rawMsg != null && !rawMsg.isBlank()) {
            p.displayClientMessage(Component.literal(rawMsg), false);
        }
    }

    public void cancel(UUID playerId) {
        pending.remove(playerId);
    }

    /** Tick once per server tick from Fabric/NeoForge events. */
    public void tick(MinecraftServer server) {
        if (pending.isEmpty()) return;

        Iterator<Map.Entry<UUID, W>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, W> e = it.next();
            UUID id = e.getKey();
            W w = e.getValue();

            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) {
                it.remove();
                continue;
            }

            // Cancel if dimension changed
            if (p.level().dimension() != w.dim) {
                it.remove();
                p.displayClientMessage(MessagesUtil.msg("warmup.cancel.move"), false);
                continue;
            }

            // Cancel if moved more than ~0.5 blocks (squared threshold 0.25)
            if (w.cancelOnMove && p.distanceToSqr(w.x, w.y, w.z) > 0.25D) {
                it.remove();
                p.displayClientMessage(MessagesUtil.msg("warmup.cancel.move"), false);
                continue;
            }

            // Countdown
            w.ticks--;
            if (w.ticks <= 0) {
                it.remove();
                w.task.run(); // already on main server thread
            }
        }
    }

    /** Optional: clear warmups on world change or disconnect */
    public void onWorldChange(ServerPlayer p) {
        if (pending.remove(p.getUUID()) != null) {
            p.displayClientMessage(MessagesUtil.msg("warmup.cancel.move"), false);
        }
    }

    public void onDisconnect(UUID playerId) {
        pending.remove(playerId);
    }
}
