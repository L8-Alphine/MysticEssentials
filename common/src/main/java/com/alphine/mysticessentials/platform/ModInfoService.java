package com.alphine.mysticessentials.platform;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public interface ModInfoService {
    record ModInfo(String id, String name, String version, boolean active, Optional<Path> sourceJar) {}

    /** Return all mods. active=true means loaded; if the loader exposes disabled/not-loaded entries, mark them false. */
    List<ModInfo> getAllMods();

    String getVersion();
}
