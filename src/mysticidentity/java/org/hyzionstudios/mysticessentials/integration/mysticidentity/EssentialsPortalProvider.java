package org.hyzionstudios.mysticessentials.integration.mysticidentity;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.hyzionstudios.mysticessentials.api.item.ItemNames;
import org.hyzionstudios.mysticessentials.api.model.MailMessage;
import org.hyzionstudios.mysticessentials.api.service.MailService;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.modules.nick.NickModule;
import org.hyzionstudios.mysticessentials.modules.patchnotes.PatchNote;
import org.hyzionstudios.mysticessentials.modules.patchnotes.PatchNotesModule;
import org.hyzionstudios.mysticessentials.modules.playervaults.PlayerVaultModule;
import org.hyzionstudios.mysticessentials.modules.playervaults.model.PlayerVault;
import org.hyzionstudios.mysticessentials.modules.playervaults.model.VaultItemStack;
import org.hyzionstudios.mysticessentials.modules.playervaults.service.PlayerVaultServiceImpl;
import org.hyzionstudios.mysticidentity.api.portal.PortalBlock;
import org.hyzionstudios.mysticidentity.api.portal.PortalBlock.Stat;
import org.hyzionstudios.mysticidentity.api.portal.PortalBlock.Tone;
import org.hyzionstudios.mysticidentity.api.portal.PortalHealth;
import org.hyzionstudios.mysticidentity.api.portal.PortalManifest;
import org.hyzionstudios.mysticidentity.api.portal.PortalProvider;
import org.hyzionstudios.mysticidentity.api.portal.PortalRequest;
import org.hyzionstudios.mysticidentity.api.portal.PortalView;

/**
 * MysticEssentials on the MysticIdentity web portal (Player Portal bible §8, §21
 * "MysticEssentials"): mail, vaults and patch notes, each shown only while its module is on, and a
 * My identity card with unread mail, vault usage and nickname.
 *
 * <p>Everything is read-only and nothing a web visit does writes. Mail is read, never marked
 * read, and attachments are claimed in game (§8). Vaults are drawn from storage without opening
 * or locking them, so the cross-server lock that prevents duplication is never touched (§8, §15
 * "Remote Inventory Policy"). A player's nickname comes from the profile this server holds, or
 * from storage — never through {@code PlayerProfileService.load}, which would record a join.
 *
 * <p>With the default JSON storage each server keeps its own mail and vaults; the portal shows
 * what the dashboard's own server holds. A network needs MySQL storage for one network-wide
 * answer.
 */
final class EssentialsPortalProvider implements PortalProvider {

    static final String MODULE_ID = "essentials";
    static final String SUMMARY_VIEW = "essentials.view";
    static final String MAIL_READ = "essentials.mail.read";
    static final String VAULT_VIEW = "essentials.vault.view";
    static final String PATCHES_VIEW = "essentials.patches.view";
    static final String NICKNAME_VIEW = "essentials.nickname.view";

    /** {@code NickModule.METADATA_KEY}: where the nickname lives in a player profile's metadata. */
    private static final String NICKNAME_KEY = "nickname";
    private static final String PROFILES_NAMESPACE = "players";
    private static final int PATCHES_SHOWN = 10;
    private static final int MAIL_SHOWN = 50;

    private final MysticCore core;

    EssentialsPortalProvider(MysticCore core) {
        this.core = core;
    }

    @Override
    public String moduleId() {
        return MODULE_ID;
    }

    @Override
    public String displayName() {
        return "MysticEssentials";
    }

    @Override
    public String version() {
        return core.getVersion();
    }

