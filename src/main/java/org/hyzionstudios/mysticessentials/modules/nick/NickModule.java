package org.hyzionstudios.mysticessentials.modules.nick;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.hyzionstudios.mysticessentials.api.Permissions;
import org.hyzionstudios.mysticessentials.core.module.AbstractMysticModule;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommand;
import org.hyzionstudios.mysticessentials.platform.command.MysticCommandSender;

import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Nicknames: {@code /nick} opens the colour-picker UI, {@code /nick <name>}
 * sets, {@code /nick reset} clears, {@code /nick <player> <name>} sets for
 * another player (admin). The nickname is stored in the player profile's
 * metadata under {@code nickname} and rendered by the Chat module through the
 * {@code {display_name}} placeholder; colour codes are permission-gated
 * ({@code mysticessentials.nick.color}).
 */
public final class NickModule extends AbstractMysticModule {

    static final String METADATA_KEY = "nickname";

    private NickConfig config = new NickConfig();

    public NickModule() {
        super("nick", "Nicknames", "1.0.0");
    }

    @Override
    public void onEnable() {
        config = core.configManager().loadModuleConfig(id(), NickConfig.class, new NickConfig());
        registerCommand(new NickCommand());
    }

    @Override
    public void onReload() {
        config = core.configManager().loadModuleConfig(id(), NickConfig.class, new NickConfig());
    }

    @Override
    public void onDisable() {
        // Nicknames persist in profiles; nothing to flush.
    }

    NickConfig config() {
        return config;
    }

    // ----- Nickname state -------------------------------------------------------

    /** The player's raw nickname (may contain colour codes), or null. */
    String nickname(UUID player) {
        return core.getPlayerProfileService().getCached(player)
                .map(profile -> profile.getMetadata().get(METADATA_KEY))
                .filter(nick -> !nick.isBlank())
                .orElse(null);
    }

    String editableNickname(UUID player) {
        return stripNickFormat(stripColors(nickname(player)));
    }

    java.util.Optional<String> nicknameColor(UUID player) {
        return java.util.Optional.ofNullable(extractColor(nickname(player)));
    }

    record NickError(String key, Map<String, String> params) {
    }

    /** Validation + set. @return an error message key, or null on success. */
    NickError setNickname(PlayerRef target, String rawNick, boolean allowColors) {
        String stripped = stripNickFormat(stripColors(rawNick));
        if (stripped.length() < config.minLength || stripped.length() > config.maxLength) {
            return new NickError("nick-error-length", Map.of(
                    "min", Integer.toString(config.minLength),
                    "max", Integer.toString(config.maxLength)));
        }
        if (!stripped.matches("[A-Za-z0-9_]+")) {
            return new NickError("nick-error-characters", Map.of());
        }
        String lower = stripped.toLowerCase(Locale.ROOT);
        if (config.blockedNames != null && config.blockedNames.contains(lower)) {
            return new NickError("nick-error-blocked", Map.of());
        }
        // Do not allow impersonating another online player's real name.
        boolean impersonation = core.platform().onlinePlayers().stream()
                .anyMatch(online -> !online.getUuid().equals(target.getUuid())
                        && online.getUsername().equalsIgnoreCase(stripped));
        if (impersonation) {
            return new NickError("nick-error-impersonation", Map.of());
        }
        String toStore;
        if (!allowColors) {
            toStore = stripped;
        } else {
            // Preserve any colour markup the player typed; translate config preset
            // names (<red>) to hex so MysticText renders them, and if they chose no
            // colour at all, fall back to the configured default colour.
            String raw = translateNamedColors(stripLeadingMarker(rawNick).trim());
            if (!hasColorMarkup(raw)) {
                String fallback = resolveColor(config.defaultColor);
                raw = fallback != null ? "<" + fallback + ">" + raw : raw;
            }
            toStore = raw;
        }
        var profile = core.getPlayerProfileService().getCached(target.getUuid()).orElse(null);
        if (profile == null) {
            return new NickError("nick-error-profile", Map.of());
        }
        profile.getMetadata().put(METADATA_KEY, formatNickname(toStore));
        core.getPlayerProfileService().save(profile);
        return null;
    }

