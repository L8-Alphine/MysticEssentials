package com.alphine.mysticessentials.commands.homes;

import com.mojang.brigadier.CommandDispatcher;
import com.alphine.mysticessentials.storage.HomesStore;
import com.alphine.mysticessentials.util.HomeLimitResolver;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import com.alphine.mysticessentials.util.MessagesUtil;
import net.minecraft.commands.*;
import net.minecraft.server.level.ServerPlayer;

import java.util.Comparator;
import java.util.Map;
import java.util.stream.Collectors;

public class HomesCmd {
    private final HomesStore store;
    public HomesCmd(HomesStore store){ this.store=store; }

    public void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("homes")
                .requires(src -> Perms.has(src, PermNodes.HOME_LIST, 0))
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    var names = store.names(p.getUUID()).stream()
                            .sorted(Comparator.naturalOrder()).collect(Collectors.toList());
                    int limit = HomeLimitResolver.resolve(p);

                    if (names.isEmpty()) {
                        p.displayClientMessage(MessagesUtil.msg("home.list.none", Map.of("limit", limit)), false);
                    } else {
                        String joined = names.stream().map(n -> "&e"+n).collect(Collectors.joining("&7, "));
                        p.displayClientMessage(MessagesUtil.msg("home.list.some",
                                Map.of("count", names.size(), "limit", limit, "homes", joined)), false);
                    }
                    return 1;
                })
        );
    }
}
