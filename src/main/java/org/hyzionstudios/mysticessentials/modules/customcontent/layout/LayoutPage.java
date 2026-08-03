package org.hyzionstudios.mysticessentials.modules.customcontent.layout;

import static org.hyzionstudios.mysticessentials.platform.ui.MysticPage.uiText;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.platform.ui.MysticPage;

import com.google.gson.JsonObject;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/** A window rendered from a {@link UiDocument}. */
public final class LayoutPage extends MysticPage {

    /** Ids declared by the shell documents; mod-prefixed to avoid collisions. */
    private static final String SHELL = "#MysticLayoutShell";
    private static final String TITLE = "#MysticLayoutTitle";
    private static final String ROOT = "#MysticLayoutRoot";

    private final LayoutRuntime runtime;
    private final UiDocument document;
    private RenderedLayout layout;

    /**
     * Opens {@code document} for {@code player}, swapping it in place when this
     * is handling a page event. Going through the scheduler instead would answer
     * the client a tick late, which the client shows as a page that never loads.
     */
    static void openPage(MysticCore core, PlayerRef player, LayoutRuntime runtime, UiDocument document,
            Ref<EntityStore> ref, Store<EntityStore> store) {
        LayoutPage page = new LayoutPage(core, player, runtime, document);
        if (ref != null && store != null) {
            reopen(ref, store, page);
        } else {
            core.platform().openPage(player, page);
        }
    }

    /** Closes whatever page the event belongs to. */
    static void closeSurface(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (ref != null && store != null) {
            close(ref, store);
        }
    }

    LayoutPage(MysticCore core, PlayerRef player, LayoutRuntime runtime, UiDocument document) {
        super(core, player, document.lifetime);
        this.runtime = runtime;
        this.document = document;
    }

    public UiDocument document() {
        return document;
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event,
            Store<EntityStore> store) {
        if (!UiAssets.exists(document.frame.template)) {
            // Appending a document the jar does not contain disconnects the
            // player, so an empty page is the safe failure here.
            runtime.reportBrokenSurface(player, document, "shell " + document.frame.template
                    + " is missing from the mod jar");
            return;
        }
        cmd.append(document.frame.template);
        cmd.setObject(SHELL + ".Anchor",
                LayoutRenderer.anchor(null, null, null, null, document.width, document.height));
        if (document.frame != UiDocument.Frame.PLAIN) {
            cmd.set(TITLE + ".TextSpans",
                    uiText(TITLE + ".TextSpans", runtime.bridge().substitute(player, document.title)));
        }
        layout = LayoutRenderer.render(document, player, runtime.bridge(), runtime.portraits(), cmd, event, ROOT);
        if (runtime.portraits() != null) runtime.portraits().flush(player.getPacketHandler());
        runtime.reportProblems(document, layout);
        runtime.trackPage(this);
    }

    /** Re-sends label values and gated visibility without rebuilding markup. */
    void refresh() {
        if (layout == null || !layout.refreshable()) {
            return;
        }
        UICommandBuilder cmd = new UICommandBuilder();
        LayoutRenderer.refreshValues(layout, player, runtime.bridge(), cmd);
        sendUpdate(cmd);
    }

    boolean refreshable() {
        return layout != null && layout.refreshable();
    }

    void refreshPortrait(String username) {
        if (layout == null || runtime.portraits() == null) return;
        UICommandBuilder cmd = new UICommandBuilder();
        LayoutRenderer.refreshPortraits(layout, player, runtime.portraits(), username, cmd);
        runtime.portraits().flush(player.getPacketHandler())
                .thenRun(() -> runtime.runOnViewerThread(player, () -> {
                    if (runtime.open(this)) sendUpdate(cmd);
                }));
    }

    PlayerRef viewer() {
        return player;
    }

    @Override
    public void onDismiss(Ref<EntityStore> ref, Store<EntityStore> store) {
        runtime.untrackPage(this);
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
        JsonObject payload = parse(data);
        String action = string(payload, "action");
        UiNode node = layout == null ? null : layout.node(string(payload, "node"));
        if (node == null) {
            return;
        }

        Map<String, String> inputs = readInputs(payload);
        LayoutBridge bridge = runtime.bridge();
        boolean close = value(node.close, document.defaultClose);
        boolean refresh = value(node.refresh, document.defaultRefresh);
        List<String> actions;

        if ("select".equals(action)) {
            String selected = field(payload, "value");
            UiNode.Option option = node.options.stream()
                    .filter(candidate -> candidate.value().equals(selected))
                    .findFirst().orElse(null);
            if (option == null || !bridge.meetsRequirements(player, option.requirements())) {
                return;
            }
            bridge.consumeRequirements(player, option.requirements());
            actions = option.actions();
            close = value(option.close(), close);
            refresh = value(option.refresh(), refresh);
        } else if ("activate".equals(action)) {
            if (!bridge.meetsRequirements(player, node.requirements)
                    || !bridge.meetsRequirements(player, node.clickRequirements)) {
                return;
            }
            boolean toggledOff = node.kind == UiNode.Kind.TOGGLE
                    && LayoutRenderer.toggleActive(node, player, bridge);
            actions = toggledOff ? node.alternateActions : node.actions;
            if (!toggledOff) {
                bridge.consumeRequirements(player, node.requirements);
            }
            bridge.consumeRequirements(player, node.clickRequirements);
        } else {
            return;
        }

        LayoutActions.Outcome outcome =
                runtime.actions().run(player, substitute(actions, inputs), ref, store);
        if (outcome != LayoutActions.Outcome.CONTINUE) {
            // An action already opened or closed a surface; reopening this one
            // on top of it would fight whatever the player just navigated to.
            runtime.untrackPage(this);
            return;
        }

        if (close) {
            runtime.untrackPage(this);
            close(ref, store);
        } else if (refresh) {
            // A rebuild is needed because actions can change what the document
            // renders (toggle states, requirement-gated sections).
            runtime.untrackPage(this);
            reopen(ref, store, new LayoutPage(core, player, runtime, document));
        } else {
            refresh();
        }
    }

    /** @return input name to current value, from the {@code @in_*} event keys. */
    private static Map<String, String> readInputs(JsonObject payload) {
        Map<String, String> inputs = new LinkedHashMap<>();
        for (String key : payload.keySet()) {
            String name = key.startsWith("@in_") ? key.substring(4)
                    : key.startsWith("in_") ? key.substring(3) : null;
            if (name != null && payload.get(key).isJsonPrimitive()) {
                inputs.put(name, sanitize(payload.get(key).getAsString()));
            }
        }
        return inputs;
    }

    /**
     * Strips control characters from a player-typed value before it can be
     * interpolated into an action. Author documents decide where inputs land,
     * and a console action is one of the places they can land.
     */
    private static String sanitize(String value) {
        return value.replaceAll("[\\p{Cntrl}]", "").trim();
    }

    /** Replaces {@code {name}} in actions with the matching input's value. */
    private static List<String> substitute(List<String> actions, Map<String, String> inputs) {
        if (actions.isEmpty() || inputs.isEmpty()) {
            return actions;
        }
        List<String> resolved = new ArrayList<>(actions.size());
        for (String action : actions) {
            String current = action;
            for (Map.Entry<String, String> input : inputs.entrySet()) {
                current = current.replace("{" + input.getKey() + "}", input.getValue());
            }
            resolved.add(current);
        }
        return resolved;
    }

    private static boolean value(Boolean value, boolean fallback) {
        return value == null ? fallback : value;
    }
}
