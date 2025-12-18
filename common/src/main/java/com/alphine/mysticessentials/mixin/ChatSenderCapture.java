package com.alphine.mysticessentials.mixin;

import net.minecraft.server.level.ServerPlayer;

final class ChatSenderCapture {
    static final ThreadLocal<ServerPlayer> SENDER = new ThreadLocal<>();
    private ChatSenderCapture() {}
}
