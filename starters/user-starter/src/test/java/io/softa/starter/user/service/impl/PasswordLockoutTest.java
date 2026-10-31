package io.softa.starter.user.service.impl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.context.UserInfo;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.CacheService;
import io.softa.framework.orm.service.TenantInfoService;
import io.softa.starter.user.dto.AuthenticationResult;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.entity.UserIdentity;
import io.softa.starter.user.enums.AccountStatus;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserIdentityService;
import io.softa.starter.user.service.UserProfileService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import io.softa.starter.user.service.ConsultantService;

/**
 * The password lockout (PRD D5 / A8) — "linked across tenants" comes free from the credential
 * being global, so what has to be tested is the enforcement:
 *
 * <ul>
 *   <li>the lock is checked BEFORE the password, or the "locked" versus "incorrect" split confirms
 *       a correct guess to whoever is making it;</li>
 *   <li>it locks the PASSWORD only — a verification code still gets the person in, otherwise a
 *       guessing attempt against them succeeds as a denial of service;</li>
 *   <li>the lock is persisted while the counter is not, so flushing the cache cannot unlock and
 *       wrong guesses cannot be used to generate write load.</li>
 * </ul>
 */
class PasswordLockoutTest {

    private static final Long PROFILE = 7L;
    private static final Long IDENTITY = 11L;
    private static final Long ACCOUNT = 100L;
    private static final String EMAIL = "alice@acme.com";

    // ── the login side ──────────────────────────────────────────────

    private final UserAccountService accountService = mock(UserAccountService.class);
    private final UserIdentityService identityService = mock(UserIdentityService.class);
    private final UserProfileService profileService = mock(UserProfileService.class);
    private final TenantInfoService tenantInfoService = mock(TenantInfoService.class);
    private final LoginServiceImpl loginService = new LoginServiceImpl();

    // ── the identity side ───────────────────────────────────────────

    private final CacheService cacheService = mock(CacheService.class);
    private final UserIdentityServiceImpl identityImpl =
            spy(new UserIdentityServiceImpl(cacheService));

    PasswordLockoutTest() {
        ReflectionTestUtils.setField(loginService, "accountService", accountService);
        ReflectionTestUtils.setField(loginService, "history", mock(LoginHistoryRecorder.class));
        ReflectionTestUtils.setField(loginService, "cacheService", mock(io.softa.framework.orm.service.CacheService.class));
        when(accountService.listAccountIdsOf(any())).thenReturn(java.util.List.of(1L));
        ReflectionTestUtils.setField(loginService, "consultantService",
                mock(ConsultantService.class));
        ReflectionTestUtils.setField(loginService, "identityService", identityService);
        ReflectionTestUtils.setField(loginService, "profileService", profileService);
        ReflectionTestUtils.setField(loginService, "tenantInfoService", tenantInfoService);
    }

    private static UserIdentity identity(LocalDateTime lockedUntil) {
        UserIdentity identity = new UserIdentity();
        identity.setId(IDENTITY);
        identity.setProfileId(PROFILE);
        identity.setLoginEmail(EMAIL);
        identity.setPassword("hash");
        identity.setPasswordLockedUntil(lockedUntil);
        return identity;
    }

    private void givenOneCompany() {
        UserAccount account = new UserAccount();
        account.setId(ACCOUNT);
        account.setTenantId(1L);
        account.setProfileId(PROFILE);
        account.setStatus(AccountStatus.ACTIVE);
        when(accountService.listMembershipsOf(PROFILE)).thenReturn(List.of(account));
        when(profileService.getUserInfo(ACCOUNT)).thenReturn(new UserInfo());
    }

