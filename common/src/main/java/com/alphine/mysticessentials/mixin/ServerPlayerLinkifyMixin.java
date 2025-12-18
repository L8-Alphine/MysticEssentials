package com.alphine.mysticessentials.mixin;

import com.alphine.mysticessentials.util.LinkifyUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerLinkifyMixin {

    @ModifyVariable(
            method = "sendSystemMessage(Lnet/minecraft/network/chat/Component;Z)V",
            at = @At("HEAD"),
            argsOnly = true
    )
    private Component mystic$linkifySystem(Component message) {
        return LinkifyUtil.linkify(message);
    }

    // 1-arg overload added in MC 1.20.4 / 1.19.5
    @ModifyVariable(
            method = "sendSystemMessage(Lnet/minecraft/network/chat/Component;)V",
            at = @At("HEAD"),
            argsOnly = true,
            require = 0 // don't crash if the overload doesn't exist
    )
    private Component mystic$linkifySystem1(Component message) {
        return LinkifyUtil.linkify(message);
    }
}
