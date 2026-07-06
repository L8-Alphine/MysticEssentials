package org.hyzionstudios.mysticessentials.modules.kits;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.hyzionstudios.mysticessentials.core.MysticCore;
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

final class KitPages {

    static final String KITS_UI = "MysticEssentials/Kits.ui";
    static final String KIT_ROW_UI = "MysticEssentials/KitRow.ui";
    static final String KIT_PREVIEW_UI = "MysticEssentials/KitPreview.ui";

    private KitPages() {
    }

    static final class KitListPage extends MysticPage {
        private final KitModule kits;
        private final String selectedKit;

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
            cmd.set("#KitCount.Text", entries.size() + " kits");
            cmd.set("#KitEmpty.Visible", entries.isEmpty());

            for (int i = 0; i < entries.size(); i++) {
                Map.Entry<String, KitConfig.Kit> entry = entries.get(i);
                String row = "#KitList[" + i + "]";
                String id = entry.getKey();
                KitConfig.Kit kit = entry.getValue();
                cmd.append("#KitList", KIT_ROW_UI);
                cmd.set(row + " #Name.Text", id);
                cmd.set(row + " #Meta.Text", kitSummary(id, kit));
                cmd.set(row + " #Status.Text", kits.statusText(player, id, kit));
                cmd.set(row + " #Swatch.Background", statusColor(kits.statusText(player, id, kit)));
                event.addEventBinding(CustomUIEventBindingType.Activating, row,
                        new EventData().put("action", "select").put("kit", id));
            }

            Map.Entry<String, KitConfig.Kit> selected = selected(entries);
            applyDetails(cmd, selected);
            if (selected != null) {
                String id = selected.getKey();
                event.addEventBinding(CustomUIEventBindingType.Activating, "#ClaimButton",
                        new EventData().put("action", "claim").put("kit", id));
                event.addEventBinding(CustomUIEventBindingType.Activating, "#PreviewButton",
                        new EventData().put("action", "preview").put("kit", id));
            }
        }

        @Override
        public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
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
                cmd.set("#KitName.Text", "No Kits");
                cmd.set("#KitDescription.Text", "No kits are configured or visible to you.");
                cmd.set("#KitStatus.Text", "-");
                cmd.set("#KitCooldown.Text", "-");
                cmd.set("#KitPlaytime.Text", "-");
                cmd.set("#KitCost.Text", "-");
                cmd.set("#KitItems.Text", "-");
                return;
            }
            String id = selected.getKey();
            KitConfig.Kit kit = selected.getValue();
            cmd.set("#KitName.Text", id);
            cmd.set("#KitDescription.Text", kit.description == null || kit.description.isBlank()
                    ? "No description." : kit.description);
            cmd.set("#KitStatus.Text", kits.statusText(player, id, kit));
            cmd.set("#KitCooldown.Text", cooldownText(kit));
            cmd.set("#KitPlaytime.Text", kit.requiredOnlineSeconds <= 0
                    ? "none" : KitModule.formatDuration(kit.requiredOnlineSeconds));
            cmd.set("#KitCost.Text", kit.cost <= 0 ? "Free" : Double.toString(kit.cost));
            cmd.set("#KitItems.Text", kit.items == null ? "0 items" : kit.items.size() + " items");
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
            cmd.set("#PreviewTitle.Text", kitName.isBlank() ? "Kit Preview" : kitName);
            cmd.set("#PreviewDescription.Text", kit == null || kit.description == null || kit.description.isBlank()
                    ? "No description." : kit.description);
            cmd.set("#PreviewEmpty.Visible", kit == null || kit.items == null || kit.items.isEmpty());
            if (kit != null && kit.items != null) {
                for (int i = 0; i < kit.items.size(); i++) {
                    KitConfig.KitItem item = kit.items.get(i);
                    String row = "#PreviewList[" + i + "]";
                    cmd.append("#PreviewList", KIT_ROW_UI);
                    cmd.set(row + " #Name.Text", item == null || item.itemId == null ? "Unknown Item" : item.itemId);
                    cmd.set(row + " #Meta.Text", "Quantity: " + (item == null ? 0 : Math.max(1, item.quantity)));
                    cmd.set(row + " #Status.Text", "");
                    cmd.set(row + " #Swatch.Background", "#55FFFF");
                }
            }
            event.addEventBinding(CustomUIEventBindingType.Activating, "#BackButton",
                    new EventData().put("action", "back"));
            event.addEventBinding(CustomUIEventBindingType.Activating, "#ClaimButton",
                    new EventData().put("action", "claim").put("kit", kitName));
        }

        @Override
        public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
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

    private static String statusColor(String status) {
        if (status == null || status.startsWith("Ready")) {
            return "#55FF55";
        }
        if (status.contains("claimed") || status.contains("Ready in")) {
            return "#FFAA00";
        }
        return "#FF5555";
    }
}
