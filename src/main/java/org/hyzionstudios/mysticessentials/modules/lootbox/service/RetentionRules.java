package org.hyzionstudios.mysticessentials.modules.lootbox.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.hyzionstudios.mysticessentials.modules.lootbox.model.Opening;

/**
 * Retention and privacy (P4-WP5). Engine-free.
 *
 * <ul>
 *   <li>Under {@code PAID_RANDOM_RESTRICTED} every record is kept at least
 *       {@link #PAID_MINIMUM_DAYS} days, whatever the config says (Design Bible
 *       §16, "maintain required records").</li>
 *   <li>An opening is only swept once it is settled ({@code DELIVERED} or
 *       {@code ABORTED}) and older than the retention; one still committed,
 *       delivering or under review is kept, however old.</li>
 *   <li>Exports replace player UUIDs with stable pseudonyms unless the exporter
 *       may see identifiers ({@code mysticessentials.lootbox.audit.identifiers}).
 *       The pseudonym is a keyed hash: the same player gets the same pseudonym in
 *       every export of this network, and it can't be reversed without the
 *       server's secret.</li>
 * </ul>
 */
public final class RetentionRules {

    public static final int PAID_MINIMUM_DAYS = 365;
    private static final Pattern UUID_TEXT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private RetentionRules() {
    }

    /** The retention actually applied: {@code configured} (at least 1), raised to 365 under the paid profile. */
    public static int effectiveDays(int configured, String policyProfile) {
        int days = Math.max(1, configured);
        boolean paid = policyProfile != null
                && "PAID_RANDOM_RESTRICTED".equals(policyProfile.trim().toUpperCase(Locale.ROOT));
        return paid ? Math.max(days, PAID_MINIMUM_DAYS) : days;
    }

    /** Whether {@code opening} may be swept at {@code cutoff} (epoch millis). */
    public static boolean sweepable(Opening opening, long cutoff) {
        if (opening == null) {
            return false;
        }
        boolean settled = Opening.DELIVERED.equals(opening.state) || Opening.ABORTED.equals(opening.state);
        long at = Math.max(opening.createdAt, opening.committedAt);
        return settled && at > 0 && at < cutoff;
    }

    /** {@code text} with every UUID replaced by its pseudonym under {@code salt}. */
    public static String pseudonymise(String text, byte[] salt) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher matcher = UUID_TEXT.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, pseudonym(matcher.group().toLowerCase(Locale.ROOT), salt));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** {@code player-<12 hex>}: SHA-256 of salt and UUID. */
    public static String pseudonym(String uuid, byte[] salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt == null ? new byte[0] : salt);
            digest.update((byte) 0);
            digest.update(uuid.getBytes(StandardCharsets.UTF_8));
            return "player-" + HexFormat.of().formatHex(digest.digest()).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
