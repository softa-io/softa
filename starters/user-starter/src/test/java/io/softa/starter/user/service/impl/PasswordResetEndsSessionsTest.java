package io.softa.starter.user.service.impl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.web.session.SessionRevokeReason;
import io.softa.starter.user.entity.UserIdentity;
import io.softa.starter.user.entity.UserSecurityLog;
import io.softa.starter.user.enums.LoginEvent;
import io.softa.starter.user.enums.SecurityAction;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserIdentityService;
import io.softa.starter.user.service.UserSecurityLogService;
import io.softa.starter.user.service.UserSessionService;
import io.softa.starter.user.spi.PushDeviceRegistry;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Forgot-password reset: by mobile or enterprise email, and it ends everything the old password
 * could have opened.
 */
class PasswordResetEndsSessionsTest {

    private static final Long PROFILE = 7L;

    private final UserIdentityService identityService = mock(UserIdentityService.class);
    private final UserAccountService accountService = mock(UserAccountService.class);
    private final UserSessionService sessionService = mock(UserSessionService.class);
    private final PushDeviceRegistry devices = mock(PushDeviceRegistry.class);
    private final UserSecurityLogService securityLog = mock(UserSecurityLogService.class);
    private final LoginHistoryRecorder history = mock(LoginHistoryRecorder.class);
    private final LoginServiceImpl loginService = new LoginServiceImpl();

    PasswordResetEndsSessionsTest() {
        ReflectionTestUtils.setField(loginService, "identityService", identityService);
        ReflectionTestUtils.setField(loginService, "accountService", accountService);
        ReflectionTestUtils.setField(loginService, "sessionService", sessionService);
        ReflectionTestUtils.setField(loginService, "pushDeviceRegistry", devices);
        ReflectionTestUtils.setField(loginService, "securityLog", securityLog);
        ReflectionTestUtils.setField(loginService, "history", history);
        ReflectionTestUtils.setField(loginService, "codeGuard", mock(VerificationCodeGuard.class));
        when(accountService.listAccountIdsOf(PROFILE)).thenReturn(List.of(100L));
        when(securityLog.entry(any(), any(), any(), any())).thenReturn(new UserSecurityLog());
    }

    @Test
    void aResetByEnterpriseEmail_endsEverySession_dropsEveryDevice_andIsLogged() {
        UserIdentity identity = new UserIdentity();
        identity.setId(11L);
        identity.setProfileId(PROFILE);
        identity.setPasswordLockedUntil(LocalDateTime.now().plusMinutes(5));
        when(identityService.findByLoginIdentifier("ada@acme.com")).thenReturn(Optional.of(identity));
        when(identityService.isPasswordLocked(identity)).thenReturn(true);

        loginService.resetPasswordByCode("ada@acme.com", "123456", "N3w-Passw0rd!");

        verify(identityService).setPassword(11L, "N3w-Passw0rd!");
        // Nobody is signed in on this path, so nothing is kept.
        verify(sessionService).revokePerson(PROFILE, null, SessionRevokeReason.PASSWORD_RESET);
        verify(devices).unregisterPerson(PROFILE);
        verify(securityLog).entry(eq(SecurityAction.PASSWORD_RESET), any(), isNull(), eq(PROFILE));
        verify(history).record(eq(LoginEvent.UNLOCKED), eq(PROFILE), any(), any(), any(), any(), any());
    }

    @Test
    void aLockThatAlreadyRanOut_isNotRecordedAsUnlockedAgain() {
        // The field outlives the lock; only a lock still in force is lifted by the reset.
        UserIdentity identity = new UserIdentity();
        identity.setId(11L);
        identity.setProfileId(PROFILE);
        identity.setPasswordLockedUntil(LocalDateTime.now().minusMinutes(5));
        when(identityService.findByLoginIdentifier("ada@acme.com")).thenReturn(Optional.of(identity));

        loginService.resetPasswordByCode("ada@acme.com", "123456", "N3w-Passw0rd!");

        verify(history, never()).record(eq(LoginEvent.UNLOCKED), any(), any(), any(), any(), any(), any());
    }
}