    /** Areas for the modules that are on right now; the card opens the first of them. */
    @Override
    public PortalManifest manifest() {
        boolean mail = core.getMailService() != null;
        boolean vaults = vaults().isPresent();
        boolean patches = patchNotes().isPresent();
        PortalManifest.Builder manifest = PortalManifest.builder()
                .capability(PortalManifest.Capability.view(SUMMARY_VIEW,
                        "The MysticEssentials card on My identity (shows only what the other switches allow)."))
                .capability(PortalManifest.Capability.view(MAIL_READ, "Read mail. Attachments are claimed in game."))
                .capability(PortalManifest.Capability.view(VAULT_VIEW, "See vault contents, read-only."))
                .capability(PortalManifest.Capability.view(PATCHES_VIEW, "Patch notes."))
                .capability(PortalManifest.Capability.view(NICKNAME_VIEW, "The player's nickname on the card."));
        String cardArea = null;
        if (mail) {
            manifest.area("mail", "Mail", PortalManifest.Group.PERSONAL, 10,
                    new PortalManifest.Page("inbox", "Inbox", MAIL_READ));
            manifest.widget("mail", "Mail", MAIL_READ, 40, PortalManifest.Size.SMALL);
            cardArea = "mail";
        }
        if (vaults) {
            manifest.area("vaults", "Vaults", PortalManifest.Group.MY_GAME, 50,
                    new PortalManifest.Page("vaults", "Vaults", VAULT_VIEW));
            cardArea = cardArea == null ? "vaults" : cardArea;
        }
        if (patches) {
            manifest.area("patches", "Patch notes", PortalManifest.Group.COMMUNITY, 20,
                    new PortalManifest.Page("latest", "Latest", PATCHES_VIEW));
            manifest.widget("patch", "Latest update", PATCHES_VIEW, 60, PortalManifest.Size.SMALL);
            cardArea = cardArea == null ? "patches" : cardArea;
        }
        if (cardArea != null) {
            manifest.card(SUMMARY_VIEW, cardArea);
        }
        return manifest.characterScoped(true).build();
    }

    @Override
    public PortalHealth health() {
        return core.getStorageService() == null
                ? PortalHealth.offline("MysticEssentials is not running on this server.")
                : PortalHealth.available();
    }