    void clearNickname(UUID player) {
        core.getPlayerProfileService().getCached(player).ifPresent(profile -> {
            profile.getMetadata().remove(METADATA_KEY);
            core.getPlayerProfileService().save(profile);
        });
    }

    /** @return {@code true} if the string carries any legacy/hex/tag colour markup. */
    boolean hasColorMarkup(String value) {
        return value != null && value.matches("(?is).*(&[0-9a-fk-or]|&#[0-9a-f]{3,6}|<[^>]+>).*");
    }

    /** Replaces {@code <presetName>} tokens with their configured hex so they render. */
    String translateNamedColors(String value) {
        if (value == null || config.colors == null || config.colors.isEmpty()) {
            return value;
        }
        String out = value;
        for (Map.Entry<String, String> entry : config.colors.entrySet()) {
            String hex = resolveHexOnly(entry.getValue());
            if (hex != null) {
                out = out.replaceAll("(?i)<" + java.util.regex.Pattern.quote(entry.getKey()) + ">", "<" + hex + ">");
            }
        }
        return out;
    }

    /**
     * Canonicalizes a colour token from many shapes — {@code #RRGGBB},
     * {@code #RGB}, {@code #RRGGBBAA} (alpha dropped), {@code &#RRGGBB}, legacy
     * {@code &c}, {@code <#hex>}, {@code <color:#hex>}, {@code r,g,b}, a config
     * preset name, or a built-in colour name — into {@code #RRGGBB}, or null.
     * Also normalizes whatever the colour-picker widget returns.
     */
    String resolveColor(String token) {
        if (token == null) {
            return null;
        }
        String t = token.trim();
        if (t.isEmpty()) {
            return null;
        }
        if (t.startsWith("<") && t.endsWith(">") && t.length() >= 2) {
            t = t.substring(1, t.length() - 1).trim();
        }
        int colon = t.indexOf(':');
        if (colon > 0) {
            String prefix = t.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            if (prefix.equals("color") || prefix.equals("c")) {
                t = t.substring(colon + 1).trim();
            }
        }
        if (t.startsWith("&")) {
            String rest = t.substring(1);
            if (rest.length() == 1) {
                String legacy = legacyColor(rest.charAt(0));
                if (legacy != null) {
                    return legacy;
                }
            }
            t = rest; // e.g. "&#RRGGBB" -> "#RRGGBB"
        }
        String hex = resolveHexOnly(t);
        if (hex != null) {
            return hex;
        }
        String name = t.toLowerCase(Locale.ROOT);
        if (config.colors != null) {
            String preset = config.colors.get(name);
            if (preset != null) {
                return resolveHexOnly(preset);
            }
        }
        return builtinNamed(name);
    }

