package org.hyzionstudios.mysticessentials.modules.lootbox.ui;

import static org.hyzionstudios.mysticessentials.platform.ui.MysticPage.uiText;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.modules.lootbox.LootBoxModule;
import org.hyzionstudios.mysticessentials.modules.lootbox.config.LootBoxConfig;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxDraft;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.LootBoxRevision;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.PendingInbox;
import org.hyzionstudios.mysticessentials.modules.lootbox.model.PlayerLootState;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.OddsFormatter;
import org.hyzionstudios.mysticessentials.modules.lootbox.odds.OddsTable;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.MotionRules;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.OpeningService;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.RewardService;
import org.hyzionstudios.mysticessentials.platform.ui.MysticPage;

import com.google.gson.JsonObject;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * The LootBox screens (§4.7). Every player-facing screen that lists rewards shows
 * each one's exact chance in its own column; there is no screen where the odds
 * hide behind a tooltip. Every staff action is re-checked on the server when its
 * button is pressed.
 */
public final class LootBoxPages {

    static final String CATALOG_UI = "MysticEssentials/LootBoxCatalog.ui";
    static final String CATALOG_ROW_UI = "MysticEssentials/LootBoxCatalogRow.ui";
    static final String PREVIEW_UI = "MysticEssentials/LootBoxPreview.ui";
    static final String REWARD_ROW_UI = "MysticEssentials/LootBoxRewardRow.ui";
    static final String CONFIRM_UI = "MysticEssentials/LootBoxConfirm.ui";
    static final String REVEAL_UI = "MysticEssentials/LootBoxReveal.ui";
    static final String REVEAL_ROW_UI = "MysticEssentials/LootBoxRevealRow.ui";
    static final String HISTORY_UI = "MysticEssentials/LootBoxHistory.ui";
    static final String HISTORY_ROW_UI = "MysticEssentials/LootBoxHistoryRow.ui";
    static final String PENDING_UI = "MysticEssentials/LootBoxPending.ui";
    static final String PENDING_ROW_UI = "MysticEssentials/LootBoxPendingRow.ui";

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private LootBoxPages() {
    }

    // ----- Shared rendering ---------------------------------------------------

    /** Writes one reward row: icon, name and rarity, what it is, and its exact chance. */
    static void rewardRow(UICommandBuilder cmd, String list, int index, LootBoxDraft.RewardSpec reward,
            OddsTable.Entry entry, long total, OddsFormatter formatter) {
        String row = list + "[" + index + "]";
        cmd.append(list, REWARD_ROW_UI);
        String icon = rewardIcon(reward);
        if (icon != null) {
            cmd.set(row + " #Icon.ItemId", icon);
        }
        cmd.set(row + " #Name.TextSpans", uiText(row + " #Name.TextSpans", rewardName(reward)));
        cmd.set(row + " #Meta.TextSpans", uiText(row + " #Meta.TextSpans", rewardMeta(reward)));
        cmd.set(row + " #Odds.TextSpans", uiText(row + " #Odds.TextSpans", formatter.percent(entry.weight(), total)));
        cmd.set(row + " #OneIn.TextSpans", uiText(row + " #OneIn.TextSpans", formatter.oneIn(entry.weight(), total)));
    }

    static String rewardIcon(LootBoxDraft.RewardSpec reward) {
        return reward.item != null && reward.item.itemId != null && !reward.item.itemId.isBlank()
                ? reward.item.itemId : null;
    }

    static String rewardName(LootBoxDraft.RewardSpec reward) {
        String rarity = reward.rarity == null || reward.rarity.isBlank() ? "" : " &8· &6" + reward.rarity;
        return (reward.name == null ? reward.id : reward.name) + rarity;
    }

    static String rewardMeta(LootBoxDraft.RewardSpec reward) {
        String type = reward.type == null ? "" : reward.type.toUpperCase(Locale.ROOT);
        return switch (type) {
            case "ITEM" -> "Item" + (reward.item != null && reward.item.quantity > 1 ? " x" + reward.item.quantity : "");
            case "ECONOMY" -> "Currency: " + trimNumber(reward.amount);
            case "COMMAND" -> "Server reward";
            case "PERMISSION" -> "Permission" + (reward.durationSeconds > 0
                    ? " for " + duration(reward.durationSeconds) : " (permanent)");
            default -> type;
        };
    }

