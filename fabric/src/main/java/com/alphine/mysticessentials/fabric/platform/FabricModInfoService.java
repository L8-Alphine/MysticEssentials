package com.alphine.mysticessentials.fabric.platform;

import com.alphine.mysticessentials.platform.ModInfoService;
import net.fabricmc.loader.api.FabricLoader;

import java.util.List;

import static com.alphine.mysticessentials.MysticEssentialsCommon.MOD_ID;

public final class FabricModInfoService implements ModInfoService {
    @Override public List<ModInfo> getAllMods() {
        return FabricLoader.getInstance().getAllMods().stream().map(mc -> {
            var m = mc.getMetadata();
            return new ModInfo(
                    m.getId(), m.getName(), m.getVersion().getFriendlyString(),
                    true, mc.getOrigin().getPaths().stream().findFirst()
            );
        }).toList();
    }

    @Override
    public String getVersion() {
        return FabricLoader.getInstance()
                .getModContainer(MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("UNKNOWN");
    }
}