    /** Hex/RGB-only normalization (no names): {@code #RRGGBB}/{@code #RGB}/8-digit/{@code r,g,b}. */
    private static String resolveHexOnly(String value) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        java.util.regex.Matcher rgb = java.util.regex.Pattern
                .compile("^(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})$").matcher(t);
        if (rgb.matches()) {
            int r = clampByte(Integer.parseInt(rgb.group(1)));
            int g = clampByte(Integer.parseInt(rgb.group(2)));
            int b = clampByte(Integer.parseInt(rgb.group(3)));
            return String.format(Locale.ROOT, "#%02X%02X%02X", r, g, b);
        }
        String hex = t.startsWith("#") ? t.substring(1) : t;
        if (hex.matches("(?i)[0-9a-f]{8}")) {
            hex = hex.substring(0, 6); // drop trailing alpha
        }
        if (hex.matches("(?i)[0-9a-f]{6}")) {
            return "#" + hex.toUpperCase(Locale.ROOT);
        }
        if (hex.matches("(?i)[0-9a-f]{3}")) {
            StringBuilder sb = new StringBuilder("#");
            for (int i = 0; i < 3; i++) {
                sb.append(hex.charAt(i)).append(hex.charAt(i));
            }
            return sb.toString().toUpperCase(Locale.ROOT);
        }
        return null;
    }

    private static int clampByte(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static String builtinNamed(String name) {
        return switch (name) {
            case "black" -> "#000000";
            case "darkblue", "dark_blue" -> "#0000AA";
            case "darkgreen", "dark_green" -> "#00AA00";
            case "darkaqua", "dark_aqua", "cyan" -> "#00AAAA";
            case "darkred", "dark_red" -> "#AA0000";
            case "darkpurple", "dark_purple" -> "#AA00AA";
            case "gold", "orange" -> "#FFAA00";
            case "gray", "grey" -> "#AAAAAA";
            case "darkgray", "dark_gray", "darkgrey" -> "#555555";
            case "blue" -> "#5555FF";
            case "green", "lime" -> "#55FF55";
            case "aqua" -> "#55FFFF";
            case "red" -> "#FF5555";
            case "lightpurple", "light_purple", "pink", "magenta" -> "#FF55FF";
            case "yellow" -> "#FFFF55";
            case "white" -> "#FFFFFF";
            case "purple" -> "#AA00AA";
            default -> null;
        };
    }

    static String stripColors(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replaceAll("(?i)&[0-9a-fk-or]", "")
                .replaceAll("(?i)&#[0-9a-f]{6}", "")
                .replaceAll("(?i)<[^>]*>", "")
                .trim();
    }

    private String stripNickFormat(String value) {
        if (value == null) {
            return "";
        }
        String format = config.nickFormat == null || config.nickFormat.isBlank()
                ? "{marker}{nickname}"
                : config.nickFormat;
        int nickToken = format.indexOf("{nickname}");
        if (nickToken >= 0) {
            String prefix = format.substring(0, nickToken).replace("{marker}", marker());
            String suffix = format.substring(nickToken + "{nickname}".length()).replace("{marker}", marker());
            if (value.startsWith(prefix) && value.endsWith(suffix)
                    && value.length() >= prefix.length() + suffix.length()) {
                return value.substring(prefix.length(), value.length() - suffix.length());
            }
        }
        return stripMarker(value);
    }

    private String stripMarker(String value) {
        String marker = config.nickMarker == null ? "" : config.nickMarker;
        if (value == null || marker.isBlank() || !value.startsWith(marker)) {
            return value == null ? "" : value;
        }
        return value.substring(marker.length());
    }

    private String stripLeadingMarker(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        String marker = marker();
        return !marker.isBlank() && trimmed.startsWith(marker) ? trimmed.substring(marker.length()) : trimmed;
    }

    private String formatNickname(String nickname) {
        String format = config.nickFormat == null || config.nickFormat.isBlank()
                ? "{marker}{nickname}"
                : config.nickFormat;
        return format
                .replace("{marker}", marker())
                .replace("{nickname}", nickname == null ? "" : nickname);
    }

    private String marker() {
        return config.nickMarker == null ? "" : config.nickMarker;
    }

    private static String extractColor(String value) {
        if (value == null) {
            return null;
        }
        java.util.regex.Matcher hexTag = java.util.regex.Pattern
                .compile("(?i)<#([0-9a-f]{6})>").matcher(value);
        if (hexTag.find()) {
            return "#" + hexTag.group(1).toUpperCase(Locale.ROOT);
        }
        java.util.regex.Matcher ampHex = java.util.regex.Pattern
                .compile("(?i)&#([0-9a-f]{6})").matcher(value);
        if (ampHex.find()) {
            return "#" + ampHex.group(1).toUpperCase(Locale.ROOT);
        }
        java.util.regex.Matcher legacy = java.util.regex.Pattern
                .compile("(?i)&([0-9a-f])").matcher(value);
        return legacy.find() ? legacyColor(legacy.group(1).charAt(0)) : null;
    }

    private static String legacyColor(char code) {
        return switch (Character.toLowerCase(code)) {
            case '0' -> "#000000";
            case '1' -> "#0000AA";
            case '2' -> "#00AA00";
            case '3' -> "#00AAAA";
            case '4' -> "#AA0000";
            case '5' -> "#AA00AA";
            case '6' -> "#FFAA00";
            case '7' -> "#AAAAAA";
            case '8' -> "#555555";
            case '9' -> "#5555FF";
            case 'a' -> "#55FF55";
            case 'b' -> "#55FFFF";
            case 'c' -> "#FF5555";
            case 'd' -> "#FF55FF";
            case 'e' -> "#FFFF55";
            case 'f' -> "#FFFFFF";
            default -> null;
        };
    }

    void openNickUi(PlayerRef player) {
        core.platform().openPage(player, new NickPages.NickPage(core, this, player));
    }

    private void applyNick(MysticCommandSender sender, PlayerRef target, String rawNick) {
        boolean allowColors = target.hasPermission(Permissions.NICK_COLOR);
        NickError error = setNickname(target, rawNick, allowColors);
        if (error != null) {
            sender.replyKey(error.key(), error.params());
            return;
        }
        String shown = nickname(target.getUuid());
        if (sender.uuid().equals(target.getUuid())) {
            sender.replyKey("nick-set", Map.of("nickname", shown));
        } else {
            sender.replyKey("nick-set-other", Map.of("player", target.getUsername(), "nickname", shown));
            core.getMessageService().sendKey(target, "nick-set-by-admin", Map.of("nickname", shown));
        }
    }

    // ----- Commands ----------------------------------------------------------

    /**
     * {@code /nick} opens the UI; {@code /nick <name>} sets your nickname;
     * {@code /nick <player> <name>} sets another player's (admin);
     * {@code /nick reset} (subcommand) clears yours.
     */
    private final class NickCommand extends MysticCommand {
        NickCommand() {
            super(NickModule.this.core, "nick", "Set your chat nickname.");
            addAliases("nickname");
            requirePermission(Permissions.NICK_USE);
            addSubCommand(new NickResetCommand());
            addUsageVariant(new NickSetVariant());
            addUsageVariant(new NickSetOtherVariant());
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            openNickUi(sender.player().orElseThrow());
        }
    }

    private final class NickResetCommand extends MysticCommand {
        NickResetCommand() {
            super(NickModule.this.core, "reset", "Remove your nickname.");
            addAliases("off", "clear");
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            clearNickname(sender.uuid());
            sender.replyKey("nick-cleared");
        }
    }

    private final class NickSetVariant extends MysticCommand {
        private final RequiredArg<String> nick = withRequiredArg("nickname", "New nickname", ArgTypes.STRING);

        NickSetVariant() {
            super(NickModule.this.core, "Set your nickname.");
            requirePermission(Permissions.NICK_USE);
        }

        @Override
        protected void run(MysticCommandSender sender) {
            if (!sender.isPlayer()) {
                sender.replyKey("player-only");
                return;
            }
            applyNick(sender, sender.player().orElseThrow(), sender.get(nick));
        }
    }

    private final class NickSetOtherVariant extends MysticCommand {
        private final RequiredArg<String> target = withRequiredArg("player", "Target player",
                ArgTypes.STRING).suggest((commandSender, input, index, result) ->
                        core.platform().onlinePlayers().forEach(p -> result.suggest(p.getUsername())));
        private final RequiredArg<String> nick = withRequiredArg("nickname", "New nickname", ArgTypes.STRING);

        NickSetOtherVariant() {
            super(NickModule.this.core, "Set another player's nickname.");
            requirePermission(Permissions.NICK_OTHERS);
        }

        @Override
        protected void run(MysticCommandSender sender) {
            PlayerRef targetRef = core.platform().findPlayerByName(sender.get(target)).orElse(null);
            if (targetRef == null) {
                sender.replyKey("player-not-found");
                return;
            }
            applyNick(sender, targetRef, sender.get(nick));
        }
    }
}
