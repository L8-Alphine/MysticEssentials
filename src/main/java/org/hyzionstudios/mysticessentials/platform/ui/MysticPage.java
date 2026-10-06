package org.hyzionstudios.mysticessentials.platform.ui;

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
     * tokens mirror {@code MysticTheme.ui}: display and value text
     * {@code #DEE2EF}, row names white, meta {@code #96A9BE}, eyebrows and
     * counts {@code #778292}, captions {@code #5A6A7A}, gold for "this one".
     */
    private static final String DEFAULT_UI_TEXT_COLOR = "#DEE2EF";
    private static final String TEXT_HI = "#FFFFFF";
    private static final String TEXT_BODY = "#DEE2EF";
    private static final String TEXT_MUTED = "#96A9BE";
    private static final String TEXT_DIM = "#778292";
    private static final String TEXT_FAINT = "#5A6A7A";
    private static final String GOLD = "#E8A93B";
    private static final String GOLD_SOFT = "#E8C97A";
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
            Map.entry("RosterCounts", GOLD_SOFT),
            Map.entry("State", GOLD_SOFT),
            Map.entry("Unread", "#FFD97A"),
            Map.entry("Secondary", "#D46A6A"));

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
        return color == null || !color.matches("#[0-9a-fA-F]{6}") ? "#7a9cc6" : color;
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
