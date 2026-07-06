package org.hyzionstudios.mysticessentials.api.service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.hyzionstudios.mysticessentials.api.model.MailMessage;

/** Virtual mail: send to online/offline players, inbox read/unread, and bulk send. */
public interface MailService {

    /** Sends mail to a recipient (online or offline). {@code sender} may be {@code null} for server mail. */
    CompletableFuture<Void> send(UUID sender, String senderName, UUID recipient, String body);

    /** Sends mail to every player that holds {@code permission} (or all players when {@code null}). */
    CompletableFuture<Integer> sendAll(String senderName, String permission, String body);

    CompletableFuture<List<MailMessage>> inbox(UUID player);

    CompletableFuture<Integer> unreadCount(UUID player);

    CompletableFuture<Boolean> markRead(UUID player, String mailId);

    CompletableFuture<Boolean> delete(UUID player, String mailId);

    CompletableFuture<Void> clear(UUID player);
}
