package org.hyzionstudios.mysticessentials.platform.ui;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.util.Json;

import com.google.gson.JsonObject;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
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
 * then addressed by index — {@code cmd.set("#List[0] #Name.Text", ...)}.</p>
 */
public abstract class MysticPage extends CustomUIPage {

    protected final MysticCore core;
    protected final PlayerRef player;

    protected MysticPage(MysticCore core, PlayerRef player, CustomPageLifetime lifetime) {
        super(player, lifetime);
        this.core = core;
        this.player = player;
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
