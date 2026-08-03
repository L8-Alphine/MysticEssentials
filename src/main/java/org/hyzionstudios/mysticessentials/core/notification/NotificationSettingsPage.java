package org.hyzionstudios.mysticessentials.core.notification;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.platform.ui.MysticPage;

import com.google.gson.JsonObject;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Per-player notification settings: which surfaces this player accepts, and
 * whether they are in do-not-disturb.
 *
 * <p>Critical alerts deliberately have no toggle here. The panel says so in
 * plain words instead of showing a control that would not work — and if the
 * server has opted into {@code allow-player-disable}, the same line says that
 * too, so the UI never misrepresents what the server will actually do.</p>
 */
public final class NotificationSettingsPage extends MysticPage {

    private static final String UI = "MysticEssentials/NotificationSettings.ui";

    private final NotificationServiceImpl notifications;

    public NotificationSettingsPage(MysticCore core, PlayerRef player,
            NotificationServiceImpl notifications) {
        super(core, player, CustomPageLifetime.CanDismiss);
        this.notifications = notifications;
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event,
            Store<EntityStore> store) {
        cmd.append(UI);
        NotificationPreferences preferences = notifications.preferences(player.getUuid());

        cmd.set("#PageSubtitle.TextSpans", uiText("#PageSubtitle.TextSpans",
                "Choose how the server reaches you. Mention-specific options live "
                        + "under /mentions."));

        toggle(cmd, event, "#ChatToggle", "Chat", preferences.chat, "chat");
        toggle(cmd, event, "#TitlesToggle", "Titles", preferences.titles, "titles");
        toggle(cmd, event, "#ActionBarToggle", "Action Bar", preferences.actionBar, "actionbar");
        toggle(cmd, event, "#ToastToggle", "Toasts", preferences.toasts, "toasts");
        toggle(cmd, event, "#SoundToggle", "Sounds", preferences.sounds, "sounds");
        toggle(cmd, event, "#BannerToggle", "Banners", preferences.banners, "banners");
        toggle(cmd, event, "#DndToggle", "Do Not Disturb", preferences.doNotDisturb, "dnd");

        boolean allowDisable = notifications.config().critical.allowPlayerDisable;
        cmd.set("#CriticalNote.TextSpans", uiText("#CriticalNote.TextSpans", allowDisable
                ? "This server allows critical alerts to be suppressed by the settings above."
                : "Critical alerts always reach you. These settings and Do Not Disturb do "
                        + "not apply to them."));

        event.addEventBinding(CustomUIEventBindingType.Activating, "#ResetButton",
                new EventData().put("action", "reset"));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#BackButton",
                new EventData().put("action", "back"));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton",
                new EventData().put("action", "close"));
    }

    private void toggle(UICommandBuilder cmd, UIEventBuilder event, String selector, String label,
            boolean on, String key) {
        cmd.set(selector + ".TextSpans", uiText(selector + ".TextSpans",
                label + "   " + (on ? "&aON" : "&8OFF")));
        event.addEventBinding(CustomUIEventBindingType.Activating, selector,
                new EventData().put("action", "toggle").put("key", key));
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
        JsonObject payload = parse(data);
        String action = string(payload, "action");
        NotificationPreferences preferences = notifications.preferences(player.getUuid());

        switch (action) {
            case "toggle" -> {
                switch (field(payload, "key")) {
                    case "chat" -> preferences.chat = !preferences.chat;
                    case "titles" -> preferences.titles = !preferences.titles;
                    case "actionbar" -> preferences.actionBar = !preferences.actionBar;
                    case "toasts" -> preferences.toasts = !preferences.toasts;
                    case "sounds" -> preferences.sounds = !preferences.sounds;
                    case "banners" -> preferences.banners = !preferences.banners;
                    case "dnd" -> preferences.doNotDisturb = !preferences.doNotDisturb;
                    default -> { /* unknown key: leave settings untouched */ }
                }
                persistAndReopen(ref, store);
            }
            case "reset" -> {
                // Surface preferences only. Mention scope and the blocked list are
                // owned by /mentions, and quietly clearing them here would be a
                // surprising side effect of pressing "Reset" on a different screen.
                NotificationPreferences defaults = new NotificationPreferences();
                preferences.chat = defaults.chat;
                preferences.titles = defaults.titles;
                preferences.actionBar = defaults.actionBar;
                preferences.toasts = defaults.toasts;
                preferences.sounds = defaults.sounds;
                preferences.banners = defaults.banners;
                preferences.doNotDisturb = defaults.doNotDisturb;
                persistAndReopen(ref, store);
            }
            case "back" -> {
                notifications.savePreferences(player.getUuid());
                reopen(ref, store, new NotificationCenterPage(core, player, notifications));
            }
            default -> {
                notifications.savePreferences(player.getUuid());
                close(ref, store);
            }
        }
    }

    private void persistAndReopen(Ref<EntityStore> ref, Store<EntityStore> store) {
        notifications.savePreferences(player.getUuid());
        reopen(ref, store, new NotificationSettingsPage(core, player, notifications));
    }
}
