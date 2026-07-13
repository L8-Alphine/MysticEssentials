package org.hyzionstudios.mysticessentials.modules.tutorial.scene;

/** Outcome of one scene playback, always delivered (never an exception). */
public final class TutorialSceneResult {

    public final TutorialSceneResultType type;
    public final String detail;

    private TutorialSceneResult(TutorialSceneResultType type, String detail) {
        this.type = type;
        this.detail = detail == null ? "" : detail;
    }

    public static TutorialSceneResult of(TutorialSceneResultType type) {
        return new TutorialSceneResult(type, "");
    }

    public static TutorialSceneResult of(TutorialSceneResultType type, String detail) {
        return new TutorialSceneResult(type, detail);
    }

    public boolean isCompleted() {
        return type == TutorialSceneResultType.COMPLETED;
    }

    @Override
    public String toString() {
        return type + (detail.isBlank() ? "" : " (" + detail + ")");
    }
}
