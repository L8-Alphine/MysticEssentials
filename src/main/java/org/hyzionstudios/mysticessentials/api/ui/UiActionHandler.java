package org.hyzionstudios.mysticessentials.api.ui;

@FunctionalInterface
public interface UiActionHandler {
    UiActionResult execute(UiActionContext context) throws Exception;
}
