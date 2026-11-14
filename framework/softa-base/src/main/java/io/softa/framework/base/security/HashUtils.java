package io.softa.framework.base.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * SHA-256 and HMAC-SHA256: the digest behind stored token hashes, checksums and fingerprints, and the MAC
 * behind signed keys. Hex output is lowercase.
 *
 * <p>Not for passwords. Both are fast by design, which is exactly what a password hash must not be.
 */
public final class HashUtils {

    private static final String SHA_256 = "SHA-256";
    private static final String HMAC_SHA_256 = "HmacSHA256";
    private static final HexFormat HEX = HexFormat.of();

    private HashUtils() {
    }

    /** The SHA-256 of {@code data}: 32 bytes. */
    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance(SHA_256).digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a mandatory JCA algorithm.", e);
        }
    }

    /** The SHA-256 of {@code data} as 64 lowercase hex characters. */
    public static String sha256Hex(byte[] data) {
        return HEX.formatHex(sha256(data));
    }

    /** The SHA-256 of the UTF-8 bytes of {@code text} as 64 lowercase hex characters. */
    public static String sha256Hex(String text) {
        return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The HMAC-SHA256 of {@code data} under {@code key}: 32 bytes. Check a presented MAC with
     * {@link MessageDigest#isEqual}, which takes the same time wherever the bytes differ.
     *
     * @param key  the secret; must not be empty
     * @param data what is signed
     */
    public static byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            // A Mac is not thread-safe; a fresh one per call costs microseconds.
            Mac mac = Mac.getInstance(HMAC_SHA_256);
            mac.init(new SecretKeySpec(key, HMAC_SHA_256));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 failed.", e);
        }
    }
}
