package org.hyzionstudios.mysticessentials.platform.ui;

import java.util.List;
import java.util.Locale;

/**
 * Vanilla icons the mod's pages and HUD draw, as asset paths under
 * {@code Common/}, set on an {@code AssetImage} at runtime
 * ({@code cmd.set("#Icon.AssetPath", path)}). Nothing is copied into the mod:
 * the client already has every one of these.
 *
 * <p>Every path is a whole string literal on purpose. A wrong path fails
 * silently on the client (it draws the fallback or nothing), so the build's
 * {@code verifyVanillaIconPaths} task looks each literal up in the game's
 * Assets.zip; a path assembled from pieces would slip past it.</p>
 *
 * <p>File names with spaces ({@code Builder Tools}, {@code Scripted Blocks})
 * are avoided: they are awkward to type into a config and to pick from chat.</p>
 */
public final class VanillaIcons {

    // Flat category icons — the closest thing the game has to an icon font.
    public static final String BENCH = "Icons/ItemCategories/Bench.png";
    public static final String BLOCKS = "Icons/ItemCategories/Blocks.png";
    public static final String BEDS = "Icons/ItemCategories/Build-Beds.png";
    public static final String FURNITURE = "Icons/ItemCategories/Build-Furnitures.png";
    public static final String LIGHTS = "Icons/ItemCategories/Build-Lights.png";
    public static final String ROOFS = "Icons/ItemCategories/Build-Roofs.png";
    public static final String STAIRS = "Icons/ItemCategories/Build-Stairs.png";
    public static final String WALLS = "Icons/ItemCategories/Build-Walls.png";
    public static final String CLOTH = "Icons/ItemCategories/Cloth.png";
    public static final String CONTAINER = "Icons/ItemCategories/Container.png";
    public static final String DOORS = "Icons/ItemCategories/Doors.png";
    public static final String EDITOR = "Icons/ItemCategories/Editor.png";
    public static final String ARMOR = "Icons/ItemCategories/Items-Armor.png";
    public static final String CLOTHES = "Icons/ItemCategories/Items-Clothes.png";
    public static final String FOOD = "Icons/ItemCategories/Items-Food.png";
    public static final String INGREDIENTS = "Icons/ItemCategories/Items-Ingredients.png";
    public static final String POTION = "Icons/ItemCategories/Items-Potion.png";
    public static final String RECIPE = "Icons/ItemCategories/Items-Recipe.png";
    public static final String SHIELDS = "Icons/ItemCategories/Items-Shields.png";
    public static final String TOOLS = "Icons/ItemCategories/Items-Tools.png";
    public static final String TRAPS = "Icons/ItemCategories/Items-Traps.png";
    public static final String WEAPONS = "Icons/ItemCategories/Items-Weapons.png";
    public static final String ITEMS = "Icons/ItemCategories/Items.png";
    public static final String MACHINIMA = "Icons/ItemCategories/Machinima.png";
    public static final String METAL = "Icons/ItemCategories/Metal.png";
    public static final String MISC = "Icons/ItemCategories/Misc.png";
    public static final String FIRE = "Icons/ItemCategories/Natural-Fire.png";
    public static final String FLUID = "Icons/ItemCategories/Natural-Fluid.png";
    public static final String ORE = "Icons/ItemCategories/Natural-Ore.png";
    public static final String VEGETAL = "Icons/ItemCategories/Natural-Vegetal.png";
    public static final String NATURAL = "Icons/ItemCategories/Natural.png";
    public static final String PORTAL = "Icons/ItemCategories/Portal.png";
    public static final String SHELVES = "Icons/ItemCategories/Shelves.png";
    public static final String SIGNS = "Icons/ItemCategories/Signs.png";
    public static final String SOIL = "Icons/ItemCategories/Soil.png";
    public static final String TECHNICAL = "Icons/ItemCategories/TechnicalBlocks.png";
    public static final String WOOD = "Icons/ItemCategories/Wood.png";

    // World-map markers.
    public static final String MARKER_HOME = "UI/WorldMap/MapMarkers/Home.png";
    public static final String MARKER_SPAWN = "UI/WorldMap/MapMarkers/Spawn.png";
    public static final String MARKER_PLAYER = "UI/WorldMap/MapMarkers/Player.png";
    public static final String MARKER_WARP = "UI/WorldMap/MapMarkers/Warp.png";
    public static final String MARKER_PORTAL = "UI/WorldMap/MapMarkers/Portal.png";
    public static final String MARKER_COORDINATE = "UI/WorldMap/MapMarkers/Coordinate.png";
    public static final String MARKER_CAMPFIRE = "UI/WorldMap/MapMarkers/Campfire.png";
    public static final String MARKER_DEATH = "UI/WorldMap/MapMarkers/Death.png";
    public static final String MARKER_HUNT = "UI/WorldMap/MapMarkers/HuntSite.png";
    public static final String MARKER_GATEWAY = "UI/WorldMap/MapMarkers/Temple_Gateway.png";

