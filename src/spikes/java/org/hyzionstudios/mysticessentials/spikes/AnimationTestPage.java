package org.hyzionstudios.mysticessentials.spikes;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * S7: how fast can the server drive a reveal with {@code sendUpdate}? A
 * highlight steps through the reward rows at a fixed rate and lands on a
 * predetermined row, while the odds column stays put. The server logs its own
 * send cadence; smoothness on screen is judged by eye.
 */
final class AnimationTestPage extends CustomUIPage {

    private static final String[][] REWARDS = {
        {"Iron Sword", "45.00%"},
        {"250 Gold", "30.00%"},
        {"Dungeon Token", "15.00%"},
        {"Royal Cosmetic", "9.00%"},
        {"Guardian Companion", "0.9875%"},
        {"Ancient Relic", "0.0125%"},
    };
    private static final int LANDING_ROW = 4;

    private final LootBoxSpikesPlugin plugin;
    private final int hz;
    private final int seconds;
    private volatile boolean open = true;
    private ScheduledFuture<?> task;
    private int highlighted = -1;
    private int frames;
    private long startedNanos;
    private long lastNanos;
    private long worstGapNanos;

    AnimationTestPage(PlayerRef player, LootBoxSpikesPlugin plugin, int hz, int seconds) {
        super(player, CustomPageLifetime.CanDismiss);
        this.plugin = plugin;
        this.hz = hz;
        this.seconds = seconds;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull Store<EntityStore> store) {
        cmd.append("LootBoxSpike/SpikeAnim.ui");
        for (int i = 0; i < REWARDS.length; i++) {
            cmd.append("#Rows", "LootBoxSpike/SpikeAnimRow.ui");
            cmd.set("#Rows[" + i + "] #Name.Text", REWARDS[i][0]);
            cmd.set("#Rows[" + i + "] #Odds.Text", REWARDS[i][1]);
        }
        cmd.set("#Status.Text", hz + " updates/s for " + seconds + " s. The odds column must stay readable.");
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton",
                new EventData().put("action", "close"));
        if (task == null) {
            World world = store.getExternalData().getWorld();
            long periodMs = Math.max(1L, 1000L / hz);
            startedNanos = System.nanoTime();
            lastNanos = startedNanos;
            task = plugin.scheduler().scheduleAtFixedRate(() -> world.execute(this::frame),
                    periodMs, periodMs, TimeUnit.MILLISECONDS);
            plugin.log().line("S7", "animation started for " + playerRef.getUsername() + " at " + hz
                    + " Hz (" + periodMs + " ms period) for " + seconds + " s");
        }
    }

    private void frame() {
        if (!open) {
            return;
        }
        long now = System.nanoTime();
        worstGapNanos = Math.max(worstGapNanos, now - lastNanos);
        lastNanos = now;
        frames++;
        int total = Math.max(1, hz * seconds);
        boolean last = frames >= total;
        int next = last ? LANDING_ROW : frames % REWARDS.length;

        UICommandBuilder cmd = new UICommandBuilder();
        if (highlighted >= 0 && highlighted != next) {
            cmd.set("#Rows[" + highlighted + "] #Hl.Visible", false);
        }
        cmd.set("#Rows[" + next + "] #Hl.Visible", true);
        highlighted = next;
        if (last) {
            long elapsedMs = (now - startedNanos) / 1_000_000L;
            String summary = "Landed on " + REWARDS[LANDING_ROW][0] + ": " + frames + " frames in " + elapsedMs
                    + " ms (target " + hz + " Hz, worst gap " + worstGapNanos / 1_000_000L + " ms)";
            cmd.set("#Status.Text", summary);
            plugin.log().line("S7", summary + " for " + playerRef.getUsername());
            stop();
        }
        sendUpdate(cmd);
    }

    private void stop() {
        open = false;
        if (task != null) {
            task.cancel(false);
        }
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, String rawData) {
        if (rawData != null && rawData.contains("\"close\"")) {
            stop();
            close();
        }
    }

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        if (open) {
            plugin.log().line("S7", "page dismissed after " + frames + " frames");
        }
        stop();
    }
}
