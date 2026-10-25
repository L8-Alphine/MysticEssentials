package org.hyzionstudios.mysticessentials.modules.lootbox.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.hyzionstudios.mysticessentials.core.util.Json;

/**
 * A block bound to a pool ({@code lootbox_crate/<crateId>}). A crate holds no keys
 * and no rewards, so placing, breaking or moving its block can never duplicate
 * either. It may override its name, behaviour and protection, never the pool's
 * odds.
 *
 * <p><b>Schema.</b> Version 1 (Phase 1) had no behaviour or protection fields;
 * version 2 had no visuals; version 3 had no generated variants; version 4 had no
 * effect stack ({@link Visuals#extras}). Gson keeps the field initialisers for anything a
 * stored document lacks, and {@link #normalise()} fills what an explicit
 * {@code null} cleared, so an older document loads with the defaults: preview
 * first, the server's protection, and no effects (an existing crate never
 * starts sparkling after an update).</p>
 */
public final class CrateInstance {

    /** The block type already has a Use interaction (chest, door, bench, modded block). */
    public static final String KIND_INTERACTIVE = "INTERACTIVE";
    /** A crate block shipped in the Mystic Essentials asset pack. */
    public static final String KIND_SHIPPED = "SHIPPED_BLOCK";
    /** A plain block swapped for a generated look-alike that carries a Use interaction (P2-J). */
    public static final String KIND_GENERATED = "GENERATED_VARIANT";

    /** Use opens the preview; its Open button leads to the confirm screen (the default). */
    public static final String OPEN_PREVIEW_THEN_CONFIRM = "PREVIEW_THEN_CONFIRM";
    /** Use goes straight to the confirm screen, which still lists every reward with its odds. */
    public static final String OPEN_DIRECT_CONFIRM = "DIRECT_CONFIRM";

    public static final int SCHEMA = 5;

    public int schema = SCHEMA;
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
    /** {@link #OPEN_PREVIEW_THEN_CONFIRM} or {@link #OPEN_DIRECT_CONFIRM}. Schema 2. */
    public String openBehavior = OPEN_PREVIEW_THEN_CONFIRM;
    /** Protection this crate adds on top of the server's. Schema 2. */
    public Protection protection = new Protection();
    public String updatedBy;
    public long updatedAt;
    /** Particles, sound and floating name. Schema 3. */
    public Visuals visuals = new Visuals();
    /**
     * For {@link #KIND_GENERATED}: the block the look-alike replaced, put back
     * when the crate is removed. {@code null} for every other kind. Schema 4.
     */
    public String originalBlockKey;

    /**
     * Protection a crate asks for beyond {@code crates.*} in the module config. A
     * crate can only tighten: the effective setting is the server's OR the
     * crate's, so a crate can never switch protection off.
     */
    public static final class Protection {
        /** Cancel player breaks even when the server allows them. */
        public boolean preventPlayerBreak;
        /** Cancel damage (including unattributed explosions) even when the server allows it. */
        public boolean preventDamage;
    }

    /**
     * What players near the crate see and hear. Ids are particle system and
     * sound event asset ids; blank means none. Particles and sounds go only to
     * nearby players who have not turned crate effects off. The floating name
     * is global (the engine's nameplates have no per-player visibility) and
     * also needs {@code crates.nameplates} in the module config.
     */
    public static final class Visuals {
        /** Refreshed every {@code crates.idleEffectIntervalMs} while players are near. */
        public String idleParticle = "";
        /** Played at the crate when an opening from it commits. */
        public String openParticle = "";
        public String openSound = "";
        /** Float the crate's name (or its pool's) above it. */
        public boolean nameplate;
        /**
         * The effect stack: more particles and sounds on top of the three slots
         * above, each with its own phase, height, scale and (idle particles)
         * interval. Schema 5; empty for every older crate.
         */
        public List<Effect> extras = new ArrayList<>();
    }

    /** A deep, normalised copy of {@code effects}; {@code null} gives an empty list. */
    public static List<Effect> copyEffects(List<Effect> effects) {
        List<Effect> copy = new ArrayList<>();
        if (effects != null) {
            for (Effect effect : effects) {
                if (effect != null) {
                    copy.add(Json.gson().fromJson(Json.toTree(effect), Effect.class).normalise());
                }
            }
        }
        return copy;
    }

