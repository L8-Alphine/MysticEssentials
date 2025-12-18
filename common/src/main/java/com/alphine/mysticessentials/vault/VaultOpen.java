package com.alphine.mysticessentials.vault;

import com.alphine.mysticessentials.util.MessagesUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class VaultOpen {

    private final VaultService vaults;

    public VaultOpen(VaultService vaults) {
        this.vaults = vaults;
    }

    public void openVault(ServerPlayer viewer, UUID targetOwner, int vaultIndex) {
        if (!VaultPerms.canUse(viewer)) return;

        boolean others = !viewer.getUUID().equals(targetOwner);
        if (others && !VaultPerms.canOpenOthers(viewer)) return;

        int maxVaults = VaultPerms.resolveMaxVaults(viewer);
        if (vaultIndex < 1 || vaultIndex > maxVaults) return;

        int rows = VaultPerms.resolveVaultRows(viewer);
        int size = rows * 9;

        VaultProfile profile = vaults.profile(targetOwner);
        SimpleContainer container = new SimpleContainer(size);
        loadInto(container, profile, vaultIndex);

        String baseName = vaults.resolveBaseName(vaults.meta(profile, vaultIndex));
        Component title = MessagesUtil.styled(baseName + " - #" + vaultIndex);

        viewer.openMenu(new SimpleMenuProvider((id, inv, p) ->
                new SavingVaultMenu(id, inv, container, rows, profile, vaultIndex, vaults),
                title
        ));
    }

    private static void loadInto(SimpleContainer container, VaultProfile profile, int vaultIndex) {
        List<ItemStack> stored = profile.itemsByIndex.get(vaultIndex);
        if (stored == null) return;
        int limit = Math.min(container.getContainerSize(), stored.size());
        for (int i = 0; i < limit; i++) {
            ItemStack it = stored.get(i);
            container.setItem(i, it == null ? ItemStack.EMPTY : it.copy());
        }
    }

    private static void saveFrom(SimpleContainer container, VaultProfile profile, int vaultIndex) {
        List<ItemStack> out = new ArrayList<>(container.getContainerSize());
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack it = container.getItem(i);
            out.add(it == null ? ItemStack.EMPTY : it.copy());
        }
        profile.itemsByIndex.put(vaultIndex, out);
    }

    /** Menu that saves vault contents on close (works on Fabric + NeoForge). */
    private static final class SavingVaultMenu extends ChestMenu {
        private final VaultProfile profile;
        private final int vaultIndex;
        private final VaultService vaults;

        protected SavingVaultMenu(int id,
                                  Inventory inv,
                                  SimpleContainer container,
                                  int rows,
                                  VaultProfile profile,
                                  int vaultIndex,
                                  VaultService vaults) {
            super(VaultMenus.menuTypeForRows(rows), id, inv, container, rows);
            this.profile = profile;
            this.vaultIndex = vaultIndex;
            this.vaults = vaults;
        }

        @Override
        public void removed(Player player) {
            super.removed(player);
            // Save always on close
            saveFrom((SimpleContainer) this.getContainer(), profile, vaultIndex);
            vaults.save(profile);
        }
    }
}