    // Emotes.
    public static final String EMOTE_FIST_BUMP = "Icons/Emotes/FistBump@2x.png";
    public static final String EMOTE_WAVE = "Icons/Emotes/Wave@2x.png";
    public static final String EMOTE_SALUTE = "Icons/Emotes/Salute@2x.png";
    public static final String EMOTE_HIGH_FIVE = "Icons/Emotes/HighFive@2x.png";

    // Full-colour item renders: for things the flat set has no picture of.
    public static final String LETTER = "Icons/ItemsGenerated/Template_Lore_Note_Pages_A.png";
    public static final String JOURNAL = "Icons/ItemsGenerated/Template_Lore_Note_Goblin_Journal_Open.png";
    public static final String FEATHER = "Icons/ItemsGenerated/Ingredient_Feathers_Light.png";
    public static final String SCROLL = "Icons/ItemsGenerated/Deco_Scroll.png";
    public static final String CHEST = "Icons/ItemsGenerated/Furniture_Village_Chest_Small.png";
    public static final String CHEST_EPIC = "Icons/ItemsGenerated/Furniture_Dungeon_Chest_Epic.png";
    public static final String GIFT = "Icons/ItemsGenerated/Furniture_Christmas_Chest_Small_Red.png";
    public static final String TRASH = "Icons/ItemsGenerated/Deco_Trash.png";
    public static final String POSTER = "Icons/ItemsGenerated/Lobby_Wall_Poster01.png";
    public static final String PARCEL = "Icons/ItemsGenerated/Deco_Moving_Box.png";
    public static final String CRATE = "Icons/ItemsGenerated/Furniture_Village_Crate.png";
    public static final String MAP = "Icons/ItemsGenerated/Deco_Map.png";
    public static final String LANTERN = "Icons/ItemsGenerated/Deco_Lantern.png";
    public static final String ANVIL = "Icons/ItemsGenerated/Bench_Anvil.png";
    public static final String TROPHY = "Icons/ItemsGenerated/Deco_Orbis_Trophy.png";
    public static final String SPELLBOOK = "Icons/ItemsGenerated/Weapon_Spellbook_Grimoire_Brown.png";
    public static final String BOOKSHELF = "Icons/ItemsGenerated/Furniture_Ancient_Bookshelf.png";
    public static final String KEY = "Icons/ItemsGenerated/Coffer_Key.png";
    public static final String BANNER = "Icons/ItemsGenerated/Furniture_Human_Ruins_Banner.png";
    public static final String GEM_SAPPHIRE = "Icons/ItemsGenerated/Rock_Gem_Sapphire.png";
    public static final String GEM_EMERALD = "Icons/ItemsGenerated/Rock_Gem_Emerald.png";
    public static final String GEM_RUBY = "Icons/ItemsGenerated/Rock_Gem_Ruby.png";
    public static final String GEM_TOPAZ = "Icons/ItemsGenerated/Rock_Gem_Topaz.png";
    public static final String GEM_DIAMOND = "Icons/ItemsGenerated/Rock_Gem_Diamond.png";
    public static final String PORTAL_KEY = "Icons/ItemsGenerated/PortalKey_Taiga.png";

    /** One pickable icon: a short name to show and type, and its path. */
    public record Entry(String name, String path) {
    }

