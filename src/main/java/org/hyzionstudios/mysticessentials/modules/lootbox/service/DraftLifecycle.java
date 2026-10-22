package org.hyzionstudios.mysticessentials.modules.lootbox.service;

import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.hyzionstudios.mysticessentials.core.util.Json;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.KeyDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.OddsEngine;
import org.hyzionstudios.mysticessentials.modules.lootbox.validate.DraftValidator;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Computes a draft's lifecycle state (implementation plan §4.1) from the draft and
 * what is published; nothing about it is stored. Every engine lookup is passed in,
 * so {@code verifyLootBoxDrafts} runs it without a server.
 *
 * <p>{@code PUBLISHED} compares the draft's canonical spec hash with the active
 * one, computed the way publishing computes it, so the state says "Published"
 * exactly when {@code /lootbox publish} would answer "unchanged". File bytes are
 * never compared: reformatting a file does not make it {@code READY}.</p>
 *
 * @param validator     the publish gates for one draft
 * @param specHash      canonical hash of a valid draft's spec
 * @param publishedHash canonical hash of the active published spec, or {@code null} when never published
 * @param disabled      whether the published pool is closed
 * @param blank         whether the draft is still the empty shell {@link DraftStore#create} writes
 */
public record DraftLifecycle<T>(
        Function<T, List<String>> validator,
        Function<T, String> specHash,
        Function<String, String> publishedHash,
        Predicate<String> disabled,
        Predicate<T> blank) {

    public enum State {
        /** Never published and still empty (a pool with no rewards, a key with no base item). */
        DRAFT,
        /** Fails the publish gates; {@link Status#problems()} says why. */
        INVALID,
        /** Valid and different from the active revision, or never published. */
        READY,
        /** Valid and identical to the active revision. */
        PUBLISHED,
        /** The published pool is closed; openings are blocked. */
        DISABLED,
        /** Moved to {@code _archive/}; only archive listings report it. */
        ARCHIVED
    }

    /** A draft's state, with the validator's problems (also kept for {@code DISABLED}). */
    public record Status(State state, List<String> problems) {

        public Status {
            problems = List.copyOf(problems);
        }

        /** {@code INVALID (3 problems)}, otherwise the state's name. */
        public String label() {
            return state == State.INVALID
                    ? "INVALID (" + problems.size() + (problems.size() == 1 ? " problem)" : " problems)")
                    : state.name();
        }
    }

    /** @param id the draft's id; {@code null} counts as never published */
    public Status status(String id, T draft) {
        List<String> problems = validator.apply(draft);
        if (id != null && disabled.test(id)) {
            return new Status(State.DISABLED, problems);
        }
        String published = id == null ? null : publishedHash.apply(id);
        if (!problems.isEmpty()) {
            return new Status(published == null && blank.test(draft) ? State.DRAFT : State.INVALID, problems);
        }
        return new Status(specHash.apply(draft).equals(published) ? State.PUBLISHED : State.READY, problems);
    }

    // ----- Pools and keys -----------------------------------------------------

    /**
     * @param environment       the publish gates' lookups; COMMAND rewards are a publisher's permission,
     *                          not a property of the draft, so pass {@code mayPublishCommands = true}
     * @param activeContentHash the active revision's {@code contentHash}, or {@code null}
     * @param disabled          whether the pool is closed
     */
    public static DraftLifecycle<LootBoxDraft> pools(Supplier<DraftValidator.Environment> environment,
            Function<String, String> activeContentHash, Predicate<String> disabled) {
        return new DraftLifecycle<>(
                draft -> DraftValidator.validate(draft, environment.get()),
                draft -> {
                    LootBoxDraft frozen = freeze(draft, LootBoxDraft.class);
                    return poolContentHash(frozen, OddsEngine.resolveWeights(frozen));
                },
                activeContentHash,
                disabled,
                draft -> draft.rewards == null || draft.rewards.isEmpty());
    }

    /** @param publishedSpec the active published key definition's spec, or {@code null} */
    public static DraftLifecycle<KeyDraft> keys(Predicate<String> itemExists,
            Function<String, KeyDraft> publishedSpec) {
        return new DraftLifecycle<>(
                key -> DraftValidator.validateKey(key, itemExists),
                key -> keySpecHash(freeze(key, KeyDraft.class)),
                id -> {
                    KeyDraft spec = publishedSpec.apply(id);
                    return spec == null ? null : keySpecHash(spec);
                },
                id -> false,
                key -> key.baseItemId == null || key.baseItemId.isBlank());
    }

    /** The {@code contentHash} a revision of {@code frozen} carries. Publishing uses this too. */
    public static String poolContentHash(LootBoxDraft frozen, List<Long> weights) {
        return OddsEngine.sha256(Json.toString(canonical(frozen)) + "|" + weights);
    }

    /** Publishing a key answers "unchanged" exactly when these are equal. */
    public static String keySpecHash(KeyDraft spec) {
        return OddsEngine.sha256(Json.toString(canonical(spec)));
    }

    /**
     * Fields added after Phase 2. While unset ({@code null}, or an empty string,
     * list or map) they are left out of the canonical JSON, so a pool or key
     * published before they existed keeps its content hash and still reads
     * "Published" instead of asking to be republished.
     */
    static final Set<String> LATER_FIELDS = Set.of(
            // pools (P3/P4)
            "conditions", "ineligibleRewards", "bonusRolls", "pity", "startsAt", "endsAt", "afterEnd", "scope",
            "paidDisclosure",
            // rewards (P3)
            "provider", "params", "bundle", "duplicates", "currency",
            // keys (P3-WP8)
            "identityMode");

    /** The JSON tree hashed for {@code value}: its Gson tree without unset {@link #LATER_FIELDS}. */
    public static JsonElement canonical(Object value) {
        JsonElement tree = Json.toTree(value);
        strip(tree);
        return tree;
    }

    private static void strip(JsonElement element) {
        if (element == null) {
            return;
        }
        if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(DraftLifecycle::strip);
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }
        JsonObject object = element.getAsJsonObject();
        for (String key : List.copyOf(object.keySet())) {
            JsonElement child = object.get(key);
            if (LATER_FIELDS.contains(key) && unset(child)) {
                object.remove(key);
            } else {
                strip(child);
            }
        }
    }

    private static boolean unset(JsonElement value) {
        return value == null || value.isJsonNull()
                || (value.isJsonArray() && value.getAsJsonArray().isEmpty())
                || (value.isJsonObject() && value.getAsJsonObject().isEmpty())
                || (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                        && value.getAsString().isEmpty());
    }

    /** A deep copy through the JSON tree: what publishing freezes and what a save writes. */
    public static <T> T freeze(T draft, Class<T> type) {
        return Json.gson().fromJson(Json.toTree(draft), type);
    }
}
