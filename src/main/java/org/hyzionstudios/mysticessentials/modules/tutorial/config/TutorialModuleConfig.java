package org.hyzionstudios.mysticessentials.modules.tutorial.config;

/**
 * Typed model of {@code mods/MysticEssentials/modules/tutorial/config.json}.
 * Field names map directly to JSON keys via Gson; the defaults here define the
 * file generated on first run. The module ships disabled twice over: the
 * {@code tutorial} entry in the main {@code config.json} modules map is
 * {@code false}, and {@link #enabled} here is {@code false} as well.
 */
public final class TutorialModuleConfig {

    public int configVersion = 1;

    /** Master switch for tutorial features (first-join, playback, pages). */
    public boolean enabled = false;

    /** Verbose logging + debug scene provider diagnostics. */
    public boolean debug = false;

    public FirstJoin firstJoin = new FirstJoin();
    public SceneProvider sceneProvider = new SceneProvider();
    public CameraPlayback cameraPlayback = new CameraPlayback();
    public Defaults defaults = new Defaults();
    public Failsafe failsafe = new Failsafe();
    public Ui ui = new Ui();
    public Storage storage = new Storage();
    public Logging logging = new Logging();

    public static final class FirstJoin {
        public boolean enabled = false;
        public String tutorialId = "first_join";
        public int delayTicksAfterJoin = 40;
        public boolean runOnlyOnce = true;
        public boolean respectBypassPermission = true;
        public String bypassPermission = "mysticessentials.tutorial.bypassfirstjoin";
    }

    public static final class SceneProvider {
        /**
         * {@code camera} (server drives the player camera along the scene's
         * keyframes — the only mode that plays scenes on 0.5.6), {@code machinima}
         * (sends the machinima packet; the 0.5.6 client has no receiver, so this
         * is a no-op — kept for a future client), {@code debug}, or {@code noop}.
         */
        public String type = "camera";
        public boolean fallbackToNoOp = true;
        public boolean logMissingSceneProvider = true;
    }

    /**
     * Tuning for the {@code camera} scene provider (server-driven camera path).
     * The scene stores absolute world coordinates and euler angles in radians;
     * these knobs adapt the recorded timeline to live playback and let an owner
     * fix orientation/timing in-client without a rebuild.
     */
    public static final class CameraPlayback {
        /** Frames-per-second the scene's {@code Frame} numbers are played back at. */
        public double framesPerSecond = 60.0;
        /** How often the server pushes a camera update (Hz). */
        public int updateHz = 30;
        /** Client-side smoothing between updates (0..1; higher = snappier, less lag). */
        public float positionLerpSpeed = 0.5f;
        public float rotationLerpSpeed = 0.5f;
        /** Catmull-Rom spline through the keyframe positions ({@code false} = straight segments). */
        public boolean smoothPath = true;
        /** Use the keyframe {@code Look.X} as pitch when {@code Rotation.X} is ~0 (camera actors store pitch there). */
        public boolean pitchFromLook = true;
        public boolean invertPitch = false;
        public boolean invertYaw = false;
        public double yawOffsetDegrees = 0.0;
        public double pitchOffsetDegrees = 0.0;
        /** Hold the last frame for this long before ending (lets the final shot land). */
        public double holdEndSeconds = 0.5;
    }

    /** Per-tutorial player-state defaults, used when a tutorial omits {@code playerState}. */
    public static final class Defaults {
        public boolean freezePlayer = true;
        public boolean disableMovement = true;
        public boolean disableInteraction = true;
        public boolean disableDamage = true;
        public boolean disableChat = false;
        public boolean hideHud = false;
        public boolean hideOtherPlayers = false;
        public boolean restoreLocationAfterTutorial = false;
        public boolean allowSkip = false;
        public String skipPermission = "mysticessentials.tutorial.skip";
    }

    public static final class Failsafe {
        public boolean enabled = true;
        public int maxDurationSeconds = 180;
        public boolean unfreezeOnError = true;
        public boolean restoreStateOnError = true;
        public boolean showFallbackPageOnError = true;
        public String fallbackPageId = "tutorial_error";
    }

    public static final class Ui {
        public boolean enabled = true;
        public boolean useNoesisGui = true;
        public String defaultCompletionPage = "getting_started";
        public boolean closeButtonEnabled = true;
    }

    public static final class Storage {
        public String type = "json";
        public int autosaveSeconds = 60;
        public boolean saveCompletionHistory = true;
        public boolean saveReplayHistory = true;
    }

    public static final class Logging {
        public boolean logStarts = true;
        public boolean logCompletions = true;
        public boolean logCancels = true;
        public boolean logErrors = true;
    }
}
