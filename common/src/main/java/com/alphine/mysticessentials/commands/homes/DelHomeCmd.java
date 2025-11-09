package com.alphine.mysticessentials.commands.homes;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.alphine.mysticessentials.storage.HomesStore;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import com.alphine.mysticessentials.util.MessagesUtil;
import net.minecraft.commands.*;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;

public class DelHomeCmd {
    private final HomesStore store;
    public DelHomeCmd(HomesStore store){ this.store=store; }

    private SuggestionProvider<CommandSourceStack> homeSuggest() {
        return (ctx, b) -> {
            try {
                var p = ctx.getSource().getPlayerOrException();
                for (var n : store.names(p.getUUID())) b.suggest(n);
            } catch (Exception ignored) {}
            return b.buildFuture();
        };
    }

    public void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("delhome")
                .requires(src -> Perms.has(src, PermNodes.HOME_DEL, 0))
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(homeSuggest())
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            String name = StringArgumentType.getString(ctx, "name");
                            boolean ok = store.delete(p.getUUID(), name);
                            p.displayClientMessage(ok
                                    ? MessagesUtil.msg("home.delete.ok", Map.of("name", name))
                                    : MessagesUtil.msg("home.delete.no_such", Map.of("name", name)), false);
                            return ok ? 1 : 0;
                        })
                )
        );
    }
}
