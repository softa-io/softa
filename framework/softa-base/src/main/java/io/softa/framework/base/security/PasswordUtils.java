package io.softa.framework.base.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import io.softa.framework.base.exception.SystemException;

/**
 * Password hashing for stored credentials: PBKDF2-HMAC-SHA256, kept as one self-describing string.
 *
 * <pre>pbkdf2-sha256$600000$&lt;salt&gt;$&lt;hash&gt;</pre>
 *
 * <p>Salt (16 random bytes) and hash (32 bytes) are Base64 without padding — 87 characters at the
 * current settings. The iteration count and the salt travel inside the string, so a hash verifies
 * on its own, and raising {@link #ITERATIONS} later strands nothing: a hash written at the old
 * count still verifies, {@link #needsRehash} reports it, and the caller stores the password again
 * at the new count the next time its owner types it.
 *
 * <p><b>Why slow.</b> A stored hash only matters on the day the table leaks. The scheme before this
 * one — one round of SHA-512 over salt + password — let a single graphics card try billions of
 * guesses a second against it; 600,000 rounds of PBKDF2 (OWASP's figure) bring that down to the
 * tens of thousands, at about 70 ms of one core per check. Argon2id resists that hardware better,
 * but needs a library and some 19 MiB per hash; PBKDF2 ships with the JDK.
 *
 * <p><b>The old scheme still verifies</b>: 128 hex characters, with the salt in a column of its own
 * ({@code legacySalt} below). Nothing can rewrite those rows in bulk — the password is not known
 * until its owner types it — so {@link #needsRehash} reports them and the caller stores each one
 * again at its next successful check. Its bytes are UTF-8: it was written with the platform charset,
 * which is UTF-8 on every JDK since 18.
 *
 * <p><b>Trimmed</b>, in both schemes. Surrounding spaces have never been part of a password here, and
 * every client sends what was typed as it was typed. Hashing the untrimmed text would turn a stray
 * space — typed once at the sign-in that re-stores an old hash, or pasted along with a new password
 * — into part of the password from then on.
 */
public final class PasswordUtils {

    /**
     * Iterations for new hashes. A code constant rather than configuration: each stored hash records
     * its own count, so changing this only decides what the next hash is written with.
     */
    static final int ITERATIONS = 600_000;

    private static final String PREFIX = "pbkdf2-sha256";
    private static final String SEPARATOR = "$";
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;

    /** The old scheme's hash: SHA-512 in hex, 512 bits = 128 characters. */
    private static final int LEGACY_HEX_LENGTH = 128;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder BASE64 = Base64.getEncoder().withoutPadding();

    /** Salt of the hash spent when there is no current hash to check against — see {@link #matches}. */
    private static final byte[] PLACEHOLDER_SALT = randomBytes(SALT_BYTES);

    private PasswordUtils() {
    }

    /**
     * Hash a password for storage, with a fresh random salt.
     *
     * @param rawPassword the password as typed; surrounding spaces are not part of it
     * @return {@code pbkdf2-sha256$<iterations>$<salt>$<hash>}
     */
    public static String hash(String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new IllegalArgumentException("A blank password cannot be hashed.");
        }
        byte[] salt = randomBytes(SALT_BYTES);
        byte[] hash = pbkdf2(rawPassword.trim(), salt, ITERATIONS);
        return String.join(SEPARATOR, PREFIX, String.valueOf(ITERATIONS),
                BASE64.encodeToString(salt), BASE64.encodeToString(hash));
    }

    /**
     * Whether a password matches a stored hash of either scheme, compared in constant time.
     *
     * <p>A blank password, a blank stored hash, and a stored value in no known format never match:
     * no stored password means password sign-in is unavailable, never that any password fits. They
     * still take as long as a real check. Unless the stored hash is a current one, a hash at the
     * current settings is spent before answering, so a wrong password costs the same whether its
     * owner has a current hash, an old one, none at all — or does not exist, which the caller says by
     * passing {@code null}. Only a blank password is refused at once, and it is refused at once for
     * everyone.
     *
     * @param rawPassword the password as typed
     * @param storedHash  the stored hash of either scheme; may be blank
     * @param legacySalt  the old scheme's salt column; not read for a current hash
     */
    public static boolean matches(String rawPassword, String storedHash, String legacySalt) {
        if (rawPassword == null || rawPassword.isBlank()) {
            return false;
        }
        String password = rawPassword.trim();
        StoredHash stored = StoredHash.parse(storedHash);
        if (stored != null) {
            return MessageDigest.isEqual(pbkdf2(password, stored.salt(), stored.iterations()), stored.hash());
        }
        pbkdf2(password, PLACEHOLDER_SALT, ITERATIONS);
        return isLegacyHash(storedHash) && legacySalt != null
                && MessageDigest.isEqual(legacyDigest(legacySalt, password), HexFormat.of().parseHex(storedHash));
    }

    /**
     * Whether a stored hash should be written again in the current scheme: it is of the old scheme,
     * or of this one at another iteration count. Asked after a successful {@link #matches}, while the
     * password is at hand.
     */
    public static boolean needsRehash(String storedHash) {
        StoredHash stored = StoredHash.parse(storedHash);
        return stored == null || stored.iterations() != ITERATIONS;
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, HASH_BYTES * 8);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new SystemException(ALGORITHM + " is unavailable.", e);
        } finally {
            spec.clearPassword();
        }
    }

    private static boolean isLegacyHash(String storedHash) {
        return storedHash != null && storedHash.length() == LEGACY_HEX_LENGTH
                && storedHash.chars().allMatch(HexFormat::isHexDigit);
    }

    /** The old scheme: one round of SHA-512 over salt + trimmed password. */
    private static byte[] legacyDigest(String salt, String password) {
        try {
            return MessageDigest.getInstance("SHA-512").digest((salt + password).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new SystemException("SHA-512 is unavailable.", e);
        }
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    /**
     * A stored hash of the current scheme, read back; {@code null} for anything else — the old scheme,
     * blank, or a value no version of this class wrote. A hash of another length is refused rather
     * than derived to that length: the length is part of the scheme, not of the stored row.
     */
    private record StoredHash(int iterations, byte[] salt, byte[] hash) {

        static StoredHash parse(String storedHash) {
            if (storedHash == null || !storedHash.startsWith(PREFIX + SEPARATOR)) {
                return null;
            }
            String[] parts = storedHash.split("\\$", -1);
            if (parts.length != 4) {
                return null;
            }
            try {
                int iterations = Integer.parseInt(parts[1]);
                byte[] salt = Base64.getDecoder().decode(parts[2]);
                byte[] hash = Base64.getDecoder().decode(parts[3]);
                if (iterations < 1 || salt.length == 0 || hash.length != HASH_BYTES) {
                    return null;
                }
                return new StoredHash(iterations, salt, hash);
            } catch (IllegalArgumentException e) {
                // A count that is not a number, or Base64 that does not decode.
                return null;
            }
        }
    }
}