    @Override
    public CompletionStage<PortalView> render(PortalRequest request) {
        UUID player = request.characterId();
        if (player == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("no character"));
        }
        Locale locale = request.locale() == Locale.ROOT ? Locale.UK : request.locale();
        try {
            return switch (request.kind() + ":" + request.targetId()) {
                case "WIDGET:mail" -> mailWidget(player);
                case "WIDGET:patch" -> CompletableFuture.completedFuture(PortalView.of(patchWidget(locale)));
                case "CARD:mail", "CARD:vaults", "CARD:patches" -> card(request, player);
                case "PAGE:inbox" -> inbox(player, locale);
                case "PAGE:vaults" -> vaultPage(player);
                case "PAGE:latest" -> CompletableFuture.completedFuture(PortalView.of(patchPage(locale)));
                default -> throw new IllegalArgumentException("no such target: " + request.targetId());
            };
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    // ----- Mail -------------------------------------------------------------------------------

    private CompletableFuture<PortalView> mailWidget(UUID player) {
        MailService mail = requireMail();
        return mail.inbox(player).thenApply(inbox -> {
            long unread = visible(inbox).stream().filter(message -> !message.isRead()).count();
            return PortalView.of(
                    PortalBlock.Stats.of(new Stat("Unread", unread == 1 ? "1 message" : unread + " messages", null,
                            unread > 0 ? Tone.ACCENT : Tone.NEUTRAL)),
                    new PortalBlock.Link("Open mail", "mail", null));
        });
    }

    private CompletableFuture<PortalView> inbox(UUID player, Locale locale) {
        MailService mail = requireMail();
        return mail.inbox(player).thenApply(inbox -> {
            List<MailMessage> shown = new ArrayList<>(visible(inbox));
            shown.sort(Comparator.comparing((MailMessage message) -> safe(message.getSentDate())).reversed());
            long archived = inbox.stream().filter(message -> message.isArchived() && !message.isDeleted()).count();
            List<PortalBlock.Item> items = new ArrayList<>();
            for (MailMessage message : shown.subList(0, Math.min(MAIL_SHOWN, shown.size()))) {
                items.add(new PortalBlock.Item(subject(message), message.getBody(),
                        sender(message) + " · " + dateTime(message.getSentDate(), locale),
                        badge(message), message.isRead() ? Tone.NEUTRAL : Tone.ACCENT));
            }
            List<PortalBlock> blocks = new ArrayList<>();
            blocks.add(new PortalBlock.Notice(Tone.INFO, null,
                    "Mail is read-only here. Items and currency attached to mail are claimed in game."));
            blocks.add(new PortalBlock.Items(items, "No mail."));
            if (archived > 0) {
                blocks.add(new PortalBlock.Text(archived + (archived == 1 ? " archived message is" : " archived messages are")
                        + " in your mailbox in game."));
            }
            return PortalView.of(blocks);
        });
    }

    private static List<MailMessage> visible(List<MailMessage> inbox) {
        return inbox.stream().filter(message -> !message.isArchived() && !message.isDeleted()).toList();
    }

    private static String subject(MailMessage message) {
        String subject = message.getSubject();
        if (subject != null && !subject.isBlank()) {
            return subject;
        }
        String body = message.getBody() == null ? "" : message.getBody().strip();
        String firstLine = body.lines().findFirst().orElse("");
        if (firstLine.isBlank()) {
            return message.hasRewards() ? "A delivery" : "A message";
        }
        return firstLine.length() > 60 ? firstLine.substring(0, 59) + "…" : firstLine;
    }

    private static String sender(MailMessage message) {
        if (message.isAnnouncement()) {
            return "Announcement";
        }
        String name = message.getSenderName();
        return name == null || name.isBlank() ? "System" : name;
    }

    private static String badge(MailMessage message) {
        if (message.hasRewards() && !message.isClaimed()) {
            return "Claim in game";
        }
        return message.isRead() ? null : "Unread";
    }

    // ----- Vaults ------------------------------------------------------------------------------

    private CompletableFuture<PortalView> vaultPage(UUID player) {
        PlayerVaultServiceImpl vaults = vaults().orElseThrow(() -> new IllegalStateException("vaults are off"));
        int columns = Math.max(1, Math.min(12, vaults.config().slotsPerRow));
        return vaults.vaultNumbers(player).thenCompose(numbers -> {
            if (numbers.isEmpty()) {
                return CompletableFuture.completedFuture(PortalView.of(new PortalBlock.Empty(
                        "You have no vaults yet", "Open your first one in game with /pv.")));
            }
            List<CompletableFuture<Optional<PlayerVault>>> loads = new ArrayList<>();
            for (int number : numbers) {
                loads.add(vaults.getVault(player, number));
            }
            return CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).thenApply(ignored -> {
                List<PortalBlock> blocks = new ArrayList<>();
                blocks.add(new PortalBlock.Notice(Tone.NEUTRAL, null,
                        "Vaults are view-only on the web. Nothing can be moved, withdrawn or deleted from here."));
                for (int index = 0; index < numbers.size(); index++) {
                    loads.get(index).join().ifPresent(vault -> blocks.add(vaultSection(vault, columns)));
                }
                return PortalView.of(blocks);
            });
        });
    }

    static PortalBlock vaultSection(PlayerVault vault, int columns) {
        int slots = Math.max(columns, vault.rows * columns);
        PortalBlock.Slot[] grid = new PortalBlock.Slot[slots];
        Arrays.fill(grid, PortalBlock.Slot.empty());
        int used = 0;
        for (VaultItemStack stack : vault.items) {
            if (stack == null || stack.itemId == null || stack.slot < 0 || stack.slot >= slots) {
                continue;
            }
            used++;
            String detail = stack.maxDurability > 0
                    ? "Durability " + ItemNames.number(stack.durability) + " / " + ItemNames.number(stack.maxDurability)
                    : null;
            grid[stack.slot] = new PortalBlock.Slot(ItemNames.prettify(stack.itemId), detail, stack.quantity, null,
                    Tone.NEUTRAL);
        }
        String name = vault.metadata != null && vault.metadata.name != null && !vault.metadata.name.isBlank()
                ? vault.metadata.name : "Vault " + vault.vaultNumber;
        List<PortalBlock> body = used == 0
                ? List.of(new PortalBlock.Empty("This vault is empty.", null))
                : List.of(new PortalBlock.Grid(columns, List.of(grid)));
        return new PortalBlock.Section(name, used + " of " + slots + " slots used", body);
    }

    // ----- Patch notes ------------------------------------------------------------------------

