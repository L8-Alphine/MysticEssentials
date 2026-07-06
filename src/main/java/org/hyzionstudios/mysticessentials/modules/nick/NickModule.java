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
        String toStore = allowColors ? stripLeadingMarker(rawNick).trim() : stripped;
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
