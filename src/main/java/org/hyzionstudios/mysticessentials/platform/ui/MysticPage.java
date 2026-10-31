package org.hyzionstudios.mysticessentials.platform.ui;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.message.MysticText;
import org.hyzionstudios.mysticessentials.core.util.Json;

import com.google.gson.JsonObject;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Base class for Mystic Essentials custom UI pages. Collects the plumbing every
 * page needs: the Core handle, the viewing player, page re-opening (the refresh
 * pattern — Hytale pages are rebuilt by opening a fresh page instance), and
 * tolerant parsing of the JSON payload delivered to {@code handleDataEvent}.
 *
 * <p>List UIs follow the verified builtin pattern (see {@code WarpListPage} in
 * the server jar): a page {@code .ui} declares an empty scrolling container and
 * a separate row-template {@code .ui} file is appended once per entry with
 * {@code cmd.append("#List", "MysticEssentials/Row.ui")}; the appended rows are
 * then addressed by index — {@code cmd.set("#List[0] #Name.TextSpans", ...)}.</p>
 */
public abstract class MysticPage extends CustomUIPage {

    /**
     * Runtime text colours, keyed by element id. A {@code TextSpans} value carries
     * its own colour and wins over the label style in the {@code .ui}, so this
     * table is where the theme's palette reaches text the server writes. The
     * tokens mirror the Hyzion palette in {@code MysticTheme.ui}: display and
     * value text {@code #F3EBDD} (text.primary), row names {@code #F8F3EA},
     * meta {@code #AEC1D5} (text.secondary), eyebrows and counts
     * {@code #93A8C0}, captions {@code #6E829B}, gold {@code #D9B572} for
     * "this one". Text that carries information reads at 4.5:1 or better on
     * the window background ({@code #16212F}); captions do not, so LootBox
     * hints and notes use the muted colour instead (P2-L).
     */
    private static final String DEFAULT_UI_TEXT_COLOR = "#F3EBDD";
    private static final String TEXT_HI = "#F8F3EA";
    private static final String TEXT_BODY = "#F3EBDD";
    private static final String TEXT_MUTED = "#AEC1D5";
    private static final String TEXT_DIM = "#93A8C0";
    private static final String TEXT_FAINT = "#6E829B";
    private static final String GOLD = "#D9B572";
    private static final String GOLD_SOFT = "#E7CE9B";
    private static final String WARN = "#E8BC63";
    private static final String DANGER = "#E48786";
    /** {@code @ColorButtonText} in the game's Common.ui. */
    private static final String BUTTON_TEXT = "#BFCDD5";
    private static final Pattern TEXT_TARGET =
            Pattern.compile("#([A-Za-z0-9_]+)\\.TextSpans$");
    private static final Map<String, String> UI_TEXT_COLORS = Map.ofEntries(
            // Display names (Secondary face) and key/value text
            Map.entry("AdminHeading", TEXT_BODY),
            Map.entry("ChannelName", TEXT_BODY),
            Map.entry("ComposeTitle", TEXT_BODY),
            Map.entry("InfoName", TEXT_BODY),
            Map.entry("ItemName", TEXT_BODY),
            Map.entry("KitName", TEXT_BODY),
            Map.entry("ManageChannelName", TEXT_BODY),
            Map.entry("MName", TEXT_BODY),
            Map.entry("PageTitle", TEXT_BODY),
            Map.entry("PatchTitle", TEXT_BODY),
            Map.entry("PortalHeading", TEXT_BODY),
            Map.entry("PreviewTitle", TEXT_BODY),
            Map.entry("ProfileName", TEXT_BODY),
            Map.entry("PwarpName", TEXT_BODY),
            Map.entry("ReadSubject", TEXT_BODY),
            Map.entry("RosterChannel", TEXT_BODY),
            Map.entry("SelectedHome", TEXT_BODY),
            Map.entry("SelectedName", TEXT_BODY),
            Map.entry("WarpName", TEXT_BODY),
            Map.entry("CurrentNick", TEXT_BODY),
            Map.entry("Cost", TEXT_BODY),
            Map.entry("Details", TEXT_MUTED),
            Map.entry("Items", TEXT_MUTED),
            Map.entry("Line", TEXT_BODY),
            Map.entry("PageCount", TEXT_DIM),
            Map.entry("ButtonCount", TEXT_DIM),
            Map.entry("PageHeading", GOLD),
            Map.entry("PageLabel", TEXT_BODY),
            Map.entry("Range", TEXT_BODY),
            Map.entry("ReadBody", TEXT_BODY),
            Map.entry("SetAllowance", TEXT_BODY),
            Map.entry("SetCustom", TEXT_BODY),
            Map.entry("SetUsage", TEXT_BODY),
            Map.entry("Timing", TEXT_BODY),
            Map.entry("World", TEXT_BODY),
            // Row titles
            Map.entry("ButtonLabel", TEXT_BODY),
            Map.entry("From", TEXT_HI),
            Map.entry("GuiTitle", TEXT_HI),
            Map.entry("HeaderTitle", TEXT_HI),
            Map.entry("ListTitle", GOLD),
            Map.entry("Name", TEXT_HI),
            Map.entry("Title", TEXT_HI),
            // Meta, descriptions and secondary copy
            Map.entry("Date", TEXT_MUTED),
            Map.entry("DraftLabel", TEXT_DIM),
            Map.entry("FooterCounts", TEXT_MUTED),
            Map.entry("Have", TEXT_MUTED),
            Map.entry("ItemSubtitle", TEXT_MUTED),
            Map.entry("KitDescription", TEXT_MUTED),
            Map.entry("ManageExpiry", TEXT_MUTED),
            Map.entry("Meta", TEXT_MUTED),
            Map.entry("Number", TEXT_MUTED),
            Map.entry("PageInfo", TEXT_MUTED),
            Map.entry("PageSubtitle", TEXT_MUTED),
            Map.entry("PatchMeta", TEXT_MUTED),
            Map.entry("PatchSummary", TEXT_BODY),
            Map.entry("Preview", TEXT_MUTED),
            Map.entry("PreviewDescription", TEXT_MUTED),
            Map.entry("PwarpDescription", TEXT_MUTED),
            Map.entry("ReadDate", TEXT_MUTED),
            Map.entry("ReadFrom", TEXT_MUTED),
            Map.entry("Status", TEXT_MUTED),
            Map.entry("Sub", TEXT_MUTED),
            Map.entry("Subtitle", TEXT_DIM),
            Map.entry("WarpDescription", TEXT_MUTED),
            // Empty states, hints and captions
            Map.entry("AdminHint", TEXT_FAINT),
            Map.entry("ContentEmpty", TEXT_DIM),
            Map.entry("ItemId", TEXT_FAINT),
            Map.entry("ListEmpty", TEXT_DIM),
            Map.entry("NoItemsLabel", TEXT_DIM),
            Map.entry("PwarpLimit", TEXT_FAINT),
            Map.entry("RosterMore", TEXT_FAINT),
            Map.entry("SetHint", TEXT_FAINT),
            Map.entry("ShareInfo", TEXT_FAINT),
            Map.entry("PortalMeta", TEXT_FAINT),
            // Title-bar metadata
            Map.entry("HeaderInfo", TEXT_DIM),
            Map.entry("HomeCount", TEXT_DIM),
            Map.entry("KitCount", TEXT_DIM),
            Map.entry("ManagerLimit", TEXT_DIM),
            Map.entry("TargetName", TEXT_DIM),
            Map.entry("VaultCount", TEXT_DIM),
            Map.entry("ViewerSubtitle", TEXT_DIM),
            // Gold: the current thing, rewards, unread markers
            Map.entry("AudienceLabel", GOLD_SOFT),
            Map.entry("Cause", GOLD_SOFT),
            Map.entry("CurrentChannelLabel", GOLD_SOFT),
            Map.entry("InfoType", GOLD_SOFT),
            Map.entry("MTags", GOLD_SOFT),
            Map.entry("Reward", GOLD_SOFT),
            Map.entry("RewardHeader", GOLD),
            // LootBox: the odds column is gold so the chance reads before the name.
            Map.entry("Odds", GOLD_SOFT),
            Map.entry("OneIn", TEXT_MUTED),
            Map.entry("Rules", TEXT_MUTED),
            Map.entry("KeyBalance", GOLD_SOFT),
            Map.entry("OddsFooter", TEXT_MUTED),
            Map.entry("Blocker", GOLD_SOFT),
            Map.entry("Consumes", GOLD_SOFT),
            // LootBox Phase 3: personal-odds notes and the section rows inside a reward list.
            Map.entry("Personal", GOLD_SOFT),
            Map.entry("SectionText", GOLD),
            Map.entry("Schedule", GOLD_SOFT),
            // LootBox screens track.
            // Simulation: the chance column is gold like the preview's odds.
            Map.entry("SimHint", TEXT_MUTED),
            Map.entry("SimWarning", WARN),
            Map.entry("SimSummary", TEXT_MUTED),
            Map.entry("SimName", TEXT_HI),
            Map.entry("SimChance", GOLD_SOFT),
            Map.entry("SimObserved", TEXT_BODY),
            Map.entry("SimCount", TEXT_MUTED),
            Map.entry("SimGap", TEXT_BODY),
            // Audit history.
            Map.entry("AuditWhen", TEXT_MUTED),
            Map.entry("AuditAction", GOLD_SOFT),
            Map.entry("AuditTarget", TEXT_HI),
            Map.entry("AuditDetail", TEXT_MUTED),
            Map.entry("AuditWho", TEXT_MUTED),
            Map.entry("AuditPageInfo", TEXT_DIM),
            Map.entry("AuditFullHead", TEXT_BODY),
            Map.entry("AuditFullWho", TEXT_MUTED),
            Map.entry("AuditFullDetail", TEXT_BODY),
            // Template library.
            Map.entry("TplName", TEXT_HI),
            Map.entry("TplMeta", TEXT_MUTED),
            Map.entry("TplSide", TEXT_MUTED),
            Map.entry("TplDetail", TEXT_BODY),
            Map.entry("TplNoTarget", TEXT_MUTED),
            Map.entry("ResultName", TEXT_HI),
            Map.entry("ResultMeta", TEXT_MUTED),
            Map.entry("RosterCounts", GOLD_SOFT),
            Map.entry("State", GOLD_SOFT),
            // LootBox crates track.
            Map.entry("CrateWorld", TEXT_BODY),
            Map.entry("CratePosition", TEXT_BODY),
            Map.entry("CrateBlock", TEXT_BODY),
            Map.entry("CrateRotation", TEXT_BODY),
            Map.entry("CrateBlockStatus", TEXT_BODY),
            Map.entry("CrateSelectHint", TEXT_MUTED),
            Map.entry("CrateIdFixed", TEXT_BODY),
            Map.entry("CrateVerdict", TEXT_BODY),
            Map.entry("CratePoolRevision", TEXT_BODY),
            Map.entry("CratePoolRewards", TEXT_BODY),
            Map.entry("CratePoolKeys", TEXT_BODY),
            Map.entry("CrateBehaviour", TEXT_HI),
            Map.entry("CrateBreakState", TEXT_BODY),
            Map.entry("CrateDamageState", TEXT_BODY),
            Map.entry("CrateRepairReason", TEXT_HI),
            Map.entry("CrateRepairStatus", TEXT_BODY),
            Map.entry("CrateNameplateState", TEXT_BODY),
            // LootBox editor kit: picker, confirmation, tab bar. A server-written
            // button label takes the game's button text colour so it matches a
            // static one.
            Map.entry("PickerTarget", GOLD),
            Map.entry("CurrentItem", TEXT_MUTED),
            Map.entry("TabLabel", TEXT_BODY),
            Map.entry("ConfirmButton", BUTTON_TEXT),
            Map.entry("DangerButton", BUTTON_TEXT),
            // LootBox Manager: the New Pool / Clone form and the selection bar.
            Map.entry("FormTitle", TEXT_HI),
            Map.entry("FormHint", TEXT_MUTED),
            Map.entry("SelectedMeta", TEXT_MUTED),
            // LootBox pool editor.
            Map.entry("NamePreview", TEXT_HI),
            Map.entry("ModeHint", TEXT_MUTED),
            Map.entry("RewardsSum", TEXT_MUTED),
            Map.entry("OddsFieldLabel", TEXT_DIM),
            Map.entry("RewardChance", GOLD_SOFT),
            Map.entry("EconomyNote", TEXT_MUTED),
            Map.entry("PermissionNote", TEXT_MUTED),
            Map.entry("CommandNote", TEXT_MUTED),
            Map.entry("RewardLock", WARN),
            // LootBox key editor.
            Map.entry("PreviewName", TEXT_HI),
            Map.entry("PreviewMeta", TEXT_MUTED),
            Map.entry("OpensLine", TEXT_MUTED),
            // LootBox keys track.
            Map.entry("PlayersNote", TEXT_MUTED),
            Map.entry("KindHint", TEXT_MUTED),
            Map.entry("GiveSummary", TEXT_MUTED),
            Map.entry("StackHint", TEXT_MUTED),
            Map.entry("KeysHint", TEXT_MUTED),
            Map.entry("RulesNote", TEXT_MUTED),
            Map.entry("Unread", "#E7CE9B"),
            Map.entry("Secondary", DANGER));

