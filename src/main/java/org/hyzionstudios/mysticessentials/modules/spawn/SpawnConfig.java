package org.hyzionstudios.mysticessentials.modules.spawn;

import java.util.LinkedHashMap;
import java.util.Map;

import org.hyzionstudios.mysticessentials.api.model.MysticLocation;

/** Persisted spawn settings for {@code modules/spawn/config.json}. */
public final class SpawnConfig {

    public boolean globalSpawnEnabled = true;
    public boolean perWorldSpawnEnabled = true;
    public boolean syncGlobalSpawnToWorldProvider = true;
    public boolean teleportOnFirstJoin = true;
    public boolean teleportOnJoin = false;
    public int defaultHomeLimit = 3;

    public MysticLocation globalSpawn;
    public Map<String, MysticLocation> worldSpawns = new LinkedHashMap<>();
}
