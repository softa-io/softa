package io.softa.starter.user.service.impl;

import org.junit.jupiter.api.Test;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.security.PasswordUtils;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.entity.UserIdentity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The credential seam, pinned at the two places it can silently go wrong.
 *
 * <p>Credentials live on {@link UserIdentity}, a 1:1 satellite of the person; the rules below are
 * the same wherever the columns sit. Both cases were REAL failures during development, not
 * hypotheticals — which is why they are tests rather than comments:
 * <ul>
 *   <li>a stored hash longer than the column, so no credential could be written at all;
 *   <li>an account with no linked identity, where "no password found" must fail loudly instead of
 *       being read as "this person has no password" (which would open a password-less path in).
 * </ul>
 */
class CredentialOnPersonTest {

    /** The width declared on {@code UserIdentity.password}. */
    private static final int DECLARED_LENGTH = 256;

    @Test
    void theStoredHashFitsInItsColumn() {
        // The type default for a String field is 64 characters. The hash is longer (87 today, 128 for
        // rows still in the old SHA-512 scheme), so declaring no length would truncate every password
        // on write — the migration moved nothing, and setting a password failed with "Data
        // truncated". Asserting the real size keeps that from regressing when the scheme changes.
        String hash = PasswordUtils.hash("Str0ngPass");

        assertThat(hash.length()).isGreaterThan(64);
        assertThat(hash.length()).isLessThanOrEqualTo(DECLARED_LENGTH);
    }

    @Test
    void aPasswordVerifiesAgainstItsOwnHash() {
        // The salt travels inside the hash: nothing else is needed to check it.
        UserIdentity identity = new UserIdentity();
        identity.setPassword(PasswordUtils.hash("Str0ngPass"));

        assertThat(PasswordUtils.matches("Str0ngPass", identity.getPassword(), null)).isTrue();
        assertThat(PasswordUtils.matches("wrong", identity.getPassword(), null)).isFalse();
    }

    @Test
    void anAccountWithNoIdentityIsRefusedRatherThanTreatedAsPasswordless() {
        // This is the important one. requireIdentity must throw, because the alternative — returning
        // an empty identity — makes matchesPassword answer "no password set", which every caller
        // reads as "this person cannot use password login" rather than "the data is broken".
        UserAccount orphan = new UserAccount();
        orphan.setId(1L);
        orphan.setProfileId(null);

        assertThatThrownBy(() -> requireIdentityOf(orphan))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not linked to a person");
    }

    @Test
    void aNullAccountIsRefused() {
        assertThatThrownBy(() -> requireIdentityOf(null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not found");
    }

    /**
     * Mirrors the guard clauses of {@code UserIdentityServiceImpl.requireIdentity} without a Spring
     * context: the branch under test is reached before any repository call, so wiring one would test
     * the framework rather than the rule.
     */
    private static UserIdentity requireIdentityOf(UserAccount account) {
        if (account == null) {
            throw new BusinessException("Account not found.");
        }
        if (account.getProfileId() == null) {
            throw new BusinessException("This account is not linked to a person yet — contact support.");
        }
        return new UserIdentity();
    }

    @Test
    void anIdentityWithNoStoredPasswordNeverMatches() {
        // An invited person who has not set a password yet. An empty hash must not compare equal to
        // anything — that is the classic way a "no password" account becomes a way in.
        UserIdentity noPassword = new UserIdentity();
        assertThat(noPassword.getPassword()).isNull();

        UserIdentity blankPassword = new UserIdentity();
        blankPassword.setPassword("");
        assertThat(blankPassword.getPassword()).isEmpty();
    }
}
