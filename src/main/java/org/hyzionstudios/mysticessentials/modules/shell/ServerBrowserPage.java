package org.hyzionstudios.mysticessentials.modules.shell;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hyzionstudios.mysticessentials.api.Permissions;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.modules.shell.ServerDirectory.Entry;
import org.hyzionstudios.mysticessentials.modules.shell.ServerDirectory.Status;
import org.hyzionstudios.mysticessentials.platform.ui.MysticPage;

import com.google.gson.JsonObject;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * The Server Browser (rail slot 8, the menu card, {@code /servers}): region
 * tabs, then server cards three to a row under their group headings.
 *
 * <p>Follows the mod's page convention — empty containers in the page
 * document, one row document appended per heading or row of cards, addressed
 * by index, the whole page rebuilt on every press. A Connect press carries
 * only the card's key; the card is resolved again from config and the roster
 * before anyone is moved, so a server that went offline or a permission that
 * was revoked since the page opened is honoured.</p>
 */
final class ServerBrowserPage extends MysticPage {

    static final String PAGE_UI = "MysticEssentials/ServerBrowser.ui";
    static final String GROUP_UI = "MysticEssentials/ServerBrowserGroupRow.ui";
    static final String CARD_ROW_UI = "MysticEssentials/ServerBrowserCardRow.ui";
    static final String REGION_UI = "MysticEssentials/ServerBrowserRegionTab.ui";

    private static final int COLUMNS = 3;
    private static final String DEFAULT_GROUP = "Servers";

    private final ShellModule shell;
    /** The region tab in view; empty for All. */
    private final String region;

