package com.alphine.mysticessentials.vault;

import com.alphine.mysticessentials.config.MEConfig;
import com.alphine.mysticessentials.perm.PermNodes;
import com.alphine.mysticessentials.perm.Perms;
import net.minecraft.server.level.ServerPlayer;

public final class VaultPerms {
    private VaultPerms() {}

    public static int resolveMaxVaults(ServerPlayer player) {
        int cap = MEConfig.INSTANCE.vaults.numericCap;
        if (cap <= 0) cap = 64;

        for (int i = cap; i >= 1; i--) {
            if (Perms.has(player, PermNodes.vaultAmountNode(i))) return i;
        }
        return 0;
    }

    public static int resolveVaultRows(ServerPlayer player) {
        for (int rows = 6; rows >= 1; rows--) {
            if (Perms.has(player, PermNodes.vaultSizeNode(String.valueOf(rows)))) return rows;
        }
        return 1; // sane default
    }

    public static boolean canUse(ServerPlayer player) {
        return Perms.has(player, PermNodes.VAULT_USE);
    }

    public static boolean canOpenOthers(ServerPlayer player) {
        return Perms.has(player, PermNodes.VAULT_OTHERS);
    }

    public static boolean canRename(ServerPlayer player) {
        return Perms.has(player, PermNodes.VAULT_RENAME);
    }

    public static boolean canReset(ServerPlayer player) {
        return Perms.has(player, PermNodes.VAULT_RESET);
    }

    public static boolean canResetAll(ServerPlayer player) {
        return Perms.has(player, PermNodes.VAULT_RESET_ALL);
    }

    public static boolean isResetExempt(ServerPlayer target) {
        return Perms.has(target, PermNodes.VAULT_RESET_EXEMPT);
    }

    public static boolean canUseAnyDisplayItem(ServerPlayer player) {
        return Perms.has(player, PermNodes.vaultItemAllowNode("*")) || Perms.has(player, "messentials.vault.item.*");
    }

    public static boolean canUseDisplayItem(ServerPlayer player, String itemId) {
        if (canUseAnyDisplayItem(player)) return true;
        return Perms.has(player, PermNodes.vaultItemAllowNode(itemId));
    }
}