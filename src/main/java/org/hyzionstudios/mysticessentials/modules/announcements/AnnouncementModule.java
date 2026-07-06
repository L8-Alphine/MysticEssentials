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
import org.hyzionstudios.mysticessentials.api.service.AnnouncementService;
import org.hyzionstudios.mysticessentials.core.module.AbstractMysticModule;
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
        Message formatted = core.getMessageService().format(message);
        for (PlayerRef player : core.platform().onlinePlayers()) {
            player.sendMessage(formatted);
        }
    }

    @Override
    public void broadcastToWorld(String world, String message) {
        Message formatted = core.getMessageService().format(message);
        for (PlayerRef player : core.platform().onlinePlayers()) {
            String playerWorld = Conversions.resolveWorldName(player.getWorldUuid());
            if (world.equals(playerWorld)) {
                player.sendMessage(formatted);
            }
        }
    }

    @Override
    public void broadcastToPermission(String permission, String message) {
        Message formatted = core.getMessageService().format(message);
        for (PlayerRef player : core.platform().onlinePlayers()) {
            if (player.hasPermission(permission)) {
                player.sendMessage(formatted);
            }
        }
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
        List<Message> formatted = new ArrayList<>(announcement.lines.size());
        for (AutoLine line : announcement.lines) {
            formatted.add(core.getMessageService().format(line.render()));
        }
        for (PlayerRef player : core.platform().onlinePlayers()) {
            for (Message line : formatted) {
                player.sendMessage(line);
            }
        }
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
            broadcast(prefixed(config.broadcastPrefix, sender.get(message)));
        }
    }

    /** {@code /alert} — like {@code /broadcast} but with its own attention-grabbing prefix. */
    private final class AlertCommand extends MysticCommand {
        private final RequiredArg<String> message =
                withRequiredArg("message", "Alert message", ArgTypes.GREEDY_STRING);

        AlertCommand() {
            super(AnnouncementModule.this.core, "alert", "Broadcast an alert to the server.");
            requirePermission(org.hyzionstudios.mysticessentials.api.Permissions.ANNOUNCEMENT_ALERT);
        }

        @Override
        protected void run(MysticCommandSender sender) {
            broadcast(prefixed(config.alertPrefix, sender.get(message)));
        }
    }

    private static String prefixed(String prefix, String message) {
        return prefix == null || prefix.isBlank() ? message : prefix + message;
    }
}
