package com.alphine.mysticessentials.fabric.chat;

import com.alphine.mysticessentials.chat.ChatModule;
import com.alphine.mysticessentials.chat.placeholder.LuckPermsPlaceholders;
import com.alphine.mysticessentials.chat.platform.CommonPlayer;
import com.alphine.mysticessentials.chat.platform.CommonServer;
import com.alphine.mysticessentials.perm.Perms;
import com.mojang.authlib.GameProfile;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/**
 * Fabric chat bridge: hooks Fabric's ALLOW_CHAT_MESSAGE event into MysticEssentials ChatModule.
 * <p>
 * Used from MysticEssentialsFabric:
 * boolean handled = FabricChatBridge.handleAllowChat(message, p);
 * return !handled; // cancel vanilla if handled
 */
public final class FabricChatBridge {

    private FabricChatBridge() {
    }

    /**
     * @param message Fabric's SignedMessage
     * @param sender  the ServerPlayer sending the message
     * @return true if MysticEssentials has fully handled the chat.
     */
    public static boolean handleAllowChat(PlayerChatMessage message, ServerPlayer sender) {
        // Extract raw content (like you're already doing in the Fabric main class)
        String raw = (message.unsignedContent() != null)
                ? message.unsignedContent().getString()
                : message.signedContent();

        // Ignore commands here (they’re already handled by Brigadier)
        if (raw.startsWith("/")) {
            return false;
        }

        CommonServer server = new FabricCommonServer(sender.getServer());
        CommonPlayer commonSender = new FabricCommonPlayer(sender);

        // messageType: you can tweak if you differentiate types later
        String messageType = "minecraft:chat";

        return ChatModule.handleChat(server, commonSender, raw, messageType);
    }

    // ---------------------------------------------------------------------
    // CommonServer / CommonPlayer adapters
    // ---------------------------------------------------------------------

    private record FabricCommonServer(MinecraftServer handle) implements CommonServer {

        @Override
        public String getServerName() {
            return handle.getServerModName(); // or a custom name if you prefer
        }

        @Override
        public Iterable<? extends CommonPlayer> getOnlinePlayers() {
            return handle.getPlayerList().getPlayers().stream()
                    .map(FabricCommonPlayer::new)
                    .toList();
        }

        @Override
        public void runCommandAsConsole(String cmd) {
            handle.getCommands().performPrefixedCommand(handle.createCommandSourceStack(), cmd);
        }

        @Override
        public void runCommandAsPlayer(CommonPlayer player, String cmd) {
            if (player instanceof FabricCommonPlayer(ServerPlayer handle1)) {
                handle.getCommands().performPrefixedCommand(handle1.createCommandSourceStack(), cmd);
            }
        }

        @Override
        public void logToConsole(String plainText) {
            handle.sendSystemMessage(net.minecraft.network.chat.Component.literal(plainText));
        }
    }

    private record FabricCommonPlayer(ServerPlayer handle) implements CommonPlayer {

        @Override
        public UUID getUuid() {
            return handle.getUUID();
        }

        @Override
        public String getName() {
            GameProfile profile = handle.getGameProfile();
            return profile.getName();
        }

        @Override
        public String getWorldId() {
            return handle.serverLevel().dimension().location().toString();
        }

        @Override
        public double getX() {
            return handle.getX();
        }

        @Override
        public double getY() {
            return handle.getY();
        }

        @Override
        public double getZ() {
            return handle.getZ();
        }

        @Override
        public boolean hasPermission(String permission) {
            // Reuse your Perms API via a CommandSourceStack
            return Perms.has(handle.createCommandSourceStack(), permission, 0);
        }

        @Override
        public void sendChatMessage(String miniMessageString) {
            // 1) pb4 placeholders, using the RECEIVER as context (NO LuckPerms here)
            String withPlaceholders =
                    com.alphine.mysticessentials.fabric.placeholder.FabricPlaceholders
                            .applyViewer(handle, miniMessageString);

            // 2) MiniMessage → Adventure → vanilla Component
            Object adv = com.alphine.mysticessentials.chat.ChatText.mm(withPlaceholders);

            net.minecraft.network.chat.Component vanilla =
                    com.alphine.mysticessentials.util.AdventureComponentBridge
                            .advToNative(adv, handle.registryAccess());

            handle.displayClientMessage(vanilla, false);
        }


        @Override
        public void playSound(String soundId, float volume, float pitch) {
            // Simple server-side sound at player location
            var id = net.minecraft.resources.ResourceLocation.tryParse(soundId);
            if (id == null) return;

            var sound = net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.get(id);
            if (sound == null) return;

            handle.playNotifySound(sound, net.minecraft.sounds.SoundSource.PLAYERS, volume, pitch);
        }

        @Override
        public CommonPlayer.ItemTagInfo getMainHandItemTagInfo() {
            ItemStack stack = handle.getMainHandItem();
            if (stack.isEmpty()) return null;

            // Label for chat
            int count = stack.getCount();
            String baseName = stack.getHoverName().getString();

            String label;
            if (count > 1 && stack.isStackable()) {
                label = "x" + count + " " + baseName;
            } else {
                label = baseName;
            }

            // SNBT for <hover:show_item:...>
            var provider = handle.server.registryAccess(); // HolderLookup.Provider
            CompoundTag tag = (CompoundTag) stack.save(provider);
            String snbt = tag.toString();

            return new CommonPlayer.ItemTagInfo(label, snbt);
        }

        @Override
        public String applySenderPlaceholders(String input) {
            // Only LuckPerms – viewer pb4 is handled later when sending
            return LuckPermsPlaceholders.apply(handle, input);
        }

    }

}
