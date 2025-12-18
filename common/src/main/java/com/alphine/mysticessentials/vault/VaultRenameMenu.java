package com.alphine.mysticessentials.vault;

import com.alphine.mysticessentials.MysticEssentialsCommon;
import com.alphine.mysticessentials.util.ItemUtil;
import com.alphine.mysticessentials.util.StackTextUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

public final class VaultRenameMenu extends AnvilMenu {

    private final ServerPlayer viewer;
    private final UUID targetOwner;
    private final int vaultIndex;
    private final int returnPage;

    private final VaultService vaults;
    private final VaultOpen vaultOpen;

    public VaultRenameMenu(int id,
                           Inventory inv,
                           ServerPlayer viewer,
                           UUID targetOwner,
                           int vaultIndex,
                           int returnPage) {
        super(id, inv, ContainerLevelAccess.create(viewer.level(), BlockPos.containing(viewer.position())));
        this.viewer = viewer;
        this.targetOwner = targetOwner;
        this.vaultIndex = vaultIndex;
        this.returnPage = Math.max(0, returnPage);

        var common = MysticEssentialsCommon.get();
        this.vaults = common.vaultService;
        this.vaultOpen = common.vaultOpen;

        // Put a paper in the left input with current name as hint
        VaultProfile profile = vaults.profile(targetOwner);
        VaultMeta meta = vaults.meta(profile, vaultIndex);
        String base = vaults.resolveBaseName(meta);

        ItemStack paper = ItemUtil.fromId("minecraft:paper", 1);
        StackTextUtil.setName(paper, Component.literal(base));
        this.getSlot(0).set(paper);
    }

    @Override
    public void createResult() {
        super.createResult();
        // output is computed by vanilla based on rename text
    }

    @Override
    public void onTake(Player player, ItemStack stack) {
        super.onTake(player, stack);

        // When output is taken, apply name (strip the "#n" suffix handling is done when rendering)
        if (!(player instanceof ServerPlayer sp)) return;
        if (!VaultPerms.canRename(sp)) return;

        Component name = stack.getHoverName();
        String raw = (name == null) ? "" : name.getString();
        if (raw.isBlank()) return;

        String cleaned = VaultTextSanitizer.sanitizeVaultBaseName(sp, raw);

        VaultProfile profile = vaults.profile(targetOwner);
        VaultMeta meta = vaults.meta(profile, vaultIndex);
        meta.customBaseName = cleaned;
        vaults.save(profile);

        // Back to settings
        VaultSettingsUi.open(sp, targetOwner, vaultIndex, returnPage);
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        // If they just close the anvil, return to settings
        if (player instanceof ServerPlayer sp) {
            VaultSettingsUi.open(sp, targetOwner, vaultIndex, returnPage);
        }
    }
}
