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
        // Primary root: /mhelp
        var root = Commands.literal("mhelp")
                // /mhelp
                .executes(ctx -> show(ctx.getSource(), Optional.empty(), Optional.empty(), 1))

                // /mhelp <page>
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(ctx -> show(
                                ctx.getSource(),
                                Optional.empty(),
                                Optional.empty(),
                                IntegerArgumentType.getInteger(ctx, "page")
                        ))
                        // /mhelp <page> <filter...>
                        .then(Commands.argument("filter", StringArgumentType.greedyString())
                                .executes(ctx -> show(
                                        ctx.getSource(),
                                        Optional.of(StringArgumentType.getString(ctx, "filter")),
                                        Optional.empty(),
                                        IntegerArgumentType.getInteger(ctx, "page")
                                ))
                        )
                )

                // /mhelp <filter...>
                .then(Commands.argument("filter", StringArgumentType.greedyString())
                        .executes(ctx -> show(
                                ctx.getSource(),
                                Optional.of(StringArgumentType.getString(ctx, "filter")),
                                Optional.empty(),
                                1
                        ))
                )

                // /mhelp mod <modid>
                .then(Commands.literal("mod")
                        .then(Commands.argument("modid", StringArgumentType.word())
                                .executes(ctx -> show(
                                        ctx.getSource(),
                                        Optional.empty(),
                                        Optional.of(StringArgumentType.getString(ctx, "modid")),
                                        1
                                ))
                                // /mhelp mod <modid> <page>
                                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                        .executes(ctx -> show(
                                                ctx.getSource(),
                                                Optional.empty(),
                                                Optional.of(StringArgumentType.getString(ctx, "modid")),
                                                IntegerArgumentType.getInteger(ctx, "page")
                                        ))
                                )
                        )
                );

        CommandNode<CommandSourceStack> rootNode = d.register(root);

        // Optional: make /help and /? redirect to our help as well.
        // If vanilla ever causes drama, you can comment these two out.
        d.register(Commands.literal("help").redirect(rootNode));
        d.register(Commands.literal("?").redirect(rootNode));
    }

    private int show(CommandSourceStack src,
                     Optional<String> filter,
                     Optional<String> modFilter,
                     int page) {

        RootCommandNode<CommandSourceStack> root =
                src.getServer().getCommands().getDispatcher().getRoot();

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

            if (f != null && !name.toLowerCase().contains(f)) continue;
            if (mf != null && !mod.equalsIgnoreCase(mf)) continue;

            var usage = src.getServer().getCommands().getDispatcher().getSmartUsage(n, src);
            if (usage.isEmpty()) {
                entries.add(new Entry(name, mod, "/" + name));
            } else {
                for (var u : usage.values()) {
                    entries.add(new Entry(name, mod, "/" + name + " " + u));
                }
            }
        }

        int pages = Math.max(1, (int) Math.ceil(entries.size() / (double) PAGE_SIZE));
        page = Math.max(1, Math.min(page, pages));
        int from = (page - 1) * PAGE_SIZE;
        int to = Math.min(entries.size(), from + PAGE_SIZE);

        // Header
        src.sendSystemMessage(
                MessagesUtil.msg("help.header", Map.of(
                        "total", entries.size(),
                        "page", page,
                        "pages", pages,
                        "filter", filter.orElse(""),
                        "mod", modFilter.orElse("")
                ))
        );

        // Lines
        for (int i = from; i < to; i++) {
            var e = entries.get(i);

            MutableComponent usageLine = Component.literal(e.usage())
                    .withStyle(ChatFormatting.AQUA)
                    .withStyle(style -> style
                            .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, e.usage()))
                            .withHoverEvent(new HoverEvent(
                                    HoverEvent.Action.SHOW_TEXT,
                                    MessagesUtil.msg("help.hover.copy_run", Map.of("mod", e.modId()))
                            ))
                    );

            src.sendSystemMessage(
                    Component.literal("[" + e.modId() + "] ").withStyle(ChatFormatting.DARK_GRAY)
                            .append(usageLine)
            );
        }

        // Pager
        if (pages > 1) {
            Component prev = page > 1
                    ? pagerButton("help.pager.prev", page - 1, filter, modFilter)
                    : MessagesUtil.msg("help.pager.prev.disabled");

            Component next = page < pages
                    ? pagerButton("help.pager.next", page + 1, filter, modFilter)
                    : MessagesUtil.msg("help.pager.next.disabled");

            src.sendSystemMessage(prev.copy().append(Component.literal("  ")).append(next));
        }

        return 1;
    }

    private Component pagerButton(String labelKey,
                                  int page,
                                  Optional<String> filter,
                                  Optional<String> mod) {

        // We ALWAYS page using /mhelp so vanilla /help can’t interfere.
        String cmd;
        if (mod.isPresent()) {
            cmd = "/mhelp mod " + mod.get() + " " + page;
        } else {
            cmd = "/mhelp " + page;
            if (filter.isPresent() && !filter.get().isBlank()) {
                cmd += " " + filter.get();
            }
        }

        // Use plain text from the translation and apply style here
        String labelText = MessagesUtil.msg(labelKey).getString();

        String finalCmd = cmd;
        MutableComponent c = Component.literal(labelText)
                .withStyle(style -> style
                        .withColor(ChatFormatting.YELLOW)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, finalCmd))
                        .withHoverEvent(new HoverEvent(
                                HoverEvent.Action.SHOW_TEXT,
                                MessagesUtil.msg("help.pager.hover", Map.of("page", page))
                        ))
                );

        return c;
    }

    private record Entry(String root, String modId, String usage) {}
}
