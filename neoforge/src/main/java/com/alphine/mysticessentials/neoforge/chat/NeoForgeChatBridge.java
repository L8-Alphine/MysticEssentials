package com.alphine.mysticessentials.neoforge.chat;

import com.alphine.mysticessentials.chat.ChatModule;
import com.alphine.mysticessentials.chat.placeholder.LuckPermsPlaceholders;
import com.alphine.mysticessentials.chat.platform.CommonPlayer;
import com.alphine.mysticessentials.chat.platform.CommonServer;
import com.alphine.mysticessentials.neoforge.placeholder.NeoForgePlaceholders;
import com.alphine.mysticessentials.perm.Perms;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.ServerChatEvent;

import java.util.UUID;

/**
 * NeoForge chat bridge: hooks ServerChatEvent into MysticEssentials ChatModule.
 * <p>
 * Used from MysticEssentialsNeoForge:
 * boolean handled = NeoForgeChatBridge.handleChat(e);
 * if (handled) e.setCanceled(true);
 */
public final class NeoForgeChatBridge {

    private NeoForgeChatBridge() {
    }

    /**
     * @param e NeoForge ServerChatEvent
     * @return true if MysticEssentials has fully handled the chat (vanilla should be canceled).
     */
    public static boolean handleChat(ServerChatEvent e) {
        ServerPlayer sender = e.getPlayer();
        String raw = e.getMessage().getString();

        // Don’t eat commands here
        if (raw.startsWith("/")) {
            return false;
        }

        CommonServer server = new NeoForgeCommonServer(sender.getServer());
        CommonPlayer commonSender = new NeoForgeCommonPlayer(sender);

        String messageType = "minecraft:chat";

        return ChatModule.handleChat(server, commonSender, raw, messageType);
    }

    // ---------------------------------------------------------------------
    // CommonServer / CommonPlayer adapters
    // ---------------------------------------------------------------------

    private record NeoForgeCommonServer(MinecraftServer handle) implements CommonServer {

        @Override
        public String getServerName() {
            return handle.getServerModName();
        }

        @Override
        public Iterable<? extends CommonPlayer> getOnlinePlayers() {
            return handle.getPlayerList().getPlayers().stream()
                    .map(NeoForgeCommonPlayer::new)
                    .toList();
        }

        @Override
        public void runCommandAsConsole(String cmd) {
            handle.getCommands().performPrefixedCommand(handle.createCommandSourceStack(), cmd);
        }

        @Override
        public void runCommandAsPlayer(CommonPlayer player, String cmd) {
            if (player instanceof NeoForgeCommonPlayer(ServerPlayer handle1)) {
                handle.getCommands().performPrefixedCommand(handle1.createCommandSourceStack(), cmd);
            }
        }

        @Override
        public void logToConsole(String plainText) {
            handle.sendSystemMessage(net.minecraft.network.chat.Component.literal(plainText));
        }
    }

    private record NeoForgeCommonPlayer(ServerPlayer handle) implements CommonPlayer {

        @Override
        public UUID getUuid() {
            return handle.getUUID();
        }

        @Override
        public String getName() {
            return handle.getGameProfile().getName();
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
            // Same Perms-based check as Fabric
            return Perms.has(handle.createCommandSourceStack(), permission, 0);
        }

        @Override
        public void sendChatMessage(String miniMessageString) {
            // 1) Apply placeholders with the RECEIVER as context (NO LuckPerms)
            String withPlaceholders =
                    NeoForgePlaceholders.applyViewer(handle, miniMessageString);

            // 2) MiniMessage → Adventure → vanilla
            Object adv = com.alphine.mysticessentials.chat.ChatText.mm(withPlaceholders);

            net.minecraft.network.chat.Component vanilla =
                    com.alphine.mysticessentials.util.AdventureComponentBridge
                            .advToNative(adv, handle.registryAccess());

            handle.displayClientMessage(vanilla, false);
        }


        @Override
        public void playSound(String soundId, float volume, float pitch) {
            var id = net.minecraft.resources.ResourceLocation.tryParse(soundId);
            if (id == null) return;

            var sound = BuiltInRegistries.SOUND_EVENT.get(id);
            if (sound == null) return;

            handle.playNotifySound(sound, SoundSource.PLAYERS, volume, pitch);
        }

        @Override
        public ItemTagInfo getMainHandItemTagInfo() {
            ItemStack stack = handle.getMainHandItem();
            if (stack.isEmpty()) return null;

            // Label formatting
            int count = stack.getCount();
            String baseName = stack.getHoverName().getString();

            String label;
            if (count > 1 && stack.isStackable()) {
                label = "x" + count + " " + baseName;
            } else {
                label = baseName;
            }

            // SNBT for <hover:show_item:...>
            // NeoForge 1.21.x requires a HolderLookup.Provider when creating NBT
            var provider = handle.registryAccess();

            // Save as new NBT
            CompoundTag tag = (CompoundTag) stack.save(provider);
            String snbt = tag.toString(); // SNBT format for MiniMessage

            return new ItemTagInfo(label, snbt);
        }

        @Override
        public String applySenderPlaceholders(String input) {
            return LuckPermsPlaceholders.apply(handle, input);
        }

    }
}