    /**
     * The most rows one list page draws in a single build.
     *
     * <p>A page's whole command array travels in one {@code CustomPage}
     * packet: unlike the newer {@code UISurface}, which chunks at 64 commands,
     * nothing splits it. A few hundred rows at three or four commands each is
     * therefore one very large packet, and on 0.7.5
     * {@code QuicheChannel#allocateLargePacketBuffer} logs a warning and
     * allocates an oversized buffer for precisely that shape.</p>
     *
     * <p>Pages that can exceed this cap must say so on screen — see
     * {@link #rowCount}. Hiding rows quietly is worse than a slow page.</p>
     */
    public static final int MAX_LIST_ROWS = 150;

    protected final MysticCore core;
    protected final PlayerRef player;

    protected MysticPage(MysticCore core, PlayerRef player, CustomPageLifetime lifetime) {
        super(player, lifetime);
        this.core = core;
        this.player = player;
    }

    /**
     * Converts a runtime UI value to a fully-coloured {@link Message} tree for
     * assignment to {@code TextSpans}. String values support all
     * {@link MysticText} markup; existing messages retain their formatting and
     * receive an explicit fallback colour on otherwise uncoloured spans.
     */
    public static Message uiText(String target, Object value) {
        String defaultColor = defaultUiTextColor(target);
        if (value instanceof Message message) {
            applyDefaultColor(message, defaultColor);
            return message;
        }
        return MysticText.parse(value == null ? "" : String.valueOf(value), defaultColor);
    }

