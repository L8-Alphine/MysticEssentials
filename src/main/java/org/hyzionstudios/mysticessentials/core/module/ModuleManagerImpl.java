package org.hyzionstudios.mysticessentials.core.module;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.api.module.ModuleManager;
import org.hyzionstudios.mysticessentials.api.module.MysticModule;
import org.hyzionstudios.mysticessentials.core.MysticCore;

/**
 * Default {@link ModuleManager}. Registers module instances, enables those
 * turned on in {@code config.json} in dependency-respecting order, and manages
 * disable/reload. Hard-dependency violations disable the dependent module with a
 * clear log instead of failing the whole server.
 */
public final class ModuleManagerImpl implements ModuleManager {

    private final MysticCore core;
    private final Map<String, MysticModule> modules = new LinkedHashMap<>();
    private final Map<String, Boolean> enabled = new LinkedHashMap<>();
    private final Set<String> externalModules = new LinkedHashSet<>();
    private boolean startupComplete;

    public ModuleManagerImpl(MysticCore core) {
        this.core = core;
    }

    @Override
    public void register(MysticModule module) {
        if (modules.containsKey(module.id())) {
            core.log(Level.WARNING, "Duplicate module id '" + module.id() + "' ignored.");
            return;
        }
        modules.put(module.id(), module);
        enabled.put(module.id(), false);
    }

    @Override
    public void registerExternalModule(MysticModule module) {
        register(module);
        externalModules.add(module.id());
        if (startupComplete && Boolean.FALSE.equals(enabled.get(module.id()))) {
            enableModule(module);
        }
    }

    /** Loads and enables every registered module that is enabled in config. */
    public void enableAll() {
        for (MysticModule module : orderedByDependencies()) {
            enableModule(module);
        }
        startupComplete = true;
    }

    private void enableModule(MysticModule module) {
        String id = module.id();
        if (isEnabled(id)) {
            return;
        }
        if (!moduleEnabledInConfig(id)) {
            core.log(Level.INFO, "Module '" + id + "' disabled in config; skipping.");
            return;
        }
        if (!hardDependenciesSatisfied(module)) {
            return;
        }
        try {
            module.onLoad(core);
            module.onEnable();
            enabled.put(id, true);
            core.log(Level.INFO, "Enabled module '" + id + "' v" + module.version());
        } catch (Throwable t) {
            core.log(Level.SEVERE, "Failed to enable module '" + id + "': " + t);
        }
    }

    private boolean moduleEnabledInConfig(String id) {
        if (!externalModules.contains(id)) {
            return core.config().isModuleEnabled(id);
        }
        return core.config().modules == null || core.config().modules.getOrDefault(id, true);
    }

    private boolean hardDependenciesSatisfied(MysticModule module) {
        for (String dependency : hardDependenciesOf(module)) {
            if (!Boolean.TRUE.equals(enabled.get(dependency))) {
                core.log(Level.WARNING, "Module '" + module.id() + "' requires '" + dependency
                        + "' which is not enabled; skipping '" + module.id() + "'.");
                return false;
            }
        }
        return true;
    }

    /** Topological-ish ordering: dependencies before dependents (stable on cycles). */
    private List<MysticModule> orderedByDependencies() {
        List<MysticModule> ordered = new ArrayList<>();
        List<MysticModule> remaining = new ArrayList<>(modules.values());
        int guard = remaining.size() * remaining.size() + 1;
        while (!remaining.isEmpty() && guard-- > 0) {
            MysticModule next = remaining.stream()
                    .filter(m -> ordered.stream().map(MysticModule::id).toList()
                            .containsAll(hardDependenciesOf(m)))
                    .findFirst()
                    .orElse(remaining.get(0));
            ordered.add(next);
            remaining.remove(next);
        }
        ordered.addAll(remaining);
        return ordered;
    }

    private List<String> hardDependenciesOf(MysticModule module) {
        try {
            List<String> dependencies = module.hardDependencies();
            return dependencies == null ? List.of() : dependencies;
        } catch (Throwable t) {
            return List.of();
        }
    }

    /** Disables all enabled modules in reverse enable order. */
    public void disableAll() {
        List<MysticModule> ordered = orderedByDependencies();
        Collections.reverse(ordered);
        for (MysticModule module : ordered) {
            if (Boolean.TRUE.equals(enabled.get(module.id()))) {
                disableModule(module);
            }
        }
    }

    /** Disables one module: {@code onDisable}, then drops its registered commands. */
    private void disableModule(MysticModule module) {
        try {
            module.onDisable();
        } catch (Throwable t) {
            core.log(Level.SEVERE, "Error disabling module '" + module.id() + "': " + t);
        }
        if (module instanceof AbstractMysticModule base) {
            base.unregisterCommands();
            base.unregisterEventListeners();
        }
        enabled.put(module.id(), false);
        core.log(Level.INFO, "Disabled module '" + module.id() + "'.");
    }

    /**
     * Reconciles running modules with the current {@code config.json} module
     * flags — the hot enable/disable path used by {@code /mysticessentials reload}.
     * Modules newly enabled in config are started (in dependency order), modules
     * newly disabled are stopped (reverse order, dependents first), and modules
     * that stay enabled are reloaded. This is what lets an operator toggle a
     * module and reload without restarting the server.
     */
    public void syncFromConfig() {
        List<MysticModule> ordered = orderedByDependencies();

        // Stop modules turned off in config — dependents before dependencies.
        List<MysticModule> reversed = new ArrayList<>(ordered);
        Collections.reverse(reversed);
        for (MysticModule module : reversed) {
            if (isEnabled(module.id()) && !moduleEnabledInConfig(module.id())) {
                disableModule(module);
            }
        }

        // Start newly-enabled modules and reload the rest — dependencies first.
        for (MysticModule module : ordered) {
            String id = module.id();
            if (!moduleEnabledInConfig(id)) {
                continue;
            }
            if (isEnabled(id)) {
                try {
                    module.onReload();
                } catch (Throwable t) {
                    core.log(Level.SEVERE, "Error reloading module '" + id + "': " + t);
                }
            } else {
                enableModule(module);
            }
        }
    }

    @Override
    public boolean isEnabled(String moduleId) {
        return Boolean.TRUE.equals(enabled.get(moduleId));
    }

    @Override
    public boolean isRegistered(String moduleId) {
        return modules.containsKey(moduleId);
    }

    @Override
    public Optional<MysticModule> getModule(String moduleId) {
        return Optional.ofNullable(modules.get(moduleId));
    }

    @Override
    public Collection<MysticModule> getModules() {
        return Collections.unmodifiableCollection(modules.values());
    }

    @Override
    public boolean reload(String moduleId) {
        MysticModule module = modules.get(moduleId);
        if (module == null || !isEnabled(moduleId)) {
            return false;
        }
        try {
            module.onReload();
            return true;
        } catch (Throwable t) {
            core.log(Level.SEVERE, "Error reloading module '" + moduleId + "': " + t);
            return false;
        }
    }

    @Override
    public void reloadAll() {
        for (String id : modules.keySet()) {
            reload(id);
        }
    }
}
