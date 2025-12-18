package com.alphine.mysticessentials.commands.kits;

import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import com.alphine.mysticessentials.storage.KitPlayerStore;
import com.alphine.mysticessentials.storage.KitStore;
import com.alphine.mysticessentials.util.DurationUtil;
import com.alphine.mysticessentials.util.MessagesUtil;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.*;
import java.util.stream.Collectors;

public class KitCmd {
    private final KitStore kits;
    private final KitPlayerStore pdata;

    public KitCmd(KitStore kits, KitPlayerStore pdata){ this.kits=kits; this.pdata=pdata; }

    public void register(CommandDispatcher<CommandSourceStack> d){
        d.register(Commands.literal("kit")
                .requires(src -> Perms.has(src, PermNodes.KIT_LIST, 0))
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    var available = kits.names().stream()
                            .filter(n -> Perms.has(p, PermNodes.kitNode(n), 0) || Perms.has(p, PermNodes.ADMIN, 2) || Perms.has(p, PermNodes.ALL, 2))
                            .sorted().collect(Collectors.toList());

                    if (available.isEmpty()){
                        p.displayClientMessage(MessagesUtil.msg("kit.list.none"), false);
                    } else {
                        // Build colored list via placeholder {kits}
                        String joined = available.stream().map(n -> "&e"+n).collect(Collectors.joining("&7, "));
                        p.displayClientMessage(MessagesUtil.msg("kit.list.some", Map.of("kits", joined)), false);
                    }
                    return 1;
                })
                .then(Commands.argument("kit", StringArgumentType.word())
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            String kitName = StringArgumentType.getString(ctx,"kit");
                            return giveKit(ctx.getSource(), kitName, p, p, false);
                        })
                        .then(Commands.argument("player", StringArgumentType.word())
                                .requires(src -> Perms.has(src, PermNodes.KIT_OTHERS, 2))
                                .executes(ctx -> {
                                    ServerPlayer issuer = ctx.getSource().getPlayerOrException();
                                    String kitName = StringArgumentType.getString(ctx,"kit");
                                    String targetName = StringArgumentType.getString(ctx,"player");
                                    ServerPlayer target = issuer.getServer().getPlayerList().getPlayerByName(targetName);
                                    if (target == null){
                                        issuer.displayClientMessage(MessagesUtil.msg("tp.player_not_found"), false);
                                        return 0;
                                    }
                                    return giveKit(ctx.getSource(), kitName, target, issuer, true);
                                })
                        )
                )
        );
    }

    private int giveKit(CommandSourceStack src, String kitName, ServerPlayer target,
                        ServerPlayer issuer, boolean isOthers) {
        var opt = kits.get(kitName);
        if (opt.isEmpty()){
            issuer.displayClientMessage(MessagesUtil.msg("kit.no_such", Map.of("kit", kitName)), false);
            return 0;
        }
        var kit = opt.get();
        String node = PermNodes.kitNode(kit.name);

        HolderLookup.Provider provider = target.server.registryAccess();

        boolean issuerAdmin = Perms.has(issuer, PermNodes.ADMIN, 2) || Perms.has(issuer, PermNodes.ALL, 2);
        if (!issuerAdmin && !Perms.has(target, node, 0)) {
            issuer.displayClientMessage(MessagesUtil.msg("kit.target_lacks_perm", Map.of("node", node)), false);
            return 0;
        }

        long now = System.currentTimeMillis();
        boolean bypassSelf = !isOthers && (Perms.has(issuer, PermNodes.KIT_BYPASS_COOLDOWN, 2) || issuerAdmin);
        boolean bypassOthers = isOthers && (Perms.has(issuer, PermNodes.KIT_BYPASS_COOLDOWN_OTHERS, 2) || issuerAdmin);

        if (kit.oneTime && !(bypassSelf || bypassOthers)){
            if (pdata.hasUsedOnce(target.getUUID(), kit.name)){
                if (issuer == target)
                    issuer.displayClientMessage(MessagesUtil.msg("kit.already_one_time.self"), false);
                else
                    issuer.displayClientMessage(MessagesUtil.msg("kit.already_one_time.other"), false);
                return 0;
            }
        }

        if (kit.cooldownMillis > 0 && !(bypassSelf || bypassOthers)){
            long last = pdata.getLast(target.getUUID(), kit.name);
            long rem = (last + kit.cooldownMillis) - now;
            if (rem > 0){
                String remStr = DurationUtil.fmtRemaining(rem);
                if (issuer == target)
                    issuer.displayClientMessage(MessagesUtil.msg("kit.cooldown.self", Map.of("time", remStr)), false);
                else
                    issuer.displayClientMessage(MessagesUtil.msg("kit.cooldown.other", Map.of("time", remStr)), false);
                return 0;
            }
        }

        int given = 0;

        for (var b64 : kit.itemsB64) {
            ItemStack stack = KitStore.b64ToStack(b64, provider);
            if (stack.isEmpty()) continue;

            ItemStack toGive = stack.copy();

            // Try to add to inventory; if it can't fully fit, drop the remainder
            boolean fullyAdded = target.getInventory().add(toGive);
            if (!fullyAdded && !toGive.isEmpty()) {
                target.drop(toGive, false, true);
            }

            given++;
        }

        // Force client inventory to refresh (fixes “visual bug” / ghost items)
        target.getInventory().setChanged();
        target.inventoryMenu.broadcastChanges();
        target.containerMenu.broadcastChanges();

        if (!(bypassSelf || bypassOthers)) {
            pdata.setLast(target.getUUID(), kit.name, now);
            if (kit.oneTime) pdata.markUsedOnce(target.getUUID(), kit.name);
        }

        if (issuer == target) {
            issuer.displayClientMessage(MessagesUtil.msg("kit.received.self",
                    Map.of("kit", kit.name, "count", given)), false);
        } else {
            issuer.displayClientMessage(MessagesUtil.msg("kit.gave",
                    Map.of("kit", kit.name, "player", target.getName().getString(), "count", given)), false);
            target.displayClientMessage(MessagesUtil.msg("kit.received.other",
                    Map.of("kit", kit.name, "player", issuer.getName().getString())), false);
        }
        return 1;
    }
}
