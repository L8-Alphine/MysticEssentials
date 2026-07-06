package org.hyzionstudios.mysticessentials.api;

import org.hyzionstudios.mysticessentials.api.event.EventBus;
import org.hyzionstudios.mysticessentials.api.module.ModuleManager;
import org.hyzionstudios.mysticessentials.api.service.AfkService;
import org.hyzionstudios.mysticessentials.api.service.AnnouncementService;
import org.hyzionstudios.mysticessentials.api.service.ChatService;
import org.hyzionstudios.mysticessentials.api.service.EconomyService;
import org.hyzionstudios.mysticessentials.api.service.MailService;
import org.hyzionstudios.mysticessentials.api.service.MessageService;
import org.hyzionstudios.mysticessentials.api.service.PermissionService;
import org.hyzionstudios.mysticessentials.api.service.PlaceholderService;
import org.hyzionstudios.mysticessentials.api.service.PlayerProfileService;
import org.hyzionstudios.mysticessentials.api.service.SpawnService;
import org.hyzionstudios.mysticessentials.api.service.StorageService;
import org.hyzionstudios.mysticessentials.api.service.TeleportService;
import org.hyzionstudios.mysticessentials.api.service.WarpService;

/**
 * Public, service-based entry point into Mystic Essentials.
 *
 * <p>Addons should depend only on this interface (obtained through
 * {@link MysticEssentialsProvider}) and never on the concrete implementation
 * classes. This keeps addons stable across internal refactors and independent
 * of whether storage is JSON, SQL, or Redis-backed.</p>
 *
 * <p>Module-owned services (mail, spawn, warps, afk, chat, announcements) may
 * return {@code null} when the owning module is disabled in {@code config.json};
 * always null-check them or gate usage on {@link ModuleManager#isEnabled(String)}.</p>
 */
public interface MysticEssentialsAPI {

    /** Semantic version of the running Mystic Essentials build. */
    String getVersion();

    // ----- Always-available Core services ------------------------------------

    ModuleManager getModuleManager();

    StorageService getStorageService();

    PlayerProfileService getPlayerProfileService();

    MessageService getMessageService();

    PlaceholderService getPlaceholderService();

    EconomyService getEconomyService();

    PermissionService getPermissionService();

    TeleportService getTeleportService();

    EventBus getEventBus();

    // ----- Module-owned services (may be null when the module is disabled) ----

    SpawnService getSpawnService();

    WarpService getWarpService();

    MailService getMailService();

    AfkService getAfkService();

    ChatService getChatService();

    AnnouncementService getAnnouncementService();
}
