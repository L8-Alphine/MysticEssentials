package org.hyzionstudios.mysticessentials.modules.wiki;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Reads the documentation a mod ships in its asset pack, so the wiki can
 * show it as pages.
 *
 * <p>The format is the one Mermaids uses: {@code Common/Docs/<Group>_<Id>.json}
 * lists one or more documentations, each a tree of topics; every topic is a
 * Markdown file under {@code Common/Docs/<Group>_<Id>/}, opening with a
 * {@code ---} block that names it. Topics with sub-topics become
 * sub-categories, which is the point: a mod's own docs nest inside its
 * category the way its authors arranged them.</p>
 *
 * <p>Everything here is a pure function of the text it is given, so it is
 * tested without a server. The files are another mod's data: they are
 * capped in size and count, decoded leniently, and converted to wiki markup
 * line by line; a link that would run a command becomes plain text.</p>
 */
final class ModDocs {

    /** Larger files are cut here: one page is one packet. */
    static final int MAX_FILE_BYTES = 64 * 1024;
    /** Topics past this many, per mod, are left out. */
    static final int MAX_TOPICS = 40;

    /** One documentation set from a {@code Common/Docs/*.json} file. */
    record Documentation(String group, String id, String name, int sortIndex, List<Topic> topics) {
    }

    /** A Markdown file, and the topics filed under it. */
    record Topic(String file, List<Topic> subTopics) {
    }

    /** A converted Markdown file. */
    record Doc(String title, String summary, String body) {
    }

