package org.hyzionstudios.mysticessentials.integration.mysticidentity;

import java.util.Optional;
import org.hyzionstudios.mysticessentials.core.MysticCore;
import org.hyzionstudios.mysticidentity.MysticIdentityProvider;
import org.hyzionstudios.mysticidentity.api.portal.PortalService;
import org.hyzionstudios.mysticidentity.api.service.MysticIdentityApi;

/** One live registration with MysticIdentity's portal, and whether it is still the live one. */
final class PortalRegistration implements AutoCloseable {

    private final MysticIdentityApi api;
    private final PortalService portal;
    private final PortalService.Registration registration;

    private PortalRegistration(MysticIdentityApi api, PortalService portal, PortalService.Registration registration) {
        this.api = api;
        this.portal = portal;
        this.registration = registration;
    }

    /** Registers the provider; {@code null} while MysticIdentity has not published its API. */
    static PortalRegistration attach(MysticCore core) {
        Optional<MysticIdentityApi> current = MysticIdentityProvider.get();
        if (current.isEmpty()) {
            return null;
        }
        Optional<PortalService> portal = current.get().portal();
        if (portal.isEmpty()) {
            return null;
        }
        PortalService.Registration registration = portal.get().register(new EssentialsPortalProvider(core));
        return new PortalRegistration(current.get(), portal.get(), registration);
    }

    boolean stillRegistered() {
        return MysticIdentityProvider.get().filter(live -> live == api).isPresent()
                && portal.isRegistered(EssentialsPortalProvider.MODULE_ID);
    }

    @Override
    public void close() {
        try {
            registration.close();
        } catch (RuntimeException | LinkageError gone) {
            // MysticIdentity went first and took its registry with it.
        }
    }
}
