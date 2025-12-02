package com.alphine.mysticessentials.chat;

import com.alphine.mysticessentials.config.MEConfig;
import com.alphine.mysticessentials.perm.PermNodes;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts player-facing text (ctx.processedMessage) from:
 *  - legacy & color codes (&a, &l, &r, etc.)
 *  - hex codes (&#RRGGBB)
 * into MiniMessage tags (<green>, <bold>, <#RRGGBB>, etc.).
 *
 * Config flags from MEConfig.chat:
 *  - enableLegacyColors
 *  - enableHexColors
 *
 * Also respects color/format permissions:
 *  - messentials.chat.color.*            (all colors)
 *  - messentials.chat.color.<code>       (e.g. messentials.chat.color.a)
 *  - messentials.chat.format.*           (all formats)
 *  - messentials.chat.format.<code>      (e.g. messentials.chat.format.l)
 */
public class ChatColorService {

    // &0 - &f / &k - &r → MiniMessage tags
    private static final char[] LEGACY_CODES = {
            '0','1','2','3','4','5','6','7','8','9',
            'a','b','c','d','e','f',
            'k','l','m','n','o','r'
    };

    private static final String[] MINI_TAGS = {
            "black","dark_blue","dark_green","dark_aqua","dark_red","dark_purple","gold","gray",
            "dark_gray","blue","green","aqua","red","light_purple","yellow","white",
            "obfuscated","bold","strikethrough","underlined","italic","reset"
    };

    private static final Pattern HEX_AMP_PATTERN = Pattern.compile("&#([0-9a-fA-F]{6})");

    public void apply(ChatContext ctx) {
        MEConfig cfg = MEConfig.INSTANCE;
        if (cfg == null || cfg.chat == null) return;

        String text = ctx.processedMessage;

        if (cfg.chat.enableHexColors) {
            text = applyHex(text, ctx);
        }
        if (cfg.chat.enableLegacyColors) {
            text = applyLegacy(text, ctx);
        }

        ctx.processedMessage = text;
    }

    private String applyHex(String input, ChatContext ctx) {
        // &#RRGGBB → <#RRGGBB>
        // For hex, require the global color-all permission, if a sender exists.
        boolean allowHex = true;
        if (ctx.sender != null) {
            allowHex = ctx.sender.hasPermission(PermNodes.CHAT_COLOR_ALL);
        }
        if (!allowHex) {
            // Leave hex patterns as-is if not allowed
            return input;
        }

        Matcher m = HEX_AMP_PATTERN.matcher(input);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String hex = m.group(1);
            m.appendReplacement(sb, "<#" + hex + ">");
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String applyLegacy(String input, ChatContext ctx) {
        // Replace &x with <color> / <bold> etc., but only if sender has permission.
        StringBuilder out = new StringBuilder(input.length() * 2);
        char[] chars = input.toCharArray();

        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (c == '&' && i + 1 < chars.length) {
                char code = Character.toLowerCase(chars[++i]);
                String tag = mapLegacyToTag(code);

                if (tag != null && hasLegacyPermission(ctx, code)) {
                    out.append('<').append(tag).append('>');
                } else {
                    // Unknown code or no permission: keep literally
                    out.append('&').append(code);
                }
            } else {
                out.append(c);
            }
        }

        return out.toString();
    }

    private String mapLegacyToTag(char code) {
        for (int i = 0; i < LEGACY_CODES.length; i++) {
            if (LEGACY_CODES[i] == code) {
                return MINI_TAGS[i];
            }
        }
        return null;
    }

    /**
     * Check if the sender is allowed to use this legacy code.
     * Colors: 0-9, a-f
     * Formats: k, l, m, n, o, r
     */
    private boolean hasLegacyPermission(ChatContext ctx, char code) {
        if (ctx.sender == null) {
            // Console / system: allow everything.
            return true;
        }

        // Locate index to determine if this is color or format
        int idx = -1;
        for (int i = 0; i < LEGACY_CODES.length; i++) {
            if (LEGACY_CODES[i] == code) {
                idx = i;
                break;
            }
        }
        if (idx == -1) return false;

        boolean isColor = idx <= 15; // 0..f
        boolean isFormat = idx >= 16; // k..r

        String permBase;
        String permAll;

        if (isColor) {
            permBase = PermNodes.chatColorNode(code);   // messentials.chat.color.<code>
            permAll = PermNodes.CHAT_COLOR_ALL;         // messentials.chat.color.*
        } else if (isFormat) {
            permBase = PermNodes.chatFormatNode(code);  // messentials.chat.format.<code>
            permAll = PermNodes.CHAT_FORMAT_ALL;        // messentials.chat.format.*
        } else {
            return false;
        }

        return ctx.sender.hasPermission(permAll) || ctx.sender.hasPermission(permBase);
    }
}
