package io.softa.starter.user.util;

import java.io.InputStream;
import java.net.URI;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import io.softa.framework.base.utils.Assert;

/**
 * Tool class: Supports parsing and verification the id_token of Google and Apple
 */
public class TokenVerifierUtil {

    private static final String GOOGLE_JWKS_URL = "https://www.googleapis.com/oauth2/v3/certs";
    private static final String APPLE_JWKS_URL = "https://appleid.apple.com/auth/keys";
    private static final String GOOGLE_ISSUER = "https://accounts.google.com";
    private static final String APPLE_ISSUER = "https://appleid.apple.com";

    // The JWK cache and refresh timestamp (to avoid remote fetching every time)
    private static final Map<String, JWKSet> JWK_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Long> JWK_CACHE_TIME = new ConcurrentHashMap<>();
    private static final long CACHE_EXPIRE = 60 * 60 * 1000;     // 1 hour

    /**
     * Verify Google id_token
     *
     * @param idTokenString    Google id_token
     * @param expectedClientId Configured Google clientId
     * @return JWTClaimsSet Verified claim information
     * @throws Exception Verification failure throws
     */
    public static JWTClaimsSet verifyGoogleIdToken(String idTokenString, String expectedClientId) throws Exception {
        Assert.notBlank(idTokenString, "Google idToken is required");
        Assert.notBlank(expectedClientId, "Google ClientId is required");
        return verifyIdToken(idTokenString, expectedClientId, GOOGLE_ISSUER, GOOGLE_JWKS_URL);
    }

    /**
     * Verify Apple id_token
     *
     * @param idTokenString    Apple id_token
     * @param expectedClientId Configured Apple clientId (Service ID)
     * @return JWTClaimsSet Verified claim information
     * @throws Exception Verification failure throws
     */
    public static JWTClaimsSet verifyAppleIdToken(String idTokenString, String expectedClientId) throws Exception {
        Assert.notBlank(idTokenString, "Apple idToken is required");
        Assert.notBlank(expectedClientId, "Apple ClientId is required");
        return verifyIdToken(idTokenString, expectedClientId, APPLE_ISSUER, APPLE_JWKS_URL);
    }

    /**
     * Verify id_token common method
     */
    private static JWTClaimsSet verifyIdToken(String idTokenString, String expectedClientId, String expectedIssuer,
            String jwksUrl) throws Exception {
        SignedJWT signedJWT = SignedJWT.parse(idTokenString);

        // Get JWKSet
        JWKSet jwkSet = getJwkSet(jwksUrl);
        JWK jwk = jwkSet.getKeyByKeyId(signedJWT.getHeader().getKeyID());
        if (jwk == null) {
            throw new IllegalArgumentException(
                    "Unable to find matching public key for kid: " + signedJWT.getHeader().getKeyID());
        }

        // Verify signature
        JWSVerifier verifier = new RSASSAVerifier((RSAPublicKey) jwk.toRSAKey().toPublicKey());
        if (!signedJWT.verify(verifier)) {
            throw new IllegalArgumentException("Invalid ID token signature");
        }

        JWTClaimsSet claims = signedJWT.getJWTClaimsSet();

        // Verify audience (maybe string or array for Google/Apple)
        if (!claims.getAudience().contains(expectedClientId)) {
            throw new IllegalArgumentException("Invalid audience, expected: " + expectedClientId);
        }

        // Verify issuer
        String iss = claims.getIssuer();
        if (GOOGLE_ISSUER.equals(expectedIssuer)) {
            if (!GOOGLE_ISSUER.equals(iss) && !"accounts.google.com".equals(iss)) {
                throw new IllegalArgumentException("Invalid Google issuer: " + iss);
            }
        } else {
            if (!expectedIssuer.equals(iss)) {
                throw new IllegalArgumentException("Invalid issuer: " + iss);
            }
        }

        // Verify expiration
        Date exp = claims.getExpirationTime();
        if (exp == null || exp.before(new Date())) {
            throw new IllegalArgumentException("ID token expired");
        }

        return claims;
    }

    /**
     * Get JWKSet with local cache
     */
    private static JWKSet getJwkSet(String jwksUrl) throws Exception {
        long now = System.currentTimeMillis();
        Long lastFetch = JWK_CACHE_TIME.get(jwksUrl);
        if (JWK_CACHE.containsKey(jwksUrl) && lastFetch != null && (now - lastFetch < CACHE_EXPIRE)) {
            return JWK_CACHE.get(jwksUrl);
        }
        try (InputStream is = URI.create(jwksUrl).toURL().openStream()) {
            JWKSet jwkSet = JWKSet.load(is);
            JWK_CACHE.put(jwksUrl, jwkSet);
            JWK_CACHE_TIME.put(jwksUrl, now);
            return jwkSet;
        }
    }
}