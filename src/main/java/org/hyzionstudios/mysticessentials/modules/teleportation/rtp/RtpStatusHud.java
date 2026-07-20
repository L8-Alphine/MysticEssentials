package org.hyzionstudios.mysticessentials.modules.teleportation.rtp;

import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/** Compact, non-modal status indicator for the random-teleport lifecycle. */
final class RtpStatusHud extends CustomUIHud {

    static final String KEY = "mysticessentials:rtp_status";

    private static final String UI_FILE = "Hud/MysticEssentialsRtpStatus.ui";
    private static final int Z_ORDER = 1;

    private final String text;

    RtpStatusHud(PlayerRef player, String text) {
        super(player, KEY, Z_ORDER);
        this.text = text;
    }

    @Override
    protected void build(UICommandBuilder cmd) {
        cmd.append(UI_FILE);
        cmd.set("#Status.Text", text);
    }
}
