package com.alphine.mysticessentials.teleport;

import com.alphine.mysticessentials.config.MEConfig;
import com.alphine.mysticessentials.perm.Bypass;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Lightweight warmup tracker. Re-schedules itself each tick via server.execute().
 * Honors config flags (cancel on move/damage) and warmup bypass.
 */
public class WarmupManager {

    private static final class W {
        final Runnable task;
        final double x, y, z;
        int ticks;
        boolean cancelOnMove;
        boolean cancelOnDamage;
        W(Runnable t, double x, double y, double z, int ticks, boolean onMove, boolean onDamage) {
            this.task=t; this.x=x; this.y=y; this.z=z; this.ticks=ticks;
            this.cancelOnMove=onMove; this.cancelOnDamage=onDamage;
        }
    }

    private final Map<UUID, W> pending = new HashMap<>();

    /**
     * Start a warmup if not bypassed. If bypassed, runs task immediately.
     * Reads default behavior from MEConfig.INSTANCE.features.
     *
     * @param seconds warmup seconds (0 to run immediately)
     * @param task    the action to run after warmup
     */
    public void startOrBypass(MinecraftServer server, ServerPlayer p, int seconds, Runnable task) {
        if (seconds <= 0 || Bypass.warmup(p.createCommandSourceStack())) {
            task.run();
            return;
        }
        var f = MEConfig.INSTANCE != null ? MEConfig.INSTANCE.features : null;
        boolean cancelOnMove   = f == null || f.cancelWarmupOnMove;
        boolean cancelOnDamage = f == null || f.cancelWarmupOnDamage;

        W w = new W(task, p.getX(), p.getY(), p.getZ(), seconds * 20, cancelOnMove, cancelOnDamage);
        pending.put(p.getUUID(), w);
        p.displayClientMessage(Component.literal("§7Teleporting in §e"+seconds+"§7s..."), false);
        schedule(server, p);
    }

    public void cancel(ServerPlayer p, String msg) {
        if (pending.remove(p.getUUID()) != null && msg != null && !msg.isBlank()) {
            p.displayClientMessage(Component.literal(msg), false);
        }
    }

    /** Call from your damage event (NeoForge/Fabric) to cancel if configured. */
    public void onDamaged(ServerPlayer p) {
        W w = pending.get(p.getUUID());
        if (w != null && w.cancelOnDamage) {
            cancel(p, "§cTeleport cancelled: you took damage.");
        }
    }

    private void schedule(MinecraftServer server, ServerPlayer p) {
        server.execute(() -> {
            W w = pending.get(p.getUUID());
            if (w == null) return;

            if (w.cancelOnMove && p.distanceToSqr(w.x, w.y, w.z) > 0.01) {
                cancel(p, "§cTeleport cancelled: you moved.");
                return;
            }

            if (--w.ticks <= 0) {
                pending.remove(p.getUUID());
                w.task.run();
                return;
            }

            // re-queue next tick
            schedule(server, p);
        });
    }
}
