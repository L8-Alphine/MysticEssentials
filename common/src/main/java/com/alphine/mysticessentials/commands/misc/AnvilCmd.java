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
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;

public class AnvilCmd {
    public void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("anvil")
                .requires(src -> Perms.has(src, PermNodes.ANVIL_USE, 2))
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    p.openMenu(new SimpleMenuProvider(
                            (id, inv, player) -> new AnvilMenu(id, inv, ContainerLevelAccess.NULL) {
                                @Override public boolean stillValid(Player pl) { return true; }
                            },
                            Component.translatable("container.repair") // "Anvil"
                    ));
                    return 1;
                })
        );

        // Optional alias
        d.register(Commands.literal("anv").redirect(d.getRoot().getChild("anvil")));
    }
}
