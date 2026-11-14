package io.softa.starter.user.service.impl;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.BusinessException;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.entity.UserIdentity;
import io.softa.starter.user.enums.AccountStatus;
import io.softa.starter.user.enums.LoginEvent;
import io.softa.starter.user.service.UserIdentityService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Changing one's own password asks for the current one — and that question is on the same terms as
 * the login screen. Without the lock it was a second door onto the same password, with no limit:
 * anyone holding a signed-in session could guess there for as long as they liked.
 */
class ChangeMyPasswordLockTest {

    private static final Long ACCOUNT = 100L;
    private static final Long PROFILE = 7L;
    private static final Long IDENTITY = 11L;

    private final UserIdentityService identityService = mock(UserIdentityService.class);
    private final UserAccountServiceImpl accountService = spy(new UserAccountServiceImpl());
    private final UserIdentity person = new UserIdentity();

    ChangeMyPasswordLockTest() {
        AccountServiceTestSupport.quiet(accountService);
        ReflectionTestUtils.setField(accountService, "identityService", identityService);
        UserAccount account = new UserAccount();
        account.setId(ACCOUNT);
        account.setTenantId(1L);
        account.setProfileId(PROFILE);
        account.setStatus(AccountStatus.ACTIVE);
        doReturn(Optional.of(account)).when(accountService).getById(ACCOUNT);
        person.setId(IDENTITY);
        person.setProfileId(PROFILE);
        when(identityService.requireIdentity(any())).thenReturn(person);
        when(identityService.matchesPassword(person, "Right-Passw0rd")).thenReturn(true);
        when(identityService.matchesPassword(person, "wrong")).thenReturn(false);
        when(identityService.matchesPassword(person, "N3w-Passw0rd")).thenReturn(false);
    }

    private void change(String current) {
        Context context = new Context();
        context.setUserId(ACCOUNT);
        ContextHolder.runWith(context, () -> accountService.changeMyPassword(current, "N3w-Passw0rd"));
    }

    private LoginHistoryRecorder history() {
        return (LoginHistoryRecorder) ReflectionTestUtils.getField(accountService, "loginHistory");
    }

    @Test
    void aWrongOldPassword_isCounted_onTheLoginCounter() {
        when(identityService.recordPasswordFailure(person)).thenReturn(3L);

        assertThatThrownBy(() -> change("wrong"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("PASSWORD_INCORRECT");
                    assertThat(e.getMessage()).isEqualTo("Incorrect old password.");
                });

        verify(identityService).recordPasswordFailure(person);
        verify(identityService, never()).setPassword(anyLong(), anyString());
    }

    @Test
    void nearTheLimit_theAttemptsLeftAreNamed() {
        when(identityService.recordPasswordFailure(person)).thenReturn(9L);

        assertThatThrownBy(() -> change("wrong"))
                .hasMessage("Incorrect old password. 1 attempt left before password login locks for 30 minutes.");
    }

    @Test
    void theFailureThatReachesTheLimit_locks() {
        when(identityService.recordPasswordFailure(person)).thenReturn((long) UserIdentityService.FAILURES_BEFORE_LOCK);

        assertThatThrownBy(() -> change("wrong"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("PASSWORD_LOCKED"));

        verify(history()).record(eq(LoginEvent.LOCKED), eq(PROFILE), eq(ACCOUNT), eq(1L), any(), any(), any());
        verify(identityService, never()).setPassword(anyLong(), anyString());
    }

    @Test
    void whileLocked_evenTheRightPasswordIsRefused_andNotChecked() {
        // Checked before the password, or "locked" versus "incorrect" would confirm a guess.
        when(identityService.isPasswordLocked(person)).thenReturn(true);

        assertThatThrownBy(() -> change("Right-Passw0rd"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("PASSWORD_LOCKED"));

        verify(identityService, never()).matchesPassword(any(), anyString());
        verify(identityService, never()).recordPasswordFailure(any());
        verify(identityService, never()).setPassword(anyLong(), anyString());
    }

    @Test
    void theLockIsWrittenInItsOwnTransaction_soTheRefusalCannotRollItBack() throws NoSuchMethodException {
        // changeMyPassword is transactional and refuses by throwing; in the caller's transaction the
        // lock would be rolled back with the refusal, while the cache counter is already reset.
        org.springframework.transaction.annotation.Transactional tx = UserIdentityServiceImpl.class
                .getMethod("recordPasswordFailure", UserIdentity.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertThat(tx).isNotNull();
        assertThat(tx.propagation())
                .isEqualTo(org.springframework.transaction.annotation.Propagation.REQUIRES_NEW);
    }

    @Test
    void theRightPassword_clearsTheCount_andChangesIt() {
        change("Right-Passw0rd");

        verify(identityService).clearPasswordFailures(IDENTITY);
        verify(identityService, never()).recordPasswordFailure(any());
        verify(identityService).setPassword(IDENTITY, "N3w-Passw0rd");
    }
}
