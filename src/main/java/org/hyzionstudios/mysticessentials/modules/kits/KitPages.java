package org.hyzionstudios.mysticessentials.modules.kits;

import static org.hyzionstudios.mysticessentials.platform.ui.MysticPage.uiText;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.platform.ui.HyzionIcons;
import org.hyzionstudios.mysticessentials.platform.ui.ItemCatalog;
import org.hyzionstudios.mysticessentials.platform.ui.MysticPage;
import org.hyzionstudios.mysticessentials.platform.ui.VanillaIcons;

import com.google.gson.JsonObject;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

final class KitPages {

    static final String KITS_UI = "MysticEssentials/Kits.ui";
    static final String KIT_ROW_UI = "MysticEssentials/KitRow.ui";
    static final String KIT_PREVIEW_UI = "MysticEssentials/KitPreview.ui";
    static final String KIT_PREVIEW_ROW_UI = "MysticEssentials/KitPreviewRow.ui";

    private KitPages() {
    }

    static final class KitListPage extends MysticPage {
        /** How long each item shows before the row moves to the next. */
        static final long CYCLE_MS = 1500;

        private final KitModule kits;
        private final String selectedKit;
        /** Rows whose picture cycles: selector to the kit's item ids. */
        private final java.util.Map<String, List<String>> cycling = new java.util.LinkedHashMap<>();
        private int frame;
        private java.util.concurrent.ScheduledFuture<?> cycleTask;

        KitListPage(MysticCore core, KitModule kits, PlayerRef player, String selectedKit) {
            super(core, player, CustomPageLifetime.CanDismiss);
            this.kits = kits;
            this.selectedKit = selectedKit == null ? null : KitModule.normalize(selectedKit);
        }

        @Override
        public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event,
                Store<EntityStore> store) {
            cmd.append(KITS_UI);
            Map<String, KitConfig.Kit> visible = kits.visibleKits(player);
            List<Map.Entry<String, KitConfig.Kit>> entries = new ArrayList<>(visible.entrySet());
            int totalKits = entries.size();
            cmd.set("#KitEmpty.Visible", entries.isEmpty());
            Map.Entry<String, KitConfig.Kit> selected = selected(entries);

            entries = capRows(entries);
            cmd.set("#KitCount.TextSpans",
                    uiText("#KitCount.TextSpans", rowCount(entries.size(), totalKits, "kits")));
            for (int i = 0; i < entries.size(); i++) {
                Map.Entry<String, KitConfig.Kit> entry = entries.get(i);
                String row = "#KitList[" + i + "]";
                String id = entry.getKey();
                KitConfig.Kit kit = entry.getValue();
                cmd.append("#KitList", KIT_ROW_UI);
                cmd.set(row + " #Name.TextSpans", uiText(row + " #Name.TextSpans", KitModule.displayName(id, kit)));
                cmd.set(row + " #Meta.TextSpans", uiText(row + " #Meta.TextSpans", kitSummary(id, kit)));
                String status = kits.statusText(player, id, kit);
                cmd.set(row + " #Status.TextSpans", uiText(row + " #Status.TextSpans", statusColor(status) + status));
                picture(cmd, row, id, kit);
                cmd.set(row + " #Selected.Visible", entry == selected);
                // Not locking: the page updates itself while items cycle, and an
                // event that lands before the client acknowledges an update is
                // dropped by the engine, which would leave a locked page stuck.
                event.addEventBinding(CustomUIEventBindingType.Activating, row,
                        new EventData().put("action", "select").put("kit", id), false);
            }
            startCycling(store);

            applyDetails(cmd, selected);
            if (selected != null) {
                String id = selected.getKey();
                event.addEventBinding(CustomUIEventBindingType.Activating, "#ClaimButton",
                        new EventData().put("action", "claim").put("kit", id), false);
                event.addEventBinding(CustomUIEventBindingType.Activating, "#PreviewButton",
                        new EventData().put("action", "preview").put("kit", id), false);
            }
        }

        /**
         * The row's picture: a padlock for a kit the player cannot claim, the
         * configured icon or item, else the kit's own items in turn.
         */
        private void picture(UICommandBuilder cmd, String row, String id, KitConfig.Kit kit) {
            String configured = kit.icon == null ? "" : kit.icon.trim();
            List<String> items = new ArrayList<>();
            if (!kits.canAccess(player, id, kit)) {
                setIcon(cmd, row + " #RowIcon", HyzionIcons.LOCK);
                cmd.set(row + " #RowItem.Visible", false);
                return;
            }
            if (configured.regionMatches(true, 0, "item:", 0, 5)) {
                items.add(configured.substring(5).trim());
            } else if (!configured.isEmpty()) {
                String path = VanillaIcons.resolve(configured);
                if (!path.isEmpty()) {
                    setIcon(cmd, row + " #RowIcon", path);
                    cmd.set(row + " #RowItem.Visible", false);
                    return;
                }
            } else if (kit.items != null) {
                for (KitConfig.KitItem item : kit.items) {
                    if (item != null && item.itemId != null) {
                        items.add(item.itemId);
                    }
                }
            }
            // An item id the client does not know must never reach an ItemSlot.
            items.removeIf(itemId -> !ItemCatalog.exists(itemId));
            if (items.isEmpty()) {
                setIcon(cmd, row + " #RowIcon", HyzionIcons.ARCHIVE);
                cmd.set(row + " #RowItem.Visible", false);
                return;
            }
            cmd.set(row + " #RowIcon.Visible", false);
            cmd.set(row + " #RowItem.Visible", true);
            cmd.set(row + " #RowItem.ItemId", items.get(0));
            if (items.size() > 1) {
                cycling.put(row, items);
            }
        }

