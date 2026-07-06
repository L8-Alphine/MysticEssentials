package org.hyzionstudios.mysticessentials.api.service;

import java.util.Map;
import java.util.UUID;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.receiver.IMessageReceiver;

/**
 * Shared formatting pipeline used by every module so output looks consistent.
 *
 * <p>Formatting flows: raw string &rarr; internal placeholders &rarr;
 * PlaceholderAPI &rarr; color/gradient/MiniMessage parsing &rarr; Hytale
 * {@link Message}. A receiver is anything that can accept a message, including a
 * {@code PlayerRef} or the console.</p>
 */
public interface MessageService {

    /** Runs the full pipeline on a raw string and returns a ready-to-send Hytale {@link Message}. */
    Message format(String raw);

    /** As {@link #format(String)} but substitutes {@code {key}} placeholders from {@code params} first. */
    Message format(String raw, Map<String, String> params);

    /** Full pipeline with a player context, so internal and PlaceholderAPI placeholders resolve for that player. */
    Message formatFor(UUID player, String raw);

    /** Placeholder stage only (internal + PlaceholderAPI) for a player context; no color parsing. */
    String resolvePlaceholders(UUID player, String raw);

    /** Color stage only (legacy/hex/gradient/MiniMessage) → {@link Message}; no placeholder resolution. */
    Message colorize(String raw);

    /** Looks up a message by key from the loaded message bundle, then formats it. */
    Message fromKey(String key);

    /** Looks up a message by key, substitutes params, then formats it. */
    Message fromKey(String key, Map<String, String> params);

    /** Looks up a message by key and returns plain text with colour/style markup removed. */
    String plainFromKey(String key);

    /** Looks up a message by key, substitutes params, and returns plain text with colour/style markup removed. */
    String plainFromKey(String key, Map<String, String> params);

    /** Convenience: formats {@code raw} and sends it to {@code receiver}. */
    void send(IMessageReceiver receiver, String raw);

    /** Convenience: formats the bundle message for {@code key} and sends it to {@code receiver}. */
    void sendKey(IMessageReceiver receiver, String key);

    /** Convenience: formats the bundle message for {@code key} with params and sends it. */
    void sendKey(IMessageReceiver receiver, String key, Map<String, String> params);

    /** The configured message prefix, already formatted (may be empty). */
    Message prefix();
}
