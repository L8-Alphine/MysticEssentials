package com.alphine.mysticessentials.chat;

import com.alphine.mysticessentials.config.ChatConfigManager;
import com.alphine.mysticessentials.config.ChatConfigManager.ReplacementsConfig;
import com.alphine.mysticessentials.config.ChatConfigManager.ReplacementsConfig.Rule;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChatReplacementsService {

    public void apply(ChatContext ctx) {
        ReplacementsConfig cfg = ChatConfigManager.REPLACEMENTS;
        if (cfg == null || !cfg.enabled) return;

        String text = ctx.processedMessage;

        for (Rule rule : cfg.rules) {
            if (rule.pattern == null || rule.pattern.isEmpty()) continue;

            String patternText = rule.pattern;
            int flags = 0;
            if (rule.ignoreCase) flags |= Pattern.CASE_INSENSITIVE;

            Pattern pattern;
            if (rule.literal) {
                pattern = Pattern.compile(Pattern.quote(patternText), flags);
            } else {
                pattern = Pattern.compile(patternText, flags);
            }

            Matcher m = pattern.matcher(text);
            if (rule.replaceAll) {
                text = m.replaceAll(rule.replacement);
            } else {
                text = m.replaceFirst(rule.replacement);
            }
        }

        ctx.processedMessage = text;
    }
}
