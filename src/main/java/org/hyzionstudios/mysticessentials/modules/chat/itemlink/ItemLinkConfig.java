package org.hyzionstudios.mysticessentials.modules.chat.itemlink;

/**
 * Persisted settings for {@code modules/chat/item-links.json} — how the chat
 * side of item sharing behaves.
 *
 * <p>How an item is <i>inspected</i> and <i>presented</i> lives in the separate
 * {@code modules/chat/item-view.json}
 * ({@link org.hyzionstudios.mysticessentials.core.item.ItemViewConfig}), because
 * the ItemView is shared infrastructure used well beyond chat.</p>
 *
 * <p>Reflects the verified 0.5.6 reality: inline {@code FormattedMessage.image}
 * does not render in chat, so there is no "item icon in chat" toggle — the chat
 * line carries the formatted, colour-coded name and the icon appears only in the
 * custom-UI panels, which render real {@code ItemSlot} elements.</p>
 */
public final class ItemLinkConfig {

    public boolean enabled = true;
    /** The literal tag a player types in chat to share their held item. */
    public String tag = "[item]";
    /** Permission required to use the tag (blank/null = everyone). */
    public String usePermission = "mysticessentials.chat.itemlink.use";
    /** Max item tags expanded per chat message (all resolve to the one held item). */
    public int maxTagsPerMessage = 3;
    /**
     * Wrap the chat display name in a {@code <link:/itemview CODE>} so a click
     * opens the details page. Harmless if the client treats chat links as URLs
     * only — the visible view command below is the guaranteed path.
     */
    public boolean linkChatNameToInspect = true;
    /**
     * Append a visible, typeable {@code (/itemview CODE)} hint after the name.
     * This is the reliable, click-independent way to open the viewer: the
     * recipient types (or clicks) exactly what is shown, on keyboard or pad.
     */
    public boolean showViewCommandInChat = true;
    /** The command shown/used to open the viewer (also registered as an alias). */
    public String viewCommand = "itemview";
    /** Underline the chat name to hint interactivity. */
    public boolean underlineChatName = true;
    /** Show the {@code ×quantity} suffix on the chat name. */
    public boolean showQuantityInChat = true;

    /** Shown in place of a link whose snapshot has expired. */
    public String expiredLabel = "&8[Item Link Expired]&r";
    /** Shown when a snapshot id resolves to nothing at all. */
    public String unavailableLabel = "&8[Item Unavailable]&r";
    /** Shown when the sender had nothing in hand. */
    public String noItemLabel = "&7[no item]&r";

    /** Clamps out-of-range values and restores blanked-out fields after an edit. */
    public ItemLinkConfig normalized() {
        ItemLinkConfig defaults = new ItemLinkConfig();
        if (tag == null || tag.isBlank()) {
            tag = defaults.tag;
        }
        if (viewCommand == null || viewCommand.isBlank()) {
            viewCommand = defaults.viewCommand;
        }
        if (expiredLabel == null || expiredLabel.isBlank()) {
            expiredLabel = defaults.expiredLabel;
        }
        if (unavailableLabel == null || unavailableLabel.isBlank()) {
            unavailableLabel = defaults.unavailableLabel;
        }
        if (noItemLabel == null || noItemLabel.isBlank()) {
            noItemLabel = defaults.noItemLabel;
        }
        maxTagsPerMessage = Math.max(1, Math.min(10, maxTagsPerMessage));
        return this;
    }
}
