package com.alphine.mysticessentials.neoforge.platform;

import com.alphine.mysticessentials.platform.ModInfoService;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModInfo;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public final class NeoForgeModInfoService implements ModInfoService {
    @Override
    public List<ModInfo> getAllMods() {
        return ModList.get().getMods().stream().map((IModInfo mi) -> {
            Optional<Path> jar = Optional.ofNullable(
                    mi.getOwningFile().getFile().getFilePath()
            );
            return new ModInfo(
                    mi.getModId(),
                    mi.getDisplayName(),
                    mi.getVersion().toString(),
                    true,
                    jar
            );
        }).toList();
    }
}
