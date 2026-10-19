package org.hyzionstudios.mysticessentials.modules.lootbox.ui.editor;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;

/**
 * The inputs of an editor form, and typed parsing of what the player typed.
 * Generalises {@code VaultEditorUi.appendFieldDrafts}.
 *
 * <p><b>Attach.</b> Every event a page binds carries the current text of every
 * input: {@code "@name" -> "#NameInput.Value"}, which the client fills in when
 * the event fires. So the server sees what the player typed even when the press
 * was a tab switch or a search, remembers it in the {@link EditorSession}, and
 * the rebuilt page shows it again. A rebuild never loses unsaved input.</p>
 *
 * <p><b>Parse.</b> The typed readers turn submitted text into a value or a
 * {@link Problem} naming the field, so a page can show every mistake at once
 * next to its field instead of stopping at the first.</p>
 */
public final class FormFields {

    /** Where an input keeps its value. */
    public enum Property {
        /** {@code TextField} and {@code DropdownBox}. */
        VALUE(".Value"),
        /** {@code ColorPickerDropdownBox}. */
        COLOR(".Color");

        private final String suffix;

        Property(String suffix) {
            this.suffix = suffix;
        }
    }

    /**
     * One input: the key its value travels under, the element it reads, and the
     * label used in problem messages.
     */
    public record Field(String key, String elementId, Property property, String label) {

        /** A {@code TextField} or {@code DropdownBox}, read through {@code .Value}. */
        public static Field text(String key, String elementId, String label) {
            return new Field(key, elementId, Property.VALUE, label);
        }

        /** A {@code ColorPickerDropdownBox}, read through {@code .Color}. */
        public static Field color(String key, String elementId, String label) {
            return new Field(key, elementId, Property.COLOR, label);
        }

        /** The client-side value reference, e.g. {@code #NameInput.Value}. */
        public String selector() {
            return "#" + elementId + property.suffix;
        }

        /** The key in the event payload, e.g. {@code @name}. */
        public String eventKey() {
            return "@" + key;
        }
    }

    private final List<Field> fields;

    private FormFields(List<Field> fields) {
        this.fields = List.copyOf(fields);
    }

    public static FormFields of(Field... fields) {
        return new FormFields(List.of(fields));
    }

    public List<Field> fields() {
        return fields;
    }

    // ----- Attach and read ----------------------------------------------------

    /** Adds every field's current value to {@code data}. @return {@code data} */
    public EventData attach(EventData data) {
        for (Field field : fields) {
            data.append(field.eventKey(), field.selector());
        }
        return data;
    }

    /** A new event for {@code action} that carries every field. */
    public EventData event(String action) {
        return attach(new EventData().put("action", action));
    }

