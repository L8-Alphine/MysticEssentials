package com.mysticlicensing.license;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Rebuilds the exact byte sequence the licensing server signed.
 *
 * <p>This is the one piece of the format that has to agree with the issuer
 * byte for byte, so it lives on its own and is covered by an interoperability
 * test against the portal's own vectors. If this drifts, every license in the
 * field stops verifying at once.
 *
 * <pre>
 *   UTF-8("mystic-license-envelope-v1")   domain separator, no length prefix
 *   uint32be(9)                           field count
 *   for each field, in this fixed order:
 *       uint32be(byteLength) || UTF-8(field)
 * </pre>
 *
 * <p>Fields 7-9 are the <b>base64url text</b> read out of the envelope, not the
 * decoded bytes. That is deliberate: both sides hash the strings they actually
 * read, so there is no re-encoding step where a stray padding character or a
 * different alphabet could make two correct implementations disagree.
 */
final class SigningInput {

    /** Must match src/services/crypto/envelope.ts in the portal. */
    static final String ENVELOPE_SIGNING_CONTEXT = "mystic-license-envelope-v1";

    static final int SIGNED_FIELD_COUNT = 9;

    private SigningInput() {
    }

    /**
     * @param version rendered as a decimal ASCII string, so envelope version 1
     *                contributes the single byte {@code '1'}
     */
    static byte[] build(String magic,
                        long version,
                        String signingKeyId,
                        String encryptionKeyId,
                        String encryptionAlgorithm,
                        String signatureAlgorithm,
                        String ivBase64Url,
                        String ciphertextBase64Url,
                        String authenticationTagBase64Url) {

        String[] fields = {
                magic,
                Long.toString(version),
                signingKeyId,
                encryptionKeyId,
                encryptionAlgorithm,
                signatureAlgorithm,
                ivBase64Url,
                ciphertextBase64Url,
                authenticationTagBase64Url
        };

        byte[] context = ENVELOPE_SIGNING_CONTEXT.getBytes(StandardCharsets.UTF_8);
        byte[][] encoded = new byte[fields.length][];
        int total = context.length + Integer.BYTES;
        for (int i = 0; i < fields.length; i++) {
            encoded[i] = fields[i].getBytes(StandardCharsets.UTF_8);
            total += Integer.BYTES + encoded[i].length;
        }

        ByteBuffer buffer = ByteBuffer.allocate(total); // big endian by default
        buffer.put(context);
        buffer.putInt(SIGNED_FIELD_COUNT);
        for (byte[] field : encoded) {
            buffer.putInt(field.length);
            buffer.put(field);
        }
        return buffer.array();
    }
}
