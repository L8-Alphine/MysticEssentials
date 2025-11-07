package com.alphine.mysticessentials.commands.misc;

import com.mojang.brigadier.CommandDispatcher;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;

public class WorkbenchCmd {
    public void register(CommandDispatcher<CommandSourceStack> d){
        d.register(net.minecraft.commands.Commands.literal("workbench")
                .requires(src -> Perms.has(src, PermNodes.WORKBENCH_USE, 0))
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    p.openMenu(new SimpleMenuProvider(
                            (id, inv, player) ->
                                    new CraftingMenu(id, inv, ContainerLevelAccess.create(p.level(), p.blockPosition())),
                            Component.literal("Crafting Table")
                    ));
                    return 1;
                })
        );
    }
}
