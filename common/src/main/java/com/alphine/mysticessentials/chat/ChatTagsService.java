package com.alphine.mysticessentials.chat;

import com.alphine.mysticessentials.chat.platform.CommonPlayer;
import com.alphine.mysticessentials.config.ChatConfigManager;
import com.alphine.mysticessentials.config.ChatConfigManager.TagsConfig;
import com.alphine.mysticessentials.config.ChatConfigManager.TagsConfig.Tag;

/**
 * Handles chat tags like <item>, <inv>, <ec>.
 *
 * For now:
 *  - <item> is fully implemented using CommonPlayer's item-tag helpers.
 *  - <inv> and <ec> still only mark presence in metadata for future GUI/hover work.
 */
public class ChatTagsService {

    public void process(ChatContext ctx) {
        TagsConfig cfg = ChatConfigManager.TAGS;
        String msg = ctx.processedMessage;
        if (cfg == null || !cfg.enabled) return;


        // Handle <item> <inv> and <ec> tags
        msg = applyItemTag(ctx, msg, cfg.item);
        msg = applyInventoryShareTag(ctx, msg, cfg.inventory);
        msg = applyEnderChestShareTag(ctx, msg, cfg.enderchest);

        ctx.processedMessage = msg;
    }

    // ------------------------------------------------------------------------
    // <item> tag: inline label + hover full tooltip
    // ------------------------------------------------------------------------

    private String applyItemTag(ChatContext ctx, String msg, Tag tag) {
        if (tag == null || !tag.enabled) return msg;
        if (ctx.sender == null) return msg;
        if (msg == null || msg.isEmpty()) return msg;

        boolean foundAlias = false;
        for (String alias : tag.aliases) {
            if (alias != null && !alias.isBlank() && msg.contains(alias)) {
                foundAlias = true;
                break;
            }
        }

        if (!foundAlias) {
            return msg;
        }

        CommonPlayer player = ctx.sender;
        if (!player.hasMainHandItem()) {
            String replacement = tag.hoverEmpty != null && !tag.hoverEmpty.isBlank()
                    ? tag.hoverEmpty
                    : "<gray>(no item)</gray>";

            for (String alias : tag.aliases) {
                if (alias == null || alias.isBlank()) continue;
                msg = msg.replace(alias, replacement);
            }

            ctx.metadata.put("has_item_tag", Boolean.TRUE);
            return msg;
        }

        CommonPlayer.ItemTagInfo info = player.getMainHandItemTagInfo();
        if (info == null || info.label == null || info.label.isBlank()) {
            String replacement = tag.hoverEmpty != null && !tag.hoverEmpty.isBlank()
                    ? tag.hoverEmpty
                    : "<gray>(no item)</gray>";

            for (String alias : tag.aliases) {
                if (alias == null || alias.isBlank()) continue;
                msg = msg.replace(alias, replacement);
            }

            ctx.metadata.put("has_item_tag", Boolean.TRUE);
            return msg;
        }

        String label = escapeMiniMessageText(info.label);
        String showItemNbt = info.showItemNbt;

        String replacement;
        if (showItemNbt != null && !showItemNbt.isBlank()) {
            // MiniMessage show_item hover:
            // <hover:show_item:{id:"minecraft:stone",Count:1b,...}>Label</hover>
            String nbtPart = showItemNbt.trim();
            replacement = "<hover:show_item:" + nbtPart + ">" + label + "</hover>";
        } else {
            replacement = label;
        }

        for (String alias : tag.aliases) {
            if (alias == null || alias.isBlank()) continue;
            msg = msg.replace(alias, replacement);
        }

        ctx.metadata.put("has_item_tag", Boolean.TRUE);
        return msg;
    }

