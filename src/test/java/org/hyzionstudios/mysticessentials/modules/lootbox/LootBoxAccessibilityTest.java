package org.hyzionstudios.mysticessentials.modules.lootbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.hyzionstudios.mysticessentials.modules.lootbox.model.PlayerLootState;
import org.hyzionstudios.mysticessentials.modules.lootbox.service.MotionRules;
import org.hyzionstudios.mysticessentials.platform.ui.MysticPage;

/**
 * The LootBox accessibility pass (P2-L, bible §10 and §19), checked so it stays
 * done: reduced motion per player, readable contrast through the MysticTheme
 * tokens and the runtime text colours, long text that wraps or shrinks instead
 * of running out of its box, window sizes no larger than the game's own pages,
 * and focus only where a page exists to be typed into. The layout checks read
 * the shipped {@code .ui} documents; a client still has to confirm how they look.
 */
public final class LootBoxAccessibilityTest {

    private static final Path UI = Path.of("src/main/resources/Common/UI/Custom/MysticEssentials");

    /**
     * {@code Common/ContainerFullPatch.png}'s fill: the lightest surface text sits
     * on at rest. A Panel's centre is transparent, so panel text sits on it too.
     */
    private static final String WINDOW = "#16212f";
    /** A hovered list row: the Well ({@code #0a0f16(0.42)}) under {@code @RowStyle}'s hover ({@code #7a9cc6(0.16)}). */
    private static final String ROW_HOVERED = blend(blend(WINDOW, "#0a0f16", 0.42), "#7a9cc6", 0.16);
    private static final double AA = 4.5;

    /** The game's largest custom pages (CommandListPage 1050 wide, 700 tall). */
    private static final int MAX_WIDTH = 1050;
    private static final int MAX_HEIGHT = 700;

    private LootBoxAccessibilityTest() {
    }

    public static void main(String[] args) throws IOException {
        Map<String, String> documents = lootBoxDocuments();
        reducedMotionFollowsThePlayerThenTheServer();
        reducedMotionLandsAtOnce();
        motionChoicesParse();
        themeTextTokensAreReadable();
        hintsAndNotesUseReadableColours(documents);
        runtimeTextColoursAreReadable(documents);
        windowsFitTheGamesOwnSizes(documents);
        longTextWrapsOrShrinks(documents);
        focusOnlyWhereAPageIsForTyping(documents);
        System.out.println("LootBoxAccessibilityTest: all checks passed.");
    }

    // ----- Reduced motion ---------------------------------------------------------

    private static void reducedMotionFollowsThePlayerThenTheServer() {
        PlayerLootState never = new PlayerLootState();
        require(!MotionRules.reduced(never, false) && MotionRules.reduced(never, true),
                "a player who never chose doesn't follow the server default");
        require(!MotionRules.reduced(null, false) && MotionRules.reduced(null, true),
                "a missing player document doesn't follow the server default");
        PlayerLootState reduced = new PlayerLootState();
        reduced.reducedMotion = Boolean.TRUE;
        PlayerLootState full = new PlayerLootState();
        full.reducedMotion = Boolean.FALSE;
        require(MotionRules.reduced(reduced, false) && !MotionRules.reduced(full, true),
                "a player's own choice doesn't win over the server default");
        require("reduced (server default)".equals(MotionRules.describe(never, true))
                && "full (server default)".equals(MotionRules.describe(never, false))
                && "reduced".equals(MotionRules.describe(reduced, false))
                && "full".equals(MotionRules.describe(full, true)),
                "the motion setting reads wrongly in chat");
    }

    private static void reducedMotionLandsAtOnce() {
        require(MotionRules.revealFrames(4000, 10, false) == 40, "4 s at 10 fps isn't 40 frames");
        require(MotionRules.revealFrames(4000, 10, true) == 0, "reduced motion still animates");
        require(MotionRules.revealFrames(0, 10, false) == 0 && MotionRules.revealFrames(-5, 10, false) == 0,
                "a zero or negative duration animates");
        require(MotionRules.revealFrames(1000, 1000, false) == MotionRules.MAX_FRAMES_PER_SECOND,
                "the frame rate isn't capped");
        require(MotionRules.framesPerSecond(0) == 1 && MotionRules.framesPerSecond(-3) == 1,
                "a frame rate below 1 isn't raised to 1");
        require(MotionRules.revealFrames(Long.MAX_VALUE / 1000, 30, false) == Integer.MAX_VALUE,
                "a huge duration overflows the frame count");
    }

