package com.alphine.mysticessentials.chat;

import com.alphine.mysticessentials.chat.platform.CommonPlayer;
import com.alphine.mysticessentials.chat.platform.CommonServer;
import com.alphine.mysticessentials.config.ChatConfigManager;
import com.alphine.mysticessentials.config.ChatConfigManager.ChannelsConfig;

/**
 * Sends join/quit/death messages using channels.json -> systemMessages.
 *
 * These strings are MiniMessage text and will be sent directly via
 * CommonPlayer#sendChatMessage(...) (which expects MiniMessage).
 *
 * Supported placeholders:
 *  - <display-name>, <name>  -> player name
 *  - <world>                 -> player world id
 *  - <server>                -> server name
 *  - <message>               -> raw system message text (for death)
 */
public final class SystemMessageService {

    public void broadcastJoin(CommonServer server, CommonPlayer player) {
        ChannelsConfig cfg = ChatConfigManager.CHANNELS;
        if (cfg == null || cfg.systemMessages == null) return;

        String template = cfg.systemMessages.join;
        String mini = build(template, server, player, null);
        send(server, mini);
    }

    public void broadcastQuit(CommonServer server, CommonPlayer player) {
        ChannelsConfig cfg = ChatConfigManager.CHANNELS;
        if (cfg == null || cfg.systemMessages == null) return;

        String template = cfg.systemMessages.quit;
        String mini = build(template, server, player, null);
        send(server, mini);
    }

    /**
     * @param server  CommonServer
     * @param player  Player who died (may be null for generic messages)
     * @param rawMessage vanilla-style death text you want in <message>
     */
    public void broadcastDeath(CommonServer server, CommonPlayer player, String rawMessage) {
        ChannelsConfig cfg = ChatConfigManager.CHANNELS;
        if (cfg == null || cfg.systemMessages == null) return;

        String template = cfg.systemMessages.death;
        String mini = build(template, server, player, rawMessage);
        send(server, mini);
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private void send(CommonServer server, String mini) {
        if (mini == null || mini.isBlank()) return;

        // Send as MiniMessage string to all online players
        for (CommonPlayer p : server.getOnlinePlayers()) {
            p.sendChatMessage(mini);
        }

        // Also log to console in plain-ish form (MiniMessage string)
        server.logToConsole("[MysticEssentials][System] " + mini);
    }

    private String build(String template,
                         CommonServer server,
                         CommonPlayer player,
                         String message) {
        if (template == null) return null;

        String s = template;

        if (player != null) {
            String name = player.getName();
            s = s.replace("<display-name>", name)
                    .replace("<name>", name)
                    .replace("<world>", player.getWorldId() != null ? player.getWorldId() : "");
        }

        if (server != null) {
            s = s.replace("<server>", server.getServerName() != null ? server.getServerName() : "");
        }

        if (message != null) {
            s = s.replace("<message>", message);
        }

        return s;
    }
}
