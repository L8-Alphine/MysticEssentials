package org.hyzionstudios.mysticessentials.modules.flight;

/**
 * Persisted settings for {@code modules/flight/config.json}. Flight is always
 * OFF until a player toggles it with {@code /fly}. When {@code paidFlight} is
 * enabled and a VaultUnlocked economy is present, flying players are charged
 * {@code costPerMinute} every minute (players with
 * {@code mysticessentials.fly.free} or {@code .unlimited} are exempt); when the
 * charge fails, flight is switched off.
 */
public final class FlightConfig {

    public int configVersion = 1;

    /** Charge for flight time via the economy (requires VaultUnlocked). */
    public boolean paidFlight = false;
    public double costPerMinute = 10.0;

    /** Optional fly speed multipliers applied while flight is enabled (1.0 = server default). */
    public float horizontalSpeedMultiplier = 1.0f;
    public float verticalSpeedMultiplier = 1.0f;
}