    private static void motionChoicesParse() {
        require(MotionRules.parse("reduced") == MotionRules.Choice.REDUCED
                && MotionRules.parse(" FULL ") == MotionRules.Choice.FULL
                && MotionRules.parse("Default") == MotionRules.Choice.DEFAULT,
                "a motion choice doesn't parse");
        // "motion off" means opposite things to different players, so it is refused.
        require(MotionRules.parse("on") == null && MotionRules.parse("off") == null
                && MotionRules.parse("") == null && MotionRules.parse(null) == null,
                "an ambiguous motion choice was accepted");
        require(MotionRules.Choice.REDUCED.stored() == Boolean.TRUE
                && MotionRules.Choice.FULL.stored() == Boolean.FALSE
                && MotionRules.Choice.DEFAULT.stored() == null,
                "a motion choice stores the wrong value");
    }

    // ----- Contrast -----------------------------------------------------------------

    private static void themeTextTokensAreReadable() throws IOException {
        String theme = Files.readString(UI.resolve("MysticTheme.ui"));
        Matcher token = Pattern.compile("(?m)^@(\\w+) = (#[0-9A-Fa-f]{6});").matcher(theme);
        int checked = 0;
        while (token.find()) {
            String name = token.group(1);
            if (!name.matches("(Text|Gold|Blue|Success|Warn|Danger)\\w*") || "TextFaint".equals(name)) {
                continue;
            }
            requireReadable(token.group(2), "MysticTheme @" + name, WINDOW);
            checked++;
        }
        require(checked >= 10, "found only " + checked + " text tokens in MysticTheme.ui");
    }

    private static void hintsAndNotesUseReadableColours(Map<String, String> documents) {
        for (Map.Entry<String, String> document : documents.entrySet()) {
            String text = withoutComments(document.getValue());
            require(!text.contains("@CaptionStyle") && !text.contains("@TextFaint"),
                    document.getKey() + " sets text in the faint caption colour; use $M.@HintStyle");
            Matcher literal = Pattern.compile("TextColor:\\s*#").matcher(text);
            require(!literal.find(), document.getKey() + " writes a text colour literal instead of a theme token");
        }
    }

    private static void runtimeTextColoursAreReadable(Map<String, String> documents) {
        Pattern label = Pattern.compile("Label #([A-Za-z][A-Za-z0-9]*)");
        int checked = 0;
        for (Map.Entry<String, String> document : documents.entrySet()) {
            Matcher matcher = label.matcher(document.getValue());
            while (matcher.find()) {
                // Row text has to stay readable while the row is hovered.
                String surface = document.getKey().endsWith("Row.ui") ? ROW_HOVERED : WINDOW;
                requireReadable(MysticPage.uiTextColor(matcher.group(1)),
                        document.getKey() + " #" + matcher.group(1) + " (MysticPage.UI_TEXT_COLORS)", surface);
                checked++;
            }
        }
        require(checked > 100, "found only " + checked + " LootBox labels");
    }

    // ----- Layout ---------------------------------------------------------------------

    private static void windowsFitTheGamesOwnSizes(Map<String, String> documents) {
        Pattern window = Pattern.compile("\\$C\\.@Container[^{]*\\{\\s*Anchor:\\s*\\(Width:\\s*(\\d+),\\s*Height:\\s*(\\d+)");
        int pages = 0;
        for (Map.Entry<String, String> document : documents.entrySet()) {
            Matcher matcher = window.matcher(document.getValue());
            if (!matcher.find()) {
                continue;
            }
            int width = Integer.parseInt(matcher.group(1));
            int height = Integer.parseInt(matcher.group(2));
            require(width <= MAX_WIDTH && height <= MAX_HEIGHT, document.getKey() + " is " + width + "×" + height
                    + ", larger than the game's own pages (" + MAX_WIDTH + "×" + MAX_HEIGHT + ")");
            pages++;
        }
        require(pages >= 15, "found only " + pages + " LootBox windows");
    }

