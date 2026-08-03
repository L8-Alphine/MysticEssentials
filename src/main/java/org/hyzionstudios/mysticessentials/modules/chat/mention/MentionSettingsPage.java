package org.hyzionstudios.mysticessentials.modules.chat.mention;

import java.util.List;

import org.hyzionstudios.mysticessentials.api.mention.MentionScopeProvider;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.notification.NotificationPreferences;
import org.hyzionstudios.mysticessentials.core.notification.NotificationServiceImpl;
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
 * {@code /mentions} — the per-player mention settings panel.
 *
 * <p>Each toggle writes through immediately and the page reopens, so a player
 * never has to remember to press Save and never loses a change by closing the
 * panel. "Reset Defaults" restores the mention-related fields only; it leaves
 * broadcast and alert preferences alone, since those belong to a different
 * screen and silently clearing them would be a surprise.</p>
 */
final class MentionSettingsPage extends MysticPage {

    private static final String UI = "MysticEssentials/MentionSettings.ui";

    private static final String SCOPE_ROW_UI = "MysticEssentials/MentionScopeRow.ui";

    private final NotificationServiceImpl notifications;
    private final MentionSubModule mentions;
    private final MentionConfig config;

    MentionSettingsPage(MysticCore core, PlayerRef player, NotificationServiceImpl notifications,
            MentionSubModule mentions, MentionConfig config) {
        super(core, player, CustomPageLifetime.CanDismiss);
        this.notifications = notifications;
        this.mentions = mentions;
        this.config = config;
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event,
            Store<EntityStore> store) {
        cmd.append(UI);
        NotificationPreferences preferences = notifications.preferences(player.getUuid());

        cmd.set("#PageSubtitle.TextSpans", uiText("#PageSubtitle.TextSpans",
                "Others reach you by typing " + config.prefix + player.getUsername()
                        + " in chat."));

        toggle(cmd, event, "#HighlightToggle", "Chat Highlighting", preferences.mentionHighlight,
                "highlight");
        toggle(cmd, event, "#SoundToggle", "Sound Notifications", preferences.mentionSound, "sound");
        toggle(cmd, event, "#TitleToggle", "Title Notifications", preferences.mentionTitle, "title");
        toggle(cmd, event, "#ActionBarToggle", "Action-Bar Notifications",
                preferences.mentionActionBar, "actionbar");

        buildScopeList(cmd, event, preferences);

        toggle(cmd, event, "#DndToggle", "Do Not Disturb", preferences.doNotDisturb, "dnd");

        int blocked = preferences.blockedMentioners.size();
        cmd.set("#BlockedInfo.TextSpans", uiText("#BlockedInfo.TextSpans",
                blocked == 0
                        ? "No blocked players. Use /ignore to block someone from mentioning you."
                        : blocked + " blocked player(s) cannot mention you."));

        event.addEventBinding(CustomUIEventBindingType.Activating, "#ResetButton",
                new EventData().put("action", "reset"));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton",
                new EventData().put("action", "close"));
    }

    /** A toggle row whose label carries its own state, e.g. {@code Sound — ON}. */
    private void toggle(UICommandBuilder cmd, UIEventBuilder event, String selector, String label,
            boolean on, String key) {
        cmd.set(selector + ".TextSpans", uiText(selector + ".TextSpans",
                label + "   " + (on ? "&aON" : "&8OFF")));
        event.addEventBinding(CustomUIEventBindingType.Activating, selector,
                new EventData().put("action", "toggle").put("key", key));
    }

    /**
     * Builds one row per currently available scope.
     *
     * <p>The list is generated because the options depend on which mods are
     * installed: this mod ships only Everyone and Nobody, and anything
     * relationship-based is contributed through the API. Nothing is shown for a
     * scope no one implements — an option that silently does nothing is worse
     * than an absent one.</p>
     *
     * <p>If the player previously chose a scope whose provider has since gone
     * away, a disabled-looking row is added for it so they can see <i>why</i>
     * their setting is not being applied rather than finding it silently reset.</p>
     */
    private void buildScopeList(UICommandBuilder cmd, UIEventBuilder event,
            NotificationPreferences preferences) {
        List<MentionScopeProvider> scopes = mentions.availableScopes();
        String current = preferences.scopeId();
        boolean currentIsAvailable = false;
        int index = 0;

        for (MentionScopeProvider provider : scopes) {
            String id = MentionSubModule.idOf(provider);
            boolean selected = id.equals(current);
            currentIsAvailable |= selected;
            scopeRow(cmd, event, index++, MentionSubModule.displayNameOf(provider), id, selected,
                    true);
        }

        if (!currentIsAvailable) {
            scopeRow(cmd, event, index, "Unavailable: " + current, current, true, false);
        }
    }

    /**
     * One scope row. The selection marker lives in the label text because
     * per-element style is markup-only in 0.5.6 and cannot be set at runtime.
     */
    private void scopeRow(UICommandBuilder cmd, UIEventBuilder event, int index, String label,
            String scopeId, boolean selected, boolean selectable) {
        String selector = "#ScopeList[" + index + "]";
        cmd.append("#ScopeList", SCOPE_ROW_UI);

        String marker = selected ? "&a> " : "&8  ";
        String text = selectable ? label : "&8" + label + " &7(mod not loaded)";
        cmd.set(selector + ".TextSpans", uiText(selector + ".TextSpans", marker + text));

        if (selectable) {
            event.addEventBinding(CustomUIEventBindingType.Activating, selector,
                    new EventData().put("action", "scope").put("scope", scopeId));
        }
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
        JsonObject payload = parse(data);
        String action = string(payload, "action");
        NotificationPreferences preferences = notifications.preferences(player.getUuid());

        switch (action) {
            case "toggle" -> {
                switch (field(payload, "key")) {
                    case "highlight" -> preferences.mentionHighlight = !preferences.mentionHighlight;
                    case "sound" -> preferences.mentionSound = !preferences.mentionSound;
                    case "title" -> preferences.mentionTitle = !preferences.mentionTitle;
                    case "actionbar" -> preferences.mentionActionBar = !preferences.mentionActionBar;
                    case "dnd" -> preferences.doNotDisturb = !preferences.doNotDisturb;
                    default -> { /* unknown key: leave settings untouched */ }
                }
                persistAndReopen(ref, store);
            }
            case "scope" -> {
                // Accept only an id that is actually registered, so a stale event
                // from a page opened before a mod unloaded cannot store a scope
                // nothing can enforce.
                String requested = field(payload, "scope");
                if (mentions.scope(requested).isPresent()) {
                    preferences.mentionScope = requested;
                }
                persistAndReopen(ref, store);
            }
            case "reset" -> {
                NotificationPreferences defaults = new NotificationPreferences();
                preferences.mentionHighlight = defaults.mentionHighlight;
                preferences.mentionSound = defaults.mentionSound;
                preferences.mentionTitle = defaults.mentionTitle;
                preferences.mentionActionBar = defaults.mentionActionBar;
                preferences.mentionScope = defaults.mentionScope;
                preferences.doNotDisturb = defaults.doNotDisturb;
                persistAndReopen(ref, store);
            }
            default -> close(ref, store);
        }
    }

    private void persistAndReopen(Ref<EntityStore> ref, Store<EntityStore> store) {
        notifications.savePreferences(player.getUuid());
        reopen(ref, store, new MentionSettingsPage(core, player, notifications, mentions, config));
    }
}
