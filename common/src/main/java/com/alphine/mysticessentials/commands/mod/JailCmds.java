package com.alphine.mysticessentials.commands.mod;

import com.alphine.mysticessentials.storage.AuditLogStore;
import com.alphine.mysticessentials.storage.PlayerDataStore;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.alphine.mysticessentials.storage.PunishStore;
import com.alphine.mysticessentials.perm.*;
import com.alphine.mysticessentials.util.Teleports;
import net.minecraft.commands.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.Level;

public class JailCmds {
    private final PunishStore store;
    private final PlayerDataStore pdata;
    private final AuditLogStore audit;
    public JailCmds(PunishStore store, PlayerDataStore pdata, AuditLogStore audit){ this.store=store; this.pdata=pdata; this.audit=audit; }

    public void register(CommandDispatcher<CommandSourceStack> d){
        // /setjail <name>
        d.register(Commands.literal("setjail")
                .requires(src -> Perms.has(src, PermNodes.JAIL_SET, 2))
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            var pos = p.blockPosition();
                            PunishStore.Point pt = new PunishStore.Point();
                            pt.dim=p.serverLevel().dimension().location().toString();
                            pt.x=pos.getX()+0.5; pt.y=pos.getY(); pt.z=pos.getZ()+0.5; pt.yaw=p.getYRot(); pt.pitch=p.getXRot();
                            String name = StringArgumentType.getString(ctx,"name");
                            store.setJail(name, pt);
                            p.displayClientMessage(Component.literal("§aJail §e"+name+" §asaved."), false);
                            return 1;
                        })
                )
        );

        // /deljail <name>
        d.register(Commands.literal("deljail")
                .requires(src -> Perms.has(src, PermNodes.JAIL_DEL, 2))
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> {
                            var ok = store.delJail(StringArgumentType.getString(ctx,"name"));
                            ctx.getSource().sendSuccess(() -> Component.literal(ok? "§aDeleted jail." : "§cNo such jail."), false);
                            return ok?1:0;
                        })
                )
        );

        // /jail <player> <jailName>
        d.register(Commands.literal("jail")
                .requires(src -> Perms.has(src, PermNodes.JAIL_USE, 2))
                .then(Commands.argument("player", StringArgumentType.word())
                        .then(Commands.argument("jailName", StringArgumentType.word())
                                .executes(ctx -> {
                                    ServerPlayer actor = ctx.getSource().getPlayerOrException();
                                    String name = StringArgumentType.getString(ctx,"player");
                                    String jail = StringArgumentType.getString(ctx,"jailName");
                                    var opt = store.getJail(jail);
                                    if(opt.isEmpty()){ actor.displayClientMessage(Component.literal("§cJail not found."), false); return 0; }
                                    ServerPlayer target = actor.getServer().getPlayerList().getPlayerByName(name);
                                    if(target==null){ actor.displayClientMessage(Component.literal("§cPlayer not found."), false); return 0; }
                                    var p = opt.get();

                                    ResourceLocation id = ResourceLocation.tryParse(p.dim); // e.g. "minecraft:overworld"
                                    if (id == null) {
                                        actor.displayClientMessage(Component.literal("§cBad dimension id for jail: " + p.dim), false);
                                        return 0;
                                    }

                                    ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, id);
                                    ServerLevel level = actor.getServer().getLevel(key);
                                    if (level == null) {
                                        actor.displayClientMessage(Component.literal("§cWorld missing for jail: " + p.dim), false);
                                        return 0;
                                    }

                                    store.jail(target.getUUID(), jail);
                                    Teleports.pushBackAndTeleport(target, level, p.x, p.y, p.z, p.yaw, p.pitch, pdata);
                                    target.displayClientMessage(Component.literal("§cYou have been jailed at §e" + jail), false);
                                    actor.displayClientMessage(Component.literal("§aJailed §e" + name + " §7at §e" + jail), false);
                                    audit.log(AuditLogStore.make("JAIL", actor.getUUID(), target.getUUID(), target.getName().getString(), null, null, null, jail));
                                    return 1;
                                })
                        )
                )
        );

        // /unjail <player>
        d.register(Commands.literal("unjail")
                .requires(src -> Perms.has(src, PermNodes.JAIL_USE, 2))
                .then(Commands.argument("player", StringArgumentType.word())
                        .executes(ctx -> {
                            var src = ctx.getSource();
                            String name = StringArgumentType.getString(ctx,"player");
                            ServerPlayer target = src.getServer().getPlayerList().getPlayerByName(name);
                            if(target==null){ src.sendFailure(Component.literal("§cPlayer not found.")); return 0; }
                            store.unjail(target.getUUID());
                            src.sendSuccess(() -> Component.literal("§aUnjailed §e"+name), false);
                            audit.log(AuditLogStore.make("UNJAIL", src.getPlayerOrException().getUUID(), target.getUUID(), target.getName().getString(), null, null, null, null));
                            return 1;
                        })
                )
        );
    }
}
