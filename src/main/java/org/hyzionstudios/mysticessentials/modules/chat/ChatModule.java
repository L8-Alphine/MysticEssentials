package org.hyzionstudios.mysticessentials.modules.chat;

import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.hyzionstudios.mysticessentials.api.service.ChatService;
import org.hyzionstudios.mysticessentials.core.module.AbstractMysticModule;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.event.events.player.PlayerChatEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Root chat module. Owns the original public chat event pipeline and delegates
 * private messaging, channel routing, and custom glyph replacement to focused
 * submodules.
 */
public final class ChatModule extends AbstractMysticModule implements ChatService {

    private static final Pattern PLAIN_URL = Pattern.compile("(?i)(?<!:)\\bhttps?://[^\\s<>]+");

    private ChatConfig config;
    private ChatGlyphSubModule glyphs;
    private PrivateMessagingSubModule privateMessaging;
    private ChannelsSubModule channels;

    public ChatModule() {
        super("chat", "Chat", "1.0.0");
    }

    @Override
    public void onEnable() {
        config = core.configManager().loadModuleConfig(id(), ChatConfig.class, new ChatConfig());
        normalizeConfig();
        glyphs = new ChatGlyphSubModule(core);
        privateMessaging = new PrivateMessagingSubModule(core, this);
        channels = new ChannelsSubModule(core, this);

        glyphs.reload(config.glyphs);
        privateMessaging.enable(config.privateMessaging, this::registerCommand);
        channels.enable(config.channels, this::registerCommand);

        if (config.formatChat) {
            registerAsyncEvent(PlayerChatEvent.class,
                    future -> future.thenApply(this::applyChatPipeline));
        }
        log("Enabled chat submodules: privateMessaging=" + config.privateMessaging.enabled
                + ", channels=" + config.channels.enabled
                + ", glyphs=" + config.glyphs.enabled
                + " (assetsRegistered=" + glyphs.assetsRegistered()
                + ", emojiSequences=" + glyphs.emojiSequenceCount() + ")");
    }

    @Override
    public void onReload() {
        config = core.configManager().loadModuleConfig(id(), ChatConfig.class, new ChatConfig());
        normalizeConfig();
        if (glyphs != null) {
            glyphs.reload(config.glyphs);
        }
        if (privateMessaging != null) {
            privateMessaging.reload(config.privateMessaging);
        }
        if (channels != null) {
            channels.reload(config.channels);
        }
    }

    private void normalizeConfig() {
        ChatConfig defaults = new ChatConfig();
        if (config == null) {
            config = defaults;
            return;
        }
        if (config.defaultFormat == null) {
            config.defaultFormat = defaults.defaultFormat;
        }
        config.defaultFormat = preferDisplayName(config.defaultFormat);
        if (config.autoLinkPlainUrls == null) {
            config.autoLinkPlainUrls = defaults.autoLinkPlainUrls;
        }
        if (config.formats == null) {
            config.formats = defaults.formats;
        } else {
            config.formats.forEach(format -> {
                if (format != null) {
                    format.format = preferDisplayName(format.format);
                }
            });
        }
        if (config.messageColorPermissions == null) {
            config.messageColorPermissions = defaults.messageColorPermissions;
        } else {
            defaults.messageColorPermissions.forEach(config.messageColorPermissions::putIfAbsent);
        }
        if (config.privateMessaging == null) {
            config.privateMessaging = defaults.privateMessaging;
        }
        if (config.glyphs == null) {
            config.glyphs = defaults.glyphs;
        } else if (config.glyphs.permissions == null) {
            config.glyphs.permissions = defaults.glyphs.permissions;
        } else {
            defaults.glyphs.permissions.forEach(config.glyphs.permissions::putIfAbsent);
        }
        if (config.channels == null) {
            config.channels = defaults.channels;
        } else if (config.channels.channels == null) {
            config.channels.channels = defaults.channels.channels;
        }
        if (config.channels.defaultSpeak == null || config.channels.defaultSpeak.isBlank()) {
            config.channels.defaultSpeak = defaults.channels.defaultSpeak;
        }
        if (config.channels.channels != null) {
            config.channels.channels.forEach(channel -> {
                channel.format = preferDisplayName(channel.format);
                if (channel.groupFormats != null) {
                    channel.groupFormats.replaceAll((group, format) -> preferDisplayName(format));
                }
            });
        }
    }

    private static String preferDisplayName(String format) {
        return format == null ? null : format.replace("{player_name}", "{display_name}");
    }

    @Override
    public void onDisable() {
        if (privateMessaging != null) {
            privateMessaging.disable();
        }
        if (channels != null) {
            channels.disable();
        }
    }

    // ----- Chat formatting ---------------------------------------------------

