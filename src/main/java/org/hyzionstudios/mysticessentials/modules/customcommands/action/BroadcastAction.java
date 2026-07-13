package org.hyzionstudios.mysticessentials.modules.customcommands.action;

import org.hyzionstudios.mysticessentials.modules.customcommands.CustomCommandContext;

/**
 * Sends a formatted line to every online player on this server.
 *
 * <pre>{ "type": "broadcast", "text": "&e{player_name} &7voted for the server!" }</pre>
 */
public final class BroadcastAction implements CustomCommandAction {

    private final String text;

    public BroadcastAction(String text) {
        this.text = text;
    }

    @Override
    public String type() {
        return "broadcast";
    }

    @Override
    public void execute(CustomCommandContext context) {
        context.broadcastFormatted(text);
    }

    @Override
    public String describe() {
        return "broadcast: " + text;
    }
}
