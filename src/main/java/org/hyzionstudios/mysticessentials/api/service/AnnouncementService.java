package org.hyzionstudios.mysticessentials.api.service;

/** Broadcast-style messaging: manual broadcasts, auto-rotating broadcasts, and targeted audiences. */
public interface AnnouncementService {

    /** Broadcasts a formatted message to every online player. */
    void broadcast(String message);

    /** Broadcasts only to players currently in {@code world}. */
    void broadcastToWorld(String world, String message);

    /** Broadcasts only to players holding {@code permission}. */
    void broadcastToPermission(String permission, String message);

    /** Starts the configured auto-broadcast rotation (idempotent). */
    void startAutoBroadcast();

    /** Stops the auto-broadcast rotation. */
    void stopAutoBroadcast();
}
