package com.alphine.mysticessentials.fabric.placeholder;

import com.alphine.mysticessentials.chat.placeholder.LuckPermsPlaceholders;
import eu.pb4.placeholders.api.PlaceholderContext;
import eu.pb4.placeholders.api.Placeholders;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Fabric-only helper for Text Placeholder API (pb4).
 * <p>
 * We have TWO flows:
 * - applySender(...) : pb4 + LuckPerms (sender context, used when BUILDING the message)
 * - applyViewer(...) : pb4 only (viewer context, used when SENDING to each player)
 */
public final class FabricPlaceholders {

    private FabricPlaceholders() {
    }

    /**
     * Sender-context placeholder expansion: pb4 + LuckPerms.
     * Use this when you are constructing the chat message based on the SENDER.
     */
    public static String applySender(ServerPlayer sender, String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }

        // 1) pb4 placeholders with sender context
        Component initial = Component.literal(input);
        Component parsed = Placeholders.parseText(
                initial,
                PlaceholderContext.of(sender)
        );
        String withPb4 = parsed.getString();

        // 2) LuckPerms placeholders with SENDER context
        return LuckPermsPlaceholders.apply(sender, withPb4);
    }

    /**
     * Viewer-context placeholder expansion: pb4 ONLY.
     * LuckPerms rank/meta here would be WRONG (it would use the viewer),
     * so we intentionally do NOT call LuckPerms here.
     */
    public static String applyViewer(ServerPlayer viewer, String input) {
        if (input == null || input.isEmpty()) return input;
        Component initial = Component.literal(input);
        Component parsed = Placeholders.parseText(initial, PlaceholderContext.of(viewer));
        return parsed.getString();
    }
}