    /** The colour {@link #uiText} gives server-written text in the element with {@code id} (no {@code #}). */
    public static String uiTextColor(String id) {
        return UI_TEXT_COLORS.getOrDefault(id, DEFAULT_UI_TEXT_COLOR);
    }

    private static String defaultUiTextColor(String target) {
        if (target != null) {
            Matcher matcher = TEXT_TARGET.matcher(target);
            if (matcher.find()) {
                return UI_TEXT_COLORS.getOrDefault(matcher.group(1), DEFAULT_UI_TEXT_COLOR);
            }
        }
        return DEFAULT_UI_TEXT_COLOR;
    }

    private static void applyDefaultColor(Message message, String defaultColor) {
        if (message.getColor() == null) {
            message.color(defaultColor);
        }
        for (Message child : message.getChildren()) {
            applyDefaultColor(child, defaultColor);
        }
        if (message.getFormattedMessage().messageParams != null) {
            message.getFormattedMessage().messageParams.values().forEach(
                    parameter -> applyDefaultColor(new Message(parameter), defaultColor));
        }
    }

    /** @return at most {@link #MAX_LIST_ROWS} of {@code rows}, for drawing. */
    protected static <T> List<T> capRows(List<T> rows) {
        return rows.size() <= MAX_LIST_ROWS ? rows : rows.subList(0, MAX_LIST_ROWS);
    }