    ServerBrowserPage(MysticCore core, ShellModule shell, PlayerRef player, String region) {
        super(core, player, CustomPageLifetime.CanDismiss);
        this.shell = shell;
        this.region = region == null ? "" : region;
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event,
            Store<EntityStore> store) {
        cmd.append(PAGE_UI);
        List<Entry> all = shell.servers().entries(player, shell.browserConfig());
        List<String> regions = ServerDirectory.regions(all);
        String active = regions.contains(region) ? region : "";

        boolean tabs = regions.size() >= 2;
        cmd.set("#BrowserRegions.Visible", tabs);
        if (tabs) {
            regionTab(cmd, event, 0, "All", "", active.isEmpty());
            for (int i = 0; i < regions.size(); i++) {
                regionTab(cmd, event, i + 1, regions.get(i), regions.get(i), regions.get(i).equals(active));
            }
        }

        List<Entry> shown = new ArrayList<>();
        for (Entry entry : all) {
            if (ServerDirectory.inRegion(entry, active)) {
                shown.add(entry);
            }
        }
        int total = shown.size();
        shown = capRows(shown);

        // Groups in the order they first appear, servers in config order within each.
        Map<String, List<Entry>> groups = new LinkedHashMap<>();
        for (Entry entry : shown) {
            String group = entry.group().isEmpty() ? DEFAULT_GROUP : entry.group();
            groups.computeIfAbsent(group, key -> new ArrayList<>()).add(entry);
        }

        int child = 0;
        for (Map.Entry<String, List<Entry>> group : groups.entrySet()) {
            String heading = "#BrowserList[" + child++ + "]";
            cmd.append("#BrowserList", GROUP_UI);
            cmd.set(heading + " #GroupTitle.TextSpans", uiText(heading + " #GroupTitle.TextSpans", group.getKey()));
            cmd.set(heading + " #GroupCount.TextSpans", uiText(heading + " #GroupCount.TextSpans",
                    groupCount(group.getValue())));
            List<Entry> members = group.getValue();
            for (int start = 0; start < members.size(); start += COLUMNS) {
                String row = "#BrowserList[" + child++ + "]";
                cmd.append("#BrowserList", CARD_ROW_UI);
                for (int column = 0; column < COLUMNS; column++) {
                    String card = row + " #Card" + column;
                    if (start + column < members.size()) {
                        renderCard(cmd, event, card, members.get(start + column));
                    } else {
                        cmd.set(card + ".Visible", false);
                    }
                }
            }
        }

        cmd.set("#BrowserEmpty.Visible", shown.isEmpty());
        if (shown.isEmpty()) {
            cmd.set("#BrowserEmpty.TextSpans", uiText("#BrowserEmpty.TextSpans", active.isEmpty()
                    ? "No servers are listed yet."
                    : "No servers are listed in " + active + "."));
        }
        cmd.set("#BrowserSummary.TextSpans", uiText("#BrowserSummary.TextSpans", summary(shown, total)));
        cmd.set("#BrowserContext.TextSpans", uiText("#BrowserContext.TextSpans", context(all)));
        cmd.set("#BrowserHint.TextSpans", uiText("#BrowserHint.TextSpans", hint()));

        event.addEventBinding(CustomUIEventBindingType.Activating, "#MenuButton",
                new EventData().put("action", "menu"));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#RefreshButton",
                new EventData().put("action", "refresh"));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton",
                new EventData().put("action", "close"));
    }

    private void regionTab(UICommandBuilder cmd, UIEventBuilder event, int index, String label,
            String value, boolean selected) {
        String tab = "#BrowserRegions[" + index + "]";
        cmd.append("#BrowserRegions", REGION_UI);
        cmd.set(tab + ".TextSpans", uiText(tab + ".TextSpans", (selected ? "&a> " : "&7") + label));
        event.addEventBinding(CustomUIEventBindingType.Activating, tab,
                new EventData().put("action", "region").put("region", value));
    }

    private void renderCard(UICommandBuilder cmd, UIEventBuilder event, String card, Entry entry) {
        cmd.set(card + " #Icon.AssetPath", entry.icon());
        cmd.set(card + " #Name.TextSpans", uiText(card + " #Name.TextSpans", entry.name()));
        cmd.set(card + " #Region.TextSpans", uiText(card + " #Region.TextSpans", regionLine(entry)));
        cmd.set(card + " #Description.TextSpans", uiText(card + " #Description.TextSpans",
                entry.description().isEmpty() ? defaultDescription(entry) : entry.description()));
        cmd.set(card + " #Status.TextSpans", uiText(card + " #Status.TextSpans", statusLine(entry)));
        cmd.set(card + " #Connect.TextSpans", uiText(card + " #Connect.TextSpans", buttonLabel(entry)));
        // Bound in every state: a press on a card that cannot connect explains
        // itself in chat rather than doing nothing.
        event.addEventBinding(CustomUIEventBindingType.Activating, card + " #Connect",
                new EventData().put("action", "connect").put("key", entry.key()));
    }

    static String statusLine(Entry entry) {
        return switch (entry.status()) {
            case HERE -> "&6You are here &7· " + count(entry);
            case ONLINE -> "&a" + count(entry);
            case DIRECT -> "&aOpen";
            case OFFLINE -> "&8Offline";
            case SOON -> "&eComing soon";
            case LOCKED -> "&cLocked";
        };
    }

    static String buttonLabel(Entry entry) {
        return switch (entry.status()) {
            case HERE -> "You're here";
            case ONLINE, DIRECT -> "Connect";
            case OFFLINE -> "Offline";
            case SOON -> "Soon";
            case LOCKED -> "Locked";
        };
    }

    private static String count(Entry entry) {
        return entry.online() + (entry.maxPlayers() > 0 ? " / " + entry.maxPlayers() : "") + " online";
    }

    private static String regionLine(Entry entry) {
        if (!entry.region().isEmpty()) {
            return entry.region();
        }
        return entry.status() == Status.HERE ? "This server" : "";
    }

    private static String defaultDescription(Entry entry) {
        return switch (entry.status()) {
            case HERE -> "The server you are on.";
            case SOON -> "Not open yet.";
            case LOCKED -> "You do not have access to this server.";
            default -> "";
        };
    }

    private static String groupCount(List<Entry> members) {
        int open = 0;
        for (Entry entry : members) {
            if (entry.connectable() || entry.status() == Status.HERE) {
                open++;
            }
        }
        return open + " of " + members.size() + " online";
    }

    private static String summary(List<Entry> shown, int total) {
        int players = 0;
        for (Entry entry : shown) {
            if (entry.status() == Status.ONLINE || entry.status() == Status.HERE) {
                players += entry.online();
            }
        }
        String servers = total == 1 ? "1 server" : total + " servers";
        if (shown.size() < total) {
            servers = shown.size() + " of " + servers;
        }
        return servers + " · " + (players == 1 ? "1 player" : players + " players") + " online";
    }

    private String context(List<Entry> all) {
        for (Entry entry : all) {
            if (entry.status() == Status.HERE) {
                return "You are on " + entry.name();
            }
        }
        return player.getUsername();
    }

    private String hint() {
        if (player.hasPermission(Permissions.SHELL_ADMIN)) {
            return "Servers are set in shell config, under browser";
        }
        return core.networkPlayers().isNetworked() ? "Player counts are live" : "";
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
        JsonObject payload = parse(data);
        switch (string(payload, "action")) {
            case "region" -> reopen(ref, store, new ServerBrowserPage(core, shell, player, field(payload, "region")));
            case "refresh" -> reopen(ref, store, new ServerBrowserPage(core, shell, player, region));
            case "connect" -> {
                Entry entry = shell.servers().find(player, shell.browserConfig(), field(payload, "key")).orElse(null);
                if (entry == null) {
                    // The list changed under the page (config reload, server left the roster).
                    reopen(ref, store, new ServerBrowserPage(core, shell, player, region));
                    return;
                }
                if (!entry.connectable()) {
                    shell.tell(player, shell.servers().connect(player, entry));
                    return;
                }
                close(ref, store);
                shell.tell(player, "Connecting to " + entry.name() + "...");
                String failure = shell.servers().connect(player, entry);
                if (!failure.isEmpty()) {
                    shell.tell(player, failure);
                }
            }
            case "menu" -> reopen(ref, store, new ShellPages.MainMenuPage(core, shell, player));
            case "close" -> close(ref, store);
            default -> {
                // Unknown action: leave the page as it is.
            }
        }
    }
}
