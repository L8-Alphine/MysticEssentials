package org.hyzionstudios.mysticessentials.modules.mail;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.hyzionstudios.mysticessentials.api.model.MailMessage;
import org.hyzionstudios.mysticessentials.api.service.MailService;
import org.hyzionstudios.mysticessentials.api.service.StorageService;
import org.hyzionstudios.mysticessentials.core.module.AbstractMysticModule;
import org.hyzionstudios.mysticessentials.core.util.Json;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommand;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommandSender;

import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Virtual mail: send to online or offline players, inbox read/unread tracking,
 * and permission-gated bulk send. Inboxes are stored through the
 * {@link StorageService} under the {@code mail} namespace, keyed by recipient
 * UUID, so offline delivery works with any storage provider.
 */
public final class MailModule extends AbstractMysticModule implements MailService {

    private static final String NAMESPACE = "mail";
    private static final Type INBOX_TYPE = new TypeToken<ArrayList<MailMessage>>() {
    }.getType();

    private MailConfig config = new MailConfig();

    public MailModule() {
        super("mail", "Mail", "1.0.0");
    }

    @Override
    public void onEnable() {
        config = core.configManager().loadModuleConfig(id(), MailConfig.class, new MailConfig());
        registerCommand(new MailCommand());
        core.platform().onEvent(
                com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent.class,
                (com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent event) ->
                        notifyUnread(event.getPlayerRef()));
    }

    @Override
    public void onReload() {
        config = core.configManager().loadModuleConfig(id(), MailConfig.class, new MailConfig());
    }

    @Override
    public void onDisable() {
        // Inboxes are persisted on every mutation; nothing to flush.
    }

    private void notifyUnread(PlayerRef player) {
        if (!config.notifyUnreadOnJoin) {
            return;
        }
        inbox(player.getUuid()).thenAccept(inbox -> {
            int unread = (int) inbox.stream().filter(m -> !m.isRead()).count();
            if (unread > 0) {
                core.getMessageService().sendKey(player, "mail-notify-join-unread",
                        Map.of("unread", Integer.toString(unread)));
            } else if (!inbox.isEmpty()) {
                core.getMessageService().sendKey(player, "mail-notify-join-any",
                        Map.of("count", Integer.toString(inbox.size())));
            }
        });
    }

    // ----- MailService -------------------------------------------------------

    private CompletableFuture<List<MailMessage>> loadInbox(UUID player) {
        StorageService storage = core.getStorageService();
        return storage.load(NAMESPACE, player.toString()).thenApply(element -> {
            List<MailMessage> inbox = element == null ? null : Json.gson().fromJson(element, INBOX_TYPE);
            return inbox != null ? inbox : new ArrayList<>();
        });
    }

    private CompletableFuture<Void> saveInbox(UUID player, List<MailMessage> inbox) {
        return core.getStorageService().save(NAMESPACE, player.toString(), Json.toTree(inbox));
    }

    @Override
    public CompletableFuture<Void> send(UUID sender, String senderName, UUID recipient, String body) {
        String trimmed = config.maxMessageLength > 0 && body != null && body.length() > config.maxMessageLength
                ? body.substring(0, config.maxMessageLength)
                : body;
        return loadInbox(recipient).thenCompose(inbox -> {
            enforceInboxCap(inbox);
            inbox.add(MailMessage.create(sender, senderName, trimmed));
            CompletableFuture<Void> saved = saveInbox(recipient, inbox);
            core.platform().findPlayer(recipient).ifPresent(ref ->
                    core.getMessageService().sendKey(ref, "mail-notify-new",
                            Map.of("sender", senderName == null || senderName.isBlank() ? "Server" : senderName)));
            return saved;
        });
    }

