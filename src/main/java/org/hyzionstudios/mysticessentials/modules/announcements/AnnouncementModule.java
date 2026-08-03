package org.hyzionstudios.mysticessentials.modules.announcements;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ThreadLocalRandom;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.hyzionstudios.mysticessentials.api.notification.Notification;
import org.hyzionstudios.mysticessentials.api.notification.NotificationAction;
import org.hyzionstudios.mysticessentials.api.notification.NotificationAudience;
import org.hyzionstudios.mysticessentials.api.notification.NotificationCategory;
import org.hyzionstudios.mysticessentials.api.notification.NotificationPriority;
import org.hyzionstudios.mysticessentials.api.service.AnnouncementService;
import org.hyzionstudios.mysticessentials.core.module.AbstractMysticModule;
import org.hyzionstudios.mysticessentials.core.notification.NotificationServiceImpl;
import org.hyzionstudios.mysticessentials.platform.Conversions;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommand;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommandSender;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Broadcast-style messaging: manual {@code /broadcast}, auto-rotating broadcasts
 * on a scheduled interval, and world/permission-targeted sends. Cross-server
 * broadcasts are a later Redis phase (fail-safe local-only until then).
 */
public final class AnnouncementModule extends AbstractMysticModule implements AnnouncementService {

    private static final String CHANNEL = "broadcast";

    private AnnouncementConfig config;
    private List<AutoAnnouncement> autoAnnouncements = List.of();
    private ScheduledFuture<?> autoTask;
    private final AtomicInteger rotationIndex = new AtomicInteger();

    public AnnouncementModule() {
        super("announcements", "Announcements", "1.0.0");
    }

    @Override
    public void onEnable() {
        loadConfig();
        registerCommand(new BroadcastCommand());
        registerCommand(new AlertCommand());
        // Cross-server broadcasts: receive network broadcasts and show them locally.
        if (core.redis().isEnabled()) {
            core.redis().subscribe(CHANNEL, this::broadcastLocal);
        }
        if (config.autoBroadcastEnabled) {
            startAutoBroadcast();
        }
    }

    @Override
    public void onReload() {
        stopAutoBroadcast();
        loadConfig();
        if (config.autoBroadcastEnabled) {
            startAutoBroadcast();
        }
    }

    @Override
    public void onDisable() {
        stopAutoBroadcast();
    }

    // ----- AnnouncementService -----------------------------------------------

    @Override
    public void broadcast(String message) {
        broadcastLocal(message);
        // Propagate network-wide; echo-suppressed so this server does not double-show it.
        core.redis().publish(CHANNEL, message);
    }

    /** Shows a broadcast to this server's players only. */
    private void broadcastLocal(String message) {
        sendAnnouncement(message, NotificationAudience.all());
    }

    @Override
    public void broadcastToWorld(String world, String message) {
        sendAnnouncement(message, NotificationAudience.world(world));
    }

    @Override
    public void broadcastToPermission(String permission, String message) {
        sendAnnouncement(message, NotificationAudience.permission(permission));
    }

    @Override
    public void startAutoBroadcast() {
        if (autoTask != null || autoAnnouncements.isEmpty()) {
            return;
        }
        long interval = Math.max(5, config.intervalSeconds);
        autoTask = core.scheduler().runRepeating(this::broadcastNext, interval, interval, TimeUnit.SECONDS);
        log("Auto-broadcast started (" + interval + "s interval).");
    }

    @Override
    public void stopAutoBroadcast() {
        if (autoTask != null) {
            autoTask.cancel(false);
            autoTask = null;
        }
    }

    private void broadcastNext() {
        if (autoAnnouncements.isEmpty() || core.platform().onlinePlayers().isEmpty()) {
            return;
        }
        int index = config.randomOrder
                ? ThreadLocalRandom.current().nextInt(autoAnnouncements.size())
                : Math.floorMod(rotationIndex.getAndIncrement(), autoAnnouncements.size());
        // Auto-broadcasts stay local: each server runs its own rotation.
        broadcastLocal(autoAnnouncements.get(index));
    }

