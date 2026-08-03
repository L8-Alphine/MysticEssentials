package com.mysticlicensing.license;

/**
 * Supplies the AES-256 content key for a given {@code encryption_key_id}.
 *
 * <p>This exists as an interface rather than a plain map for one reason: it
 * makes "the content key is never requested until the signature has verified"
 * an observable property. A test can hand the verifier a source that fails the
 * test if it is ever called, and prove the ordering rather than asserting it in
 * a comment.
 *
 * <p>It also leaves room for a build that fetches the key from somewhere other
 * than a constant, without the verifier caring.
 */
@FunctionalInterface
public interface ContentKeySource {

    /**
     * @return the 32 key bytes, or null when this build carries no such key
     */
    byte[] keyFor(String encryptionKeyId);
}
