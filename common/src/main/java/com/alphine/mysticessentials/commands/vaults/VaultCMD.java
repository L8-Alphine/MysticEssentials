package com.alphine.mysticessentials.commands.vaults;

import com.alphine.mysticessentials.MysticEssentialsCommon;
import com.alphine.mysticessentials.vault.VaultPerms;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class VaultCMD {

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("vault")
                .requires(src -> true) // permission checked in handlers
                // /vault
                .executes(ctx -> openSelectorSelf(ctx.getSource()))
                // /vault <number>
                .then(Commands.argument("number", IntegerArgumentType.integer(1, 64))
                        .executes(ctx -> openVaultSelf(ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "number"))))
                // /vault <player>
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(ctx -> openSelectorOther(ctx.getSource(),
                                EntityArgument.getPlayer(ctx, "player")))
                        // /vault <player> <number>
                        .then(Commands.argument("number", IntegerArgumentType.integer(1, 64))
                                .executes(ctx -> openVaultOther(ctx.getSource(),
                                        EntityArgument.getPlayer(ctx, "player"),
                                        IntegerArgumentType.getInteger(ctx, "number")))))
        );
    }

    private static int openSelectorSelf(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer viewer = src.getPlayerOrException();

        if (!VaultPerms.canUse(viewer)) {
            src.sendFailure(Component.literal("§cYou do not have permission to use vaults."));
            return 0;
        }

        var common = MysticEssentialsCommon.get();
        if (common.vaultSelectorUi == null) {
            src.sendFailure(Component.literal("§cVault system is not initialized."));
            return 0;
        }

        common.vaultSelectorUi.open(viewer, viewer.getUUID());
        return 1;
    }

    private static int openVaultSelf(CommandSourceStack src, int number) throws CommandSyntaxException {
        ServerPlayer viewer = src.getPlayerOrException();

        if (!VaultPerms.canUse(viewer)) {
            src.sendFailure(Component.literal("§cYou do not have permission to use vaults."));
            return 0;
        }

        int max = VaultPerms.resolveMaxVaults(viewer);
        if (max <= 0) {
            src.sendFailure(Component.literal("§cYou do not have any vaults."));
            return 0;
        }
        if (number < 1 || number > max) {
            src.sendFailure(Component.literal("§cVault out of range. Max: " + max));
            return 0;
        }

        var common = MysticEssentialsCommon.get();
        if (common.vaultOpen == null) {
            src.sendFailure(Component.literal("§cVault system is not initialized."));
            return 0;
        }

        common.vaultOpen.openVault(viewer, viewer.getUUID(), number);
        return 1;
    }

    private static int openSelectorOther(CommandSourceStack src, ServerPlayer target) throws CommandSyntaxException {
        ServerPlayer viewer = src.getPlayerOrException();

        if (!VaultPerms.canUse(viewer)) {
            src.sendFailure(Component.literal("§cYou do not have permission to use vaults."));
            return 0;
        }
        if (!VaultPerms.canOpenOthers(viewer)) {
            src.sendFailure(Component.literal("§cYou do not have permission to view other players' vaults."));
            return 0;
        }

        var common = MysticEssentialsCommon.get();
        if (common.vaultSelectorUi == null) {
            src.sendFailure(Component.literal("§cVault system is not initialized."));
            return 0;
        }

        common.vaultSelectorUi.open(viewer, target.getUUID());
        return 1;
    }

    private static int openVaultOther(CommandSourceStack src, ServerPlayer target, int number) throws CommandSyntaxException {
        ServerPlayer viewer = src.getPlayerOrException();

        if (!VaultPerms.canUse(viewer)) {
            src.sendFailure(Component.literal("§cYou do not have permission to use vaults."));
            return 0;
        }
        if (!VaultPerms.canOpenOthers(viewer)) {
            src.sendFailure(Component.literal("§cYou do not have permission to view other players' vaults."));
            return 0;
        }

        int max = VaultPerms.resolveMaxVaults(viewer);
        if (max <= 0) {
            src.sendFailure(Component.literal("§cYou do not have any vaults."));
            return 0;
        }
        if (number < 1 || number > max) {
            src.sendFailure(Component.literal("§cVault out of range. Max: " + max));
            return 0;
        }

        var common = MysticEssentialsCommon.get();
        if (common.vaultOpen == null) {
            src.sendFailure(Component.literal("§cVault system is not initialized."));
            return 0;
        }

        // NOTE: uses viewer perms (like Essentials does). If you want to gate by target's max vaults instead,
        // tell me and I’ll swap that behavior.
        common.vaultOpen.openVault(viewer, target.getUUID(), number);
        return 1;
    }
}
