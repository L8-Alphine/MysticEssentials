package com.alphine.mysticessentials.commands.homes;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.alphine.mysticessentials.storage.HomesStore;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import net.minecraft.commands.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public class DelHomeCmd {
    private final HomesStore store;
    public DelHomeCmd(HomesStore store){ this.store=store; }

    private static final SuggestionProvider<CommandSourceStack> HOME_SUGGEST = (ctx, b) -> {
        try {
            var p = ctx.getSource().getPlayerOrException();
            for (var n : com.alphine.mysticessentials.MysticEssentialsCommon.get().homes.names(p.getUUID())) b.suggest(n);
        } catch (Exception ignored) {}
        return b.buildFuture();
    };

    public void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("delhome")
                .requires(src -> Perms.has(src, PermNodes.HOME_DEL, 2))
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(HOME_SUGGEST)
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            String name = StringArgumentType.getString(ctx, "name");
                            boolean ok = store.delete(p.getUUID(), name);
                            p.displayClientMessage(Component.literal(ok ? "§aDeleted home §e"+name : "§cNo home named §e"+name), false);
                            return ok ? 1 : 0;
                        })
                )
        );
    }
}
