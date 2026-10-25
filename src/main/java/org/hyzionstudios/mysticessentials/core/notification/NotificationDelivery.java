package org.hyzionstudios.mysticessentials.core.notification;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.api.notification.Notification;
import org.hyzionstudios.mysticessentials.api.notification.NotificationPriority;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.message.MysticText;

import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.protocol.packets.interface_.EventTitleStyle;
import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.util.EventTitleUtil;
import com.hypixel.hytale.server.core.util.NotificationUtil;

/**
 * Pushes one notification onto one player's screen across every enabled surface.
 *
 * <p>Each surface is independently guarded. A missing sound asset, an unopenable
 * HUD, or a packet failure costs that surface and nothing else — the point of a
 * critical alert is that it arrives, so one broken channel must never suppress
 * the other five.</p>
 *
 * <p>Surface mapping, all verified against the 0.6.2 server jar:</p>
 * <ul>
 *   <li><b>chat</b> — {@code PlayerRef.sendMessage}</li>
 *   <li><b>title / subtitle</b> — {@code EventTitleUtil.showEventTitleToPlayer}
 *       (the {@code ShowEventTitle} packet behind the engine's own event titles)</li>
 *   <li><b>toast</b> — {@code NotificationUtil.sendNotification}</li>
 *   <li><b>sound</b> — {@code SoundUtil.playSoundEvent2dToPlayer}</li>
 *   <li><b>action bar / banner</b> — custom HUD documents; see
 *       {@link NotificationHuds} for why Update 6's native boss bar does not
 *       serve this purpose</li>
 * </ul>
 */
final class NotificationDelivery {

    /** Event-title lines longer than this are cut; chat still carries the full text. */
    private static final int TITLE_MAX_CHARS = 48;
    private static final int SUBTITLE_MAX_CHARS = 110;

    private final MysticCore core;
    /** Latest push per player and HUD key; an older push's timer must not remove a newer HUD. */
    private final Map<String, Object> hudTokens = new ConcurrentHashMap<>();
    private volatile NotificationConfig config;

    NotificationDelivery(MysticCore core, NotificationConfig config) {
        this.core = core;
        this.config = config;
    }

    void updateConfig(NotificationConfig config) {
        this.config = config;
    }

    /**
     * Delivers {@code notification} to {@code player} on the surfaces
     * {@code profile} enables and {@code preferences} permits.
     *
     * @param overridePreferences whether this send outranks the recipient's
     *                            per-surface preferences — true for critical
     *                            priority and for an explicit staff override
     */
    void deliver(PlayerRef player, Notification notification, NotificationConfig.Category category,
            NotificationConfig.Profile profile, NotificationPreferences preferences,
            boolean overridePreferences) {
        boolean critical = overridePreferences;

        if (enabled(profile.chat, notification.showInChat(), preferences.chat, critical)) {
            chat(player, notification, category);
        }
        if (enabled(profile.title, notification.showAsTitle(), preferences.titles, critical)) {
            title(player, notification, profile);
        }
        if (enabled(profile.actionbar, notification.showAsActionBar(), preferences.actionBar,
                critical)) {
            actionBar(player, notification, profile);
        }
        if (enabled(profile.toast, notification.showAsToast(), preferences.toasts, critical)) {
            toast(player, notification);
        }
        if (enabled(profile.sound, notification.playSound(), preferences.sounds, critical)) {
            sound(player, notification, category);
        }
        if (enabled(profile.banner, notification.showAsBanner(), preferences.banners, critical)) {
            banner(player, notification, category, profile);
        }
    }

    /**
     * Resolves one surface's on/off state.
     *
     * <p>Precedence is: the sender's explicit override, then the profile, then
     * the player's preference — except for critical notifications, where the
     * player's preference is bypassed unless the server allows it. That last
     * clause is the reason this decision lives in one method instead of being
     * repeated at six call sites.</p>
     */
    private boolean enabled(boolean profileValue, Optional<Boolean> override,
            boolean preference, boolean overridePreferences) {
        boolean wanted = override.orElse(profileValue);
        if (!wanted) {
            return false;
        }
        // An overriding send still respects allow-player-disable, so a server that
        // has explicitly handed control to players keeps that promise even here.
        if (overridePreferences && !config.critical.allowPlayerDisable) {
            return true;
        }
        return preference;
    }

