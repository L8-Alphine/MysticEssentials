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

    private static final String DEFAULT_UI_TEXT_COLOR = "#D7E1EE";
    private static final Pattern TEXT_TARGET =
            Pattern.compile("#([A-Za-z0-9_]+)\\.TextSpans$");
    private static final Map<String, String> UI_TEXT_COLORS = Map.ofEntries(
            Map.entry("AdminHeading", "#FFFFFF"),
            Map.entry("AdminHint", "#96A9BE"),
            Map.entry("AudienceLabel", "#F0B429"),
            Map.entry("ButtonCount", "#D7E1EE"),
            Map.entry("ButtonLabel", "#FFFFFF"),
            Map.entry("Cause", "#FFD97A"),
            Map.entry("ChannelName", "#FFFFFF"),
            Map.entry("ComposeTitle", "#FFFFFF"),
            Map.entry("ContentEmpty", "#9FB0C4"),
            Map.entry("Cost", "#9FB0C4"),
            Map.entry("CurrentChannelLabel", "#E8A93B"),
            Map.entry("Date", "#96A9BE"),
            Map.entry("Details", "#9FB0C4"),
            Map.entry("DraftLabel", "#9FB0C4"),
            Map.entry("FooterCounts", "#9FB0C4"),
            Map.entry("From", "#FFFFFF"),
            Map.entry("GuiTitle", "#FFFFFF"),
            Map.entry("Have", "#96A9BE"),
            Map.entry("HeaderInfo", "#96A9BE"),
            Map.entry("HeaderTitle", "#FFFFFF"),
            Map.entry("HomeCount", "#96A9BE"),
            Map.entry("InfoName", "#FFFFFF"),
            Map.entry("InfoType", "#E8A93B"),
            Map.entry("ItemId", "#6F7F93"),
            Map.entry("ItemName", "#FFFFFF"),
            Map.entry("ItemSubtitle", "#96A9BE"),
            Map.entry("Items", "#9FB0C4"),
            Map.entry("KitCount", "#96A9BE"),
            Map.entry("KitDescription", "#96A9BE"),
            Map.entry("Line", "#D7E1EE"),
            Map.entry("ListEmpty", "#9FB0C4"),
            Map.entry("ListTitle", "#FFFFFF"),
            Map.entry("ManageChannelName", "#FFFFFF"),
            Map.entry("ManageExpiry", "#96A9BE"),
            Map.entry("ManagerLimit", "#96A9BE"),
            Map.entry("Meta", "#9FB0C4"),
            Map.entry("Name", "#FFFFFF"),
            Map.entry("NoItemsLabel", "#9FB0C4"),
            Map.entry("Number", "#96A9BE"),
            Map.entry("PageCount", "#D7E1EE"),
            Map.entry("PageHeading", "#E8C97A"),
            Map.entry("PageInfo", "#96A9BE"),
            Map.entry("PageLabel", "#D7E1EE"),
            Map.entry("PageSubtitle", "#9FB0C4"),
            Map.entry("PageTitle", "#FFFFFF"),
            Map.entry("PatchMeta", "#96A9BE"),
            Map.entry("PatchSummary", "#C9D6E5"),
            Map.entry("PatchTitle", "#FFFFFF"),
            Map.entry("Preview", "#9FB0C4"),
            Map.entry("PreviewDescription", "#96A9BE"),
            Map.entry("PreviewTitle", "#FFFFFF"),
            Map.entry("ProfileName", "#FFFFFF"),
            Map.entry("PwarpDescription", "#96A9BE"),
            Map.entry("PwarpLimit", "#96A9BE"),
            Map.entry("PwarpName", "#FFFFFF"),
            Map.entry("Range", "#9FB0C4"),
            Map.entry("ReadBody", "#D7E1EE"),
            Map.entry("ReadDate", "#96A9BE"),
            Map.entry("ReadFrom", "#96A9BE"),
            Map.entry("ReadSubject", "#FFFFFF"),
            Map.entry("Reward", "#F0B429"),
            Map.entry("RewardHeader", "#F0B429"),
            Map.entry("RosterChannel", "#FFFFFF"),
            Map.entry("RosterCounts", "#E8A93B"),
            Map.entry("RosterMore", "#9FB0C4"),
            Map.entry("Secondary", "#D46A6A"),
            Map.entry("SetAllowance", "#CDDBE8"),
            Map.entry("SetCustom", "#CDDBE8"),
            Map.entry("SetHint", "#96A9BE"),
            Map.entry("SetUsage", "#CDDBE8"),
            Map.entry("ShareInfo", "#7F8EA3"),
            Map.entry("State", "#E8C97A"),
            Map.entry("Status", "#9FB0C4"),
            Map.entry("Sub", "#9FB0C4"),
            Map.entry("Subtitle", "#96A9BE"),
            Map.entry("TargetName", "#96A9BE"),
            Map.entry("Timing", "#9FB0C4"),
            Map.entry("Title", "#FFFFFF"),
            Map.entry("Unread", "#FFD97A"),
            Map.entry("VaultCount", "#96A9BE"),
            Map.entry("ViewerSubtitle", "#96A9BE"),
            Map.entry("WarpDescription", "#96A9BE"),
            Map.entry("WarpName", "#FFFFFF"),
            Map.entry("World", "#9FB0C4"));

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
                        com.hypixel.hytale.protocol.packets.interface_.Page.None);
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
