package org.hyzionstudios.mysticessentials.modules.playervaults.model;

/**
 * Cosmetic, player-editable metadata for a vault card: display name, optional
 * description/lore, an accent colour, and an icon. None of this affects stored
 * items — resetting metadata never touches vault contents.
 *
 * <p>The icon is <b>copied as display data only</b> ({@link Icon}); the source
 * item is not consumed unless {@code consumeIconItem} is enabled in config.</p>
 */
public final class VaultMetadata {

    /** Custom name, or {@code null} to fall back to the default {@code "Vault #<n>"}. */
    public String name;
    /** Optional short lore shown on the card, or {@code null}. */
    public String description;
    /** Accent colour as {@code #RRGGBB}, or {@code null} for the theme default. */
    public String color;
    /** Copied icon item, or {@code null} for the default icon. */
    public Icon icon;

    public static final class Icon {
        public String itemId;
        public String displayName;
        public Integer customModelData;

        public Icon() {
        }

        public Icon(String itemId, String displayName, Integer customModelData) {
            this.itemId = itemId;
            this.displayName = displayName;
            this.customModelData = customModelData;
        }
    }

    public VaultMetadata copy() {
        VaultMetadata copy = new VaultMetadata();
        copy.name = name;
        copy.description = description;
        copy.color = color;
        if (icon != null) {
            copy.icon = new Icon(icon.itemId, icon.displayName, icon.customModelData);
        }
        return copy;
    }
}
