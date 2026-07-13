package org.hyzionstudios.mysticessentials.modules.tutorial.scene;

/** How a scene playback ended. */
public enum TutorialSceneResultType {
    /** The scene ran to its end (or the provider does not track progress). */
    COMPLETED,
    /** The provider could not play the scene. */
    FAILED,
    /** The scene was stopped externally (session stop/skip). */
    STOPPED,
    /** The scene did not finish within its time budget. */
    TIMED_OUT,
    /** The provider is not available on this server/client. */
    UNAVAILABLE
}
