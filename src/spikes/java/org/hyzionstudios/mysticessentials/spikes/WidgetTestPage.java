package org.hyzionstudios.mysticessentials.spikes;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * S3: opens one widget per document, so a widget the 0.7.2 client rejects
 * aborts only its own page. The server also writes a value into each widget
 * after it mounts, proving it can be driven at runtime.
 */
final class WidgetTestPage extends CustomUIPage {

    /** Widget name (as typed in /lbspike ui) to its test document. */
    static final Map<String, String> DOCUMENTS = new LinkedHashMap<>();

    static {
        DOCUMENTS.put("numberfield", "SpikeNumberField.ui");
        DOCUMENTS.put("progressbar", "SpikeProgressBar.ui");
        DOCUMENTS.put("tabs", "SpikeTabs.ui");
        DOCUMENTS.put("tabstext", "SpikeTabsText.ui");
        DOCUMENTS.put("itemgrid", "SpikeItemGrid.ui");
        DOCUMENTS.put("reorderable", "SpikeReorderable.ui");
        DOCUMENTS.put("itempreview", "SpikeItemPreview.ui");
        DOCUMENTS.put("colorpicker", "SpikeColorPicker.ui");
    }

    private final String widget;
    private final SpikeLog log;
    private final ItemStack held;

    WidgetTestPage(PlayerRef player, String widget, SpikeLog log, ItemStack held) {
        super(player, CustomPageLifetime.CanDismiss);
        this.widget = widget;
        this.log = log;
        this.held = held == null || held.isEmpty() ? null : held;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull Store<EntityStore> store) {
        cmd.append("LootBoxSpike/" + DOCUMENTS.get(widget));
        switch (widget) {
            case "numberfield" -> {
                cmd.set("#Num.Value", 7.25);
                events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#Num",
                        new EventData().put("action", "value").append("@value", "#Num.Value"));
            }
            case "progressbar" -> cmd.set("#Bar.Value", 0.75f);
            case "itemgrid" -> {
                if (held != null) {
                    cmd.set("#Grid.ItemStacks", new ItemStack[] {
                        new ItemStack(held.getItemId(), 1),
                        new ItemStack(held.getItemId(), Math.max(1, held.getQuantity())),
                        new ItemStack(held.getItemId(), 1)
                    });
                }
            }
            case "itempreview" -> {
                if (held != null) {
                    cmd.set("#Preview.ItemId", held.getItemId());
                }
            }
            case "colorpicker" -> events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#Color",
                    new EventData().put("action", "value").append("@value", "#Color.Color"));
            default -> {
            }
        }
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton",
                new EventData().put("action", "close"));
        log.line("S3", "opened " + DOCUMENTS.get(widget) + " for " + playerRef.getUsername()
                + (held == null && ("itemgrid".equals(widget) || "itempreview".equals(widget))
                        ? " (hold an item to fill it)" : "")
                + "; watch the client log for Markup Error");
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, String rawData) {
        log.line("S3", widget + " event from client: " + rawData);
        if (rawData != null && rawData.contains("\"close\"")) {
            close();
        }
    }
}
