package com.alphine.mysticessentials.chat;

import com.alphine.mysticessentials.config.ChatConfigManager;
import com.alphine.mysticessentials.config.ChatConfigManager.MotdConfig;
import com.alphine.mysticessentials.util.AdventureComponentBridge;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.minecraft.server.level.ServerPlayer;

public final class MotdService {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private MotdService() {}

    /**
     * Send the configured MOTD to the joining player.
     * Uses chat/motd.json.
     */
    public static void sendJoinMotd(ServerPlayer player) {
        MotdConfig cfg = ChatConfigManager.MOTD;
        if (cfg == null || !cfg.enabled || cfg.lines == null || cfg.lines.isEmpty()) {
            return;
        }

        var server   = player.getServer();
        String name  = player.getGameProfile().getName();
        String disp  = player.getDisplayName().getString();
        String world = player.serverLevel().dimension().location().toString();
        int online   = server.getPlayerList().getPlayerCount();
        int max      = server.getPlayerList().getMaxPlayers();
        String srv   = server.getServerModName(); // or your custom name

        var registries = player.registryAccess();

        for (String template : cfg.lines) {
            if (template == null || template.isBlank()) continue;

            String line = template
                    .replace("<player>", name)
                    .replace("<name>", name)
                    .replace("<display-name>", disp)
                    .replace("<world>", world)
                    .replace("<online>", String.valueOf(online))
                    .replace("<max>", String.valueOf(max))
                    .replace("<server>", srv);

            Component adv = MM.deserialize(line);
            net.minecraft.network.chat.Component vanilla =
                    AdventureComponentBridge.advToNative(adv, registries);

            player.displayClientMessage(vanilla, false);
        }
    }
}
