package org.hyzionstudios.mysticessentials.modules.customcommands.action;

import java.util.List;

import org.hyzionstudios.mysticessentials.modules.customcommands.CustomCommandContext;

/**
 * Sends one or more formatted lines to the command sender. Supports the full
 * Mystic colour pipeline (legacy, hex, gradients) and all module placeholders.
 *
 * <pre>{ "type": "message", "text": "&aHello {player_name}!" }</pre>
 * <pre>{ "type": "message", "lines": ["&7Line one", "&7Line two"] }</pre>
 */
public final class MessageAction implements CustomCommandAction {

    private final List<String> lines;

    public MessageAction(List<String> lines) {
        this.lines = List.copyOf(lines);
    }

    @Override
    public String type() {
        return "message";
    }

    @Override
    public void execute(CustomCommandContext context) {
        for (String line : lines) {
            context.replyFormatted(line);
        }
    }

    @Override
    public String describe() {
        return "message (" + lines.size() + " line" + (lines.size() == 1 ? "" : "s") + ")";
    }
}
