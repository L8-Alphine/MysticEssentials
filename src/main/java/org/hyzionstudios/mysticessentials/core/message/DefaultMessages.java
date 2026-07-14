package org.hyzionstudios.mysticessentials.core.message;

import com.google.gson.JsonObject;

/** Built-in default message bundle written to {@code messages/en_us.json} on first run. */
public final class DefaultMessages {

    private DefaultMessages() {
    }

    public static JsonObject enUs() {
        JsonObject o = new JsonObject();
        o.addProperty("prefix", "<#7b2cff>Mystic <#00d4ff>Essentials &8» &f");
        o.addProperty("no-permission", "&cYou do not have permission to do that.");
        o.addProperty("player-only", "&cThis command can only be used by a player.");
        o.addProperty("player-not-found", "&cThat player could not be found.");
        o.addProperty("module-disabled", "&cThat feature is disabled on this server.");
        o.addProperty("reload-success", "&aMystic Essentials reloaded.");
        o.addProperty("core-info-version", "&d&lMystic Essentials &7v{version}");
        o.addProperty("core-info-status", "&7Storage: &f{storage} &7| Modules loaded: &f{modules}");
        o.addProperty("core-info-help", "&7Use &f/mystic reload &7to reload configuration.");
        o.addProperty("on-cooldown", "&cPlease wait {seconds}s before doing that again.");
        o.addProperty("not-enough-money", "&cYou cannot afford that ({cost}).");
        o.addProperty("teleport-warmup", "&7Teleporting in {seconds}s. Do not move.");
        o.addProperty("teleport-hud-warmup", "Teleporting in {seconds}...");
        o.addProperty("teleport-starting", "&7Teleporting...");
        o.addProperty("teleport-on-cooldown", "&cPlease wait {seconds}s before teleporting again.");
        o.addProperty("teleport-cost-charged", "&7Teleport cost: &f{cost}&7.");
        o.addProperty("teleport-invalid-destination", "&cThat teleport destination is not available.");
        o.addProperty("teleport-world-disabled", "&cTeleporting to world &f{world} &cis disabled.");
        o.addProperty("teleport-cancelled-move", "&cTeleport cancelled because you moved.");
        o.addProperty("teleport-cancelled-damage", "&cTeleport cancelled because you took damage.");
        o.addProperty("teleport-success", "&aTeleported.");
        o.addProperty("teleport-target-offline", "&cThat player is no longer online.");
        o.addProperty("teleport-request-incoming-to-you",
                "&e{player} &7wants to teleport to you. &a/tpaccept &7or &c/tpdeny");
        o.addProperty("teleport-request-incoming-to-them",
                "&e{player} &7wants you to teleport to them. &a/tpaccept &7or &c/tpdeny");
        o.addProperty("teleport-request-sent", "&7Teleport request sent to &e{player}");
        o.addProperty("teleport-request-self", "&cYou cannot send a teleport request to yourself.");
        o.addProperty("teleport-request-accepted", "&aTeleport request accepted.");
        o.addProperty("teleport-request-accepted-by", "&a{player} accepted your teleport request.");
        o.addProperty("teleport-request-denied", "&7Teleport request denied.");
        o.addProperty("teleport-request-denied-by", "&c{player} denied your teleport request.");
        o.addProperty("teleport-request-none", "&cYou have no pending teleport requests.");
        o.addProperty("teleport-request-none-from", "&cNo pending request from that player.");
        o.addProperty("teleport-request-cancelled", "&7Outgoing teleport requests cancelled.");
        o.addProperty("teleport-request-none-outgoing", "&cYou have no outgoing requests.");
        o.addProperty("teleport-requests-disabled", "&7Incoming teleport requests disabled.");
        o.addProperty("teleport-requests-enabled", "&aIncoming teleport requests enabled.");
        o.addProperty("teleport-requests-disabled-target",
                "&c{player} is not accepting teleport requests.");
        o.addProperty("teleport-to-failed", "&cCould not teleport to &f{player}&c: {reason}");
        o.addProperty("teleport-here-self", "&cYou are already here.");
        o.addProperty("teleport-here-success", "&aTeleported &f{player} &ato you.");
        o.addProperty("teleport-here-target", "&7You were teleported to &e{player}&7.");
        o.addProperty("teleport-here-failed", "&cCould not teleport &f{player}&c: {reason}");
        o.addProperty("teleport-all-none", "&cNo other players are online.");
        o.addProperty("teleport-all-started", "&aTeleporting &f{count} &aplayer{plural} to you.");
        o.addProperty("teleport-top-unavailable", "&cCould not find a safe top location here.");
        o.addProperty("teleport-back-none", "&cNo previous location to return to.");
        // Random Teleport
        o.addProperty("rtp-disabled", "&cRandom teleport is disabled on this server.");
        o.addProperty("rtp-no-destination", "&cCould not find a safe place to teleport you. Try again.");
        o.addProperty("rtp-profile-disabled", "&cThat random-teleport profile is disabled.");
        o.addProperty("rtp-world-disabled", "&cRandom teleport is disabled in world &f{world}&c.");
        o.addProperty("rtp-no-permission", "&cYou do not have permission to use that random teleport.");
        o.addProperty("rtp-already-active", "&cYou already have a random teleport in progress.");
        o.addProperty("rtp-on-cooldown", "&cYou must wait {seconds}s before using random teleport again.");
        o.addProperty("rtp-limit-reached", "&cYou have reached your random-teleport usage limit.");
        o.addProperty("rtp-not-enough-money", "&cYou cannot afford that random teleport ({cost}).");
        o.addProperty("rtp-warmup", "&7Searching for a destination in {seconds}s. Do not move.");
        o.addProperty("rtp-searching", "&7Searching for a safe destination...");
        o.addProperty("rtp-success", "&aTeleported to &f{profile} &7at &f{x}, {y}, {z}&a.");
        o.addProperty("rtp-cancelled", "&cRandom teleport cancelled.");
        o.addProperty("rtp-cancel-ok", "&7Random teleport cancelled.");
        o.addProperty("rtp-cancel-none", "&cYou have no random teleport in progress.");
        o.addProperty("rtp-other-success", "&aRandomly teleported &f{player}&a.");
        o.addProperty("rtp-other-failed", "&cCould not random-teleport &f{player}&c: {reason}");
        o.addProperty("rtp-status-header", "&d&lRandom Teleport Status");
        o.addProperty("rtp-status-active", "&7Phase: &f{phase} &7| Profile: &f{profile} &7| Queue: &f{queue}");
        o.addProperty("rtp-status-idle", "&7No random teleport in progress.");
        o.addProperty("rtp-status-profile", "&7 - &f{profile}&7: {cooldown}");
        o.addProperty("rtp-info",
                "&7{profile} &8» &7world &f{world}&7, {shape} &f{min}-{max}&7 blocks, cost &f{cost}&7, "
                        + "warmup &f{warmup}s&7, cooldown &f{cooldown}s");
        o.addProperty("rtp-info-unknown", "&cUnknown random-teleport profile: &f{name}");
        o.addProperty("rtp-usage-world", "&7Usage: &f/rtp world <world> [player]");
        o.addProperty("rtp-usage-profile", "&7Usage: &f/rtp profile <profile> [player]");
        o.addProperty("rtp-usage-biome", "&7Usage: &f/rtp biome <biome>");
        o.addProperty("rtp-admin-reloaded", "&aReloaded random teleport ({count} profiles).");
        o.addProperty("rtp-admin-test-start", "&7Testing profile &f{profile}&7...");
        o.addProperty("rtp-admin-test-found", "&aFound: &f{x}, {y}, {z} &7in {attempts} attempts.");
        o.addProperty("rtp-admin-test-none", "&cNo destination after {attempts} attempts ({reason}).");
        o.addProperty("rtp-admin-preview",
                "&7{profile} &8» &7{shape} in &f{world}&7, center &f{center}&7, radius &f{min}-{max}&7, Y &f{y}");
        o.addProperty("rtp-admin-debug-start", "&7Debugging profile &f{profile}&7...");
        o.addProperty("rtp-admin-debug-summary", "&7Found: &f{found} &7| Attempts: &f{attempts} &7| Rejections:");
        o.addProperty("rtp-admin-debug-line", "&7 - &f{reason}&7: &c{count}");
        o.addProperty("rtp-admin-usage-world", "&7Usage: &f/rtpadmin enable|disable <world>");
        o.addProperty("rtp-admin-world-enabled", "&aRandom teleport enabled for world &f{world}&a.");
        o.addProperty("rtp-admin-world-disabled", "&7Random teleport disabled for world &f{world}&7.");
        o.addProperty("rtp-admin-setcenter", "&aProfile &f{profile} &acenter set to &f{x}, {z}&a.");
        o.addProperty("rtp-admin-cache-cleared", "&7Cleared cached destinations for &f{profile}&7.");
        o.addProperty("rtp-admin-queue-header", "&d&lRTP Queue &7- active &f{active}&7, queued &f{queued}");
        o.addProperty("rtp-admin-queue-empty", "&7No active or queued searches.");
        o.addProperty("rtp-admin-cancel-ok", "&aCancelled &f{player}&a's random teleport.");
        o.addProperty("rtp-admin-cancel-none", "&c{player} has no random teleport in progress.");
        o.addProperty("rtp-admin-usage-cancel", "&7Usage: &f/rtpadmin cancel <player>");
        o.addProperty("rtp-admin-spread", "&aSpreading &f{count} &aplayers with profile &f{profile}&a.");
        o.addProperty("rtp-admin-usage-spread", "&7Usage: &f/rtpadmin spread <profile> <all|world:name>");
        o.addProperty("rtp-admin-queuelogin-ok", "&aQueued &f{player} &afor profile &f{profile} &aon next login.");
        o.addProperty("rtp-admin-usage-queuelogin", "&7Usage: &f/rtpadmin queue-login <player> <profile>");
        o.addProperty("rtp-admin-usage-profile", "&7Usage: &f/rtpadmin <test|preview|debug|setcenter> <profile>");
        o.addProperty("rtp-admin-help",
                "&7/rtpadmin &f<reload|test|preview|debug|enable|disable|setcenter|clearcache|queue|"
                        + "cancel|spread|queue-login>");
        o.addProperty("warp-unknown", "&cUnknown warp: &f{warp}");
        o.addProperty("warp-none", "&7There are no warps available.");
        o.addProperty("warp-list", "&7Warps: &f{warps}");
        o.addProperty("warp-temp-world", "&cYou cannot set a warp in a temporary world - it would vanish when the world is removed.");
        o.addProperty("warp-saved", "&aWarp &f{warp} &asaved.");
        o.addProperty("warp-renamed", "&aWarp &f{old} &arenamed to &f{new}&a.");
        o.addProperty("warp-name-taken", "&cA warp named &f{warp} &calready exists.");
        o.addProperty("warp-save-failed", "&cCould not save that warp.");
        o.addProperty("warp-deleted", "&aWarp &f{warp} &adeleted.");
        o.addProperty("warp-delete-failed", "&cCould not delete &f{warp}");
        o.addProperty("pwarp-unknown", "&cUnknown player warp: &f{warp}");
        o.addProperty("pwarp-temp-world", "&cYou cannot create a player warp in a temporary world.");
        o.addProperty("pwarp-created", "&aPlayer warp &f{warp} &acreated.");
        o.addProperty("pwarp-name-taken", "&cThat player-warp name is already taken.");
        o.addProperty("pwarp-limit", "&cYou have reached your player-warp limit ({limit}).");
        o.addProperty("pwarp-deleted", "&aPlayer warp &f{warp} &adeleted.");
        o.addProperty("pwarp-not-owned", "&cNo player warp of yours named &f{warp}");
        o.addProperty("pwarp-updated", "&aPlayer warp &f{warp} &aupdated.");
        o.addProperty("pwarp-update-failed", "&cCould not update that player warp.");
        o.addProperty("pwarp-renamed", "&aPlayer warp renamed to &f{warp}&a.");
        o.addProperty("pwarp-rename-failed", "&cCould not rename (name taken or invalid).");
        o.addProperty("pwarp-moved", "&aPlayer warp &f{warp} &amoved to your location.");
        o.addProperty("pwarp-move-failed", "&cCould not move that player warp.");
        o.addProperty("pwarp-missing-owned", "&cYou have no player warp named &f{warp}");
        o.addProperty("spawn-not-set", "&cSpawn has not been set. An admin can use /setspawn.");
        o.addProperty("spawn-world-not-loaded",
                "&c{label} is in world &f{world}&c, which is not loaded. An admin should re-run /setspawn.");
        o.addProperty("spawn-temp-world", "&cYou cannot set {target} in a temporary world - it would vanish when the world is removed.");
        o.addProperty("spawn-global-set", "&aGlobal spawn set to your current location.");
        o.addProperty("spawn-world-set", "&aSpawn for world &f{world} &aset to your current location.");
        o.addProperty("home-missing", "&cNo home named &f{home}&c.");
        o.addProperty("home-missing-use-list", "&cNo home named &f{home}&c. Use /homes to list.");
        o.addProperty("home-set", "&aHome &f{home} &aset.");
        o.addProperty("home-limit", "&cYou have reached your home limit ({limit}).");
        o.addProperty("home-deleted", "&aHome &f{home} &adeleted.");
        o.addProperty("home-renamed", "&aHome &f{old} &arenamed to &f{new}&a.");
        o.addProperty("home-renamed-ui", "&aHome renamed to &f{home}&a.");
        o.addProperty("home-rename-failed", "&cCould not rename (name taken or invalid).");
        o.addProperty("home-moved", "&aHome &f{home} &amoved to your location.");
        o.addProperty("home-move-failed", "&cCould not move that home.");
        o.addProperty("home-name-required", "&cA home needs a name.");
        o.addProperty("home-name-taken", "&cYou already have a home named &f{home}&c.");
        o.addProperty("home-none", "&7You have no homes. Use /sethome <name>.");
        o.addProperty("home-list", "&7Homes (&f{count}&7/&f{limit}&7): &f{homes}");
        o.addProperty("mail-notify-new", "&eYou have new mail from &f{sender}&e. &7/mail");
        o.addProperty("mail-notify-join-unread", "&eYou have &f{unread} &eunread mail. &7/mail");
        o.addProperty("mail-notify-join-any", "&eYou have &f{count} &email in your inbox. &7/mail");
        o.addProperty("mail-inbox-empty", "&7Your inbox is empty.");
        o.addProperty("mail-inbox-header", "&7Inbox (&f{count}&7):");
        o.addProperty("mail-inbox-entry", "{read_color}[{id}] &7from &f{sender}&7: &f{body}");
        o.addProperty("mail-read-line", "&7from &f{sender}&7: &f{body}");
        o.addProperty("mail-sent", "&aMail sent to &f{player}");
        o.addProperty("mail-sent-offline", "&aMail sent to &f{player} &7(offline)");
        o.addProperty("mail-sent-all", "&aMail sent to &f{count} &aplayers.");
        o.addProperty("mail-deleted", "&aMail deleted.");
        o.addProperty("mail-cleared", "&aInbox cleared.");
        o.addProperty("mail-missing", "&cNo mail with id &f{id}");
        o.addProperty("mail-player-never-joined", "&cNo player named &f{player} &chas joined this server.");
        o.addProperty("mail-send-player-message", "&cUsage: /mail send <player> <message>");
        o.addProperty("mail-sendall-message", "&cUsage: /mail sendall <message>");
        o.addProperty("mail-marked-all-read", "&aMarked &f{count} &amail as read.");
        o.addProperty("mail-archived", "&aMail archived.");
        o.addProperty("mail-restored", "&aMail restored.");
        o.addProperty("mail-claimed", "&aRewards claimed!");
        o.addProperty("mail-nothing-to-claim", "&7There is nothing to claim on this mail.");
        o.addProperty("mail-claim-no-space", "&cMake room in your inventory to claim these rewards.");
        o.addProperty("mail-attach-insufficient", "&cYou no longer have the items you tried to attach.");
        o.addProperty("mail-announcement-sent", "&aAnnouncement sent to &f{count} &aplayers.");
        o.addProperty("mail-announcement-missing", "&cThat announcement is no longer in the history.");
        o.addProperty("mail-audience-need-node", "&cEnter a permission node for the Permission audience.");
        o.addProperty("mail-audience-need-player", "&cEnter a player name for the One Player audience.");
        o.addProperty("kit-none", "&7No kits are configured.");
        o.addProperty("kit-list-header", "&7Available kits:");
        o.addProperty("kit-list-entry", "&8- &f{kit}{meta}{description}");
        o.addProperty("kit-unknown", "&cUnknown kit: &f{kit}&c. Use /kit list.");
        o.addProperty("kit-no-access", "&cYou do not have access to the &f{kit} &ckit.");
        o.addProperty("kit-need-playtime", "&cYou need &f{duration} &cmore playtime to claim this kit.");
        o.addProperty("kit-once", "&cThe &f{kit} &ckit can only be claimed once.");
        o.addProperty("kit-cooldown", "&cYou can claim &f{kit} &cagain in &f{duration}&c.");
        o.addProperty("kit-claimed", "&aYou received the &f{kit} &akit!");
        o.addProperty("kit-given", "&aGave kit &f{kit} &ato &f{player}&a.");
        o.addProperty("kit-cannot-afford", "&cYou cannot afford this kit (&f{cost}&c).");
        o.addProperty("nick-error-length", "&cNicknames must be {min}-{max} characters.");
        o.addProperty("nick-error-characters", "&cNicknames may only contain letters, digits, and underscores.");
        o.addProperty("nick-error-blocked", "&cThat nickname is not allowed.");
        o.addProperty("nick-error-impersonation", "&cThat nickname matches another player's real name.");
        o.addProperty("nick-error-profile", "&cYour profile is not loaded yet - try again in a moment.");
        o.addProperty("nick-enter-first", "&cEnter a nickname first.");
        o.addProperty("nick-set", "&aYour nickname is now &f{nickname}&a.");
        o.addProperty("nick-set-other", "&aNickname for &f{player} &ais now &f{nickname}&a.");
        o.addProperty("nick-set-by-admin", "&7An admin set your nickname to &f{nickname}&7.");
        o.addProperty("nick-cleared", "&aYour nickname was removed.");
        o.addProperty("afk-now", "&7You are now AFK.");
        o.addProperty("afk-returned", "&7You are no longer AFK.");
        o.addProperty("afk-reward", "&aAFK reward: &f{amount}");
        o.addProperty("afk-reward-item", "&aAFK reward: &f{quantity}x {item}");
        o.addProperty("afk-reward-zone-hint", "&7You will only earn AFK rewards inside an AFK reward zone.");
        o.addProperty("afk-zone-teleported", "&7Teleported to AFK zone '&f{name}&7'. You will be sent back when you return.");
        o.addProperty("afk-zone-returned", "&7You have been returned to your previous location.");
        o.addProperty("afkzone-usage", "&7/afkzone &fpos1&7|&fpos2&7|&fcreate <name>&7|&fdelete <name>&7|&fpermission <name> <node|->&7|&fdefault <name|->&7|&flist&7|&fcheck");
        o.addProperty("afkzone-pos1", "&aAFK zone corner 1 set: &f{pos}");
        o.addProperty("afkzone-pos2", "&aAFK zone corner 2 set: &f{pos}");
        o.addProperty("afkzone-need-corners", "&cSet both corners first: stand at each corner and run &f/afkzone pos1 &cand &f/afkzone pos2&c.");
        o.addProperty("afkzone-cross-world", "&cBoth corners must be in the same world.");
        o.addProperty("afkzone-invalid-name", "&cZone names may only use letters, numbers, '-' and '_' (max 32 characters).");
        o.addProperty("afkzone-exists", "&cAn AFK zone named '&f{name}&c' already exists.");
        o.addProperty("afkzone-created", "&aAFK zone '&f{name}&a' created (&f{size}&a).");
        o.addProperty("afkzone-zone-not-required-hint", "&7Note: rewards.requireInZone is &ffalse&7, so AFK rewards are earned anywhere. Enable it in modules/afk/config.json to restrict rewards to zones.");
        o.addProperty("afkzone-deleted", "&aAFK zone '&f{name}&a' deleted.");
        o.addProperty("afkzone-unknown", "&cNo AFK zone named '&f{name}&c'.");
        o.addProperty("afkzone-none", "&7No AFK zones are defined yet.");
        o.addProperty("afkzone-list-header", "&7AFK zones:");
        o.addProperty("afkzone-list-entry", "&8- &f{name} &7({world}&7, {size})");
        o.addProperty("afkzone-list-entry-perm", "&8- &f{name} &7({world}&7, {size}&7, requires &f{permission}&7)");
        o.addProperty("afkzone-permission-set", "&aAFK zone '&f{name}&a' now requires &f{permission}&a.");
        o.addProperty("afkzone-permission-cleared", "&aAFK zone '&f{name}&a' no longer requires a permission.");
        o.addProperty("afkzone-default-set", "&aAFK zone '&f{name}&a' is now the default auto-AFK teleport zone.");
        o.addProperty("afkzone-default-cleared", "&aCleared the default auto-AFK zone; one is now picked automatically.");
        o.addProperty("afkzone-list-default", "&7Default auto-AFK zone: &f{name}");
        o.addProperty("afkzone-check-in", "&aYou are inside AFK zone '&f{name}&a'.");
        o.addProperty("afkzone-check-out", "&7You are not inside any AFK zone.");
        o.addProperty("flight-enabled", "&aFlight enabled.");
        o.addProperty("flight-disabled", "&cFlight disabled.");
        o.addProperty("flight-unavailable", "&cFlight is not available right now.");
        o.addProperty("flight-out-of-money", "&cYou ran out of money for flight.");
        o.addProperty("flight-charged", "&7Flight cost &f{cost} &7withdrawn. Balance: &f{balance}&7.");
        o.addProperty("flight-not-enough-money", "&cNot enough money for flight (&f{cost}&c/minute).");
        o.addProperty("flight-state", "&7Flight {state}&7.");
        o.addProperty("flight-state-other", "&7Flight {state} &7for &e{player}&7.");
        o.addProperty("flight-cost", "&7Flight costs &f{cost} &7per minute.");
        o.addProperty("inventory-cleared", "&aYour inventory was cleared (a backup snapshot was taken).");
        o.addProperty("inventory-clear-failed", "&cCould not clear your inventory.");
        o.addProperty("inventory-cleared-by-admin", "&7Your inventory was cleared by an admin.");
        o.addProperty("inventory-clear-all", "&aCleared &f{count} &ainventories (protected players skipped).");
        o.addProperty("inventory-clear-other", "&aCleared &f{player}&a's inventory.");
        o.addProperty("inventory-clear-other-failed", "&cCould not clear that inventory.");
        o.addProperty("inventory-usage", "&7Usage: &f/inventory clear [player|all] &7or &f/inventory restore <player>");
        o.addProperty("inventory-snapshot-missing", "&cThat snapshot no longer exists.");
        o.addProperty("inventory-protected", "&eSkipped &f{player}&e because their inventory is protected.");
        o.addProperty("chat-channel-unavailable", "&cThat chat channel is not available.");
        o.addProperty("chat-channel-no-speak", "&cYou cannot speak in that channel.");
        o.addProperty("chat-channel-join-usage", "&cUsage: /channel join <channel> [password]");
        o.addProperty("chat-channel-leave-usage", "&cUsage: /channel leave <channel>");
        o.addProperty("chat-channel-switch-usage", "&cUsage: /channel switch <channel> [password]");
        o.addProperty("chat-channel-no-temp-owned", "&cYou do not own a temporary channel. Create one with /channel temp <id>.");
        o.addProperty("chat-channel-temp-no-permission", "&cYou cannot create temporary channels.");
        o.addProperty("chat-channel-temp-usage", "&cUsage: /channel temp <id> [password|-] [prefix|-] [alias1,alias2|-] [permission]");
        o.addProperty("chat-channel-temp-created", "&aCreated temporary channel &f{channel}&a.");
        o.addProperty("chat-channel-temp-failed", "&cCould not create that temporary channel.");
        o.addProperty("chat-channel-joined", "&aNow listening to &f{channel}&a.");
        o.addProperty("chat-channel-already-listening", "&7Already listening to &f{channel}&7.");
        o.addProperty("chat-channel-password-required", "&cThat channel requires a password.");
        o.addProperty("chat-channel-no-listen", "&cYou cannot listen to that channel.");
        o.addProperty("chat-channel-unknown", "&cUnknown chat channel.");
        o.addProperty("chat-channel-left", "&7Stopped listening to &f{channel}&7.");
        o.addProperty("chat-channel-not-listening", "&7You are not listening to &f{channel}&7.");
        o.addProperty("chat-channel-current", "&cSwitch to another channel before leaving this one.");
        o.addProperty("chat-channel-switched", "&7Channel set to &f{channel}");
        o.addProperty("chat-channel-list-header", "&bChat Channels &8| &7Speaking: &f{channel}");
        o.addProperty("chat-channel-list-empty", "&7No chat channels are available.");
        o.addProperty("chat-channel-list-entry", "&8- &f{channel}{aliases} &8| {status}");
        o.addProperty("chat-channel-list-help", "&7Use &f/channel join <name> [password] &7for password channels.");
        o.addProperty("chat-channel-temp-name-required", "&cA temporary channel needs a name.");
        o.addProperty("chat-channel-temp-updated", "&aTemporary channel updated.");
        o.addProperty("chat-channel-temp-closed", "&aTemporary channel closed.");
        o.addProperty("chat-channel-temp-not-owned", "&cYou do not own a temporary channel.");
        o.addProperty("pm-received", "&7[&d{sender} &7-> &dyou&7] &f{message}");
        o.addProperty("pm-sent", "&7[&dyou &7-> &d{target}&7] &f{message}");
        o.addProperty("pm-spy", "&8[spy] &7{sender} -> {target}: {message}");
        o.addProperty("pm-no-permission", "&cYou do not have permission to send private messages.");
        o.addProperty("pm-reply-no-permission", "&cYou do not have permission to reply to private messages.");
        o.addProperty("pm-reply-none", "&cYou have no one to reply to.");
        o.addProperty("announcement-broadcast", "{message}");
        o.addProperty("tutorial-started", "&aTutorial &f{tutorial} &astarted.");
        o.addProperty("tutorial-started-other", "&aStarted tutorial &f{tutorial} &afor &f{player}&a.");
        o.addProperty("tutorial-unknown", "&cUnknown tutorial: &f{tutorial}");
        o.addProperty("tutorial-disabled", "&cTutorial &f{tutorial} &cis disabled.");
        o.addProperty("tutorial-already-active", "&cA tutorial is already running for that player.");
        o.addProperty("tutorial-already-completed",
                "&cThat tutorial was already completed and does not allow replays (use --force).");
        o.addProperty("tutorial-requirements-not-met",
                "&cThe requirements for tutorial &f{tutorial} &care not met.");
        o.addProperty("tutorial-start-error", "&cThe tutorial could not be started. See the server log.");
        o.addProperty("tutorial-stopped", "&7Tutorial stopped. Your character has been restored.");
        o.addProperty("tutorial-stopped-other", "&aStopped the tutorial for &f{player}&a.");
        o.addProperty("tutorial-not-in", "&cYou are not in a tutorial.");
        o.addProperty("tutorial-not-in-other", "&f{player} &cis not in a tutorial.");
        o.addProperty("tutorial-skipped", "&7Tutorial skipped.");
        o.addProperty("tutorial-reset-done", "&aReset tutorial &f{tutorial} &afor &f{player}&a.");
        o.addProperty("tutorial-complete-done", "&aMarked tutorial &f{tutorial} &acompleted for &f{player}&a.");
        o.addProperty("tutorial-status-active",
                "&d{player} &7is in tutorial &f{tutorial} &7({state}, {seconds}s).");
        o.addProperty("tutorial-status-idle", "&d{player} &7is not in a tutorial.");
        o.addProperty("tutorial-status-completed", "&7Completed ({count}): &f{tutorials}");
        o.addProperty("tutorial-list-header", "&d&lTutorials &7({count}):");
        o.addProperty("tutorial-list-empty", "&7No tutorials are defined.");
        o.addProperty("tutorial-page-unknown", "&cUnknown tutorial page: &f{page}");
        o.addProperty("tutorial-page-opened", "&aOpened page &f{page} &afor &f{player}&a.");
        o.addProperty("tutorial-reloaded",
                "&aTutorial module reloaded: &f{tutorials} &atutorial(s), &f{pages} &apage(s).");
        o.addProperty("tutorial-debug-on", "&aTutorial debug logging enabled.");
        o.addProperty("tutorial-debug-off", "&7Tutorial debug logging disabled.");
        o.addProperty("tutorial-chat-blocked", "&cChat is disabled during the tutorial.");
        o.addProperty("customcommands-unknown", "&cUnknown custom command: &f{command}");
        o.addProperty("customcommands-command-disabled", "&cThat command is currently disabled.");
        o.addProperty("customcommands-cooldown",
                "&cPlease wait {seconds}s before using &f/{command} &cagain.");
        o.addProperty("customcommands-usage", "&cUsage: &f/{command} {usage}");
        o.addProperty("customcommands-arg-missing", "&cMissing argument &f{arg} &7({expected})");
        o.addProperty("customcommands-arg-invalid",
                "&cInvalid value for &f{arg}&c: &f{value} &7(expected {expected})");
        o.addProperty("customcommands-player-not-online",
                "&cArgument &f{arg}&c: player &f{value} &cis not online.");
        o.addProperty("customcommands-condition-failed", "&cYou cannot use that command right now.");
        o.addProperty("customcommands-reloaded",
                "&aCustom commands reloaded: &f{count} &aactive, &f{errors} &aissue(s).");
        o.addProperty("vault-no-access", "&cYou do not have access to vault &f#{vault}&c.");
        o.addProperty("vault-invalid-number", "&cVault numbers must be between 1 and {max}.");
        o.addProperty("vault-locked-elsewhere",
                "&cThat vault is currently open on &f{server}&c. Try again shortly.");
        o.addProperty("vault-locked-local", "&cThat vault is already open.");
        o.addProperty("vault-storage-unavailable",
                "&cVault storage is temporarily unavailable. No changes were made.");
        o.addProperty("vault-conflict",
                "&cVault changed during save. The latest data was preserved and a recovery snapshot was created.");
        o.addProperty("vault-saved", "&aVault &f#{vault} &asaved.");
        o.addProperty("vault-save-failed", "&cCould not save vault &f#{vault}&c. Your items are still in the vault session.");
        o.addProperty("vault-readonly", "&7This vault is open in read-only mode.");
        o.addProperty("vault-readonly-downgrade",
                "&cThe vault lock could not be renewed; the vault is now read-only.");
        o.addProperty("vault-overflow-notice",
                "&eYour current rank allows {rows} row(s). Items beyond that remain safe but unavailable.");
        o.addProperty("vault-item-blocked", "&cThat item cannot be stored in a vault.");
        o.addProperty("vault-full", "&cThis vault has no free slots within your allowed rows.");
        o.addProperty("vault-deposit-failed", "&cCould not deposit that item. It was returned to you.");
        o.addProperty("vault-admin-opened", "&7Opened &f{player}&7's vault &f#{vault} &7({mode}).");
        o.addProperty("vault-admin-viewing", "&eStaff member {player} is viewing your vault #{vault}.");
        o.addProperty("vault-admin-unlocked", "&aForce-unlocked vault &f#{vault} &afor &f{player}&a.");
        o.addProperty("vault-admin-unlock-none", "&7No lock is held on that vault.");
        o.addProperty("vault-admin-target-required", "&cEnter a player name to view their vaults.");
        o.addProperty("vault-restored", "&aRestored vault &f#{vault} &afor &f{player} &afrom backup &f{backup}&a.");
        o.addProperty("vault-restore-unknown-backup", "&cUnknown backup id: &f{backup}");
        o.addProperty("vault-player-never-joined", "&cNo vault data found for &f{player}&c.");
        o.addProperty("vault-metadata-updated", "&aVault &f#{vault} &aupdated.");
        o.addProperty("vault-name-invalid", "&cThat vault name is not allowed.");
        o.addProperty("vault-icon-invalid", "&cThat item cannot be used as a vault icon.");
        o.addProperty("vault-icon-consumed", "&7One &f{item} &7was consumed for the vault icon.");
        o.addProperty("vault-logs-header", "&d&lVault Logs &7- &f{player} &7({count} entries)");
        o.addProperty("vault-logs-entry", "&8[{time}] &7{actor} &f{action} &7vault #{vault} on {server}");
        o.addProperty("vault-logs-empty", "&7No vault log entries for that player.");
        o.addProperty("vault-reloaded", "&aPlayer Vaults configuration reloaded.");

        // ----- Patch Notes -----
        o.addProperty("patchnotes-notify-join",
                "&e{count} &7new patch note(s)! Use &f/{command} &7to read them.");
        o.addProperty("patchnotes-reloaded", "&aPatch notes reloaded. &7{count} loaded, {errors} error(s).");
        o.addProperty("patchnotes-marked-read", "&aMarked all patch notes as read.");
        o.addProperty("patchnotes-marked-read-other", "&aMarked all patch notes as read for &f{player}&a.");
        o.addProperty("patchnotes-opened-other", "&7Opened patch notes for &f{player}&7.");
        o.addProperty("patchnotes-none", "&7There are no patch notes yet.");
        o.addProperty("patchnotes-list-header", "&d&lPatch Notes &7({count})");
        o.addProperty("patchnotes-list-entry", "{pin}&f{title} &7v{version} &8- &7{date}");

        return o;
    }
}
