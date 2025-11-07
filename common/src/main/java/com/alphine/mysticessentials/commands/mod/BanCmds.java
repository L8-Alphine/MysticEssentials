package com.alphine.mysticessentials.commands.mod;

import com.alphine.mysticessentials.storage.AuditLogStore;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.alphine.mysticessentials.storage.PunishStore;
import com.alphine.mysticessentials.util.DurationUtil;
import com.alphine.mysticessentials.perm.*;
import com.alphine.mysticessentials.util.ModerationPerms;
import net.minecraft.commands.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Date;

public class BanCmds {
    private final PunishStore store;
    private final AuditLogStore audit;
    public BanCmds(PunishStore store, AuditLogStore audit){ this.store=store; this.audit=audit; }

    public void register(CommandDispatcher<CommandSourceStack> d){
        // /ban <player> <reason...>
        d.register(Commands.literal("ban")
                .requires(src -> Perms.has(src, PermNodes.BAN_USE, 2))
                .then(Commands.argument("player", StringArgumentType.word())
                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    ServerPlayer actor = ctx.getSource().getPlayerOrException();
                                    String name = StringArgumentType.getString(ctx,"player");
                                    String reason = StringArgumentType.getString(ctx,"reason");
                                    ServerPlayer target = actor.getServer().getPlayerList().getPlayerByName(name);
                                    if(target==null){ actor.displayClientMessage(Component.literal("§cPlayer not found."), false); return 0; }
                                    if (ModerationPerms.exempt(target, "ban")) { actor.displayClientMessage(Component.literal("§cTarget is exempt."), false); return 0; }

                                    PunishStore.Ban b = new PunishStore.Ban();
                                    b.target = target.getUUID(); b.actor = actor.getUUID(); b.reason=reason; b.at=System.currentTimeMillis(); b.until=null;
                                    store.banUuid(b);

                                    target.connection.disconnect(Component.literal("§cBanned: §f"+reason));
                                    actor.displayClientMessage(Component.literal("§aBanned §e"+name), false);
                                    audit.log(AuditLogStore.make("BAN", actor.getUUID(), target.getUUID(), target.getName().getString(), reason, null, null, null));
                                    return 1;
                                })
                        )
                )
        );

        // /tempban <player> <duration> <reason...>
        d.register(Commands.literal("tempban")
                .requires(src -> Perms.has(src, PermNodes.TEMPBAN_USE, 2))
                .then(Commands.argument("player", StringArgumentType.word())
                        .then(Commands.argument("duration", StringArgumentType.word())
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            ServerPlayer actor = ctx.getSource().getPlayerOrException();
                                            String name = StringArgumentType.getString(ctx,"player");
                                            String durStr = StringArgumentType.getString(ctx,"duration");
                                            String reason = StringArgumentType.getString(ctx,"reason");
                                            long ms = DurationUtil.parseToMillis(durStr);
                                            if(ms<=0){ actor.displayClientMessage(Component.literal("§cInvalid duration."), false); return 0; }
                                            ServerPlayer target = actor.getServer().getPlayerList().getPlayerByName(name);
                                            if(target==null){ actor.displayClientMessage(Component.literal("§cPlayer not found."), false); return 0; }
                                            if (ModerationPerms.exempt(target, "tempban")) { actor.displayClientMessage(Component.literal("§cTarget is exempt."), false); return 0; }

                                            PunishStore.Ban b = new PunishStore.Ban();
                                            b.target=target.getUUID(); b.actor=actor.getUUID(); b.reason=reason; b.at=System.currentTimeMillis(); b.until=b.at+ms;
                                            store.banUuid(b);
                                            target.connection.disconnect(Component.literal("§cTemp-banned until §e"+new Date(b.until)+"§c: §f"+reason));
                                            actor.displayClientMessage(Component.literal("§aTemp-banned §e"+name+" §7for §e"+durStr), false);
                                            audit.log(AuditLogStore.make("TEMPBAN", actor.getUUID(), target.getUUID(), target.getName().getString(), reason, b.until, null, null));
                                            return 1;
                                        })
                                )
                        )
                )
        );

        // /unban <player>
        d.register(Commands.literal("unban")
                .requires(src -> Perms.has(src, PermNodes.UNBAN_USE, 2))
                .then(Commands.argument("player", StringArgumentType.word())
                        .executes(ctx -> {
                            var src = ctx.getSource();
                            String name = StringArgumentType.getString(ctx,"player");
                            var profile = src.getServer().getProfileCache().get(name).orElse(null);
                            if(profile==null){ src.sendFailure(Component.literal("§cUnknown profile.")); return 0; }
                            store.unbanUuid(profile.getId());
                            src.sendSuccess(() -> Component.literal("§aUnbanned §e"+name), false);
                            audit.log(AuditLogStore.make("UNBAN", src.getPlayerOrException().getUUID(), profile.getId(), profile.getName(), null, null, null, null));
                            return 1;
                        })
                )
        );

        // /banlist
        d.register(Commands.literal("banlist")
                .requires(src -> Perms.has(src, PermNodes.BANLIST_USE, 2))
                .executes(ctx -> {
                    var list = store.allBans();
                    if(list.isEmpty()){ ctx.getSource().sendSuccess(() -> Component.literal("§7No active bans."), false); return 1; }
                    ctx.getSource().sendSuccess(() -> Component.literal("§aActive bans:"), false);
                    for(var b:list){
                        String who = b.target!=null? b.target.toString() : b.ip;
                        String until = (b.until==null? "permanent" : new Date(b.until).toString());
                        ctx.getSource().sendSuccess(() -> Component.literal("§7- §e"+who+" §7until §e"+until+" §7reason §f"+b.reason), false);
                    }
                    return 1;
                })
        );
    }
}
