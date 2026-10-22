package org.hyzionstudios.mysticessentials.modules.lootbox.integration;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.IntSupplier;

import org.hyzionstudios.mysticessentials.modules.lootbox.api.ConditionProvider;

/**
 * MysticQuests (P3-WP7), bound by reflection to
 * {@code org.hyzionstudios.mysticquests.api.MysticQuestsApi}:
 *
 * <ul>
 *   <li>Condition {@code QUEST_COMPLETED} ({@code "params": {"quest": "main:intro"}}):
 *       passes when the player completed the quest. Asked through
 *       {@code resolveText(player, package, "%quest.<id>%")}, which MysticQuests
 *       answers {@code active}, {@code completed}, {@code abandoned} or
 *       {@code inactive} from its player cache. Fails closed
 *       ({@code UNAVAILABLE}) while MysticQuests is not running.</li>
 *   <li>Quest event {@code mysticessentials:lootbox_key}
 *       ({@code {"type": "mysticessentials:lootbox_key", "key": "mystic:gold",
 *       "amount": "1", "virtual": "true"}}): grants keys with
 *       {@code GrantSource.QUEST}. Registered once MysticQuests is up, again
 *       if it restarts.</li>
 * </ul>
 */
final class MysticQuestsBridge {

    static final String CONDITION = "QUEST_COMPLETED";
    static final String EVENT = "mysticessentials:lootbox_key";

    /** Grants the parsed keys; LootBox's {@code grantKey} with {@code GrantSource.QUEST}. */
    interface KeyGranter {
        void grant(UUID player, IntegrationRules.QuestKeyGrant grant, String questPackage);
    }

    private record Hooks(Method isAvailable, Method get, Method resolveText, Method registry, Method registerEvent,
            Method unregisterEvent, Class<?> handlerType, Method contextPlayer, Method contextPackage,
            Method contextText) {
    }

    private final Hooks hooks;
    private final KeyGranter granter;
    private final IntSupplier maxCount;
    private final BiConsumer<String, Throwable> log;
    /** The MysticQuests instance our event is registered with, or {@code null}. */
    private Object registeredWith;

    private MysticQuestsBridge(Hooks hooks, KeyGranter granter, IntSupplier maxCount, BiConsumer<String, Throwable> log) {
        this.hooks = hooks;
        this.granter = granter;
        this.maxCount = maxCount;
        this.log = log;
    }

    /** The bridge, or {@code null} when MysticQuests' API classes are not present. */
    static MysticQuestsBridge detect(KeyGranter granter, IntSupplier maxCount, BiConsumer<String, Throwable> log) {
        try {
            Class<?> api = Reflect.require("org.hyzionstudios.mysticquests.api.MysticQuestsApi");
            Class<?> handler = Reflect.require("org.hyzionstudios.mysticquests.api.QuestEventHandler");
            Class<?> context = Reflect.require("org.hyzionstudios.mysticquests.api.QuestActionContext");
            Method registry = Reflect.method(api, "registry");
            Hooks hooks = new Hooks(Reflect.method(api, "isAvailable"), Reflect.method(api, "get"),
                    Reflect.method(api, "resolveText", UUID.class, String.class, String.class), registry,
                    Reflect.method(registry.getReturnType(), "registerEvent", String.class, handler),
                    Reflect.method(registry.getReturnType(), "unregisterEvent", String.class), handler,
                    Reflect.method(context, "playerId"), Reflect.method(context, "packageId"),
                    Reflect.method(context, "text", String.class, String.class));
            return new MysticQuestsBridge(hooks, granter, maxCount, log);
        } catch (Throwable t) {
            return null;
        }
    }

    ConditionProvider condition() {
        return new QuestCompleted();
    }

