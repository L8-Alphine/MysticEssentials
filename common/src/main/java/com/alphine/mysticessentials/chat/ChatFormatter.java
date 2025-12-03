package com.alphine.mysticessentials.chat;

import com.alphine.mysticessentials.config.ChatConfigManager.ChannelsConfig.Channel;

/**
 * Responsible for taking ChatContext + Channel and producing a MiniMessage string,
 * then delegating delivery to ChatDelivery.
 */
public class ChatFormatter {

    private final ChatDelivery delivery;

    public ChatFormatter(ChatDelivery delivery) {
        this.delivery = delivery;
    }

    public void sendChannelMessage(ChatContext ctx, Channel channel) {
        if (channel == null || ctx.sender == null) return;

        String format = channel.format;
        if (format == null || format.isBlank()) {
            format = "<display-name>: <message>";
        }

        String msg = ctx.processedMessage;

        String base = format
                .replace("<prefix>", "%luckperms_prefix%")
                .replace("<suffix>", "%luckperms_suffix%")
                .replace("<display-name>", ctx.sender.getName())
                .replace("<world>", ctx.sender.getWorldId())
                .replace("<server>", ctx.server.getServerName())
                .replace("<message>", msg);

        String formatted = ctx.sender.applySenderPlaceholders(base);

        // Deliver to players
        delivery.deliver(ctx, channel, formatted);

        // Console log
        String consoleFormat = channel.consoleFormat != null
                ? channel.consoleFormat
                : "[Chat] <name>: <message>";

        String consoleLine = consoleFormat
                .replace("<name>", ctx.sender.getName())
                .replace("<world>", ctx.sender.getWorldId())
                .replace("<message>", msg);

        // Use CommonServer logging
        ctx.server.logToConsole(consoleLine);
    }
}
