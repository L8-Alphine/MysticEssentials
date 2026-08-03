package com.mysticlicensing.license;

/**
 * The key material a shipped mod build carries.
 *
 * <p>Everything here is intended to be readable by anyone holding the jar. That
 * is not a compromise, it is the design:
 *
 * <ul>
 *   <li>The <b>Ed25519 public keys</b> are public by definition. They are the
 *       security boundary - forging a license needs the matching private key,
 *       which only the licensing server holds.</li>
 *   <li>The <b>AES content key</b> is symmetric and must be assumed extractable
 *       from any jar that can decrypt with it. It makes license contents opaque
 *       to casual inspection and nothing more. Extracting it lets someone
 *       <em>read</em> a license; it does not let them <em>make</em> one.</li>
 * </ul>
 *
 * <h2>Regenerating these values</h2>
 * In the licensing portal repository (MysticGate):
 * <pre>
 *   npm run keys:show-public     # prints SIGNING_KEY_ID and the SPKI base64
 *   npm run keys:content         # prints ACTIVE_LICENSE_CONTENT_KEY_ID and the key
 * </pre>
 * {@code keys:show-public} prints the standard-alphabet base64 of the SPKI DER,
 * which is what {@link #SIGNING_SPKI_BASE64} holds. The content key is unpadded
 * base64url of 32 bytes.
 *
 * <h2>Rotating the signing key</h2>
 * Licenses already downloaded name the key that signed them, so a rotation
 * cannot be a swap. The sequence is:
 * <ol>
 *   <li>Generate the new key in the portal and start issuing with it.</li>
 *   <li>Ship a mod build that trusts <b>both</b> ids - add a second
 *       {@code trustSigningKey} call in {@link #trustAll}.</li>
 *   <li>Wait for every outstanding license signed by the old key to expire.</li>
 *   <li>Drop the old key in a later release.</li>
 * </ol>
 * Skipping step 2 turns every license in the field into
 * {@link LicenseStatus#UNKNOWN_SIGNING_KEY} the moment operators update.
 */
public final class EmbeddedKeys {

    /** Current production signing key id, as it appears in the envelope. */
    public static final String SIGNING_KEY_ID = "mystic-signing-2026-01";

    /**
     * SPKI DER of the production Ed25519 public key, standard base64.
     * Source: {@code secrets/mystic-signing-2026-01.pub.pem} in the portal.
     */
    public static final String SIGNING_SPKI_BASE64 =
            "MCowBQYDK2VwAyEACvpHzlBlwaA+YZw5Yv1AtxdE8gs9k63vdTqcv001Rw0=";

    /** Current production content key id, as it appears in the envelope. */
    public static final String CONTENT_KEY_ID = "mystic-license-content-v1";

    /** 32-byte AES-256 content key, unpadded base64url. Not a secret - see above. */
    public static final String CONTENT_KEY_B64URL =
            "5bciU8RIzT9otRztfZNiMpiEtpltc-uT6qQytPMSwRI";

    private EmbeddedKeys() {
    }

    /**
     * Register every key this build trusts. During a rotation, add the second
     * {@code trustSigningKey} call here and nowhere else.
     */
    public static McLicenseVerifier.Builder trustAll(McLicenseVerifier.Builder builder) {
        return builder
                .trustSigningKey(SIGNING_KEY_ID, SIGNING_SPKI_BASE64)
                .addContentKey(CONTENT_KEY_ID, CONTENT_KEY_B64URL);
    }
}
