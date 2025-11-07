package com.alphine.mysticessentials.commands.mod;

import com.alphine.mysticessentials.storage.AuditLogStore;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.alphine.mysticessentials.storage.PunishStore;
import com.alphine.mysticessentials.util.DurationUtil;
import com.alphine.mysticessentials.perm.*;
import net.minecraft.commands.*;
import net.minecraft.network.chat.Component;

import java.util.Date;

public class IpBanCmds {
    private final PunishStore store;
    private final AuditLogStore audit;

    public IpBanCmds(PunishStore store, AuditLogStore audit){ this.store=store; this.audit=audit; }

    public void register(CommandDispatcher<CommandSourceStack> d){
        // /ipban <ip> <reason...>
        d.register(Commands.literal("ipban")
                .requires(src -> Perms.has(src, PermNodes.IPBAN_USE, 2))
                .then(Commands.argument("ip", StringArgumentType.word())
                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    var src = ctx.getSource();
                                    String ip = StringArgumentType.getString(ctx,"ip");
                                    String reason = StringArgumentType.getString(ctx,"reason");
                                    PunishStore.Ban b = new PunishStore.Ban();
                                    b.ip=ip; b.actor = src.getPlayerOrException().getUUID(); b.reason=reason; b.at=System.currentTimeMillis(); b.until=null;
                                    store.banIp(b);
                                    src.sendSuccess(() -> Component.literal("§aIP banned §e"+ip), false);
                                    audit.log(AuditLogStore.make("IPBAN", src.getPlayerOrException().getUUID(), null, null, reason, null, ip, null));
                                    return 1;
                                })
                        )
                )
        );

        // /tempipban <ip> <duration> <reason...>
        d.register(Commands.literal("tempipban")
                .requires(src -> Perms.has(src, PermNodes.TEMIPBAN_USE, 2))
                .then(Commands.argument("ip", StringArgumentType.word())
                        .then(Commands.argument("duration", StringArgumentType.word())
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            var src = ctx.getSource();
                                            String ip = StringArgumentType.getString(ctx,"ip");
                                            String durStr = StringArgumentType.getString(ctx,"duration");
                                            String reason = StringArgumentType.getString(ctx,"reason");
                                            long ms = DurationUtil.parseToMillis(durStr);
                                            if(ms<=0){ src.sendFailure(Component.literal("§cInvalid duration.")); return 0; }
                                            PunishStore.Ban b = new PunishStore.Ban();
                                            b.ip=ip; b.actor = src.getPlayerOrException().getUUID(); b.reason=reason; b.at=System.currentTimeMillis(); b.until=b.at+ms;
                                            store.banIp(b);
                                            src.sendSuccess(() -> Component.literal("§aTemp IP banned §e"+ip+" §7for §e"+durStr), false);
                                            audit.log(AuditLogStore.make("TEMPIPBAN", src.getPlayerOrException().getUUID(), null, null, reason, b.until, ip, null));
                                            return 1;
                                        })
                                )
                        )
                )
        );

        // /unbanip <ip>
        d.register(Commands.literal("unbanip")
                .requires(src -> Perms.has(src, PermNodes.UNBAN_USE, 2))
                .then(Commands.argument("ip", StringArgumentType.word())
                        .executes(ctx -> {
                            var src = ctx.getSource();
                            String ip = StringArgumentType.getString(ctx,"ip");
                            store.unbanIp(ip);
                            src.sendSuccess(() -> Component.literal("§aUnbanned IP §e"+ip), false);
                            audit.log(AuditLogStore.make("UNBANIP", src.getPlayerOrException().getUUID(), null, null, null, null, ip, null));
                            return 1;
                        })
                )
        );
    }
}
