package org.hyzionstudios.mysticessentials.core.playerlist;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.api.service.AfkService;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.config.MainConfig;
import org.hyzionstudios.mysticessentials.core.message.MysticText;

import com.hypixel.hytale.event.EventPriority;
import com.hypixel.hytale.protocol.packets.interface_.AddToServerPlayerList;
import com.hypixel.hytale.protocol.packets.interface_.RemoveFromServerPlayerList;
import com.hypixel.hytale.protocol.packets.interface_.ServerPlayerListPlayer;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Puts rank prefixes, suffixes, and an AFK marker on the names in the client's
 * <b>Server Players</b> list (the roster on the map screen).
 *
 * <p>The engine's own {@code ServerPlayerListModule} builds each row from
 * {@code PlayerRef.getUsername()} and ships it as the plain {@code username}
 * field of {@code ServerPlayerListPlayer}. There is no server-side hook on that
 * name, so this service simply sends a replacement entry for the same UUID after
 * the engine has sent its own — the client keys rows by UUID, and the row's
 * username is whatever arrived last.</p>
 *
 * <p>Names are recomputed on a timer and pushed <b>only when the resolved name
 * actually changes</b>, so an idle server sends nothing. A player whose resolved
 * name equals their real username is left entirely alone: with no prefix, suffix,
 * or AFK state there is nothing to override, and the engine's own row stands.</p>
 *
 * <p>The client renders the row as a plain {@code Label}, so colour and format
 * markup is stripped from the resolved name rather than shipped as literal
 * {@code &c} noise.</p>
 */
public final class PlayerListService {

    /** How long after a connect the joining client's rows are rebuilt once more. */
    private static final long JOIN_RESYNC_DELAY_MILLIS = 1500;

    private final MysticCore core;

    /**
     * Names currently overriding the engine's, keyed by player. A player absent
     * from this map is showing their real username, which is what every client
     * receives from the engine on join — so this doubles as the set of rows a
     * freshly-connected player has to be told about.
     */
    private final Map<UUID, String> overrides = new ConcurrentHashMap<>();

    private ScheduledFuture<?> refreshTask;
    private com.hypixel.hytale.registry.Registration connectListener;
    private com.hypixel.hytale.registry.Registration disconnectListener;

    public PlayerListService(MysticCore core) {
        this.core = core;
    }

    // ----- Lifecycle ---------------------------------------------------------

    public void start() {
        MainConfig.PlayerList config = config();
        if (!config.enabled) {
            return;
        }
        // LAST so the engine's ServerPlayerListModule has already sent the
        // joining client its roster; our replacement rows land on top of it.
        connectListener = core.platform().onEvent(EventPriority.LAST, PlayerConnectEvent.class,
                (PlayerConnectEvent event) -> onConnect(event.getPlayerRef()));
        disconnectListener = core.platform().onEvent(PlayerDisconnectEvent.class,
                (PlayerDisconnectEvent event) -> overrides.remove(event.getPlayerRef().getUuid()));
        refreshTask = core.scheduler().runRepeating(this::refresh,
                config.refreshSeconds, config.refreshSeconds, TimeUnit.SECONDS);
    }

    /**
     * Applies changed settings after {@code /mystic reload}. A still-enabled
     * service re-resolves names immediately instead of reverting to plain
     * usernames for a refresh interval first; a newly disabled one hands every
     * decorated row back to the engine.
     */
    public void reload() {
        cancel();
        if (config().enabled) {
            start();
            refresh();
        } else {
            restoreAll();
        }
    }

    /**
     * Stops refreshing and hands every decorated row back to the engine's plain
     * username, so disabling the feature does not leave stale names on connected
     * clients.
     */
    public void stop() {
        cancel();
        restoreAll();
    }

    private void cancel() {
        if (refreshTask != null) {
            refreshTask.cancel(false);
            refreshTask = null;
        }
        connectListener = unregister(connectListener);
        disconnectListener = unregister(disconnectListener);
    }

    private com.hypixel.hytale.registry.Registration unregister(
            com.hypixel.hytale.registry.Registration registration) {
        if (registration != null) {
            try {
                registration.unregister();
            } catch (Throwable ignored) {
                // One-shot handle; already gone or the engine is shutting down.
            }
        }
        return null;
    }

    // ----- Refresh -----------------------------------------------------------

    /**
     * Recomputes every online player's listed name and broadcasts the ones that
     * changed. Every method that touches the override map is synchronized on the
     * service: the timer, the connect listener, and {@code /mystic reload} all
     * reach it from different threads.
     */
    private synchronized void refresh() {
        try {
            List<PlayerRef> online = new ArrayList<>(core.platform().onlinePlayers());
            List<ServerPlayerListPlayer> changed = new ArrayList<>();
            Set<UUID> present = new HashSet<>(online.size());

            for (PlayerRef player : online) {
                UUID uuid = player.getUuid();
                present.add(uuid);
                String username = player.getUsername();
                String resolved = decorate(player);
                String showing = overrides.getOrDefault(uuid, username);
                if (resolved.equals(showing)) {
                    continue;
                }
                if (resolved.equals(username)) {
                    overrides.remove(uuid);
                } else {
                    overrides.put(uuid, resolved);
                }
                changed.add(entry(player, resolved));
            }
            overrides.keySet().retainAll(present);

            if (!changed.isEmpty()) {
                send(online, changed);
            }
        } catch (Throwable t) {
            core.log(Level.WARNING, "Server player list refresh failed: " + t);
        }
    }