    /** Keeps the inbox under {@code maxInboxSize}, dropping oldest read mail first. */
    private void enforceInboxCap(List<MailMessage> inbox) {
        int max = config.maxInboxSize;
        if (max <= 0) {
            return;
        }
        while (inbox.size() >= max) {
            int drop = 0;
            for (int i = 0; i < inbox.size(); i++) {
                if (inbox.get(i).isRead()) {
                    drop = i;
                    break;
                }
            }
            inbox.remove(drop);
        }
    }

    @Override
    public CompletableFuture<Integer> sendAll(String senderName, String permission, String body) {
        List<PlayerRef> recipients = core.platform().onlinePlayers().stream()
                .filter(ref -> permission == null || ref.hasPermission(permission))
                .toList();
        List<CompletableFuture<Void>> sends = new ArrayList<>();
        for (PlayerRef ref : recipients) {
            sends.add(send(null, senderName, ref.getUuid(), body));
        }
        return CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new))
                .thenApply(v -> recipients.size());
    }

    @Override
    public CompletableFuture<List<MailMessage>> inbox(UUID player) {
        return loadInbox(player);
    }

    @Override
    public CompletableFuture<Integer> unreadCount(UUID player) {
        return loadInbox(player).thenApply(inbox ->
                (int) inbox.stream().filter(m -> !m.isRead()).count());
    }

    @Override
    public CompletableFuture<Boolean> markRead(UUID player, String mailId) {
        return loadInbox(player).thenCompose(inbox -> {
            Optional<MailMessage> mail = inbox.stream().filter(m -> m.getId().equals(mailId)).findFirst();
            if (mail.isEmpty()) {
                return CompletableFuture.completedFuture(false);
            }
            mail.get().setRead(true);
            return saveInbox(player, inbox).thenApply(v -> true);
        });
    }

    @Override
    public CompletableFuture<Boolean> delete(UUID player, String mailId) {
        return loadInbox(player).thenCompose(inbox -> {
            boolean removed = inbox.removeIf(m -> m.getId().equals(mailId));
            return removed ? saveInbox(player, inbox).thenApply(v -> true)
                    : CompletableFuture.completedFuture(false);
        });
    }

    @Override
    public CompletableFuture<Void> clear(UUID player) {
        return saveInbox(player, new ArrayList<>());
    }

    // ----- Custom UI ----------------------------------------------------------

    /** Loads the inbox, then opens the Mail UI (storage is async; page building is not). */
    void openMailUi(PlayerRef player, String selectedId) {
        inbox(player.getUuid()).thenAccept(inbox ->
                core.platform().openPage(player,
                        new MailPages.MailPage(core, this, player, inbox, selectedId)));
    }

    /**
     * Sends mail from the UI compose row, resolving offline recipients by name
     * (permission-gated the same way as {@code /mail send}).
     */
    CompletableFuture<Void> sendFromUi(PlayerRef sender, String targetName, String body) {
        if (!sender.hasPermission(org.hyzionstudios.mysticessentials.api.Permissions.MAIL_SEND)) {
            core.getMessageService().sendKey(sender, "no-permission");
            return CompletableFuture.completedFuture(null);
        }
        Optional<PlayerRef> online = core.platform().findPlayerByName(targetName)
                .filter(ref -> core.vanish().canSee(sender.getUuid(), ref.getUuid()));
        if (online.isPresent()) {
            return send(sender.getUuid(), sender.getUsername(), online.get().getUuid(), body)
                    .thenRun(() -> core.getMessageService().sendKey(sender, "mail-sent",
                            Map.of("player", targetName)));
        }
        if (!sender.hasPermission(org.hyzionstudios.mysticessentials.api.Permissions.MAIL_SEND_OFFLINE)) {
            core.getMessageService().sendKey(sender, "player-not-found");
            return CompletableFuture.completedFuture(null);
        }
        return core.getPlayerProfileService().resolveUuid(targetName).thenCompose(resolved -> {
            if (resolved.isPresent()) {
                return send(sender.getUuid(), sender.getUsername(), resolved.get(), body).thenRun(() ->
                        core.getMessageService().sendKey(sender, "mail-sent-offline",
                                Map.of("player", targetName)));
            }
            core.getMessageService().sendKey(sender, "mail-player-never-joined",
                    Map.of("player", targetName));
            return CompletableFuture.completedFuture(null);
        });
    }

    // ----- Command -----------------------------------------------------------

    /**
     * {@code /mail} with no args opens the Mail UI; text subcommands remain for
     * quick use: inbox / read &lt;id&gt; / send &lt;player&gt; &lt;msg&gt; /
     * sendall &lt;msg&gt; / delete &lt;id&gt; / clear.
     */
    private final class MailCommand extends MysticCommand {
        MailCommand() {
            super(MailModule.this.core, "mail", "Send and read mail.");
            requirePermission(org.hyzionstudios.mysticessentials.api.Permissions.MAIL_USE);
            addSubCommand(new MailInboxCommand());
            addSubCommand(new MailUiCommand());
            addSubCommand(new MailReadCommand());
            addSubCommand(new MailSendCommand());
            addSubCommand(new MailSendAllCommand());
            addSubCommand(new MailDeleteCommand());
            addSubCommand(new MailClearCommand());
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            openMailUi(sender.player().orElseThrow(), null);
        }

        private void showInbox(MysticCommandSender sender) {
            inbox(sender.uuid()).thenAccept(inbox -> {
                if (inbox.isEmpty()) {
                    sender.replyKey("mail-inbox-empty");
                    return;
                }
                sender.replyKey("mail-inbox-header", Map.of("count", Integer.toString(inbox.size())));
                for (MailMessage mail : inbox) {
                    sender.replyKey("mail-inbox-entry", Map.of(
                            "read_color", mail.isRead() ? "&8" : "&e",
                            "id", shortId(mail.getId()),
                            "sender", mail.getSenderName() == null ? "Server" : mail.getSenderName(),
                            "body", mail.getBody() == null ? "" : mail.getBody()));
                }
            });
        }

        private void sendMail(MysticCommandSender sender, String targetName, String body) {
            if (!sender.hasPermission(org.hyzionstudios.mysticessentials.api.Permissions.MAIL_SEND)) {
                sender.replyKey("no-permission");
                return;
            }
            Optional<PlayerRef> online = core.platform().findPlayerByName(targetName);
            if (online.isPresent()) {
                send(sender.uuid(), sender.name(), online.get().getUuid(), body)
                        .thenRun(() -> sender.replyKey("mail-sent", Map.of("player", targetName)));
            } else if (sender.hasPermission(org.hyzionstudios.mysticessentials.api.Permissions.MAIL_SEND_OFFLINE)) {
                // Offline delivery: resolve the name via our username index, then write to
                // the recipient's stored inbox (keyed by UUID) so it works while they are offline.
                core.getPlayerProfileService().resolveUuid(targetName).thenAccept(resolved -> {
                    if (resolved.isPresent()) {
                        send(sender.uuid(), sender.name(), resolved.get(), body)
                                .thenRun(() -> sender.replyKey("mail-sent-offline", Map.of("player", targetName)));
                    } else {
                        sender.replyKey("mail-player-never-joined", Map.of("player", targetName));
                    }
                });
            } else {
                sender.replyKey("player-not-found");
            }
        }

        private void sendAllMail(MysticCommandSender sender, String body) {
            if (!sender.hasPermission(org.hyzionstudios.mysticessentials.api.Permissions.MAIL_SEND_ALL)) {
                sender.replyKey("no-permission");
                return;
            }
            sendAll(sender.name(), null, body)
                    .thenAccept(count -> sender.replyKey("mail-sent-all", Map.of("count", Integer.toString(count))));
        }

        private void deleteMail(MysticCommandSender sender, String shortId) {
            inbox(sender.uuid()).thenAccept(list -> list.stream()
                    .filter(m -> shortId(m.getId()).equalsIgnoreCase(shortId))
                    .findFirst()
                    .ifPresentOrElse(
                            m -> delete(sender.uuid(), m.getId()).thenRun(() -> sender.replyKey("mail-deleted")),
                            () -> sender.replyKey("mail-missing", Map.of("id", shortId))));
        }

        private void markReadByShortId(MysticCommandSender sender, String shortId) {
            inbox(sender.uuid()).thenAccept(list -> list.stream()
                    .filter(m -> shortId(m.getId()).equalsIgnoreCase(shortId))
                    .findFirst()
                    .ifPresentOrElse(m -> markRead(sender.uuid(), m.getId()).thenRun(() ->
                            sender.replyKey("mail-read-line", Map.of(
                                    "sender", m.getSenderName() == null ? "Server" : m.getSenderName(),
                                    "body", m.getBody() == null ? "" : m.getBody()))),
                            () -> sender.replyKey("mail-missing", Map.of("id", shortId))));
        }

        private final class MailInboxCommand extends MysticCommand {
            MailInboxCommand() {
                super(MailModule.this.core, "inbox", "List your mail.");
            }

            @Override
            protected void run(MysticCommandSender sender) {
                showInbox(sender);
            }
        }

        private final class MailUiCommand extends MysticCommand {
            MailUiCommand() {
                super(MailModule.this.core, "ui", "Open the mail UI.");
            }

            @Override
            protected void run(MysticCommandSender sender) {
                if (!sender.isPlayer()) {
                    sender.replyKey("player-only");
                    return;
                }
                openMailUi(sender.player().orElseThrow(), null);
            }
        }

        private final class MailReadCommand extends MysticCommand {
            private final RequiredArg<String> id = withRequiredArg("id", "Mail id", ArgTypes.STRING);

            MailReadCommand() {
                super(MailModule.this.core, "read", "Read a mail message.");
            }

            @Override
            protected void run(MysticCommandSender sender) {
                markReadByShortId(sender, sender.get(id));
            }
        }

        private final class MailSendCommand extends MysticCommand {
            private final RequiredArg<String> player = withRequiredArg("player", "Recipient player", ArgTypes.STRING)
                    .suggest((commandSender, input, index, result) ->
                            core.platform().onlinePlayers().forEach(ref -> result.suggest(ref.getUsername())));
            private final RequiredArg<String> message =
                    withRequiredArg("message", "Message", ArgTypes.GREEDY_STRING);

            MailSendCommand() {
                super(MailModule.this.core, "send", "Send mail to a player.");
                requirePermission(org.hyzionstudios.mysticessentials.api.Permissions.MAIL_SEND);
            }

            @Override
            protected void run(MysticCommandSender sender) {
                sendMail(sender, sender.get(player), sender.get(message));
            }
        }

        private final class MailSendAllCommand extends MysticCommand {
            private final RequiredArg<String> message =
                    withRequiredArg("message", "Message", ArgTypes.GREEDY_STRING);

            MailSendAllCommand() {
                super(MailModule.this.core, "sendall", "Send mail to all online players.");
                requirePermission(org.hyzionstudios.mysticessentials.api.Permissions.MAIL_SEND_ALL);
            }

            @Override
            protected void run(MysticCommandSender sender) {
                sendAllMail(sender, sender.get(message));
            }
        }

        private final class MailDeleteCommand extends MysticCommand {
            private final RequiredArg<String> id = withRequiredArg("id", "Mail id", ArgTypes.STRING);

            MailDeleteCommand() {
                super(MailModule.this.core, "delete", "Delete a mail message.");
            }

            @Override
            protected void run(MysticCommandSender sender) {
                deleteMail(sender, sender.get(id));
            }
        }

        private final class MailClearCommand extends MysticCommand {
            MailClearCommand() {
                super(MailModule.this.core, "clear", "Clear your inbox.");
            }

            @Override
            protected void run(MysticCommandSender sender) {
                clear(sender.uuid()).thenRun(() -> sender.replyKey("mail-cleared"));
            }
        }
    }

    private static String shortId(String id) {
        return id.substring(0, 8);
    }
}