    /**
     * What an icon picker offers, in the order it shows them: the flat set
     * first (it matches the rail), then markers, emotes and item renders.
     */
    public static final List<Entry> PICKER = List.of(
            new Entry("Signs", SIGNS), new Entry("Container", CONTAINER), new Entry("Shields", SHIELDS),
            new Entry("Weapons", WEAPONS), new Entry("Armor", ARMOR), new Entry("Clothes", CLOTHES),
            new Entry("Tools", TOOLS), new Entry("Traps", TRAPS), new Entry("Potion", POTION),
            new Entry("Food", FOOD), new Entry("Ingredients", INGREDIENTS), new Entry("Recipe", RECIPE),
            new Entry("Ore", ORE), new Entry("Metal", METAL), new Entry("Fire", FIRE),
            new Entry("Fluid", FLUID), new Entry("Vegetal", VEGETAL), new Entry("Natural", NATURAL),
            new Entry("Soil", SOIL), new Entry("Wood", WOOD), new Entry("Blocks", BLOCKS),
            new Entry("Portal", PORTAL), new Entry("Bench", BENCH), new Entry("Beds", BEDS),
            new Entry("Furniture", FURNITURE), new Entry("Lights", LIGHTS), new Entry("Roofs", ROOFS),
            new Entry("Stairs", STAIRS), new Entry("Walls", WALLS), new Entry("Doors", DOORS),
            new Entry("Shelves", SHELVES), new Entry("Cloth", CLOTH), new Entry("Items", ITEMS),
            new Entry("Misc", MISC), new Entry("Editor", EDITOR), new Entry("Machinima", MACHINIMA),
            new Entry("Technical", TECHNICAL),
            new Entry("Home", MARKER_HOME), new Entry("Spawn", MARKER_SPAWN), new Entry("Player", MARKER_PLAYER),
            new Entry("Warp", MARKER_WARP), new Entry("Gateway", MARKER_GATEWAY), new Entry("Waypoint", MARKER_COORDINATE),
            new Entry("Campfire", MARKER_CAMPFIRE), new Entry("Hunt", MARKER_HUNT), new Entry("Death", MARKER_DEATH),
            new Entry("Fist Bump", EMOTE_FIST_BUMP), new Entry("Wave", EMOTE_WAVE), new Entry("Salute", EMOTE_SALUTE),
            new Entry("High Five", EMOTE_HIGH_FIVE),
            new Entry("Letter", LETTER), new Entry("Journal", JOURNAL), new Entry("Feather", FEATHER),
            new Entry("Scroll", SCROLL), new Entry("Map", MAP), new Entry("Poster", POSTER),
            new Entry("Banner", BANNER), new Entry("Chest", CHEST), new Entry("Epic Chest", CHEST_EPIC),
            new Entry("Gift", GIFT), new Entry("Parcel", PARCEL), new Entry("Crate", CRATE),
            new Entry("Trash", TRASH), new Entry("Lantern", LANTERN), new Entry("Anvil", ANVIL),
            new Entry("Trophy", TROPHY), new Entry("Spellbook", SPELLBOOK), new Entry("Bookshelf", BOOKSHELF),
            new Entry("Key", KEY), new Entry("Portal Key", PORTAL_KEY), new Entry("Sapphire", GEM_SAPPHIRE),
            new Entry("Emerald", GEM_EMERALD), new Entry("Ruby", GEM_RUBY), new Entry("Topaz", GEM_TOPAZ),
            new Entry("Diamond", GEM_DIAMOND));

    private VanillaIcons() {
    }

    /**
     * Resolves what a player or config typed: a picker name ("Portal",
     * "fist bump"), a server icon ({@code custom:castle}, from
     * {@code mods/MysticEssentials/icons/castle.png}) or a path. A path is
     * returned as given — servers with their own asset pack point at their own
     * art the same way.
     *
     * @return the path, or empty when {@code input} is blank or an unknown name
     */
    public static String resolve(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        String trimmed = input.trim();
        if (trimmed.regionMatches(true, 0, CustomIcons.PREFIX, 0, CustomIcons.PREFIX.length())) {
            return CustomIcons.resolve(trimmed);
        }
        if (trimmed.indexOf('/') >= 0 || trimmed.endsWith(".png")) {
            return trimmed;
        }
        String key = normalise(trimmed);
        for (Entry entry : HyzionIcons.PICKER) {
            if (normalise(entry.name()).equals(key)) {
                return entry.path();
            }
        }
        for (Entry entry : PICKER) {
            if (normalise(entry.name()).equals(key)) {
                return entry.path();
            }
        }
        return "";
    }

    /** The picker's full list: server icons, Hyzion's family, then the game's. */
    public static List<Entry> pickerWithCustom() {
        List<Entry> custom = CustomIcons.entries();
        List<Entry> all = new java.util.ArrayList<>(custom.size() + HyzionIcons.PICKER.size() + PICKER.size());
        all.addAll(custom);
        all.addAll(HyzionIcons.PICKER);
        all.addAll(PICKER);
        return all;
    }

    /** @return the picker name for a path, or empty when it is not one of ours */
    public static String nameOf(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String custom = CustomIcons.nameOf(path);
        if (!custom.isEmpty()) {
            return custom;
        }
        for (Entry entry : HyzionIcons.PICKER) {
            if (entry.path().equals(path)) {
                return entry.name();
            }
        }
        for (Entry entry : PICKER) {
            if (entry.path().equals(path)) {
                return entry.name();
            }
        }
        return "";
    }

    private static String normalise(String name) {
        return name.replace(" ", "").replace("-", "").replace("_", "").toLowerCase(Locale.ROOT);
    }
}