    @Test
    void aLockedPerson_isRefusedWithoutTheirPasswordBeingChecked() {
        // The order is the security property: checking the password first would answer "was my
        // guess right?" through the difference between the two messages.
        UserIdentity person = identity(LocalDateTime.now().plusMinutes(5));
        when(identityService.findByLoginIdentifier(EMAIL)).thenReturn(Optional.of(person));
        when(identityService.isPasswordLocked(person)).thenReturn(true);

        assertThatThrownBy(() -> loginService.authenticateByPassword(EMAIL, "whatever"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("locked");

        verify(identityService, never()).matchesPassword(any(), anyString());
    }

    @Test
    void aLockedPerson_canStillGetInWithAVerificationCode() {
        // What is locked is the password. Locking the person out of every route would finish the
        // attack on their behalf, so the code path must not consult the lock at all.
        UserIdentity person = identity(LocalDateTime.now().plusMinutes(5));
        VerificationCodeGuard codeGuard = mock(VerificationCodeGuard.class);
        ReflectionTestUtils.setField(loginService, "codeGuard", codeGuard);
        when(identityService.findByLoginIdentifier(EMAIL)).thenReturn(Optional.of(person));
        when(identityService.isPasswordLocked(person)).thenReturn(true);
        givenOneCompany();

        AuthenticationResult result = loginService.authenticateByCode(EMAIL, "123456");

        assertThat(result.isResolved()).isTrue();
        verify(codeGuard).verify(EMAIL, "123456");
        verify(identityService, never()).isPasswordLocked(any());
        verify(identityService, never()).recordPasswordFailure(any());
    }

    @Test
    void aWrongPassword_isCounted_andASuccessfulOneClearsTheCount() {
        UserIdentity person = identity(null);
        when(identityService.findByLoginIdentifier(EMAIL)).thenReturn(Optional.of(person));
        when(identityService.isPasswordLocked(person)).thenReturn(false);
        when(identityService.matchesPassword(person, "wrong")).thenReturn(false);
        when(identityService.matchesPassword(person, "right")).thenReturn(true);
        givenOneCompany();

        assertThatThrownBy(() -> loginService.authenticateByPassword(EMAIL, "wrong"));
        verify(identityService).recordPasswordFailure(person);

        loginService.authenticateByPassword(EMAIL, "right");
        verify(identityService).clearPasswordFailures(IDENTITY);
    }

    // ── what the person is told (PRD L3) ────────────────────────────

    private void givenWrongPasswordIsFailureNumber(UserIdentity person, long count) {
        when(identityService.findByLoginIdentifier(EMAIL)).thenReturn(Optional.of(person));
        when(identityService.isPasswordLocked(person)).thenReturn(false);
        when(identityService.matchesPassword(person, "wrong")).thenReturn(false);
        when(identityService.recordPasswordFailure(person)).thenReturn(count);
    }

    @Test
    void earlyFailures_getThePlainRefusal() {
        // No countdown for the first guesses: it would tell an attacker exactly how much room is
        // left, and only the legitimate owner gains from it — once a lock is actually near.
        givenWrongPasswordIsFailureNumber(identity(null), 6);

        assertThatThrownBy(() -> loginService.authenticateByPassword(EMAIL, "wrong"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Incorrect account or password.");
    }

    @Test
    void fromTheSeventhFailure_theRemainingAttemptsAreNamed() {
        givenWrongPasswordIsFailureNumber(identity(null), 7);

        assertThatThrownBy(() -> loginService.authenticateByPassword(EMAIL, "wrong"))
                .hasMessage("Incorrect account or password. 3 attempts left before password login locks for 30 minutes.");
    }

    @Test
    void theNinthFailure_warnsOfTheLastAttempt() {
        givenWrongPasswordIsFailureNumber(identity(null), 9);

        // Also the singular: one attempt, not "1 attempts". The count is the whole point of this
        // sentence, so reading it wrong is the one thing it must not do.
        assertThatThrownBy(() -> loginService.authenticateByPassword(EMAIL, "wrong"))
                .hasMessage("Incorrect account or password. 1 attempt left before password login locks for 30 minutes.");
    }

    @Test
    void wrongPasswordAndLock_carryTheirStableCodes() {
        givenWrongPasswordIsFailureNumber(identity(null), 8);
        assertThatThrownBy(() -> loginService.authenticateByPassword(EMAIL, "wrong"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("PASSWORD_INCORRECT");
                    assertThat(e.getErrorDetails()).containsEntry("remainingAttempts", 2L);
                });
        givenWrongPasswordIsFailureNumber(identity(null), 10);
        assertThatThrownBy(() -> loginService.authenticateByPassword(EMAIL, "wrong"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("PASSWORD_LOCKED"));
    }

    @Test
    void theTenthFailure_isAnsweredWithTheLock() {
        // The guess that locks says so itself, instead of a plain "incorrect" followed by an
        // unexplained "locked" on the next try.
        givenWrongPasswordIsFailureNumber(identity(null), 10);

        assertThatThrownBy(() -> loginService.authenticateByPassword(EMAIL, "wrong"))
                .hasMessageContaining("Too many failed attempts")
                .hasMessageContaining("locked for 30 minutes");
    }

    @Test
    void belowTheThreshold_nothingIsWritten_andTheCountIsReported() {
        // Wrong guesses must not be a way to generate row writes on demand. The count still comes
        // back, because the login path words its refusal from it.
        when(cacheService.increment(anyString(), anyLong())).thenReturn(3L);

        assertThat(identityImpl.recordPasswordFailure(identity(null))).isEqualTo(3L);

        verify(identityImpl, never()).updateOne(any(UserIdentity.class));
    }

    @Test
    void atTheThreshold_theLockIsPersisted_andTheCounterReset() {
        // Persisted because a cache flush must not be an unlock; the counter is reset with it so
        // one wrong password after expiry does not immediately re-lock.
        UserIdentity person = identity(null);
        when(cacheService.increment(anyString(), anyLong())).thenReturn(10L);
        doReturn(true).when(identityImpl).updateOne(person);

        assertThat(identityImpl.recordPasswordFailure(person)).isEqualTo(10L);

        assertThat(person.getPasswordLockedUntil()).isAfter(LocalDateTime.now().plusMinutes(29));
        verify(identityImpl).updateOne(person);
        verify(cacheService).clear("login:pwd-failures:" + IDENTITY);
    }

    @Test
    void settingAPassword_clearsTheLock_notJustTheCounter() {
        // The reset path's whole point: someone who forgot their password must be able to use the
        // one they just set. Leaving passwordLockedUntil standing would refuse them for 30 more
        // minutes, on a lock the reset itself resolved.
        UserIdentity person = identity(LocalDateTime.now().plusMinutes(29));
        doReturn(Optional.of(person)).when(identityImpl).getById(IDENTITY);
        doReturn(true).when(identityImpl).updateOne(person, false);

        identityImpl.setPassword(IDENTITY, "N3w-Passw0rd");

        assertThat(person.getPasswordLockedUntil()).isNull();
        // The overload that keeps nulls — the default one would drop exactly this write.
        verify(identityImpl).updateOne(person, false);
        verify(cacheService).clear("login:pwd-failures:" + IDENTITY);
    }

    @Test
    void anExpiredLock_isNotALock() {
        assertThat(identityImpl.isPasswordLocked(identity(LocalDateTime.now().minusMinutes(1))))
                .isFalse();
        assertThat(identityImpl.isPasswordLocked(identity(LocalDateTime.now().plusMinutes(1))))
                .isTrue();
        assertThat(identityImpl.isPasswordLocked(identity(null))).isFalse();
    }
}
