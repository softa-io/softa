package io.softa.framework.web.signature;

import java.security.*;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import io.softa.framework.base.exception.SignatureException;

/**
 * Utilities for generating and transporting Ed25519 key material using
 * the JDK 25 built-in provider — no BouncyCastle or other dependency.
 * <p>
 * Wire format:
 * <ul>
 *   <li>Public key: base64-encoded X.509 SubjectPublicKeyInfo (~60 chars).</li>
 *   <li>Private key: base64-encoded PKCS#8 PrivateKeyInfo (~65 chars).</li>
 * </ul>
 * These are the standard encodings {@link java.security.Key#getEncoded()}
 * returns for Ed25519 and are what {@link KeyFactory} expects on the way back.
 */
public final class Ed25519Keys {

    public static final String ALGORITHM = "Ed25519";

    private Ed25519Keys() {}

    /** Generate a fresh Ed25519 key pair. */
    public static KeyPair generate() {
        try {
            return KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new SignatureException("JDK does not expose Ed25519 — require JDK 15+", e);
        }
    }

    /** Encode a public key as base64(X.509). */
    public static String encodePublicKey(PublicKey publicKey) {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    /** Encode a private key as base64(PKCS#8). */
    public static String encodePrivateKey(PrivateKey privateKey) {
        return Base64.getEncoder().encodeToString(privateKey.getEncoded());
    }

    /** Decode a base64(X.509) Ed25519 public key. */
    public static PublicKey decodePublicKey(String base64X509) {
        try {
            byte[] der = Base64.getDecoder().decode(base64X509);
            return KeyFactory.getInstance(ALGORITHM).generatePublic(new X509EncodedKeySpec(der));
        } catch (IllegalArgumentException | NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new SignatureException("Invalid Ed25519 public key encoding", e);
        }
    }

    /** Decode a base64(PKCS#8) Ed25519 private key. */
    public static PrivateKey decodePrivateKey(String base64Pkcs8) {
        try {
            byte[] der = Base64.getDecoder().decode(base64Pkcs8);
            return KeyFactory.getInstance(ALGORITHM).generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (IllegalArgumentException | NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new SignatureException("Invalid Ed25519 private key encoding", e);
        }
    }
}
