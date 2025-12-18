package com.alphine.mysticessentials.mixin;

import com.alphine.mysticessentials.chat.ChatDecoratorHook;
import com.alphine.mysticessentials.util.AfkPingUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(PlayerList.class)
public abstract class PlayerListChatTagsMixin {

    @ModifyVariable(
            method = "broadcastChatMessage(Lnet/minecraft/network/chat/PlayerChatMessage;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/network/chat/ChatType$Bound;)V",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0
    )
    private PlayerChatMessage mysticessentials$decorateChatMessage(PlayerChatMessage msg) {
        if (msg == null) return msg;
        if (!ChatDecoratorHook.enabled()) return msg;

        // If already decorated, don't double-process
        if (msg.unsignedContent() != null) return msg;

        String raw = msg.signedContent();
        if (raw == null || raw.isBlank()) return msg;

        ServerPlayer sender = ChatSenderCapture.SENDER.get();
        if (sender == null) return msg;

        // AFK mention ping (DM sender if they mention an AFK player)
        AfkPingUtil.handleChatMention(sender.server, sender, raw);

        Component decorated = ChatDecoratorHook.decorate(sender, raw);
        if (decorated == null) return msg;

        return msg.withUnsignedContent(decorated);
    }
}
