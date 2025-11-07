package com.alphine.mysticessentials.commands.admin;

import com.alphine.mysticessentials.MysticEssentialsCommon;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public class ReloadCmd {
    private final MysticEssentialsCommon common;

    public ReloadCmd(MysticEssentialsCommon common) { this.common = common; }

    public void register(CommandDispatcher<CommandSourceStack> d){
        d.register(Commands.literal("mereload")
                .requires(src -> Perms.has(src, PermNodes.MOD_RELOAD, 2) || Perms.has(src, PermNodes.ADMIN, 2))
                .executes(ctx -> {
                    int changed = common.reloadAll(); // returns a summary count; see below
                    ctx.getSource().sendSuccess(() -> Component.literal("§aMysticEssentials reloaded (§e"+changed+"§a modules)."), false);
                    return 1;
                })
        );
    }
}