    // ----- Surfaces ------------------------------------------------------------------

    private void chat(PlayerRef player, Notification notification,
            NotificationConfig.Category category) {
        try {
            // A sender that owns its own configured prefix keeps it; otherwise the
            // category supplies one.
            String prefix = notification.chatPrefix()
                    .orElse(category.chatPrefix == null ? "" : category.chatPrefix);
            String body = notification.bestText();
            if (body.isBlank()) {
                return;
            }
            player.sendMessage(core.getMessageService().format(prefix + body));
        } catch (Throwable t) {
            logSurfaceFailure("chat", t);
        }
    }

    private void title(PlayerRef player, Notification notification,
            NotificationConfig.Profile profile) {
        try {
            String primary = titleLine(notification.title().orElse(null), TITLE_MAX_CHARS);
            String secondary = titleLine(
                    notification.subtitle().or(notification::message).orElse(null),
                    SUBTITLE_MAX_CHARS);
            if (primary.isEmpty() && secondary.isEmpty()) {
                // Nothing headline-worthy; a title with no text is just a flash.
                return;
            }
            if (primary.isEmpty()) {
                primary = secondary;
                secondary = "";
            }
            // Both lines go out as single plain raw messages. Two engine facts
            // (0.7.2) force this: showEventTitleToPlayer calls
            // secondaryTitle.getFormattedMessage() unguarded, so a null secondary
            // throws and the whole title is lost; and vanilla only ever sends one
            // flat Message per line (WorldMapTracker#onZoneDiscovered), while
            // MysticText renders coloured markup as an empty root with children,
            // which is the line that came out blank in game.
            EventTitleUtil.showEventTitleToPlayer(player, Message.raw(primary),
                    Message.raw(secondary), titleStyle(notification.priority()),
                    notification.icon().orElse(EventTitleUtil.DEFAULT_ZONE),
                    profile.stayMillis / 1000f,
                    profile.fadeInMillis / 1000f,
                    profile.fadeOutMillis / 1000f);
        } catch (Throwable t) {
            logSurfaceFailure("title", t);
        }
    }

    /**
     * Only a critical notice earns the large Major presentation; everything up
     * to important reads at the Default size. GoblinBreach and VoidEviction are
     * themed for those vanilla world events and are not used for server text.
     */
    private static EventTitleStyle titleStyle(NotificationPriority priority) {
        return priority.atLeast(NotificationPriority.CRITICAL)
                ? EventTitleStyle.Major : EventTitleStyle.Default;
    }

    /** One plain line: markup stripped, line breaks folded, overlong text cut. */
    private static String titleLine(String markup, int maxChars) {
        if (markup == null) {
            return "";
        }
        String text = MysticText.stripMarkup(markup)
                .replaceAll("\\s*\\R\\s*", " ")
                .trim();
        if (text.length() > maxChars) {
            text = text.substring(0, maxChars - 1).trim() + "\u2026";
        }
        return text;
    }

    private void actionBar(PlayerRef player, Notification notification,
            NotificationConfig.Profile profile) {
        String text = MysticText.stripMarkup(notification.bestText());
        if (text.isBlank()) {
            return;
        }
        try {
            core.platform().showHud(player, new NotificationHuds.ActionBar(player, text));
            // The action bar is transient by definition — schedule its own removal
            // rather than leaving the last notice pinned above the hotbar forever.
            long seconds = Math.max(1, profile.stayMillis / 1000);
            removeHudLater(player, NotificationHuds.ACTION_BAR_KEY, seconds);
        } catch (Throwable t) {
            logSurfaceFailure("action bar", t);
        }
    }

    private void toast(PlayerRef player, Notification notification) {
        try {
            Message primary = messageOrNull(notification.title().orElse(notification.bestText()));
            if (primary == null) {
                return;
            }
            Message secondary = notification.title().isPresent()
                    ? messageOrNull(notification.message().orElse(null))
                    : null;
            NotificationStyle style = toastStyle(notification.priority());
            if (secondary == null) {
                NotificationUtil
                        .sendNotification(player.getPacketHandler(), primary, style);
            } else {
                NotificationUtil
                        .sendNotification(player.getPacketHandler(), primary, secondary, style);
            }
        } catch (Throwable t) {
            logSurfaceFailure("toast", t);
        }
    }