    private void loadConfig() {
        config = core.configManager().loadModuleConfig(id(), AnnouncementConfig.class, new AnnouncementConfig());
        autoAnnouncements = parseAutoAnnouncements(config.messages);
    }

    private void broadcastLocal(AutoAnnouncement announcement) {
        String message = announcement.lines.stream()
                .map(AutoLine::render)
                .collect(java.util.stream.Collectors.joining("\n"));
        Notification.Builder builder = announcement(config.broadcastTitle, message,
                config.broadcastSound).chatPrefix(orEmpty(config.broadcastPrefix));
        announcement.lines.stream().map(line -> line.clickTarget)
                .filter(java.util.Objects::nonNull).findFirst()
                .map(AnnouncementModule::actionFor)
                .ifPresent(builder::action);
        send(builder.build(), NotificationAudience.all());
    }

    private void sendAnnouncement(String message, NotificationAudience audience) {
        send(announcement(config.broadcastTitle, message, config.broadcastSound)
                .chatPrefix(orEmpty(config.broadcastPrefix))
                .build(), audience);
    }

    private Notification.Builder announcement(String title, String message, String sound) {
        return Notification.builder()
                .category(NotificationCategory.ANNOUNCEMENT)
                .priority(NotificationPriority.NORMAL)
                .title(title)
                .subtitle(message)
                .message(message)
                .sound(sound)
                .showAsTitle(true)
                .source("mysticessentials:announcements");
    }

    private void send(Notification notification, NotificationAudience audience) {
        NotificationServiceImpl notifications = core.notifications();
        if (notifications != null) {
            notifications.send(notification, audience);
            return;
        }
        // Core normally creates the engine before modules. Preserve chat output
        // if an unusual partial-startup state leaves it unavailable.
        Message formatted = core.getMessageService().format(
                notification.chatPrefix().orElse("") + notification.bestText());
        for (PlayerRef player : core.platform().onlinePlayers()) {
            if (audience.kind() == NotificationAudience.Kind.ALL
                    || audience.kind() == NotificationAudience.Kind.PERMISSION
                            && player.hasPermission(audience.value())
                    || audience.kind() == NotificationAudience.Kind.WORLD
                            && audience.value().equals(Conversions.resolveWorldName(player.getWorldUuid()))) {
                player.sendMessage(formatted);
            }
        }
    }

    private static NotificationAction actionFor(String target) {
        return target.startsWith("/")
                ? NotificationAction.command(target)
                : NotificationAction.url(target);
    }

    private List<AutoAnnouncement> parseAutoAnnouncements(List<JsonElement> configured) {
        if (configured == null || configured.isEmpty()) {
            return List.of();
        }
        List<AutoAnnouncement> parsed = new ArrayList<>();
        for (JsonElement element : configured) {
            AutoAnnouncement announcement = parseAutoAnnouncement(element);
            if (announcement != null && !announcement.lines.isEmpty()) {
                parsed.add(announcement);
            }
        }
        return List.copyOf(parsed);
    }

