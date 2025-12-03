package com.alphine.mysticessentials.chat.platform;

import java.util.UUID;

public interface CommonPlayer {
    UUID getUuid();
    String getName();
    String getWorldId();   // e.g. "minecraft:overworld"
    double getX();
    double getY();
    double getZ();

    boolean hasPermission(String permission);

    void sendChatMessage(String miniMessageString);
    void playSound(String soundId, float volume, float pitch);

    // ------------------------------------------------------------------------
    // Optional helpers for chat tags (item / inventory / ec)
    //
    // Default no-op implementations so existing wrappers still compile.
    // Once you implement these in your platform adapters, <item> will become:
    //   "xN DisplayName" (or "DisplayName") with hover showing full tooltip.
    // ------------------------------------------------------------------------

    /**
     * @return true if this player currently has a non-empty item in their main hand.
     */
    default boolean hasMainHandItem() {
        return false;
    }

    /**
     * Information for rendering the <item> chat tag.
     *
     * label         -> text shown in chat (e.g. "x3 Diamond Sword")
     * showItemNbt   -> SNBT string for MiniMessage <hover:show_item:...>
     *                  e.g. {id:"minecraft:diamond_sword",Count:3b,tag:{...}}
     */
    default ItemTagInfo getMainHandItemTagInfo() {
        return null;
    }

    default String applySenderPlaceholders(String input) {
        return input;
    }

    /**
     * Simple record to carry label + SNBT for show_item hover.
     */
    final class ItemTagInfo {
        public final String label;
        public final String showItemNbt;

        public ItemTagInfo(String label, String showItemNbt) {
            this.label = label;
            this.showItemNbt = showItemNbt;
        }
    }
}
