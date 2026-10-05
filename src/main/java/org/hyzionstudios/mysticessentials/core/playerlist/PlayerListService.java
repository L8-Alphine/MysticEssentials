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

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.event.EventPriority;
import com.hypixel.hytale.protocol.packets.interface_.AddToServerPlayerList;
import com.hypixel.hytale.protocol.packets.interface_.RemoveFromServerPlayerList;
import com.hypixel.hytale.protocol.packets.interface_.ServerPlayerListPlayer;
import com.hypixel.hytale.registry.Registration;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.entity.entities.player.HiddenPlayersManager;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.modules.entity.component.PlayerLives;
import com.hypixel.hytale.server.core.modules.entity.component.Spectating;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

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
 * <p>The engine re-sends its own plain row at three points, each of which wipes
 * an override, so all three are followed here: it ships the joining client the
 * whole roster from {@code PlayerReadyEvent} (0.6 moved this off
 * {@code AddPlayerToWorldEvent}, and it lands well after
 * {@code PlayerConnectEvent} — listening on connect alone is why decorated names
 * came back plain), and it calls {@code broadcastListEntry} whenever a player's
 * spectating or hardcore-lives state changes.</p>
 *
 * <p>Names are recomputed on a timer and pushed <b>only when the row actually
 * changes</b>, so an idle server sends nothing. Spectating and lives are part of
 * that comparison precisely because they are what the engine re-broadcasts on.
 * A player whose resolved name equals their real username is left entirely
 * alone: with no prefix, suffix, or AFK state there is nothing to override, and
 * the engine's own row stands.</p>
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
     * The row last sent for each decorated player. A player absent from this map
     * is showing their real username, which is what every client receives from
     * the engine on join — so this doubles as the set of rows a freshly-connected
     * player has to be told about.
     */
    private final Map<UUID, Row> overrides = new ConcurrentHashMap<>();

    private ScheduledFuture<?> refreshTask;
    private Registration connectListener;
    private Registration readyListener;
    private Registration disconnectListener;

    /**
     * Everything in a list row that this service decides. Ping is excluded: the
     * engine refreshes it through {@code UpdateServerPlayerListPing}, which
     * carries no username and so never disturbs an override.
     */
    private record Row(String name, boolean spectating, Integer lives) {}

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
        // The roster the client actually keeps is the one sent from here. This
        // event is keyed, so it needs a global registration to arrive at all.
        readyListener = core.platform().onGlobalEvent(EventPriority.LAST, PlayerReadyEvent.class,
                (PlayerReadyEvent event) -> onReady(playerRefOf(event.getPlayerRef())));
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
        readyListener = unregister(readyListener);
        disconnectListener = unregister(disconnectListener);
    }

    private Registration unregister(Registration registration) {
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
                if (resolved.equals(username)) {
                    // Nothing left to override; hand the row back to the engine
                    // if we were the ones holding it.
                    if (overrides.remove(uuid) != null) {
                        changed.add(entry(player, username));
                    }
                    continue;
                }
                Row row = new Row(resolved, isSpectating(player), livesRemaining(player));
                if (row.equals(overrides.get(uuid))) {
                    continue;
                }
                overrides.put(uuid, row);
                changed.add(entry(player, row));
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
        replayOverridesTo(joiner);
        refresh();
    }

    /**
     * Resolves the {@link PlayerRef} behind an entity reference. {@code
     * PlayerReadyEvent} hands out the ECS reference, and both routes from there
     * to a {@code PlayerRef} on {@code Player} itself are deprecated for
     * removal; the ref is carried as an ordinary component, so read it as one.
     *
     * @return {@code null} when the entity is gone or is not a player
     */
    private static PlayerRef playerRefOf(Ref<EntityStore> ref) {
        if (ref == null || !ref.isValid()) {
            return null;
        }
        return ref.getStore().getComponent(ref, Universe.get().getPlayerRefComponentType());
    }

    /**
     * Rebuilds both directions once the engine has shipped this client the full
     * roster of plain usernames and told everyone else the joiner's plain row.
     * Also fires when a player moves between worlds, where the engine re-sends
     * that same roster.
     */
    private void onReady(PlayerRef player) {
        if (player == null) {
            return;
        }
        UUID uuid = player.getUuid();
        resync(uuid);
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
                Row row = overrides.get(player.getUuid());
                if (row != null) {
                    rows.add(entry(player, row));
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

    private ServerPlayerListPlayer entry(PlayerRef player, Row row) {
        return new ServerPlayerListPlayer(player.getUuid(), row.name(), player.getWorldUuid(),
                core.platform().pingMillis(player), row.spectating(), row.lives());
    }

    /** A row carrying {@code name} over this player's current engine-owned state. */
    private ServerPlayerListPlayer entry(PlayerRef player, String name) {
        return entry(player, new Row(name, isSpectating(player), livesRemaining(player)));
    }

    private static boolean isSpectating(PlayerRef player) {
        return player.getComponentConcurrent(Spectating.getComponentType()) != null;
    }

    private static Integer livesRemaining(PlayerRef player) {
        var defaults = HytaleServer.get().getConfig().getDefaults();
        if (!defaults.getHardcoreMode().showsPersonalLives(defaults.getHardcoreLives())) {
            return null;
        }
        PlayerLives lives = player.getComponentConcurrent(PlayerLives.getComponentType());
        int remaining = lives == null ? defaults.getHardcoreLives() : lives.getRemaining();
        return Math.max(remaining, 0);
    }

    /**
     * Sends replacement rows to each viewer. With {@code rebuildEntries} on, the
     * rows are removed first: that is correct whether the client upserts entries
     * by UUID or appends them, and the two packets are written back to back on
     * the same connection so they arrive together.
     *
     * <p>A viewer is never sent a row for somebody their {@code
     * HiddenPlayersManager} hides. The engine filters its own broadcasts that
     * way, so re-adding the row here would put a vanished player back on the map
     * list.</p>
     *
     * <p>One packet instance is shared across every viewer hiding nobody in the
     * batch, matching the engine's own broadcast, so the connection layer
     * serializes it once.</p>
     */
    private void send(Collection<PlayerRef> viewers, List<ServerPlayerListPlayer> rows) {
        ServerPlayerListPlayer[] entries = rows.toArray(new ServerPlayerListPlayer[0]);
        boolean rebuild = config().rebuildEntries;
        AddToServerPlayerList sharedAddition = new AddToServerPlayerList(entries);
        RemoveFromServerPlayerList sharedRemoval = rebuild
                ? new RemoveFromServerPlayerList(uuidsOf(entries))
                : null;

        for (PlayerRef viewer : viewers) {
            ServerPlayerListPlayer[] visible = visibleTo(viewer, entries);
            if (visible.length == 0) {
                continue;
            }
            boolean whole = visible.length == entries.length;
            if (rebuild) {
                core.platform().sendPacket(viewer, whole
                        ? sharedRemoval
                        : new RemoveFromServerPlayerList(uuidsOf(visible)));
            }
            core.platform().sendPacket(viewer, whole
                    ? sharedAddition
                    : new AddToServerPlayerList(visible));
        }
    }

    /**
     * @return {@code entries} without the players {@code viewer} hides, or
     *         {@code entries} itself when it hides none of them.
     */
    private static ServerPlayerListPlayer[] visibleTo(PlayerRef viewer, ServerPlayerListPlayer[] entries) {
        HiddenPlayersManager hidden = viewer.getHiddenPlayersManager();
        if (hidden == null) {
            return entries;
        }
        int count = 0;
        for (ServerPlayerListPlayer entry : entries) {
            if (!hidden.isPlayerHidden(entry.uuid)) {
                count++;
            }
        }
        if (count == entries.length) {
            return entries;
        }
        ServerPlayerListPlayer[] visible = new ServerPlayerListPlayer[count];
        int index = 0;
        for (ServerPlayerListPlayer entry : entries) {
            if (!hidden.isPlayerHidden(entry.uuid)) {
                visible[index++] = entry;
            }
        }
        return visible;
    }

    private static UUID[] uuidsOf(ServerPlayerListPlayer[] entries) {
        UUID[] uuids = new UUID[entries.length];
        for (int i = 0; i < entries.length; i++) {
            uuids[i] = entries[i].uuid;
        }
        return uuids;
    }

    private MainConfig.PlayerList config() {
        MainConfig.PlayerList config = core.config().playerList;
        return config == null ? new MainConfig.PlayerList() : config;
    }
}
