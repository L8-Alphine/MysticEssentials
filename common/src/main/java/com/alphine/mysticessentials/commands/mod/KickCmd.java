package com.alphine.mysticessentials.commands.mod;

import com.alphine.mysticessentials.storage.AuditLogStore;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.alphine.mysticessentials.perm.*;
import com.alphine.mysticessentials.util.ModerationPerms;
import com.alphine.mysticessentials.util.MessagesUtil;
import net.minecraft.commands.*;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;

public class KickCmd {
    private final AuditLogStore audit;

    public KickCmd(AuditLogStore audit){ this.audit = audit; }

    public void register(CommandDispatcher<CommandSourceStack> d){
        d.register(Commands.literal("kick")
                .requires(src -> Perms.has(src, PermNodes.KICK_USE, 2))
                .then(Commands.argument("player", StringArgumentType.word())
                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    ServerPlayer actor = ctx.getSource().getPlayerOrException();
                                    String name = StringArgumentType.getString(ctx,"player");
                                    String reason = StringArgumentType.getString(ctx,"reason");
                                    ServerPlayer target = actor.getServer().getPlayerList().getPlayerByName(name);
                                    if (target == null) {
                                        actor.displayClientMessage(MessagesUtil.msg("tp.player_not_found"), false);
                                        return 0;
                                    }
                                    if (ModerationPerms.exempt(target, "kick")) {
                                        actor.displayClientMessage(MessagesUtil.msg("moderation.target_exempt"), false);
                                        return 0;
                                    }

                                    // Disconnect target with localized message
                                    target.connection.disconnect(MessagesUtil.msg("kick.notify.to", Map.of("reason", reason)));
                                    actor.displayClientMessage(MessagesUtil.msg("kick.ok", Map.of("player", name)), false);

                                    // Log moderation action
                                    audit.log(AuditLogStore.make("KICK",
                                            actor.getUUID(),
                                            target.getUUID(),
                                            target.getName().getString(),
                                            reason, null, null, null));
                                    return 1;
                                })
                        )
                )
        );
    }
}
