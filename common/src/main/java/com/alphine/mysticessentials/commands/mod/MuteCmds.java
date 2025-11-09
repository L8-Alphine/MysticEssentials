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

public class MuteCmds {
    private final PunishStore store;
    private final AuditLogStore audit;
    public MuteCmds(PunishStore store, AuditLogStore audit){ this.store=store; this.audit=audit; }

    public void register(CommandDispatcher<CommandSourceStack> d){
        // /mute <player> <durationOrPerm> <reason...>
        d.register(Commands.literal("mute")
                .requires(src -> Perms.has(src, PermNodes.MUTE_USE, 2))
                .then(Commands.argument("player", StringArgumentType.word())
                        .then(Commands.argument("durationOrPerm", StringArgumentType.word())
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            ServerPlayer actor = ctx.getSource().getPlayerOrException();
                                            String name = StringArgumentType.getString(ctx,"player");
                                            String token = StringArgumentType.getString(ctx,"durationOrPerm");
                                            String reason = StringArgumentType.getString(ctx,"reason");
                                            ServerPlayer target = actor.getServer().getPlayerList().getPlayerByName(name);
                                            if(target==null){
                                                actor.displayClientMessage(MessagesUtil.msg("tp.player_not_found"), false);
                                                return 0;
                                            }
                                            if (ModerationPerms.exempt(target, "mute")) {
                                                actor.displayClientMessage(MessagesUtil.msg("moderation.target_exempt"), false);
                                                return 0;
                                            }

                                            long ms = "perm".equalsIgnoreCase(token) ? 0L : DurationUtil.parseToMillis(token);
                                            PunishStore.Mute m = new PunishStore.Mute();
                                            m.target = target.getUUID();
                                            m.actor  = actor.getUUID();
                                            m.reason = reason;
                                            m.at     = System.currentTimeMillis();
                                            m.until  = ms <= 0 ? null : m.at + ms;

                                            store.mute(m);

                                            // notify target
                                            if (m.until != null) {
                                                String untilStr = new Date(m.until).toString();
                                                target.displayClientMessage(
                                                        MessagesUtil.msg("mute.notify.to.temp", Map.of("until", untilStr, "reason", reason)), false);
                                            } else {
                                                target.displayClientMessage(
                                                        MessagesUtil.msg("mute.notify.to.perm", Map.of("reason", reason)), false);
                                            }

                                            // notify actor
                                            if (m.until != null) {
                                                actor.displayClientMessage(
                                                        MessagesUtil.msg("mute.ok.temp", Map.of("player", name, "duration", token)), false);
                                            } else {
                                                actor.displayClientMessage(
                                                        MessagesUtil.msg("mute.ok.perm", Map.of("player", name)), false);
                                            }

                                            audit.log(AuditLogStore.make(
                                                    (m.until==null? "MUTE":"TEMPMUTE"),
                                                    actor.getUUID(), target.getUUID(), target.getName().getString(),
                                                    reason, m.until, null, null
                                            ));
                                            return 1;
                                        })
                                )
                        )
                )
        );

        // /unmute <player>
        d.register(Commands.literal("unmute")
                .requires(src -> Perms.has(src, PermNodes.UNMUTE_USE, 2))
                .then(Commands.argument("player", StringArgumentType.word())
                        .executes(ctx -> {
                            var src = ctx.getSource();
                            String name = StringArgumentType.getString(ctx,"player");
                            var profile = src.getServer().getProfileCache().get(name).orElse(null);
                            if (profile == null) {
                                src.sendFailure(MessagesUtil.msg("unmute.unknown_profile"));
                                return 0;
                            }
                            store.unmute(profile.getId());
                            src.sendSuccess(() -> MessagesUtil.msg("unmute.ok", Map.of("name", name)), false);
                            audit.log(AuditLogStore.make("UNMUTE",
                                    src.getPlayerOrException().getUUID(),
                                    profile.getId(),
                                    profile.getName(),
                                    null, null, null, null));
                            return 1;
                        })
                )
        );
    }
}
