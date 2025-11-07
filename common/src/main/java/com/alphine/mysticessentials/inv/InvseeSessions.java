package com.alphine.mysticessentials.inv;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks active /invsee viewers and force-ticks their container each server tick
 * so the UI live-updates if the target changes inventory on their own.
 */
public final class InvseeSessions {
    private InvseeSessions() {}

    private static final Map<UUID, UUID> VIEWER_TO_TARGET = new ConcurrentHashMap<>();

    public static void open(ServerPlayer viewer, ServerPlayer target) {
        VIEWER_TO_TARGET.put(viewer.getUUID(), target.getUUID());
    }

    public static void close(ServerPlayer viewer) {
        VIEWER_TO_TARGET.remove(viewer.getUUID());
    }

    /** Called each server tick by the platform shim (Forge/Fabric) */
    public static void tick(MinecraftServer server) {
        VIEWER_TO_TARGET.keySet().removeIf(viewerId -> {
            ServerPlayer viewer = server.getPlayerList().getPlayer(viewerId);
            if (viewer == null) return true;                // remove: player offline
            if (viewer.containerMenu == null) return true;  // remove: no menu open
            if (!viewer.containerMenu.stillValid(viewer)) return true; // <-- fix

            try {
                viewer.containerMenu.broadcastChanges();     // push updates
            } catch (Throwable ignored) {}
            return false; // keep
        });
    }
}
