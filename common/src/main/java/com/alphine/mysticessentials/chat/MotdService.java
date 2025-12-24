package com.alphine.mysticessentials.chat;

import com.alphine.mysticessentials.MysticEssentialsCommon;
import com.alphine.mysticessentials.config.ChatConfigManager;
import com.alphine.mysticessentials.config.ChatConfigManager.MotdConfig;
import com.alphine.mysticessentials.placeholders.LegacyAnglePlaceholderTranslator;
import com.alphine.mysticessentials.placeholders.PlaceholderContext;
import com.alphine.mysticessentials.placeholders.PlaceholderService;
import com.alphine.mysticessentials.util.AdventureComponentBridge;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.minecraft.server.level.ServerPlayer;

public final class MotdService {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    // Inject or store this somewhere central
    private static PlaceholderService PLACEHOLDERS;

    private MotdService() {}

    public static void init(PlaceholderService svc) {
        PLACEHOLDERS = svc;
    }

    public static void sendJoinMotd(ServerPlayer player) {
        MotdConfig cfg = ChatConfigManager.MOTD;
        if (cfg == null || !cfg.enabled || cfg.lines == null || cfg.lines.isEmpty()) {
            return;
        }

        var server = player.getServer();
        var registries = player.registryAccess();

        var placeholderSvc = MysticEssentialsCommon.get().placeholders;
        PlaceholderContext ctx = PlaceholderContext.of(server, player)
                .withViewer(player);

        for (String template : cfg.lines) {
            if (template == null || template.isBlank()) continue;

            // 1) Convert <player> → {player}, etc
            String legacyTranslated =
                    LegacyAnglePlaceholderTranslator.translate(template);

            // 2) Apply unified placeholder engine
            String parsed = placeholderSvc.applyAll(
                    legacyTranslated,
                    ctx,
                    true,  // %placeholders%
                    true   // {placeholders}
            );

            // 3) MiniMessage
            Component adv = MM.deserialize(parsed);
            net.minecraft.network.chat.Component vanilla =
                    AdventureComponentBridge.advToNative(adv, registries);

            player.displayClientMessage(vanilla, false);
        }
    }
}
