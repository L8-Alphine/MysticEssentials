package org.hyzionstudios.mysticessentials.modules.chat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;

import org.hyzionstudios.mysticessentials.api.chat.ChatDeliveryResult.Status;
import org.hyzionstudios.mysticessentials.api.chat.ChatMute;

/**
 * Who a line handed to {@code ChatService.deliver} reaches, decided before anything is
 * rendered or sent, and the ignore filter public chat shares with it. Free of engine
 * types so the rules can be checked without a server.
 */
final class ChatDelivery {

    /**
     * @param status     {@code DELIVERED}, {@code SHADOW_MUTED}, {@code MUTED},
     *                   {@code BLOCKED}, {@code SENDER_OFFLINE} or {@code EMPTY}
     * @param recipients who gets the line, the sender last; empty when it is refused
     * @param skipped    requested recipients other than the sender who do not get it
     */
    record Plan(Status status, List<UUID> recipients, int skipped) {
    }

    private ChatDelivery() {
    }

    /**
     * @param senderMute   the sender's mute, or {@code null}
     * @param blocked      whether another chat rule refuses the line (chat guard,
     *                     tutorial, moderation unable to answer)
     * @param emptyMessage whether nothing is left of the message once cleaned
     * @param online       whether a player is online on this server
     * @param refuses      whether {@code (recipient, sender)} must not get the sender's lines
     */
    static Plan plan(UUID sender, Collection<UUID> requested, ChatMute senderMute, boolean blocked,
            boolean emptyMessage, Predicate<UUID> online, BiPredicate<UUID, UUID> refuses) {
        Set<UUID> others = new LinkedHashSet<>();
        if (requested != null) {
            for (UUID recipient : requested) {
                if (recipient != null && !recipient.equals(sender)) {
                    others.add(recipient);
                }
            }
        }
        if (sender == null || !online.test(sender)) {
            return new Plan(Status.SENDER_OFFLINE, List.of(), others.size());
        }
        if (senderMute != null && !senderMute.shadow()) {
            return new Plan(Status.MUTED, List.of(), others.size());
        }
        if (blocked) {
            return new Plan(Status.BLOCKED, List.of(), others.size());
        }
        if (emptyMessage) {
            return new Plan(Status.EMPTY, List.of(), others.size());
        }
        if (senderMute != null) {
            // As MysticModeration does in public chat: only the author sees the line,
            // so nothing tells them they are muted.
            return new Plan(Status.SHADOW_MUTED, List.of(sender), others.size());
        }
        List<UUID> reached = new ArrayList<>();
        int skipped = 0;
        for (UUID recipient : others) {
            if (online.test(recipient) && !refuses.test(recipient, sender)) {
                reached.add(recipient);
            } else {
                skipped++;
            }
        }
        // The sender always sees their own line, as in public chat.
        reached.add(sender);
        return new Plan(Status.DELIVERED, List.copyOf(reached), skipped);
    }

    /** A mute reason to show: {@code reason}, or {@code fallback} when the moderator gave none. */
    static String reasonOrDefault(String reason, String fallback) {
        return reason == null || reason.isBlank() ? fallback : reason;
    }

    /**
     * The targets of a public or channel line without those who ignore its sender; the
     * sender keeps their own line and {@code null} targets are dropped.
     */
    static <T> List<T> withoutIgnoring(List<T> targets, Function<T, UUID> idOf, UUID sender,
            Predicate<UUID> ignoresSender) {
        List<T> kept = new ArrayList<>(targets.size());
        for (T target : targets) {
            if (target == null) {
                continue;
            }
            UUID id = idOf.apply(target);
            if (id.equals(sender) || !ignoresSender.test(id)) {
                kept.add(target);
            }
        }
        return kept;
    }
}