    /**
     * Registers the quest event with the running MysticQuests, once per
     * instance. Cheap when already done; called on enable and by the periodic
     * check, because MysticQuests may start after this plugin.
     */
    synchronized void attach() {
        Object api = api();
        if (api == null || api == registeredWith) {
            if (api == null) {
                registeredWith = null;
            }
            return;
        }
        try {
            Object handler = Proxy.newProxyInstance(hooks.handlerType().getClassLoader(),
                    new Class<?>[] {hooks.handlerType()}, eventHandler());
            Object registry = Reflect.call(hooks.registry(), api);
            if (Boolean.TRUE.equals(Reflect.call(hooks.registerEvent(), registry, EVENT, handler))) {
                registeredWith = api;
                log.accept("MysticQuests quest event " + EVENT + " registered", null);
            } else {
                // Taken (by an earlier copy of ours after a reload, or by someone else): leave it.
                registeredWith = api;
                log.accept("MysticQuests refused the quest event " + EVENT + " (name taken); quest key grants "
                        + "use whichever handler holds it", null);
            }
        } catch (RuntimeException e) {
            log.accept("could not register the MysticQuests quest event " + EVENT, e);
        }
    }

    synchronized void detach() {
        Object api = api();
        if (api != null && api == registeredWith) {
            try {
                Reflect.call(hooks.unregisterEvent(), Reflect.call(hooks.registry(), api), EVENT);
            } catch (RuntimeException ignored) {
                // MysticQuests is shutting down too.
            }
        }
        registeredWith = null;
    }

    private Object api() {
        try {
            return Boolean.TRUE.equals(Reflect.call(hooks.isAvailable(), null)) ? Reflect.call(hooks.get(), null)
                    : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private InvocationHandler eventHandler() {
        return (proxy, method, args) -> switch (method.getName()) {
            case "execute" -> {
                onEvent(args == null || args.length == 0 ? null : args[0]);
                yield null;
            }
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "MysticEssentials LootBox quest event " + EVENT;
            default -> throw new UnsupportedOperationException(method.getName());
        };
    }

    /** Runs on MysticQuests' thread (usually the world thread): parse, then hand the grant off. */
    private void onEvent(Object context) {
        if (context == null) {
            return;
        }
        Object player = Reflect.call(hooks.contextPlayer(), context);
        Object questPackage = Reflect.call(hooks.contextPackage(), context);
        Object parsed = IntegrationRules.parseQuestKeyGrant(
                field -> (String) Reflect.call(hooks.contextText(), context, field, ""), maxCount.getAsInt());
        if (!(player instanceof UUID uuid)) {
            return;
        }
        if (parsed instanceof IntegrationRules.QuestKeyGrant grant) {
            granter.grant(uuid, grant, questPackage == null ? "?" : questPackage.toString());
        } else {
            log.accept("quest event " + EVENT + " in package " + questPackage + " is invalid: " + parsed, null);
        }
    }

    private final class QuestCompleted implements ConditionProvider {

        @Override
        public String type() {
            return CONDITION;
        }

        @Override
        public List<String> validate(Map<String, String> params) {
            List<String> problems = new ArrayList<>();
            String quest = params.get("quest");
            if (quest == null || !IntegrationRules.QUEST_ID.matcher(quest.trim()).matches()) {
                problems.add("quest must be a MysticQuests id such as main:intro (no '.')");
            }
            return problems;
        }

        @Override
        public String describe(Map<String, String> params) {
            return "Completed the quest \"" + params.getOrDefault("quest", "?").trim() + "\"";
        }

        @Override
        public Decision test(UUID player, Map<String, String> params) {
            String quest = params.getOrDefault("quest", "").trim();
            if (!IntegrationRules.QUEST_ID.matcher(quest).matches()) {
                return Decision.UNAVAILABLE;
            }
            Object api = api();
            if (api == null) {
                return Decision.UNAVAILABLE;
            }
            try {
                Object state = Reflect.call(hooks.resolveText(), api, player, IntegrationRules.questPackage(quest),
                        IntegrationRules.questPlaceholder(quest));
                return "completed".equals(state) ? Decision.PASS : Decision.FAIL;
            } catch (RuntimeException e) {
                return Decision.UNAVAILABLE;
            }
        }
    }
}
