package com.alphine.mysticessentials.chat;

import com.alphine.mysticessentials.chat.platform.CommonPlayer;
import com.alphine.mysticessentials.config.ChatConfigManager;
import com.alphine.mysticessentials.config.ChatConfigManager.TriggersConfig;
import com.alphine.mysticessentials.config.ChatConfigManager.TriggersConfig.TriggerRule;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChatTriggersService {

    /**
     * @return true if a trigger fired and consumed the message.
     */
    public boolean handle(ChatContext ctx) {
        TriggersConfig cfg = ChatConfigManager.TRIGGERS;
        if (cfg == null || !cfg.enabled) return false;

        String text = ctx.processedMessage;

        for (TriggerRule rule : cfg.rules) {
            if (rule.pattern == null || rule.pattern.isEmpty()) continue;
            Pattern pattern = Pattern.compile(rule.pattern);
            Matcher matcher = pattern.matcher(text);

            if (!matcher.find()) continue;

            // Run commands
            runCommands(ctx.server, ctx.sender, rule, text);

            if (rule.stopOnMatch) {
                // Do not send this as a normal chat message
                return true;
            }
        }
        return false;
    }

    private void runCommands(com.alphine.mysticessentials.chat.platform.CommonServer server,
                             CommonPlayer sender,
                             TriggerRule rule,
                             String message) {

        for (String cmdTemplate : rule.commands) {
            String cmd = cmdTemplate
                    .replace("{player}", sender.getName())
                    .replace("{message}", message);

            if (rule.runAsConsole) {
                server.runCommandAsConsole(cmd);
            } else {
                server.runCommandAsPlayer(sender, cmd);
            }
        }
    }
}
