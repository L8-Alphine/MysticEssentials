package com.alphine.mysticessentials.chat;

import com.alphine.mysticessentials.config.MEConfig;
import com.alphine.mysticessentials.config.ChatConfigManager;

public final class ChatSystemGate {

    private ChatSystemGate() {}

    public static boolean chatSystemEnabled() {
        MEConfig cfg = MEConfig.INSTANCE;
        if (cfg == null) return false;

        // Master toggles
        if (!cfg.features.enableChatSystem) return false;
        if (!cfg.chat.enabled) return false;

        // If channels.json didn't load / is missing, don't override vanilla.
        if (ChatConfigManager.CHANNELS == null) return false;

        // require systemMessages block (join/quit/death formats) to exist
        return ChatConfigManager.CHANNELS.systemMessages != null;
    }
}
