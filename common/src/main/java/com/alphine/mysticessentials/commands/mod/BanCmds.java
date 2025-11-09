package com.alphine.mysticessentials.commands.mod;

import com.alphine.mysticessentials.storage.AuditLogStore;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.alphine.mysticessentials.storage.PunishStore;
import com.alphine.mysticessentials.util.DurationUtil;
import com.alphine.mysticessentials.perm.*;
import com.alphine.mysticessentials.util.ModerationPerms;
import com.alphine.mysticessentials.util.MessagesUtil;
import net.minecraft.commands.*;
import net.minecraft.server.level.ServerPlayer;

import java.util.Date;
import java.util.Map;

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
                                    if(target==null){ actor.displayClientMessage(MessagesUtil.msg("tp.player_not_found"), false); return 0; }
                                    if (ModerationPerms.exempt(target, "ban")) { actor.displayClientMessage(MessagesUtil.msg("moderation.target_exempt"), false); return 0; }

                                    PunishStore.Ban b = new PunishStore.Ban();
                                    b.target = target.getUUID(); b.actor = actor.getUUID(); b.reason = reason; b.at = System.currentTimeMillis(); b.until = null;
                                    store.banUuid(b);

                                    target.connection.disconnect(MessagesUtil.msg("ban.notify.to.perm", Map.of("reason", reason)));
                                    actor.displayClientMessage(MessagesUtil.msg("ban.ok.perm", Map.of("player", name)), false);
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
                                            if(ms<=0){ actor.displayClientMessage(MessagesUtil.msg("duration.invalid"), false); return 0; }
                                            ServerPlayer target = actor.getServer().getPlayerList().getPlayerByName(name);
                                            if(target==null){ actor.displayClientMessage(MessagesUtil.msg("tp.player_not_found"), false); return 0; }
                                            if (ModerationPerms.exempt(target, "tempban")) { actor.displayClientMessage(MessagesUtil.msg("moderation.target_exempt"), false); return 0; }

                                            PunishStore.Ban b = new PunishStore.Ban();
                                            b.target=target.getUUID(); b.actor=actor.getUUID(); b.reason=reason; b.at=System.currentTimeMillis(); b.until=b.at+ms;
                                            store.banUuid(b);

                                            String untilStr = new Date(b.until).toString();
                                            target.connection.disconnect(MessagesUtil.msg("ban.notify.to.temp", Map.of("until", untilStr, "reason", reason)));
                                            actor.displayClientMessage(MessagesUtil.msg("ban.ok.temp", Map.of("player", name, "duration", durStr)), false);
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
                            if(profile==null){ src.sendFailure(MessagesUtil.msg("profile.unknown")); return 0; }
                            store.unbanUuid(profile.getId());
                            src.sendSuccess(() -> MessagesUtil.msg("unban.ok", Map.of("player", name)), false);
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
                    if(list.isEmpty()){ ctx.getSource().sendSuccess(() -> MessagesUtil.msg("banlist.none"), false); return 1; }
                    ctx.getSource().sendSuccess(() -> MessagesUtil.msg("banlist.header"), false);
                    for(var b:list){
                        String who = b.target!=null? b.target.toString() : b.ip;
                        String until = (b.until==null? "permanent" : new Date(b.until).toString());
                        ctx.getSource().sendSuccess(() -> MessagesUtil.msg("banlist.entry", Map.of("who", who, "until", until, "reason", b.reason)), false);
                    }
                    return 1;
                })
        );
    }
}
