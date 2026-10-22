package org.hyzionstudios.mysticessentials.modules.lootbox.model;

import java.util.ArrayList;
import java.util.List;

/**
 * One key definition as authored in {@code modules/lootbox/keys/*.json}. A key is
 * an ordinary base item carrying a signed marker; its name and lore are cosmetic
 * and never used to validate it.
 */
public final class KeyDraft {

    /** {@code namespace:name}, both {@code [a-z0-9_]+}; the namespace defaults to {@code mystic}. */
    public String id;
    /** Any registered item; the key looks like it. */
    public String baseItemId;
    /** MysticText markup, e.g. {@code <#E7B84D>&lAvalon Royal Key}. */
    public String name;
    /** One entry per line, MysticText markup; joined into the item's single description. */
    public List<String> lore = new ArrayList<>();
    /** Openings one physical key allows before it is spent. */
    public int uses = 1;
    /** Days after issue a physical key stops working; 0 = never. */
    public long expiresAfterDays = 0;
    /**
     * Opt-in (P2-K): issue key items as stacks that share one signed batch marker
     * ({@link KeyBatch}) instead of one serial each. Each item opens once, whatever
     * {@link #uses} says. Weaker against duplication exploits; see {@link KeyBatch}.
     * Off by default, which leaves serialized keys exactly as they were.
     */
    public boolean stackable = false;
    /**
     * {@code METADATA_TAG} (default: the base item with a marker) or
     * {@code REGISTERED_CUSTOM_ASSET} (P3-WP8: a generated child item of the base,
     * only while {@code keys.nativeKeyItems} is on and the asset has loaded). Left
     * unset, a key keeps its earlier content hash.
     */
    public String identityMode;

    /** A published key definition ({@code lootbox_keydef/<id>}). */
    public static final class Published {
        public int schema = 1;
        public int version;
        public KeyDraft spec;
        public String publishedBy;
        public long publishedAt;
    }
}
