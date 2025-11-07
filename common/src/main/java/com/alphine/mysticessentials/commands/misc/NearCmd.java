package com.alphine.mysticessentials.commands.misc;

import com.mojang.brigadier.CommandDispatcher;
import com.alphine.mysticessentials.config.MEConfig;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import net.minecraft.commands.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import java.util.stream.Collectors;

public class NearCmd {
    public void register(CommandDispatcher<CommandSourceStack> d){
        d.register(Commands.literal("near")
                .requires(src -> Perms.has(src, PermNodes.NEAR_USE, 0))
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    int r = MEConfig.INSTANCE.limits.nearRadius;
                    String exempt = MEConfig.INSTANCE.permissions.nearExempt;

                    var list = p.getServer().getPlayerList().getPlayers().stream()
                            .filter(other -> other != p)
                            .filter(other -> {
                                if (exempt != null && !exempt.isBlank() && Perms.has(other, exempt, 2)) return false;
                                return other.level() == p.level() && other.distanceTo(p) <= r;
                            })
                            .map(o -> o.getName().getString())
                            .sorted().collect(Collectors.toList());

                    p.displayClientMessage(Component.literal(
                            list.isEmpty() ? "§7No players nearby (≤ "+r+" blocks)."
                                    : "§aNearby ("+r+"): §e"+String.join("§7, §e", list)), false);
                    return 1;
                })
        );
    }
}