    private AutoAnnouncement parseAutoAnnouncement(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            return new AutoAnnouncement(linesFromText(element.getAsString(), null));
        }
        if (!element.isJsonObject()) {
            return null;
        }
        JsonObject object = element.getAsJsonObject();
        String inheritedClick = parseClick(object, null);
        List<AutoLine> lines = new ArrayList<>();
        JsonElement linesElement = object.get("lines");
        if (linesElement != null && linesElement.isJsonArray()) {
            JsonArray array = linesElement.getAsJsonArray();
            for (JsonElement lineElement : array) {
                lines.addAll(parseLineElement(lineElement, inheritedClick));
            }
        } else {
            String text = firstString(object, "message", "text", "line");
            if (text != null) {
                lines.addAll(linesFromText(text, inheritedClick));
            }
        }
        return new AutoAnnouncement(lines);
    }

    private List<AutoLine> parseLineElement(JsonElement element, String inheritedClick) {
        if (element == null || element.isJsonNull()) {
            return List.of();
        }
        if (element.isJsonPrimitive()) {
            return linesFromText(element.getAsString(), inheritedClick);
        }
        if (!element.isJsonObject()) {
            return List.of();
        }
        JsonObject object = element.getAsJsonObject();
        String text = firstString(object, "text", "message", "line");
        if (text == null) {
            return List.of();
        }
        return linesFromText(text, parseClick(object, inheritedClick));
    }

    private List<AutoLine> linesFromText(String text, String clickTarget) {
        if (text == null) {
            return List.of();
        }
        String[] split = text.split("\\R", -1);
        List<AutoLine> lines = new ArrayList<>(split.length);
        for (String line : split) {
            lines.add(new AutoLine(line, clickTarget));
        }
        return lines;
    }

    private String parseClick(JsonObject object, String fallback) {
        String direct = firstString(object, "command", "url", "link", "href");
        if (direct != null) {
            return normalizeClickTarget(object.has("command") ? "command" : "link", direct);
        }
        JsonElement click = object.get("click");
        if (click == null || click.isJsonNull()) {
            return fallback;
        }
        if (click.isJsonPrimitive()) {
            return normalizeClickTarget(null, click.getAsString());
        }
        if (!click.isJsonObject()) {
            return fallback;
        }
        JsonObject clickObject = click.getAsJsonObject();
        String action = firstString(clickObject, "action", "type");
        String value = firstString(clickObject, "value", "target", "command", "url", "link", "href");
        if (value == null) {
            return fallback;
        }
        if (action == null) {
            action = clickObject.has("command") ? "command" : "link";
        }
        return normalizeClickTarget(action, value);
    }

    private String normalizeClickTarget(String action, String value) {
        if (value == null) {
            return null;
        }
        String target = value.trim();
        if (target.isEmpty() || target.indexOf('>') >= 0) {
            return null;
        }
        String normalizedAction = action == null ? "" : action.toLowerCase();
        if (normalizedAction.contains("command") && !target.startsWith("/")) {
            return "/" + target;
        }
        return target;
    }

    private String firstString(JsonObject object, String... names) {
        for (String name : names) {
            JsonElement value = object.get(name);
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                return value.getAsString();
            }
        }
        return null;
    }

    private static final class AutoAnnouncement {
        final List<AutoLine> lines;

        AutoAnnouncement(List<AutoLine> lines) {
            this.lines = List.copyOf(lines);
        }
    }

    private static final class AutoLine {
        final String text;
        final String clickTarget;

        AutoLine(String text, String clickTarget) {
            this.text = text == null ? "" : text;
            this.clickTarget = clickTarget;
        }

        String render() {
            if (clickTarget == null) {
                return text;
            }
            return "<link:" + clickTarget + ">" + text + "</link>";
        }
    }

    // ----- Command -----------------------------------------------------------

    /**
     * {@code /broadcast [category] [priority] <message> [flags]} — a server-wide
     * notice through the shared notification engine.
     *
     * <p>Routing this through the engine rather than a bare chat loop is what
     * makes a broadcast reach the action bar and the notification history, honour
     * do-not-disturb, and be targetable at a world or channel — none of which the
     * old chat-only implementation could do. A plain
     * {@code /broadcast Hello everyone} still behaves exactly as it always
     * did.</p>
     */
    private final class BroadcastCommand extends MysticCommand {
        private final RequiredArg<String> message =
                withRequiredArg("message", "Message to broadcast", ArgTypes.GREEDY_STRING);

        BroadcastCommand() {
            super(AnnouncementModule.this.core, "broadcast", "Broadcast a message to the server.");
            addAliases("bc");
            requirePermission(org.hyzionstudios.mysticessentials.api.Permissions.ANNOUNCEMENT_BROADCAST);
        }

        @Override
        protected void run(MysticCommandSender sender) {
            dispatch(sender, sender.get(message), NotificationCategory.ANNOUNCEMENT,
                    NotificationPriority.NORMAL, config.broadcastPrefix);
        }
    }

    /**
     * {@code /alert [category] [priority] <message> [flags]} — the same engine at
     * a higher default priority, so an alert takes the screen instead of scrolling
     * past in chat.
     */
    private final class AlertCommand extends MysticCommand {
        private final RequiredArg<String> message =
                withRequiredArg("message", "Alert message", ArgTypes.GREEDY_STRING);

        AlertCommand() {
            super(AnnouncementModule.this.core, "alert", "Send an alert to the server.");
            requirePermission(org.hyzionstudios.mysticessentials.api.Permissions.ANNOUNCEMENT_ALERT);
        }

        @Override
        protected void run(MysticCommandSender sender) {
            dispatch(sender, sender.get(message), NotificationCategory.WARNING,
                    NotificationPriority.IMPORTANT, config.alertPrefix);
        }
    }

    /**
     * Parses and sends one broadcast or alert.
     *
     * <p>Critical priority needs its own permission on top of the command's. A
     * critical alert bypasses every player's preferences and pins a banner, so
     * being trusted to announce an event is not the same as being trusted to
     * override everyone's settings.</p>
     */
    private void dispatch(MysticCommandSender sender, String input,
            NotificationCategory defaultCategory, NotificationPriority defaultPriority,
            String configuredPrefix) {
        NotificationServiceImpl notifications = core.notifications();
        if (notifications == null) {
            // The engine should always be present, but a broadcast is exactly the
            // wrong thing to lose to an initialisation edge case — fall back to the
            // module's own plain chat path so the message still goes out.
            log("Notification engine unavailable; broadcasting '" + input + "' as plain chat.");
            broadcast(prefixed(configuredPrefix, input));
            return;
        }
        AlertArguments.Parsed parsed = AlertArguments.parse(input, defaultCategory, defaultPriority,
                notifications.config().categories.keySet(), "mysticessentials:announcements");
        if (!parsed.ok()) {
            sender.reply("&c" + parsed.error());
            return;
        }
        // Gate on the priority the notification will ACTUALLY be sent at, not the
        // one that was typed. A category configured with a critical floor raises
        // any send through it, so checking the parsed priority alone would let
        // `/broadcast emergency ...` reach the critical surfaces ungated.
        NotificationPriority effective = NotificationPriority.parse(
                notifications.config().category(parsed.notification().category().id())
                        .minimumPriority, NotificationPriority.LOW);
        boolean critical = parsed.notification().priority().atLeast(NotificationPriority.CRITICAL)
                || effective.atLeast(NotificationPriority.CRITICAL);
        if (critical && !sender.hasPermission(
                org.hyzionstudios.mysticessentials.api.Permissions.NOTIFICATIONS_CRITICAL)) {
            sender.reply("&cSending critical alerts requires "
                    + org.hyzionstudios.mysticessentials.api.Permissions.NOTIFICATIONS_CRITICAL
                    + ".");
            return;
        }
        // A server's configured broadcastPrefix/alertPrefix keeps applying to the
        // plain command form, so upgrading does not silently retag every broadcast
        // with the category's prefix instead. Naming a category explicitly is a
        // request for that category's own prefix, so it wins.
        boolean alert = defaultPriority.atLeast(NotificationPriority.IMPORTANT);
        Notification.Builder presentation = parsed.notification().toBuilder();
        if (parsed.notification().title().isEmpty()) {
            presentation.title(alert ? config.alertTitle : config.broadcastTitle);
        }
        if (parsed.notification().subtitle().isEmpty()
                && parsed.notification().message().isPresent()) {
            presentation.subtitle(parsed.notification().message().orElseThrow());
        }
        if (parsed.notification().sound().isEmpty()) {
            presentation.sound(alert ? config.alertSound : config.broadcastSound);
        }
        // Both commands are intentional screen-level notices. EventTitleUtil in
        // NotificationDelivery is Hytale's built-in title system.
        presentation.showAsTitle(true);
        if (!parsed.categoryExplicit()) {
            presentation.chatPrefix(orEmpty(configuredPrefix));
        }
        Notification notification = presentation.build();

        notifications.send(notification, parsed.audience());
        sender.reply("&aSent a " + notification.priority().id() + " "
                + notification.category() + " notification to " + parsed.audience() + ".");
    }

    private static String prefixed(String prefix, String message) {
        return prefix == null || prefix.isBlank() ? message : prefix + message;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
