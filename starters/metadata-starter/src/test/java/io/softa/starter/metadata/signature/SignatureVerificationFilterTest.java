package io.softa.starter.metadata.signature;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import io.softa.framework.web.signature.Ed25519Keys;
import io.softa.framework.web.signature.SignatureConstant;
import io.softa.framework.web.signature.support.CanonicalRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Server-side filter tests — proves the filter rejects stale, tampered,
 * foreign-key-signed, and header-less requests while admitting well-formed
 * signed ones. Replay defense is an application-layer concern (business
 * idempotency keys), not this filter's responsibility.
 * <p>
 * The filter is now path-scoped (registered against {@code /upgrade/*}) and
 * unconditionally verifies every request that reaches it — there is no
 * per-handler opt-out, so these tests no longer need a stub handler mapping.
 */
class SignatureVerificationFilterTest {

    private KeyPair keyPair;
    private SignatureVerificationFilter filter;

    @BeforeEach
    void setUp() {
        keyPair = Ed25519Keys.generate();
        filter = new SignatureVerificationFilter(keyPair.getPublic());
    }

    @Test
    void wellSignedRequestReachesDownstreamHandler() throws Exception {
        byte[] body = "{\"ping\":1}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = signedRequest(body, System.currentTimeMillis(), UUID.randomUUID().toString());
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertTrue(chain.getRequest() != null, "downstream handler must have been invoked");
    }

    @Test
    void staleTimestampIsRejected() throws Exception {
        byte[] body = new byte[0];
        long staleTs = System.currentTimeMillis() - Duration.ofHours(1).toMillis();
        MockHttpServletRequest request = signedRequest(body, staleTs, UUID.randomUUID().toString());
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(HttpStatus.UNAUTHORIZED.value(), response.getStatus());
    }

    @Test
    void signatureFromUntrustedKeyIsRejected() throws Exception {
        // Sign the canonical request with a different keypair than the verifier trusts.
        // Filter must reject regardless of how the request is otherwise well-formed.
        KeyPair foreign = Ed25519Keys.generate();
        byte[] body = new byte[0];
        long ts = System.currentTimeMillis();
        String nonce = UUID.randomUUID().toString();

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/upgrade/upgradeMetadata");
        request.setServerName("runtime.example");
        request.setScheme("https");
        request.setServerPort(443);
        request.setContent(body);
        byte[] canonical = CanonicalRequest.build("POST",
                java.net.URI.create("https://runtime.example/upgrade/upgradeMetadata"), body, ts, nonce);
        request.addHeader(SignatureConstant.TIMESTAMP, Long.toString(ts));
        request.addHeader(SignatureConstant.NONCE, nonce);
        request.addHeader(SignatureConstant.SIGNATURE, sign(foreign.getPrivate(), canonical));

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(HttpStatus.UNAUTHORIZED.value(), response.getStatus());
    }

    @Test
    void tamperedBodyIsRejected() throws Exception {
        byte[] signedBody = "{\"amount\":1}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = signedRequest(signedBody, System.currentTimeMillis(), UUID.randomUUID().toString());
        // Replace the body AFTER signing — the filter will hash the new bytes and mismatch.
        request.setContent("{\"amount\":9999}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(HttpStatus.UNAUTHORIZED.value(), response.getStatus());
    }

    @Test
    void requestMissingSignatureHeadersIsRejected() throws Exception {
        // Path-scoped filter has no pass-through: a request that lands on the
        // signed prefix without headers must be rejected, not silently forwarded.
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/upgrade/upgradeMetadata");
        request.setServerName("runtime.example");
        request.setScheme("https");
        request.setServerPort(443);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(HttpStatus.UNAUTHORIZED.value(), response.getStatus());
    }

    // ------------- helpers -------------

    private MockHttpServletRequest signedRequest(byte[] body, long timestamp, String nonce) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/upgrade/upgradeMetadata");
        request.setServerName("runtime.example");
        request.setScheme("https");
        request.setServerPort(443);
        request.setContent(body);

        byte[] canonical = CanonicalRequest.build("POST",
                java.net.URI.create("https://runtime.example/upgrade/upgradeMetadata"),
                body, timestamp, nonce);
        String signature = sign(keyPair.getPrivate(), canonical);

        request.addHeader(SignatureConstant.TIMESTAMP, Long.toString(timestamp));
        request.addHeader(SignatureConstant.NONCE, nonce);
        request.addHeader(SignatureConstant.SIGNATURE, signature);
        return request;
    }

    private static String sign(PrivateKey key, byte[] canonical) throws Exception {
        Signature s = Signature.getInstance(Ed25519Keys.ALGORITHM);
        s.initSign(key);
        s.update(canonical);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.sign());
    }
}
