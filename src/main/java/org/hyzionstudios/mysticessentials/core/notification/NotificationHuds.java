package org.hyzionstudios.mysticessentials.core.notification;

import static org.hyzionstudios.mysticessentials.platform.ui.MysticPage.uiText;

import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * The two HUD overlays the notification engine draws: the persistent banner
 * (this platform's boss bar) and the action-bar line.
 *
 * <p>0.5.6 has no native boss bar or action bar, so both are custom HUD
 * documents. Keeping them here rather than in the delivery class means the
 * delivery logic stays about <i>policy</i> — which surfaces, for whom — and the
 * markup details stay in one small place.</p>
 */
final class NotificationHuds {

    static final String BANNER_KEY = "mysticessentials:notification_banner";
    static final String ACTION_BAR_KEY = "mysticessentials:action_bar";

    private NotificationHuds() {
    }

    /**
     * The pinned banner. Redrawn as a countdown advances, which is why the
     * remaining fraction is expressed as complementary flex weights rather than a
     * fixed width — the bar then scales with whatever resolution the player runs.
     */
    static final class Banner extends CustomUIHud {

        private static final String UI_FILE = "Hud/MysticEssentialsNotificationBanner.ui";
        private static final int Z_ORDER = 4;

        private final String text;
        private final String hint;
        private final String accent;
        private final double remaining;

        Banner(PlayerRef player, String text, String hint, String accent, double remaining) {
            super(player, BANNER_KEY, Z_ORDER);
            this.text = text == null ? "" : text;
            this.hint = hint == null ? "" : hint;
            this.accent = accent;
            this.remaining = Math.max(0, Math.min(1, remaining));
        }

        @Override
        protected void build(UICommandBuilder cmd) {
            cmd.append(UI_FILE);
            cmd.set("#MysticNotificationBannerText.TextSpans",
                    uiText("#MysticNotificationBannerText.TextSpans", text));
            cmd.set("#MysticNotificationBannerHint.TextSpans",
                    uiText("#MysticNotificationBannerHint.TextSpans", hint));
            cmd.set("#MysticNotificationBannerAccent.Background", accent);
            cmd.set("#MysticNotificationBannerFill.Background", accent);

            // Integer weights so the client never has to deal with a rounding
            // artefact at the extremes of the countdown.
            int filled = (int) Math.round(remaining * 1000);
            cmd.set("#MysticNotificationBannerFill.FlexWeight", filled);
            cmd.set("#MysticNotificationBannerRest.FlexWeight", 1000 - filled);
        }
    }

    /** The transient single-line nudge above the hotbar. */
    static final class ActionBar extends CustomUIHud {

        private static final String UI_FILE = "Hud/MysticEssentialsActionBar.ui";
        private static final int Z_ORDER = 3;

        private final String text;

        ActionBar(PlayerRef player, String text) {
            super(player, ACTION_BAR_KEY, Z_ORDER);
            this.text = text == null ? "" : text;
        }

        @Override
        protected void build(UICommandBuilder cmd) {
            cmd.append(UI_FILE);
            cmd.set("#MysticActionBarText.TextSpans", uiText("#MysticActionBarText.TextSpans", text));
        }
    }
}
