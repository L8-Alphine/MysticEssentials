package org.hyzionstudios.mysticessentials.platform.ui;

import java.util.List;

/**
 * Hyzion's own icon family, shipped with the mod under
 * {@code Common/UI/Custom/MysticEssentials/Icons/Hyzion/} and drawn as one
 * visual system across the rail, the menu and the feature pages.
 *
 * <p>The shipped files are 64 px, built from the 128 px masters in
 * {@code art/icons/hyzion/} by {@code scripts/optimize-icons.py}: the UI shows
 * them at 20-44 px, so 64 px carries every detail that survives while a third
 * of the bytes reach each client. Edit a master, then re-run the script.</p>
 *
 * <p>Every path is a whole literal so the build's {@code verifyVanillaIconPaths}
 * check can find it (it also accepts files the mod ships).</p>
 */
public final class HyzionIcons {

    public static final String ADVENTURE = "UI/Custom/MysticEssentials/Icons/Hyzion/Adventure.png";
    public static final String ANNOUNCEMENT = "UI/Custom/MysticEssentials/Icons/Hyzion/Announcement.png";
    public static final String ARCHIVE = "UI/Custom/MysticEssentials/Icons/Hyzion/Archive.png";
    public static final String ARMOR = "UI/Custom/MysticEssentials/Icons/Hyzion/Armor.png";
    public static final String ATTACHMENT = "UI/Custom/MysticEssentials/Icons/Hyzion/Attachment.png";
    public static final String CHANNELS = "UI/Custom/MysticEssentials/Icons/Hyzion/Channels.png";
    public static final String COMMUNITY = "UI/Custom/MysticEssentials/Icons/Hyzion/Community.png";
    public static final String COMPASS = "UI/Custom/MysticEssentials/Icons/Hyzion/Compass.png";
    public static final String COMPOSE = "UI/Custom/MysticEssentials/Icons/Hyzion/Compose.png";
    public static final String ECONOMY = "UI/Custom/MysticEssentials/Icons/Hyzion/Economy.png";
    public static final String GENERATION = "UI/Custom/MysticEssentials/Icons/Hyzion/Generation.png";
    public static final String GUILD = "UI/Custom/MysticEssentials/Icons/Hyzion/Guild.png";
    public static final String HOME = "UI/Custom/MysticEssentials/Icons/Hyzion/Home.png";
    public static final String KEY = "UI/Custom/MysticEssentials/Icons/Hyzion/Key.png";
    public static final String LOCK = "UI/Custom/MysticEssentials/Icons/Hyzion/Lock.png";
    public static final String MAIL = "UI/Custom/MysticEssentials/Icons/Hyzion/Mail.png";
    public static final String MENU = "UI/Custom/MysticEssentials/Icons/Hyzion/Menu.png";
    public static final String NAMETAG = "UI/Custom/MysticEssentials/Icons/Hyzion/Nametag.png";
    public static final String NICKNAME = "UI/Custom/MysticEssentials/Icons/Hyzion/Nickname.png";
    public static final String NOTIFICATIONS = "UI/Custom/MysticEssentials/Icons/Hyzion/Notifications.png";
    public static final String PATCH_NOTES = "UI/Custom/MysticEssentials/Icons/Hyzion/PatchNotes.png";
    public static final String PLAYER = "UI/Custom/MysticEssentials/Icons/Hyzion/Player.png";
    public static final String PORTAL = "UI/Custom/MysticEssentials/Icons/Hyzion/Portal.png";
    public static final String RAIL = "UI/Custom/MysticEssentials/Icons/Hyzion/Rail.png";
    public static final String SENT = "UI/Custom/MysticEssentials/Icons/Hyzion/Sent.png";
    public static final String SERVERS = "UI/Custom/MysticEssentials/Icons/Hyzion/Servers.png";
    public static final String SERVICES = "UI/Custom/MysticEssentials/Icons/Hyzion/Services.png";
    public static final String SETTINGS = "UI/Custom/MysticEssentials/Icons/Hyzion/Settings.png";
    public static final String SHORTCUTS = "UI/Custom/MysticEssentials/Icons/Hyzion/Shortcuts.png";
    public static final String SPAWN = "UI/Custom/MysticEssentials/Icons/Hyzion/Spawn.png";
    public static final String STAFF = "UI/Custom/MysticEssentials/Icons/Hyzion/Staff.png";
    public static final String TRASH = "UI/Custom/MysticEssentials/Icons/Hyzion/Trash.png";
    public static final String VAULT = "UI/Custom/MysticEssentials/Icons/Hyzion/Vault.png";
    public static final String WARP = "UI/Custom/MysticEssentials/Icons/Hyzion/Warp.png";
    public static final String WIKI = "UI/Custom/MysticEssentials/Icons/Hyzion/Wiki.png";

    /** Entries exposed to the rail editor without colliding with vanilla names. */
    public static final List<VanillaIcons.Entry> PICKER = List.of(
            entry("Adventure", ADVENTURE), entry("Announcement", ANNOUNCEMENT),
            entry("Archive", ARCHIVE), entry("Armor", ARMOR), entry("Attachment", ATTACHMENT),
            entry("Channels", CHANNELS), entry("Community", COMMUNITY), entry("Compass", COMPASS),
            entry("Compose", COMPOSE), entry("Economy", ECONOMY), entry("Generation", GENERATION),
            entry("Guild", GUILD), entry("Home", HOME), entry("Key", KEY), entry("Lock", LOCK),
            entry("Mail", MAIL), entry("Menu", MENU), entry("Nametag", NAMETAG),
            entry("Nickname", NICKNAME), entry("Notifications", NOTIFICATIONS),
            entry("Patch Notes", PATCH_NOTES), entry("Player", PLAYER), entry("Portal", PORTAL),
            entry("Rail", RAIL), entry("Sent", SENT), entry("Servers", SERVERS),
            entry("Services", SERVICES), entry("Settings", SETTINGS), entry("Shortcuts", SHORTCUTS),
            entry("Spawn", SPAWN), entry("Staff", STAFF), entry("Trash", TRASH),
            entry("Vault", VAULT), entry("Warp", WARP), entry("Wiki", WIKI));

    private HyzionIcons() {
    }

    private static VanillaIcons.Entry entry(String name, String path) {
        return new VanillaIcons.Entry("Hyzion " + name, path);
    }
}
