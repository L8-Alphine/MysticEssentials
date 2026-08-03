package org.hyzionstudios.mysticessentials.api.ui;

/** Public entry point for MysticEssentials Custom Content UI Framework 2.0. */
public interface CustomUiService {
    CustomUiRegistry registry();
    UiCompiler compiler();
    UiSessionManager sessions();
    UiActionRouter actions();
    UiBindingEngine bindings();
    UiPatchService patches();
}
