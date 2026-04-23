package io.softa.framework.web.signature;

import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;

import io.softa.framework.base.exception.SignatureException;
import io.softa.framework.web.signature.support.CanonicalRequest;

/**
 * Thin wrapper over the JDK Ed25519 provider exposing sign / verify as pure
 * byte-array operations.
 * <p>
 * Separate from {@link Ed25519Keys} (which handles key generation and wire
 * encoding) so non-HTTP callers — e.g. signing a release artifact, a message
 * queue payload, or a config bundle — can reuse the primitives without
 * dragging in the HTTP request signing machinery (canonical string, headers,
 * clock skew, filter/interceptor).
 * <p>
 * Callers that want text payloads should encode explicitly, e.g.
 * {@code sign(key, payload.getBytes(StandardCharsets.UTF_8))} — using
 * {@code String} here would silently corrupt binary input.
 */
public final class Ed25519Signer {

    private Ed25519Signer() {}

    /** Sign the raw bytes with an Ed25519 private key. */
    public static byte[] sign(PrivateKey privateKey, byte[] payload) {
        try {
            Signature signer = Signature.getInstance(Ed25519Keys.ALGORITHM);
            signer.initSign(privateKey);
            signer.update(payload);
            return signer.sign();
        } catch (GeneralSecurityException e) {
            throw new SignatureException("Ed25519 signing failed", e);
        }
    }

    /** Verify an Ed25519 signature over the raw bytes. */
    public static boolean verify(PublicKey publicKey, byte[] payload, byte[] signature) {
        try {
            Signature verifier = Signature.getInstance(Ed25519Keys.ALGORITHM);
            verifier.initVerify(publicKey);
            verifier.update(payload);
            return verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            throw new SignatureException("Ed25519 verification error", e);
        }
    }

    /**
     * Sign an outbound {@link HttpRequest} with an Ed25519 private key and attach
     * the four wire headers ({@link SignatureConstant#TIMESTAMP},
     * {@link SignatureConstant#NONCE}, {@link SignatureConstant#SIGNATURE}).
     * <p>
     * The framework intentionally stops at the protocol boundary: callers choose
     * how they obtain the private key (yml, DB, KMS, per-env lookup) and how they
     * wire signing into their {@code RestClient} — typically a one-line
     * {@code ClientHttpRequestInterceptor} that pulls its key and delegates here.
     * <p>
     * A fresh timestamp + nonce is generated on every call, so a scenario
     * interceptor that re-enters the chain on retry gets a new signature and does
     * not trip the verifier's clock-skew window.
     */
    public static void attachHttpRequest(HttpRequest request, byte[] body, PrivateKey privateKey) {
        long timestamp = System.currentTimeMillis();
        String nonce = UUID.randomUUID().toString().replace("-", "");
        byte[] canonical = CanonicalRequest.build(
                request.getMethod().name(), request.getURI(), body, timestamp, nonce);
        byte[] signature = sign(privateKey, canonical);

        HttpHeaders headers = request.getHeaders();
        headers.set(SignatureConstant.TIMESTAMP, Long.toString(timestamp));
        headers.set(SignatureConstant.NONCE, nonce);
        headers.set(SignatureConstant.SIGNATURE,
                Base64.getUrlEncoder().withoutPadding().encodeToString(signature));
    }
}
