package org.hyzionstudios.mysticessentials.api.item;


/**
 * The leaf value types of the ItemView model: statistics, modifiers,
 * requirements, properties, durability, binding, source, and raw metadata.
 *
 * <p>They are grouped in one file because each is a small immutable record with
 * no behaviour of its own, and they are only ever used together. Every one of
 * them treats a missing field as {@code null} and a present-but-odd-looking
 * field as real data.</p>
 */
public final class ItemViewEntries {

    private ItemViewEntries() {
    }

    /**
     * One labelled statistic, e.g. {@code Basic Damage} / {@code 38.0}.
     *
     * @param label   display label, already prettified
     * @param value   display value, already formatted
     * @param ordinal sort hint within its section; lower sorts first
     */
    public record ItemStatEntry(RichText label, RichText value, int ordinal) {

        public static ItemStatEntry of(String label, String value) {
            return new ItemStatEntry(RichText.plain(label), RichText.plain(value), 0);
        }

        public static ItemStatEntry of(String label, String value, int ordinal) {
            return new ItemStatEntry(RichText.plain(label), RichText.plain(value), ordinal);
        }
    }

    /**
     * One affix / enchantment / attribute modifier, e.g. {@code +8.1 Haste}.
     *
     * @param name       what is being modified ({@code Haste})
     * @param amount     the signed magnitude
     * @param percentage whether {@code amount} is a percentage rather than a flat value
     * @param source     where it came from (affix name, enchantment, set bonus), or {@code null}
     */
    public record ItemModifierEntry(RichText name, double amount, boolean percentage, String source) {

        public static ItemModifierEntry of(String name, double amount) {
            return new ItemModifierEntry(RichText.plain(name), amount, false, null);
        }

        /** The conventional rendering: {@code +8.1 Haste}, {@code -4.8 Defense}. */
        public String display() {
            return ItemNames.signedNumber(amount) + (percentage ? "%" : "") + " " + name.plain();
        }
    }

    /**
     * One requirement or restriction, e.g. {@code Required Level} / {@code 60}.
     *
     * @param label    what is required
     * @param value    the required value
     * @param satisfied {@code TRUE}/{@code FALSE} when the viewer's eligibility is
     *                  known, {@code null} when it is not evaluated
     */
    public record ItemRequirementEntry(RichText label, RichText value, Boolean satisfied) {

        public static ItemRequirementEntry of(String label, String value) {
            return new ItemRequirementEntry(RichText.plain(label), RichText.plain(value), null);
        }
    }

    /** A generic labelled property (binding, tradability, stack limit, …). */
    public record ItemPropertyEntry(RichText label, RichText value) {

        public static ItemPropertyEntry of(String label, String value) {
            return new ItemPropertyEntry(RichText.plain(label), RichText.plain(value));
        }
    }

    /**
     * A raw key/value pair for the collapsed Technical Information section.
     * Values here are always escaped literals — this section exists to show
     * unrecognised data safely, so it must never interpret what it displays.
     */
    public record ItemMetadataEntry(String key, String value) {

        public static ItemMetadataEntry of(String key, Object value) {
            return new ItemMetadataEntry(key == null ? "" : key,
                    value == null ? "" : String.valueOf(value));
        }
    }

    /**
     * Durability state.
     *
     * @param current     remaining durability
     * @param maximum     maximum durability
     * @param unbreakable whether the item cannot be damaged
     */
    public record ItemDurabilityData(double current, double maximum, boolean unbreakable) {

        /** Whether there is a meaningful bar to draw (breakable with a non-zero maximum). */
        public boolean isDisplayable() {
            return !unbreakable && maximum > 0;
        }

        public String display() {
            return ItemNames.number(current) + " / " + ItemNames.number(maximum);
        }

        /** Remaining fraction in {@code [0,1]}, or {@code 1} when there is no maximum. */
        public double fraction() {
            return maximum > 0 ? Math.max(0, Math.min(1, current / maximum)) : 1;
        }
    }

    /**
     * Binding and trade restrictions.
     *
     * @param bound       whether the item is bound to a player
     * @param bindingType free-form binding description ({@code Bind on Pickup}), or {@code null}
     * @param boundTo     the owning player's name, or {@code null}
     * @param tradable    whether the item may be traded
     * @param droppable   whether the item may be dropped or destroyed
     */
    public record ItemBindingData(boolean bound, String bindingType, String boundTo,
            boolean tradable, boolean droppable) {

        public static ItemBindingData unrestricted() {
            return new ItemBindingData(false, null, null, true, true);
        }

        public boolean isDisplayable() {
            return bound || !tradable || !droppable || bindingType != null;
        }
    }

    /**
     * Provenance of the item definition.
     *
     * @param namespace  the item id's namespace ({@code custom_mod}), or {@code null}
     * @param assetPack  the asset pack or mod that registered it, or {@code null}
     * @param providerId the ItemView provider that contributed the data, or {@code null}
     */
    public record ItemSourceData(String namespace, String assetPack, String providerId) {

        public boolean isDisplayable() {
            return namespace != null || assetPack != null || providerId != null;
        }
    }
}
