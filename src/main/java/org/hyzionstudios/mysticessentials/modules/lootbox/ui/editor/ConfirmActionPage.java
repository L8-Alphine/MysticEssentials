package org.hyzionstudios.mysticessentials.modules.lootbox.ui.editor;

import java.util.List;

import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticessentials.platform.ui.MysticPage;

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
 * Asks before an editor action that is hard to take back: publish, archive,
 * delete a reward. Shows a title, one line per detail (MysticText markup, so a
 * diff can colour old and new values), and a confirm button that turns red when
 * the action is destructive.
 *
 * <p>Confirm checks {@link Spec#permission} again before running the callback,
 * and runs it at most once. The callbacks run on the player's world thread, so
 * they may reopen a page; they must check their own preconditions again too
 * (the session is still open, the draft has not changed), because the page
 * may have sat open for a while.</p>
 */
public final class ConfirmActionPage extends MysticPage {

    static final String CONFIRM_UI = "MysticEssentials/LootBoxConfirmAction.ui";
    static final String LINE_UI = "MysticEssentials/LootBoxConfirmActionLine.ui";

    /**
     * What to confirm.
     *
     * @param title        window title, e.g. "Publish Avalon Supply?"
     * @param lines        one detail per line; MysticText markup allowed
     * @param confirmLabel the confirm button's label, e.g. "Publish"
     * @param destructive  red styling for an action that removes something
     * @param permission   node re-checked when Confirm is pressed
     */
    public record Spec(String title, List<String> lines, String confirmLabel, boolean destructive,
            String permission) {
    }

    /** Runs on the player's world thread inside {@code handleDataEvent}. */
    @FunctionalInterface
    public interface Callback {
        void run(Ref<EntityStore> ref, Store<EntityStore> store);
    }

    private final Spec spec;
    private final Callback onConfirm;
    private final Callback onCancel;
    private boolean submitted;

    public ConfirmActionPage(MysticCore core, PlayerRef player, Spec spec, Callback onConfirm, Callback onCancel) {
        super(core, player, CustomPageLifetime.CanDismiss);
        this.spec = spec;
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder event, Store<EntityStore> store) {
        cmd.append(CONFIRM_UI);
        cmd.set("#HeaderTitle.TextSpans", uiText("#HeaderTitle.TextSpans", spec.title()));
        cmd.set("#DangerBar.Visible", spec.destructive());
        cmd.set("#DangerLabel.Visible", spec.destructive());
        List<String> lines = spec.lines() == null ? List.of() : spec.lines();
        for (int i = 0; i < lines.size(); i++) {
            String row = "#BodyList[" + i + "]";
            cmd.append("#BodyList", LINE_UI);
            cmd.set(row + " #Line.TextSpans", uiText(row + " #Line.TextSpans", lines.get(i)));
        }
        String button = spec.destructive() ? "#DangerButton" : "#ConfirmButton";
        cmd.set("#ConfirmButton.Visible", !spec.destructive());
        cmd.set("#DangerButton.Visible", spec.destructive());
        cmd.set(button + ".TextSpans", uiText(button + ".TextSpans",
                spec.confirmLabel() == null || spec.confirmLabel().isBlank() ? "Confirm" : spec.confirmLabel()));
        event.addEventBinding(CustomUIEventBindingType.Activating, button, new EventData().put("action", "confirm"));
        event.addEventBinding(CustomUIEventBindingType.Activating, "#CancelButton",
                new EventData().put("action", "cancel"));
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String data) {
        if (!"confirm".equals(string(parse(data), "action"))) {
            onCancel.run(ref, store);
            return;
        }
        if (submitted) {
            return;
        }
        if (spec.permission() != null && !player.hasPermission(spec.permission())) {
            core.getMessageService().sendKey(player, "no-permission");
            close(ref, store);
            return;
        }
        submitted = true;
        onConfirm.run(ref, store);
    }
}
