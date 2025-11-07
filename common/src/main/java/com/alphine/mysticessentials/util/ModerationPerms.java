package com.alphine.mysticessentials.util;

import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import net.minecraft.server.level.ServerPlayer;

public final class ModerationPerms {
    private ModerationPerms(){}
    public static boolean exempt(ServerPlayer target, String suffix){
        // messentials.ban.exempt.<suffix>, OR admin/wildcard
        if (Perms.has(target, PermNodes.ADMIN, 2) || Perms.has(target, PermNodes.ALL, 2)) return true;
        String node = PermNodes.BAN_EXEMPT_BASE + "." + suffix;
        return Perms.has(target, node, 2);
    }
}
