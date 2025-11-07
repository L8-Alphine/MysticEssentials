package com.alphine.mysticessentials.commands.admin;

import com.alphine.mysticessentials.perm.Perms;
import com.alphine.mysticessentials.platform.ModInfoService;
import com.alphine.mysticessentials.platform.PasteService;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

import java.util.Comparator;
import java.util.Locale;
import java.util.stream.Collectors;

public class ModlistCmd {
    private final ModInfoService mods;

    public ModlistCmd(ModInfoService mods) { this.mods = mods; }

    public void register(CommandDispatcher<CommandSourceStack> d) {
        var root = Commands.literal("modlist")
                .requires(src -> src.hasPermission(2) || Perms.has(src, "messentials.modlist", 2))
                .executes(ctx -> show(ctx.getSource(), "all", false))
                .then(Commands.argument("filter", StringArgumentType.word())
                        .executes(ctx -> show(ctx.getSource(),
                                ctx.getArgument("filter", String.class), false))
                        .then(Commands.argument("upload", BoolArgumentType.bool())
                                .executes(ctx -> show(ctx.getSource(),
                                        ctx.getArgument("filter", String.class),
                                        ctx.getArgument("upload", Boolean.class)))));

        // sub-literal: /modlist upload
        root.then(Commands.literal("upload")
                .executes(ctx -> show(ctx.getSource(), "all", true)));

        d.register(root);
    }

    private int show(CommandSourceStack src, String filter, boolean upload) {
        var all = mods.getAllMods().stream()
                .sorted(Comparator.comparing(ModInfoService.ModInfo::id))
                .toList();

        String norm = filter.toLowerCase(Locale.ROOT);
        var filtered = all.stream().filter(m -> {
            if ("all".equals(norm)) return true;
            if ("enabled".equals(norm)) return m.active();
            if ("disabled".equals(norm)) return !m.active();
            return m.id().toLowerCase(Locale.ROOT).contains(norm) ||
                    m.name().toLowerCase(Locale.ROOT).contains(norm);
        }).toList();

        // Pretty chat view (first 20 + hint)
        src.sendSystemMessage(Component.literal("— Mods (" + filtered.size() + "/" + all.size() + ") —")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));

        int shown = 0;
        for (var m : filtered) {
            if (shown++ >= 20) break;
            var line = Component.literal(m.id())
                    .withStyle(m.active()? ChatFormatting.GREEN : ChatFormatting.RED)
                    .append(Component.literal("  "))
                    .append(Component.literal(m.name()).withStyle(ChatFormatting.WHITE))
                    .append(Component.literal("  v" + m.version()).withStyle(ChatFormatting.DARK_GRAY));
            src.sendSystemMessage(line);
        }
        if (filtered.size() > 20)
            src.sendSystemMessage(Component.literal("…and " + (filtered.size()-20) + " more. Use ")
                    .append(Component.literal("/modlist " + filter + " true")
                            .withStyle(style -> style
                                    .withColor(ChatFormatting.AQUA)
                                    .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/modlist " + filter + " true"))
                                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Upload full list to mclo.gs")))))
                    .append(Component.literal(" to upload.")));

        if (upload) {
            String text = filtered.stream()
                    .map(m -> String.format("%s | %s | %s | %s%s",
                            m.id(), m.name(), m.version(), m.active()? "ENABLED":"DISABLED",
                            m.sourceJar().map(p -> " | " + p).orElse("")))
                    .collect(Collectors.joining("\n"));
            try {
                String url = PasteService.uploadToMclogs(text);
                src.sendSystemMessage(Component.literal("Uploaded to mclo.gs → ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(url).withStyle(style -> style
                                .withColor(ChatFormatting.AQUA)
                                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Open in browser"))))));
            } catch (Exception e) {
                src.sendFailure(Component.literal("Upload failed: " + e.getMessage()).withStyle(ChatFormatting.RED));
                return 0;
            }
        }
        return 1;
    }
}
