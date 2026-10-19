package org.hyzionstudios.mysticessentials.modules.lootbox.odds;

import java.security.SecureRandom;
import java.util.random.RandomGenerator;

/**
 * The server-side roll. {@link RandomGenerator#nextLong(long)} is unbiased by its
 * contract (rejection sampling), and production always uses an unseeded
 * {@link SecureRandom}; tests inject a seeded generator instead.
 */
public final class SecureDraw {

    private final RandomGenerator random;

    public SecureDraw() {
        this(new SecureRandom());
    }

    public SecureDraw(RandomGenerator random) {
        this.random = random;
    }

    /** @return a uniform value in {@code [0, bound)}. */
    public long roll(long bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive: " + bound);
        }
        return random.nextLong(bound);
    }

    /** 128 random bits as lowercase base32 (26 chars): opening ids, serials, request ids. */
    public String id() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return base32(bytes);
    }

    private static final char[] ALPHABET = "abcdefghijklmnopqrstuvwxyz234567".toCharArray();

    static String base32(byte[] bytes) {
        StringBuilder out = new StringBuilder((bytes.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(ALPHABET[(buffer >>> (bits - 5)) & 31]);
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(ALPHABET[(buffer << (5 - bits)) & 31]);
        }
        return out.toString();
    }
}
