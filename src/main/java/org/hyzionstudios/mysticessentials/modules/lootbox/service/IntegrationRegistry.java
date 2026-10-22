package org.hyzionstudios.mysticessentials.modules.lootbox.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

import org.hyzionstudios.mysticessentials.modules.lootbox.api.ConditionProvider;
import org.hyzionstudios.mysticessentials.modules.lootbox.api.RewardProvider;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxDraft;

/**
 * The reward and condition providers other plugins registered (P3-WP1, the
 * bible's LootBoxIntegrationRegistry). It outlives a module disable, because
 * providers register once when their plugin starts.
 *
 * <p>{@link #missing} is the "is this pool still deliverable" check: a pool that
 * names a provider or condition type nobody provides is invalid. Previews show
 * it, openings refuse it, and nothing ever stands in for the missing provider.
 * Engine-free, so {@code verifyLootBox} runs it.</p>
 */
public final class IntegrationRegistry {

    private static final Pattern REWARD_ID = Pattern.compile("[a-z0-9_.:-]{1,64}");
    private static final Pattern CONDITION_TYPE = Pattern.compile("[A-Z0-9_]{1,48}");

    /** Reward types delivered by a provider rather than by LootBox itself. */
    public static final Set<String> PROVIDER_TYPES = Set.of("EXTENSION", "COSMETIC", "RPG");
    /** Condition types LootBox answers itself; a provider can't take these names. */
    public static final Set<String> BUILT_IN_CONDITIONS = Set.of("PERMISSION", "WORLD", "REGION", "DATE_WINDOW");

    private final Map<String, RewardProvider> rewards = new ConcurrentHashMap<>();
    private final Map<String, ConditionProvider> conditions = new ConcurrentHashMap<>();
    /** Told {@code (what, id)} on every change, e.g. {@code ("REWARD_PROVIDER_REMOVED", "mysticeconomy")}. */
    private final List<BiConsumer<String, String>> listeners = new CopyOnWriteArrayList<>();

    /**
     * Adds or replaces the provider with this id (a plugin that reloads registers
     * again). {@code IllegalArgumentException} for a malformed id or kind.
     */
    public void register(RewardProvider provider) {
        String id = provider.id();
        if (id == null || !REWARD_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("reward provider id must match " + REWARD_ID.pattern() + ": " + id);
        }
        String kind = provider.kind() == null ? "" : provider.kind().toUpperCase(Locale.ROOT);
        if (!PROVIDER_TYPES.contains(kind) && !"ECONOMY".equals(kind)) {
            throw new IllegalArgumentException("reward provider kind must be EXTENSION, COSMETIC, RPG or ECONOMY: "
                    + provider.kind());
        }
        RewardProvider previous = rewards.put(id, provider);
        fire(previous == null ? "REWARD_PROVIDER_ADDED" : "REWARD_PROVIDER_REPLACED", id);
    }

    public void register(ConditionProvider provider) {
        String type = provider.type();
        if (type == null || !CONDITION_TYPE.matcher(type).matches()) {
            throw new IllegalArgumentException("condition type must match " + CONDITION_TYPE.pattern() + ": " + type);
        }
        if (BUILT_IN_CONDITIONS.contains(type)) {
            throw new IllegalArgumentException("condition type " + type + " is built in");
        }
        ConditionProvider previous = conditions.put(type, provider);
        fire(previous == null ? "CONDITION_PROVIDER_ADDED" : "CONDITION_PROVIDER_REPLACED", type);
    }

    /** Removes the provider only if it is still {@code provider}, so a stale unregister can't drop its successor. */
    public boolean unregister(RewardProvider provider) {
        boolean removed = provider != null && rewards.remove(provider.id(), provider);
        if (removed) {
            fire("REWARD_PROVIDER_REMOVED", provider.id());
        }
        return removed;
    }

    public boolean unregister(ConditionProvider provider) {
        boolean removed = provider != null && conditions.remove(provider.type(), provider);
        if (removed) {
            fire("CONDITION_PROVIDER_REMOVED", provider.type());
        }
        return removed;
    }

    public RewardProvider reward(String id) {
        return id == null ? null : rewards.get(id.toLowerCase(Locale.ROOT));
    }

    public ConditionProvider condition(String type) {
        return type == null ? null : conditions.get(type.toUpperCase(Locale.ROOT));
    }

    public Collection<RewardProvider> rewardProviders() {
        return List.copyOf(rewards.values());
    }

    public Collection<ConditionProvider> conditionProviders() {
        return List.copyOf(conditions.values());
    }

    public void onChange(BiConsumer<String, String> listener) {
        listeners.add(listener);
    }

    private void fire(String what, String id) {
        for (BiConsumer<String, String> listener : listeners) {
            try {
                listener.accept(what, id);
            } catch (RuntimeException ignored) {
                // A listener's failure never blocks a registration.
            }
        }
    }

    /**
     * What {@code spec} needs that nobody provides. Empty means every provider is
     * present and the pool can be opened.
     */
    public List<String> missing(LootBoxDraft spec) {
        Set<String> missing = new LinkedHashSet<>();
        if (spec == null) {
            return List.of();
        }
        for (LootBoxDraft.ConditionSpec condition : spec.conditions()) {
            missingCondition(condition, missing);
        }
        for (LootBoxDraft.RewardSpec reward : RewardActions.everyReward(spec)) {
            for (LootBoxDraft.ConditionSpec condition : reward.conditions()) {
                missingCondition(condition, missing);
            }
            for (LootBoxDraft.RewardSpec action : RewardActions.actions(reward)) {
                if (RewardActions.needsProvider(action) && reward(action.provider) == null) {
                    missing.add("reward provider '" + action.provider + "'");
                }
            }
        }
        return new ArrayList<>(missing);
    }

    private void missingCondition(LootBoxDraft.ConditionSpec condition, Set<String> missing) {
        String type = condition == null ? "" : ConditionRules.type(condition);
        if (!BUILT_IN_CONDITIONS.contains(type) && condition(type) == null) {
            missing.add("condition '" + type + "'");
        }
    }
}
