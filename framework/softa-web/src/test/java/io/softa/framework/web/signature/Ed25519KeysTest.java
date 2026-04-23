package io.softa.framework.web.signature;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import org.junit.jupiter.api.Test;

import io.softa.framework.base.exception.SignatureException;

import static org.junit.jupiter.api.Assertions.*;

class Ed25519KeysTest {

    @Test
    void generateProducesDistinctKeyPairs() {
        KeyPair a = Ed25519Keys.generate();
        KeyPair b = Ed25519Keys.generate();
        assertNotEquals(Ed25519Keys.encodePublicKey(a.getPublic()),
                Ed25519Keys.encodePublicKey(b.getPublic()));
        assertNotEquals(Ed25519Keys.encodePrivateKey(a.getPrivate()),
                Ed25519Keys.encodePrivateKey(b.getPrivate()));
    }

    @Test
    void publicKeyRoundtripsThroughBase64X509() {
        KeyPair kp = Ed25519Keys.generate();
        String encoded = Ed25519Keys.encodePublicKey(kp.getPublic());
        PublicKey decoded = Ed25519Keys.decodePublicKey(encoded);
        // The JDK EdDSA provider reports the algorithm name as either "Ed25519" (the
        // curve) or the umbrella "EdDSA" depending on the module path — both are
        // valid here and a future JDK update could flip either way.
        assertTrue(isEd25519Algorithm(decoded.getAlgorithm()), decoded.getAlgorithm());
        assertEquals(Ed25519Keys.encodePublicKey(kp.getPublic()),
                Ed25519Keys.encodePublicKey(decoded));
    }

    @Test
    void privateKeyRoundtripsThroughBase64Pkcs8() throws Exception {
        KeyPair kp = Ed25519Keys.generate();
        String encoded = Ed25519Keys.encodePrivateKey(kp.getPrivate());
        PrivateKey decoded = Ed25519Keys.decodePrivateKey(encoded);
        assertTrue(isEd25519Algorithm(decoded.getAlgorithm()), decoded.getAlgorithm());

        // Cross-check: sign with the decoded private, verify with the original public.
        byte[] message = "round-trip".getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance(Ed25519Keys.ALGORITHM);
        signer.initSign(decoded);
        signer.update(message);
        byte[] sig = signer.sign();

        Signature verifier = Signature.getInstance(Ed25519Keys.ALGORITHM);
        verifier.initVerify(kp.getPublic());
        verifier.update(message);
        assertTrue(verifier.verify(sig));
    }

    @Test
    void invalidPublicKeyRaisesSignatureException() {
        assertThrows(SignatureException.class,
                () -> Ed25519Keys.decodePublicKey("not-base64!!"));
    }

    @Test
    void invalidPrivateKeyRaisesSignatureException() {
        assertThrows(SignatureException.class,
                () -> Ed25519Keys.decodePrivateKey("not-base64!!"));
    }

    private static boolean isEd25519Algorithm(String name) {
        return "Ed25519".equals(name) || "EdDSA".equals(name);
    }
}
