package org.hyzionstudios.mysticessentials.modules.customcommands.action;

import java.util.Locale;

import org.hyzionstudios.mysticessentials.modules.customcommands.CustomCommandContext;

import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.util.NotificationUtil;

/**
 * Shows a client toast notification, using the verified 0.5.6
 * {@code NotificationUtil} (the same path the vanilla server uses).
 *
 * <pre>
 * { "type": "notification", "title": "&aThanks for voting!",
 *   "body": "&7Your reward is waiting.", "style": "success", "target": "sender" }
 * </pre>
 *
 * <p>Styles: {@code default}, {@code danger}, {@code warning}, {@code success}.
 * Targets: {@code sender} (falls back to a chat message for the console) or
 * {@code all} (whole universe).</p>
 */
public final class NotificationAction implements CustomCommandAction {

    private final String title;
    private final String body;
    private final String style;
    private final boolean toAll;

    public NotificationAction(String title, String body, String style, boolean toAll) {
        this.title = title;
        this.body = body == null ? "" : body;
        this.style = style;
        this.toAll = toAll;
    }

    /** @return {@code true} if {@code style} maps to a known {@link NotificationStyle}. */
    public static boolean isKnownStyle(String style) {
        return style == null || style.isBlank() || parseStyle(style) != null;
    }

    private static NotificationStyle parseStyle(String style) {
        if (style == null || style.isBlank()) {
            return NotificationStyle.Default;
        }
        return switch (style.toLowerCase(Locale.ROOT)) {
            case "default" -> NotificationStyle.Default;
            case "danger", "error" -> NotificationStyle.Danger;
            case "warning", "warn" -> NotificationStyle.Warning;
            case "success" -> NotificationStyle.Success;
            default -> null;
        };
    }

    @Override
    public String type() {
        return "notification";
    }

    @Override
    public void execute(CustomCommandContext context) {
        NotificationStyle notificationStyle = parseStyle(style);
        if (notificationStyle == null) {
            notificationStyle = NotificationStyle.Default;
        }
        Message primary = context.formatMessage(title);
        Message secondary = body.isBlank() ? null : context.formatMessage(body);

        if (toAll) {
            if (secondary == null) {
                NotificationUtil.sendNotificationToUniverse(primary, notificationStyle);
            } else {
                NotificationUtil.sendNotificationToUniverse(primary, secondary, notificationStyle);
            }
            return;
        }
        PlayerRef player = context.player();
        if (player == null) {
            // Console has no toast UI; degrade to plain chat output.
            context.replyFormatted(title + (body.isBlank() ? "" : " - " + body));
            return;
        }
        if (secondary == null) {
            NotificationUtil.sendNotification(player.getPacketHandler(), primary, notificationStyle);
        } else {
            NotificationUtil.sendNotification(player.getPacketHandler(), primary, secondary, notificationStyle);
        }
    }

    @Override
    public String describe() {
        return "notification [" + (toAll ? "all" : "sender") + "]: " + title;
    }
}
