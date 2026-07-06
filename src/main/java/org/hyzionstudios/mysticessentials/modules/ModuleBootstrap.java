package org.hyzionstudios.mysticessentials.modules;

import org.hyzionstudios.mysticessentials.core.module.ModuleManagerImpl;
import org.hyzionstudios.mysticessentials.modules.afk.AfkModule;
import org.hyzionstudios.mysticessentials.modules.announcements.AnnouncementModule;
import org.hyzionstudios.mysticessentials.modules.chat.ChatModule;
import org.hyzionstudios.mysticessentials.modules.flight.FlightModule;
import org.hyzionstudios.mysticessentials.modules.greetings.GreetingsModule;
import org.hyzionstudios.mysticessentials.modules.inventory.InventoryModule;
import org.hyzionstudios.mysticessentials.modules.kits.KitModule;
import org.hyzionstudios.mysticessentials.modules.mail.MailModule;
import org.hyzionstudios.mysticessentials.modules.nick.NickModule;
import org.hyzionstudios.mysticessentials.modules.spawn.SpawnModule;
import org.hyzionstudios.mysticessentials.modules.teleportation.TeleportationModule;
import org.hyzionstudios.mysticessentials.modules.warps.WarpModule;

/**
 * Registers the built-in V1 modules with the {@link ModuleManagerImpl}. Actual
 * enable order is resolved from declared dependencies by the manager; this only
 * lists what exists. Addons register their own modules via the public
 * {@code ModuleManager} before enable.
 */
public final class ModuleBootstrap {

    private ModuleBootstrap() {
    }

    public static void registerBuiltins(ModuleManagerImpl manager) {
        manager.register(new TeleportationModule());
        manager.register(new SpawnModule());
        manager.register(new WarpModule());
        manager.register(new MailModule());
        manager.register(new AnnouncementModule());
        manager.register(new AfkModule());
        manager.register(new ChatModule());
        manager.register(new GreetingsModule());
        manager.register(new KitModule());
        manager.register(new FlightModule());
        manager.register(new InventoryModule());
        manager.register(new NickModule());
    }
}
