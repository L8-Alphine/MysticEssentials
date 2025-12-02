package com.alphine.mysticessentials.neoforge.placeholder;

import com.alphine.mysticessentials.chat.placeholder.LuckPermsPlaceholders;
import net.minecraft.server.level.ServerPlayer;

public final class NeoForgePlaceholders {

    private NeoForgePlaceholders() {
    }

    public static String apply(ServerPlayer player, String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }

        var server = player.getServer();
        String result = input;

        // --- existing simple placeholders ---
        result = result.replace("%player:name%", player.getGameProfile().getName());
        result = result.replace("%player:uuid%", player.getUUID().toString());
        result = result.replace("%player:display_name%", player.getDisplayName().getString());

        String worldId = player.serverLevel().dimension().location().toString();
        result = result.replace("%world:id%", worldId);

        result = result.replace("%player:x%", String.format("%.2f", player.getX()));
        result = result.replace("%player:y%", String.format("%.2f", player.getY()));
        result = result.replace("%player:z%", String.format("%.2f", player.getZ()));

        int online = server.getPlayerList().getPlayerCount();
        int max = server.getPlayerList().getMaxPlayers();
        result = result.replace("%server:name%", server.getServerModName());
        result = result.replace("%server:online%", Integer.toString(online));
        result = result.replace("%server:max%", Integer.toString(max));

        // --- LuckPerms placeholders ---
        result = LuckPermsPlaceholders.apply(player, result);

        return result;
    }
}
