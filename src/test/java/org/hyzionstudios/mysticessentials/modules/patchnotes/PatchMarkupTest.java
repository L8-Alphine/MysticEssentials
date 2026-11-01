package org.hyzionstudios.mysticessentials.modules.patchnotes;

import java.util.List;

import org.hyzionstudios.mysticessentials.modules.patchnotes.PatchMarkup.Line;
import org.hyzionstudios.mysticessentials.modules.patchnotes.PatchMarkup.Type;

/**
 * Patch note lines: the add/remove templates draw the marker, so the text
 * must not carry one too, and a wrapped item shows its marker once.
 */
public final class PatchMarkupTest {

    public static void main(String[] args) {
        List<Line> lines = PatchMarkup.renderSection("What's new",
                "+ Added homes.\n- A plain bullet in a changes section.\n+ " + "word ".repeat(40).trim(), "changes");
        require(lines.get(0).type() == Type.HEADER && lines.get(0).section().equals("changes"),
                "the header carries its section's category");
        Line added = lines.get(1);
        require(added.type() == Type.ADD && added.text().equals("Added homes.") && !added.continued(),
                "an addition's text has no '+' of its own: '" + added.text() + "'");
        Line bullet = lines.get(2);
        require(bullet.type() == Type.BULLET && bullet.text().startsWith("• "), "neutral bullets keep their dot");
        List<Line> wrapped = lines.subList(3, lines.size());
        require(wrapped.size() > 1, "a long addition wraps");
        require(!wrapped.get(0).continued() && wrapped.stream().skip(1).allMatch(Line::continued),
                "only the first line of a wrapped item shows the marker");

        List<Line> removals = PatchMarkup.renderSection("", "- Gone.", "removals");
        require(removals.get(0).type() == Type.REMOVE && removals.get(0).text().equals("Gone."),
                "a bullet in a removals section is a removal, without a '-' in its text");
        PatchNotesConfig config = new PatchNotesConfig();
        require(!org.hyzionstudios.mysticessentials.platform.ui.VanillaIcons.resolve(config.defaultIcon).isEmpty(),
                "the default patch icon names a real icon: " + config.defaultIcon);
        for (String id : List.of("additions", "fixes", "changes", "removals")) {
            require(!org.hyzionstudios.mysticessentials.platform.ui.VanillaIcons.resolve(config.iconFor(id)).isEmpty(),
                    "the " + id + " heading icon names a real icon: " + config.iconFor(id));
        }
        PatchNotesConfig.Category old = new PatchNotesConfig.Category();
        old.id = "fixes";
        config.categories = List.of(old);
        require(!config.iconFor("fixes").isEmpty(), "a category saved before icons existed still gets the shipped one");
        System.out.println("PatchMarkupTest: all checks passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
