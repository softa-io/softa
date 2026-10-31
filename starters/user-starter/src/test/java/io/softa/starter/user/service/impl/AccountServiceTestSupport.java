package io.softa.starter.user.service.impl;

import org.springframework.test.util.ReflectionTestUtils;

import io.softa.starter.user.entity.UserSecurityLog;
import io.softa.starter.user.service.UserInvitationService;
import io.softa.starter.user.service.UserSecurityLogService;
import io.softa.starter.user.service.UserSessionService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Collaborators every account-service test needs and none of them is about: the security log, the
 * session revocations and the invitation links that a status change touches on the side.
 */
final class AccountServiceTestSupport {

    private AccountServiceTestSupport() {
    }

    /** Wires quiet mocks for the side effects; returns the security log for tests that assert on it. */
    static UserSecurityLogService quiet(UserAccountServiceImpl accountService) {
        UserSecurityLogService securityLog = mock(UserSecurityLogService.class);
        when(securityLog.entry(any(), any(), any(), any())).thenAnswer(invocation -> {
            UserSecurityLog row = new UserSecurityLog();
            row.setAction(invocation.getArgument(0));
            return row;
        });
        ReflectionTestUtils.setField(accountService, "securityLog", securityLog);
        ReflectionTestUtils.setField(accountService, "sessionService", mock(UserSessionService.class));
        ReflectionTestUtils.setField(accountService, "invitationService", mock(UserInvitationService.class));
        return securityLog;
    }
}
