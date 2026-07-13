package org.hyzionstudios.mysticessentials.modules.tutorial.player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hyzionstudios.mysticessentials.api.model.MysticLocation;

/**
 * Snapshot of exactly the player state the tutorial module mutates, captured
 * on the player's world thread before any tutorial flag is applied and used to
 * put everything back afterwards. This is the tutorial-owned "lock ledger":
 * only values recorded here are ever restored, so state owned by other systems
 * (e.g. flight granted by the Flight module before the tutorial started) is
 * returned to whatever it was, and state the tutorial never touched is never
 * written at all.
 */
public final class PlayerStateSnapshot {

    public final UUID playerId;
    public final long capturedAt = System.currentTimeMillis();

    /** Where the player stood when the tutorial began (for restoreLocationAfterTutorial). */
    public MysticLocation location;

    // Movement settings captured before the freeze zeroed them.
    public boolean movementCaptured;
    public float baseSpeed;
    public float acceleration;
    public float jumpForce;
    public float swimJumpForce;
    public float climbSpeed;
    public float climbSpeedLateral;
    public float horizontalFlySpeed;
    public float verticalFlySpeed;
    public boolean canFly;

    /** Whether the entity already had the Invulnerable component before the tutorial. */
    public boolean invulnerableApplied;
    public boolean wasInvulnerable;

    /** HUD components visible before the tutorial hid them ({@code null} = HUD untouched). */
    public List<String> visibleHudComponents;

    private boolean restored;

    public PlayerStateSnapshot(UUID playerId) {
        this.playerId = playerId;
    }

    public void rememberHud(List<String> componentNames) {
        this.visibleHudComponents = new ArrayList<>(componentNames);
    }

    /** @return {@code true} the first time only — guards against double restores. */
    public synchronized boolean beginRestore() {
        if (restored) {
            return false;
        }
        restored = true;
        return true;
    }

    public synchronized boolean isRestored() {
        return restored;
    }
}
