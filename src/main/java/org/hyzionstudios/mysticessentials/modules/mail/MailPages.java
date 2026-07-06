package org.hyzionstudios.mysticessentials.modules.mail;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import org.hyzionstudios.mysticessentials.api.Permissions;
import org.hyzionstudios.mysticessentials.api.model.MailMessage;
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
 * The Mail UI ({@code /mail} with no args): the inbox as a clickable list
 * (select = read + mark read), a reading pane with delete, a compose row, and —
 * for admins with {@code mysticessentials.mail.send.all} — a server-wide mail
 * row. The inbox is loaded <b>before</b> the page opens (storage is async, page
 * building is not), so the page is constructed with a snapshot of the inbox.
 */
final class MailPages {

    static final String MAIL_UI = "MysticEssentials/Mail.ui";
    static final String MAIL_ROW_UI = "MysticEssentials/MailRow.ui";

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("MMM d HH:mm").withZone(ZoneId.systemDefault());

    private MailPages() {
    }

    static final class MailPage extends MysticPage {
        private final MailModule mail;
        private final List<MailMessage> inbox;
        private final String selectedId;

        MailPage(MysticCore core, MailModule mail, PlayerRef player,
                List<MailMessage> inbox, String selectedId) {
            super(core, player, CustomPageLifetime.CanDismiss);
            this.mail = mail;
            this.inbox = inbox;
            this.selectedId = selectedId;
        }

        @Override
        public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event,
                Store<EntityStore> store) {
            cmd.append(MAIL_UI);

            long unread = inbox.stream().filter(m -> !m.isRead()).count();
            cmd.set("#MailCount.Text", inbox.size() + " messages, " + unread + " unread");
            cmd.set("#MailEmpty.Visible", inbox.isEmpty());

            for (int i = 0; i < inbox.size(); i++) {
                MailMessage message = inbox.get(i);
                String row = "#MailList[" + i + "]";
                cmd.append("#MailList", MAIL_ROW_UI);
                cmd.set(row + " #Unread.Text", message.isRead() ? "" : "●");
                cmd.set(row + " #From.Text", senderLabel(message));
                cmd.set(row + " #Preview.Text", preview(message.getBody()));
                cmd.set(row + " #Date.Text", shortDate(message.getSentDate()));
                event.addEventBinding(CustomUIEventBindingType.Activating, row,
                        new EventData().put("action", "select").put("id", message.getId()));
            }

            MailMessage selected = selectedMessage();
            cmd.set("#ReadFrom.Text", selected == null ? "Select a message" : senderLabel(selected));
            cmd.set("#ReadDate.Text", selected == null ? "" : shortDate(selected.getSentDate()));
            cmd.set("#ReadBody.Text", selected == null ? "" : selected.getBody());
            if (selected != null) {
                event.addEventBinding(CustomUIEventBindingType.Activating, "#DeleteButton",
                        new EventData().put("action", "delete").put("id", selected.getId()));
            }

            event.addEventBinding(CustomUIEventBindingType.Activating, "#ClearButton",
                    new EventData().put("action", "clear"));
            event.addEventBinding(CustomUIEventBindingType.Activating, "#SendButton",
                    new EventData().put("action", "send")
                            .append("@recipient", "#RecipientInput.Value")
                            .append("@message", "#MessageInput.Value"));

            boolean admin = player.hasPermission(Permissions.MAIL_SEND_ALL);
            cmd.set("#AdminSection.Visible", admin);
            if (admin) {
                event.addEventBinding(CustomUIEventBindingType.Activating, "#SendAllButton",
                        new EventData().put("action", "sendall").append("@message", "#AdminInput.Value"));
            }
        }

        @Override
        public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
            JsonObject payload = parse(data);
            String action = string(payload, "action");
            switch (action) {
                case "select" -> {
                    String id = field(payload, "id");
                    mail.markRead(player.getUuid(), id)
                            .thenRun(() -> mail.openMailUi(player, id));
                }
                case "delete" -> {
                    String id = field(payload, "id");
                    mail.delete(player.getUuid(), id).thenAccept(deleted -> {
                        if (deleted) {
                            core.getMessageService().sendKey(player, "mail-deleted");
                        }
                        mail.openMailUi(player, null);
                    });
                }
                case "clear" -> mail.clear(player.getUuid()).thenRun(() -> {
                    core.getMessageService().sendKey(player, "mail-cleared");
                    mail.openMailUi(player, null);
                });
                case "send" -> {
                    String recipient = field(payload, "recipient");
                    String body = field(payload, "message");
                    if (recipient.isBlank() || body.isBlank()) {
                        core.getMessageService().sendKey(player, "mail-send-player-message");
                        mail.openMailUi(player, selectedId);
                        return;
                    }
                    mail.sendFromUi(player, recipient, body)
                            .thenRun(() -> mail.openMailUi(player, selectedId));
                }
                case "sendall" -> {
                    if (!player.hasPermission(Permissions.MAIL_SEND_ALL)) {
                        return;
                    }
                    String body = field(payload, "message");
                    if (body.isBlank()) {
                        core.getMessageService().sendKey(player, "mail-sendall-message");
                        mail.openMailUi(player, selectedId);
                        return;
                    }
                    mail.sendAll(player.getUsername(), null, body).thenAccept(count -> {
                        core.getMessageService().sendKey(player, "mail-sent-all",
                                java.util.Map.of("count", Integer.toString(count)));
                        mail.openMailUi(player, selectedId);
                    });
                }
                default -> {
                }
            }
        }

        private MailMessage selectedMessage() {
            if (inbox.isEmpty()) {
                return null;
            }
            if (selectedId != null) {
                Optional<MailMessage> match = inbox.stream()
                        .filter(m -> m.getId().equals(selectedId)).findFirst();
                if (match.isPresent()) {
                    return match.get();
                }
            }
            return null;
        }

        private static String senderLabel(MailMessage message) {
            String name = message.getSenderName();
            return name == null || name.isBlank() ? "Server" : name;
        }

        private static String preview(String body) {
            if (body == null) {
                return "";
            }
            return body.length() <= 42 ? body : body.substring(0, 42) + "…";
        }

        private static String shortDate(String isoDate) {
            try {
                return DATE_FORMAT.format(Instant.parse(isoDate));
            } catch (RuntimeException e) {
                return "";
            }
        }
    }
}
