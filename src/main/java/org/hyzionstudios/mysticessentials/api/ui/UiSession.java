package org.hyzionstudios.mysticessentials.api.ui;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Mutable state for one player's server-authoritative UI instance. */
public final class UiSession {

    private final UUID playerId;
    private final Instant openedAt = Instant.now();
    private final Deque<String> history = new ArrayDeque<>();
    private final Deque<String> dialogs = new ArrayDeque<>();
    private final Set<String> activeHuds = new LinkedHashSet<>();
    private final Map<String, Object> viewModel = new LinkedHashMap<>();
    private final Map<String, Object> localState = new LinkedHashMap<>();
    private final Map<String, Object> lastProperties = new LinkedHashMap<>();
    private String currentPage;
    private String currentRoute;
    private String lastAction;
    private String lastValidationError;

    UiSession(UUID playerId) { this.playerId = playerId; }

    public UUID playerId() { return playerId; }
    public Instant openedAt() { return openedAt; }
    public synchronized String currentPage() { return currentPage; }
    public synchronized String currentRoute() { return currentRoute; }

    public synchronized void navigate(String page, String route) {
        if (page == null || page.isBlank()) throw new IllegalArgumentException("page is required");
        if (currentPage != null && !currentPage.equals(page)) history.push(currentPage);
        currentPage = page;
        currentRoute = route;
    }

    public synchronized String back() {
        if (history.isEmpty()) return currentPage;
        currentPage = history.pop();
        currentRoute = null;
        return currentPage;
    }

    public synchronized void openDialog(String id) { if (id != null && !id.isBlank()) dialogs.push(id); }
    public synchronized String closeDialog() { return dialogs.isEmpty() ? null : dialogs.pop(); }
    public synchronized void showHud(String id) { if (id != null && !id.isBlank()) activeHuds.add(id); }
    public synchronized void hideHud(String id) { activeHuds.remove(id); }
    public synchronized List<String> navigationHistory() { return List.copyOf(history); }
    public synchronized List<String> openDialogs() { return List.copyOf(dialogs); }
    public synchronized Set<String> activeHuds() { return Set.copyOf(activeHuds); }
    public synchronized Map<String, Object> viewModel() { return viewModel; }
    public synchronized Map<String, Object> localState() { return localState; }
    synchronized Map<String, Object> lastProperties() { return lastProperties; }
    public synchronized String lastAction() { return lastAction; }
    public synchronized void lastAction(String value) { lastAction = value; }
    public synchronized String lastValidationError() { return lastValidationError; }
    public synchronized void lastValidationError(String value) { lastValidationError = value; }

    public synchronized List<UiPatchService.Patch> updateProperties(Map<String, ?> properties,
            UiPatchService patches) {
        List<UiPatchService.Patch> result = patches.diff(lastProperties, properties);
        lastProperties.clear();
        properties.forEach(lastProperties::put);
        return new ArrayList<>(result);
    }
}
