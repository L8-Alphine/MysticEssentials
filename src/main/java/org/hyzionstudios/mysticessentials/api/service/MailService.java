package org.hyzionstudios.mysticessentials.api.service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.hyzionstudios.mysticessentials.api.model.MailMessage;

/** Virtual mail: send to online/offline players, inbox read/unread, and bulk send. */
public interface MailService {

    /** Sends mail to a recipient (online or offline). {@code sender} may be {@code null} for server mail. */
    CompletableFuture<Void> send(UUID sender, String senderName, UUID recipient, String body);

    /**
     * Delivers a fully-built mail prototype to {@code recipient}. The prototype is
     * copied per recipient (fresh id, delivery date, reset unread/claim state, and
     * deep-copied rewards) so callers can reuse one prototype for many recipients.
     */
    CompletableFuture<Void> deliver(UUID recipient, MailMessage prototype);

    /** Sends mail to every player that holds {@code permission} (or all players when {@code null}). */
    CompletableFuture<Integer> sendAll(String senderName, String permission, String body);

    CompletableFuture<List<MailMessage>> inbox(UUID player);

    /** Loads a single mail from a player's inbox by id, or empty if absent. */
    CompletableFuture<java.util.Optional<MailMessage>> getMessage(UUID player, String mailId);

    CompletableFuture<Integer> unreadCount(UUID player);

    CompletableFuture<Boolean> markRead(UUID player, String mailId);

    /** Marks every message in a player's inbox as read; returns how many were flipped. */
    CompletableFuture<Integer> markAllRead(UUID player);

    /** Sets a mail's archived flag (Archived folder). */
    CompletableFuture<Boolean> setArchived(UUID player, String mailId, boolean archived);

    /** Sets a mail's soft-deleted flag (Deleted folder); {@link #delete} still purges permanently. */
    CompletableFuture<Boolean> setDeleted(UUID player, String mailId, boolean deleted);

    /** Marks a mail's rewards as claimed (claim-once). */
    CompletableFuture<Boolean> markClaimed(UUID player, String mailId);

    CompletableFuture<Boolean> delete(UUID player, String mailId);

    CompletableFuture<Void> clear(UUID player);
}
