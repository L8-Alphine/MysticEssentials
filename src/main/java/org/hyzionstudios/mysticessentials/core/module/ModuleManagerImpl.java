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
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.api.module.ModuleManager;
import org.hyzionstudios.mysticessentials.api.module.MysticModule;
import org.hyzionstudios.mysticessentials.core.MysticCore;

import com.mysticlicensing.license.Products;

/**
 * Default {@link ModuleManager}. Registers module instances, enables those
 * turned on in {@code config.json} in dependency-respecting order, and manages
 * disable/reload. Hard-dependency violations disable the dependent module with a
 * clear log instead of failing the whole server.
 */
public final class ModuleManagerImpl implements ModuleManager {

    private final MysticCore core;
    private final Map<String, MysticModule> modules = new LinkedHashMap<>();
    /** Read from any thread (commands, chat, scheduler); written under this manager's lock. */
    private final Map<String, Boolean> enabled = new ConcurrentHashMap<>();
    private final Set<String> externalModules = new LinkedHashSet<>();
    private boolean startupComplete;

    public ModuleManagerImpl(MysticCore core) {
        this.core = core;
    }

    @Override
    public synchronized void register(MysticModule module) {
        add(module);
    }

    /** @return {@code false} when the id is taken and {@code module} was ignored. */
    private boolean add(MysticModule module) {
        if (modules.containsKey(module.id())) {
            core.log(Level.WARNING, "Duplicate module id '" + module.id() + "' ignored.");
            return false;
        }
        modules.put(module.id(), module);
        enabled.put(module.id(), false);
        return true;
    }

    @Override
    public synchronized void registerExternalModule(MysticModule module) {
        // A rejected duplicate must not be marked external or enabled: the id
        // belongs to the module already registered under it.
        if (!add(module)) {
            return;
        }
        externalModules.add(module.id());
        if (startupComplete && Boolean.FALSE.equals(enabled.get(module.id()))) {
            enableModule(module);
        }
    }

    /** Loads and enables every registered module that is enabled in config. */
    public synchronized void enableAll() {
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
        if (!licensed(module)) {
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
            core.log(Level.SEVERE, "Failed to enable module '" + id + "'", t);
            // Whatever onEnable registered before it threw (commands, listeners,
            // tasks) must not outlive it: the module counts as disabled, so no
            // later disable would clean up, and a reload would register it twice.
            try {
                module.onDisable();
            } catch (Throwable ignored) {
                // Best effort on a half-enabled module.
            }
            if (module instanceof AbstractMysticModule base) {
                base.unregisterCommands();
                base.unregisterEventListeners();
            }
        }
    }

    /**
     * Checks the module's declared license feature, if it has one.
     *
     * <p>A locked module is skipped exactly the way a config-disabled one is:
     * one log line, everything else carries on. Any failure to answer the
     * question counts as unlicensed rather than as a reason to stop enabling
     * modules.
     *
     * @see AbstractMysticModule#licensedFeature()
     */
    private boolean licensed(MysticModule module) {
        if (!(module instanceof AbstractMysticModule base)) {
            return true;
        }
        String feature;
        try {
            feature = base.licensedFeature();
        } catch (Throwable t) {
            core.log(Level.WARNING, "Module '" + module.id() + "' could not report its license feature;"
                    + " treating it as unlicensed.", t);
            return false;
        }
        if (feature == null) {
            return true;
        }
        if (core.license().hasFeature(Products.ESSENTIALS, feature)) {
            return true;
        }
        core.log(Level.INFO, "Module '" + module.id() + "' needs the '" + feature
                + "' license feature, which this server does not have; skipping. "
                + "Run /mystic license for details. Everything else is unaffected.");
        return false;
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
    public synchronized void disableAll() {
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
            core.log(Level.SEVERE, "Error disabling module '" + module.id() + "'", t);
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
    public synchronized void syncFromConfig() {
        List<MysticModule> ordered = orderedByDependencies();

        // Modules turned off in config, plus every running module that hard-depends
        // (directly or not) on one of them: a dependent must not outlive what it needs.
        // A module whose license lapsed (expired, revoked, reloaded as invalid) stops too.
        Set<String> stopping = new LinkedHashSet<>();
        for (MysticModule module : ordered) {
            if (isEnabled(module.id()) && (!moduleEnabledInConfig(module.id()) || !licensed(module))) {
                stopping.add(module.id());
            }
        }
        boolean grew = true;
        while (grew) {
            grew = false;
            for (MysticModule module : ordered) {
                if (isEnabled(module.id()) && !stopping.contains(module.id())
                        && hardDependenciesOf(module).stream().anyMatch(stopping::contains)) {
                    stopping.add(module.id());
                    grew = true;
                }
            }
        }

        // Stop them — dependents before dependencies.
        List<MysticModule> reversed = new ArrayList<>(ordered);
        Collections.reverse(reversed);
        for (MysticModule module : reversed) {
            if (stopping.contains(module.id())) {
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
                    core.log(Level.SEVERE, "Error reloading module '" + id + "'", t);
                }
            } else {
                enableModule(module);
            }
        }
    }

    /**
     * Stops every running module whose license feature is no longer granted (and
     * its hard dependents). Called periodically, so a license that expires while
     * the server runs takes effect without a reload.
     */
    public synchronized void enforceLicenses() {
        List<MysticModule> ordered = orderedByDependencies();
        Set<String> stopping = new LinkedHashSet<>();
        for (MysticModule module : ordered) {
            if (isEnabled(module.id()) && module instanceof AbstractMysticModule base
                    && licensedFeatureOf(base) != null && !licensed(module)) {
                stopping.add(module.id());
            }
        }
        if (stopping.isEmpty()) {
            return;
        }
        boolean grew = true;
        while (grew) {
            grew = false;
            for (MysticModule module : ordered) {
                if (isEnabled(module.id()) && !stopping.contains(module.id())
                        && hardDependenciesOf(module).stream().anyMatch(stopping::contains)) {
                    stopping.add(module.id());
                    grew = true;
                }
            }
        }
        List<MysticModule> reversed = new ArrayList<>(ordered);
        Collections.reverse(reversed);
        for (MysticModule module : reversed) {
            if (stopping.contains(module.id())) {
                core.log(Level.WARNING, "Module '" + module.id()
                        + "' is no longer licensed; stopping it. Run /mystic license for details.");
                disableModule(module);
            }
        }
    }

    private static String licensedFeatureOf(AbstractMysticModule module) {
        try {
            return module.licensedFeature();
        } catch (Throwable t) {
            return "?";
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
            core.log(Level.SEVERE, "Error reloading module '" + moduleId + "'", t);
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