    private static final Pattern FENCE = Pattern.compile("^(```|~~~).*");
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s*(.*?)\\s*#*\\s*$");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)([-*+]|\\d{1,3}[.)])\\s+(.*)$");
    private static final Pattern IMAGE = Pattern.compile("!\\[([^\\]]*)\\]\\(([^)]*)\\)");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)\\]\\(([^)\"]+?)(?:\\s+\"[^\"]*\")?\\)");
    private static final Pattern TAG = Pattern.compile("</?[A-Za-z][^>]{0,80}>");
    private static final Pattern TABLE_RULE = Pattern.compile("^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?$");

    private ModDocs() {
    }

    // ----- The index file ------------------------------------------------------

    /** @return the enabled documentations in a {@code Common/Docs/*.json} file, or none if it is not one */
    static List<Documentation> documentations(String json) {
        List<Documentation> out = new ArrayList<>();
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            return out;
        }
        if (!root.isJsonObject() || !root.getAsJsonObject().has("documentations")
                || !root.getAsJsonObject().get("documentations").isJsonArray()) {
            return out;
        }
        for (JsonElement element : root.getAsJsonObject().getAsJsonArray("documentations")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject doc = element.getAsJsonObject();
            if (!bool(doc, "enabled", true)) {
                continue;
            }
            out.add(new Documentation(string(doc, "group"), string(doc, "id"), string(doc, "name"),
                    integer(doc, "sortIndex"), topics(doc.get("topics"), 0)));
        }
        out.sort((a, b) -> Integer.compare(a.sortIndex(), b.sortIndex()));
        return out;
    }

    private static List<Topic> topics(JsonElement element, int depth) {
        List<Topic> out = new ArrayList<>();
        if (element == null || !element.isJsonArray() || depth > 4) {
            return out;
        }
        JsonArray array = element.getAsJsonArray();
        for (JsonElement entry : array) {
            if (!entry.isJsonObject()) {
                continue;
            }
            String file = string(entry.getAsJsonObject(), "file");
            if (safeFile(file)) {
                out.add(new Topic(file, topics(entry.getAsJsonObject().get("subTopics"), depth + 1)));
            }
        }
        return out;
    }

    /** A topic path inside the docs folder: relative, Markdown, no way out of the folder. */
    static boolean safeFile(String file) {
        return file != null && !file.isBlank() && file.toLowerCase(Locale.ROOT).endsWith(".md")
                && !file.startsWith("/") && !file.contains("\\") && !file.contains("..") && !file.contains(":")
                && file.length() <= 160;
    }

    // ----- One Markdown file ---------------------------------------------------

    /**
     * Mod files arrive in whatever encoding their author's editor used: UTF-8
     * when it is valid, otherwise Windows-1252, which is what turns curly
     * quotes into replacement marks when read the other way.
     */
    static String decode(byte[] bytes) {
        int length = Math.min(bytes.length, MAX_FILE_BYTES);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 0, length)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, 0, length, Charset.forName("windows-1252"));
        }
    }

    /**
     * Converts one Markdown file.
     *
     * @param fallbackTitle the title when the file does not name itself
     * @param pageFor       maps another topic file this one links to onto its
     *                      page id, or {@code null} when it is not one
     */
    static Doc convert(String markdown, String fallbackTitle, Function<String, String> pageFor) {
        List<String> lines = new ArrayList<>(List.of(plainQuotes(markdown).replace("\r\n", "\n").replace('\r', '\n')
                .replace("﻿", "").split("\n", -1)));
        Map<String, String> front = frontMatter(lines);
        StringBuilder body = new StringBuilder();
        boolean code = false;
        for (String raw : lines) {
            if (FENCE.matcher(raw.strip()).matches()) {
                code = !code;
                continue;
            }
            String line = code ? "> " + clean(raw.strip()) : line(raw, pageFor);
            if (line != null) {
                body.append(line).append('\n');
            }
        }
        String title = front.getOrDefault("name", "").isBlank() ? fallbackTitle : front.get("name");
        return new Doc(clean(title), clean(front.getOrDefault("description", "")),
                body.toString().replaceAll("\n{3,}", "\n\n").strip());
    }

    /** Removes a leading {@code ---} block and returns its {@code key: value} lines. */
    private static Map<String, String> frontMatter(List<String> lines) {
        Map<String, String> values = new LinkedHashMap<>();
        int start = 0;
        while (start < lines.size() && lines.get(start).isBlank()) {
            start++;
        }
        if (start >= lines.size() || !lines.get(start).strip().equals("---")) {
            return values;
        }
        for (int end = start + 1; end < lines.size() && end < start + 40; end++) {
            String line = lines.get(end).strip();
            if (line.equals("---")) {
                for (String entry : lines.subList(start + 1, end)) {
                    int colon = entry.indexOf(':');
                    if (colon > 0) {
                        values.put(entry.substring(0, colon).strip().toLowerCase(Locale.ROOT),
                                unquote(entry.substring(colon + 1).strip()));
                    }
                }
                lines.subList(0, end + 1).clear();
                return values;
            }
        }
        return values;
    }

    /** One Markdown line as wiki markup; {@code null} drops it. */
    private static String line(String raw, Function<String, String> pageFor) {
        String stripped = raw.strip();
        if (stripped.isEmpty()) {
            return "";
        }
        if (stripped.matches("^(-{3,}|\\*{3,}|_{3,})$")) {
            return "---";
        }
        Matcher heading = HEADING.matcher(stripped);
        if (heading.matches()) {
            String text = inline(heading.group(2), pageFor);
            if (text.isBlank()) {
                return null;
            }
            return (heading.group(1).length() <= 2 ? "## " : "### ") + text;
        }
        if (stripped.startsWith("|")) {
            if (TABLE_RULE.matcher(stripped).matches()) {
                return null;
            }
            List<String> cells = new ArrayList<>();
            for (String cell : stripped.replaceAll("^\\||\\|$", "").split("\\|")) {
                if (!cell.isBlank()) {
                    cells.add(inline(cell.strip(), pageFor));
                }
            }
            return cells.isEmpty() ? null : "- " + String.join("  ·  ", cells);
        }
        Matcher bullet = BULLET.matcher(raw.replace("\t", "    "));
        if (bullet.matches()) {
            String text = inline(bullet.group(3), pageFor);
            if (text.isBlank()) {
                return null;
            }
            String marker = bullet.group(2);
            String prefix = Character.isDigit(marker.charAt(0)) ? marker + " " : "";
            // Nesting is lost on a single-column page; a sub-item reads as an
            // indented aside under its parent instead.
            return (bullet.group(1).length() >= 2 ? "> " : "- ") + prefix + text;
        }
        if (stripped.startsWith(">")) {
            String text = inline(stripped.replaceFirst("^>+\\s*", ""), pageFor);
            return text.isBlank() ? "" : "> " + text;
        }
        return inline(stripped, pageFor);
    }

    /** Running text: emphasis and HTML dropped, links kept where they can work. */
    private static String inline(String text, Function<String, String> pageFor) {
        String source = TAG.matcher(IMAGE.matcher(text).replaceAll("")).replaceAll("")
                .replace("**", "").replace("__", "").replace("`", "");
        // Text and labels are cleaned; a web address is kept exactly, since
        // its '&' is part of the address, not a colour code.
        StringBuilder out = new StringBuilder();
        Matcher link = LINK.matcher(source);
        int last = 0;
        while (link.find()) {
            out.append(escape(source.substring(last, link.start())));
            String label = clean(link.group(1)).replace("]", "").replace("|", "/");
            String target = link.group(2).strip();
            if (target.startsWith("https://") || target.startsWith("http://")) {
                out.append('[').append(label).append("](").append(target).append(')');
            } else {
                String page = pageFor == null ? null : pageFor.apply(target.replaceFirst("#.*$", ""));
                out.append(page == null ? label : "[[" + page + "|" + label + "]]");
            }
            last = link.end();
        }
        out.append(escape(source.substring(last)));
        return out.toString().replaceAll(" {2,}", " ").strip();
    }

    /**
     * Text from another mod, made safe to show: its {@code &} and {@code <}
     * are not colour codes or tags here, and a line cannot become a macro.
     */
    static String clean(String text) {
        return text == null ? "" : escape(text).strip();
    }

    private static String escape(String text) {
        return text.replaceAll("&(?=[0-9a-fk-orA-FK-OR#])", "& ")
                .replaceAll("<(?=[A-Za-z#/!])", "< ")
                .replace("{{", "{ {");
    }

    private static String plainQuotes(String text) {
        return text.replace('“', '"').replace('”', '"').replace('‘', '\'').replace('’', '\'')
                .replace("—", " - ").replace(' ', ' ').replace("�", "");
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                || value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String string(JsonObject object, String key) {
        try {
            JsonElement value = object.get(key);
            return value == null || value.isJsonNull() ? "" : value.getAsString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static int integer(JsonObject object, String key) {
        try {
            JsonElement value = object.get(key);
            return value == null || value.isJsonNull() ? 0 : value.getAsInt();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        try {
            JsonElement value = object.get(key);
            return value == null || value.isJsonNull() ? fallback : value.getAsBoolean();
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
