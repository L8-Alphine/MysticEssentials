package com.alphine.mysticessentials.commands.misc;

import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import com.alphine.mysticessentials.util.MessagesUtil;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;

public class RepairCmd {

    public void register(CommandDispatcher<CommandSourceStack> d) {
        // /repair ...
        var repair = Commands.literal("repair")
                .requires(src -> Perms.has(src, PermNodes.REPAIR_USE, 2))
                .executes(ctx -> doRepair(ctx.getSource(), "hand"))
                .then(Commands.argument("scope", StringArgumentType.word())
                        .suggests((c, b) -> {
                            List.of("hand", "offhand", "armor", "hotbar", "all").forEach(b::suggest);
                            return b.buildFuture();
                        })
                        .executes(ctx -> doRepair(ctx.getSource(), StringArgumentType.getString(ctx, "scope")))
                );

        // /fix ... (alias)
        var fix = Commands.literal("fix")
                .requires(src -> Perms.has(src, PermNodes.REPAIR_USE, 2))
                .redirect(repair.build()); // share logic

        d.register(repair);
        d.register(fix);
    }

    private int doRepair(CommandSourceStack src, String scope) {
        ServerPlayer p = src.getPlayer();
        if (p == null) {
            src.sendFailure(MessagesUtil.msg("repair.players_only"));
            return 0;
        }

        boolean allowEnch = Perms.has(src, PermNodes.REPAIR_ENCHANTED, 2);
        int repaired = switch (scope.toLowerCase()) {
            case "hand"    -> repairStack(p.getMainHandItem(), allowEnch) ? 1 : 0;
            case "offhand" -> repairStack(p.getOffhandItem(), allowEnch) ? 1 : 0;
            case "armor"   -> p.getInventory().armor.stream().mapToInt(s -> repairStack(s, allowEnch) ? 1 : 0).sum();
            case "hotbar"  -> {
                int n = 0;
                for (int i = 0; i < 9; i++) n += repairStack(p.getInventory().getItem(i), allowEnch) ? 1 : 0;
                yield n;
            }
            case "all"     -> {
                int n = 0;
                for (ItemStack s : p.getInventory().items)   n += repairStack(s, allowEnch) ? 1 : 0;
                for (ItemStack s : p.getInventory().armor)   n += repairStack(s, allowEnch) ? 1 : 0;
                for (ItemStack s : p.getInventory().offhand) n += repairStack(s, allowEnch) ? 1 : 0;
                yield n;
            }
            default -> {
                src.sendFailure(MessagesUtil.msg("repair.unknown_scope", Map.of("scope", scope)));
                yield 0;
            }
        };

        if (repaired == 0) {
            src.sendFailure(MessagesUtil.msg(allowEnch ? "repair.nothing" : "repair.nothing.enchanted_hint"));
        } else {
            src.sendSuccess(() -> MessagesUtil.msg("repair.ok", Map.of("count", repaired)), true);
        }
        return repaired > 0 ? 1 : 0;
    }

    private boolean repairStack(ItemStack stack, boolean allowEnchanted) {
        if (stack.isEmpty()) return false;
        if (!stack.isDamageableItem()) return false;
        if (stack.isEnchanted() && !allowEnchanted) return false;

        if (stack.getDamageValue() == 0) return false;
        stack.setDamageValue(0);
        return true;
    }
}
