package com.alphine.mysticessentials.util;

import com.alphine.mysticessentials.storage.PlayerDataStore;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class Teleports {
    private Teleports(){}

    public static void pushBackAndTeleport(ServerPlayer p, ServerLevel to,
                                           double x, double y, double z, float yaw, float pitch,
                                           PlayerDataStore pdata) {
        pushBack(p, pdata);
        p.teleportTo(to, x, y, z, yaw, pitch);
    }

    public static void pushBack(ServerPlayer p, PlayerDataStore pdata){
        PlayerDataStore.LastLoc cur = new PlayerDataStore.LastLoc();
        cur.dim = p.serverLevel().dimension().location().toString();
        cur.x = p.getX(); cur.y = p.getY(); cur.z = p.getZ();
        cur.yaw = p.getYRot(); cur.pitch = p.getXRot();
        cur.when = System.currentTimeMillis();
        // store a single back location
        pdata.setBack(p.getUUID(), cur);
    }
}
