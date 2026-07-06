package org.hyzionstudios.mysticessentials.modules.announcements;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/** Persisted settings for {@code modules/announcements/config.json}. */
public final class AnnouncementConfig {

    public boolean autoBroadcastEnabled = true;
    public int intervalSeconds = 300;
    public boolean randomOrder = false;
    /** Prefix prepended to every {@code /broadcast} message. Colour codes allowed; empty disables. */
    public String broadcastPrefix = "&8[&dBroadcast&8] &f";
    /** Prefix prepended to every {@code /alert} message. Colour codes allowed; empty disables. */
    public String alertPrefix = "&8[&c&lALERT&8] &c";
    public List<JsonElement> messages = defaultMessages();

    private static List<JsonElement> defaultMessages() {
        List<JsonElement> list = new ArrayList<>();
        list.add(announcement(
                new String[] {
                        "&7Welcome to the server!",
                        "&8Click this announcement to run &f/mystic&8."
                },
                "command",
                "/mystic"));
        list.add(new JsonPrimitive("&7Set a home with &f/sethome &7and return with &f/home&7."));
        list.add(announcement(
                new String[] {
                        "&7Need to reach a friend?",
                        "&8Use &f/tpa <player>&8."
                },
                null,
                null));
        return list;
    }

    private static JsonObject announcement(String[] lines, String clickAction, String clickValue) {
        JsonObject object = new JsonObject();
        JsonArray array = new JsonArray();
        for (String line : lines) {
            array.add(line);
        }
        object.add("lines", array);
        if (clickValue != null && !clickValue.isBlank()) {
            JsonObject click = new JsonObject();
            click.addProperty("action", clickAction == null || clickAction.isBlank() ? "link" : clickAction);
            click.addProperty("value", clickValue);
            object.add("click", click);
        }
        return object;
    }
}
