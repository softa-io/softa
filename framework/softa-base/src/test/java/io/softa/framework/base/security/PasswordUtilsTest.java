package io.softa.framework.base.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The stored-password scheme: what a hash looks like, what verifies against it, and that the
 * hashes of earlier releases still do.
 *
 * <p>The fixed hashes below come from other implementations, not from {@link PasswordUtils} itself:
 * the PBKDF2 ones from Python's {@code hashlib.pbkdf2_hmac}, the SHA-512 ones from {@code shasum}. A
 * round trip through one implementation would stay green while the scheme drifted — and every
 * stored password with it.
 */
class PasswordUtilsTest {

    private static final String PASSWORD = "Str0ngPass";
    private static final String NON_ASCII_PASSWORD = "密码Str0ng1";

    /** {@code pbkdf2_hmac('sha256', b'Str0ngPass', b'0123456789abcdef', 600000, 32)}, in this format. */
    private static final String CURRENT_HASH =
            "pbkdf2-sha256$600000$MDEyMzQ1Njc4OWFiY2RlZg$i7N/IgzeagSrLd/s3lSKvOZaIAPpfvlVbTYxCnGeNas";

    /** The same at 1,000 iterations — a hash written before the count was raised. */
    private static final String FEWER_ITERATIONS_HASH =
            "pbkdf2-sha256$1000$MDEyMzQ1Njc4OWFiY2RlZg$A/uwfdvEyDFftoSl5Epe8XQLfbK/1lIV8lOhhAVwIcY";

    /** {@code 密码Str0ng1} at 1,000 iterations: the password reaches PBKDF2 as UTF-8. */
    private static final String NON_ASCII_HASH =
            "pbkdf2-sha256$1000$MDEyMzQ1Njc4OWFiY2RlZg$5lC13lrcvU+2wdC2nT6jyzMSYb3+DiF5IyQYbjplFUY";

    /** The old scheme as earlier releases stored it: SHA-512 over salt + password, in hex. */
    private static final String LEGACY_SALT = "0123456789abcdef0123456789abcdef";
    private static final String LEGACY_HASH = "11e2c4325ec46e77f568ed57613b0f319e8e6f853bf6518db74c91d5a6ab0a4b"
            + "c9b2069f527f37b552612943c9a37c236826761f0151944bd2b52f12e06e3964";
    private static final String LEGACY_NON_ASCII_HASH = "8d93f2a81c9eeb15f1332777de3075ecda070ef4ced44c91a70ba71085673a42"
            + "8c6644aa99783f2342862d58d3d2378b043554d16b666a9faafcb03095195bb0";

    @Test
    void aNewHash_isTheDocumentedFormat_andFitsItsColumn() {
        String hash = PasswordUtils.hash(PASSWORD);

        assertThat(hash).matches("pbkdf2-sha256\\$600000\\$[A-Za-z0-9+/]{22}\\$[A-Za-z0-9+/]{43}");
        // UserIdentity.password is 256 wide. The 64-character type default would truncate it, and
        // no credential could be written at all.
        assertThat(hash).hasSize(87);
    }

    @Test
    void aNewHash_verifiesItsPassword_andNoOther() {
        String hash = PasswordUtils.hash(PASSWORD);

        assertThat(PasswordUtils.matches(PASSWORD, hash, null)).isTrue();
        assertThat(PasswordUtils.matches("Str0ngPasS", hash, null)).isFalse();
        assertThat(PasswordUtils.needsRehash(hash)).isFalse();
    }

    @Test
    void twoHashesOfOnePassword_haveTheirOwnSalts() {
        assertThat(PasswordUtils.hash(PASSWORD)).isNotEqualTo(PasswordUtils.hash(PASSWORD));
    }

    @Test
    void aHashWrittenElsewhere_inThisScheme_verifies() {
        assertThat(PasswordUtils.matches(PASSWORD, CURRENT_HASH, null)).isTrue();
        assertThat(PasswordUtils.needsRehash(CURRENT_HASH)).isFalse();
        assertThat(PasswordUtils.matches(NON_ASCII_PASSWORD, NON_ASCII_HASH, null)).isTrue();
    }

    @Test
    void aHashAtAnotherIterationCount_stillVerifies_andAsksToBeWrittenAgain() {
        assertThat(PasswordUtils.matches(PASSWORD, FEWER_ITERATIONS_HASH, null)).isTrue();
        assertThat(PasswordUtils.matches("Str0ngPasS", FEWER_ITERATIONS_HASH, null)).isFalse();
        assertThat(PasswordUtils.needsRehash(FEWER_ITERATIONS_HASH)).isTrue();
    }

