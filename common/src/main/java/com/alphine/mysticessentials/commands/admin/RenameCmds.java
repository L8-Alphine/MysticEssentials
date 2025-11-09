package com.alphine.mysticessentials.commands.admin;

import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import com.alphine.mysticessentials.util.MessagesUtil;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class RenameCmds {

    public void register(CommandDispatcher<CommandSourceStack> d) {
        // /rename <name...>
        d.register(Commands.literal("rename")
                .requires(src -> Perms.has(src, PermNodes.RENAME_USE, 2))
                .then(Commands.argument("name", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            ItemStack item = p.getMainHandItem();
                            if (item.isEmpty()) {
                                p.displayClientMessage(MessagesUtil.msg("rename.no_item"), false);
                                return 0;
                            }
                            if (item.isEnchanted() && !Perms.has(p.createCommandSourceStack(), PermNodes.RENAME_ENCHANTED, 2)) {
                                p.displayClientMessage(MessagesUtil.msg("rename.denied.enchanted"), false);
                                return 0;
                            }
                            if (item.has(DataComponents.CUSTOM_DATA) && !Perms.has(p.createCommandSourceStack(), PermNodes.RENAME_UNIQUE, 2)) {
                                p.displayClientMessage(MessagesUtil.msg("rename.denied.unique"), false);
                                return 0;
                            }

                            String raw = StringArgumentType.getString(ctx, "name");
                            String colored = raw.replace('&', ChatFormatting.PREFIX_CODE);
                            item.set(DataComponents.CUSTOM_NAME, Component.literal(colored));

                            var ph = new HashMap<String, Object>();
                            ph.put("name", colored);
                            p.displayClientMessage(MessagesUtil.msg("rename.success", ph), false);
                            return 1;
                        })
                )
        );

        // /lore [line] <text...>
        d.register(Commands.literal("lore")
                .requires(src -> Perms.has(src, PermNodes.RENAME_LORE, 2))
                .then(Commands.argument("text", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            return setLoreAtLine(p, -1, StringArgumentType.getString(ctx, "text"));
                        }))
                .then(Commands.argument("line", IntegerArgumentType.integer(1))
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                                    int line = IntegerArgumentType.getInteger(ctx, "line");
                                    String text = StringArgumentType.getString(ctx, "text");
                                    return setLoreAtLine(p, line, text);
                                })
                        )
                )
        );
    }

    private static int setLoreAtLine(ServerPlayer p, int line1Based, String text) {
        ItemStack item = p.getMainHandItem();
        if (item.isEmpty()) {
            p.displayClientMessage(MessagesUtil.msg("lore.no_item"), false);
            return 0;
        }
        if (item.isEnchanted() && !Perms.has(p.createCommandSourceStack(), PermNodes.RENAME_ENCHANTED, 2)) {
            p.displayClientMessage(MessagesUtil.msg("lore.denied.enchanted"), false);
            return 0;
        }
        if (item.has(DataComponents.CUSTOM_DATA) && !Perms.has(p.createCommandSourceStack(), PermNodes.RENAME_UNIQUE, 2)) {
            p.displayClientMessage(MessagesUtil.msg("lore.denied.unique"), false);
            return 0;
        }

        String colored = text.replace('&', ChatFormatting.PREFIX_CODE);
        ItemLore current = item.getOrDefault(DataComponents.LORE, ItemLore.EMPTY);
        List<Component> lines = new ArrayList<>(current.lines());

        if (line1Based <= 0) {
            lines.clear();
            lines.add(Component.literal(colored));
        } else {
            int idx = line1Based - 1;
            while (lines.size() <= idx) lines.add(Component.literal(""));
            lines.set(idx, Component.literal(colored));
        }

        item.set(DataComponents.LORE, new ItemLore(lines));
        p.displayClientMessage(MessagesUtil.msg("lore.updated"), false);
        return 1;
    }
}
