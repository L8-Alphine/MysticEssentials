package com.alphine.mysticessentials.teleport;

import com.alphine.mysticessentials.config.MEConfig;
import com.alphine.mysticessentials.perm.Bypass;
import com.alphine.mysticessentials.util.MessagesUtil;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.function.BooleanSupplier;

public final class TeleportExecutor {

    private final CooldownManager cooldowns;
    private final WarmupManager warmups;

    public TeleportExecutor(CooldownManager cooldowns, WarmupManager warmups) {
        this.cooldowns = cooldowns;
        this.warmups = warmups;
    }

    public void runTeleport(
            MinecraftServer server,
            ServerPlayer player,
            CommandSourceStack src,
            String key,
            BooleanSupplier teleportAction
    ) {
        if (server == null || player == null || src == null || key == null || teleportAction == null) return;

        long now = System.currentTimeMillis();

        // ----------------------------
        // Cooldown check (no stamping)
        // ----------------------------
        if (!Bypass.cooldown(src) && cooldowns.hasDefault(key)) {
            long remaining = cooldowns.remainingSeconds(player.getUUID(), key, now);
            if (remaining > 0) {
                player.displayClientMessage(
                        MessagesUtil.msg("cooldown.wait", Map.of("seconds", remaining)),
                        false
                );
                return;
            }
        }

        int warmupSeconds = (MEConfig.INSTANCE != null) ? MEConfig.INSTANCE.getWarmup(key) : 0;

        Runnable executeTeleport = () -> {
            boolean success;
            try {
                success = teleportAction.getAsBoolean();
            } catch (Throwable t) {
                // If teleport throws, don't stamp cooldown
                return;
            }
            if (!success) return;

            // ----------------------------
            // Stamp cooldown AFTER success
            // ----------------------------
            if (!Bypass.cooldown(src) && cooldowns.hasDefault(key)) {
                cooldowns.stampDefault(player.getUUID(), key, System.currentTimeMillis());
            }
        };

        // ----------------------------
        // Warmup (WarmupManager handles bypass via src)
        // ----------------------------
        warmups.startOrBypass(server, player, src, warmupSeconds, executeTeleport);
    }
}
