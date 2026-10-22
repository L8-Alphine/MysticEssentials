package org.hyzionstudios.mysticessentials.modules.lootbox.integration;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.hyzionstudios.mysticessentials.modules.lootbox.api.event.OpeningFlaggedEvent;

/**
 * Hands {@link OpeningFlaggedEvent}s (forged or replayed keys, odds anomalies,
 * unusual luck) to MysticModeration (P3-WP7), bound by reflection to
 * {@code org.hyzionstudios.mysticmoderation.api.MysticModerationProvider}.
 *
 * <p>Each flag becomes a {@code HIGH} priority {@code CHEAT} report against the
 * player, filed by a fixed "LootBox" reporter so staff can filter on it. When
 * the reports module refuses it (disabled, or MysticModeration's per-reporter
 * cooldown), it becomes an {@code INVESTIGATION} note instead. Flags without a
 * player (a crate whose block changed) are only audited here. One flag per
 * player and pool is forwarded per cooldown.</p>
 *
 * <p>Runs on the caller's executor, never the thread that published the event.</p>
 */
final class ModerationForwarder {

    /** The reporter every LootBox report is filed under. */
    static final UUID REPORTER = UUID.nameUUIDFromBytes("mysticessentials:lootbox".getBytes(StandardCharsets.UTF_8));
    static final String REPORTER_NAME = "LootBox";

    private record Hooks(Method providerGet, Method serviceRegistry, Method find, Class<?> reportService,
            Method submit, Constructor<?> request, Object high, Object normal, Method accepted, Method refusal,
            Class<?> noteService, Method addNote, Constructor<?> staffActor, Object investigation, Object highRisk) {
    }

    private final Hooks hooks;
    private final Function<UUID, String> names;
    private final Supplier<String> server;
    private final BiConsumer<String, Throwable> log;
    private final IntegrationRules.FlagThrottle throttle = new IntegrationRules.FlagThrottle();

    private ModerationForwarder(Hooks hooks, Function<UUID, String> names, Supplier<String> server,
            BiConsumer<String, Throwable> log) {
        this.hooks = hooks;
        this.names = names;
        this.server = server;
        this.log = log;
    }

    static ModerationForwarder detect(Function<UUID, String> names, Supplier<String> server,
            BiConsumer<String, Throwable> log) {
        try {
            String base = "org.hyzionstudios.mysticmoderation.api.";
            Class<?> provider = Reflect.require(base + "MysticModerationProvider");
            Class<?> api = Reflect.require(base + "MysticModerationAPI");
            Class<?> reports = Reflect.require(base + "service.ReportService");
            Class<?> notes = Reflect.require(base + "service.NoteService");
            Class<?> request = Reflect.require(base + "model.ReportRequest");
            Class<?> priority = Reflect.require(base + "model.ReportPriority");
            Class<?> visibility = Reflect.require(base + "model.ReporterVisibility");
            Class<?> submission = Reflect.require(base + "model.ReportSubmission");
            Class<?> actor = Reflect.require(base + "model.StaffActor");
            Class<?> noteType = Reflect.require(base + "model.NoteType");
            Class<?> risk = Reflect.require(base + "model.RiskLevel");
            Method registry = Reflect.method(api, "serviceRegistry");
            Hooks hooks = new Hooks(Reflect.method(provider, "get"), registry,
                    Reflect.method(registry.getReturnType(), "find", Class.class), reports,
                    Reflect.method(reports, "submit", request),
                    request.getConstructor(UUID.class, String.class, UUID.class, String.class, String.class,
                            String.class, priority, String.class, String.class, visibility),
                    Reflect.enumValue(priority, "HIGH"), Reflect.enumValue(visibility, "NORMAL"),
                    Reflect.method(submission, "accepted"), Reflect.method(submission, "refusalReason"), notes,
                    Reflect.method(notes, "add", UUID.class, String.class, actor, noteType, risk, String.class),
                    actor.getConstructor(String.class, String.class, boolean.class),
                    Reflect.enumValue(noteType, "INVESTIGATION"), Reflect.enumValue(risk, "HIGH"));
            return new ModerationForwarder(hooks, names, server, log);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Forwards one flag. Returns what happened, for the audit log:
     * {@code REPORTED}, {@code NOTED}, {@code THROTTLED}, {@code NO_PLAYER},
     * {@code UNAVAILABLE} or {@code FAILED}.
     */
    String forward(OpeningFlaggedEvent event, long now, long cooldownMs) {
        if (event.player() == null) {
            return "NO_PLAYER";
        }
        if (!throttle.admit(event.player(), event.lootboxId(), now, cooldownMs)) {
            return "THROTTLED";
        }
        try {
            Object api = Reflect.call(hooks.providerGet(), null);
            if (!(api instanceof Optional<?> optional) || optional.isEmpty()) {
                return "UNAVAILABLE";
            }
            Object registry = Reflect.call(hooks.serviceRegistry(), optional.get());
            String target = names.apply(event.player());
            String targetName = target == null ? event.player().toString() : target;
            String text = IntegrationRules.flagReason(event.lootboxId(), event.reason(), event.detail());
            Object reports = Reflect.call(hooks.find(), registry, hooks.reportService());
            String refused = "the reports module is off";
            if (reports instanceof Optional<?> service && service.isPresent()) {
                Object request = hooks.request().newInstance(REPORTER, REPORTER_NAME, event.player(), targetName,
                        "LOOTBOX", text, hooks.high(), server.get(), "CHEAT", hooks.normal());
                Object submission = Reflect.call(hooks.submit(), service.get(), request);
                if (Boolean.TRUE.equals(Reflect.call(hooks.accepted(), submission))) {
                    return "REPORTED";
                }
                refused = String.valueOf(Reflect.call(hooks.refusal(), submission));
            }
            Object notes = Reflect.call(hooks.find(), registry, hooks.noteService());
            if (notes instanceof Optional<?> service && service.isPresent()) {
                Object author = hooks.staffActor().newInstance("mysticessentials:lootbox", REPORTER_NAME, true);
                Reflect.call(hooks.addNote(), service.get(), event.player(), targetName, author,
                        hooks.investigation(), hooks.highRisk(), text);
                return "NOTED";
            }
            log.accept("MysticModeration took neither a report (" + refused + ") nor a note for " + text, null);
            return "UNAVAILABLE";
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.accept("forwarding a LootBox flag to MysticModeration failed", e);
            return "FAILED";
        }
    }
}
