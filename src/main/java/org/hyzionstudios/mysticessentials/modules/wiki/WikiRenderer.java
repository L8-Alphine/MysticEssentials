package org.hyzionstudios.mysticessentials.modules.wiki;

import static org.hyzionstudios.mysticessentials.platform.ui.MysticPage.uiText;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.hyzionstudios.mysticessentials.modules.wiki.WikiMarkup.Block;
import org.hyzionstudios.mysticessentials.modules.wiki.WikiMarkup.Kind;
import org.hyzionstudios.mysticessentials.modules.wiki.WikiModule.WikiPage;
import org.hyzionstudios.mysticessentials.platform.ui.HyzionIcons;
import org.hyzionstudios.mysticessentials.platform.ui.ItemCatalog;
import org.hyzionstudios.mysticessentials.platform.ui.VanillaIcons;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Draws parsed wiki blocks into a container, one {@code WikiLine.ui} each. The
 * reader and the editor's live preview share it, so what an author sees while
 * typing is exactly what readers get.
 *
 * <p>Anything a block names that does not exist — an icon, an item, an
 * embedded page — renders as a dim note saying so rather than silently
 * vanishing: the author sees the mistake in the preview.</p>
 */
final class WikiRenderer {

    static final String LINE_UI = "MysticEssentials/WikiLine.ui";
    private static final int GALLERY_CELLS = 8;

    private WikiRenderer() {
    }

    /**
     * Replaces {@code {{page:id}}} with that page's blocks, one level deep: an
     * embed inside an embedded page becomes a link, so two pages that embed
     * each other cannot loop.
     */
    static List<Block> expand(List<Block> blocks, WikiModule wiki, PlayerRef reader, String selfId) {
        List<Block> out = new ArrayList<>();
        for (Block block : blocks) {
            if (block.kind() != Kind.EMBED) {
                out.add(block);
                continue;
            }
            String id = block.target();
            WikiPage page = id.equals(selfId) ? null : wiki.page(reader, id).orElse(null);
            if (page == null) {
                out.add(new Block(Kind.PARAGRAPH, "&8(No page called \"" + id + "\" to show here.)", ""));
                continue;
            }
            out.add(new Block(Kind.EMBED, block.text().isEmpty() ? page.title() : block.text(), page.id()));
            for (Block inner : WikiMarkup.parse(page.body())) {
                out.add(inner.kind() == Kind.EMBED
                        ? new Block(Kind.LINK, inner.text().isEmpty() ? inner.target() : inner.text(), inner.target())
                        : inner);
            }
            out.add(new Block(Kind.LINK, "Open " + page.title(), page.id()));
        }
        return out;
    }

    /**
     * Appends {@code blocks} to {@code container}.
     *
     * @param event where link buttons are bound, or {@code null} to draw them inert (the preview)
     */
    static void render(UICommandBuilder cmd, UIEventBuilder event, String container, List<Block> blocks) {
        for (int i = 0; i < blocks.size(); i++) {
            String row = container + "[" + i + "]";
            cmd.append(container, LINE_UI);
            renderBlock(cmd, event, row, blocks.get(i));
        }
    }

    private static void renderBlock(UICommandBuilder cmd, UIEventBuilder event, String row, Block block) {
        switch (block.kind()) {
            case HEADING -> text(cmd, row, "#LineHeading", "#LineHeading", block.text());
            case SUBHEADING -> text(cmd, row, "#LineSubheading", "#LineSubheading", block.text());
            case PARAGRAPH -> text(cmd, row, "#LineParagraph", "#LineParagraph", block.text());
            case BULLET -> text(cmd, row, "#LineBullet", "#LineBulletText", block.text());
            case QUOTE -> text(cmd, row, "#LineQuote", "#LineQuoteText", block.text());
            case RULE -> cmd.set(row + " #LineRule.Visible", true);
            case LINK -> {
                text(cmd, row, "#LineLink", "#LineLink", linkLabel(block));
                if (event != null) {
                    event.addEventBinding(CustomUIEventBindingType.Activating, row + " #LineLink",
                            new EventData().put("action", "link").put("target", block.target()));
                }
            }
            case IMAGE -> {
                String path = iconPath(block.target());
                if (path.isEmpty()) {
                    missing(cmd, row, "No icon called \"" + block.target() + "\"");
                    return;
                }
                cmd.set(row + " #LineImage.Visible", true);
                cmd.set(row + " #LineImageIcon.AssetPath", path);
                cmd.set(row + " #LineImageCaption.TextSpans", uiText(row + " #LineImageCaption.TextSpans", block.text()));
            }
            case ITEM -> {
                String itemId = block.target();
                if (!ItemCatalog.exists(itemId)) {
                    missing(cmd, row, "No item called \"" + itemId + "\"");
                    return;
                }
                cmd.set(row + " #LineItem.Visible", true);
                cmd.set(row + " #LineItemSlot.ItemId", itemId);
                cmd.set(row + " #LineItemName.TextSpans", uiText(row + " #LineItemName.TextSpans", itemName(itemId)));
                cmd.set(row + " #LineItemCaption.TextSpans", uiText(row + " #LineItemCaption.TextSpans",
                        block.text().isEmpty() ? itemId : block.text()));
            }
            case ICONS, ITEMS -> gallery(cmd, row, block);
            case EMBED -> text(cmd, row, "#LineEmbed", "#LineEmbedTitle", "From " + block.text());
        }
    }

