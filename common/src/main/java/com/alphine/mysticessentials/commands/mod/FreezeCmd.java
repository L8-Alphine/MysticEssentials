package com.alphine.mysticessentials.commands.mod;

import com.alphine.mysticessentials.storage.AuditLogStore;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.alphine.mysticessentials.storage.PunishStore;
import com.alphine.mysticessentials.perm.*;
import net.minecraft.commands.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

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
                            if(target==null){ actor.displayClientMessage(Component.literal("§cPlayer not found."), false); return 0; }
                            boolean on = store.toggleFreeze(target.getUUID());
                            target.displayClientMessage(Component.literal(on ? "§cYou have been frozen." : "§aYou are unfrozen."), false);
                            actor.displayClientMessage(Component.literal((on ? "§aFroze " : "§aUnfroze ") + target.getName().getString()), false);
                            audit.log(AuditLogStore.make(on? "FREEZE" : "UNFREEZE", actor.getUUID(), target.getUUID(), target.getName().getString(), null, null, null, null));
                            return 1;
                        })
                )
        );
    }
}
