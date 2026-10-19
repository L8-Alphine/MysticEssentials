package org.hyzionstudios.mysticessentials.modules.lootbox.odds;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

/**
 * Turns exact weights into the percentages players read. A chance that fits in
 * {@code maxDecimals} places is shown exactly ({@code 0.0125%}, {@code 45.00%});
 * one that does not is rounded with at least three significant digits
 * ({@code 33.33%}, {@code 0.00333%}). A non-zero chance is never shown as
 * {@code 0%}: below the finest step it reads as {@code <0.00000001%} next to its
 * exact "1 in N" form.
 */
public final class OddsFormatter {

    /** Three significant digits: the unscaled rounded value must reach 100. */
    private static final java.math.BigInteger HUNDRED = java.math.BigInteger.valueOf(100);

    private final int minDecimals;
    private final int maxDecimals;
    private final Locale locale;

    public OddsFormatter(int minDecimals, int maxDecimals, Locale locale) {
        this.minDecimals = Math.max(0, minDecimals);
        this.maxDecimals = Math.max(this.minDecimals, maxDecimals);
        this.locale = locale == null ? Locale.US : locale;
    }

    /** @return e.g. {@code 45.00%}, {@code 0.0125%}, {@code 100%}, or {@code <0.00000001%}. */
    public String percent(long weight, long total) {
        if (weight <= 0 || total <= 0) {
            return format(BigDecimal.ZERO, minDecimals) + "%";
        }
        if (weight >= total) {
            return "100%";
        }
        BigDecimal numerator = BigDecimal.valueOf(weight).multiply(BigDecimal.valueOf(100));
        BigDecimal denominator = BigDecimal.valueOf(total);
        try {
            BigDecimal terminating = numerator.divide(denominator);
            int scale = Math.max(minDecimals, Math.max(0, terminating.stripTrailingZeros().scale()));
            if (scale <= maxDecimals) {
                return format(terminating, scale) + "%";
            }
        } catch (ArithmeticException nonTerminating) {
            // Falls through to rounding.
        }
        BigDecimal exact = numerator.divide(denominator, MathContext.DECIMAL128);
        for (int decimals = minDecimals; decimals <= maxDecimals; decimals++) {
            BigDecimal rounded = exact.setScale(decimals, RoundingMode.HALF_UP);
            if (rounded.signum() > 0 && rounded.unscaledValue().abs().compareTo(HUNDRED) >= 0) {
                return format(rounded, decimals) + "%";
            }
        }
        BigDecimal finest = exact.setScale(maxDecimals, RoundingMode.HALF_UP);
        if (finest.signum() > 0) {
            return format(finest, maxDecimals) + "%";
        }
        return "<" + format(BigDecimal.ONE.movePointLeft(maxDecimals), maxDecimals) + "%";
    }

    /** @return {@code 1 in 8,000}, rounded to the nearest whole number. */
    public String oneIn(long weight, long total) {
        if (weight <= 0) {
            return "never";
        }
        BigDecimal ratio = BigDecimal.valueOf(total).divide(BigDecimal.valueOf(weight), 0, RoundingMode.HALF_UP);
        return "1 in " + NumberFormat.getIntegerInstance(locale).format(ratio);
    }

    /** True when the shown percentages of {@code weights} do not add up to exactly 100. */
    public boolean needsRoundingNote(List<Long> weights, long total) {
        BigDecimal sum = BigDecimal.ZERO;
        for (long weight : weights) {
            String shown = percent(weight, total);
            if (shown.startsWith("<")) {
                return true;
            }
            sum = sum.add(parse(shown));
        }
        return sum.compareTo(BigDecimal.valueOf(100)) != 0;
    }

    private BigDecimal parse(String shown) {
        String digits = shown.substring(0, shown.length() - 1);
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(locale);
        digits = digits.replace(String.valueOf(symbols.getGroupingSeparator()), "")
                .replace(symbols.getDecimalSeparator(), '.');
        return new BigDecimal(digits);
    }

    private String format(BigDecimal value, int decimals) {
        DecimalFormat format = (DecimalFormat) NumberFormat.getNumberInstance(locale);
        format.setGroupingUsed(false);
        format.setMinimumFractionDigits(decimals);
        format.setMaximumFractionDigits(decimals);
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format.format(value);
    }
}
