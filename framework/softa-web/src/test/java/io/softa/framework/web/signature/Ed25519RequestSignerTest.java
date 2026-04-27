package io.softa.framework.web.signature;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.client.MockClientHttpRequest;

import io.softa.framework.web.signature.support.CanonicalRequest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the framework's sign-and-attach helper produces headers that a
 * textbook Ed25519 verifier accepts, and that two consecutive calls pick up fresh timestamp
 * and nonce values so scenario-owned retries never double-book a signature.
 */
class Ed25519RequestSignerTest {

    @Test
    void attachedHeadersVerifyAgainstStandaloneEd25519() throws Exception {
        KeyPair kp = Ed25519Keys.generate();
        MockClientHttpRequest request = new MockClientHttpRequest(
                HttpMethod.POST, URI.create("https://runtime.example/upgrade/upgradeMetadata"));
        byte[] body = "{\"ping\":1}".getBytes(StandardCharsets.UTF_8);

        Ed25519Signer.attachHttpRequest(request, body, kp.getPrivate());

        String signature = request.getHeaders().getFirst(SignatureConstant.SIGNATURE);
        String timestamp = request.getHeaders().getFirst(SignatureConstant.TIMESTAMP);
        String nonce = request.getHeaders().getFirst(SignatureConstant.NONCE);
        assertNotNull(signature);
        assertNotNull(timestamp);
        assertNotNull(nonce);

        byte[] canonical = CanonicalRequest.build("POST", request.getURI(), body,
                Long.parseLong(timestamp), nonce);
        assertTrue(verify(kp.getPublic(), canonical, Base64.getUrlDecoder().decode(signature)));
    }

    @Test
    void consecutiveAttachesRebuildFreshTimestampAndNonce() throws Exception {
        KeyPair kp = Ed25519Keys.generate();
        MockClientHttpRequest request = new MockClientHttpRequest(
                HttpMethod.POST, URI.create("https://runtime.example/upgrade/upgradeMetadata"));
        byte[] body = new byte[]{1, 2, 3};

        Ed25519Signer.attachHttpRequest(request, body, kp.getPrivate());
        String firstNonce = request.getHeaders().getFirst(SignatureConstant.NONCE);
        String firstTimestamp = request.getHeaders().getFirst(SignatureConstant.TIMESTAMP);

        Thread.sleep(5);
        Ed25519Signer.attachHttpRequest(request, body, kp.getPrivate());
        String secondNonce = request.getHeaders().getFirst(SignatureConstant.NONCE);
        String secondTimestamp = request.getHeaders().getFirst(SignatureConstant.TIMESTAMP);

        assertNotEquals(firstNonce, secondNonce);
        assertNotEquals(firstTimestamp, secondTimestamp);
    }

    private static boolean verify(PublicKey publicKey, byte[] canonical, byte[] signature) throws Exception {
        Signature verifier = Signature.getInstance(Ed25519Keys.ALGORITHM);
        verifier.initVerify(publicKey);
        verifier.update(canonical);
        return verifier.verify(signature);
    }
}
