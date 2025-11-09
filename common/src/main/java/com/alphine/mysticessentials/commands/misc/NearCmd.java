package com.alphine.mysticessentials.commands.misc;

import com.mojang.brigadier.CommandDispatcher;
import com.alphine.mysticessentials.config.MEConfig;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import com.alphine.mysticessentials.util.MessagesUtil;
import net.minecraft.commands.*;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class NearCmd {
    public void register(CommandDispatcher<CommandSourceStack> d){
        d.register(Commands.literal("near")
                .requires(src -> Perms.has(src, PermNodes.NEAR_USE, 0))
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    int r = MEConfig.INSTANCE.limits.nearRadius;
                    String exempt = MEConfig.INSTANCE.permissions.nearExempt;

                    List<String> names = p.getServer().getPlayerList().getPlayers().stream()
                            .filter(other -> other != p)
                            .filter(other -> {
                                if (exempt != null && !exempt.isBlank() && Perms.has(other, exempt, 2)) return false;
                                return other.level() == p.level() && other.distanceTo(p) <= r;
                            })
                            .map(o -> o.getName().getString())
                            .sorted()
                            .collect(Collectors.toList());

                    if (names.isEmpty()) {
                        p.displayClientMessage(MessagesUtil.msg("near.none", Map.of("radius", r)), false);
                    } else {
                        // format as "&eName1&7, &eName2"
                        String players = names.stream().map(n -> "&e" + n).collect(Collectors.joining("&7, "));
                        p.displayClientMessage(MessagesUtil.msg("near.list", Map.of("radius", r, "players", players)), false);
                    }
                    return 1;
                })
        );
    }
}
