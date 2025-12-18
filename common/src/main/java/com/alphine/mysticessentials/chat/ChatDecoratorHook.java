package com.alphine.mysticessentials.chat;

import com.alphine.mysticessentials.chat.platform.CommonPlatforms;
import com.alphine.mysticessentials.config.ChatConfigManager;
import com.alphine.mysticessentials.util.AdventureComponentBridge;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class ChatDecoratorHook {

    private static final ChatTagsService TAGS = new ChatTagsService();
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private ChatDecoratorHook() {}

    public static boolean enabled() {
        return ChatSystemGate.chatSystemEnabled()
                && ChatConfigManager.TAGS != null
                && ChatConfigManager.TAGS.enabled;
    }

    public static Component decorate(ServerPlayer sender, String rawMessage) {
        var commonServer = CommonPlatforms.server(sender.server);
        var commonPlayer = CommonPlatforms.player(sender);

        ChatContext ctx = new ChatContext(
                commonServer,
                commonPlayer,
                rawMessage,
                "minecraft:chat"
        );

        TAGS.process(ctx);

        var adv = MINI.deserialize(ctx.processedMessage);
        return AdventureComponentBridge.advToNative(adv, sender.server.registryAccess());
    }
}
