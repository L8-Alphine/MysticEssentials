package org.hyzionstudios.mysticessentials.modules.chat.itemlink;

import static org.hyzionstudios.mysticessentials.platform.ui.MysticPage.uiText;

import java.util.List;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.core.item.ItemViewConfig;
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
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * The "Recently Shared Items" browser: a per-recipient history of item links
 * seen in chat, each with an Inspect button that opens the read-only
 * {@link ItemDetailsPage}. Rows are appended from the {@code RecentItemLinkRow.ui}
 * template (builtin list pattern) and carry a real {@code ItemSlot} icon.
 */
public final class RecentItemLinksPage extends MysticPage {

    static final String UI = "MysticEssentials/RecentItemLinks.ui";
    static final String ROW_UI = "MysticEssentials/RecentItemLinkRow.ui";

    private final ItemSnapshotService snapshots;
    private final ItemViewConfig config;

    public RecentItemLinksPage(MysticCore core, PlayerRef player, ItemSnapshotService snapshots,
            ItemViewConfig config) {
        super(core, player, CustomPageLifetime.CanDismiss);
        this.snapshots = snapshots;
        this.config = config;
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event,
            Store<EntityStore> store) {
        cmd.append(UI);

        List<ItemSnapshot> recent = snapshots.recent(player.getUuid());
        cmd.set("#RecentEmpty.Visible", recent.isEmpty());
        for (int i = 0; i < recent.size(); i++) {
            ItemSnapshot snapshot = recent.get(i);
            String sel = "#RecentList[" + i + "]";
            cmd.append("#RecentList", ROW_UI);
            cmd.set(sel + " #Icon.ItemId", snapshot.itemId());
            // Translated names go through as a Message so the client resolves
            // them; everything else is sent pre-resolved, because a raw Message
            // set on a text span is a known 0.5.6 client disconnect.
            cmd.set(sel + " #Name.TextSpans", snapshot.view.displayName().isTranslated()
                    ? uiText(sel + " #Name.TextSpans", snapshot.nameMessage())
                    : uiText(sel + " #Name.TextSpans", snapshot.plainName()));
            cmd.set(sel + " #Meta.TextSpans", uiText(sel + " #Meta.TextSpans", meta(snapshot)));
            event.addEventBinding(CustomUIEventBindingType.Activating, sel + " #InspectButton",
                    new EventData().put("action", "inspect").put("id", snapshot.id));
        }
        event.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton",
                new EventData().put("action", "close"));
    }

    private static String meta(ItemSnapshot snapshot) {
        StringBuilder sb = new StringBuilder(
                snapshot.senderName.isBlank() ? "unknown" : snapshot.senderName);
        if (!snapshot.channelName.isBlank()) {
            sb.append(" • ").append(snapshot.channelName);
        }
        sb.append(" • ").append(ItemDetailsPage.relativeTime(snapshot.capturedAtEpochMs()));
        return sb.toString();
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
        JsonObject payload = parse(data);
        String action = string(payload, "action");
        if ("inspect".equals(action)) {
            String id = field(payload, "id");
            snapshots.get(id).ifPresentOrElse(
                    snapshot -> reopen(ref, store, new ItemDetailsPage(core, player, snapshot, config)),
                    () -> core.getMessageService().send(player, "&cThat shared item has expired."));
            return;
        }
        close(ref, store);
    }
}
