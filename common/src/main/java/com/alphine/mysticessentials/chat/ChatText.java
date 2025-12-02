package com.alphine.mysticessentials.chat;

import net.kyori.adventure.text.minimessage.MiniMessage;

public final class ChatText {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private ChatText() {}

    public static Object mm(String input) {
        if (input == null) {
            input = "";
        }
        // Real type is net.kyori.adventure.text.Component
        return MM.deserialize(input);
    }
}
