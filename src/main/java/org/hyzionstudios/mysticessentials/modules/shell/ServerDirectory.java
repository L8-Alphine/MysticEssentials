package org.hyzionstudios.mysticessentials.modules.shell;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.network.NetworkPlayerService;
import org.hyzionstudios.mysticessentials.core.network.NetworkPlayerService.NetworkPlayer;
import org.hyzionstudios.mysticessentials.core.network.NetworkPlayerService.RemoteServer;
import org.hyzionstudios.mysticessentials.platform.ui.HyzionIcons;
import org.hyzionstudios.mysticessentials.platform.ui.VanillaIcons;

import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * What the Server Browser lists, resolved for one viewer: the configured
 * servers in order, then — with {@code browser.showNetwork} — whatever else is
 * on the Redis network roster, each with a status its card can act on.
 *
 * <p>Only in-memory reads: the roster is a map the presence heartbeat keeps
 * current on the scheduler, so building the list never waits on Redis and is
 * safe from the page's thread.</p>
 */
final class ServerDirectory {

    /** Heading for roster servers the config does not name. */
    static final String NETWORK_GROUP = "Network";

    enum Status {
        /** The server the viewer is on. */
        HERE,
        /** On the network roster: live count, Connect works. */
        ONLINE,
        /** Configured by address only: Connect works, count unknown. */
        DIRECT,
        /** Named in config, not on the roster and no address to fall back on. */
        OFFLINE,
        /** {@code comingSoon}. */
        SOON,
        /** The viewer lacks the card's permission. */
        LOCKED
    }

    /**
     * One card. {@code key} identifies it across a page rebuild ({@code c<n>}
     * for the n-th configured server, {@code n:<id>} for a roster server), so a
     * press is re-resolved rather than trusting anything else the client sent.
     */
    record Entry(String key, String name, String description, String group, String region,
            String icon, Status status, int online, int maxPlayers,
            String serverId, String host, int port) {

        boolean connectable() {
            return status == Status.ONLINE || status == Status.DIRECT;
        }
    }

    private final MysticCore core;
    private final ShellModule shell;

    ServerDirectory(MysticCore core, ShellModule shell) {
        this.core = core;
        this.shell = shell;
    }

    List<Entry> entries(PlayerRef viewer, ShellConfig.Browser browser) {
        NetworkPlayerService network = core.networkPlayers();
        String local = network.localServerId();
        boolean networked = network.isNetworked();
        Map<String, Integer> counts = visibleCounts(network);
        Set<String> roster = new HashSet<>();
        if (networked) {
            for (RemoteServer remote : network.remoteServers()) {
                roster.add(remote.serverId());
            }
        }

        List<Entry> entries = new ArrayList<>();
        Set<String> listed = new HashSet<>();
        List<ShellConfig.Server> servers = browser.servers == null ? List.of() : browser.servers;
        for (int i = 0; i < servers.size(); i++) {
            ShellConfig.Server server = servers.get(i);
            if (server == null) {
                continue;
            }
            String id = clean(server.id);
            String host = clean(server.host);
            if (!id.isEmpty()) {
                listed.add(id);
            }
            Status status;
            if (server.comingSoon) {
                status = Status.SOON;
            } else if (!clean(server.permission).isEmpty() && !viewer.hasPermission(server.permission.trim())) {
                status = Status.LOCKED;
            } else if (!id.isEmpty() && id.equals(local)) {
                status = Status.HERE;
            } else if (!id.isEmpty() && roster.contains(id) && network.hasEndpoint(id)) {
                status = Status.ONLINE;
            } else if (!host.isEmpty() && server.port > 0) {
                status = Status.DIRECT;
            } else {
                status = Status.OFFLINE;
            }
            String name = blankTo(server.name, id.isEmpty() ? "Server " + (i + 1) : id);
            entries.add(new Entry("c" + i, name, clean(server.description), clean(server.group),
                    clean(server.region).toUpperCase(Locale.ROOT), iconOf(server.icon),
                    status, counts.getOrDefault(id, 0), Math.max(0, server.maxPlayers), id, host, server.port));
        }

        if (browser.showNetwork) {
            if (!listed.contains(local)) {
                String title = blankTo(shell.serverTitle(), networked ? local : "This server");
                entries.add(new Entry("n:" + local, title, "The server you are on.", NETWORK_GROUP, "",
                        HyzionIcons.HOME, Status.HERE, counts.getOrDefault(local, 0), 0, local, "", 0));
            }
            for (RemoteServer remote : network.remoteServers()) {
                if (!networked || listed.contains(remote.serverId())) {
                    continue;
                }
                Status status = network.hasEndpoint(remote.serverId()) ? Status.ONLINE : Status.OFFLINE;
                entries.add(new Entry("n:" + remote.serverId(), remote.serverId(), "", NETWORK_GROUP, "",
                        HyzionIcons.SERVERS, status, counts.getOrDefault(remote.serverId(), 0), 0,
                        remote.serverId(), "", 0));
            }
        }
        return entries;
    }

    Optional<Entry> find(PlayerRef viewer, ShellConfig.Browser browser, String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        return entries(viewer, browser).stream().filter(entry -> entry.key().equals(key)).findFirst();
    }

    /** Region codes in first-seen order, for the filter tabs. */
    static List<String> regions(List<Entry> entries) {
        Set<String> regions = new LinkedHashSet<>();
        for (Entry entry : entries) {
            if (!entry.region().isEmpty()) {
                regions.add(entry.region());
            }
        }
        return new ArrayList<>(regions);
    }

    /** Whether a card belongs under a region tab; region-less cards show under every tab. */
    static boolean inRegion(Entry entry, String region) {
        return region == null || region.isEmpty() || entry.region().isEmpty()
                || entry.region().equalsIgnoreCase(region);
    }

    /**
     * Sends the player to the card's server: through the roster (and the
     * proxy, when one fronts the network) when the server is on it, else
     * straight to its configured address.
     *
     * @return what to tell the player when it could not be done, empty on success
     */
    String connect(PlayerRef player, Entry entry) {
        switch (entry.status()) {
            case HERE -> {
                return "You are already on " + entry.name() + ".";
            }
            case SOON -> {
                return entry.name() + " is coming soon.";
            }
            case LOCKED -> {
                return "You do not have access to " + entry.name() + ".";
            }
            case OFFLINE -> {
                return entry.name() + " is offline right now.";
            }
            default -> {
                // ONLINE and DIRECT fall through to the transfer below.
            }
        }
        if (entry.status() == Status.ONLINE
                && core.networkPlayers().referToServer(player, entry.serverId())) {
            return "";
        }
        if (!entry.host().isEmpty() && entry.port() > 0) {
            try {
                player.referToServer(entry.host(), entry.port());
                return "";
            } catch (RuntimeException e) {
                core.log(Level.WARNING, "Server browser: referring " + player.getUsername() + " to "
                        + entry.host() + ":" + entry.port() + " failed: " + e.getMessage());
            }
        }
        return "Could not reach " + entry.name() + ". Try again in a moment.";
    }

    /** Players each server shows, vanished players left out of every count. */
    private static Map<String, Integer> visibleCounts(NetworkPlayerService network) {
        Map<String, Integer> counts = new HashMap<>();
        for (NetworkPlayer player : network.onlinePlayers()) {
            if (!player.vanished() && player.serverId() != null) {
                counts.merge(player.serverId(), 1, Integer::sum);
            }
        }
        return counts;
    }

    private static String iconOf(String configured) {
        String resolved = VanillaIcons.resolve(configured);
        return resolved.isEmpty() ? HyzionIcons.PORTAL : resolved;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
