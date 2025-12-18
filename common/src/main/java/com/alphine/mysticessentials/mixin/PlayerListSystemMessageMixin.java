package com.alphine.mysticessentials.mixin;

import com.alphine.mysticessentials.chat.ChatSystemGate;
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
    private void mysticessentials$filterJoinQuitDeath(Component message, boolean overlay, CallbackInfo ci) {
        if (message == null) return;

        // Only override vanilla system messages if chat system is actually enabled/configured.
        if (!ChatSystemGate.chatSystemEnabled()) return;

        ComponentContents contents = message.getContents();
        if (!(contents instanceof TranslatableContents tc)) return;

        String key = tc.getKey();

        // Join/leave
        if ("multiplayer.player.joined".equals(key) || "multiplayer.player.left".equals(key)) {
            ci.cancel();
            return;
        }

        // Death messages (covers death.attack.*, death.fell.*, etc.)
        if (key != null && key.startsWith("death.")) {
            ci.cancel();
        }
    }
}