        /**
         * Advances every cycling row while this page is the one on screen.
         * The timer only hops onto the world thread; it stops itself when the
         * page is closed or replaced.
         */
        private void startCycling(Store<EntityStore> store) {
            if (cycling.isEmpty() || cycleTask != null) {
                return;
            }
            var world = store.getExternalData().getWorld();
            cycleTask = core.scheduler().runRepeating(() -> world.execute(this::advance),
                    CYCLE_MS, CYCLE_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        }

        private void advance() {
            Ref<EntityStore> ref = player.getReference();
            com.hypixel.hytale.server.core.entity.entities.Player entity = ref == null ? null
                    : ref.getStore().getComponent(ref, com.hypixel.hytale.server.core.entity.entities.Player.getComponentType());
            if (entity == null || entity.getPageManager().getCustomPage() != this) {
                stopCycling();
                return;
            }
            frame++;
            UICommandBuilder cmd = new UICommandBuilder();
            for (java.util.Map.Entry<String, List<String>> row : cycling.entrySet()) {
                List<String> items = row.getValue();
                cmd.set(row.getKey() + " #RowItem.ItemId", items.get(frame % items.size()));
            }
            sendUpdate(cmd);
        }

        private void stopCycling() {
            if (cycleTask != null) {
                cycleTask.cancel(false);
                cycleTask = null;
            }
        }

        @Override
        protected void onClosed(Ref<EntityStore> ref, Store<EntityStore> store) {
            stopCycling();
            super.onClosed(ref, store);
        }

        @Override
        protected void onEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
            JsonObject payload = parse(data);
            String kit = field(payload, "kit");
            switch (string(payload, "action")) {
                case "select" -> reopen(ref, store, new KitListPage(core, kits, player, kit));
                case "preview" -> reopen(ref, store, new KitPreviewPage(core, kits, player, kit));
                case "claim" -> {
                    kits.claimFromUi(player, kit);
                    reopen(ref, store, new KitListPage(core, kits, player, kit));
                }
                default -> {
                }
            }
        }

        private Map.Entry<String, KitConfig.Kit> selected(List<Map.Entry<String, KitConfig.Kit>> entries) {
            if (entries.isEmpty()) {
                return null;
            }
            if (selectedKit != null) {
                for (Map.Entry<String, KitConfig.Kit> entry : entries) {
                    if (entry.getKey().equalsIgnoreCase(selectedKit)) {
                        return entry;
                    }
                }
            }
            return entries.get(0);
        }

