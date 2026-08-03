package org.hyzionstudios.mysticessentials.core.notification;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.hyzionstudios.mysticessentials.api.notification.NotificationFilter;
import org.hyzionstudios.mysticessentials.api.notification.NotificationRecord;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.message.MysticText;
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
 * {@code /notifications} — the Notification Center.
 *
 * <p>This is where a notification stops being an interruption and becomes a
 * record. Everything the engine stored is listed newest-first with its category,
 * age, and read state; opening one performs its action, which is what makes a
 * missed mention still take you to the channel and a missed guild alert still
 * open the claim.</p>
 *
 * <p>The filter tabs are generated from the registry rather than hardcoded, so a
 * server only ever sees tabs something can actually fill. Opening the page does
 * <b>not</b> mark everything read — reading a list is not the same as reading
 * its contents, and silently clearing the unread state is how people miss things
 * twice.</p>
 */
public final class NotificationCenterPage extends MysticPage {

    private static final String UI = "MysticEssentials/Notifications.ui";
    private static final String ROW_UI = "MysticEssentials/NotificationRow.ui";
    private static final String FILTER_ROW_UI = "MysticEssentials/NotificationFilterRow.ui";

    private static final String DEFAULT_FILTER = "all";
    private static final int MAX_ROWS = 50;

    private final NotificationServiceImpl notifications;
    private final String filterId;

    public NotificationCenterPage(MysticCore core, PlayerRef player,
            NotificationServiceImpl notifications) {
        this(core, player, notifications, DEFAULT_FILTER);
    }

