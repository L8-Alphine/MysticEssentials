package com.alphine.mysticessentials.commands.kits;

import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import com.alphine.mysticessentials.storage.KitStore;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.*;
import net.minecraft.network.chat.Component;

public class DelKitCmd {
    private final KitStore kits;
    public DelKitCmd(KitStore kits){ this.kits=kits; }

    public void register(CommandDispatcher<CommandSourceStack> d){
        d.register(Commands.literal("delkit")
                .requires(src -> Perms.has(src, PermNodes.KIT_DELETE, 2))
                .then(Commands.argument("kit", StringArgumentType.word())
                        .executes(ctx -> {
                            String name = StringArgumentType.getString(ctx,"kit");
                            boolean ok = kits.delete(name);
                            ctx.getSource().sendSuccess(() -> Component.literal(ok? "§aDeleted kit §e"+name : "§cNo kit named §e"+name), false);
                            return ok?1:0;
                        })
                )
        );
    }
}
