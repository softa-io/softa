package io.softa.framework.web.signature.support;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import io.softa.framework.base.exception.SignatureException;

/**
 * Builds the canonical byte string that signer and verifier both feed into Ed25519.
 * <p>
 * Format (each segment terminated by {@code \n}):
 * <pre>
 * v1
 * {timestamp_ms}
 * {nonce}
 * {METHOD}
 * {path?query}
 * {sha256_hex(body)}
 * </pre>
 * <p>
 * The leading version tag lets future protocol changes coexist with v1 signers
 * during rollout. The body hash (rather than the body itself) keeps the
 * canonical string bounded regardless of payload size.
 */
public final class CanonicalRequest {

    public static final String VERSION = "v1";
    private static final String SHA_256 = "SHA-256";
    private static final byte[] EMPTY_BODY_HASH = hexHash(new byte[0]);

    private CanonicalRequest() {}

    /**
     * Build the canonical byte string to be signed / verified.
     *
     * @param method      HTTP method; case-normalized to uppercase
     * @param uri         target URI — only path + query participate, never scheme/host
     * @param body        raw request body bytes (null is treated as empty)
     * @param timestampMs value of the {@code X-Softa-Signature-Timestamp} header
     * @param nonce       value of the {@code X-Softa-Signature-Nonce} header
     */
    public static byte[] build(String method, URI uri, byte[] body, long timestampMs, String nonce) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(VERSION).append('\n');
        sb.append(timestampMs).append('\n');
        sb.append(nonce).append('\n');
        sb.append(method.toUpperCase()).append('\n');
        sb.append(pathAndQuery(uri)).append('\n');
        sb.append(new String(body == null || body.length == 0 ? EMPTY_BODY_HASH : hexHash(body), StandardCharsets.US_ASCII));
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String pathAndQuery(URI uri) {
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        String query = uri.getRawQuery();
        return query == null ? path : path + "?" + query;
    }

    private static byte[] hexHash(byte[] body) {
        try {
            byte[] digest = MessageDigest.getInstance(SHA_256).digest(body);
            return toHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new SignatureException("SHA-256 unavailable — broken JRE", e);
        }
    }

    private static byte[] toHex(byte[] bytes) {
        byte[] out = new byte[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xff;
            out[i * 2] = (byte) Character.forDigit(v >>> 4, 16);
            out[i * 2 + 1] = (byte) Character.forDigit(v & 0x0f, 16);
        }
        return out;
    }
}