    static String rules(LootBoxRevision revision) {
        return "One reward per opening · Duplicates possible · No pity · Uses "
                + Math.max(1, revision.spec.keysPerOpening) + " key"
                + (revision.spec.cooldownSeconds > 0 ? " · Cooldown " + duration(revision.spec.cooldownSeconds) : "");
    }

    static String oddsFooter(LootBoxRevision revision, OddsTable table, OddsFormatter formatter) {
        List<Long> weights = new ArrayList<>();
        table.entries().forEach(entry -> weights.add(entry.weight()));
        String note = formatter.needsRoundingNote(weights, table.total())
                ? " · Shown chances are rounded; the exact values are used" : "";
        return "Odds ID " + table.oddsId() + " · Revision " + revision.revision + note;
    }

    static String keyNames(LootBoxModule module, LootBoxRevision revision) {
        Set<String> names = new LinkedHashSet<>();
        for (String keyId : revision.spec.keys) {
            names.add(module.registry().key(keyId)
                    .map(key -> org.hyzionstudios.mysticessentials.core.message.MysticText.stripMarkup(key.spec.name))
                    .orElse(keyId));
        }
        return String.join(" or ", names);
    }

    private static String trimNumber(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }

    static String duration(long seconds) {
        if (seconds % 86400 == 0) {
            return seconds / 86400 + "d";
        }
        if (seconds % 3600 == 0) {
            return seconds / 3600 + "h";
        }
        if (seconds % 60 == 0) {
            return seconds / 60 + "m";
        }
        return seconds + "s";
    }

    // ----- Catalog ------------------------------------------------------------

    static final class CatalogPage extends MysticPage {

        private final LootBoxModule module;
        private final int pending;
        private final boolean reducedMotion;
        /** Sent with Deposit Held Key, so a double press deposits once. */
        private final String depositRequest = UUID.randomUUID().toString();

        CatalogPage(MysticCore core, PlayerRef player, LootBoxModule module, int pending, boolean reducedMotion) {
            super(core, player, CustomPageLifetime.CanDismiss);
            this.module = module;
            this.pending = pending;
            this.reducedMotion = reducedMotion;
        }

