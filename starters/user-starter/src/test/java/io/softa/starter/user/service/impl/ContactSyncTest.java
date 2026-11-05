package io.softa.starter.user.service.impl;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.message.MailRequestMessage;
import io.softa.framework.orm.domain.Filters;
import io.softa.starter.user.entity.UserIdentity;
import io.softa.starter.user.service.UserInvitationService;
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
import static org.mockito.Mockito.when;

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
    void aClosedAccount_takesNoEmailBack() {
        // Closing released the address for the next hire; writing it back would block them.
        account.setStatus(AccountStatus.DEACTIVATED);
        account.setEmail(null);

        accountService.syncEnterpriseEmail(ACCOUNT, "old@acme.com");

        assertThat(account.getEmail()).isNull();
        verify(accountService, never()).updateOne(any(UserAccount.class), anyBoolean());
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

    @Test
    void thePlaceholderPersonsLoginMobile_followsTheInvitationMobile() {
        // Left on the old number, the placeholder would keep claiming a number the employee no
        // longer has, and block whoever owns it now.
        UserIdentityService identityService = mock(UserIdentityService.class);
        ReflectionTestUtils.setField(accountService, "identityService", identityService);
        UserInvitationService invitations = mock(UserInvitationService.class);
        ReflectionTestUtils.setField(accountService, "invitationService", invitations);
        when(invitations.isPlaceholderPerson(7L, ACCOUNT)).thenReturn(true);
        UserIdentity placeholder = new UserIdentity();
        placeholder.setId(70L);
        placeholder.setProfileId(7L);
        placeholder.setLoginMobile("+6591111111");
        when(identityService.findByProfile(7L)).thenReturn(Optional.of(placeholder));
        when(identityService.findByLoginIdentifier("+6592222222")).thenReturn(Optional.empty());
        account.setStatus(AccountStatus.PENDING);

        accountService.syncInviteMobile(ACCOUNT, "+6592222222");

        verify(identityService).updateLoginMobile(70L, "+6592222222");
    }

    @Test
    void aRealPersonsLoginMobile_staysTheirs_whenTheInvitationMobileChanges() {
        // The account was bound to someone who already signs in elsewhere: their login mobile is
        // theirs, in every company, and an employee record edited here must not move it.
        UserIdentityService identityService = mock(UserIdentityService.class);
        ReflectionTestUtils.setField(accountService, "identityService", identityService);
        UserInvitationService invitations = mock(UserInvitationService.class);
        ReflectionTestUtils.setField(accountService, "invitationService", invitations);
        when(invitations.isPlaceholderPerson(7L, ACCOUNT)).thenReturn(false);
        account.setStatus(AccountStatus.INVITED);

        accountService.syncInviteMobile(ACCOUNT, "+6592222222");

        assertThat(account.getMobile()).isEqualTo("+6592222222");
        verify(identityService, never()).updateLoginMobile(any(), any());
    }

    @Test
    void theEmailCheck_neverCountsTheAccountItIsAbout() {
        // /join replacing a placeholder: the account still names the placeholder until the bind is
        // written, and counting it refused every such join with EMAIL_TAKEN.
        UserAccount itself = new UserAccount();
        itself.setId(ACCOUNT);
        itself.setProfileId(5L);
        itself.setStatus(AccountStatus.INVITED);
        itself.setEmail("Ada@Acme.com");
        doReturn(List.of(itself)).when(accountService).searchList(any(Filters.class));

        assertThat(accountService.isEmailHeldByAnotherPerson("ada@acme.com", 9L, ACCOUNT)).isFalse();
        assertThat(accountService.isEmailHeldByAnotherPerson("ada@acme.com", 9L, null)).isTrue();
    }

    @Test
    void anEmailLookup_escapesLikeWildcards_andRefusesWhatIsNotAnAddress() {
        // Anonymous login screens reach this; "%@%" must not become a match-everything pattern.
        assertThat(UserAccountServiceImpl.likeLiteral("a_b%c@x.com")).isEqualTo("a\\_b\\%c@x.com");
        assertThat(accountService.findJoinedProfilesByEmail("%@%")).isEmpty();
        verify(accountService, never()).searchList(any(Filters.class));
    }
}