    /**
     * Replace inventory tag aliases (<inv>, [inv], {inv}, etc.) with a clickable
     * MiniMessage snippet that runs /invshare <senderName>.
     *
     * /invshare snapshots the sender's inventory and opens it read-only for the viewer.
     */
    private String applyInventoryShareTag(ChatContext ctx, String msg, Tag tag) {
        if (tag == null || !tag.enabled || msg == null || msg.isEmpty()) {
            return msg;
        }

        String senderName = ctx.sender != null ? ctx.sender.getName() : "player";

        String label = (tag.displayText != null && !tag.displayText.isBlank())
                ? tag.displayText
                : "<light_purple>[INVENTORY]</light_purple>";

        StringBuilder hover = new StringBuilder();
        if (tag.hoverHeader != null && !tag.hoverHeader.isBlank()) {
            hover.append(tag.hoverHeader);
        }
        if (tag.hoverNote != null && !tag.hoverNote.isBlank()) {
            if (!hover.isEmpty()) hover.append("\\n");
            hover.append(tag.hoverNote);
        }
        if (hover.isEmpty()) {
            hover.append("<yellow>Click to view inventory snapshot</yellow>");
        }
        String hoverEscaped = hover.toString().replace("'", "''");

        String replacement =
                "<hover:show_text:'" + hoverEscaped + "'>" +
                        "<click:run_command:'/invshare " + senderName + "'>" +
                        label +
                        "</click></hover>";

        boolean found = false;
        for (String alias : tag.aliases) {
            if (alias == null || alias.isBlank()) continue;
            if (msg.contains(alias)) {
                msg = msg.replace(alias, replacement);
                found = true;
            }
        }
        if (found) {
            ctx.metadata.put("has_inventory_tag", Boolean.TRUE);
        }
        return msg;
    }

    /**
     * Replace ender chest tag aliases (<ec>, [ec], {ec}, etc.) with a clickable
     * MiniMessage snippet that runs /ecshare <senderName>.
     *
     * /ecshare snapshots the sender's ender chest and opens it read-only.
     */
    private String applyEnderChestShareTag(ChatContext ctx, String msg, Tag tag) {
        if (tag == null || !tag.enabled || msg == null || msg.isEmpty()) {
            return msg;
        }

        String senderName = ctx.sender != null ? ctx.sender.getName() : "player";

        String label = (tag.displayText != null && !tag.displayText.isBlank())
                ? tag.displayText
                : "<dark_purple>[ENDER CHEST]</dark_purple>";

        StringBuilder hover = new StringBuilder();
        if (tag.hoverHeader != null && !tag.hoverHeader.isBlank()) {
            hover.append(tag.hoverHeader);
        }
        if (tag.hoverNote != null && !tag.hoverNote.isBlank()) {
            if (!hover.isEmpty()) hover.append("\\n");
            hover.append(tag.hoverNote);
        }
        if (hover.isEmpty()) {
            hover.append("<yellow>Click to view ender chest snapshot</yellow>");
        }
        String hoverEscaped = hover.toString().replace("'", "''");

        String replacement =
                "<hover:show_text:'" + hoverEscaped + "'>" +
                        "<click:run_command:'/ecshare " + senderName + "'>" +
                        label +
                        "</click></hover>";

        boolean found = false;
        for (String alias : tag.aliases) {
            if (alias == null || alias.isBlank()) continue;
            if (msg.contains(alias)) {
                msg = msg.replace(alias, replacement);
                found = true;
            }
        }
        if (found) {
            ctx.metadata.put("has_enderchest_tag", Boolean.TRUE);
        }
        return msg;
    }

    // ------------------------------------------------------------------------
    // Mark presence for other tags (future expansion)
    // ------------------------------------------------------------------------

    /**
     * Minimal escaping so that item display names like "Diamond <Sword>" don't break MiniMessage.
     * We escape '<' as '\<' which MiniMessage treats as literal.
     */
    private String escapeMiniMessageText(String input) {
        if (input == null || input.isEmpty()) return input;
        return input.replace("<", "\\<");
    }
}
