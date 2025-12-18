package com.alphine.mysticessentials.vault;

import com.alphine.mysticessentials.config.MEConfig;

import java.util.UUID;

public final class VaultService {
    private final VaultStore store;

    public VaultService(VaultStore store) {
        this.store = store;
    }

    public VaultProfile profile(UUID owner) {
        return store.load(owner);
    }

    public void save(VaultProfile p) {
        store.save(p);
    }

    public VaultMeta meta(VaultProfile p, int index) {
        return p.metaByIndex.computeIfAbsent(index, k -> new VaultMeta());
    }

    public String resolveBaseName(VaultMeta meta) {
        if (meta.customBaseName != null && !meta.customBaseName.isBlank()) return meta.customBaseName;
        return MEConfig.INSTANCE.vaults.defaultVaultName;
    }

    public String resolveDisplayItemId(VaultMeta meta) {
        if (meta.displayItemId != null && !meta.displayItemId.isBlank()) return meta.displayItemId;
        return MEConfig.INSTANCE.vaults.defaultDisplayItem;
    }

    public void resetVault(java.util.UUID owner, int index, boolean clearItems, boolean resetMeta) {
        store.resetVault(owner, index, clearItems, resetMeta);
    }

    public void resetAll(java.util.UUID owner) {
        store.resetAll(owner);
    }
}
