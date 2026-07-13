package org.hyzionstudios.mysticessentials.modules.afk;

import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

final class AfkZoneHud extends CustomUIHud {

    static final String KEY = "mysticessentials:afk_zone";

    private static final String UI_FILE = "Hud/MysticEssentialsTeleportWarmup.ui";
    private static final int Z_ORDER = 2;

    private final String zoneName;
    private final String elapsed;
    private final String nextReward;

    AfkZoneHud(PlayerRef player, String zoneName, String elapsed, String nextReward) {
        super(player, KEY, Z_ORDER);
        this.zoneName = zoneName;
        this.elapsed = elapsed;
        this.nextReward = nextReward;
    }

    @Override
    protected void build(UICommandBuilder cmd) {
        cmd.append(UI_FILE);
        cmd.set("#Status.Text", zoneName + " | " + elapsed + " | " + nextReward);
    }
}
