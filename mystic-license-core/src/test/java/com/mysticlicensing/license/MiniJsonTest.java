package com.mysticlicensing.license;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parser is small enough to read, which is the point, but it sits directly
 * in front of attacker-influenced bytes for the envelope. These tests pin the
 * two properties the verifier relies on: exact integers, and strictness.
 */
class MiniJsonTest {

    @Test
    @DisplayName("integers survive exactly, without a trip through double")
    void integersAreExact() {
        Map<String, Object> parsed = MiniJson.asObject(
                MiniJson.parse("{\"grace\":259200,\"big\":9007199254740993}"));

        assertEquals(259_200L, MiniJson.asLong(parsed.get("grace")));
        assertEquals(9_007_199_254_740_993L, MiniJson.asLong(parsed.get("big")),
                "a double would have rounded this");
    }

    @Test
    @DisplayName("nested objects, arrays and nulls parse")
    void structures() {
        Map<String, Object> parsed = MiniJson.asObject(MiniJson.parse(
                "{\"a\":{\"b\":[1,\"two\",null,true,false]},\"c\":null}"));

        Map<String, Object> a = MiniJson.asObject(parsed.get("a"));
        List<Object> b = (List<Object>) a.get("b");

        assertEquals(5, b.size());
        assertEquals(1L, b.get(0));
        assertEquals("two", b.get(1));
        assertNull(b.get(2));
        assertEquals(Boolean.TRUE, b.get(3));
        assertNull(parsed.get("c"));
    }

    @Test
    @DisplayName("escapes and non-ASCII round-trip")
    void escapes() {
        Map<String, Object> parsed = MiniJson.asObject(MiniJson.parse(
                "{\"s\":\"line\\nbreak \\\"quoted\\\" \\u00e9 caf\u00e9\"}"));

        assertEquals("line\nbreak \"quoted\" \u00e9 caf\u00e9", MiniJson.asString(parsed.get("s")));
    }

    @ParameterizedTest(name = "rejects {0}")
    @ValueSource(strings = {
            "",
            "{",
            "}",
            "{\"a\"}",
            "{\"a\":}",
            "{\"a\":1,}",
            "[1,]",
            "{\"a\":1} trailing",
            "\"unterminated",
            "{\"a\":\"bad \\q escape\"}",
            "{\"a\":\"truncated \\u12\"}",
            "tru",
            "nul"
    })
    @DisplayName("malformed JSON throws IllegalArgumentException, never anything else")
    void strictness(String text) {
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse(text));
    }

    @Test
    @DisplayName("nesting deeper than the cap is rejected rather than overflowing the stack")
    void depthCap() {
        String deep = "[".repeat(10_000) + "]".repeat(10_000);

        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> MiniJson.parse(deep));

        assertTrue(e.getMessage().contains("nesting"), e.getMessage());
    }

    @Test
    @DisplayName("the narrowing accessors return null rather than throwing on a type mismatch")
    void accessorsAreForgiving() {
        Map<String, Object> parsed = MiniJson.asObject(
                MiniJson.parse("{\"n\":1,\"s\":\"x\",\"o\":{},\"a\":[1,\"y\",null]}"));

        assertNull(MiniJson.asString(parsed.get("n")));
        assertNull(MiniJson.asLong(parsed.get("s")));
        assertNull(MiniJson.asObject(parsed.get("s")));
        assertEquals(List.of("y"), MiniJson.asStringList(parsed.get("a")),
                "non-strings in an array are skipped, not fatal");
        assertEquals(List.of(), MiniJson.asStringList(parsed.get("n")));
    }

    @Test
    @DisplayName("a fractional number is not mistaken for an integer")
    void fractionalNumbers() {
        Map<String, Object> parsed = MiniJson.asObject(MiniJson.parse("{\"a\":1.5,\"b\":2.0}"));

        assertNull(MiniJson.asLong(parsed.get("a")), "1.5 is not an exact long");
        assertEquals(2L, MiniJson.asLong(parsed.get("b")));
    }
}
