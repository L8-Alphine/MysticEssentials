package org.hyzionstudios.mysticessentials.modules.lootbox.integration;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Gathers {@link PaidPolicy.Signals} from MysticIdentity
 * ({@code org.hyzionstudios.mysticidentity.MysticIdentityProvider}) by
 * reflection. Unlike the chat module's {@code ManagedAccountsBridge}, which fails
 * open, this one fails closed: absent, disabled or throwing all read as "not
 * known", and {@link PaidPolicy} refuses.
 *
 * <p>Answers from MysticIdentity's caches ({@code players().lookup},
 * {@code managed().check}); safe on any thread.</p>
 */
final class IdentityPaidGate implements Function<UUID, String> {

    private record Hooks(Method providerGet, Method players, Method lookup, Method managedAccount, Method managed,
            Method isManaged, Method check, Method isAllowed, Object realMoneyPurchase) {
    }

    private final Hooks hooks;
    private final BooleanSupplier requireAgeSignal;

    private IdentityPaidGate(Hooks hooks, BooleanSupplier requireAgeSignal) {
        this.hooks = hooks;
        this.requireAgeSignal = requireAgeSignal;
    }

    /** Always returns a gate; without MysticIdentity's classes it refuses everyone. */
    static IdentityPaidGate create(BooleanSupplier requireAgeSignal) {
        Hooks hooks;
        try {
            Class<?> provider = Reflect.require("org.hyzionstudios.mysticidentity.MysticIdentityProvider");
            Class<?> api = Reflect.require("org.hyzionstudios.mysticidentity.api.service.MysticIdentityApi");
            Class<?> identity = Reflect.require("org.hyzionstudios.mysticidentity.api.service.PlayerIdentity");
            Class<?> capability = Reflect.require("org.hyzionstudios.mysticidentity.api.managed.ManagedCapability");
            Method players = Reflect.method(api, "players");
            Method managed = Reflect.method(api, "managed");
            Method check = Reflect.method(managed.getReturnType(), "check", UUID.class, capability);
            hooks = new Hooks(Reflect.method(provider, "get"), players,
                    Reflect.method(players.getReturnType(), "lookup", UUID.class),
                    Reflect.method(identity, "managedAccount"), managed,
                    Reflect.method(managed.getReturnType(), "isManaged", UUID.class), check,
                    Reflect.method(check.getReturnType(), "isAllowed"),
                    Reflect.enumValue(capability, "REAL_MONEY_PURCHASE"));
        } catch (Throwable t) {
            hooks = null;
        }
        return new IdentityPaidGate(hooks, requireAgeSignal);
    }

    boolean present() {
        return hooks != null;
    }

    @Override
    public String apply(UUID player) {
        return PaidPolicy.refusal(signals(player), requireAgeSignal.getAsBoolean());
    }

    PaidPolicy.Signals signals(UUID player) {
        Hooks h = hooks;
        if (h == null || player == null) {
            return PaidPolicy.Signals.absent();
        }
        try {
            Object maybeApi = Reflect.call(h.providerGet(), null);
            if (!(maybeApi instanceof Optional<?> optional) || optional.isEmpty()) {
                return PaidPolicy.Signals.absent();
            }
            Object api = optional.get();
            Optional<Boolean> claim = Optional.empty();
            Object found = Reflect.call(h.lookup(), Reflect.call(h.players(), api), player);
            if (found instanceof Optional<?> identity && identity.isPresent()) {
                Object managedAccount = Reflect.call(h.managedAccount(), identity.get());
                if (managedAccount instanceof Optional<?> value && value.orElse(null) instanceof Boolean known) {
                    claim = Optional.of(known);
                }
            }
            Object service = Reflect.call(h.managed(), api);
            boolean managed = !Boolean.FALSE.equals(Reflect.call(h.isManaged(), service, player));
            Object decision = Reflect.call(h.check(), service, player, h.realMoneyPurchase());
            boolean allowed = Boolean.TRUE.equals(Reflect.call(h.isAllowed(), decision));
            // MysticIdentity has no age signal yet; see PaidPolicy.
            return new PaidPolicy.Signals(true, claim, managed, allowed, Optional.empty());
        } catch (RuntimeException | LinkageError e) {
            return PaidPolicy.Signals.absent();
        }
    }
}
