package org.hyzionstudios.mysticessentials.api;

import org.hyzionstudios.mysticessentials.api.event.EventBus;
import org.hyzionstudios.mysticessentials.api.item.ItemInspectionService;
import org.hyzionstudios.mysticessentials.api.module.ModuleManager;
import org.hyzionstudios.mysticessentials.api.notification.NotificationService;
import org.hyzionstudios.mysticessentials.api.service.AfkService;
import org.hyzionstudios.mysticessentials.api.service.AnnouncementService;
import org.hyzionstudios.mysticessentials.api.service.ChatService;
import org.hyzionstudios.mysticessentials.api.service.EconomyService;
import org.hyzionstudios.mysticessentials.api.service.MailService;
import org.hyzionstudios.mysticessentials.api.service.MessageService;
import org.hyzionstudios.mysticessentials.api.service.PermissionService;
import org.hyzionstudios.mysticessentials.api.service.PlaceholderService;
import org.hyzionstudios.mysticessentials.api.service.PlayerProfileService;
import org.hyzionstudios.mysticessentials.api.service.PlaytimeService;
import org.hyzionstudios.mysticessentials.api.service.SpawnService;
import org.hyzionstudios.mysticessentials.api.service.StorageService;
import org.hyzionstudios.mysticessentials.api.service.TeleportService;
import org.hyzionstudios.mysticessentials.api.service.WarpService;
import org.hyzionstudios.mysticessentials.api.ui.CustomUiService;

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

    PlaytimeService getPlaytimeService();

    MessageService getMessageService();

    PlaceholderService getPlaceholderService();

    EconomyService getEconomyService();

    PermissionService getPermissionService();

    TeleportService getTeleportService();

    EventBus getEventBus();

    /**
     * Shared item inspection. Register an {@link org.hyzionstudios.mysticessentials.api.item.ItemViewProvider}
     * here to contribute structured data about your own items to every ItemView
     * on the server — chat item links, the details panel, and any other mod's
     * lookups — without owning any part of the layout.
     */
    ItemInspectionService getItemInspectionService();

    /**
     * The shared notification engine behind mentions, broadcasts, alerts, and
     * every other player-facing notice. Use it instead of sending your own titles
     * and sounds so your notifications obey the same profiles, player
     * preferences, and history rules as the rest of the server.
     */
    NotificationService getNotificationService();

    /**
     * Shared Custom Content UI Framework 2.0 services. Available even when the
     * optional renderer module is disabled, so addons can register components,
     * themes and typed actions during their own startup.
     */
    CustomUiService getCustomUiService();

    // ----- Module-owned services (may be null when the module is disabled) ----

    SpawnService getSpawnService();

    WarpService getWarpService();

    MailService getMailService();

    AfkService getAfkService();

    ChatService getChatService();

    AnnouncementService getAnnouncementService();
}
