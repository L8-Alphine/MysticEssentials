package org.hyzionstudios.mysticessentials.modules.lootbox.service;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The identity a physical key carries in its item metadata
 * ({@code mysticessentials:lootbox_key}). The HMAC stops anyone who can write
 * item BSON from forging a marker; the serial ledger stays the real authority,
 * so a valid marker on a spent serial still opens nothing.
 *
 * @param keyId     the key definition
 * @param version   the definition version the key was issued from
 * @param serial    the ledger entry
 * @param secretId  which server secret signed it, for rotation
 * @param signature base64url HMAC-SHA256 over the fields above
 */
public record KeyMarker(String keyId, int version, String serial, int secretId, String signature) {

    public static final String METADATA_KEY = "mysticessentials:lootbox_key";

    /** @return a marker for these fields, signed with {@code secret}. */
    public static KeyMarker signed(String keyId, int version, String serial, int secretId, byte[] secret) {
        return new KeyMarker(keyId, version, serial, secretId, sign(keyId, version, serial, secretId, secret));
    }

    /** Constant-time check of the signature against {@code secret}. */
    public boolean verify(byte[] secret) {
        if (keyId == null || serial == null || signature == null || secret == null) {
            return false;
        }
        byte[] expected = sign(keyId, version, serial, secretId, secret).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.US_ASCII));
    }

    private static String sign(String keyId, int version, String serial, int secretId, byte[] secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            String payload = keyId + "|" + version + "|" + serial + "|" + secretId;
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
