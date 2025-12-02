package com.alphine.mysticessentials.chat;

import com.alphine.mysticessentials.config.ChatConfigManager;
import com.alphine.mysticessentials.config.ChatConfigManager.ChannelsConfig;
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
        if (channel == null) return;

        String format = channel.format;
        String msg    = ctx.processedMessage;

        // Basic placeholders – later you’ll pipe this through Text Placeholder API + LP meta.
        String prefix      = "%luckperms_prefix%";
        String suffix      = "%luckperms_suffix%";
        String displayName = ctx.sender.getName();
        String world       = ctx.sender.getWorldId();
        String serverName  = ctx.server.getServerName(); // or config value

        String formatted = format
                .replace("<prefix>", prefix == null ? "" : prefix)
                .replace("<suffix>", suffix == null ? "" : suffix)
                .replace("<display-name>", displayName)
                .replace("<world>", world)
                .replace("<server>", serverName)
                .replace("<message>", msg);

        // 1) Deliver to players (local-only for now)
        delivery.deliver(ctx, channel, formatted);

        // 2) Console log
        String consoleFormat = channel.consoleFormat != null
                ? channel.consoleFormat
                : "[Chat] <name>: <message>";

        String consoleLine = consoleFormat
                .replace("<name>", displayName)
                .replace("<world>", world)
                .replace("<message>", msg);

        // Use CommonServer logging
        ctx.server.logToConsole(consoleLine);
    }
}