    private static NotificationStyle toastStyle(NotificationPriority priority) {
        return switch (priority) {
            case CRITICAL -> NotificationStyle.Danger;
            case IMPORTANT -> NotificationStyle.Warning;
            default -> NotificationStyle.Default;
        };
    }

    private void sound(PlayerRef player, Notification notification,
            NotificationConfig.Category category) {
        String soundId = notification.sound().orElse(category.sound);
        if (soundId == null || soundId.isBlank()) {
            return;
        }
        try {
            int index = SoundEvent.getAssetMap().getIndexOrDefault(soundId, -1);
            if (index < 0) {
                // Sound assets differ per server; a name this pack does not ship
                // is a config problem, not a delivery failure.
                core.log(Level.FINE, "[notifications] Unknown sound event '" + soundId + "'.");
                return;
            }
            SoundUtil.playSoundEvent2dToPlayer(player, index, SoundCategory.UI, 1.0f, 1.0f);
        } catch (Throwable t) {
            logSurfaceFailure("sound", t);
        }
    }

    /**
     * Pins the banner and schedules its removal. A countdown-style alert simply
     * re-sends with a smaller remaining fraction; the HUD key is stable, so each
     * push replaces the previous one rather than stacking.
     */
    private void banner(PlayerRef player, Notification notification,
            NotificationConfig.Category category, NotificationConfig.Profile profile) {
        // The banner carries the notice itself. Showing only the title is what put
        // a bare server name on an otherwise empty bar.
        String title = titleLine(notification.title().orElse(null), TITLE_MAX_CHARS);
        String body = titleLine(notification.message().or(notification::subtitle).orElse(null),
                SUBTITLE_MAX_CHARS);
        String text = title.isEmpty() || body.isEmpty() || body.equals(title)
                ? (body.isEmpty() ? title : body)
                : title + " \u2014 " + body;
        if (text.isBlank()) {
            return;
        }
        long seconds = notification.duration()
                .map(Duration::toSeconds)
                .orElse((long) profile.durationSeconds);
        seconds = Math.max(1, seconds);
        // A banner the player cannot dismiss says so, so nobody spends the
        // countdown hunting for a close button that was never there.
        String hint = notification.dismissible() && profile.dismissible ? "" : "Required";
        try {
            core.platform().showHud(player,
                    new NotificationHuds.Banner(player, text, hint, safeColor(category.accent), 1.0));
            removeHudLater(player, NotificationHuds.BANNER_KEY, seconds);
        } catch (Throwable t) {
            logSurfaceFailure("banner", t);
        }
    }

    /**
     * Removes the HUD under {@code key} after {@code seconds}, unless it was
     * pushed again meanwhile: the key is shared, so the older timer would
     * otherwise take down the newer action bar or banner early.
     */
    private void removeHudLater(PlayerRef player, String key, long seconds) {
        String slot = player.getUuid() + "/" + key;
        Object token = new Object();
        hudTokens.put(slot, token);
        core.scheduler().runLater(() -> {
            if (hudTokens.remove(slot, token)) {
                core.platform().removeHud(player, key);
            }
        }, seconds, TimeUnit.SECONDS);
    }

    /** Removes a pinned banner ahead of its scheduled expiry. */
    void clearBanner(PlayerRef player) {
        try {
            core.platform().removeHud(player, NotificationHuds.BANNER_KEY);
        } catch (Throwable ignored) {
            // The banner clears itself on its timer regardless.
        }
    }

    // ----- Helpers ----------------------------------------------------------------------

    private Message messageOrNull(String markup) {
        if (markup == null || markup.isBlank()) {
            return null;
        }
        return core.getMessageService().format(markup);
    }

    private static String safeColor(String color) {
        return color == null || !color.matches("(?i)#[0-9a-f]{6}") ? "#7a9cc6" : color;
    }

    private void logSurfaceFailure(String surface, Throwable error) {
        core.log(Level.FINE, "[notifications] " + surface + " delivery failed: " + error);
    }
}