    private List<PortalBlock> patchWidget(Locale locale) {
        List<PatchNote> notes = patchNotes().map(PatchNotesModule::displayedNotes).orElse(List.of());
        if (notes.isEmpty()) {
            return List.of(new PortalBlock.Empty("No patch notes yet", null));
        }
        PatchNote latest = notes.getFirst();
        return List.of(
                new PortalBlock.Items(List.of(new PortalBlock.Item(title(latest), plain(latest.summary),
                        date(latest.date, locale), latest.pinned ? "Pinned" : null, Tone.ACCENT)), null),
                new PortalBlock.Link("View patch notes", "patches", null));
    }

    private List<PortalBlock> patchPage(Locale locale) {
        List<PatchNote> notes = patchNotes().map(PatchNotesModule::displayedNotes).orElse(List.of());
        if (notes.isEmpty()) {
            return List.of(new PortalBlock.Empty("No patch notes yet", "Updates to the network will be posted here."));
        }
        List<PortalBlock> blocks = new ArrayList<>();
        for (PatchNote note : notes.subList(0, Math.min(PATCHES_SHOWN, notes.size()))) {
            List<PortalBlock> body = new ArrayList<>();
            if (note.summary != null && !note.summary.isBlank()) {
                body.add(new PortalBlock.Text(plain(note.summary)));
            }
            List<PortalBlock.Item> sections = new ArrayList<>();
            if (note.sections != null) {
                for (PatchNote.Section section : note.sections) {
                    if (section == null) {
                        continue;
                    }
                    String heading = section.title == null || section.title.isBlank() ? readable(section.type) : section.title;
                    sections.add(new PortalBlock.Item(heading.isBlank() ? "Changes" : heading, plain(section.body), null,
                            section.type == null ? null : readable(section.type), categoryTone(section.type)));
                }
            }
            if (!sections.isEmpty()) {
                body.add(new PortalBlock.Items(sections, null));
            }
            if (body.isEmpty()) {
                body.add(new PortalBlock.Text("No details were published for this update."));
            }
            String subtitle = date(note.date, locale)
                    + (note.author == null || note.author.isBlank() ? "" : " · " + note.author);
            blocks.add(new PortalBlock.Section(title(note), subtitle, body));
        }
        return blocks;
    }

    private static String title(PatchNote note) {
        String title = note.title == null || note.title.isBlank() ? "Update" : note.title.strip();
        return note.version == null || note.version.isBlank() || title.contains(note.version)
                ? title : title + " (" + note.version + ")";
    }

