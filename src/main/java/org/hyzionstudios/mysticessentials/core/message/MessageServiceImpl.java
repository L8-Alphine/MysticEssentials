package org.hyzionstudios.mysticessentials.core.message;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.api.service.MessageService;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.util.Json;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.receiver.IMessageReceiver;

/**
 * Default {@link MessageService}. Owns the shared formatting pipeline and the
 * loaded message bundle so every module produces consistent output.
 *
 * <p>Pipeline order (per design): internal placeholders &rarr; PlaceholderAPI
 * &rarr; {@code {key}} params &rarr; color/gradient/MiniMessage parse &rarr; Hytale
 * {@link Message}. The placeholder stages are wired to the Core services; the
 * color stage is handled by {@link MysticText}, which builds a coloured
 * {@link Message} tree from legacy, hex, gradient, rainbow, and MiniMessage-style
 * markup.</p>
 */
public final class MessageServiceImpl implements MessageService {

    private final MysticCore core;
    private JsonObject bundle = new JsonObject();
    private String rawPrefix = "";

    public MessageServiceImpl(MysticCore core) {
        this.core = core;
    }

    /** Loads {@code messages/en_us.json}, writing defaults if missing. */
    public void load() {
        Path file = core.paths().messagesFile("en_us");
        try {
            JsonObject defaults = DefaultMessages.enUs();
            JsonElement loaded = Json.readFile(file);
            if (loaded == null) {
                bundle = defaults;
                Json.writeFile(file, bundle);
                core.log(Level.INFO, "Generated default messages/en_us.json");
            } else {
                bundle = Json.asObject(loaded);
                if (mergeMissing(bundle, defaults)) {
                    Json.writeFile(file, bundle);
                    core.log(Level.INFO, "Updated messages/en_us.json with missing default messages");
                }
            }
        } catch (IOException e) {
            core.log(Level.SEVERE, "Failed to load messages/en_us.json: " + e.getMessage());
            bundle = DefaultMessages.enUs();
        }
        rawPrefix = bundle.has("prefix") ? bundle.get("prefix").getAsString() : "";
    }

    @Override
    public Message format(String raw) {
        return format(raw, Map.of());
    }

    @Override
    public Message format(String raw, Map<String, String> params) {
        if (raw == null) {
            return Message.empty();
        }
        return colorize(fillParams(raw, params, text -> resolvePlaceholders(null, text)));
    }

    @Override
    public Message formatFor(UUID player, String raw) {
        if (raw == null) {
            return Message.empty();
        }
        return colorize(resolvePlaceholders(player, raw));
    }

    @Override
    public String resolvePlaceholders(UUID player, String raw) {
        return raw == null ? "" : core.getPlaceholderService().resolve(player, raw);
    }

    @Override
    public Message colorize(String raw) {
        // NOTE: Message.parse is a JSON reader, not a markup parser — build the
        // colour tree ourselves from legacy/hex/gradient/MiniMessage markup.
        return MysticText.parse(raw);
    }

    @Override
    public Message fromKey(String key) {
        return format(lookup(key));
    }

    @Override
    public Message fromKey(String key, Map<String, String> params) {
        return format(lookup(key), params);
    }

    @Override
    public String plainFromKey(String key) {
        return plainFromKey(key, Map.of());
    }

    @Override
    public String plainFromKey(String key, Map<String, String> params) {
        return MysticText.stripMarkup(
                fillParams(lookup(key), params, text -> resolvePlaceholders(null, text)));
    }

    @Override
    public void send(IMessageReceiver receiver, String raw) {
        receiver.sendMessage(format(raw));
    }

    @Override
    public void sendKey(IMessageReceiver receiver, String key) {
        receiver.sendMessage(fromKey(key));
    }

    @Override
    public void sendKey(IMessageReceiver receiver, String key, Map<String, String> params) {
        receiver.sendMessage(fromKey(key, params));
    }

    @Override
    public Message prefix() {
        return format(rawPrefix);
    }

    private String lookup(String key) {
        if (bundle.has(key) && bundle.get(key).isJsonPrimitive()) {
            return bundle.get(key).getAsString();
        }
        core.log(Level.WARNING, "Missing message key: " + key);
        return key;
    }

    /**
     * Resolves placeholders in {@code raw} and fills its {@code {key}} params, in that
     * order: {@code placeholders} only sees the template text around the param tokens,
     * and each value is inserted verbatim afterwards. Player text passed as a param (a
     * PM body, a mute reason, a nickname...) can therefore never expand a {@code {name}}
     * or {@code %name%} placeholder, nor another param's token, while a param still wins
     * over a placeholder of the same name. Values are not escaped: their colour markup
     * still renders when the result is colourized.
     */
    public static String fillParams(String raw, Map<String, String> params, UnaryOperator<String> placeholders) {
        if (params.isEmpty()) {
            return placeholders.apply(raw);
        }
        StringBuilder result = new StringBuilder(raw.length());
        int literalStart = 0;
        int open = raw.indexOf('{');
        while (open >= 0) {
            String value = null;
            int close = -1;
            for (Map.Entry<String, String> entry : params.entrySet()) {
                String token = "{" + entry.getKey() + "}";
                if (raw.startsWith(token, open)) {
                    value = entry.getValue();
                    close = open + token.length();
                    break;
                }
            }
            if (close < 0) {
                open = raw.indexOf('{', open + 1);
                continue;
            }
            result.append(placeholders.apply(raw.substring(literalStart, open))).append(value);
            literalStart = close;
            open = raw.indexOf('{', close);
        }
        return result.append(placeholders.apply(raw.substring(literalStart))).toString();
    }

    private static boolean mergeMissing(JsonObject target, JsonObject defaults) {
        boolean changed = false;
        for (Map.Entry<String, JsonElement> entry : defaults.entrySet()) {
            if (!target.has(entry.getKey())) {
                target.add(entry.getKey(), entry.getValue());
                changed = true;
            }
        }
        return changed;
    }
}
