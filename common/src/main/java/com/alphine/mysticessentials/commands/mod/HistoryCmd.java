package com.alphine.mysticessentials.commands.mod;

import com.alphine.mysticessentials.storage.AuditLogStore;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import com.mojang.brigadier.context.CommandContext;
import com.alphine.mysticessentials.util.MessagesUtil;
import net.minecraft.commands.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class HistoryCmd {
    private static final int PAGE_SIZE = 10;

    private final AuditLogStore audit;
    public HistoryCmd(AuditLogStore audit){ this.audit=audit; }

    public void register(CommandDispatcher<CommandSourceStack> d){
        // /history <player> [page]
        d.register(Commands.literal("history")
                .requires(src -> Perms.has(src, PermNodes.HISTORY_USE, 2))
                .then(Commands.argument("player", StringArgumentType.word())
                        .executes(ctx -> show(ctx, 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> show(ctx, IntegerArgumentType.getInteger(ctx, "page")))
                        )
                )
        );

        // /historyrecent [page]  -> latest actions globally
        d.register(Commands.literal("historyrecent")
                .requires(src -> Perms.has(src, PermNodes.HISTORY_USE, 2))
                .executes(ctx -> showRecent(ctx, 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(ctx -> showRecent(ctx, IntegerArgumentType.getInteger(ctx, "page")))
                )
        );
    }

    private int show(CommandContext<CommandSourceStack> ctx, int page){
        var src = ctx.getSource();
        String name = StringArgumentType.getString(ctx,"player");
        var prof = src.getServer().getProfileCache().get(name).orElse(null);
        if (prof == null) { src.sendFailure(MessagesUtil.msg("profile.unknown")); return 0; }

        var all = audit.byTarget(prof.getId(), Integer.MAX_VALUE); // full list; we’ll page it
        if (all.isEmpty()) { src.sendSuccess(() -> MessagesUtil.msg("history.none_for_player", Map.of("player", name)), false); return 1; }

        // reverse chronological
        Collections.reverse(all);
        int maxPage = (int)Math.ceil(all.size() / (double) PAGE_SIZE);
        page = Math.min(Math.max(page, 1), Math.max(maxPage,1));
        int from = (page-1)*PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, all.size());
        var slice = all.subList(from, to);

        var df = utc();
        int finalPage = page;
        src.sendSuccess(() -> MessagesUtil.msg("history.header", Map.of("player", name, "page", finalPage, "max", maxPage)), false);
        for (var e : slice){
            src.sendSuccess(() -> MessagesUtil.msg("history.entry.raw", Map.of("line", formatLine(e, df))), false);
        }
        if (page < maxPage) {
            int next = page + 1;
            src.sendSuccess(() -> MessagesUtil.msg("history.next", Map.of("player", name, "next", next)), false);
        }
        return 1;
    }

    private int showRecent(CommandContext<CommandSourceStack> ctx, int page){
        var src = ctx.getSource();
        var all = audit.recent(5000); // cap safety
        if (all.isEmpty()) { src.sendSuccess(() -> MessagesUtil.msg("historyrecent.none"), false); return 1; }

        int maxPage = (int)Math.ceil(all.size() / (double) PAGE_SIZE);
        page = Math.min(Math.max(page, 1), Math.max(maxPage,1));
        int from = (page-1)*PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, all.size());
        var slice = all.subList(from, to);

        var df = utc();
        int finalPage = page;
        src.sendSuccess(() -> MessagesUtil.msg("historyrecent.header", Map.of("page", finalPage, "max", maxPage)), false);
        for (var e : slice){
            src.sendSuccess(() -> MessagesUtil.msg("history.entry.raw", Map.of("line", formatLine(e, df))), false);
        }
        if (page < maxPage) {
            int next = page + 1;
            src.sendSuccess(() -> MessagesUtil.msg("historyrecent.next", Map.of("next", next)), false);
        }
        return 1;
    }

    private static SimpleDateFormat utc(){
        var df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        df.setTimeZone(TimeZone.getTimeZone("UTC"));
        return df;
    }

    private static String formatLine(AuditLogStore.Entry e, SimpleDateFormat df){
        String when = df.format(new Date(e.at)) + " UTC";
        String until = e.until==null? "" : (" §7until §e"+df.format(new Date(e.until))+" UTC");
        String who = e.actor==null? "Console" : e.actor.toString().substring(0,8);
        String target = (e.targetName!=null? e.targetName : (e.target==null? "-" : e.target.toString().substring(0,8)));
        String ip = (e.ip!=null? " §7ip §e"+e.ip : "");
        String extra = (e.extra!=null && !e.extra.isBlank()? " §7| §f"+e.extra : "");
        String reason = (e.reason==null || e.reason.isBlank())? "" : (" §7| §f"+e.reason);
        return "§7- §b"+e.action+" §8| §7"+when+" §8| §7by §e"+who+" §8| §7target §e"+target+until+ip+extra+reason;
    }
}
