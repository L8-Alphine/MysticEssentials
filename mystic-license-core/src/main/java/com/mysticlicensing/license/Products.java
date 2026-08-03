package com.mysticlicensing.license;

import java.util.List;
import java.util.Map;

/**
 * Product and feature identifiers, mirroring {@code config/entitlements.json}
 * in the licensing portal.
 *
 * <p>These are constants for one reason: an unrecognised feature id does not
 * fail, it silently returns {@code false} forever. A typo would present as
 * "the feature I paid for never unlocks", diagnosed by reading two codebases.
 * Referring to {@code Products.Essentials.MODULE_CUSTOM_CONTENT} turns that
 * into a compile error.
 *
 * <p>The core library itself is product-agnostic - it never consults this
 * class. Each mod passes its own product id in. This is a convenience for the
 * mods, and a single place to update when the portal's entitlement config
 * changes.
 */
public final class Products {

    public static final String BOARDS = "mysticboards";
    public static final String HOLOS = "mysticholos";
    public static final String NAMETAGS = "mysticnametags";
    public static final String ESSENTIALS = "mysticessentials";
    public static final String GUILDS = "mysticguilds";

    private Products() {
    }

    /** MysticBoards. */
    public static final class Boards {
        public static final String SCOREBOARDS_MULTIPLE = "scoreboards.multiple";
        public static final String SCOREBOARDS_CONDITIONAL = "scoreboards.conditional";

        public static final List<String> ALL =
                List.of(SCOREBOARDS_MULTIPLE, SCOREBOARDS_CONDITIONAL);

        private Boards() {
        }
    }

    /** MysticHolos. */
    public static final class Holos {
        public static final String HOLOGRAMS_TEXT = "holograms.text";
        public static final String HOLOGRAMS_ITEMS = "holograms.items";
        public static final String HOLOGRAMS_IMAGES = "holograms.images";
        public static final String HOLOGRAMS_GIF = "holograms.gif";

        public static final List<String> ALL =
                List.of(HOLOGRAMS_TEXT, HOLOGRAMS_ITEMS, HOLOGRAMS_IMAGES, HOLOGRAMS_GIF);

        private Holos() {
        }
    }

    /** MysticNameTags. */
    public static final class NameTags {
        public static final String TAGS_BANNER = "tags.banner";

        public static final List<String> ALL = List.of(TAGS_BANNER);

        private NameTags() {
        }
    }

    /** MysticEssentials. */
    public static final class Essentials {
        public static final String EDITOR_KIT = "editor.kit";
        public static final String EDITOR_SHOP = "editor.shop";
        public static final String MAIL_SEND_MONEY = "mail.send.money";

        /**
         * Gates the CustomGUIs and CustomDialogs module.
         *
         * <p>Follows the {@code module.*} convention MysticGuilds already uses
         * for whole-module grants. This id must also be listed under
         * {@code mysticessentials} in the portal's {@code entitlements.json};
         * until it is, no license can be issued that grants it and the module
         * stays off everywhere.
         */
        public static final String MODULE_CUSTOM_CONTENT = "module.customcontent";

        public static final List<String> ALL =
                List.of(EDITOR_KIT, EDITOR_SHOP, MAIL_SEND_MONEY, MODULE_CUSTOM_CONTENT);

        private Essentials() {
        }
    }

    /** MysticGuilds. */
    public static final class Guilds {
        public static final String MODULE_CLAIMS = "module.claims";
        public static final String MODULE_CIVIL = "module.civil";
        public static final String MODULE_PLOTS = "module.plots";
        public static final String MODULE_WARS = "module.wars";
        public static final String MODULE_NPC = "module.npc";
        public static final String MODULE_NPC_GUARDS = "module.npc.guards";

        public static final List<String> ALL = List.of(
                MODULE_CLAIMS, MODULE_CIVIL, MODULE_PLOTS,
                MODULE_WARS, MODULE_NPC, MODULE_NPC_GUARDS);

        private Guilds() {
        }
    }

    /** Every known product to its feature ids, for admin commands and tests. */
    public static final Map<String, List<String>> FEATURES = Map.of(
            BOARDS, Boards.ALL,
            HOLOS, Holos.ALL,
            NAMETAGS, NameTags.ALL,
            ESSENTIALS, Essentials.ALL,
            GUILDS, Guilds.ALL);
}
