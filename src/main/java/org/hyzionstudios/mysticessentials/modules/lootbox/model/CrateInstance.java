package org.hyzionstudios.mysticessentials.modules.lootbox.model;

/**
 * A block bound to a pool ({@code lootbox_crate/<crateId>}). A crate holds no keys
 * and no rewards, so placing, breaking or moving its block can never duplicate
 * either. It may override its name, never the pool's odds.
 */
public final class CrateInstance {

    /** The block type already has a Use interaction (chest, door, bench, modded block). */
    public static final String KIND_INTERACTIVE = "INTERACTIVE";
    /** A crate block shipped in the Mystic Essentials asset pack. */
    public static final String KIND_SHIPPED = "SHIPPED_BLOCK";

    public int schema = 1;
    public String crateId;
    /** World UUID (falls back to the world name when the UUID is unavailable). */
    public String worldId;
    public String worldName;
    public int x;
    public int y;
    public int z;
    /** Block type key recorded at conversion; a mismatch at use disables the crate. */
    public String blockKey;
    public String lootboxId;
    public String kind = KIND_INTERACTIVE;
    /** Optional name shown instead of the pool's. */
    public String name = "";
    public boolean disabled;
    public String disabledReason = "";
    public String createdBy;
    public long createdAt;

    public String position() {
        return (worldName == null || worldName.isBlank() ? worldId : worldName) + " " + x + "," + y + "," + z;
    }
}
