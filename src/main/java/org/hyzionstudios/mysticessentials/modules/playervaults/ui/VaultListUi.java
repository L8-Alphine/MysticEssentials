package org.hyzionstudios.mysticessentials.modules.playervaults.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.modules.playervaults.api.VaultOpenMode;
import org.hyzionstudios.mysticessentials.modules.playervaults.config.PlayerVaultConfig;
import org.hyzionstudios.mysticessentials.modules.playervaults.model.PlayerVault;
import org.hyzionstudios.mysticessentials.platform.ui.MysticPage;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.LocalizableString;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * The vault-manager dashboard: a tabbed Custom UI over the vaults the viewer can
 * see. Tabs (rendered as a button row that rebuilds the page — no unverified
 * {@code TabNavigation} element) are:
 *
 * <ul>
 *   <li><b>My Vaults</b> — searchable, sortable list of vault cards. Left-click a
 *       card to open the native chest; right-click or Edit to open the editor.</li>
 *   <li><b>Settings</b> — the viewer's allowance and usage summary.</li>
 *   <li><b>Admin</b> (staff only) — open another online player's vaults.</li>
 * </ul>
 *
 * <p>Vault data is loaded once by the controller and handed in via {@code vaults}
 * (index {@code i} = vault number {@code i + 1}). Tab switches, search, and sort
 * are <b>synchronous</b> page rebuilds reusing that already-loaded data, so they
 * never hit storage and never stall the client.</p>
 */
final class VaultListUi extends MysticPage {

    static final String LIST_UI = "MysticEssentials/VaultList.ui";
    static final String CARD_UI = "MysticEssentials/VaultCardRow.ui";

    private final PlayerVaultUiController controller;
    private final UUID ownerUuid;
    private final String ownerName;
    private final int allowedVaults;
    private final int allowedRows;
    private final boolean adminView;
    private final List<PlayerVault> vaults;

    /** "vaults" | "settings" | "admin". */
    private final String activeTab;
    private final String query;
    /** "number" | "name" | "fullness" | "emptiest". */
    private final String sortKey;

    VaultListUi(MysticCore core, PlayerVaultUiController controller, PlayerRef player, UUID ownerUuid,
            String ownerName, int allowedVaults, int allowedRows, boolean adminView, List<PlayerVault> vaults) {
        this(core, controller, player, ownerUuid, ownerName, allowedVaults, allowedRows, adminView, vaults,
                "vaults", "", "number");
    }

    VaultListUi(MysticCore core, PlayerVaultUiController controller, PlayerRef player, UUID ownerUuid,
            String ownerName, int allowedVaults, int allowedRows, boolean adminView, List<PlayerVault> vaults,
            String activeTab, String query, String sortKey) {
        super(core, player, CustomPageLifetime.CanDismiss);
        this.controller = controller;
        this.ownerUuid = ownerUuid;
        this.ownerName = ownerName;
        this.allowedVaults = allowedVaults;
        this.allowedRows = allowedRows;
        this.adminView = adminView;
        this.vaults = vaults;
        this.activeTab = activeTab == null ? "vaults" : activeTab;
        this.query = query == null ? "" : query;
        this.sortKey = sortKey == null ? "number" : sortKey;
    }

