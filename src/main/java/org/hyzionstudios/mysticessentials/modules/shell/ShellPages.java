package org.hyzionstudios.mysticessentials.modules.shell;

import static org.hyzionstudios.mysticessentials.platform.ui.MysticPage.uiText;

import java.util.List;

import org.hyzionstudios.mysticessentials.api.Permissions;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.modules.shell.MenuCatalog.MenuCard;
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
 * The shell's two pages: the pointer-driven Shortcuts list and the main menu.
 *
 * <p>Both follow the mod's page convention — a page {@code .ui} with empty
 * containers, one row-template {@code .ui} appended per entry, rows addressed
 * by index, and the whole page rebuilt on every press rather than patched.</p>
 */
final class ShellPages {

    static final String SHORTCUTS_UI = "MysticEssentials/ShortcutBar.ui";
    static final String SHORTCUT_ROW_UI = "MysticEssentials/ShortcutBarRow.ui";
    static final String MENU_UI = "MysticEssentials/MainMenu.ui";
    static final String MENU_NAV_UI = "MysticEssentials/MainMenuNavRow.ui";
    static final String MENU_CARD_UI = "MysticEssentials/MainMenuCard.ui";

    private ShellPages() {
    }

    // ----- Shortcuts ---------------------------------------------------------

    /**
     * The nine rail slots as real buttons.
     *
     * <p>The rail itself is a HUD and the client routes no events to a HUD, so
     * this page is the only place a pointer can reach a slot. It shows exactly
     * what the rail shows — same order, same glyphs, same reasons — so the two
     * never disagree about what a slot does.</p>
     */
    static final class ShortcutBarPage extends MysticPage {

        private final ShellModule shell;

        ShortcutBarPage(MysticCore core, ShellModule shell, PlayerRef player) {
            super(core, player, CustomPageLifetime.CanDismiss);
            this.shell = shell;
        }

