package com.alphine.mysticessentials.chat;

import com.alphine.mysticessentials.chat.platform.CommonPlayer;
import com.alphine.mysticessentials.config.ChatConfigManager;
import com.alphine.mysticessentials.config.ChatConfigManager.MentionConfig;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChatMentionService {

    public void process(ChatContext ctx) {
        MentionConfig cfg = ChatConfigManager.MENTION;
        if (cfg == null || !cfg.enabled) return;

        String text = ctx.processedMessage;

        // Simple implementation: AT_PREFIX → @Name
        if ("AT_PREFIX".equalsIgnoreCase(cfg.trigger.mode)) {
            for (CommonPlayer target : ctx.server.getOnlinePlayers()) {
                String name = target.getName();
                String token = "@" + name;

                String replacement = (target.getUuid().equals(ctx.sender.getUuid()))
                        ? cfg.formatting.selfHighlightFormat.replace("<target>", name)
                        : cfg.formatting.highlightFormat.replace("<target>", name);

                if (cfg.trigger.caseInsensitive) {
                    Pattern p = Pattern.compile(Pattern.quote(token), Pattern.CASE_INSENSITIVE);
                    Matcher m = p.matcher(text);
                    if (m.find()) {
                        text = m.replaceAll(replacement);
                        playMentionSound(target, cfg);
                    }
                } else {
                    if (text.contains(token)) {
                        text = text.replace(token, replacement);
                        playMentionSound(target, cfg);
                    }
                }
            }
        }

        ctx.processedMessage = text;
    }

    private void playMentionSound(CommonPlayer target, MentionConfig cfg) {
        if (!cfg.sound.enabled) return;
        target.playSound(cfg.sound.soundId, cfg.sound.volume, cfg.sound.pitch);
        // Repeating sound could be handled via scheduled tasks on platform side if needed
    }
}
