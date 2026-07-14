package org.hyzionstudios.mysticessentials.modules.chat.itemlink;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.platform.ui.MysticPage;

import com.google.gson.JsonObject;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Read-only Item Details page. Intentionally light: it presents the shared item's
 * icon, name, and share provenance, plus Report/Close. The <b>full</b> tooltip
 * (rarity-coloured name, lore with inline colours, damage data, durability) comes
 * from the native item tooltip the 0.5.6 client renders when the player hovers the
 * {@code #Icon} ItemSlot — a renderer we cannot reproduce in a custom-UI Label
 * (Labels are plain-text only and reject coloured Message trees). So this page
 * defers the rich detail to that hover rather than shipping a lesser imitation.
 */
public final class ItemDetailsPage extends MysticPage {

    static final String UI = "MysticEssentials/ItemDetails.ui";
    static final String STAT_ROW_UI = "MysticEssentials/ItemStatRow.ui";

    private final ItemSnapshot snapshot;
    private final ItemLinkConfig config;

    public ItemDetailsPage(MysticCore core, PlayerRef player, ItemSnapshot snapshot,
            ItemLinkConfig config) {
        super(core, player, CustomPageLifetime.CanDismiss);
        this.snapshot = snapshot;
        this.config = config;
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event,
            Store<EntityStore> store) {
        cmd.append(UI);

        // Config-based slot: ItemId + Quantity. The parent ItemSlotButton shows the
        // full native item tooltip (colours, quality, lore, durability) on hover.
        cmd.set("#Icon.ItemId", snapshot.itemId);
        cmd.set("#Icon.Quantity", Math.max(1, snapshot.quantity));
        // Rarity accent bar — Background is the one property the client colours
        // dynamically, so this conveys the rarity colour the name Label cannot.
        cmd.set("#RarityAccent.Background", safeColor(snapshot.rarityColor));

        // Name: prefer a captured custom name (plain String), else the item's
        // translation Message (a translation Message sets fine on a Label; a raw
        // Message would disconnect the client).
        if (snapshot.customName != null && !snapshot.customName.isBlank()) {
            cmd.set("#ItemName.Text", snapshot.customName);
        } else if (snapshot.translationKey != null) {
            cmd.set("#ItemName.Text", Message.translation(snapshot.translationKey));
        } else {
            cmd.set("#ItemName.Text", snapshot.plainName());
        }

        cmd.set("#ItemSubtitle.Text", subtitle());
        cmd.set("#ItemId.Text", "ID: " + snapshot.itemId);

        buildStats(cmd);

        boolean showShare = config.inspectionUi.showShareInformation;
        cmd.set("#ShareInfo.Visible", showShare);
        if (showShare) {
            cmd.set("#ShareInfo.Text", shareInfo());
        }

        event.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton",
                new EventData().put("action", "close"));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#ReportButton",
                new EventData().put("action", "report"));
    }

    private void buildStats(UICommandBuilder cmd) {
        List<ItemSnapshot.Stat> rows = new ArrayList<>();
        if (config.inspectionUi.showStats) {
            rows.addAll(snapshot.stats);
        }
        if (config.inspectionUi.showDurability && snapshot.hasDurability()) {
            rows.add(new ItemSnapshot.Stat("Durability",
                    round(snapshot.durability) + "/" + round(snapshot.maxDurability)));
        }
        cmd.set("#StatsEmpty.Visible", rows.isEmpty());
        for (int i = 0; i < rows.size(); i++) {
            String sel = "#StatList[" + i + "]";
            cmd.append("#StatList", STAT_ROW_UI);
            cmd.set(sel + " #StatLabel.Text", rows.get(i).label());
            cmd.set(sel + " #StatValue.Text", rows.get(i).value());
        }
    }

    private String subtitle() {
        StringBuilder sb = new StringBuilder(snapshot.rarityName == null ? "" : snapshot.rarityName);
        String type = snapshot.subCategory != null ? snapshot.subCategory : snapshot.category;
        if (type != null && !type.isBlank()) {
            if (sb.length() > 0) {
                sb.append("  •  ");
            }
            sb.append(type);
        }
        if (snapshot.itemLevel > 0) {
            if (sb.length() > 0) {
                sb.append("  •  ");
            }
            sb.append("Item Level ").append(snapshot.itemLevel);
        }
        return sb.length() == 0 ? snapshot.itemId : sb.toString();
    }

    private String shareInfo() {
        StringBuilder sb = new StringBuilder("Shared by ")
                .append(snapshot.sharedByName == null ? "unknown" : snapshot.sharedByName);
        if (snapshot.channelName != null && !snapshot.channelName.isBlank()) {
            sb.append("  •  ").append(snapshot.channelName);
        }
        if (snapshot.worldName != null && !snapshot.worldName.isBlank()) {
            sb.append("  •  ").append(snapshot.worldName);
        }
        sb.append("  •  ").append(relativeTime(snapshot.capturedAtEpochMs));
        return sb.toString();
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
        JsonObject payload = parse(data);
        String action = string(payload, "action");
        if ("report".equals(action)) {
            core.log(Level.INFO, "[chat] item-links: " + player.getUsername()
                    + " reported shared item '" + snapshot.itemId + "' (id=" + snapshot.id
                    + ") shared by " + snapshot.sharedByName);
            core.getMessageService().send(player,
                    "&aThanks — this shared item has been reported to staff.");
        }
        close(ref, store);
    }

    static String relativeTime(long epochMs) {
        long seconds = Math.max(0, Duration.between(
                Instant.ofEpochMilli(epochMs), Instant.now()).getSeconds());
        if (seconds < 60) {
            return seconds + " second" + (seconds == 1 ? "" : "s") + " ago";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + " minute" + (minutes == 1 ? "" : "s") + " ago";
        }
        long hours = minutes / 60;
        return hours + " hour" + (hours == 1 ? "" : "s") + " ago";
    }

    private static String round(double value) {
        return Long.toString(Math.round(value));
    }
}