    private static Tone categoryTone(String type) {
        if (type == null) {
            return Tone.NEUTRAL;
        }
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "additions", "added", "new", "features" -> Tone.GOOD;
            case "changes", "changed", "balance" -> Tone.INFO;
            case "removals", "removed", "breaking" -> Tone.WARN;
            default -> Tone.NEUTRAL;
        };
    }

    /**
     * Patch bodies use a small Markdown subset (PatchMarkup). The portal draws plain text, so the
     * markers are dropped and bullets kept as bullets.
     */
    static String plain(String markup) {
        if (markup == null || markup.isBlank()) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (String line : markup.strip().split("\\R")) {
            String stripped = line.strip();
            stripped = stripped.replaceFirst("^#{1,6}\\s*", "");
            if (stripped.startsWith("- ") || stripped.startsWith("* ")) {
                stripped = "• " + stripped.substring(2);
            }
            stripped = stripped.replace("**", "").replace("__", "").replace("`", "");
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(stripped);
        }
        return text.toString();
    }

    // ----- My identity card -------------------------------------------------------------------

    private CompletableFuture<PortalView> card(PortalRequest request, UUID player) {
        MailService mail = core.getMailService();
        CompletableFuture<Optional<String>> unread = mail != null && request.holds(MAIL_READ)
                ? mail.inbox(player).thenApply(inbox -> Optional.of(String.valueOf(
                        visible(inbox).stream().filter(message -> !message.isRead()).count())))
                : CompletableFuture.completedFuture(Optional.empty());
        Optional<PlayerVaultServiceImpl> vaults = vaults();
        CompletableFuture<Optional<String>> vaultCount = vaults.isPresent() && request.holds(VAULT_VIEW)
                ? vaults.get().vaultNumbers(player).thenApply(numbers -> Optional.of(String.valueOf(numbers.size())))
                : CompletableFuture.completedFuture(Optional.empty());
        CompletableFuture<Optional<String>> nickname = request.holds(NICKNAME_VIEW)
                ? nickname(player)
                : CompletableFuture.completedFuture(Optional.empty());
        return unread.thenCombine(vaultCount, (mailCount, vaultTotal) -> List.of(mailCount, vaultTotal))
                .thenCombine(nickname, (counts, nick) -> {
                    List<Stat> stats = new ArrayList<>();
                    counts.get(0).ifPresent(value -> stats.add(Stat.of("Unread mail", value)));
                    counts.get(1).ifPresent(value -> stats.add(Stat.of("Vaults", value)));
                    if (request.holds(NICKNAME_VIEW)) {
                        stats.add(Stat.of("Nickname", nick.orElse("None")));
                    }
                    return stats.isEmpty()
                            ? PortalView.of(new PortalBlock.Empty("Nothing to show", null))
                            : PortalView.of(new PortalBlock.Stats(stats));
                });
    }

    /** The profile this server holds while the player is online, otherwise the stored one. */
    private CompletableFuture<Optional<String>> nickname(UUID player) {
        Optional<NickModule> nicks = module("nick", NickModule.class);
        if (nicks.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        Optional<String> held = core.getPlayerProfileService().getCached(player)
                .map(profile -> profile.getMetadata().get(NICKNAME_KEY));
        if (core.getPlayerProfileService().getCached(player).isPresent()) {
            return CompletableFuture.completedFuture(held.map(nicks.get()::plainNickname));
        }
        return core.getStorageService().load(PROFILES_NAMESPACE, player.toString()).thenApply(element ->
                storedNickname(element).map(nicks.get()::plainNickname));
    }

    static Optional<String> storedNickname(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return Optional.empty();
        }
        JsonObject profile = element.getAsJsonObject();
        if (!profile.has("metadata") || !profile.get("metadata").isJsonObject()) {
            return Optional.empty();
        }
        JsonElement nickname = profile.getAsJsonObject("metadata").get(NICKNAME_KEY);
        return nickname == null || !nickname.isJsonPrimitive() ? Optional.empty() : Optional.of(nickname.getAsString());
    }

    // ----- Pieces -----------------------------------------------------------------------------

    private MailService requireMail() {
        MailService mail = core.getMailService();
        if (mail == null) {
            throw new IllegalStateException("mail is off");
        }
        return mail;
    }

    private Optional<PlayerVaultServiceImpl> vaults() {
        return module("playervaults", PlayerVaultModule.class).map(PlayerVaultModule::serviceImpl);
    }

    private Optional<PatchNotesModule> patchNotes() {
        return module("patchnotes", PatchNotesModule.class);
    }

    private <T> Optional<T> module(String id, Class<T> type) {
        if (core.getModuleManager() == null || !core.getModuleManager().isEnabled(id)) {
            return Optional.empty();
        }
        return core.getModuleManager().getModule(id).filter(type::isInstance).map(type::cast);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String dateTime(String iso, Locale locale) {
        if (iso == null || iso.isBlank()) {
            return "—";
        }
        try {
            return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)
                    .format(Instant.parse(iso).atZone(ZoneOffset.UTC));
        } catch (DateTimeParseException notAnInstant) {
            return date(iso, locale);
        }
    }

    private static String date(String isoDate, Locale locale) {
        if (isoDate == null || isoDate.isBlank()) {
            return "—";
        }
        try {
            String day = isoDate.length() >= 10 ? isoDate.substring(0, 10) : isoDate;
            return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(LocalDate.parse(day));
        } catch (DateTimeParseException unreadable) {
            return isoDate;
        }
    }

    private static String readable(String constant) {
        if (constant == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (String word : constant.toLowerCase(Locale.ROOT).split("[_\\s-]+")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!text.isEmpty()) {
                text.append(' ');
            }
            text.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return text.toString();
    }
}
