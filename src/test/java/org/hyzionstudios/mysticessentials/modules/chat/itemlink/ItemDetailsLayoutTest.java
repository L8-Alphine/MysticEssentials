package org.hyzionstudios.mysticessentials.modules.chat.itemlink;

import org.hyzionstudios.mysticessentials.modules.chat.itemlink.ItemDetailsPage.Paragraph;

/**
 * Layout arithmetic for the Item Details panel, executed by the Gradle
 * verification task.
 *
 * <p>The generated sections are laid out from the heights this page declares,
 * so a paragraph measured short does not clip — the next section draws on top
 * of it. Every check here defends that one invariant: the height reserved for a
 * paragraph covers every line the client will actually draw.</p>
 */
public final class ItemDetailsLayoutTest {

    private static final int BODY_FONT_SIZE = 13;

    private ItemDetailsLayoutTest() {
    }

    public static void main(String[] args) {
        countsExplicitLineBreaks();
        ignoresZeroWidthMarkup();
        wrapsLongLines();
        trimsTextWhenCapping();
        reservesEnoughForEveryDrawnLine();
    }

    /**
     * The reported overlap: MysticRPG rejoins a description around its promoted
     * block with a blank line, so one paragraph carries several rendered lines
     * while remaining short enough to measure as one.
     */
    private static void countsExplicitLineBreaks() {
        String lore = "+6.6 Precision\n+5.9 Stamina\n-2.7 Defense\n"
                + "Resonance: Nature (2/3/5pc) - Hunter (2/4/5pc)\n\nHuntsman's - Uncommon";
        Paragraph measured = ItemDetailsPage.measure(lore, BODY_FONT_SIZE, false);

        require(measured.lines() == 6, "explicit line breaks were not counted: " + measured.lines());
        require(lore.equals(measured.text()), "a paragraph within the cap was altered");
    }

    /**
     * A modifier line spends characters on colour tags that occupy no width. The
     * visible text below fits one line; counted with its markup it does not.
     */
    private static void ignoresZeroWidthMarkup() {
        String visible = "+11 SignatureEnergy from a long affix source name here";
        String modifier = "<#8fd48f>" + visible + "  <#8191a5>(Weapon Reforge Bench)";
        require(modifier.length() > 83 && visible.length() + 25 < 83,
                "the fixture no longer straddles the wrap boundary");
        require(ItemDetailsPage.measure(modifier, BODY_FONT_SIZE, false).lines() == 1,
                "markup was measured as if it were rendered text");
    }

    private static void wrapsLongLines() {
        String line = "x".repeat(240);
        require(ItemDetailsPage.measure(line, BODY_FONT_SIZE, false).lines() >= 3,
                "a long unbroken line was measured as too few lines");
        require(ItemDetailsPage.measure(line, BODY_FONT_SIZE, true).lines()
                        > ItemDetailsPage.measure(line, BODY_FONT_SIZE, false).lines(),
                "the compact shell wraps sooner and must measure taller");
    }

    /**
     * A provider-supplied paragraph is not length-capped, so the ceiling has to
     * shorten the text as well — capping the reserved height alone would put the
     * overlap back for exactly the paragraphs the cap exists for.
     */
    private static void trimsTextWhenCapping() {
        Paragraph measured = ItemDetailsPage.measure("line\n".repeat(200), BODY_FONT_SIZE, false);

        require(measured.text().endsWith("…"), "a capped paragraph was not marked as trimmed");
        require(measured.lines() < 200, "the cap did not bound the reserved height");
        require(measured.text().startsWith("line"), "capping dropped the paragraph's opening text");
    }

    /** Re-measuring what will be drawn must never need more room than was reserved. */
    private static void reservesEnoughForEveryDrawnLine() {
        String[] cases = {
            "Patient feet, steady breath, certain shot.",
            "a\nb\nc",
            "word ".repeat(400),
            "nowordboundariesatallinthisverylongrun".repeat(40),
            "line\n".repeat(200),
            "\n\n\n",
            "…"
        };
        for (String text : cases) {
            for (boolean compact : new boolean[] {false, true}) {
                Paragraph measured = ItemDetailsPage.measure(text, BODY_FONT_SIZE, compact);
                Paragraph drawn = ItemDetailsPage.measure(measured.text(), BODY_FONT_SIZE, compact);
                require(drawn.lines() <= measured.lines(),
                        "reserved " + measured.lines() + " lines but the kept text draws "
                                + drawn.lines());
                require(measured.lines() * ItemDetailsPage.lineHeight(BODY_FONT_SIZE) > 0,
                        "a paragraph reserved no height at all");
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