        @Override
        public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event, Store<EntityStore> store) {
            cmd.append(CATALOG_UI);
            List<LootBoxRevision> pools = new ArrayList<>();
            for (LootBoxRevision revision : module.registry().publishedPools()) {
                if (!module.registry().isDisabled(revision.lootboxId)) {
                    pools.add(revision);
                }
            }
            cmd.set("#HeaderInfo.TextSpans", uiText("#HeaderInfo.TextSpans", pools.size() + " open · "
                    + (pending > 0 ? pending + " pending reward" + (pending == 1 ? "" : "s") + " to claim"
                            : "nothing pending")));
            cmd.set("#ListEmpty.Visible", pools.isEmpty());
            for (int i = 0; i < pools.size(); i++) {
                LootBoxRevision revision = pools.get(i);
                String row = "#PoolList[" + i + "]";
                cmd.append("#PoolList", CATALOG_ROW_UI);
                String icon = revision.spec.icon != null && !revision.spec.icon.isBlank() ? revision.spec.icon
                        : revision.spec.rewards.stream().map(LootBoxPages::rewardIcon).filter(id -> id != null)
                                .findFirst().orElse(null);
                if (icon != null) {
                    cmd.set(row + " #Icon.ItemId", icon);
                }
                cmd.set(row + " #Name.TextSpans", uiText(row + " #Name.TextSpans", revision.spec.displayName));
                cmd.set(row + " #Meta.TextSpans", uiText(row + " #Meta.TextSpans",
                        revision.spec.rewards.size() + " rewards · " + keyNames(module, revision)));
                cmd.set(row + " #Status.TextSpans", uiText(row + " #Status.TextSpans", "Revision " + revision.revision));
                event.addEventBinding(CustomUIEventBindingType.Activating, row + " #PreviewButton",
                        new EventData().put("action", "preview").put("id", revision.lootboxId));
            }
            event.addEventBinding(CustomUIEventBindingType.Activating, "#HistoryButton",
                    new EventData().put("action", "history"));
            event.addEventBinding(CustomUIEventBindingType.Activating, "#PendingButton",
                    new EventData().put("action", "pending"));
            cmd.set("#ReduceMotionButton.Visible", !reducedMotion);
            cmd.set("#FullMotionButton.Visible", reducedMotion);
            event.addEventBinding(CustomUIEventBindingType.Activating, "#ReduceMotionButton",
                    new EventData().put("action", "motion").put("to", MotionRules.Choice.REDUCED.name()));
            event.addEventBinding(CustomUIEventBindingType.Activating, "#FullMotionButton",
                    new EventData().put("action", "motion").put("to", MotionRules.Choice.FULL.name()));
            boolean conversion = module.config().keys.allowConversion;
            cmd.set("#DepositButton.Visible", conversion);
            if (conversion) {
                event.addEventBinding(CustomUIEventBindingType.Activating, "#DepositButton",
                        new EventData().put("action", "deposit").put("request", depositRequest));
            }
            event.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton",
                    new EventData().put("action", "close"));
        }

        @Override
        public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
            JsonObject payload = parse(data);
            switch (string(payload, "action")) {
                case "preview" -> module.ui().openPreview(player, field(payload, "id"), null);
                case "history" -> module.ui().openHistory(player, player.getUuid(), player.getUsername());
                case "pending" -> module.ui().openPending(player);
                case "motion" -> module.ui().setMotion(player,
                        "FULL".equals(field(payload, "to")) ? MotionRules.Choice.FULL : MotionRules.Choice.REDUCED);
                case "deposit" -> module.ui().depositHeld(player, field(payload, "request"));
                default -> close(ref, store);
            }
        }
    }

    // ----- Preview ------------------------------------------------------------

    static final class PreviewPage extends MysticPage {

        private final LootBoxModule module;
        private final OpeningService.Preview preview;

        PreviewPage(MysticCore core, PlayerRef player, LootBoxModule module, OpeningService.Preview preview) {
            super(core, player, CustomPageLifetime.CanDismiss);
            this.module = module;
            this.preview = preview;
        }

        @Override
        public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event, Store<EntityStore> store) {
            cmd.append(PREVIEW_UI);
            LootBoxRevision revision = preview.revision();
            OddsTable table = preview.table();
            OddsFormatter formatter = module.opening().formatter();
            cmd.set("#PreviewTitle.TextSpans", uiText("#PreviewTitle.TextSpans", revision.spec.displayName));
            cmd.set("#PreviewDescription.TextSpans", uiText("#PreviewDescription.TextSpans",
                    revision.spec.description == null ? "" : revision.spec.description));
            cmd.set("#Rules.TextSpans", uiText("#Rules.TextSpans", rules(revision)));
            cmd.set("#KeyBalance.TextSpans", uiText("#KeyBalance.TextSpans", "Your keys: " + preview.keys()
                    + " (" + preview.physicalKeys() + " held, " + preview.virtualKeys() + " virtual) · Opens with "
                    + keyNames(module, revision)));
            for (int i = 0; i < table.entries().size(); i++) {
                OddsTable.Entry entry = table.entries().get(i);
                rewardRow(cmd, "#RewardList", i, revision.spec.rewards.get(entry.index()), entry, table.total(),
                        formatter);
            }
            cmd.set("#OddsFooter.TextSpans", uiText("#OddsFooter.TextSpans", oddsFooter(revision, table, formatter)));
            if (!preview.blockers().isEmpty()) {
                cmd.set("#Blocker.Visible", true);
                cmd.set("#Blocker.TextSpans", uiText("#Blocker.TextSpans", preview.blockers().get(0)));
            }
            event.addEventBinding(CustomUIEventBindingType.Activating, "#OpenButton",
                    new EventData().put("action", "open"));
            event.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton",
                    new EventData().put("action", "close"));
        }

        @Override
        public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
            if (!"open".equals(string(parse(data), "action"))) {
                close(ref, store);
                return;
            }
            if (!preview.blockers().isEmpty()) {
                core.getMessageService().send(player, "&c" + preview.blockers().get(0));
                return;
            }
            reopen(ref, store, new ConfirmPage(core, player, module, preview));
        }
    }

    // ----- Confirm ------------------------------------------------------------

    static final class ConfirmPage extends MysticPage {

        private final LootBoxModule module;
        private final OpeningService.Preview preview;
        private volatile boolean submitted;

        ConfirmPage(MysticCore core, PlayerRef player, LootBoxModule module, OpeningService.Preview preview) {
            super(core, player, CustomPageLifetime.CanDismiss);
            this.module = module;
            this.preview = preview;
        }

        @Override
        public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event, Store<EntityStore> store) {
            cmd.append(CONFIRM_UI);
            LootBoxRevision revision = preview.revision();
            OddsTable table = preview.table();
            OddsFormatter formatter = module.opening().formatter();
            cmd.set("#PreviewTitle.TextSpans", uiText("#PreviewTitle.TextSpans", revision.spec.displayName));
            cmd.set("#Consumes.TextSpans", uiText("#Consumes.TextSpans", "Consumes 1 " + keyNames(module, revision)
                    + ". Cancel spends nothing."));
            for (int i = 0; i < table.entries().size(); i++) {
                OddsTable.Entry entry = table.entries().get(i);
                rewardRow(cmd, "#RewardList", i, revision.spec.rewards.get(entry.index()), entry, table.total(),
                        formatter);
            }
            cmd.set("#OddsFooter.TextSpans", uiText("#OddsFooter.TextSpans", oddsFooter(revision, table, formatter)));
            event.addEventBinding(CustomUIEventBindingType.Activating, "#ConfirmButton",
                    new EventData().put("action", "confirm").put("request", preview.requestId()));
            event.addEventBinding(CustomUIEventBindingType.Activating, "#CancelButton",
                    new EventData().put("action", "cancel"));
        }

        @Override
        public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
            JsonObject payload = parse(data);
            if (!"confirm".equals(string(payload, "action"))) {
                close(ref, store);
                return;
            }
            if (submitted) {
                return;
            }
            submitted = true;
            String lootboxId = preview.revision().lootboxId;
            module.opening().open(player, lootboxId, preview.table().hash(), field(payload, "request"))
                    .thenAccept(result -> module.ui().afterOpen(player, lootboxId, preview.crateId(), result));
        }
    }

    // ----- Reveal -------------------------------------------------------------

    static final class RevealPage extends MysticPage {

        private final LootBoxModule module;
        private final OpeningService.OpenResult result;
        /** Reduced motion (P2-L): land on the result without animating. */
        private final boolean reducedMotion;
        private volatile boolean landed;
        private volatile boolean dismissed;
        private volatile String status = "Delivering...";
        private ScheduledFuture<?> animation;
        private int highlighted = -1;
        private int frames;

        RevealPage(MysticCore core, PlayerRef player, LootBoxModule module, OpeningService.OpenResult result,
                boolean reducedMotion) {
            super(core, player, CustomPageLifetime.CanDismiss);
            this.module = module;
            this.result = result;
            this.reducedMotion = reducedMotion;
        }

        @Override
        public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event, Store<EntityStore> store) {
            cmd.append(REVEAL_UI);
            LootBoxRevision revision = result.revision();
            OddsTable table = result.table();
            OddsFormatter formatter = module.opening().formatter();
            cmd.set("#PreviewTitle.TextSpans", uiText("#PreviewTitle.TextSpans", revision.spec.displayName));
            for (int i = 0; i < table.entries().size(); i++) {
                OddsTable.Entry entry = table.entries().get(i);
                String row = "#RevealList[" + i + "]";
                cmd.append("#RevealList", REVEAL_ROW_UI);
                cmd.set(row + " #Name.TextSpans", uiText(row + " #Name.TextSpans",
                        rewardName(revision.spec.rewards.get(entry.index()))));
                cmd.set(row + " #Odds.TextSpans", uiText(row + " #Odds.TextSpans",
                        formatter.percent(entry.weight(), table.total())));
            }
            cmd.set("#ResultName.TextSpans", uiText("#ResultName.TextSpans", "..."));
            cmd.set("#ResultMeta.TextSpans", uiText("#ResultMeta.TextSpans", "Receipt " + result.opening().openingId));
            cmd.set("#Status.TextSpans", uiText("#Status.TextSpans", "Opening..."));
            event.addEventBinding(CustomUIEventBindingType.Activating, "#SkipButton",
                    new EventData().put("action", "skip"));
            event.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton",
                    new EventData().put("action", "close"));
            World world = store.getExternalData().getWorld();
            result.delivery().thenAccept(delivery -> {
                status = delivery.label();
                if (landed && !dismissed) {
                    world.execute(this::pushStatus);
                }
            });
            startAnimation(world);
        }

        private void startAnimation(World world) {
            LootBoxConfig.Opening opening = module.config().opening;
            int fps = MotionRules.framesPerSecond(opening.revealFramesPerSecond);
            int total = MotionRules.revealFrames(opening.revealDurationMs, fps, reducedMotion);
            if (total == 0 || animation != null) {
                world.execute(this::land);
                return;
            }
            long period = Math.max(1L, 1000L / fps);
            animation = core.scheduler().runRepeating(() -> world.execute(() -> frame(total)), period, period,
                    TimeUnit.MILLISECONDS);
        }

        private void frame(int total) {
            if (landed || dismissed) {
                return;
            }
            frames++;
            if (frames >= total) {
                land();
                return;
            }
            highlight(frames % result.table().entries().size(), null);
        }

        private void land() {
            if (landed || dismissed) {
                return;
            }
            landed = true;
            if (animation != null) {
                animation.cancel(false);
            }
            UICommandBuilder cmd = new UICommandBuilder();
            LootBoxDraft.RewardSpec reward = result.revision().spec.rewards.get(result.entry().index());
            cmd.set("#ResultName.TextSpans", uiText("#ResultName.TextSpans", "You won " + rewardName(reward)));
            cmd.set("#ResultMeta.TextSpans", uiText("#ResultMeta.TextSpans", "Chance " + result.opening().probability
                    + " · " + module.opening().formatter().oneIn(result.entry().weight(), result.table().total())
                    + " · Receipt " + result.opening().openingId));
            cmd.set("#Status.TextSpans", uiText("#Status.TextSpans", status));
            highlight(result.table().entries().indexOf(result.entry()), cmd);
        }

        private void pushStatus() {
            UICommandBuilder cmd = new UICommandBuilder();
            cmd.set("#Status.TextSpans", uiText("#Status.TextSpans", status));
            sendUpdate(cmd);
        }

        private void highlight(int next, UICommandBuilder existing) {
            UICommandBuilder cmd = existing == null ? new UICommandBuilder() : existing;
            if (highlighted >= 0 && highlighted != next) {
                cmd.set("#RevealList[" + highlighted + "] #Hl.Visible", false);
            }
            cmd.set("#RevealList[" + next + "] #Hl.Visible", true);
            highlighted = next;
            sendUpdate(cmd);
        }

        @Override
        public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
            if ("skip".equals(string(parse(data), "action"))) {
                if (module.config().opening.allowSkip) {
                    land();
                }
                return;
            }
            dismissed = true;
            if (animation != null) {
                animation.cancel(false);
            }
            close(ref, store);
        }

        @Override
        public void onDismiss(Ref<EntityStore> ref, Store<EntityStore> store) {
            dismissed = true;
            if (animation != null) {
                animation.cancel(false);
            }
        }
    }

    // ----- History ------------------------------------------------------------

    static final class HistoryPage extends MysticPage {

        private final LootBoxModule module;
        private final String ownerName;
        private final List<PlayerLootState.Receipt> receipts;

        HistoryPage(MysticCore core, PlayerRef player, LootBoxModule module, String ownerName,
                List<PlayerLootState.Receipt> receipts) {
            super(core, player, CustomPageLifetime.CanDismiss);
            this.module = module;
            this.ownerName = ownerName;
            this.receipts = receipts;
        }

        @Override
        public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event, Store<EntityStore> store) {
            cmd.append(HISTORY_UI);
            cmd.set("#HeaderInfo.TextSpans", uiText("#HeaderInfo.TextSpans", ownerName + " · " + receipts.size()
                    + " receipt" + (receipts.size() == 1 ? "" : "s") + " · chances are as shown when each opened"));
            cmd.set("#ListEmpty.Visible", receipts.isEmpty());
            for (int i = 0; i < receipts.size(); i++) {
                PlayerLootState.Receipt receipt = receipts.get(i);
                String row = "#ReceiptList[" + i + "]";
                cmd.append("#ReceiptList", HISTORY_ROW_UI);
                cmd.set(row + " #Name.TextSpans", uiText(row + " #Name.TextSpans", receipt.rewardName));
                cmd.set(row + " #Meta.TextSpans", uiText(row + " #Meta.TextSpans", receipt.poolName + " r"
                        + receipt.revision + " · " + WHEN.format(Instant.ofEpochMilli(receipt.at)) + " · "
                        + receipt.keyLabel + " · odds " + receipt.oddsId));
                cmd.set(row + " #Status.TextSpans", uiText(row + " #Status.TextSpans", receipt.probability + " · "
                        + (receipt.status == null ? "" : receipt.status)));
            }
            event.addEventBinding(CustomUIEventBindingType.Activating, "#BackButton",
                    new EventData().put("action", "back"));
            event.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton",
                    new EventData().put("action", "close"));
        }

        @Override
        public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
            if ("back".equals(string(parse(data), "action"))) {
                module.ui().openCatalog(player);
                return;
            }
            close(ref, store);
        }
    }

    // ----- Pending ------------------------------------------------------------

    static final class PendingPage extends MysticPage {

        private final LootBoxModule module;
        private final PendingInbox inbox;

        PendingPage(MysticCore core, PlayerRef player, LootBoxModule module, PendingInbox inbox) {
            super(core, player, CustomPageLifetime.CanDismiss);
            this.module = module;
            this.inbox = inbox;
        }

        @Override
        public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event, Store<EntityStore> store) {
            cmd.append(PENDING_UI);
            cmd.set("#HeaderInfo.TextSpans", uiText("#HeaderInfo.TextSpans", inbox.items.size() + " waiting"));
            cmd.set("#ListEmpty.Visible", inbox.items.isEmpty());
            for (int i = 0; i < inbox.items.size(); i++) {
                PendingInbox.PendingReward item = inbox.items.get(i);
                String row = "#PendingList[" + i + "]";
                cmd.append("#PendingList", PENDING_ROW_UI);
                cmd.set(row + " #Icon.ItemId", item.itemId);
                cmd.set(row + " #Name.TextSpans", uiText(row + " #Name.TextSpans",
                        item.label == null ? item.itemId : item.label));
                cmd.set(row + " #Meta.TextSpans", uiText(row + " #Meta.TextSpans", "x" + item.quantity + " · since "
                        + WHEN.format(Instant.ofEpochMilli(item.createdAt))));
                cmd.set(row + " #Status.TextSpans", uiText(row + " #Status.TextSpans", item.expiresAt <= 0 ? ""
                        : "expires " + WHEN.format(Instant.ofEpochMilli(item.expiresAt))));
                event.addEventBinding(CustomUIEventBindingType.Activating, row + " #ClaimButton",
                        new EventData().put("action", "claim").put("id", item.fulfillmentId));
            }
            event.addEventBinding(CustomUIEventBindingType.Activating, "#ClaimAllButton",
                    new EventData().put("action", "claimAll"));
            event.addEventBinding(CustomUIEventBindingType.Activating, "#BackButton",
                    new EventData().put("action", "back"));
        }

        @Override
        public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
            JsonObject payload = parse(data);
            switch (string(payload, "action")) {
                case "claim" -> module.ui().claim(player, field(payload, "id"));
                case "claimAll" -> module.ui().claim(player, null);
                case "back" -> module.ui().openCatalog(player);
                default -> close(ref, store);
            }
        }
    }

    // ----- Manager (staff) ----------------------------------------------------
    /** Status shown after a delivery chain settles, for messages outside the reveal. */
    static String statusLabel(RewardService.Status status) {
        return status == null ? "" : status.label();
    }
}
