package com.mysticlicensing.license;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small, strict, read-only JSON parser.
 *
 * <p>Package-private on purpose: this is not a JSON library and nothing outside
 * the verifier should reach for it. See the README for why the core bundles
 * this rather than depending on Gson - in short, this jar lands on a mod
 * classpath we do not control, and a relocated JSON library is a bigger
 * liability than 150 lines of parser we can read in full.
 *
 * <p>Two properties matter for correctness:
 * <ul>
 *   <li>Integers survive as {@code Long}, exactly. Timestamps and
 *       {@code grace_period_seconds} must not round-trip through a double.</li>
 *   <li>It is strict. Trailing content, unterminated strings and bad escapes
 *       throw rather than being silently tolerated, so a malformed file is
 *       reported as {@code INVALID_FORMAT} instead of parsing into something
 *       half-sensible.</li>
 * </ul>
 *
 * <p>Every throw is an unchecked {@link IllegalArgumentException}, which the
 * verifier catches and converts to a status. Nothing escapes.
 */
final class MiniJson {

    /**
     * Depth cap. A hostile file could otherwise nest arrays deeply enough to
     * overflow the stack, and a StackOverflowError in a game thread is exactly
     * the kind of outage this library exists to avoid.
     */
    private static final int MAX_DEPTH = 64;

    private final String src;
    private int pos;
    private int depth;

    private MiniJson(String src) {
        this.src = src;
    }

    static Object parse(String text) {
        MiniJson parser = new MiniJson(text);
        parser.skipWhitespace();
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (parser.pos != parser.src.length()) {
            throw new IllegalArgumentException("trailing content at " + parser.pos);
        }
        return value;
    }

    private Object readValue() {
        skipWhitespace();
        if (pos >= src.length()) {
            throw new IllegalArgumentException("unexpected end of input");
        }
        char c = src.charAt(pos);
        switch (c) {
            case '{':
                return readObject();
            case '[':
                return readArray();
            case '"':
                return readString();
            case 't':
                expect("true");
                return Boolean.TRUE;
            case 'f':
                expect("false");
                return Boolean.FALSE;
            case 'n':
                expect("null");
                return null;
            default:
                return readNumber();
        }
    }

    private Map<String, Object> readObject() {
        enter();
        Map<String, Object> map = new LinkedHashMap<>();
        pos++; // {
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            leave();
            return map;
        }
        while (true) {
            skipWhitespace();
            String key = readString();
            skipWhitespace();
            if (peek() != ':') {
                throw new IllegalArgumentException("expected ':' at " + pos);
            }
            pos++;
            map.put(key, readValue());
            skipWhitespace();
            char c = peek();
            pos++;
            if (c == '}') {
                leave();
                return map;
            }
            if (c != ',') {
                throw new IllegalArgumentException("expected ',' or '}' at " + (pos - 1));
            }
        }
    }

    private List<Object> readArray() {
        enter();
        List<Object> list = new ArrayList<>();
        pos++; // [
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            leave();
            return list;
        }
        while (true) {
            list.add(readValue());
            skipWhitespace();
            char c = peek();
            pos++;
            if (c == ']') {
                leave();
                return list;
            }
            if (c != ',') {
                throw new IllegalArgumentException("expected ',' or ']' at " + (pos - 1));
            }
        }
    }

    private String readString() {
        if (peek() != '"') {
            throw new IllegalArgumentException("expected string at " + pos);
        }
        pos++;
        StringBuilder out = new StringBuilder();
        while (true) {
            if (pos >= src.length()) {
                throw new IllegalArgumentException("unterminated string");
            }
            char c = src.charAt(pos++);
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (pos >= src.length()) {
                throw new IllegalArgumentException("unterminated escape");
            }
            char esc = src.charAt(pos++);
            switch (esc) {
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case '/' -> out.append('/');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    if (pos + 4 > src.length()) {
                        throw new IllegalArgumentException("truncated \\u escape");
                    }
                    out.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                    pos += 4;
                }
                default -> throw new IllegalArgumentException("bad escape \\" + esc);
            }
        }
    }

    private Object readNumber() {
        int start = pos;
        while (pos < src.length() && "-+.eE0123456789".indexOf(src.charAt(pos)) >= 0) {
            pos++;
        }
        String text = src.substring(start, pos);
        if (text.isEmpty()) {
            throw new IllegalArgumentException("expected a value at " + start);
        }
        try {
            if (text.indexOf('.') < 0 && text.indexOf('e') < 0 && text.indexOf('E') < 0) {
                return Long.parseLong(text);
            }
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad number '" + text + "' at " + start, e);
        }
    }

    private void expect(String literal) {
        if (!src.startsWith(literal, pos)) {
            throw new IllegalArgumentException("expected " + literal + " at " + pos);
        }
        pos += literal.length();
    }

    private char peek() {
        if (pos >= src.length()) {
            throw new IllegalArgumentException("unexpected end of input");
        }
        return src.charAt(pos);
    }

    private void skipWhitespace() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }

    private void enter() {
        if (++depth > MAX_DEPTH) {
            throw new IllegalArgumentException("nesting deeper than " + MAX_DEPTH);
        }
    }

    private void leave() {
        depth--;
    }

    // ------------------------------------------------------------- accessors

    /** Narrows to a JSON object, or null if it is anything else. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> asObject(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    /** Narrows to a JSON string, or null if it is anything else. */
    static String asString(Object value) {
        return value instanceof String s ? s : null;
    }

    /** Narrows to an exact integer, or null if it is anything else. */
    static Long asLong(Object value) {
        if (value instanceof Long l) {
            return l;
        }
        if (value instanceof BigDecimal d) {
            try {
                return d.longValueExact();
            } catch (ArithmeticException e) {
                return null;
            }
        }
        return null;
    }

    /** Every string in a JSON array, skipping anything that is not one. */
    static List<String> asStringList(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String s) {
                    out.add(s);
                }
            }
        }
        return out;
    }
}
