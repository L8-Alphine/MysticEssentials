package com.alphine.mysticessentials.commands.admin;

import com.alphine.mysticessentials.MysticEssentialsCommon;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import com.alphine.mysticessentials.util.MessagesUtil; // ← add
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import java.util.Map;

public class ReloadCmd {
    private final MysticEssentialsCommon common;

    public ReloadCmd(MysticEssentialsCommon common) { this.common = common; }

    public void register(CommandDispatcher<CommandSourceStack> d){
        d.register(Commands.literal("mereload")
                .requires(src -> Perms.has(src, PermNodes.MOD_RELOAD, 2) || Perms.has(src, PermNodes.ADMIN, 2))
                .executes(ctx -> {
                    int changed = common.reloadAll();
                    ctx.getSource().sendSuccess(() -> MessagesUtil.msg("reload.ok", Map.of("count", changed)), false);
                    return 1;
                })
        );
    }
}
