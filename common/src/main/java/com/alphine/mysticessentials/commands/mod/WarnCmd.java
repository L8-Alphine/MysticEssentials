package com.alphine.mysticessentials.commands.mod;

import com.alphine.mysticessentials.storage.AuditLogStore;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.alphine.mysticessentials.storage.PunishStore;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import com.alphine.mysticessentials.util.ModerationPerms;
import net.minecraft.commands.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Arrays;

public class WarnCmd {
    private final PunishStore store;
    private final AuditLogStore audit;
    public WarnCmd(PunishStore store, AuditLogStore audit){ this.store=store; this.audit=audit; }

    public void register(CommandDispatcher<CommandSourceStack> d){
        d.register(Commands.literal("warn")
                .requires(src -> Perms.has(src, PermNodes.WARN_USE, 2))
                .then(Commands.argument("player", StringArgumentType.word())
                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    ServerPlayer actor = ctx.getSource().getPlayerOrException();
                                    String name = StringArgumentType.getString(ctx,"player");
                                    String reason = StringArgumentType.getString(ctx,"reason");
                                    ServerPlayer target = actor.getServer().getPlayerList().getPlayerByName(name);
                                    if(target==null){ actor.displayClientMessage(Component.literal("§cPlayer not found."), false); return 0; }
                                    if (ModerationPerms.exempt(target, "warn")) { actor.displayClientMessage(Component.literal("§cTarget is exempt."), false); return 0; }
                                    var w = store.addWarning(target.getUUID(), actor.getUUID(), reason);
                                    actor.displayClientMessage(Component.literal("§aWarned §e"+target.getName().getString()+" §7[id: §e"+w.id+"§7]"), false);
                                    target.displayClientMessage(Component.literal("§cYou have been warned: §e"+reason), false);
                                    audit.log(AuditLogStore.make("WARN", actor.getUUID(), target.getUUID(), target.getName().getString(), reason, null, null, null));
                                    return 1;
                                })
                        )
                )
        );

        // /warnings and /warnings list variants
        d.register(Commands.literal("warnings")
                // base node: generic access to /warnings tree
                .requires(src -> Perms.has(src, PermNodes.WARNINGS_USE, 0))
                // /warnings (self)
                .executes(ctx -> {
                    ServerPlayer viewer = ctx.getSource().getPlayerOrException();
                    // need self permission (or alias / broad)
                    if (!Perms.has(ctx.getSource(),
                            Arrays.toString(new String[]{ PermNodes.WARNINGS_LIST_SELF, PermNodes.WARNINGS_LIST, PermNodes.ADMIN, PermNodes.ALL }), 0)) {
                        viewer.displayClientMessage(Component.literal("§cYou don't have permission to view your warnings."), false);
                        return 0;
                    }
                    return showWarnings(store, viewer, viewer.getUUID(), viewer.getName().getString());
                })
                // /warnings <player> (others or self)
                .then(Commands.argument("player", StringArgumentType.word())
                        .executes(ctx -> {
                            ServerPlayer viewer = ctx.getSource().getPlayerOrException();
                            String name = StringArgumentType.getString(ctx, "player");
                            ServerPlayer target = viewer.getServer().getPlayerList().getPlayerByName(name);
                            if (target == null) {
                                viewer.displayClientMessage(Component.literal("§cPlayer not found."), false);
                                return 0;
                            }

                            boolean isSelf = target.getUUID().equals(viewer.getUUID());
                            if (isSelf) {
                                if (!Perms.has(ctx.getSource(),
                                        Arrays.toString(new String[]{ PermNodes.WARNINGS_LIST_SELF, PermNodes.WARNINGS_LIST, PermNodes.ADMIN, PermNodes.ALL }), 0)) {
                                    viewer.displayClientMessage(Component.literal("§cYou don't have permission to view your warnings."), false);
                                    return 0;
                                }
                            } else {
                                if (!Perms.has(ctx.getSource(),
                                        Arrays.toString(new String[]{ PermNodes.WARNINGS_LIST_OTHERS, PermNodes.ADMIN, PermNodes.ALL }), 2)) {
                                    viewer.displayClientMessage(Component.literal("§cYou don't have permission to view others' warnings."), false);
                                    return 0;
                                }
                            }

                            return showWarnings(store, viewer, target.getUUID(), target.getName().getString());
                        })
                )
                // alias: /warnings list [player]
                .then(Commands.literal("list")
                        // /warnings list (self)
                        .executes(ctx -> {
                            ServerPlayer viewer = ctx.getSource().getPlayerOrException();
                            if (!Perms.has(ctx.getSource(),
                                    Arrays.toString(new String[]{ PermNodes.WARNINGS_LIST_SELF, PermNodes.WARNINGS_LIST, PermNodes.ADMIN, PermNodes.ALL }), 0)) {
                                viewer.displayClientMessage(Component.literal("§cYou don't have permission to view your warnings."), false);
                                return 0;
                            }
                            return showWarnings(store, viewer, viewer.getUUID(), viewer.getName().getString());
                        })
                        // /warnings list <player> (others or self)
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(ctx -> {
                                    ServerPlayer viewer = ctx.getSource().getPlayerOrException();
                                    String name = StringArgumentType.getString(ctx, "player");
                                    ServerPlayer target = viewer.getServer().getPlayerList().getPlayerByName(name);
                                    if (target == null) {
                                        viewer.displayClientMessage(Component.literal("§cPlayer not found."), false);
                                        return 0;
                                    }

                                    boolean isSelf = target.getUUID().equals(viewer.getUUID());
                                    if (isSelf) {
                                        if (!Perms.has(ctx.getSource(),
                                                Arrays.toString(new String[]{ PermNodes.WARNINGS_LIST_SELF, PermNodes.WARNINGS_LIST, PermNodes.ADMIN, PermNodes.ALL }), 0)) {
                                            viewer.displayClientMessage(Component.literal("§cYou don't have permission to view your warnings."), false);
                                            return 0;
                                        }
                                    } else {
                                        if (!Perms.has(ctx.getSource(),
                                                Arrays.toString(new String[]{ PermNodes.WARNINGS_LIST_OTHERS, PermNodes.ADMIN, PermNodes.ALL }), 2)) {
                                            viewer.displayClientMessage(Component.literal("§cYou don't have permission to view others' warnings."), false);
                                            return 0;
                                        }
                                    }

                                    return showWarnings(store, viewer, target.getUUID(), target.getName().getString());
                                })
                        )
                )
        );


        d.register(Commands.literal("pardon")
                .requires(src -> Perms.has(src, PermNodes.PARDON_USE, 2))
                .then(Commands.argument("player", StringArgumentType.word())
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(ctx -> {
                                    ServerPlayer actor = ctx.getSource().getPlayerOrException();
                                    String name = StringArgumentType.getString(ctx,"player");
                                    String id = StringArgumentType.getString(ctx,"id");
                                    ServerPlayer target = actor.getServer().getPlayerList().getPlayerByName(name);
                                    if(target==null){ actor.displayClientMessage(Component.literal("§cPlayer not found."), false); return 0; }
                                    boolean ok = store.pardonWarning(target.getUUID(), id);
                                    actor.displayClientMessage(Component.literal(ok? "§aRemoved warning §e"+id : "§cNo warning with id §e"+id), false);
                                    audit.log(AuditLogStore.make("PARDON", actor.getUUID(), target.getUUID(), target.getName().getString(), "warning:"+id, null, null, null));
                                    return ok?1:0;
                                })
                        )
                )
        );

        d.register(Commands.literal("warningsclear")
                .requires(src -> Perms.has(src, PermNodes.WARNINGS_CLEAR, 2))
                .then(Commands.argument("player", StringArgumentType.word())
                        .executes(ctx -> {
                            ServerPlayer actor = ctx.getSource().getPlayerOrException();
                            String name = StringArgumentType.getString(ctx,"player");
                            ServerPlayer target = actor.getServer().getPlayerList().getPlayerByName(name);
                            if(target==null){ actor.displayClientMessage(Component.literal("§cPlayer not found."), false); return 0; }
                            boolean ok = store.clearWarnings(target.getUUID());
                            actor.displayClientMessage(Component.literal(ok? "§aCleared warnings." : "§7No warnings to clear."), false);
                            audit.log(AuditLogStore.make("WARNINGS_CLEAR", actor.getUUID(), target.getUUID(), target.getName().getString(), null, null, null, null));
                            return 1;
                        })
                )
        );
    }

    // helper to print a target's warnings to a viewer
    private static int showWarnings(com.alphine.mysticessentials.storage.PunishStore store,
                                    ServerPlayer viewer,
                                    java.util.UUID targetId,
                                    String targetName) {
        var list = store.getWarnings(targetId);
        if (list.isEmpty()) {
            viewer.displayClientMessage(Component.literal("§7No warnings."), false);
            return 1;
        }
        viewer.displayClientMessage(Component.literal("§aWarnings for §e" + targetName + "§a:"), false);
        for (var w : list) {
            viewer.displayClientMessage(Component.literal("§7- §e" + w.id + " §7| " + new java.util.Date(w.at) + " §7| §f" + w.reason), false);
        }
        return 1;
    }

}
