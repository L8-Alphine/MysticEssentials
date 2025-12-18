package com.alphine.mysticessentials.mixin;

import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerList.class)
public abstract class PlayerListChatSenderCaptureMixin {

    @Inject(
            method = "broadcastChatMessage(Lnet/minecraft/network/chat/PlayerChatMessage;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/network/chat/ChatType$Bound;)V",
            at = @At("HEAD")
    )
    private void mysticessentials$captureSender(PlayerChatMessage msg, ServerPlayer sender, ChatType.Bound bound, CallbackInfo ci) {
        ChatSenderCapture.SENDER.set(sender);
    }

    @Inject(
            method = "broadcastChatMessage(Lnet/minecraft/network/chat/PlayerChatMessage;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/network/chat/ChatType$Bound;)V",
            at = @At("RETURN")
    )
    private void mysticessentials$clearSender(PlayerChatMessage msg, ServerPlayer sender, ChatType.Bound bound, CallbackInfo ci) {
        ChatSenderCapture.SENDER.remove();
    }
}
