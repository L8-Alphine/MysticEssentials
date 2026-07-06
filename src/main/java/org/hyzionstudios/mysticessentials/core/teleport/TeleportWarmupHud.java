package org.hyzionstudios.mysticessentials.core.teleport;

import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

final class TeleportWarmupHud extends CustomUIHud {

    static final String KEY = "mysticessentials:teleport_warmup";

    private static final String UI_FILE = "Hud/MysticEssentialsTeleportWarmup.ui";
    private static final int Z_ORDER = 1;

    private final String text;

    TeleportWarmupHud(PlayerRef player, String text) {
        super(player, KEY, Z_ORDER);
        this.text = text;
    }

    @Override
    protected void build(UICommandBuilder cmd) {
        cmd.append(UI_FILE);
        cmd.set("#Status.Text", text);
    }
}
