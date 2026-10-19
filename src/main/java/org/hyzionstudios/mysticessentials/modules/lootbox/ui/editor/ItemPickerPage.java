package org.hyzionstudios.mysticessentials.modules.lootbox.ui.editor;

import java.util.List;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.modules.lootbox.hytale.InventoryAdapter;
import org.hyzionstudios.mysticessentials.platform.ui.ItemCatalog;
import org.hyzionstudios.mysticessentials.platform.ui.MysticPage;

import com.google.gson.JsonObject;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Chooses an item for an editor: a search over the item registry, category
 * tabs, and "Use held item". The editor arms its session first
 * ({@link EditorSession#awaitReturn}) with what to do with the chosen id, then
 * opens this page with a {@link Caller} that reopens the editor:
 *
 * <pre>{@code
 * session.awaitReturn(id -> session.working().icon = id);
 * reopen(ref, store, ItemPickerPage.create(core, player, session,
 *         new ItemPickerPage.Spec("Choose an Icon", "Icon for " + name, draft.icon, Permissions.X, ""),
 *         (r, s) -> reopen(r, s, new PoolEditorPage(core, player, module, session))));
 * }</pre>
 *
 * <p>Choosing runs the armed action (which marks the session dirty) and calls
 * the caller; Cancel drops it and calls the caller; dismissing the page drops
 * it. Every press checks again that the session is still open and that the
 * player still holds {@link Spec#permission}, and a chosen id is checked
 * against the registry because the event payload comes from the client.</p>
 */
public final class ItemPickerPage extends MysticPage {

    static final String PICKER_UI = "MysticEssentials/LootBoxItemPicker.ui";
    static final String ROW_UI = "MysticEssentials/LootBoxItemPickerRow.ui";

    /** Rows per search; the rest are reached by narrowing the search. */
    public static final int RESULT_LIMIT = 60;

    /**
     * Category tabs. Item categories are {@code Root.Child} ids from the game's
     * creative library ({@code Items.Weapons}, {@code Furniture.Beds}); the
     * builder-tool root {@code Tool} is left out because no reward should be one.
     */
    public static final List<EditorTabs.Tab> CATEGORIES = List.of(
            new EditorTabs.Tab("", "All"),
            new EditorTabs.Tab("Items.Weapons", "Weapons"),
            new EditorTabs.Tab("Items.Armors", "Armor"),
            new EditorTabs.Tab("Items.Tools", "Tools"),
            new EditorTabs.Tab("Items.Foods", "Food"),
            new EditorTabs.Tab("Items.Ingredients", "Materials"),
            new EditorTabs.Tab("Blocks", "Blocks"),
            new EditorTabs.Tab("Furniture", "Furniture"));

    /**
     * What the picker is for.
     *
     * @param title      window title, e.g. "Choose an Icon"
     * @param purpose    what the item will be, e.g. "Icon for Avalon Supply"
     * @param current    the item chosen now, or blank
     * @param permission node re-checked on every press
     * @param category   the category tab to start on; blank for All
     */
    public record Spec(String title, String purpose, String current, String permission, String category) {
    }

    /** Reopens the page that opened the picker. Runs on the player's world thread. */
    @FunctionalInterface
    public interface Caller {
        void reopen(Ref<EntityStore> ref, Store<EntityStore> store);
    }

    private final EditorSession<?> session;
    private final Spec spec;
    private final Caller caller;
    private final String query;
    private final String category;
    private final List<String> results;
    /** Set once this page has handed over to another page, so its dismissal is not a cancel. */
    private boolean handedOver;

    private ItemPickerPage(MysticCore core, PlayerRef player, EditorSession<?> session, Spec spec, Caller caller,
            String query, String category) {
        super(core, player, CustomPageLifetime.CanDismiss);
        this.session = session;
        this.spec = spec;
        this.caller = caller;
        this.query = query == null ? "" : query;
        this.category = EditorTabs.resolve(CATEGORIES, category == null ? "" : category);
        this.results = ItemCatalog.search(this.query, this.category, RESULT_LIMIT);
    }

    /** A picker on {@code spec}'s starting category with no search. Arm the session first. */
    public static ItemPickerPage create(MysticCore core, PlayerRef player, EditorSession<?> session, Spec spec,
            Caller caller) {
        return new ItemPickerPage(core, player, session, spec, caller, "", spec.category());
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event, Store<EntityStore> store) {
        cmd.append(PICKER_UI);
        String current = spec.current() == null ? "" : spec.current();
        cmd.set("#HeaderTitle.TextSpans", uiText("#HeaderTitle.TextSpans", spec.title()));
        cmd.set("#HeaderInfo.TextSpans", uiText("#HeaderInfo.TextSpans", results.size() >= RESULT_LIMIT
                ? "First " + RESULT_LIMIT + " matches" : results.size() + (results.size() == 1 ? " match" : " matches")));
        cmd.set("#PickerTarget.TextSpans", uiText("#PickerTarget.TextSpans", spec.purpose()));
        if (!current.isBlank()) {
            cmd.set("#CurrentIcon.ItemId", current);
        }
        cmd.set("#CurrentItem.TextSpans", uiText("#CurrentItem.TextSpans",
                current.isBlank() ? "Nothing chosen yet" : "Current: " + current));
        cmd.set("#SearchInput.Value", query);

        EditorTabs.render(cmd, event, "#CategoryTabs", CATEGORIES, category, tab -> new EventData()
                .put("action", "category").put("category", tab.id()).append("@query", "#SearchInput.Value"));

        cmd.set("#ListEmpty.Visible", results.isEmpty());
        for (int i = 0; i < results.size(); i++) {
            String itemId = results.get(i);
            String row = "#ResultList[" + i + "]";
            boolean chosen = itemId.equals(current);
            cmd.append("#ResultList", ROW_UI);
            cmd.set(row + " #Icon.ItemId", itemId);
            cmd.set(row + " #Name.TextSpans", uiText(row + " #Name.TextSpans", itemId));
            cmd.set(row + " #Meta.TextSpans", uiText(row + " #Meta.TextSpans",
                    String.join(" · ", ItemCatalog.categories(itemId))));
            if (chosen) {
                cmd.set(row + " #Selected.Visible", true);
                cmd.set(row + " #Status.TextSpans", uiText(row + " #Status.TextSpans", "<#E8C97A>Current"));
            }
            event.addEventBinding(CustomUIEventBindingType.Activating, row,
                    new EventData().put("action", "pick").put("item", itemId));
        }

        event.addEventBinding(CustomUIEventBindingType.ValueChanged, "#SearchInput", new EventData()
                .put("action", "search").put("category", category).append("@query", "#SearchInput.Value"));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#HeldButton",
                new EventData().put("action", "held"));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#CancelButton",
                new EventData().put("action", "cancel"));
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
        if (!allowed(ref, store)) {
            return;
        }
        JsonObject payload = parse(data);
        switch (string(payload, "action")) {
            case "search", "category" -> {
                handedOver = true;
                reopen(ref, store, new ItemPickerPage(core, player, session, spec, caller, field(payload, "query"),
                        string(payload, "category")));
            }
            case "pick" -> choose(ref, store, field(payload, "item"));
            case "held" -> {
                String held = InventoryAdapter.heldItemId(store, ref);
                if (held == null) {
                    core.getMessageService().send(player, "&eHold the item you want in your hand, then press "
                            + "Use Held Item again.");
                    return;
                }
                choose(ref, store, held);
            }
            default -> {
                handedOver = true;
                session.cancelReturn();
                caller.reopen(ref, store);
            }
        }
    }

    @Override
    public void onDismiss(Ref<EntityStore> ref, Store<EntityStore> store) {
        // Opening any page dismisses this one first, so only a dismissal that is
        // not a hand-over (Escape) cancels the choice.
        if (!handedOver) {
            session.cancelReturn();
        }
    }

    private void choose(Ref<EntityStore> ref, Store<EntityStore> store, String itemId) {
        if (!ItemCatalog.exists(itemId)) {
            core.getMessageService().send(player, "&c" + (itemId == null || itemId.isBlank() ? "That"
                    : itemId) + " is not an item on this server.");
            return;
        }
        handedOver = true;
        session.completeReturn(itemId);
        caller.reopen(ref, store);
    }

    /** Re-checked on every press: the session must still be open and the permission still held. */
    private boolean allowed(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (!session.active()) {
            core.getMessageService().send(player, "&eThat editor has closed. Open it again to keep editing.");
            close(ref, store);
            return false;
        }
        if (spec.permission() != null && !player.hasPermission(spec.permission())) {
            core.getMessageService().sendKey(player, "no-permission");
            session.cancelReturn();
            close(ref, store);
            return false;
        }
        return true;
    }
}
