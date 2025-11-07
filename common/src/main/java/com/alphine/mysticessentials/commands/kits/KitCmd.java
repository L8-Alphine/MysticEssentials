package com.alphine.mysticessentials.commands.kits;

import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import com.alphine.mysticessentials.storage.KitPlayerStore;
import com.alphine.mysticessentials.storage.KitStore;
import com.alphine.mysticessentials.util.DurationUtil;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.*;
import java.util.stream.Collectors;

public class KitCmd {
    private final KitStore kits;
    private final KitPlayerStore pdata;

    public KitCmd(KitStore kits, KitPlayerStore pdata){ this.kits=kits; this.pdata=pdata; }

    public void register(CommandDispatcher<CommandSourceStack> d){
        // /kit -> list
        d.register(Commands.literal("kit")
                .requires(src -> Perms.has(src, PermNodes.KIT_LIST, 0))
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    var available = kits.names().stream()
                            .filter(n -> Perms.has(p, PermNodes.kitNode(n), 0) || Perms.has(p, PermNodes.ADMIN, 2) || Perms.has(p, PermNodes.ALL, 2))
                            .sorted()
                            .collect(Collectors.toList());
                    if (available.isEmpty()){
                        p.displayClientMessage(Component.literal("§7You have no kits."), false);
                    } else {
                        p.displayClientMessage(Component.literal("§aKits: §e"+String.join("§7, §e", available)), false);
                    }
                    return 1;
                })
                // /kit <name>
                .then(Commands.argument("kit", StringArgumentType.word())
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            String kitName = StringArgumentType.getString(ctx,"kit");
                            return giveKit(ctx.getSource(), kitName, p, /*issuedBy*/p, /*isOthers*/false);
                        })
                        // /kit <name> <player>
                        .then(Commands.argument("player", StringArgumentType.word())
                                .requires(src -> Perms.has(src, PermNodes.KIT_OTHERS, 2))
                                .executes(ctx -> {
                                    ServerPlayer issuer = ctx.getSource().getPlayerOrException();
                                    String kitName = StringArgumentType.getString(ctx,"kit");
                                    String targetName = StringArgumentType.getString(ctx,"player");
                                    ServerPlayer target = issuer.getServer().getPlayerList().getPlayerByName(targetName);
                                    if (target == null){ issuer.displayClientMessage(Component.literal("§cPlayer not found."), false); return 0; }
                                    return giveKit(ctx.getSource(), kitName, target, issuer, /*isOthers*/true);
                                })
                        )
                )
        );
    }

    private int giveKit(CommandSourceStack src, String kitName, ServerPlayer target,
                        ServerPlayer issuer, boolean isOthers) {
        var opt = kits.get(kitName);
        if (opt.isEmpty()){ issuer.displayClientMessage(Component.literal("§cNo kit named §e"+kitName), false); return 0; }
        var kit = opt.get();
        String node = PermNodes.kitNode(kit.name);

        HolderLookup.Provider provider = target.server.registryAccess();

        // Access check is for the receiver (target) unless issuer is admin/wildcard
        boolean issuerAdmin = Perms.has(issuer, PermNodes.ADMIN, 2) || Perms.has(issuer, PermNodes.ALL, 2);
        if (!issuerAdmin && !Perms.has(target, node, 0)) {
            issuer.displayClientMessage(Component.literal("§cTarget lacks permission: §e"+node), false);
            return 0;
        }

        long now = System.currentTimeMillis();
        boolean bypassSelf = !isOthers && (Perms.has(issuer, PermNodes.KIT_BYPASS_COOLDOWN, 2) || issuerAdmin);
        boolean bypassOthers = isOthers && (Perms.has(issuer, PermNodes.KIT_BYPASS_COOLDOWN_OTHERS, 2) || issuerAdmin);

        // One-time check
        if (kit.oneTime && !bypassSelf && !bypassOthers){
            if (pdata.hasUsedOnce(target.getUUID(), kit.name)){
                if (issuer == target)
                    issuer.displayClientMessage(Component.literal("§cYou have already claimed this one-time kit."), false);
                else
                    issuer.displayClientMessage(Component.literal("§cThat player has already claimed this one-time kit."), false);
                return 0;
            }
        }

        // Cooldown check
        if (kit.cooldownMillis > 0 && !(bypassSelf || bypassOthers)){
            long last = pdata.getLast(target.getUUID(), kit.name);
            long rem = (last + kit.cooldownMillis) - now;
            if (rem > 0){
                String msg = "§cCooldown remaining: §e"+ DurationUtil.fmtRemaining(rem);
                if (issuer == target) issuer.displayClientMessage(Component.literal(msg), false);
                else issuer.displayClientMessage(Component.literal("§cTarget cooldown. " + msg), false);
                return 0;
            }
        }

        // Give items (spawn or add to inventory; drop overflow)
        int given = 0;
        for (var b64 : kit.itemsB64) {
            ItemStack st = KitStore.b64ToStack(b64, provider); // <-- pass provider
            if (st.isEmpty()) continue;
            if (!target.addItem(st.copy())) {
                target.drop(st.copy(), false, true);
            }
            given++;
        }

        // Record usage
        if (!(bypassSelf || bypassOthers)) {
            pdata.setLast(target.getUUID(), kit.name, now);
            if (kit.oneTime) pdata.markUsedOnce(target.getUUID(), kit.name);
        }

        if (issuer == target) {
            issuer.displayClientMessage(Component.literal("§aReceived kit §e"+kit.name+" §7("+given+" items)"), false);
        } else {
            issuer.displayClientMessage(Component.literal("§aGave kit §e"+kit.name+" §7to §e"+target.getName().getString()+" §7("+given+" items)"), false);
            target.displayClientMessage(Component.literal("§aYou received kit §e"+kit.name+" §7from §e"+issuer.getName().getString()), false);
        }
        return 1;
    }
}