    private static void longTextWrapsOrShrinks(Map<String, String> documents) {
        Pattern rowText = Pattern.compile("Label #(Name|Meta|\\w+Name|\\w+Meta|AuditDetail|AuditTarget)\\s*\\{([^}]*)\\}");
        for (Map.Entry<String, String> document : documents.entrySet()) {
            if (!document.getKey().endsWith("Row.ui")) {
                continue;
            }
            Matcher matcher = rowText.matcher(document.getValue());
            while (matcher.find()) {
                require(matcher.group(2).matches("(?s).*\\$M\\.@\\w*Fit\\w*Style.*"),
                        document.getKey() + " #" + matcher.group(1) + " can run past its row; use a *FitStyle");
            }
        }
        String line = documents.get("LootBoxConfirmActionLine.ui");
        require(line != null && line.contains("$M.@BodyStyle") && !line.matches("(?s).*Anchor:\\s*\\([^)]*Height.*"),
                "the shared detail line (problems, diffs, lore preview) has a fixed height or doesn't wrap");
        String preview = withoutComments(documents.get("LootBoxPreview.ui"));
        require(preview.matches("(?s).*Label #Rules\\s*\\{\\s*Anchor:\\s*\\(Bottom: 4\\);\\s*Style:\\s*\\$M\\.@MetaWrapStyle.*"),
                "the preview's rules line doesn't wrap");
    }

    private static void focusOnlyWhereAPageIsForTyping(Map<String, String> documents) {
        List<String> focused = new ArrayList<>();
        for (Map.Entry<String, String> document : documents.entrySet()) {
            Matcher matcher = Pattern.compile("AutoFocus:\\s*true").matcher(withoutComments(document.getValue()));
            int count = 0;
            while (matcher.find()) {
                count++;
            }
            require(count <= 1, document.getKey() + " focuses " + count + " fields at once");
            if (count == 1) {
                focused.add(document.getKey());
            }
        }
        // Pages rebuild on every press, so a focused field that is hidden or not
        // the page's purpose would take the keyboard from the player each time.
        require(focused.equals(List.of("LootBoxItemPicker.ui")),
                "only the item picker should focus a field when it opens, not " + focused);
    }

    // ----- Helpers --------------------------------------------------------------------

    private static Map<String, String> lootBoxDocuments() throws IOException {
        Map<String, String> documents = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(UI)) {
            for (Path file : files.filter(path -> path.getFileName().toString().matches("LootBox\\w*\\.ui"))
                    .sorted().toList()) {
                documents.put(file.getFileName().toString(), Files.readString(file));
            }
        }
        require(documents.size() >= 30, "found only " + documents.size() + " LootBox documents under " + UI);
        return documents;
    }

    private static String withoutComments(String text) {
        return text.replaceAll("//[^\\n]*", "");
    }

    private static void requireReadable(String colour, String what, String background) {
        double ratio = contrast(colour, background);
        require(ratio >= AA, what + " " + colour + " is " + String.format("%.2f", ratio) + ":1 on " + background
                + "; text people read needs " + AA + ":1");
    }

    /** WCAG 2 contrast ratio. */
    static double contrast(String a, String b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(String hex) {
        int[] rgb = rgb(hex);
        double[] linear = new double[3];
        for (int i = 0; i < 3; i++) {
            double c = rgb[i] / 255.0;
            linear[i] = c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2];
    }

    private static String blend(String base, String over, double alpha) {
        int[] a = rgb(base);
        int[] b = rgb(over);
        StringBuilder out = new StringBuilder("#");
        for (int i = 0; i < 3; i++) {
            out.append(String.format("%02x", (int) Math.round(a[i] + (b[i] - a[i]) * alpha)));
        }
        return out.toString();
    }

    private static int[] rgb(String hex) {
        String digits = hex.startsWith("#") ? hex.substring(1) : hex;
        return new int[] {Integer.parseInt(digits.substring(0, 2), 16), Integer.parseInt(digits.substring(2, 4), 16),
                Integer.parseInt(digits.substring(4, 6), 16)};
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