    /**
     * The submitted text of every field the event carried, by field key. A
     * field the event did not carry is left out; one the player emptied is
     * {@code ""}.
     */
    public Map<String, String> read(JsonObject payload) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Field field : fields) {
            String value = submitted(payload, field.key());
            if (value != null) {
                values.put(field.key(), value);
            }
        }
        return values;
    }

    /** Remembers every submitted value in {@code session}, so the next build shows it. */
    public void remember(JsonObject payload, EditorSession<?> session) {
        session.rememberInputs(read(payload));
    }

    /**
     * Writes {@code field}'s value into the page: the player's unsaved text when
     * the session holds some, else {@code saved} (the working copy's value).
     */
    public static void fill(UICommandBuilder cmd, Field field, EditorSession<?> session, String saved) {
        String unsaved = session == null ? null : session.input(field.key());
        String value = unsaved != null ? unsaved : saved;
        cmd.set(field.selector(), value == null ? "" : value);
    }

    /** The submitted text for {@code key} ({@code @key} or plain {@code key}), or {@code null} when absent. */
    public static String submitted(JsonObject payload, String key) {
        for (String name : new String[] {"@" + key, key}) {
            if (payload != null && payload.has(name) && payload.get(name).isJsonPrimitive()) {
                return payload.get(name).getAsString();
            }
        }
        return null;
    }

    // ----- Typed parsing ------------------------------------------------------

    /** What was wrong with a value. */
    public enum Kind {
        BLANK,
        NOT_A_NUMBER,
        OUT_OF_RANGE,
        TOO_MANY_PLACES,
        BAD_FORMAT,
        TOO_LONG
    }

    /** One field's problem, with a message the page can show next to it. */
    public record Problem(String field, Kind kind, String message) {
    }

    /** A parsed value, or the problem that stopped it. */
    public record Result<T>(T value, Problem problem) {

        static <T> Result<T> ok(T value) {
            return new Result<>(value, null);
        }

        static <T> Result<T> error(Field field, Kind kind, String message) {
            return new Result<>(null, new Problem(field.key(), kind, field.label() + " " + message));
        }

        public boolean ok() {
            return problem == null;
        }

        /** Adds the problem (if any) to {@code problems}. @return the value, or {@code null} on a problem */
        public T collect(List<Problem> problems) {
            if (problem != null) {
                problems.add(problem);
            }
            return value;
        }
    }

    private static final Pattern INTEGER = Pattern.compile("[+-]?\\d+");
    private static final Pattern DECIMAL = Pattern.compile("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)");

    /** A whole number from {@code min} to {@code max}, e.g. a weight of 1–1000. */
    public static Result<Integer> intInRange(Field field, String raw, int min, int max) {
        Result<Long> wide = longInRange(field, raw, min, max);
        return wide.ok() ? Result.ok(wide.value().intValue()) : new Result<>(null, wide.problem());
    }

    /** A whole number from {@code min} to {@code max}, e.g. a cooldown in seconds. */
    public static Result<Long> longInRange(Field field, String raw, long min, long max) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            return Result.error(field, Kind.BLANK, "is required.");
        }
        if (!INTEGER.matcher(text).matches()) {
            return Result.error(field, Kind.NOT_A_NUMBER, "must be a whole number.");
        }
        BigInteger value = new BigInteger(text);
        if (value.compareTo(BigInteger.valueOf(min)) < 0 || value.compareTo(BigInteger.valueOf(max)) > 0) {
            return Result.error(field, Kind.OUT_OF_RANGE, "must be from " + min + " to " + max + ".");
        }
        return Result.ok(value.longValueExact());
    }

    /**
     * A decimal with at most {@code maxPlaces} places (after dropping trailing
     * zeros, as the odds engine counts them), within {@code min}..{@code max}
     * inclusive when they are not {@code null}. Exponent forms are refused.
     *
     * @return the value without trailing zeros; use {@code toPlainString()} to store it
     */
    public static Result<BigDecimal> decimal(Field field, String raw, int maxPlaces, BigDecimal min,
            BigDecimal max) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            return Result.error(field, Kind.BLANK, "is required.");
        }
        if (!DECIMAL.matcher(text).matches()) {
            return Result.error(field, Kind.NOT_A_NUMBER, "must be a number such as 12.5.");
        }
        BigDecimal value = new BigDecimal(text).stripTrailingZeros();
        if (value.scale() > maxPlaces) {
            return Result.error(field, Kind.TOO_MANY_PLACES, "allows at most " + maxPlaces + " decimal places.");
        }
        if ((min != null && value.compareTo(min) < 0) || (max != null && value.compareTo(max) > 0)) {
            return Result.error(field, Kind.OUT_OF_RANGE, "must be "
                    + (min != null && max != null ? "from " + min.toPlainString() + " to " + max.toPlainString()
                            : min != null ? "at least " + min.toPlainString() : "at most " + max.toPlainString())
                    + ".");
        }
        return Result.ok(value.scale() < 0 ? value.setScale(0) : value);
    }

    /**
     * An id that must match {@code pattern} in full.
     *
     * @param hint what a valid id looks like, finishing "must be ..."
     */
    public static Result<String> id(Field field, String raw, Pattern pattern, String hint) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            return Result.error(field, Kind.BLANK, "is required.");
        }
        if (!pattern.matcher(text).matches()) {
            return Result.error(field, Kind.BAD_FORMAT, "must be " + hint + ".");
        }
        return Result.ok(text);
    }

    /** Text that is not blank and at most {@code maxLength} characters, trimmed. */
    public static Result<String> nonBlank(Field field, String raw, int maxLength) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            return Result.error(field, Kind.BLANK, "is required.");
        }
        if (text.length() > maxLength) {
            return Result.error(field, Kind.TOO_LONG, "is longer than " + maxLength + " characters.");
        }
        return Result.ok(text);
    }
}