    // ----- Build ----------------------------------------------------------------

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event, Store<EntityStore> store) {
        cmd.append(LIST_UI);
        PlayerVaultConfig config = controller.config();
        int slotsPerRow = Math.max(1, config.slotsPerRow);
        boolean canAdmin = controller.permissions().canAdminOpen(player) && !adminView;

        cmd.set("#HeaderTitle.Text", adminView
                ? (ownerName == null ? "Vaults" : ownerName + "'s Vaults") : "Vault Manager");
        cmd.set("#VaultCount.Text", allowedVaults + " vault" + (allowedVaults == 1 ? "" : "s"));

        boolean vaultsTab = activeTab.equals("vaults");
        boolean settingsTab = activeTab.equals("settings");
        boolean adminTab = activeTab.equals("admin") && canAdmin;
        if (!vaultsTab && !settingsTab && !adminTab) {
            vaultsTab = true; // fall back if an admin tab was requested without permission
        }

        // Tabs
        cmd.set("#TabAdmin.Visible", canAdmin);
        cmd.set("#TabMyVaultsAccent.Visible", vaultsTab);
        cmd.set("#TabSettingsAccent.Visible", settingsTab);
        cmd.set("#TabAdminAccent.Visible", adminTab);
        event.addEventBinding(CustomUIEventBindingType.Activating, "#TabMyVaults",
                new EventData().put("action", "tab").put("tab", "vaults"));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#TabSettings",
                new EventData().put("action", "tab").put("tab", "settings"));
        if (canAdmin) {
            event.addEventBinding(CustomUIEventBindingType.Activating, "#TabAdmin",
                    new EventData().put("action", "tab").put("tab", "admin"));
        }

        cmd.set("#Toolbar.Visible", vaultsTab);
        cmd.set("#VaultList.Visible", vaultsTab);
        cmd.set("#SettingsPanel.Visible", settingsTab);
        cmd.set("#AdminPanel.Visible", adminTab);

        if (vaultsTab) {
            buildVaultsTab(cmd, event, config, slotsPerRow);
        } else if (settingsTab) {
            buildSettingsTab(cmd, slotsPerRow);
        } else {
            buildAdminTab(cmd, event);
        }
    }

    private void buildVaultsTab(UICommandBuilder cmd, UIEventBuilder event, PlayerVaultConfig config, int slotsPerRow) {
        cmd.set("#SearchInput.Value", query);
        cmd.set("#SortBox.Entries", List.of(
                new DropdownEntryInfo(LocalizableString.fromString("Number"), "number"),
                new DropdownEntryInfo(LocalizableString.fromString("Name"), "name"),
                new DropdownEntryInfo(LocalizableString.fromString("Most full"), "fullness"),
                new DropdownEntryInfo(LocalizableString.fromString("Emptiest"), "emptiest")));
        cmd.set("#SortBox.Value", sortKey);
        event.addEventBinding(CustomUIEventBindingType.ValueChanged, "#SortBox",
                new EventData().put("action", "sort").append("@sort", "#SortBox.Value"));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#SearchButton",
                new EventData().put("action", "search").append("@q", "#SearchInput.Value"));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#ClearButton",
                new EventData().put("action", "clear"));

        List<Card> cards = collectCards(config, slotsPerRow);
        cmd.set("#VaultEmpty.Visible", cards.isEmpty());

        for (int i = 0; i < cards.size(); i++) {
            Card card = cards.get(i);
            String row = "#VaultList[" + i + "]";
            cmd.append("#VaultList", CARD_UI);

            cmd.set(row + " #Name.Text", card.name);
            cmd.set(row + " #Number.Text", "#" + card.number);
            cmd.set(row + " #Icon.ItemId", iconItemId(card.vault));
            cmd.set(row + " #Meta.Text", card.used + " / " + card.capacity + " slots  |  " + card.rows + " rows");
            cmd.set(row + " #Status.Text", statusText(card.accessible, card.overflow));
            cmd.set(row + " #Swatch.Background", swatchColor(card.accessible, card.vault));

            if (card.accessible) {
                event.addEventBinding(CustomUIEventBindingType.Activating, row,
                        new EventData().put("action", "open").put("vault", Integer.toString(card.number)));
                event.addEventBinding(CustomUIEventBindingType.RightClicking, row,
                        new EventData().put("action", "edit").put("vault", Integer.toString(card.number)));
                event.addEventBinding(CustomUIEventBindingType.Activating, row + " #EditButton",
                        new EventData().put("action", "edit").put("vault", Integer.toString(card.number)));
            }
        }
    }

    private void buildSettingsTab(UICommandBuilder cmd, int slotsPerRow) {
        int perVaultSlots = allowedRows * slotsPerRow;
        int usedSlots = 0;
        int customized = 0;
        for (int i = 0; i < vaults.size() && i < allowedVaults; i++) {
            PlayerVault vault = vaults.get(i);
            if (vault == null) {
                continue;
            }
            usedSlots += vault.accessibleItemCount(allowedRows, slotsPerRow);
            if (vault.metadata != null && (vault.metadata.name != null || vault.metadata.icon != null
                    || vault.metadata.color != null || vault.metadata.description != null)) {
                customized++;
            }
        }
        int totalCapacity = perVaultSlots * allowedVaults;

        cmd.set("#SetAllowance.Text", "Vaults: " + allowedVaults + "     Rows each: " + allowedRows
                + "     Slots per vault: " + perVaultSlots);
        cmd.set("#SetUsage.Text", "Used: " + usedSlots + " / " + totalCapacity + " slots across all vaults");
        cmd.set("#SetCustom.Text", "Customized cards: " + customized + " / " + allowedVaults);
        cmd.set("#SetHint.Text", "Rank up for more vaults and rows. Open a vault from the My Vaults tab to "
                + "store items (drag them in the chest), or use its Edit button to rename, recolour, "
                + "pick an icon, and set a description.");
    }

    private void buildAdminTab(UICommandBuilder cmd, UIEventBuilder event) {
        event.addEventBinding(CustomUIEventBindingType.Activating, "#AdminViewButton",
                new EventData().put("action", "adminView").append("@player", "#AdminPlayerInput.Value"));
        cmd.set("#AdminHint.Text", "Enter an online player's name to open their vaults in admin view. "
                + "You can inspect and (with edit permission) modify their vault contents and cards.");
    }

    // ----- Events ---------------------------------------------------------------

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
        var payload = parse(data);
        switch (string(payload, "action")) {
            case "tab" -> reopen(ref, store, with(string(payload, "tab"), query, sortKey));
            case "search" -> reopen(ref, store, with(activeTab, string(payload, "@q"), sortKey));
            case "clear" -> reopen(ref, store, with(activeTab, "", sortKey));
            case "sort" -> reopen(ref, store, with(activeTab, query, string(payload, "@sort")));
            case "open" -> {
                int vault = (int) parseDouble(field(payload, "vault"), 0);
                if (vault < 1) {
                    return;
                }
                close(ref, store);
                if (adminView) {
                    controller.openAdminVault(player, ownerUuid, ownerName, vault,
                            VaultOpenMode.ADMIN_EDIT, isOnline(ownerUuid));
                } else {
                    controller.openOwnVault(player, vault);
                }
            }
            case "edit" -> {
                int vault = (int) parseDouble(field(payload, "vault"), 0);
                if (vault < 1) {
                    return;
                }
                close(ref, store);
                controller.openEditor(player, ownerUuid, ownerName, vault, adminView);
            }
            case "adminView" -> handleAdminView(ref, store, string(payload, "@player"));
            default -> {
            }
        }
    }

    private void handleAdminView(Ref<EntityStore> ref, Store<EntityStore> store, String targetName) {
        if (!controller.permissions().canAdminOpen(player)) {
            return;
        }
        if (targetName == null || targetName.isBlank()) {
            core.getMessageService().sendKey(player, "vault-admin-target-required");
            return;
        }
        Optional<PlayerRef> target = core.platform().findPlayerByName(targetName.trim());
        if (target.isEmpty()) {
            core.getMessageService().sendKey(player, "player-not-found",
                    java.util.Map.of("player", targetName.trim()));
            return;
        }
        close(ref, store);
        controller.openVaultListFor(player, target.get().getUuid(), target.get().getUsername(), true);
    }

    /** A fresh page instance with the same loaded data but new tab/search/sort — for synchronous refresh. */
    private VaultListUi with(String tab, String search, String sort) {
        return new VaultListUi(core, controller, player, ownerUuid, ownerName, allowedVaults, allowedRows,
                adminView, vaults, tab, search, sort);
    }

    // ----- Card assembly --------------------------------------------------------

    /** One rendered card: resolved number, its vault (nullable), and display facts. */
    private static final class Card {
        final int number;
        final PlayerVault vault;
        final boolean accessible;
        final boolean overflow;
        final int rows;
        final int used;
        final int capacity;
        final String name;

        Card(int number, PlayerVault vault, boolean accessible, boolean overflow,
                int rows, int used, int capacity, String name) {
            this.number = number;
            this.vault = vault;
            this.accessible = accessible;
            this.overflow = overflow;
            this.rows = rows;
            this.used = used;
            this.capacity = capacity;
            this.name = name;
        }
    }

    private List<Card> collectCards(PlayerVaultConfig config, int slotsPerRow) {
        List<Card> cards = new ArrayList<>();
        String needle = query == null ? "" : query.trim().toLowerCase();
        for (int i = 0; i < vaults.size(); i++) {
            int number = i + 1;
            boolean accessible = adminView || number <= allowedVaults;
            if (!accessible && !config.showLockedVaults) {
                continue;
            }
            PlayerVault vault = vaults.get(i);
            String name = vault != null && vault.metadata != null && vault.metadata.name != null
                    && !vault.metadata.name.isBlank() ? vault.metadata.name : "Vault #" + number;
            if (!needle.isEmpty()
                    && !name.toLowerCase().contains(needle)
                    && !Integer.toString(number).contains(needle)) {
                continue;
            }
            int rows = vault != null && vault.rows > 0 ? Math.min(vault.rows, allowedRows) : allowedRows;
            int used = vault == null ? 0 : vault.accessibleItemCount(allowedRows, slotsPerRow);
            int capacity = rows * slotsPerRow;
            boolean overflow = vault != null && vault.hasOverflow(allowedRows, slotsPerRow);
            cards.add(new Card(number, vault, accessible, overflow, rows, used, capacity, name));
        }
        sortCards(cards);
        return cards;
    }

    private void sortCards(List<Card> cards) {
        Comparator<Card> comparator = switch (sortKey) {
            case "name" -> Comparator.comparing((Card c) -> c.name.toLowerCase())
                    .thenComparingInt(c -> c.number);
            case "fullness" -> Comparator.comparingInt((Card c) -> c.used).reversed()
                    .thenComparingInt(c -> c.number);
            case "emptiest" -> Comparator.comparingInt((Card c) -> c.used)
                    .thenComparingInt(c -> c.number);
            default -> Comparator.comparingInt(c -> c.number);
        };
        cards.sort(comparator);
    }

    private boolean isOnline(UUID uuid) {
        return core.platform().findPlayer(uuid).isPresent();
    }

    /** @return the item id the card's ItemSlot should render, or the config default. */
    private String iconItemId(PlayerVault vault) {
        if (vault != null && vault.metadata != null && vault.metadata.icon != null
                && vault.metadata.icon.itemId != null && !vault.metadata.icon.itemId.isBlank()) {
            return vault.metadata.icon.itemId;
        }
        return controller.config().defaultIconItemId == null ? "" : controller.config().defaultIconItemId;
    }

    private String statusText(boolean accessible, boolean overflow) {
        if (!accessible) {
            return "Locked";
        }
        if (adminView) {
            return "Admin View";
        }
        return overflow ? "Overflow" : "Available";
    }

    private String swatchColor(boolean accessible, PlayerVault vault) {
        if (!accessible) {
            return "#555555";
        }
        if (vault != null && vault.metadata != null) {
            return safeColor(vault.metadata.color);
        }
        return "#7a9cc6";
    }
}
