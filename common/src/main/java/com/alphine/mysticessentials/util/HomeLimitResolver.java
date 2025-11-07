package com.alphine.mysticessentials.util;

import com.alphine.mysticessentials.config.MEConfig;
import com.alphine.mysticessentials.perm.PermNodes;
import net.minecraft.server.level.ServerPlayer;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;

import java.util.Map;

public final class HomeLimitResolver {
    private HomeLimitResolver() {}

    public static int resolve(ServerPlayer p) {
        MEConfig cfg = MEConfig.INSTANCE;
        if (cfg == null) return 1;

        // Determine the permission base. Prefer explicit config if set, else our new node.
        String base = (cfg.permissions != null && cfg.permissions.homesMultipleBase != null && !cfg.permissions.homesMultipleBase.isBlank())
                ? cfg.permissions.homesMultipleBase
                : PermNodes.HOME_MULTIPLE; // "messentials.home.multiple"

        // 1) numeric nodes messentials.home.multiple.<n>
        int cap = Math.max(1, cfg.homes.numericCap <= 0 ? 64 : cfg.homes.numericCap);
        for (int n = cap; n >= 1; n--) {
            if (has(p, base + "." + n)) return n;
        }

        // 2) group nodes messentials.home.multiple.<group>
        int best = cfg.homes.defaultHomes;
        for (Map.Entry<String, Integer> e : cfg.homes.groups.entrySet()) {
            String group = e.getKey();
            int val = e.getValue() == null ? cfg.homes.defaultHomes : e.getValue();
            if (has(p, base + "." + group)) {
                if (val > best) best = val;
            }
        }

        // 3) LuckPerms group name -> cfg.homes.groups map (optional)
        if (cfg.features.useLuckPermsHomeLimits && lpAvailable()) {
            var user = LuckPermsProvider.get().getUserManager().getUser(p.getUUID());
            if (user != null) {
                for (var node : user.getNodes()) {
                    String key = node.getKey(); // e.g., "group.vip"
                    if (key.startsWith("group.")) {
                        String name = key.substring("group.".length());
                        Integer mapped = cfg.homes.groups.get(name);
                        if (mapped != null && mapped > best) best = mapped;
                    }
                }
            }
        }

        return best > 0 ? best : cfg.homes.defaultHomes;
    }

    private static boolean has(ServerPlayer p, String perm) {
        try {
            if (lpAvailable()) {
                LuckPerms lp = LuckPermsProvider.get();
                var user = lp.getUserManager().getUser(p.getUUID());
                if (user != null && user.getCachedData().getPermissionData().checkPermission(perm).asBoolean()) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        // fallback: allow if the sender has vanilla permission level 2+ (ops). For players we typically expect LP.
        return p.hasPermissions(2);
    }

    private static boolean lpAvailable() {
        try { LuckPermsProvider.get(); return true; } catch (Throwable t) { return false; }
    }
}
