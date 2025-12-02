package com.alphine.mysticessentials.fabric.placeholder;

import com.alphine.mysticessentials.chat.placeholder.LuckPermsPlaceholders;
import eu.pb4.placeholders.api.PlaceholderContext;
import eu.pb4.placeholders.api.Placeholders;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;

/**
 * Fabric-only helper for Text Placeholder API (pb4).
 *
 * This:
 *  - takes a raw string (MiniMessage or plain text),
 *  - lets pb4 expand %modid:placeholder% using the provided player as context,
 *  - then returns a plain String again for MiniMessage / Adventure.
 */
public final class FabricPlaceholders {

    private FabricPlaceholders() {
    }

    public static String apply(ServerPlayer player, String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }

        // 1) pb4 placeholders
        net.minecraft.network.chat.Component initial =
                net.minecraft.network.chat.Component.literal(input);

        net.minecraft.network.chat.Component parsed =
                eu.pb4.placeholders.api.Placeholders.parseText(
                        initial,
                        eu.pb4.placeholders.api.PlaceholderContext.of(player)
                );

        String withPb4 = parsed.getString();

        // 2) LuckPerms placeholders (prefix, suffix, meta, etc.)
        return LuckPermsPlaceholders.apply(player, withPb4);
    }
}
