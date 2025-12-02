package com.alphine.mysticessentials.chat.platform;

public interface CommonServer {
    String getServerName();
    Iterable<? extends CommonPlayer> getOnlinePlayers();

    void runCommandAsConsole(String cmd);
    void runCommandAsPlayer(CommonPlayer player, String cmd);

    void logToConsole(String plainText);
}
