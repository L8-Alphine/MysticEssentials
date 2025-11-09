package com.alphine.mysticessentials.teleport;

import com.alphine.mysticessentials.config.MEConfig;
import com.alphine.mysticessentials.perm.Bypass;
import com.alphine.mysticessentials.util.MessagesUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public class WarmupManager {

    private static final class W {
        final UUID playerId;
        final Runnable task;
        final ResourceKey<net.minecraft.world.level.Level> dim;
        final double x, y, z;
        int ticks;                       // remaining
        final boolean cancelOnMove;
        final boolean cancelOnDamage;

        W(UUID pid, Runnable t, ResourceKey<net.minecraft.world.level.Level> dim,
          double x, double y, double z, int ticks, boolean onMove, boolean onDamage) {
            this.playerId = pid; this.task=t; this.dim=dim;
            this.x=x; this.y=y; this.z=z; this.ticks=ticks;
            this.cancelOnMove=onMove; this.cancelOnDamage=onDamage;
        }
    }

    private final Map<UUID, W> pending = new HashMap<>();

    /**
     * Start a warmup if not bypassed. If bypassed or seconds <= 0, runs task immediately.
     */
    public void startOrBypass(MinecraftServer server, ServerPlayer p, int seconds, Runnable task) {
        if (seconds <= 0 || Bypass.warmup(p.createCommandSourceStack())) {
            task.run();
            return;
        }
        var f = MEConfig.INSTANCE != null ? MEConfig.INSTANCE.features : null;
        boolean cancelOnMove   = f == null || f.cancelWarmupOnMove;
        boolean cancelOnDamage = f == null || f.cancelWarmupOnDamage;

        var w = new W(p.getUUID(), task, p.level().dimension(),
                p.getX(), p.getY(), p.getZ(),
                seconds * 20, cancelOnMove, cancelOnDamage);

        pending.put(p.getUUID(), w);

        // “Teleporting in {seconds}s...”
        p.displayClientMessage(MessagesUtil.msg("warmup.wait", Map.of("seconds", seconds)), false);
    }

    /** Call from your damage hook to cancel if configured. */
    public void onDamaged(ServerPlayer p) {
        W w = pending.get(p.getUUID());
        if (w != null && w.cancelOnDamage) {
            cancel(p, MessagesUtil.msg("warmup.cancel.damage"));
        }
    }

    /** Cancel helper with raw message (kept for compatibility). */
    public void cancel(ServerPlayer p, String rawMsg) {
        if (pending.remove(p.getUUID()) != null && rawMsg != null && !rawMsg.isBlank()) {
            p.displayClientMessage(Component.literal(rawMsg), false);
        }
    }
    /** Preferred cancel with a localized message. */
    public void cancel(ServerPlayer p, Component msg) {
        if (pending.remove(p.getUUID()) != null && msg != null) {
            p.displayClientMessage(msg, false);
        }
    }

    /** Tick once per server tick from Fabric/NeoForge events. */
    public void tick(MinecraftServer server) {
        if (pending.isEmpty()) return;

        Iterator<Map.Entry<UUID, W>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            UUID id = e.getKey();
            W w = e.getValue();

            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) { it.remove(); continue; }

            // Cancel if dimension changed
            if (p.level().dimension() != w.dim) {
                cancel(p, MessagesUtil.msg("warmup.cancel.move"));
                it.remove();
                continue;
            }

            // Cancel if moved more than ~0.5 blocks (squared threshold 0.25)
            if (w.cancelOnMove && p.distanceToSqr(w.x, w.y, w.z) > 0.25) {
                cancel(p, MessagesUtil.msg("warmup.cancel.move"));
                it.remove();
                continue;
            }

            // Countdown
            if (--w.ticks <= 0) {
                it.remove();
                // Run on main thread immediately
                w.task.run();
            }
        }
    }

    /** Optional: clear warmups on world change or disconnect */
    public void onWorldChange(ServerPlayer p) {
        if (pending.remove(p.getUUID()) != null) {
            p.displayClientMessage(MessagesUtil.msg("warmup.cancel.move"), false);
        }
    }
    public void onDisconnect(UUID playerId) { pending.remove(playerId); }
}
