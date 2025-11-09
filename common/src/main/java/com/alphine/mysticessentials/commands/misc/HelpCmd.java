package com.alphine.mysticessentials.commands.misc;

import com.alphine.mysticessentials.commands.CommandIndex;
import com.alphine.mysticessentials.util.MessagesUtil;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.*;

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
        List<CommandNode<CommandSourceStack>> visible = root.getChildren().stream()
                .filter(n -> n.canUse(src))
                .sorted(Comparator.comparing(CommandNode::getName))
                .collect(Collectors.toList());

        String f = filter.map(String::toLowerCase).orElse(null);
        String mf = modFilter.map(String::toLowerCase).orElse(null);

        List<Entry> entries = new ArrayList<>();
        for (var n : visible) {
            String name = n.getName();
            String mod = CommandIndex.modOf(name);
            if (f != null && !(name.toLowerCase().contains(f))) continue;
            if (mf != null && !mod.equals(mf)) continue;

            var usage = src.getServer().getCommands().getDispatcher().getSmartUsage(n, src);
            if (usage.isEmpty()) {
                entries.add(new Entry(name, mod, "/" + name));
            } else {
                for (var u : usage.values()) entries.add(new Entry(name, mod, "/" + name + " " + u));
            }
        }

        int pages = Math.max(1, (int)Math.ceil(entries.size() / (double) PAGE_SIZE));
        page = Math.max(1, Math.min(page, pages));
        int from = (page-1)*PAGE_SIZE, to = Math.min(entries.size(), from+PAGE_SIZE);

        // Header (localized)
        src.sendSystemMessage(
                MessagesUtil.msg("help.header", Map.of(
                        "total", entries.size(),
                        "page", page,
                        "pages", pages,
                        "filter", filter.orElse(""),
                        "mod", modFilter.orElse("")
                ))
        );

        for (int i = from; i < to; i++) {
            var e = entries.get(i);
            var line = Component.literal(e.usage())
                    .withStyle(ChatFormatting.AQUA)
                    .withStyle(style -> style
                            .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, e.usage()))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    MessagesUtil.msg("help.hover.copy_run", Map.of("mod", e.modId())))));

            src.sendSystemMessage(
                    Component.literal("[/" + e.root() + "] ").withStyle(ChatFormatting.GRAY)
                            .append(Component.literal("[" + e.modId() + "] ").withStyle(ChatFormatting.DARK_GRAY))
                            .append(line)
            );
        }

        if (pages > 1) {
            var prev = page > 1
                    ? pagerButton("help.pager.prev", page - 1, filter, modFilter)
                    : MessagesUtil.msg("help.pager.prev.disabled");

            var next = page < pages
                    ? pagerButton("help.pager.next", page + 1, filter, modFilter)
                    : MessagesUtil.msg("help.pager.next.disabled");

            src.sendSystemMessage(prev.copy().append(Component.literal("  ")).append(next));
        }
        return 1;
    }

    private Component pagerButton(String labelKey, int page, Optional<String> filter, Optional<String> mod) {
        final String cmd = mod.isPresent()
                ? "/help mod " + mod.get() + " " + page + (filter.isPresent() ? " " + filter.get() : "")
                : "/help " + page + (filter.isPresent() ? " " + filter.get() : "");

        // Start from msg() → copy() to get a MutableComponent
        MutableComponent c = MessagesUtil.msg(labelKey).copy();

        // Build a Style and apply it
        Style styled = c.getStyle()
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        MessagesUtil.msg("help.pager.hover", Map.of("page", page))));

        c.setStyle(styled);
        return c;
    }

    private record Entry(String root, String modId, String usage) {}
}