    /**
     * A joining client receives the engine's roster with plain usernames for
     * everyone, so every active override has to be replayed to that one player.
     * The joiner's own name is then picked up by the following refresh, which
     * also broadcasts it to everybody else.
     */
    private synchronized void onConnect(PlayerRef joiner) {
        UUID uuid = joiner.getUuid();
        replayOverridesTo(joiner);
        refresh();
        // Listening at LAST priority puts this after the engine's own roster
        // packet, but that ordering is the engine's to change. One delayed
        // repair pass costs two packets and makes a lost race self-correcting
        // instead of leaving plain names until the player reconnects.
        core.scheduler().runLater(() -> resync(uuid), JOIN_RESYNC_DELAY_MILLIS, TimeUnit.MILLISECONDS);
    }

    /** Replays every active override to one client, whatever we think it is showing. */
    private synchronized void replayOverridesTo(PlayerRef viewer) {
        try {
            if (overrides.isEmpty()) {
                return;
            }
            List<ServerPlayerListPlayer> rows = new ArrayList<>(overrides.size());
            for (PlayerRef player : core.platform().onlinePlayers()) {
                String name = overrides.get(player.getUuid());
                if (name != null) {
                    rows.add(entry(player, name));
                }
            }
            if (!rows.isEmpty()) {
                send(List.of(viewer), rows);
            }
        } catch (Throwable t) {
            core.log(Level.WARNING, "Server player list sync for " + viewer.getUsername() + " failed: " + t);
        }
    }

    /**
     * Rebuilds both directions for a recently connected player: forgetting their
     * override makes the next refresh re-broadcast their own row, and the replay
     * re-sends everyone else's rows to them.
     */
    private synchronized void resync(UUID uuid) {
        PlayerRef joiner = core.platform().findPlayer(uuid).orElse(null);
        if (joiner == null) {
            return;
        }
        overrides.remove(uuid);
        replayOverridesTo(joiner);
        refresh();
    }

    /** Restores the engine's plain usernames for every row this service replaced. */
    private synchronized void restoreAll() {
        if (overrides.isEmpty()) {
            return;
        }
        try {
            List<PlayerRef> online = new ArrayList<>(core.platform().onlinePlayers());
            List<ServerPlayerListPlayer> rows = new ArrayList<>();
            for (PlayerRef player : online) {
                if (overrides.containsKey(player.getUuid())) {
                    rows.add(entry(player, player.getUsername()));
                }
            }
            overrides.clear();
            if (!rows.isEmpty()) {
                send(online, rows);
            }
        } catch (Throwable t) {
            core.log(Level.WARNING, "Failed to restore engine player list names: " + t);
        }
    }

    // ----- Name resolution ---------------------------------------------------

    /** @return the name {@code player} should be listed under, already stripped to plain text. */
    private String decorate(PlayerRef player) {
        UUID uuid = player.getUuid();
        String username = player.getUsername();
        MainConfig.PlayerList config = config();

        String format = config.format == null || config.format.isBlank()
                ? "{display_name}"
                : config.format;
        String resolved = core.getMessageService().resolvePlaceholders(uuid, format
                .replace("{player_name}", username)
                .replace("{display_name}", displayNameOf(uuid, username)));

        if (config.showAfk && isAfk(uuid)) {
            String afkFormat = config.afkFormat == null || config.afkFormat.isBlank()
                    ? "{name}"
                    : config.afkFormat;
            resolved = afkFormat.replace("{name}", resolved);
        }

        resolved = MysticText.stripMarkup(resolved).trim();
        return resolved.isEmpty() ? username : resolved;
    }

    /** The nickname set through {@code /nick}, or the real username. */
    private String displayNameOf(UUID uuid, String username) {
        return core.getPlayerProfileService().getCached(uuid)
                .map(profile -> profile.getMetadata().get("nickname"))
                .filter(nick -> nick != null && !nick.isBlank())
                .orElse(username);
    }

    private boolean isAfk(UUID uuid) {
        AfkService afk = core.getAfkService();
        return afk != null && afk.isAfk(uuid);
    }

    // ----- Protocol ----------------------------------------------------------

    private ServerPlayerListPlayer entry(PlayerRef player, String name) {
        return new ServerPlayerListPlayer(player.getUuid(), name, player.getWorldUuid(),
                core.platform().pingMillis(player));
    }

    /**
     * Sends replacement rows to each viewer. With {@code rebuildEntries} on, the
     * rows are removed first: that is correct whether the client upserts entries
     * by UUID or appends them, and the two packets are written back to back on
     * the same connection so they arrive together.
     *
     * <p>One packet instance is shared across every viewer, matching the engine's
     * own broadcast, so the connection layer serializes it once.</p>
     */
    private void send(Collection<PlayerRef> viewers, List<ServerPlayerListPlayer> rows) {
        ServerPlayerListPlayer[] entries = rows.toArray(new ServerPlayerListPlayer[0]);
        AddToServerPlayerList addition = new AddToServerPlayerList(entries);
        RemoveFromServerPlayerList removal = null;
        if (config().rebuildEntries) {
            UUID[] uuids = new UUID[entries.length];
            for (int i = 0; i < entries.length; i++) {
                uuids[i] = entries[i].uuid;
            }
            removal = new RemoveFromServerPlayerList(uuids);
        }
        for (PlayerRef viewer : viewers) {
            if (removal != null) {
                core.platform().sendPacket(viewer, removal);
            }
            core.platform().sendPacket(viewer, addition);
        }
    }

    private MainConfig.PlayerList config() {
        MainConfig.PlayerList config = core.config().playerList;
        return config == null ? new MainConfig.PlayerList() : config;
    }
}
