package com.alphine.mysticessentials.vault;

import com.alphine.mysticessentials.util.MessagesUtil;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;

import java.util.Map;
import java.util.UUID;

public final class VaultRenameUi {
    private VaultRenameUi(){}

    public static void open(ServerPlayer viewer, UUID targetOwner, int vaultIndex, int returnPage) {
        viewer.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new VaultRenameMenu(id, inv, viewer, targetOwner, vaultIndex, returnPage),
                MessagesUtil.msg("vault.ui.rename.title", Map.of("index", vaultIndex))
        ));
    }
}
