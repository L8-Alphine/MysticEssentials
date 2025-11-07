package com.alphine.mysticessentials.commands.misc;

import com.alphine.mysticessentials.commands.CommandIndex;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

import java.util.*;
import java.util.stream.Collectors;

public class HelpCmd {
    private static final int PAGE_SIZE = 8;

    public void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("help")
                .executes(ctx -> show(ctx.getSource(), Optional.empty(), Optional.empty(), 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(ctx -> show(ctx.getSource(), Optional.empty(), Optional.empty(),
                                IntegerArgumentType.getInteger(ctx, "page"))))
                .then(Commands.argument("filter", StringArgumentType.greedyString())
                        .executes(ctx -> show(ctx.getSource(),
                                Optional.of(StringArgumentType.getString(ctx, "filter")),
                                Optional.empty(), 1)))
                .then(Commands.literal("mod")
                        .then(Commands.argument("modid", StringArgumentType.word())
                                .executes(ctx -> show(ctx.getSource(),
                                        Optional.empty(),
                                        Optional.of(StringArgumentType.getString(ctx, "modid")),
                                        1)
                                ))));
    }

    private int show(CommandSourceStack src, Optional<String> filter, Optional<String> modFilter, int page) {
        RootCommandNode<CommandSourceStack> root = src.getServer().getCommands().getDispatcher().getRoot();

        // Collect visible root commands for this source (permission-aware)
        List<CommandNode<CommandSourceStack>> visible = root.getChildren().stream()
                .filter(n -> n.canUse(src)) // respects requires()
                .sorted(Comparator.comparing(CommandNode::getName))
                .collect(Collectors.toList());

        // Filter by string and/or mod id mapping
        String f = filter.map(String::toLowerCase).orElse(null);
        String mf = modFilter.map(String::toLowerCase).orElse(null);

        List<Entry> entries = new ArrayList<>();
        for (var n : visible) {
            String name = n.getName();
            String mod = CommandIndex.modOf(name);
            if (f != null && !(name.toLowerCase().contains(f))) continue;
            if (mf != null && !mod.equals(mf)) continue;

            // smart usage for this node
            var usage = src.getServer().getCommands().getDispatcher().getSmartUsage(n, src);
            if (usage.isEmpty()) {
                entries.add(new Entry(name, mod, "/" + name));
            } else {
                for (var u : usage.values()) {
                    entries.add(new Entry(name, mod, "/" + name + " " + u));
                }
            }
        }

        int pages = Math.max(1, (int)Math.ceil(entries.size() / (double) PAGE_SIZE));
        page = Math.max(1, Math.min(page, pages));
        int from = (page-1)*PAGE_SIZE, to = Math.min(entries.size(), from+PAGE_SIZE);

        src.sendSystemMessage(titleComponent(entries.size(), page, pages, filter, modFilter));

        for (int i = from; i < to; i++) {
            var e = entries.get(i);
            var line = Component.literal(e.usage())
                    .withStyle(ChatFormatting.AQUA)
                    .withStyle(style -> style
                            .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, e.usage()))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    Component.literal("Click to copy/run\nMod: " + e.modId()))));
            // show root + mod tag
            src.sendSystemMessage(
                    Component.literal("[/" + e.root() + "] ")
                            .withStyle(ChatFormatting.GRAY)
                            .append(Component.literal("[" + e.modId() + "] ").withStyle(ChatFormatting.DARK_GRAY))
                            .append(line)
            );
        }

        // Pager
        if (pages > 1) {
            var prev = page > 1
                    ? pagerButton("← Prev", page - 1, filter, modFilter)
                    : Component.literal("← Prev").withStyle(ChatFormatting.DARK_GRAY); // this is a MutableComponent
            var next = page < pages
                    ? pagerButton("Next →", page + 1, filter, modFilter)
                    : Component.literal("Next →").withStyle(ChatFormatting.DARK_GRAY);

            // copy() avoids mutating the 'prev' instance if it were reused later
            src.sendSystemMessage(prev.copy().append(Component.literal("  ")).append(next));
        }
        return 1;
    }

    private Component titleComponent(int total, int page, int pages, Optional<String> filter, Optional<String> mod) {
        String base = "— Help (" + total + " cmds";
        if (filter.isPresent()) base += ", filter=\"" + filter.get() + "\"";
        if (mod.isPresent()) base += ", mod=" + mod.get();
        base += ") — Page " + page + "/" + pages;
        return Component.literal(base).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
    }

    private net.minecraft.network.chat.MutableComponent pagerButton(
            String label, int page, Optional<String> filter, Optional<String> mod
    ) {
        String cmd;
        if (mod.isPresent()) {
            // /help mod <modid> <page> [filter...]
            cmd = "/help mod " + mod.get() + " " + page;
            if (filter.isPresent()) cmd += " " + filter.get();
        } else {
            // /help <page> [filter...]
            cmd = "/help " + page;
            if (filter.isPresent()) cmd += " " + filter.get();
        }

        String hover = "Go to page " + page;
        String finalCmd = cmd;
        return Component.literal(label).withStyle(style -> style
                .withColor(ChatFormatting.YELLOW)
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, finalCmd))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover))));
    }

    private record Entry(String root, String modId, String usage) {}
}
