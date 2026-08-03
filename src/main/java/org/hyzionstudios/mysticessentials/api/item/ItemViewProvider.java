package org.hyzionstudios.mysticessentials.api.item;

import com.hypixel.hytale.server.core.inventory.ItemStack;

/**
 * Lets another mod contribute structured data about its own items to the shared
 * ItemView.
 *
 * <p>A provider supplies <b>data</b>. It does not lay out, colour, localize, or
 * paginate anything — Mystic Essentials keeps ownership of UI consistency,
 * rich-text sanitization, section ordering, permission checks, and fallback
 * rendering, so that items from a dozen mods still look like one system.</p>
 *
 * <p>Providers run after the generic native inspection, in ascending
 * {@link #getPriority() priority}, each writing into the same
 * {@link ItemViewBuilder}. Scalar setters overwrite, collection methods append,
 * so a higher-priority provider corrects what it knows better and leaves the
 * rest intact.</p>
 *
 * <p><b>Failure is contained.</b> An exception thrown from
 * {@link #populate} is logged and swallowed; inspection continues with the other
 * providers and the generic fallback, and the player still gets a working
 * ItemView. Providers should not rely on this — but the UI must never fail to
 * open because a third-party mod threw.</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * public final class ExampleRpgItemProvider implements ItemViewProvider {
 *     public String getProviderId() { return "example_rpg"; }
 *     public int getPriority() { return 100; }
 *
 *     public boolean supports(ItemStack item, ItemInspectionContext context) {
 *         return "example_rpg".equals(ItemNames.namespaceOf(item.getItemId()));
 *     }
 *
 *     public void populate(ItemStack item, ItemInspectionContext context, ItemViewBuilder builder) {
 *         builder.quality("example_rpg:null")     // a quality literally named "Null"
 *                .rarity("example_rpg:epic")
 *                .tier("example_rpg:maelstrom")
 *                .category("Weapon")
 *                .subcategory("One-Handed")
 *                .description("A blur of edges you can barely follow.")
 *                .addModifier("haste", 8.1)
 *                .addModifier("strength", 8.3)
 *                .addModifier("precision", 6.1)
 *                .addModifier("defense", -4.8);
 *     }
 * }
 * }</pre>
 */
public interface ItemViewProvider {

    /** Stable identifier, conventionally the owning mod's namespace. */
    String getProviderId();

    /**
     * Relative ordering among providers. Higher runs later and therefore wins on
     * conflicting scalar fields. The generic native inspection is effectively
     * priority {@link Integer#MIN_VALUE}.
     */
    default int getPriority() {
        return 0;
    }

    /**
     * Whether this provider has anything to say about {@code item}. Called for
     * every inspection, so keep it cheap — an id or namespace test, not a
     * database lookup.
     */
    boolean supports(ItemStack item, ItemInspectionContext context);

    /**
     * Contributes data for {@code item}. Called on the world thread that owns the
     * item, so component reads are safe here but blocking calls are not.
     */
    void populate(ItemStack item, ItemInspectionContext context, ItemViewBuilder builder);
}