        private void applyDetails(UICommandBuilder cmd, Map.Entry<String, KitConfig.Kit> selected) {
            if (selected == null) {
                cmd.set("#KitName.TextSpans", uiText("#KitName.TextSpans", "No Kits"));
                cmd.set("#KitDescription.TextSpans", uiText("#KitDescription.TextSpans", "No kits are configured or visible to you."));
                cmd.set("#KitStatus.TextSpans", uiText("#KitStatus.TextSpans", "-"));
                cmd.set("#KitCooldown.TextSpans", uiText("#KitCooldown.TextSpans", "-"));
                cmd.set("#KitPlaytime.TextSpans", uiText("#KitPlaytime.TextSpans", "-"));
                cmd.set("#KitCost.TextSpans", uiText("#KitCost.TextSpans", "-"));
                cmd.set("#KitItems.TextSpans", uiText("#KitItems.TextSpans", "-"));
                return;
            }
            String id = selected.getKey();
            KitConfig.Kit kit = selected.getValue();
            cmd.set("#KitName.TextSpans", uiText("#KitName.TextSpans", KitModule.displayName(id, kit)));
            cmd.set("#KitDescription.TextSpans", uiText("#KitDescription.TextSpans", kit.description == null || kit.description.isBlank()
                    ? "No description." : kit.description));
            cmd.set("#KitStatus.TextSpans", uiText("#KitStatus.TextSpans", kits.statusText(player, id, kit)));
            cmd.set("#KitCooldown.TextSpans", uiText("#KitCooldown.TextSpans", cooldownText(kit)));
            cmd.set("#KitPlaytime.TextSpans", uiText("#KitPlaytime.TextSpans", kit.requiredOnlineSeconds <= 0
                    ? "none" : KitModule.formatDuration(kit.requiredOnlineSeconds)));
            cmd.set("#KitCost.TextSpans", uiText("#KitCost.TextSpans", kit.cost <= 0 ? "Free" : Double.toString(kit.cost)));
            cmd.set("#KitItems.TextSpans", uiText("#KitItems.TextSpans", kit.items == null ? "0 items" : kit.items.size() + " items"));
        }
    }

    static final class KitPreviewPage extends MysticPage {
        private final KitModule kits;
        private final String kitName;

        KitPreviewPage(MysticCore core, KitModule kits, PlayerRef player, String kitName) {
            super(core, player, CustomPageLifetime.CanDismiss);
            this.kits = kits;
            this.kitName = KitModule.normalize(kitName);
        }

        @Override
        public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event,
                Store<EntityStore> store) {
            cmd.append(KIT_PREVIEW_UI);
            KitConfig.Kit kit = kits.findKit(kitName).orElse(null);
            String title = KitModule.displayName(kitName, kit);
            cmd.set("#PreviewTitle.TextSpans", uiText("#PreviewTitle.TextSpans", title.isBlank() ? "Kit Preview" : title));
            cmd.set("#PreviewDescription.TextSpans", uiText("#PreviewDescription.TextSpans", kit == null || kit.description == null || kit.description.isBlank()
                    ? "No description." : kit.description));
            cmd.set("#PreviewEmpty.Visible", kit == null || kit.items == null || kit.items.isEmpty());
            if (kit != null && kit.items != null) {
                for (int i = 0; i < kit.items.size(); i++) {
                    KitConfig.KitItem item = kit.items.get(i);
                    String row = "#PreviewList[" + i + "]";
                    cmd.append("#PreviewList", KIT_PREVIEW_ROW_UI);
                    if (item == null || item.itemId == null || item.itemId.isBlank()) {
                        cmd.set(row + " #Name.TextSpans", uiText(row + " #Name.TextSpans", "Unknown Item"));
                        cmd.set(row + " #Meta.TextSpans", uiText(row + " #Meta.TextSpans", ""));
                        cmd.set(row + " #Qty.TextSpans", uiText(row + " #Qty.TextSpans", ""));
                        continue;
                    }
                    int quantity = Math.max(1, item.quantity);
                    Item registryItem = KitModule.itemOrNull(item.itemId);
                    Message name = KitModule.itemDisplayName(registryItem);
                    if (registryItem != null) {
                        cmd.set(row + " #Icon.ItemId", item.itemId);
                    }
                    // Registered items retain their translated Message; unknown items
                    // use a prettified raw id. Both are valid TextSpans values.
                    if (name != null) {
                        cmd.set(row + " #Name.TextSpans", uiText(row + " #Name.TextSpans", name));
                    } else {
                        cmd.set(row + " #Name.TextSpans", uiText(row + " #Name.TextSpans", KitModule.prettify(item.itemId)));
                    }
                    cmd.set(row + " #Meta.TextSpans", uiText(row + " #Meta.TextSpans", KitModule.itemMeta(item.itemId)));
                    cmd.set(row + " #Qty.TextSpans", uiText(row + " #Qty.TextSpans", "x" + quantity));
                }
            }
            event.addEventBinding(CustomUIEventBindingType.Activating, "#BackButton",
                    new EventData().put("action", "back"));
            event.addEventBinding(CustomUIEventBindingType.Activating, "#ClaimButton",
                    new EventData().put("action", "claim").put("kit", kitName));
        }

        @Override
        protected void onEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
            JsonObject payload = parse(data);
            switch (string(payload, "action")) {
                case "back" -> reopen(ref, store, new KitListPage(core, kits, player, kitName));
                case "claim" -> {
                    kits.claimFromUi(player, kitName);
                    reopen(ref, store, new KitListPage(core, kits, player, kitName));
                }
                default -> {
                }
            }
        }
    }

    private static String kitSummary(String id, KitConfig.Kit kit) {
        String description = kit.description == null || kit.description.isBlank() ? "No description" : kit.description;
        return description + " | " + (kit.items == null ? 0 : kit.items.size()) + " items";
    }

    private static String cooldownText(KitConfig.Kit kit) {
        if (kit.cooldownSeconds < 0) {
            return "once";
        }
        if (kit.cooldownSeconds == 0) {
            return "none";
        }
        return KitModule.formatDuration(kit.cooldownSeconds);
    }

    /** Colour markup for a status line: ready, waiting, or blocked. */
    private static String statusColor(String status) {
        if (status == null || status.startsWith("Ready,") || status.equals("Ready")) {
            return "&a";
        }
        if (status.contains("claimed") || status.contains("Ready in")) {
            return "&6";
        }
        return "&c";
    }
}