    private PlayerChatEvent applyChatPipeline(PlayerChatEvent event) {
        if (event.isCancelled()) {
            return event;
        }
        PlayerRef sender = event.getSender();
        event.setContent(preparePlayerMessage(sender, event.getContent()));
        if (channels != null) {
            event = channels.route(event);
        }
        if (!event.isCancelled()) {
            event.setFormatter(this::renderChatLine);
        }
        return event;
    }

    String preparePlayerMessage(PlayerRef sender, String raw) {
        String result = glyphs == null ? raw : glyphs.apply(sender, raw);
        result = sanitizeColors(sender, result);
        return enforceLength(result);
    }

    private Message renderChatLine(PlayerRef sender, String content) {
        UUID uuid = sender.getUuid();
        String template = channels == null
                ? resolveFormat(uuid)
                : channels.formatFor(uuid).orElse(resolveFormat(uuid));
        // {display_name} honours a nickname set by the Nick module (profile
        // metadata), falling back to the real username.
        String displayName = core.getPlayerProfileService().getCached(uuid)
                .map(p -> p.getMetadata().get("nickname"))
                .filter(nick -> nick != null && !nick.isBlank())
                .orElse(sender.getUsername());
        template = template
                .replace("{player_name}", sender.getUsername())
                .replace("{display_name}", displayName)
                .replace("{channel}", channels == null
                        ? "global"
                        : channels.displayNameFor(uuid).orElse(channels.currentChannel(uuid)));
        String resolved = core.getMessageService().resolvePlaceholders(uuid, template);
        String message = content == null ? "" : content;
        if (Boolean.TRUE.equals(config.autoLinkPlainUrls) && allows(sender, config.autoLinkPermission)) {
            message = autoLinkPlainUrls(message);
        }
        String line = resolved.replace("{message}", message);
        return core.getMessageService().colorize(line);
    }

    private String sanitizeColors(PlayerRef sender, String content) {
        Map<String, String> perms = config.messageColorPermissions;
        boolean legacy = allows(sender, perms.get("legacy"));
        boolean hex = allows(sender, perms.get("hex"));
        boolean gradient = allows(sender, perms.get("gradient"));
        boolean rainbow = allows(sender, perms.get("rainbow"));
        boolean minimessage = allows(sender, perms.get("minimessage"));
        boolean links = allows(sender, perms.get("links"));
        return ChatColors.sanitize(content, legacy, hex, gradient, rainbow, minimessage, links);
    }

    private boolean allows(PlayerRef sender, String permission) {
        return sender == null || permission == null || permission.isBlank() || sender.hasPermission(permission);
    }

    private String enforceLength(String value) {
        if (value == null || config.maxMessageLength <= 0) {
            return value;
        }
        int max = config.maxMessageLength;
        if (value.codePointCount(0, value.length()) <= max) {
            return value;
        }
        int end = value.offsetByCodePoints(0, max);
        return value.substring(0, end);
    }

    private String autoLinkPlainUrls(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        Matcher matcher = PLAIN_URL.matcher(value);
        StringBuilder result = new StringBuilder(value.length());
        while (matcher.find()) {
            String url = trimTrailingUrlPunctuation(matcher.group());
            String trailing = matcher.group().substring(url.length());
            matcher.appendReplacement(result, Matcher.quoteReplacement("<link:" + url + ">" + url + "</link>" + trailing));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String trimTrailingUrlPunctuation(String url) {
        while (!url.isEmpty() && ".,;:!?)\"]}".indexOf(url.charAt(url.length() - 1)) >= 0) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    // ----- ChatService -------------------------------------------------------

    @Override
    public String resolveFormat(UUID player) {
        if (config != null && config.formats != null) {
            return config.formats.stream()
                    .filter(f -> f.format != null)
                    .filter(f -> player != null && (f.permission == null
                            || core.getPermissionService().has(player, f.permission)))
                    .max(Comparator.comparingInt(f -> f.priority))
                    .map(f -> f.format)
                    .orElse(config.defaultFormat);
        }
        return config != null ? config.defaultFormat : "{player_name} &8» &f{message}";
    }

    @Override
    public CompletableFuture<Boolean> privateMessage(UUID from, UUID to, String message) {
        return privateMessaging == null
                ? CompletableFuture.completedFuture(false)
                : privateMessaging.privateMessage(from, to, message);
    }

    @Override
    public String currentChannel(UUID player) {
        return channels == null ? "global" : channels.currentChannel(player);
    }

    @Override
    public boolean setChannel(UUID player, String channelId) {
        return channels != null && channels.setChannel(player, channelId);
    }

    @Override
    public String applyGlyphs(UUID player, String raw) {
        return glyphs == null ? raw : glyphs.apply(player, raw);
    }

    @Override
    public boolean createTemporaryChannel(UUID owner, String channelId, String permissionGate) {
        return channels != null && channels.createTemporaryChannel(owner, channelId, permissionGate);
    }
}
