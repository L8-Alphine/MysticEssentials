package org.hyzionstudios.mysticessentials.modules.customcontent;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for the optional CustomGUIs and CustomDialogs module.
 */
public final class CustomContentConfig {

    public int configVersion = 1;

    /** Enables the in-game CustomDialogs builder and compatible JSON export. */
    public boolean customDialogsEnabled = true;
    /** Enables declarative HTML GUI loading and rendering. */
    public boolean customGuisEnabled = true;
    /** Imports supported legacy CustomDialogs/CustomGUIs data once when available. */
    public boolean importStandaloneData = true;
    /** Registers data-custom-command aliases found on GUI documents. */
    public boolean registerGuiAliasCommands = true;
    /**
     * Maximum elements compiled from one GUI document. Layout documents nest,
     * so this counts every panel, label and button in the tree — not rows.
     */
    public int maxGuiElements = 400;

    /** Enables downloaded half-body textures for v2 {@code player-portrait} nodes. */
    public boolean playerPortraitsEnabled = true;
    /** HTTP(S) endpoint; {@code {username}} is URL encoded before substitution. */
    public String playerPortraitApiTemplate = "https://hytale.photo/skin/halfbody.png?user={username}";
    /** Disk-cache lifetime. Zero keeps cached portraits until manually removed. */
    public int playerPortraitCacheHours = 24;

    public String command = "customcontent";
    public List<String> aliases = new ArrayList<>(List.of("customtools"));
    public String customDialogsCommand = "customdialogs";
    public String customGuisCommand = "customguis";

    /** Optional compatibility plugin identifier used by the reflection bridge. */
    public String compatibilityPluginGroup = "net.evilcraft";
    public String compatibilityPluginName = "QuestLines";
    /** Dialog export folder relative to the server working directory. */
    public String dialogExportDirectory = "mods/QuestLines";
}
