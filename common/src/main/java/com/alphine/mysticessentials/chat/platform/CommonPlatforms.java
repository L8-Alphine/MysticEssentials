package com.alphine.mysticessentials.chat.platform;

import com.alphine.mysticessentials.util.AdventureComponentBridge;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public final class CommonPlatforms {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private CommonPlatforms() {}

    public static CommonPlayer player(ServerPlayer player) {
        return new ServerPlayerCommon(player);
    }

    public static CommonServer server(MinecraftServer server) {
        return new MinecraftServerCommon(server);
    }

    // ------------------------------------------------------------------------
    // Server wrapper
    // ------------------------------------------------------------------------

    private static final class MinecraftServerCommon implements CommonServer {
        private final MinecraftServer server;

        private MinecraftServerCommon(MinecraftServer server) {
            this.server = server;
        }

        @Override
        public String getServerName() {
            // Use whatever you want here (cluster/serverId, etc.)
            return server.getServerModName();
        }

        @Override
        public Iterable<? extends CommonPlayer> getOnlinePlayers() {
            return server.getPlayerList().getPlayers().stream()
                    .map(CommonPlatforms::player)
                    .toList();
        }

        @Override
        public void runCommandAsConsole(String cmd) {
            if (cmd == null || cmd.isBlank()) return;
            CommandSourceStack src = server.createCommandSourceStack();
            server.getCommands().performPrefixedCommand(src, cmd);
        }

        @Override
        public void runCommandAsPlayer(CommonPlayer player, String cmd) {
            // Optional. You can implement later if needed for triggers.
            runCommandAsConsole(cmd);
        }

        @Override
        public void logToConsole(String plainText) {
            System.out.println(plainText);
        }
    }

    // ------------------------------------------------------------------------
    // Player wrapper
    // ------------------------------------------------------------------------

    private static final class ServerPlayerCommon implements CommonPlayer {
        private final ServerPlayer p;

        private ServerPlayerCommon(ServerPlayer p) {
            this.p = p;
        }

        @Override public UUID getUuid() { return p.getUUID(); }
        @Override public String getName() { return p.getGameProfile().getName(); }

        @Override public String getWorldId() {
            return p.level().dimension().location().toString();
        }

        @Override public double getX() { return p.getX(); }
        @Override public double getY() { return p.getY(); }
        @Override public double getZ() { return p.getZ(); }

        @Override
        public boolean hasPermission(String permission) {
            // Replace with your perms integration later.
            return p.hasPermissions(2);
        }

        @Override
        public void sendChatMessage(String miniMessageString) {
            if (miniMessageString == null) return;
            var adv = MINI.deserialize(miniMessageString);
            var nativeComp = AdventureComponentBridge.advToNative(adv, p.server.registryAccess());
            p.sendSystemMessage(nativeComp);
        }

        @Override
        public void playSound(String soundId, float volume, float pitch) {
            // Optional / implement later
        }

        // Tag helpers
        @Override
        public boolean hasMainHandItem() {
            return !p.getMainHandItem().isEmpty();
        }

        @Override
        public ItemTagInfo getMainHandItemTagInfo() {
            if (p.getMainHandItem().isEmpty()) return null;

            var stack = p.getMainHandItem();
            String label = stack.getHoverName().getString();

            // Add showItemNbt later if you want hover:show_item
            return new ItemTagInfo(label, null);
        }
    }
}
