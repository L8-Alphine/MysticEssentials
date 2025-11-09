package com.alphine.mysticessentials.commands.admin;

import com.alphine.mysticessentials.perm.Perms;
import com.alphine.mysticessentials.platform.ModInfoService;
import com.alphine.mysticessentials.platform.PasteService;
import com.alphine.mysticessentials.util.MessagesUtil; // ← add
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
import java.util.Map;

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

        src.sendSystemMessage(MessagesUtil.msg("modlist.header",
                Map.of("shown", filtered.size(), "total", all.size())));

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
        if (filtered.size() > 20) {
            String cmd = "/modlist " + filter + " true";
            src.sendSystemMessage(
                    Component.empty()
                            .append(MessagesUtil.msg("modlist.more_hint", Map.of("more", filtered.size() - 20)))
                            .append(Component.literal(" "))
                            .append(Component.literal(cmd).withStyle(s -> s
                                    .withColor(ChatFormatting.AQUA)
                                    .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, cmd))
                                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                            MessagesUtil.msg("modlist.upload_hover")))))
            );
        }

        if (upload) {
            String text = filtered.stream()
                    .map(m -> String.format("%s | %s | %s | %s%s",
                            m.id(), m.name(), m.version(), m.active()? "ENABLED":"DISABLED",
                            m.sourceJar().map(p -> " | " + p).orElse("")))
                    .collect(Collectors.joining("\n"));
            try {
                String url = PasteService.uploadToMclogs(text);
                src.sendSystemMessage(
                        Component.empty()
                                .append(MessagesUtil.msg("modlist.upload_ok"))
                                .append(Component.literal(url).withStyle(s -> s
                                        .withColor(ChatFormatting.AQUA)
                                        .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                                MessagesUtil.msg("modlist.open_browser")))))
                );
            } catch (Exception e) {
                src.sendFailure(MessagesUtil.msg("modlist.upload_fail", Map.of("error", e.getMessage())));
                return 0;
            }
        }
        return 1;
    }
}
