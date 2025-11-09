package com.alphine.mysticessentials.commands.misc;

import com.mojang.brigadier.CommandDispatcher;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;

public class WorkbenchCmd {
    public void register(CommandDispatcher<CommandSourceStack> d){
        d.register(Commands.literal("workbench")
                .requires(src -> Perms.has(src, PermNodes.WORKBENCH_USE, 0))
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    p.openMenu(new SimpleMenuProvider(
                            (id, inv, player) -> new CraftingMenu(id, inv, ContainerLevelAccess.NULL) {
                                @Override public boolean stillValid(Player pl) { return true; }
                            },
                            Component.translatable("container.crafting") // "Crafting Table"
                    ));
                    return 1;
                })
        );

        // Optional aliases
        d.register(Commands.literal("craft").redirect(d.getRoot().getChild("workbench")));
        d.register(Commands.literal("wb").redirect(d.getRoot().getChild("workbench")));
    }
}
