package com.alphine.mysticessentials.commands.homes;

import com.mojang.brigadier.CommandDispatcher;
import com.alphine.mysticessentials.storage.HomesStore;
import com.alphine.mysticessentials.util.HomeLimitResolver;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import net.minecraft.commands.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Comparator;
import java.util.stream.Collectors;

public class HomesCmd {
    private final HomesStore store;
    public HomesCmd(HomesStore store){ this.store=store; }

    public void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("homes")
                .requires(src -> Perms.has(src, PermNodes.HOME_LIST, 0))
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    var names = store.names(p.getUUID()).stream().sorted(Comparator.naturalOrder()).collect(Collectors.toList());
                    int limit = HomeLimitResolver.resolve(p);
                    p.displayClientMessage(Component.literal(
                            names.isEmpty() ? "§7You have no homes. §8(§7limit: "+limit+"§8)"
                                    : "§aHomes ("+names.size()+"/"+limit+"): §e"+String.join("§7, §e", names)
                    ), false);
                    return 1;
                })
        );
    }
}
