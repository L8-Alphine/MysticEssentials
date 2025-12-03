package com.alphine.mysticessentials.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerList.class)
public abstract class PlayerListSystemMessageMixin {

    @Inject(
            method = "broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void mysticessentials$filterJoinQuit(Component message, boolean overlay, CallbackInfo ci) {
        if (message == null) {
            return;
        }

        ComponentContents contents = message.getContents();
        if (!(contents instanceof TranslatableContents tc)) {
            return; // not a vanilla translatable message -> let it through
        }

        String key = tc.getKey();
        // Vanilla join / leave keys
        if ("multiplayer.player.joined".equals(key)
                || "multiplayer.player.left".equals(key)) {
            // Suppress vanilla join/quit so only MysticEssentials’ SystemMessageService fires
            ci.cancel();
        }
    }
}
