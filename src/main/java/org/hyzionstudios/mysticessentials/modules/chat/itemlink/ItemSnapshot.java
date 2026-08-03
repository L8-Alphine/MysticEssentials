package org.hyzionstudios.mysticessentials.modules.chat.itemlink;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.hyzionstudios.mysticessentials.api.item.ItemViewData;
import org.hyzionstudios.mysticessentials.api.item.RichText;

import com.hypixel.hytale.server.core.Message;

/**
 * An immutable, server-owned record of an item somebody shared in chat.
 *
 * <p>Everything shown when the link is opened comes from this object, and this
 * object was built <b>on the server, from the real stack, at capture time</b>.
 * The client only ever sends back a snapshot id. That is the whole security
 * model for item links: no client-supplied name, statistic, quality,
 * enchantment, or item id ever reaches another player's screen, because none of
 * those values are accepted from a client at all.</p>
 *
 * <p>Consequently a player cannot forge an item they never held, inflate the
 * stats of one they did, or link to an item id that does not exist. Guessing
 * another snapshot's id yields only what that snapshot legitimately contains,
 * and {@link #signature} lets a snapshot relayed between servers be
 * integrity-checked before it is trusted.</p>
 *
 * <p>No live {@code ItemStack} is retained, so inspection is strictly read-only
 * and can never mint an inventory-compatible copy of the item.</p>
 */
public final class ItemSnapshot {

    /** Short, human-typeable code used in chat and by {@code /itemview}. */
    public final String id;

    /** The normalized view built by the inspection service at capture time. */
    public final ItemViewData view;

    public final UUID senderId;
    public final String senderName;
    public final String channelName;
    public final String worldName;
    public final String serverId;

    public final Instant createdAt;
    public final Instant expiresAt;

    /**
     * Integrity tag over the identifying fields, keyed by a secret generated at
     * startup. Local lookups do not need it — a snapshot found in this server's
     * own map is authentic by construction — but a snapshot arriving over the
     * network is trusted only if its signature verifies.
     */
    public final String signature;

    ItemSnapshot(String id, ItemViewData view, UUID senderId, String senderName,
            String channelName, String worldName, String serverId,
            Instant createdAt, Instant expiresAt, String signature) {
        this.id = id;
        this.view = view;
        this.senderId = senderId;
        this.senderName = senderName == null ? "" : senderName;
        this.channelName = channelName == null ? "" : channelName;
        this.worldName = worldName == null ? "" : worldName;
        this.serverId = serverId == null ? "" : serverId;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.signature = signature;
    }

    public String itemId() {
        return view.itemId();
    }

    public int quantity() {
        return view.quantity();
    }

    /** A short, markup-safe label for chat and history rows. */
    public String plainName() {
        return view.plainName();
    }

    /**
     * The item's name as a renderable {@link Message}: a client-translated
     * segment when the item has a translation key (so a templated name such as
     * "{material} Longsword" resolves fully client-side), otherwise a literal.
     */
    public Message nameMessage() {
        RichText name = view.displayName();
        if (!name.isTranslated()) {
            String plain = name.plain();
            return Message.raw(plain.isBlank() ? plainName() : plain);
        }
        Message message = Message.translation(name.translationKey());
        for (Map.Entry<String, String> argument : name.translationArguments().entrySet()) {
            String value = argument.getValue();
            Message parameter = value.startsWith("@")
                    ? Message.translation(value.substring(1))
                    : Message.raw(value.startsWith("~") ? value.substring(1) : value);
            message.param(argument.getKey(), parameter);
        }
        return message;
    }

    /** The accent colour for this item's chat name and UI framing, if it has one. */
    public Optional<String> accentColor() {
        return view.accentColor();
    }

    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    public long capturedAtEpochMs() {
        return createdAt == null ? 0 : createdAt.toEpochMilli();
    }

    @Override
    public String toString() {
        return "ItemSnapshot[" + id + " " + view.itemId() + " by " + senderName + "]";
    }
}
