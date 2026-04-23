package io.softa.framework.web.signature;

import java.time.Duration;

/**
 * HTTP header names used by the Ed25519 request signing protocol.
 * <p>
 * The three signature headers are the wire-level protocol between signer and
 * verifier. The protocol assumes a 1:1 trust relationship: the verifier holds
 * exactly one public key, so the wire format does not carry a key identifier.
 */
public interface SignatureConstant {

    /** Base64url-encoded Ed25519 signature of the canonical request. */
    String SIGNATURE = "X-Softa-Signature";

    /** Unix epoch milliseconds when the signer built the canonical string. */
    String TIMESTAMP = "X-Softa-Signature-Timestamp";

    /** Per-request random value for replay protection. */
    String NONCE = "X-Softa-Signature-Nonce";

    /**
     * One-time callback token echoed by the runtime on the async upgrade callback.
     * The studio webhook verifies the token matches the pending deployment before
     * applying the callback — distinct from the Ed25519 signature headers because
     * the runtime → studio direction is authenticated by the one-shot token alone,
     * not by a key pair.
     */
    String CALLBACK_TOKEN = "X-Softa-Callback-Token";

    /**
     * Maximum allowed difference between the timestamp header and the verifier's
     * clock. Requests outside this window are rejected as either stale or
     * future-dated regardless of signature correctness.
     */
    Duration CLOCK_SKEW_TOLERANCE = Duration.ofSeconds(30);
}
