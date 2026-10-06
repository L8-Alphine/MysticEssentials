package org.hyzionstudios.mysticessentials.core.message;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import org.hyzionstudios.mysticessentials.core.placeholder.PlaceholderServiceImpl;

/**
 * Dependency-free checks that message params are filled after placeholder
 * resolution, so player text passed as a param never expands a server placeholder.
 */
public final class MessageParamOrderTest {

    private static final String MALICIOUS = "{player_name} %player_name% {balance} %mystic_balance%";

    private MessageParamOrderTest() {
    }

    public static void main(String[] args) {
        paramValuesAreNeverResolved();
        templatePlaceholdersStillExpand();
        paramWinsOverSameNamedPlaceholder();
        paramsDoNotChain();
        noParamsResolvesWholeTemplate();
        markupInParamsIsKeptForColourizing();
    }

    private static void paramValuesAreNeverResolved() {
        List<String> seen = new ArrayList<>();
        UnaryOperator<String> recording = text -> {
            seen.add(text);
            return resolver().apply(text);
        };
        String out = MessageServiceImpl.fillParams("&7{sender} &8> &f{message}",
                Map.of("sender", "Alex", "message", MALICIOUS), recording);
        require(("&7Alex &8> &f" + MALICIOUS).equals(out), "a param value was expanded: " + out);
        for (String text : seen) {
            require(!text.contains("Alex") && !text.contains("%player_name%"),
                    "a param value reached the placeholder resolver: " + text);
        }
    }

    private static void templatePlaceholdersStillExpand() {
        String out = MessageServiceImpl.fillParams(
                "{player_name} ({balance}, %mystic_balance%): {message} {unknown}",
                Map.of("message", MALICIOUS), resolver());
        require(("Steve (1000, 1000): " + MALICIOUS + " {unknown}").equals(out),
                "template placeholders did not expand around the param: " + out);
    }

    private static void paramWinsOverSameNamedPlaceholder() {
        String out = MessageServiceImpl.fillParams("{player_name} and {player_name}",
                Map.of("player_name", "%balance%"), resolver());
        require("%balance% and %balance%".equals(out), "the param did not win over the placeholder: " + out);
    }

    private static void paramsDoNotChain() {
        String out = MessageServiceImpl.fillParams("{a}|{b}|{{b}}", Map.of("a", "{b}", "b", "X"), resolver());
        require("{b}|X|{X}".equals(out), "a param value was substituted again: " + out);
    }

    private static void noParamsResolvesWholeTemplate() {
        String out = MessageServiceImpl.fillParams("Hi {player_name}, %balance%", Map.of(), resolver());
        require("Hi Steve, 1000".equals(out), "a template without params was not resolved: " + out);
    }

    private static void markupInParamsIsKeptForColourizing() {
        String out = MessageServiceImpl.fillParams("&f{amount}", Map.of("amount", "&a$5"), resolver());
        require("&f&a$5".equals(out), "param markup was not kept for colourizing: " + out);
        String plain = MysticText.stripMarkup(MessageServiceImpl.fillParams("&7From {sender}: {message}",
                Map.of("sender", "&cAlex", "message", MALICIOUS), resolver()));
        require(("From Alex: " + MALICIOUS).equals(plain), "the plain form expanded a param: " + plain);
    }

    /** The real placeholder registry, standing in for the server's with two fixed values. */
    private static UnaryOperator<String> resolver() {
        PlaceholderServiceImpl placeholders = new PlaceholderServiceImpl(null);
        placeholders.register("player_name", (uuid, arg) -> "Steve");
        placeholders.register("balance", (uuid, arg) -> "1000");
        return text -> placeholders.resolve(null, text);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
