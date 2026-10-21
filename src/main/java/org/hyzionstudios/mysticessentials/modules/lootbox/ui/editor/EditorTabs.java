package org.hyzionstudios.mysticessentials.modules.lootbox.ui.editor;

import static org.hyzionstudios.mysticessentials.platform.ui.MysticPage.uiText;

import java.util.List;
import java.util.function.Function;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;

/**
 * The editors' tab bar: one {@code LootBoxEditorTab.ui} button per tab,
 * appended into a {@code LayoutMode: Left} group on the page, with the theme's
 * {@code #Selected} overlay on the active one. A tab switch is an ordinary
 * event, so bind it through {@link FormFields#event} and it carries the
 * form's unsaved input like every other press.
 */
public final class EditorTabs {

    public static final String TAB_UI = "MysticEssentials/LootBoxEditorTab.ui";

    /** The active tab's label colour: the theme's soft gold for "this one". */
    private static final String SELECTED_MARKUP = "<#E8C97A>";

    /** One tab: the id the page switches on, and its label. */
    public record Tab(String id, String label) {
    }

    private EditorTabs() {
    }

    /** The tab to show: {@code requested} when it is one of {@code tabs}, else the first. Engine-free. */
    public static String resolve(List<Tab> tabs, String requested) {
        for (Tab tab : tabs) {
            if (tab.id().equals(requested)) {
                return requested;
            }
        }
        return tabs.isEmpty() ? null : tabs.get(0).id();
    }

    /**
     * Appends one tab per entry into {@code container}, marks the
     * {@linkplain #resolve resolved} selection, and binds each tab to the
     * event {@code press} builds for it.
     */
    public static void render(UICommandBuilder cmd, UIEventBuilder event, String container, List<Tab> tabs,
            String selected, Function<Tab, EventData> press) {
        String current = resolve(tabs, selected);
        for (int i = 0; i < tabs.size(); i++) {
            Tab tab = tabs.get(i);
            String row = container + "[" + i + "]";
            boolean active = tab.id().equals(current);
            cmd.append(container, TAB_UI);
            cmd.set(row + " #Selected.Visible", active);
            cmd.set(row + " #TabLabel.TextSpans", uiText(row + " #TabLabel.TextSpans",
                    active ? SELECTED_MARKUP + tab.label() : tab.label()));
            event.addEventBinding(CustomUIEventBindingType.Activating, row, press.apply(tab));
        }
    }
}