        @Override
        public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event,
                Store<EntityStore> store) {
            cmd.append(SHORTCUTS_UI);
            cmd.set("#HeaderHint.TextSpans", uiText("#HeaderHint.TextSpans", shell.railHint()));
            cmd.set("#ShortcutIntro.TextSpans", uiText("#ShortcutIntro.TextSpans",
                    "Nine slots sit beside your chat. The first four switch the channel you "
                            + "speak in; the rest open a destination. " + shell.activationHint()));

            List<ShortcutSlot> slots = shell.slotsFor(player);
            int channels = 0;
            int destinations = 0;
            for (ShortcutSlot slot : slots) {
                boolean isChannel = slot.kind() == ShortcutKind.CHANNEL;
                String list = isChannel ? "#ChannelList" : "#DestinationList";
                int index = isChannel ? channels++ : destinations++;
                renderSlot(cmd, event, list, index, slot);
            }

            cmd.set("#ToggleRailButton.TextSpans", uiText("#ToggleRailButton.TextSpans",
                    shell.railVisible(player) ? "Hide Rail" : "Show Rail"));
            cmd.set("#ToggleRailButton.Visible", shell.config().rail.enabled);
            event.addEventBinding(CustomUIEventBindingType.Activating, "#ToggleRailButton",
                    new EventData().put("action", "rail"));
            event.addEventBinding(CustomUIEventBindingType.Activating, "#OpenMenuButton",
                    new EventData().put("action", "menu"));
            cmd.set("#EditRailButton.Visible", player.hasPermission(Permissions.SHELL_ADMIN));
            event.addEventBinding(CustomUIEventBindingType.Activating, "#EditRailButton",
                    new EventData().put("action", "edit"));
            event.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton",
                    new EventData().put("action", "close"));
        }

        private void renderSlot(UICommandBuilder cmd, UIEventBuilder event, String list, int index,
                ShortcutSlot slot) {
            String row = list + "[" + index + "]";
            cmd.append(list, SHORTCUT_ROW_UI);
            boolean slotIcon = !slot.icon().isEmpty();
            cmd.set(row + " #SlotIcon.Visible", slotIcon);
            cmd.set(row + " #SlotGlyph.Visible", !slotIcon);
            if (slotIcon) {
                cmd.set(row + " #SlotIcon.AssetPath", slot.icon());
            } else {
                cmd.set(row + " #SlotGlyph.TextSpans", uiText(row + " #SlotGlyph.TextSpans", slot.glyph()));
            }
            cmd.set(row + " #SlotName.TextSpans", uiText(row + " #SlotName.TextSpans", slot.label()));
            cmd.set(row + " #SlotState.TextSpans", uiText(row + " #SlotState.TextSpans", slot.stateLine()));
            cmd.set(row + " #SlotKey.TextSpans", uiText(row + " #SlotKey.TextSpans", shell.keyLabel(slot.index())));
            cmd.set(row + " #SlotSelected.Visible", slot.active());
            // An unavailable slot keeps its binding: pressing it should repeat
            // the reason, not do nothing. activateSlot re-checks and explains.
            event.addEventBinding(CustomUIEventBindingType.Activating, row,
                    new EventData().put("action", "slot").put("slot", String.valueOf(slot.index())));
        }

        @Override
        public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
            JsonObject payload = parse(data);
            switch (string(payload, "action")) {
                case "slot" -> {
                    int slot = (int) parseDouble(field(payload, "slot"), 0);
                    // The slot runs after the page closes: most of them open a
                    // page of their own, and two custom pages at once is one
                    // too many.
                    close(ref, store);
                    shell.activateSlot(player, slot);
                }
                case "rail" -> {
                    shell.setRailVisible(player, !shell.railVisible(player));
                    reopen(ref, store, new ShortcutBarPage(core, shell, player));
                }
                case "menu" -> reopen(ref, store, new MainMenuPage(core, shell, player));
                case "edit" -> {
                    if (player.hasPermission(Permissions.SHELL_ADMIN)) {
                        reopen(ref, store, new RailEditorPage(core, shell, player, 1));
                    }
                }
                case "close" -> close(ref, store);
                default -> {
                    // Unknown action: leave the page as it is.
                }
            }
        }
    }

    // ----- Main menu ---------------------------------------------------------

    /**
     * The navigation shell (bible §7, §10): a section rail on the left, a card
     * grid on the right, and nothing hardcoded about which modules exist.
     */
    static final class MainMenuPage extends MysticPage {

        private final ShellModule shell;
        private final MenuSection section;

        MainMenuPage(MysticCore core, ShellModule shell, PlayerRef player) {
            this(core, shell, player, MenuSection.OVERVIEW);
        }

        MainMenuPage(MysticCore core, ShellModule shell, PlayerRef player, MenuSection section) {
            super(core, player, CustomPageLifetime.CanDismiss);
            this.shell = shell;
            this.section = section == null ? MenuSection.OVERVIEW : section;
        }

        @Override
        public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event,
                Store<EntityStore> store) {
            cmd.append(MENU_UI);
            MenuCatalog catalog = shell.catalog();

            cmd.set("#MenuContext.TextSpans", uiText("#MenuContext.TextSpans", context()));
            cmd.set("#SectionTitle.TextSpans", uiText("#SectionTitle.TextSpans", section.title()));
            cmd.set("#SectionBlurb.TextSpans", uiText("#SectionBlurb.TextSpans", section.blurb()));
            cmd.set("#MenuKeyHint.TextSpans", uiText("#MenuKeyHint.TextSpans", shell.railHint()));
            cmd.set("#MenuFooterStatus.TextSpans", uiText("#MenuFooterStatus.TextSpans",
                    shell.ecosystemSummary()));

            List<MenuSection> sections = catalog.sectionsFor(player);
            for (int i = 0; i < sections.size(); i++) {
                MenuSection entry = sections.get(i);
                String row = "#MenuNav[" + i + "]";
                cmd.append("#MenuNav", MENU_NAV_UI);
                cmd.set(row + " #NavName.TextSpans", uiText(row + " #NavName.TextSpans", entry.title()));
                cmd.set(row + " #NavSelected.Visible", entry == section);
                cmd.set(row + " #NavIcon.AssetPath", entry.icon());
                cmd.set(row + " #NavIcon.Visible", true);
                event.addEventBinding(CustomUIEventBindingType.Activating, row,
                        new EventData().put("action", "section").put("section", entry.name()));
            }

            List<MenuCard> cards = catalog.cards(player, section);
            cmd.set("#MenuEmpty.Visible", cards.isEmpty());
            if (cards.isEmpty()) {
                cmd.set("#MenuEmpty.TextSpans", uiText("#MenuEmpty.TextSpans",
                        "Nothing in this section is available to you yet."));
            }
            // Deal the cards left/right so the two columns stay even, which is
            // the bible's primary/secondary grid without a fixed card count.
            int left = 0;
            int right = 0;
            for (MenuCard card : cards) {
                boolean toLeft = left <= right;
                String list = toLeft ? "#MenuCardsLeft" : "#MenuCardsRight";
                int index = toLeft ? left++ : right++;
                renderCard(cmd, event, list, index, card);
            }

            event.addEventBinding(CustomUIEventBindingType.Activating, "#ShortcutsButton",
                    new EventData().put("action", "shortcuts"));
            cmd.set("#WikiButton.Visible", core.getModuleManager().isEnabled("wiki"));
            event.addEventBinding(CustomUIEventBindingType.Activating, "#WikiButton",
                    new EventData().put("action", "wiki"));
            event.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton",
                    new EventData().put("action", "close"));
        }

        private void renderCard(UICommandBuilder cmd, UIEventBuilder event, String list, int index,
                MenuCard card) {
            String row = list + "[" + index + "]";
            cmd.append(list, MENU_CARD_UI);
            String icon = MenuCatalog.iconFor(card);
            cmd.set(row + " #CardIcon.Visible", !icon.isEmpty());
            cmd.set(row + " #CardGlyph.Visible", icon.isEmpty());
            if (!icon.isEmpty()) {
                cmd.set(row + " #CardIcon.AssetPath", icon);
            } else {
                cmd.set(row + " #CardGlyph.TextSpans", uiText(row + " #CardGlyph.TextSpans", card.glyph()));
            }
            cmd.set(row + " #CardTitle.TextSpans", uiText(row + " #CardTitle.TextSpans", card.title()));
            cmd.set(row + " #CardBlurb.TextSpans", uiText(row + " #CardBlurb.TextSpans", card.blurb()));
            cmd.set(row + " #CardStatus.Visible", !card.available());
            if (!card.available()) {
                cmd.set(row + " #CardStatus.TextSpans",
                        uiText(row + " #CardStatus.TextSpans", card.reason()));
            }
            event.addEventBinding(CustomUIEventBindingType.Activating, row,
                    new EventData().put("action", "card").put("card", card.id()));
        }

        /** The world/character strip the bible puts at the top of the menu. */
        private String context() {
            String world = core.platform().worldNameOf(player).orElse("");
            String server = shell.serverTitle();
            StringBuilder context = new StringBuilder(player.getUsername());
            if (!world.isBlank()) {
                context.append(" · ").append(world);
            }
            if (!server.isBlank()) {
                context.append(" · ").append(server);
            }
            return context.toString();
        }

        @Override
        public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
            JsonObject payload = parse(data);
            switch (string(payload, "action")) {
                case "section" -> reopen(ref, store,
                        new MainMenuPage(core, shell, player, MenuSection.parse(field(payload, "section"))));
                case "card" -> {
                    String id = field(payload, "card");
                    // Re-resolve from the catalog rather than trusting the id
                    // the client sent: the card may have become unavailable
                    // since the page was built, and the owning module's own
                    // permission check still runs inside the action.
                    MenuCard card = shell.catalog().cards(player, section).stream()
                            .filter(entry -> entry.id().equals(id))
                            .findFirst().orElse(null);
                    if (card == null) {
                        reopen(ref, store, new MainMenuPage(core, shell, player, section));
                        return;
                    }
                    if (!card.available()) {
                        shell.tell(player, card.title() + ": " + card.reason());
                        reopen(ref, store, new MainMenuPage(core, shell, player, section));
                        return;
                    }
                    close(ref, store);
                    shell.runCard(player, card);
                }
                case "shortcuts" -> reopen(ref, store, new ShortcutBarPage(core, shell, player));
                case "wiki" -> {
                    close(ref, store);
                    shell.openWiki(player);
                }
                case "close" -> close(ref, store);
                default -> {
                    // Unknown action: leave the page as it is.
                }
            }
        }
    }
}