    public NotificationCenterPage(MysticCore core, PlayerRef player,
            NotificationServiceImpl notifications, String filterId) {
        super(core, player, CustomPageLifetime.CanDismiss);
        this.notifications = notifications;
        this.filterId = filterId == null || filterId.isBlank() ? DEFAULT_FILTER : filterId;
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event,
            Store<EntityStore> store) {
        cmd.append(UI);

        List<NotificationRecord> all = notifications.history(player.getUuid());
        NotificationFilter active = notifications.filter(filterId).orElse(null);
        List<NotificationRecord> shown = applyFilter(all, active);
        long unread = all.stream().filter(record -> !record.read()).count();

        cmd.set("#FooterCounts.TextSpans", uiText("#FooterCounts.TextSpans",
                all.size() + " notification" + (all.size() == 1 ? "" : "s")
                        + "  •  " + unread + " unread"));

        // Distinguish "you have nothing" from "nothing matches this tab" — the
        // header count says 2 while the list is empty, and without this the page
        // reads as a bug rather than an active filter.
        cmd.set("#ListEmpty.Visible", shown.isEmpty());
        if (shown.isEmpty()) {
            cmd.set("#ListEmpty.TextSpans", uiText("#ListEmpty.TextSpans", all.isEmpty()
                    ? "Nothing here yet."
                    : "No notifications match the " + filterLabel(active) + " filter."));
        }

        for (int i = 0; i < shown.size() && i < MAX_ROWS; i++) {
            buildRow(cmd, event, shown.get(i), "#NotificationList[" + i + "]");
        }

        buildFilterRow(cmd, event);

        event.addEventBinding(CustomUIEventBindingType.Activating, "#MarkAllButton",
                new EventData().put("action", "mark-all"));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#SettingsButton",
                new EventData().put("action", "settings"));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton",
                new EventData().put("action", "close"));
    }

    /**
     * Builds one tab per registered filter. If the active filter has since been
     * unregistered (its mod unloaded while the page was open), a marked tab is
     * still shown for it so the empty list has a visible explanation.
     */
    private void buildFilterRow(UICommandBuilder cmd, UIEventBuilder event) {
        List<NotificationFilter> filters = notifications.filters();
        boolean activeIsRegistered = false;
        int index = 0;

        for (NotificationFilter filter : filters) {
            String id = NotificationServiceImpl.safeFilterId(filter);
            boolean selected = id.equals(filterId);
            activeIsRegistered |= selected;
            filterTab(cmd, event, index++, NotificationServiceImpl.safeFilterName(filter), id,
                    selected, true);
        }
        if (!activeIsRegistered) {
            filterTab(cmd, event, index, filterId, filterId, true, false);
        }
    }

    private void filterTab(UICommandBuilder cmd, UIEventBuilder event, int index, String label,
            String id, boolean selected, boolean selectable) {
        String selector = "#FilterRow[" + index + "]";
        cmd.append("#FilterRow", FILTER_ROW_UI);
        cmd.set(selector + ".TextSpans", uiText(selector + ".TextSpans",
                (selected ? "&a> " : "&7") + (selectable ? label : label + " &8(gone)")));
        if (selectable) {
            event.addEventBinding(CustomUIEventBindingType.Activating, selector,
                    new EventData().put("action", "filter").put("filter", id));
        }
    }

    private void buildRow(UICommandBuilder cmd, UIEventBuilder event, NotificationRecord record,
            String selector) {
        cmd.append("#NotificationList", ROW_UI);

        NotificationConfig.Category category =
                notifications.config().category(record.category().id());
        cmd.set(selector + " #Accent.Background", safeColor(category.accent));

        cmd.set(selector + " #Title.TextSpans", uiText(selector + " #Title.TextSpans",
                badge(record) + MysticText.stripMarkup(record.heading())));
        cmd.set(selector + " #Sub.TextSpans", uiText(selector + " #Sub.TextSpans",
                MysticText.stripMarkup(record.message())));
        cmd.set(selector + " #Meta.TextSpans", uiText(selector + " #Meta.TextSpans",
                meta(record, category)));

        cmd.set(selector + " #OpenButton.Visible", record.action().isPresent());
        if (record.action().isPresent()) {
            event.addEventBinding(CustomUIEventBindingType.Activating, selector + " #OpenButton",
                    new EventData().put("action", "open").put("id", record.id()));
        }
        event.addEventBinding(CustomUIEventBindingType.Activating, selector + " #DismissButton",
                new EventData().put("action", "dismiss").put("id", record.id()));
    }

    /** A short marker so priority and read state are legible without colour. */
    private static String badge(NotificationRecord record) {
        return switch (record.priority()) {
            case CRITICAL -> "[!] ";
            case IMPORTANT -> "[*] ";
            default -> "";
        };
    }

    private static String meta(NotificationRecord record, NotificationConfig.Category category) {
        StringBuilder sb = new StringBuilder();
        String name = category.displayName == null ? record.category().id() : category.displayName;
        sb.append(name).append("  •  ").append(relativeTime(record.receivedAt()));
        if (!record.read()) {
            sb.append("  •  UNREAD");
        }
        if (record.source() != null && !record.source().isBlank()) {
            sb.append("  •  ").append(record.source());
        }
        return sb.toString();
    }

    private String filterLabel(NotificationFilter filter) {
        return filter == null ? filterId : NotificationServiceImpl.safeFilterName(filter);
    }

    /** An unregistered filter matches nothing, which is what makes its tab explain itself. */
    private List<NotificationRecord> applyFilter(List<NotificationRecord> records,
            NotificationFilter filter) {
        if (filter == null) {
            return List.of();
        }
        List<NotificationRecord> out = new ArrayList<>();
        for (NotificationRecord record : records) {
            if (notifications.matches(filter, record)) {
                out.add(record);
            }
        }
        return out;
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
        JsonObject payload = parse(data);
        String action = string(payload, "action");
        switch (action) {
            case "filter" -> {
                // Only switch to a filter that is actually registered, so a stale
                // click from a page opened before a mod unloaded cannot strand the
                // player on a tab nothing can fill.
                String requested = field(payload, "filter");
                String next = notifications.filter(requested).isPresent() ? requested : filterId;
                reopen(ref, store, new NotificationCenterPage(core, player, notifications, next));
            }
            case "mark-all" -> {
                notifications.markAllRead(player.getUuid());
                reopen(ref, store,
                        new NotificationCenterPage(core, player, notifications, filterId));
            }
            case "dismiss" -> {
                notifications.dismiss(player.getUuid(), field(payload, "id"));
                reopen(ref, store,
                        new NotificationCenterPage(core, player, notifications, filterId));
            }
            case "open" -> {
                String id = field(payload, "id");
                // Opening a notification is what "reading" means, so mark it read
                // here rather than when the list was merely displayed.
                notifications.markRead(player.getUuid(), id);
                notifications.find(player.getUuid(), id)
                        .ifPresent(record -> notifications.runAction(player, record.action()));
                close(ref, store);
            }
            case "settings" -> reopen(ref, store,
                    new NotificationSettingsPage(core, player, notifications));
            default -> close(ref, store);
        }
    }

    static String relativeTime(Instant instant) {
        if (instant == null) {
            return "just now";
        }
        long seconds = Math.max(0, Duration.between(instant, Instant.now()).getSeconds());
        if (seconds < 60) {
            return seconds + " second" + (seconds == 1 ? "" : "s") + " ago";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + " minute" + (minutes == 1 ? "" : "s") + " ago";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + " hour" + (hours == 1 ? "" : "s") + " ago";
        }
        long days = hours / 24;
        return days + " day" + (days == 1 ? "" : "s") + " ago";
    }
}
