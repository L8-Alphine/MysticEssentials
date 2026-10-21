package org.hyzionstudios.mysticessentials.spikes;

import java.nio.file.Files;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Level;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

/**
 * DEV-ONLY plugin for the LootBox Phase 0 spikes (docs/lootbox/implementation-plan.md
 * §10): S2 generated block variants, S3 UI widgets, S6 block event coverage and
 * S7 reveal animation cadence. It probes engine behaviour on a development server
 * and must never be installed on a live one.
 */
public class LootBoxSpikesPlugin extends JavaPlugin {

    private SpikeLog log;
    private ScheduledExecutorService scheduler;
    private S2VariantSpike variants;
    private S6EventProbe probe;

    public LootBoxSpikesPlugin(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        // Registered before assets parse, like MysticPortal, so generated block
        // variants and the generated pack can reference the type at boot.
        getCodecRegistry(Interaction.CODEC)
                .register(SpikeUseInteraction.TYPE_ID, SpikeUseInteraction.class, SpikeUseInteraction.CODEC);
    }

    @Override
    protected void start() {
        log = new SpikeLog(message -> getLogger().at(Level.INFO).log(message),
                getDataDirectory().resolve("spike-results.log"));
        log.line("BOOT", "DEV-ONLY LootBox spike plugin started; data folder "
                + getDataDirectory().toAbsolutePath());
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "LootBoxSpikes");
            thread.setDaemon(true);
            return thread;
        });
        SpikeUseInteraction.log = log;
        variants = new S2VariantSpike(log, getDataDirectory());
        probe = new S6EventProbe(log, variants);
        // Systems are keyed by class and live for the plugin's lifetime.
        probe.systems().forEach(system -> getEntityStoreRegistry().registerSystem(system));
        getCommandRegistry().registerCommand(new SpikeCommand(this));
        variants.reportGeneratedPackOnBoot();
        if (Boolean.getBoolean("lbspike.autotest") || Files.exists(getDataDirectory().resolve("autotest"))) {
            variants.autotest();
        }
    }

    @Override
    protected void shutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    SpikeLog log() {
        return log;
    }

    ScheduledExecutorService scheduler() {
        return scheduler;
    }

    S2VariantSpike variants() {
        return variants;
    }

    S6EventProbe probe() {
        return probe;
    }
}
