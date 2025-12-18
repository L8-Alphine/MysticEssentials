package com.alphine.mysticessentials.chat;

import com.alphine.mysticessentials.chat.platform.CommonPlayer;
import com.alphine.mysticessentials.chat.platform.CommonServer;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class ChatContext {

    public final CommonServer server;
    public CommonPlayer sender;
    public final Instant timestamp;

    public String rawMessage;
    public String processedMessage;
    public String messageType; // "minecraft:chat", etc.

    public String channelId; // global/local/staff/admin

    public final Map<String, Object> metadata = new HashMap<>();

    public ChatContext(CommonServer server, CommonPlayer sender,
                       String rawMessage, String messageType) {

        this.server = server;
        this.sender = sender;
        this.rawMessage = rawMessage;
        this.processedMessage = rawMessage;
        this.messageType = messageType;
        this.timestamp = Instant.now();
    }
}