    @Test
    void anOldSchemeHash_stillVerifiesWithItsSalt_andAsksToBeWrittenAgain() {
        assertThat(PasswordUtils.matches(PASSWORD, LEGACY_HASH, LEGACY_SALT)).isTrue();
        assertThat(PasswordUtils.matches(NON_ASCII_PASSWORD, LEGACY_NON_ASCII_HASH, LEGACY_SALT)).isTrue();
        assertThat(PasswordUtils.needsRehash(LEGACY_HASH)).isTrue();

        assertThat(PasswordUtils.matches("Str0ngPasS", LEGACY_HASH, LEGACY_SALT)).isFalse();
        assertThat(PasswordUtils.matches(PASSWORD, LEGACY_HASH, null)).isFalse();
        assertThat(PasswordUtils.matches(PASSWORD, LEGACY_HASH, "0123456789abcdef0123456789abcdee")).isFalse();
    }

    @Test
    void surroundingSpacesAreNotPartOfThePassword_inEitherScheme() {
        String hash = PasswordUtils.hash("  " + PASSWORD + " ");

        assertThat(PasswordUtils.matches(PASSWORD, hash, null)).isTrue();
        assertThat(PasswordUtils.matches(" " + PASSWORD + "\t", hash, null)).isTrue();
        assertThat(PasswordUtils.matches(" " + PASSWORD + " ", LEGACY_HASH, LEGACY_SALT)).isTrue();
        // Spaces inside it are.
        assertThat(PasswordUtils.matches("Str0ng Pass", hash, null)).isFalse();
    }

    @Test
    void aBlankPassword_neverMatches_andIsNeverHashed() {
        String hash = PasswordUtils.hash(PASSWORD);

        assertThat(PasswordUtils.matches(null, hash, null)).isFalse();
        assertThat(PasswordUtils.matches("", hash, null)).isFalse();
        assertThat(PasswordUtils.matches("   ", hash, null)).isFalse();
        assertThat(PasswordUtils.matches("", LEGACY_HASH, LEGACY_SALT)).isFalse();
        assertThatThrownBy(() -> PasswordUtils.hash(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PasswordUtils.hash(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void noStoredHash_orAnUnreadableOne_neverMatches() {
        // No stored password means password sign-in is unavailable — never that any password fits.
        assertThat(PasswordUtils.matches(PASSWORD, null, null)).isFalse();
        assertThat(PasswordUtils.matches(PASSWORD, "", LEGACY_SALT)).isFalse();
        // Readable as neither scheme: answered "no", not thrown.
        String salt = "MDEyMzQ1Njc4OWFiY2RlZg";
        String hash = "A/uwfdvEyDFftoSl5Epe8XQLfbK/1lIV8lOhhAVwIcY";
        assertThat(PasswordUtils.matches(PASSWORD, "pbkdf2-sha256$many$" + salt + "$" + hash, null)).isFalse();
        assertThat(PasswordUtils.matches(PASSWORD, "pbkdf2-sha256$0$" + salt + "$" + hash, null)).isFalse();
        assertThat(PasswordUtils.matches(PASSWORD, "pbkdf2-sha256$1000$$" + hash, null)).isFalse();
        assertThat(PasswordUtils.matches(PASSWORD, "pbkdf2-sha256$1000$" + salt + "$!!", null)).isFalse();
        assertThat(PasswordUtils.matches(PASSWORD, "pbkdf2-sha256$1000$" + salt + "$" + hash.substring(0, 20), null))
                .isFalse();
        assertThat(PasswordUtils.matches(PASSWORD, FEWER_ITERATIONS_HASH + "$", null)).isFalse();
        assertThat(PasswordUtils.matches(PASSWORD, "pbkdf2-sha512$1000$" + salt + "$" + hash, null)).isFalse();
        assertThat(PasswordUtils.matches(PASSWORD, LEGACY_HASH.substring(1), LEGACY_SALT)).isFalse();
    }

    @Test
    void aCheckWithNothingToCompareAgainst_takesAsLongAsARealOne() {
        // The property the login's unknown-identifier branch relies on: "no such person", "no
        // password yet" and "old-scheme hash" all spend a full hash before saying no. 600,000 rounds
        // of HMAC-SHA256 cannot finish in 10 ms on any current CPU; skipping the hash takes
        // microseconds. A lower bound, so a fast machine cannot make it flaky.
        assertThat(millisToCheck(null, null)).isGreaterThan(10);
        assertThat(millisToCheck("", null)).isGreaterThan(10);
        assertThat(millisToCheck(LEGACY_HASH, LEGACY_SALT)).isGreaterThan(10);
    }

    private static long millisToCheck(String storedHash, String legacySalt) {
        long start = System.nanoTime();
        PasswordUtils.matches("Wr0ngPassword", storedHash, legacySalt);
        return (System.nanoTime() - start) / 1_000_000;
    }
}
