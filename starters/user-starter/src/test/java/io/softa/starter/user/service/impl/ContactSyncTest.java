package io.softa.starter.user.service.impl;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.message.MailRequestMessage;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.enums.AccountStatus;
import io.softa.starter.user.enums.SecurityAction;
import io.softa.starter.user.service.UserIdentityService;
import io.softa.starter.user.service.UserSecurityLogService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * The enterprise email follows the employee record in every status; the invitation mobile follows
 * it only until the person joins.
 */
class ContactSyncTest {

    private static final Long ACCOUNT = 100L;

    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final UserAccountServiceImpl accountService = spy(new UserAccountServiceImpl());
    private final UserSecurityLogService securityLog = AccountServiceTestSupport.quiet(accountService);
    private final UserAccount account = new UserAccount();

    ContactSyncTest() {
        ReflectionTestUtils.setField(accountService, "identityService", mock(UserIdentityService.class));
        ReflectionTestUtils.setField(accountService, "eventPublisher", eventPublisher);
        account.setId(ACCOUNT);
        account.setTenantId(1L);
        account.setProfileId(7L);
        account.setEmail("old@acme.com");
        account.setMobile("+6591111111");
        doReturn(Optional.of(account)).when(accountService).getById(ACCOUNT);
        doReturn(true).when(accountService).updateOne(any(UserAccount.class), anyBoolean());
        doReturn(null).when(accountService).enterpriseEmailRefusal(eq(ACCOUNT), any());
    }

    @Test
    void aJoinedAccountsEmailChange_tellsTheOldAddress_andIsLogged() {
        account.setStatus(AccountStatus.ACTIVE);

        accountService.syncEnterpriseEmail(ACCOUNT, "new@acme.com");

        assertThat(account.getEmail()).isEqualTo("new@acme.com");
        verify(eventPublisher).publishEvent(any(MailRequestMessage.class));
        verify(securityLog).entry(eq(SecurityAction.ENTERPRISE_EMAIL_CHANGED), any(), eq(account), any());
    }

    @Test
    void anInvitedAccountsEmailChange_isSilent() {
        account.setStatus(AccountStatus.INVITED);

        accountService.syncEnterpriseEmail(ACCOUNT, "new@acme.com");

        assertThat(account.getEmail()).isEqualTo("new@acme.com");
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void anEmailSomeoneElseHolds_isTaken() {
        account.setStatus(AccountStatus.ACTIVE);
        doReturn("That work email is already linked to another person's account.")
                .when(accountService).enterpriseEmailRefusal(ACCOUNT, "taken@acme.com");

        assertThatThrownBy(() -> accountService.syncEnterpriseEmail(ACCOUNT, "taken@acme.com"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("EMAIL_TAKEN"));
        assertThat(account.getEmail()).isEqualTo("old@acme.com");
    }

    @Test
    void theInvitationMobileFollowsTheRecord_onlyBeforeJoining() {
        account.setStatus(AccountStatus.PENDING);
        accountService.syncInviteMobile(ACCOUNT, "+6592222222");
        assertThat(account.getMobile()).isEqualTo("+6592222222");

        account.setStatus(AccountStatus.ACTIVE);
        accountService.syncInviteMobile(ACCOUNT, "+6593333333");
        assertThat(account.getMobile()).isEqualTo("+6592222222");
    }
}
