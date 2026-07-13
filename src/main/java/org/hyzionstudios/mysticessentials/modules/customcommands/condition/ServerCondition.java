package org.hyzionstudios.mysticessentials.modules.customcommands.condition;

import org.hyzionstudios.mysticessentials.modules.customcommands.CustomCommandContext;

/**
 * Passes based on server state: the configured server name (useful on Redis
 * networks where several servers share one command set) and/or the online
 * player count.
 *
 * <pre>{ "type": "server", "server": "survival-1", "minOnline": 0, "maxOnline": 0 }</pre>
 *
 * <p>{@code server} blank = any server; {@code maxOnline} 0 = no upper bound.</p>
 */
public final class ServerCondition implements CommandCondition {

    private final String server;
    private final int minOnline;
    private final int maxOnline;
    private final String denyMessage;

    public ServerCondition(String server, int minOnline, int maxOnline, String denyMessage) {
        this.server = server == null ? "" : server;
        this.minOnline = minOnline;
        this.maxOnline = maxOnline;
        this.denyMessage = denyMessage;
    }

    @Override
    public String type() {
        return "server";
    }

    @Override
    public boolean test(CustomCommandContext context) {
        if (!server.isBlank() && !server.equalsIgnoreCase(context.serverName())) {
            return false;
        }
        int online = context.serverOnline();
        if (online < minOnline) {
            return false;
        }
        return maxOnline <= 0 || online <= maxOnline;
    }

    @Override
    public String describe() {
        StringBuilder sb = new StringBuilder("server");
        if (!server.isBlank()) {
            sb.append(" is ").append(server);
        }
        if (minOnline > 0) {
            sb.append(", online >= ").append(minOnline);
        }
        if (maxOnline > 0) {
            sb.append(", online <= ").append(maxOnline);
        }
        return sb.toString();
    }

    @Override
    public String denyMessage() {
        return denyMessage;
    }
}
