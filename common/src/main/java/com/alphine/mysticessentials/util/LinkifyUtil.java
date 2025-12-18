package com.alphine.mysticessentials.util;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LinkifyUtil {
    // Decent URL matcher for http(s) links in chat.
    private static final Pattern URL = Pattern.compile(
            "(?i)\\bhttps?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+"
    );

    // Characters commonly stuck on the end of URLs in chat (punctuation).
    private static final String TRAILING_PUNCT = ".,;:!?)\"]}";

    private LinkifyUtil() {}

    public static Component linkify(Component input) {
        // Fast path: nothing that looks like a URL anywhere in the plain string.
        String flat = input.getString();
        if (!flat.contains("http://") && !flat.contains("https://")) return input;

        // Rebuild while preserving siblings and per-part styles as best as possible.
        MutableComponent out = Component.empty().withStyle(input.getStyle());

        // Process “this” component’s own content
        appendLinkifiedContent(out, input);

        // Then process siblings
        for (Component sib : input.getSiblings()) {
            out.append(linkify(sib));
        }

        return out;
    }

    private static void appendLinkifiedContent(MutableComponent out, Component component) {
        // If it’s a translatable component, we keep it as-is (arguments may contain URLs,
        // but handling every arg perfectly is more work; you can extend if you need it).
        if (component.getContents() instanceof TranslatableContents) {
            out.append(component.plainCopy().withStyle(component.getStyle()));
            return;
        }

        // Plain text content is what most chat messages are.
        String text = component.getContents() instanceof PlainTextContents pt
                ? pt.text()
                : component.getString(); // fallback

        Style baseStyle = component.getStyle();

        Matcher m = URL.matcher(text);
        int last = 0;

        boolean found = false;
        while (m.find()) {
            found = true;

            if (m.start() > last) {
                out.append(Component.literal(text.substring(last, m.start())).withStyle(baseStyle));
            }

            String raw = m.group();
            String url = stripTrailingPunct(raw);

            // If we stripped punctuation, keep it as normal text after the link.
            String strippedTail = raw.substring(url.length());

            Style linkStyle = baseStyle
                    .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                            Component.literal("Open link: ").append(Component.literal(url).withStyle(ChatFormatting.AQUA))))
                    .withUnderlined(true)
                    .withColor(ChatFormatting.AQUA);

            out.append(Component.literal(url).withStyle(linkStyle));

            if (!strippedTail.isEmpty()) {
                out.append(Component.literal(strippedTail).withStyle(baseStyle));
            }

            last = m.end();
        }

        if (!found) {
            out.append(Component.literal(text).withStyle(baseStyle));
        } else if (last < text.length()) {
            out.append(Component.literal(text.substring(last)).withStyle(baseStyle));
        }
    }

    private static String stripTrailingPunct(String s) {
        int end = s.length();
        while (end > 0 && TRAILING_PUNCT.indexOf(s.charAt(end - 1)) >= 0) {
            end--;
        }
        return s.substring(0, end);
    }
}
