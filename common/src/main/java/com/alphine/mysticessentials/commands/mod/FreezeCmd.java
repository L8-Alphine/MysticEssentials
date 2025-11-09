package com.alphine.mysticessentials.commands.mod;

import com.alphine.mysticessentials.storage.AuditLogStore;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.alphine.mysticessentials.storage.PunishStore;
import com.alphine.mysticessentials.perm.*;
import com.alphine.mysticessentials.util.MessagesUtil;
import net.minecraft.commands.*;
import net.minecraft.server.level.ServerPlayer;
import java.util.Map;

public class FreezeCmd {
    private final PunishStore store;
    private final AuditLogStore audit;
    public FreezeCmd(PunishStore store, AuditLogStore audit){ this.store=store; this.audit=audit; }

    public void register(CommandDispatcher<CommandSourceStack> d){
        d.register(Commands.literal("freeze")
                .requires(src -> Perms.has(src, PermNodes.FREEZE_USE, 2))
                .then(Commands.argument("player", StringArgumentType.word())
                        .executes(ctx -> {
                            ServerPlayer actor = ctx.getSource().getPlayerOrException();
                            String name = StringArgumentType.getString(ctx,"player");
                            ServerPlayer target = actor.getServer().getPlayerList().getPlayerByName(name);
                            if (target == null) {
                                actor.displayClientMessage(MessagesUtil.msg("tp.player_not_found"), false);
                                return 0;
                            }
                            boolean on = store.toggleFreeze(target.getUUID());
                            target.displayClientMessage(MessagesUtil.msg(on ? "freeze.notify.to.on" : "freeze.notify.to.off"), false);
                            actor.displayClientMessage(MessagesUtil.msg(on ? "freeze.ok.on" : "freeze.ok.off", Map.of("player", target.getName().getString())), false);
                            audit.log(AuditLogStore.make(on? "FREEZE" : "UNFREEZE", actor.getUUID(), target.getUUID(), target.getName().getString(), null, null, null, null));
                            return 1;
                        })
                )
        );
    }
}
