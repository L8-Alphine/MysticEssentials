package org.hyzionstudios.mysticessentials.modules.customcontent;

import java.lang.reflect.Method;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.modules.customcontent.layout.LayoutBridge;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Reflection-only bridge to QuestLines. Keeping the integration soft lets the
 * Essentials module load and edit files even when QuestLines is absent.
 */
final class CustomContentBridge implements LayoutBridge {

    private final MysticCore core;
    private final CustomContentConfig config;
    private Object plugin;
    private Object api;
    private Object textFormatter;
    private Method meetsRequirements;
    private Method consumeRequirements;
    private Method executeActions;
    private Method substituteVariables;
    private volatile BiConsumer<PlayerRef, String> guiOpener = (player, id) -> {
    };

    CustomContentBridge(MysticCore core, CustomContentConfig config) {
        this.core = core;
        this.config = config;
    }

    boolean connect(BiConsumer<PlayerRef, String> opener) {
        guiOpener = opener == null ? (player, id) -> {
        } : opener;
        try {
            PluginIdentifier id = new PluginIdentifier(config.compatibilityPluginGroup,
                    config.compatibilityPluginName);
            PluginBase found = HytaleServer.get().getPluginManager().getPlugin(id);
            if (found == null) {
                clear();
                return false;
            }
            plugin = found;
            api = found.getClass().getMethod("getApi").invoke(found);
            try {
                textFormatter = found.getClass().getMethod("getTextFormatter").invoke(found);
            } catch (ReflectiveOperationException ignored) {
                textFormatter = null;
            }
            meetsRequirements = method(api, "meetsRequirements", PlayerRef.class, List.class);
            executeActions = method(api, "executeActions", PlayerRef.class, List.class);
            consumeRequirements = optionalMethod(api, "consumeRequirements", PlayerRef.class, List.class);
            substituteVariables = findSubstitutionMethod(textFormatter);
            registerOpenGuiAction();
            return true;
        } catch (Throwable t) {
            clear();
            core.log(Level.WARNING, "[customcontent] Compatibility bridge unavailable: " + t.getMessage());
            return false;
        }
    }

    void disconnect() {
        guiOpener = (player, id) -> {
        };
        clear();
    }

    boolean connected() {
        return plugin != null && api != null;
    }

    @Override
    public boolean meetsRequirements(PlayerRef player, List<String> requirements) {
        if (requirements == null || requirements.isEmpty()) {
            return true;
        }
        if (!connected() || meetsRequirements == null) {
            return true;
        }
        try {
            return Boolean.TRUE.equals(meetsRequirements.invoke(api, player, requirements));
        } catch (ReflectiveOperationException e) {
            core.log(Level.WARNING, "[customcontent] Requirement check failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public void consumeRequirements(PlayerRef player, List<String> requirements) {
        if (!connected() || consumeRequirements == null || requirements == null || requirements.isEmpty()) {
            return;
        }
        try {
            consumeRequirements.invoke(api, player, requirements);
        } catch (ReflectiveOperationException e) {
            core.log(Level.WARNING, "[customcontent] Requirement consumption failed: " + e.getMessage());
        }
    }

    @Override
    public boolean handlesUnknownActions() {
        return connected() && executeActions != null;
    }

    @Override
    public void executeActions(PlayerRef player, List<String> actions) {
        if (!connected() || executeActions == null || actions == null || actions.isEmpty()) {
            return;
        }
        try {
            executeActions.invoke(api, player, actions);
        } catch (ReflectiveOperationException e) {
            core.log(Level.WARNING, "[customcontent] Action execution failed: " + e.getMessage());
        }
    }

    @Override
    public String substitute(PlayerRef player, String text) {
        if (text == null) {
            return "";
        }
        String resolved = text;
        if (connected() && substituteVariables != null && textFormatter != null) {
            try {
                resolved = (String) substituteVariables.invoke(textFormatter, text, player, null);
            } catch (ReflectiveOperationException ignored) {
                resolved = text;
            }
        }
        resolved = resolved.replace("{username}", player.getUsername());
        return core.getPlaceholderService().resolve(player.getUuid(), resolved);
    }

    void reloadQuests() {
        if (plugin == null) {
            return;
        }
        try {
            Method external = optionalMethod(plugin, "reloadQuestsExternal");
            if (external != null) {
                external.invoke(plugin);
                return;
            }
            Object questConfig = plugin.getClass().getMethod("getQuestConfig").invoke(plugin);
            questConfig.getClass().getMethod("load").invoke(questConfig);
        } catch (ReflectiveOperationException e) {
            core.log(Level.WARNING, "[customcontent] Could not reload the compatibility plugin: "
                    + e.getMessage());
        }
    }

    boolean startAssignment(PlayerRef player, String questId) {
        if (plugin == null) {
            return false;
        }
        try {
            Object listener = plugin.getClass().getMethod("getQuestInteractionListener").invoke(plugin);
            listener.getClass().getMethod("startAssignment", PlayerRef.class, String.class)
                    .invoke(listener, player, questId);
            return true;
        } catch (ReflectiveOperationException e) {
            core.log(Level.WARNING, "[customcontent] Could not start NPC assignment: " + e.getMessage());
            return false;
        }
    }

    void unbindAndReload(String questId) {
        if (plugin == null) {
            return;
        }
        try {
            Method method = optionalMethod(plugin, "unbindAndReloadQuest", String.class);
            if (method != null) {
                method.invoke(plugin, questId);
            } else {
                reloadQuests();
            }
        } catch (ReflectiveOperationException e) {
            core.log(Level.WARNING, "[customcontent] Could not unbind deleted dialog: " + e.getMessage());
        }
    }

    private void registerOpenGuiAction() {
        Method register = optionalMethod(api, "registerSimpleAction", String.class, BiConsumer.class);
        if (register == null) {
            return;
        }
        try {
            BiConsumer<PlayerRef, String> action = (player, guiId) -> guiOpener.accept(player, guiId);
            register.invoke(api, "opengui", action);
        } catch (ReflectiveOperationException e) {
            core.log(Level.WARNING, "[customcontent] Could not register opengui action: " + e.getMessage());
        }
    }

    private void clear() {
        plugin = null;
        api = null;
        textFormatter = null;
        meetsRequirements = null;
        consumeRequirements = null;
        executeActions = null;
        substituteVariables = null;
    }

    private static Method method(Object target, String name, Class<?>... types) throws NoSuchMethodException {
        return target.getClass().getMethod(name, types);
    }

    private static Method optionalMethod(Object target, String name, Class<?>... types) {
        if (target == null) {
            return null;
        }
        try {
            return target.getClass().getMethod(name, types);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static Method findSubstitutionMethod(Object formatter) {
        if (formatter == null) {
            return null;
        }
        for (Method method : formatter.getClass().getMethods()) {
            if (method.getName().equals("substituteVariables") && method.getParameterCount() == 3) {
                return method;
            }
        }
        return null;
    }
}
