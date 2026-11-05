package io.softa.starter.user.service.impl;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.domain.Filters;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.entity.UserIdentity;
import io.softa.starter.user.entity.UserSecurityLog;
import io.softa.starter.user.enums.AccountStatus;
import io.softa.starter.user.enums.SecurityAction;
import io.softa.starter.user.service.UserIdentityService;
import io.softa.starter.user.service.UserSecurityLogService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A login mobile whose holder has left every company can be taken back for someone else; one whose
 * holder is still somewhere cannot.
 */
class DormantLoginMobileTest {

    private static final String NUMBER = "+6591234567";
    private static final Long LEAVER = 7L;

    private final UserIdentityService identityService = mock(UserIdentityService.class);
    private final UserAccountServiceImpl accountService = spy(new UserAccountServiceImpl());
    private final UserSecurityLogService securityLog = AccountServiceTestSupport.quiet(accountService);

    DormantLoginMobileTest() {
        ReflectionTestUtils.setField(accountService, "identityService", identityService);
        // No row is waiting to have anyone back unless a test says so.
        doReturn(List.of()).when(accountService).searchList(any(Filters.class));
    }

    private UserIdentity holder() {
        UserIdentity identity = new UserIdentity();
        identity.setId(11L);
        identity.setProfileId(LEAVER);
        identity.setLoginMobile(NUMBER);
        when(identityService.findByLoginIdentifier(NUMBER)).thenReturn(Optional.of(identity));
        return identity;
    }

    @Test
    void theReleasedNumber_isLoggedMaskedAsEverywhere() {
        holder();
        doReturn(List.of()).when(accountService).listMembershipsOf(LEAVER);

        accountService.releaseDormantLoginMobile(NUMBER, "Join");

        assertThat(logged(SecurityAction.DORMANT_MOBILE_RELEASED).getOldValue()).isEqualTo("+65 912****67");
    }

    @Test
    void aHolderWithOnlyClosedAccounts_isDormant_andLosesTheNumber() {
        UserIdentity identity = holder();
        doReturn(List.of()).when(accountService).listMembershipsOf(LEAVER);

        assertThat(accountService.releaseDormantLoginMobile(NUMBER, "Join")).isTrue();

        verify(identityService).updateLoginMobile(11L, null);
        verify(securityLog).entry(eq(SecurityAction.DORMANT_MOBILE_RELEASED), any(), any(), eq(LEAVER));
    }

    @Test
    void aHolderStillInvitedSomewhere_keepsTheNumber() {
        UserIdentity identity = holder();
        UserAccount invited = new UserAccount();
        invited.setStatus(AccountStatus.INVITED);
        doReturn(List.of(invited)).when(accountService).listMembershipsOf(LEAVER);

        assertThat(accountService.releaseDormantLoginMobile(NUMBER, "Join")).isFalse();

        assertThat(identity.getLoginMobile()).isEqualTo(NUMBER);
        verify(identityService, never()).updateLoginMobile(any(), any());
    }

    @Test
    void aNumberNobodyHolds_releasesNothing() {
        when(identityService.findByLoginIdentifier(NUMBER)).thenReturn(Optional.empty());

        assertThat(accountService.releaseDormantLoginMobile(NUMBER, "Join")).isFalse();
    }

    /** The security-log row of this action that was written. */
    private UserSecurityLog logged(SecurityAction action) {
        org.mockito.ArgumentCaptor<UserSecurityLog> rows = org.mockito.ArgumentCaptor.forClass(UserSecurityLog.class);
        verify(securityLog, org.mockito.Mockito.atLeastOnce()).record(rows.capture());
        return rows.getAllValues().stream().filter(row -> row.getAction() == action).findFirst().orElseThrow();
    }
}
