package com.alphine.mysticessentials.perm;

import com.alphine.mysticessentials.config.MEConfig;
import net.minecraft.commands.CommandSourceStack;

public final class Bypass {
    private Bypass() {}

    public static boolean cooldown(CommandSourceStack src) {
        var cfg = MEConfig.INSTANCE;
        String node = (cfg != null && cfg.permissions != null)
                ? cfg.permissions.cooldownBypass
                : "messentials.teleport.cooldown.bypass";

        return Perms.has(src, node, 0)
                || Perms.has(src, PermNodes.ADMIN, 2)
                || Perms.has(src, PermNodes.ALL, 2);
    }

    public static boolean warmup(CommandSourceStack src) {
        var cfg = MEConfig.INSTANCE;
        String node = (cfg != null && cfg.permissions != null)
                ? cfg.permissions.warmupBypass
                : "messentials.teleport.warmup.bypass";

        return Perms.has(src, node, 0)
                || Perms.has(src, PermNodes.ADMIN, 2)
                || Perms.has(src, PermNodes.ALL, 2);
    }

    public static boolean moderation(CommandSourceStack src) {
        var cfg = MEConfig.INSTANCE;
        String node = (cfg != null && cfg.permissions != null)
                ? cfg.permissions.modBypass
                : "messentials.mod.bypass";

        return Perms.has(src, node, 2)
                || Perms.has(src, PermNodes.ADMIN, 2)
                || Perms.has(src, PermNodes.ALL, 2);
    }
}
