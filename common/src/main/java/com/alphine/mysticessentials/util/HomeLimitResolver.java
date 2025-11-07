package com.alphine.mysticessentials.util;

import com.alphine.mysticessentials.config.MEConfig;
import net.minecraft.server.level.ServerPlayer;

// LuckPerms (soft) — no compile error if absent thanks to reflection-style guard
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;

import java.util.Map;

public final class HomeLimitResolver {
    private HomeLimitResolver() {}

    public static int resolve(ServerPlayer p) {
        MEConfig cfg = MEConfig.INSTANCE;
        if (cfg == null) return 1;

        // 1) numeric nodes messentials.homes.multiple.<n>
        int cap = Math.max(1, cfg.homes.numericCap <= 0 ? 64 : cfg.homes.numericCap);
        for (int n = cap; n >= 1; n--) {
            if (has(p, cfg.permissions.homesMultipleBase + "." + n)) {
                return n;
            }
        }

        // 2) group nodes messentials.homes.multiple.<group>
        //    Highest mapped value wins if multiple match
        int best = cfg.homes.defaultHomes;
        for (Map.Entry<String,Integer> e : cfg.homes.groups.entrySet()) {
            String group = e.getKey();
            int val = e.getValue() == null ? cfg.homes.defaultHomes : e.getValue();
            if (has(p, cfg.permissions.homesMultipleBase + "." + group)) {
                if (val > best) best = val;
            }
        }

        // 3) LuckPerms primary/any group name match (optional)
        if (cfg.features.useLuckPermsHomeLimits && lpAvailable()) {
            var user = LuckPermsProvider.get().getUserManager().getUser(p.getUUID());
            if (user != null) {
                for (var node : user.getNodes()) {
                    String gp = node.getKey(); // e.g., "group.vip"
                    if (gp.startsWith("group.")) {
                        String name = gp.substring("group.".length());
                        Integer mapped = cfg.homes.groups.get(name);
                        if (mapped != null && mapped > best) best = mapped;
                    }
                }
            }
        }

        // 4) fallback
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
        // fallback to op-level permission if LP not present
        return p.hasPermissions(2);
    }

    private static boolean lpAvailable() {
        try { LuckPermsProvider.get(); return true; } catch (Throwable t) { return false; }
    }
}