    /**
     * The count line for a list, which says when it was cut.
     *
     * @param noun plural, lowercase ({@code "warps"})
     */
    protected static String rowCount(int shown, int total, String noun) {
        if (shown >= total) {
            return total + " " + noun;
        }
        return shown + " of " + total + " " + noun + " \u2014 search to narrow";
    }

    /** Replaces the current page with {@code page} (must be called from {@code handleDataEvent}). */
    protected static void reopen(Ref<EntityStore> ref, Store<EntityStore> store, CustomUIPage page) {
        try {
            Player entity = store.getComponent(ref, Player.getComponentType());
            if (entity != null) {
                entity.getPageManager().openCustomPage(ref, store, page);
            }
        } catch (Throwable ignored) {
            // If the refresh fails the action still ran; the player can reopen the page.
        }
    }

    /** Closes the currently open page (must be called from {@code handleDataEvent}). */
    protected static void close(Ref<EntityStore> ref, Store<EntityStore> store) {
        try {
            Player entity = store.getComponent(ref, Player.getComponentType());
            if (entity != null) {
                entity.getPageManager().setPage(ref, store,
                        Page.None);
            }
        } catch (Throwable ignored) {
            // The player can close the page manually.
        }
    }

    /** Parses the {@code handleDataEvent} payload, returning an empty object on malformed input. */
    protected static JsonObject parse(String data) {
        try {
            return data == null || data.isBlank() ? new JsonObject() : Json.asObject(Json.parse(data));
        } catch (Throwable t) {
            return new JsonObject();
        }
    }

    protected static String string(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
    }

    /** Reads {@code key}, falling back to {@code @key} (the EventData append convention). */
    protected static String field(JsonObject object, String key) {
        String value = string(object, key);
        return value.isBlank() ? string(object, "@" + key) : value;
    }

    protected static double parseDouble(String raw, double fallback) {
        try {
            return raw == null || raw.isBlank() ? fallback : Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** @return {@code color} if it is a {@code #RRGGBB} hex string, else a neutral blue. */
    protected static String safeColor(String color) {
        return color == null || !color.matches("#[0-9a-fA-F]{6}") ? "#67A5DA" : color;
    }

    protected static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Case-insensitive containment filter used by the header search fields. */
    protected static boolean matchesSearch(String search, String... haystacks) {
        if (search == null || search.isBlank()) {
            return true;
        }
        String needle = search.toLowerCase().trim();
        for (String hay : haystacks) {
            if (hay != null && hay.toLowerCase().contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
