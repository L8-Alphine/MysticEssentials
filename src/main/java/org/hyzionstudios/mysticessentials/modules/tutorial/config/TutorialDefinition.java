package org.hyzionstudios.mysticessentials.modules.tutorial.config;

import java.util.ArrayList;
import java.util.List;

/**
 * A tutorial loaded from
 * {@code mods/MysticEssentials/modules/tutorial/tutorials/<tutorial-id>.json}.
 * Sections left out of the file fall back to the module-level defaults in
 * {@link TutorialModuleConfig.Defaults} (resolved by the session manager, not
 * here, so the JSON on disk stays an exact mirror of what the owner wrote).
 */
public final class TutorialDefinition {

    public String id;
    public String displayName;
    public String description = "";
    public boolean enabled = true;

    public Replay replay = new Replay();
    public Requirements requirements = new Requirements();
    public PlayerState playerState;
    public Machinima machinima = new Machinima();
    public Completion completion = new Completion();
    public Failsafe failsafe = new Failsafe();

    public static final class Replay {
        public boolean allowReplay = false;
        public boolean adminCanForceReplay = true;
        public boolean countReplayAsCompletion = false;
    }

    public static final class Requirements {
        public List<String> permissions = new ArrayList<>();
        public List<String> worlds = new ArrayList<>();
        public List<String> mustHaveCompleted = new ArrayList<>();
        public List<String> mustNotHaveCompleted = new ArrayList<>();
    }

    /**
     * Player-state flags applied for the duration of the tutorial. Boxed types:
     * {@code null} means "not set in the file, use the module default".
     */
    public static final class PlayerState {
        public Boolean freezePlayer;
        public Boolean disableMovement;
        public Boolean disableInteraction;
        public Boolean disableDamage;
        public Boolean disableChat;
        public Boolean hideHud;
        public Boolean hideOtherPlayers;
        public Boolean restoreLocationAfterTutorial;
    }

    public static final class Machinima {
        public boolean enabled = true;
        public String sceneId = "";
        public String pathId = "";
        public boolean waitForCompletion = true;
        public int startDelayTicks = 20;
        public int timeoutSeconds = 180;

        /**
         * Where the scene plays: {@code "fixed"} (default) uses the scene's
         * authored world coordinates; {@code "relocate"} translates it so its
         * origin sits at {@link #anchor}. Recorded scenes store absolute
         * positions, so {@code relocate} is what makes one scene reusable at
         * every player's location.
         */
        public String placement = "fixed";

        /** Anchor for {@code placement = "relocate"}. */
        public Anchor anchor = new Anchor();

        public static final class Anchor {
            /** {@code "player"} = the player's position when the scene starts; {@code "coords"} = {@link #x}/{@link #y}/{@link #z}. */
            public String type = "player";
            public double x;
            public double y;
            public double z;
        }
    }

    public static final class Completion {
        public boolean markCompleted = true;
        public boolean showPage = true;
        public String pageId = "";
        public boolean runCommands = false;
        public List<String> commands = new ArrayList<>();
    }

    public static final class Failsafe {
        public boolean enabled = true;
        public int timeoutSeconds = 180;
        public boolean restoreState = true;
        public boolean unfreezePlayer = true;
    }

    /** @return {@code true} if the definition has the minimum required fields. */
    public boolean isValid() {
        return id != null && !id.isBlank();
    }

    public String displayNameOrId() {
        return displayName == null || displayName.isBlank() ? id : displayName;
    }
}