    /** One stacked effect. Ids are checked against the live registries when played, never trusted. */
    public static final class Effect {
        public static final String PHASE_IDLE = "IDLE";
        public static final String PHASE_OPEN = "OPEN";
        public static final String KIND_PARTICLE = "PARTICLE";
        public static final String KIND_SOUND = "SOUND";

        /** {@link #PHASE_IDLE} repeats while players are near; {@link #PHASE_OPEN} plays once per opening. */
        public String phase = PHASE_OPEN;
        /** {@link #KIND_PARTICLE} or {@link #KIND_SOUND}. Sounds only play on opening. */
        public String kind = KIND_PARTICLE;
        public String id = "";
        /** Height above the crate block's base, in blocks. */
        public double height = 0.5;
        /** Particle scale; ignored for sounds. */
        public double scale = 1.0;
        /** Idle particles: how often this one repeats, at least the module's idle tick. */
        public long intervalMs = 2000L;

        /** Clamps every number into the range the editor offers. @return this effect */
        public Effect normalise() {
            phase = PHASE_IDLE.equalsIgnoreCase(phase == null ? "" : phase.trim()) ? PHASE_IDLE : PHASE_OPEN;
            kind = KIND_SOUND.equalsIgnoreCase(kind == null ? "" : kind.trim()) ? KIND_SOUND : KIND_PARTICLE;
            if (KIND_SOUND.equals(kind)) {
                phase = PHASE_OPEN;
            }
            id = id == null ? "" : id.trim();
            height = Double.isFinite(height) ? Math.max(-1.0, Math.min(4.0, height)) : 0.5;
            scale = Double.isFinite(scale) ? Math.max(0.1, Math.min(4.0, scale)) : 1.0;
            intervalMs = Math.max(500L, Math.min(60_000L, intervalMs));
            return this;
        }

        /** A short label for lists and change logs, e.g. {@code idle Dust_Sparkles @1.0 x1.5 /2s}. */
        public String label() {
            StringBuilder text = new StringBuilder(PHASE_IDLE.equals(phase) ? "idle " : "open ")
                    .append(KIND_SOUND.equals(kind) ? "sound " : "").append(id.isEmpty() ? "(none)" : id)
                    .append(" @").append(height);
            if (KIND_PARTICLE.equals(kind)) {
                text.append(" x").append(scale);
                if (PHASE_IDLE.equals(phase)) {
                    text.append(" /").append(intervalMs / 1000.0).append('s');
                }
            }
            return text.toString();
        }
    }

    public String position() {
        return (worldName == null || worldName.isBlank() ? worldId : worldName) + " " + x + "," + y + "," + z;
    }

    /** @return {@code true} when a use goes straight to the confirm screen. */
    public boolean directConfirm() {
        return OPEN_DIRECT_CONFIRM.equals(openBehavior);
    }

    /**
     * Fills anything an older or hand-edited document left empty and upgrades
     * the schema number. Safe to call more than once. @return this crate
     */
    public CrateInstance normalise() {
        if (kind == null || kind.isBlank()) {
            kind = KIND_INTERACTIVE;
        }
        if (name == null) {
            name = "";
        }
        if (disabledReason == null) {
            disabledReason = "";
        }
        openBehavior = OPEN_DIRECT_CONFIRM.equalsIgnoreCase(openBehavior == null ? "" : openBehavior.trim())
                ? OPEN_DIRECT_CONFIRM : OPEN_PREVIEW_THEN_CONFIRM;
        if (protection == null) {
            protection = new Protection();
        }
        if (visuals == null) {
            visuals = new Visuals();
        }
        visuals.idleParticle = visuals.idleParticle == null ? "" : visuals.idleParticle.trim();
        visuals.openParticle = visuals.openParticle == null ? "" : visuals.openParticle.trim();
        visuals.openSound = visuals.openSound == null ? "" : visuals.openSound.trim();
        if (visuals.extras == null) {
            visuals.extras = new ArrayList<>();
        }
        visuals.extras.removeIf(java.util.Objects::isNull);
        visuals.extras.forEach(Effect::normalise);
        if (lootboxId != null) {
            lootboxId = lootboxId.toLowerCase(Locale.ROOT);
        }
        schema = Math.max(schema, SCHEMA);
        return this;
    }

    /** A deep copy through the same Gson mapping the storage uses, so new fields never need listing here. */
    public CrateInstance copy() {
        return Json.gson().fromJson(Json.toTree(this), CrateInstance.class).normalise();
    }
}
