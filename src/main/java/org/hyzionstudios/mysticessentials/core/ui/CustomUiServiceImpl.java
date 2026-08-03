package org.hyzionstudios.mysticessentials.core.ui;

import org.hyzionstudios.mysticessentials.api.ui.CustomUiRegistry;
import org.hyzionstudios.mysticessentials.api.ui.CustomUiService;
import org.hyzionstudios.mysticessentials.api.ui.UiActionRouter;
import org.hyzionstudios.mysticessentials.api.ui.UiBindingEngine;
import org.hyzionstudios.mysticessentials.api.ui.UiCompiler;
import org.hyzionstudios.mysticessentials.api.ui.UiPatchService;
import org.hyzionstudios.mysticessentials.api.ui.UiSessionManager;

/** Process-wide service implementation; rendering remains module-owned. */
public final class CustomUiServiceImpl implements CustomUiService {
    private final CustomUiRegistry registry = new CustomUiRegistry();
    private final UiCompiler compiler;
    private final UiSessionManager sessions = new UiSessionManager();
    private final UiActionRouter actions = new UiActionRouter();
    private final UiBindingEngine bindings = new UiBindingEngine();
    private final UiPatchService patches = new UiPatchService();

    public CustomUiServiceImpl(int maxNodes) { compiler = new UiCompiler(maxNodes, 32); }
    @Override public CustomUiRegistry registry() { return registry; }
    @Override public UiCompiler compiler() { return compiler; }
    @Override public UiSessionManager sessions() { return sessions; }
    @Override public UiActionRouter actions() { return actions; }
    @Override public UiBindingEngine bindings() { return bindings; }
    @Override public UiPatchService patches() { return patches; }
}
