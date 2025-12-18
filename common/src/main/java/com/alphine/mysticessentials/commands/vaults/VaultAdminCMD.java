package com.alphine.mysticessentials.commands.vaults;

import com.alphine.mysticessentials.MysticEssentialsCommon;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import com.alphine.mysticessentials.vault.VaultPerms;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class VaultAdminCMD {

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("vaultadmin")
                .requires(src -> Perms.has(src, PermNodes.ADMIN, 2) || Perms.has(src, PermNodes.ALL, 2) || src.hasPermission(2))
                .then(Commands.literal("reset")
                        .requires(VaultAdminCMD::hasReset)
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("number", IntegerArgumentType.integer(1, 64))
                                        .executes(ctx -> resetVault(ctx.getSource(),
                                                EntityArgument.getPlayer(ctx, "player"),
                                                IntegerArgumentType.getInteger(ctx, "number"),
                                                true,
                                                true))
                                        .then(Commands.argument("clearItems", BoolArgumentType.bool())
                                                .executes(ctx -> resetVault(ctx.getSource(),
                                                        EntityArgument.getPlayer(ctx, "player"),
                                                        IntegerArgumentType.getInteger(ctx, "number"),
                                                        BoolArgumentType.getBool(ctx, "clearItems"),
                                                        true))
                                                .then(Commands.argument("resetMeta", BoolArgumentType.bool())
                                                        .executes(ctx -> resetVault(ctx.getSource(),
                                                                EntityArgument.getPlayer(ctx, "player"),
                                                                IntegerArgumentType.getInteger(ctx, "number"),
                                                                BoolArgumentType.getBool(ctx, "clearItems"),
                                                                BoolArgumentType.getBool(ctx, "resetMeta"))))))))
                .then(Commands.literal("resetall")
                        .requires(VaultAdminCMD::hasResetAll)
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> resetAll(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"), false))
                                .then(Commands.argument("force", BoolArgumentType.bool())
                                        .executes(ctx -> resetAll(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"),
                                                BoolArgumentType.getBool(ctx, "force"))))))
        );
    }

    private static boolean hasReset(CommandSourceStack src) {
        if (src.getEntity() instanceof ServerPlayer p) return VaultPerms.canReset(p);
        return true; // console
    }

    private static boolean hasResetAll(CommandSourceStack src) {
        if (src.getEntity() instanceof ServerPlayer p) return VaultPerms.canResetAll(p);
        return true;
    }

    private static int resetVault(CommandSourceStack src, ServerPlayer target, int number, boolean clearItems, boolean resetMeta) {
        var common = MysticEssentialsCommon.get();

        if (common.vaultStore == null) {
            src.sendFailure(Component.literal("§cVault store is not initialized."));
            return 0;
        }

        // Respect reset exempt (unless console)
        if (src.getEntity() instanceof ServerPlayer viewer) {
            if (VaultPerms.isResetExempt(target) && !viewer.getUUID().equals(target.getUUID())) {
                src.sendFailure(Component.literal("§cThat player is exempt from vault resets."));
                return 0;
            }
        }

        common.vaultStore.resetVault(target.getUUID(), number, clearItems, resetMeta);
        src.sendSuccess(() -> Component.literal("§aReset vault #" + number + " for " + target.getGameProfile().getName()
                + " (clearItems=" + clearItems + ", resetMeta=" + resetMeta + ")"), true);
        return 1;
    }

    private static int resetAll(CommandSourceStack src, ServerPlayer target, boolean force) {
        var common = MysticEssentialsCommon.get();
        if (common.vaultStore == null) {
            src.sendFailure(Component.literal("§cVault store is not initialized."));
            return 0;
        }

        if (!force && VaultPerms.isResetExempt(target) && !(src.getEntity() == null)) {
            src.sendFailure(Component.literal("§cThat player is exempt from vault resets. Use /vaultadmin resetall <player> true"));
            return 0;
        }

        common.vaultStore.resetAll(target.getUUID());
        src.sendSuccess(() -> Component.literal("§aReset ALL vaults for " + target.getGameProfile().getName()
                + (force ? " (forced)" : "")), true);
        return 1;
    }
}
