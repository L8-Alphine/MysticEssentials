package com.alphine.mysticessentials.vault;

import com.alphine.mysticessentials.util.MessagesUtil;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;

import java.util.Map;
import java.util.UUID;

public final class VaultSettingsUi {
    private VaultSettingsUi() {}

    public static void open(ServerPlayer viewer, UUID targetOwner, int vaultIndex, int returnPage) {
        viewer.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new VaultSettingsMenu(id, inv, viewer, targetOwner, vaultIndex, returnPage),
                MessagesUtil.msg("vault.ui.settings.title", Map.of("index", vaultIndex))
        ));
    }
}
