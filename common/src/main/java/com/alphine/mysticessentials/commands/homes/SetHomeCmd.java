package com.alphine.mysticessentials.commands.homes;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.alphine.mysticessentials.storage.HomesStore;
import com.alphine.mysticessentials.util.HomeLimitResolver;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import com.alphine.mysticessentials.util.MessagesUtil;
import net.minecraft.commands.*;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;

public class SetHomeCmd {
    private final HomesStore store;
    public SetHomeCmd(HomesStore store) { this.store = store; }

    public void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("sethome")
                .requires(src -> Perms.has(src, PermNodes.HOME_SET, 0))
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            String name = StringArgumentType.getString(ctx, "name");

                            int limit = HomeLimitResolver.resolve(p);
                            int existing = store.all(p.getUUID()).size();
                            boolean replacing = store.get(p.getUUID(), name).isPresent();
                            if (!replacing && existing >= limit) {
                                p.displayClientMessage(MessagesUtil.msg("home.limit_reached", Map.of("limit", limit)), false);
                                return 0;
                            }

                            var pos = p.blockPosition();
                            HomesStore.Home h = new HomesStore.Home();
                            h.name=name; h.dim=p.serverLevel().dimension().location().toString();
                            h.x=pos.getX()+0.5; h.y=pos.getY(); h.z=pos.getZ()+0.5;
                            h.yaw=p.getYRot(); h.pitch=p.getXRot();

                            store.set(p.getUUID(), h);
                            p.displayClientMessage(MessagesUtil.msg("home.set.ok", Map.of("name", name)), false);
                            return 1;
                        })
                )
        );
    }
}
