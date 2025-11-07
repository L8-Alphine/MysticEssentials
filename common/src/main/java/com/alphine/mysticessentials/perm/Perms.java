package com.alphine.mysticessentials.perm;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

/** LuckPerms-aware permission helper with ADMIN/ALL and OP fallbacks. */
public final class Perms {
    private Perms(){}

    public static boolean isConsole(CommandSourceStack src) { return src.getEntity() == null; }

    public static boolean has(CommandSourceStack src, String node, int opLevelFallback) {
        if (isConsole(src)) return true; // console always allowed
        try { return has(src.getPlayerOrException(), node, opLevelFallback); }
        catch (Exception e) { return true; } // non-player sources: allow
    }

    public static boolean has(ServerPlayer p, String node, int opLevelFallback) {
        // 1) LuckPerms check (node OR admin OR wildcard)
        try {
            LuckPerms lp = LuckPermsProvider.get();
            var user = lp.getUserManager().getUser(p.getUUID());
            if (user != null) {
                var data = user.getCachedData().getPermissionData();
                if (data.checkPermission(node).asBoolean()) return true;
                if (data.checkPermission(PermNodes.ADMIN).asBoolean()) return true;
                if (data.checkPermission(PermNodes.ALL).asBoolean()) return true;
            }
        } catch (Throwable ignored) {
            // LP not present/initialized – fall back to vanilla
        }

        // 2) Vanilla OP fallback
        return p.hasPermissions(opLevelFallback);
    }

    /** Bypass helper: null/blank means disabled. Also honors ADMIN/ALL. */
    public static boolean hasBypass(ServerPlayer p, String bypassNode, int opLevelFallback) {
        if (bypassNode == null || bypassNode.isBlank()) return false;
        return has(p, bypassNode, opLevelFallback);
    }
}