    private static void gallery(UICommandBuilder cmd, String row, Block block) {
        boolean items = block.kind() == Kind.ITEMS;
        int shown = 0;
        for (int i = 0; i < GALLERY_CELLS; i++) {
            String cell = row + " #Cell" + i;
            String ref = i < block.refs().size() ? block.refs().get(i) : "";
            String value = ref.isEmpty() ? "" : items ? (ItemCatalog.exists(ref) ? ref : "") : iconPath(ref);
            if (value.isEmpty()) {
                cmd.set(cell + ".Visible", false);
                continue;
            }
            shown++;
            if (items) {
                cmd.set(cell + " #CellItem.Visible", true);
                cmd.set(cell + " #CellItem.ItemId", value);
            } else {
                cmd.set(cell + " #CellIcon.Visible", true);
                cmd.set(cell + " #CellIcon.AssetPath", value);
            }
        }
        if (shown == 0) {
            missing(cmd, row, "None of " + String.join(", ", block.refs()) + " exist");
            return;
        }
        cmd.set(row + " #LineGallery.Visible", true);
        cmd.set(row + " #LineGalleryCaption.TextSpans", uiText(row + " #LineGalleryCaption.TextSpans", block.text()));
    }

    private static void text(UICommandBuilder cmd, String row, String group, String label, String value) {
        cmd.set(row + " " + group + ".Visible", true);
        cmd.set(row + " " + label + ".TextSpans", uiText(row + " " + label + ".TextSpans", value));
    }

    private static void missing(UICommandBuilder cmd, String row, String what) {
        text(cmd, row, "#LineParagraph", "#LineParagraph", "&8(" + what + ".)");
    }

    private static String linkLabel(Block block) {
        return switch (WikiMarkup.Target.of(block.target())) {
            case URL -> block.text() + "  (link)";
            case COMMAND -> block.text() + "  (" + block.target() + ")";
            case PAGE -> block.text();
        };
    }

    /** An icon reference ({@code Hyzion Wiki}, {@code Portal}, {@code custom:x}, a path) to its path. */
    static String iconPath(String ref) {
        return VanillaIcons.resolve(ref);
    }

    /**
     * Shows a page's icon in an {@code AssetImage} / {@code ItemSlot} pair:
     * {@code item:<Id>} draws the item, anything else is an icon reference,
     * and a page with neither gets the wiki's book.
     */
    static void pageIcon(UICommandBuilder cmd, String image, String slot, String icon) {
        String value = icon == null ? "" : icon.trim();
        if (value.regionMatches(true, 0, "item:", 0, 5) && ItemCatalog.exists(value.substring(5).trim())) {
            cmd.set(image + ".Visible", false);
            cmd.set(slot + ".Visible", true);
            cmd.set(slot + ".ItemId", value.substring(5).trim());
            return;
        }
        String path = value.isEmpty() ? "" : iconPath(value);
        cmd.set(slot + ".Visible", false);
        cmd.set(image + ".Visible", true);
        cmd.set(image + ".AssetPath", path.isEmpty() ? HyzionIcons.WIKI : path);
    }

    /** The item's translated name, or its id made readable when it has none. */
    static Object itemName(String itemId) {
        try {
            Item item = Item.getAssetMap().getAssetMap().get(itemId);
            Message name = item == null ? null : item.getTranslationMessage();
            if (name != null) {
                return name;
            }
        } catch (Throwable ignored) {
            // Fall through to the readable id.
        }
        String readable = itemId.replace('_', ' ').toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();
        for (String word : readable.split(" ")) {
            if (!word.isEmpty()) {
                out.append(out.length() == 0 ? "" : " ").append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return out.toString();
    }
}
