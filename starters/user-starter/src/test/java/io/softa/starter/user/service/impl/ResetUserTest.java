package io.softa.starter.user.service.impl;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.message.MailRequestMessage;
import io.softa.framework.base.message.SmsRequestMessage;
import io.softa.framework.web.session.SessionRevokeReason;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.entity.UserIdentity;
import io.softa.starter.user.entity.UserSecurityLog;
import io.softa.starter.user.enums.AccountStatus;
import io.softa.starter.user.enums.SecurityAction;
import io.softa.starter.user.service.UserIdentityService;
import io.softa.starter.user.service.UserSecurityLogService;
import io.softa.starter.user.service.UserSessionService;
import io.softa.starter.user.spi.PushDeviceRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.spy;

/**
 * Reset User replaces the person's login mobile with the personal mobile on the record — in every
 * company, for the same person — and nothing else.
 */
class ResetUserTest {

    private static final Long ACCOUNT = 100L;
    private static final Long PROFILE = 7L;
    private static final String OLD = "+6591111111";
    private static final String NEW = "+6592222222";

    private final UserIdentityService identityService = mock(UserIdentityService.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final PushDeviceRegistry devices = mock(PushDeviceRegistry.class);
    private final UserAccountServiceImpl accountService = spy(new UserAccountServiceImpl());
    private final UserSecurityLogService securityLog = AccountServiceTestSupport.quiet(accountService);
    private final UserAccount account = new UserAccount();
    private final UserIdentity person = new UserIdentity();

    ResetUserTest() {
        ReflectionTestUtils.setField(accountService, "identityService", identityService);
        ReflectionTestUtils.setField(accountService, "eventPublisher", eventPublisher);
        ReflectionTestUtils.setField(accountService, "pushDeviceRegistry", devices);
        account.setId(ACCOUNT);
        account.setTenantId(1L);
        account.setProfileId(PROFILE);
        account.setStatus(AccountStatus.ACTIVE);
        account.setEmail("ada@acme.com");
        person.setId(11L);
        person.setProfileId(PROFILE);
        person.setLoginMobile(OLD);
        person.setPassword("hash");
        doReturn(Optional.of(account)).when(accountService).getById(ACCOUNT);
        doReturn(List.of(account)).when(accountService).listMembershipsOf(PROFILE);
        doReturn(true).when(accountService).updateOne(any(UserAccount.class), anyBoolean());
        when(identityService.findByProfile(PROFILE)).thenReturn(Optional.of(person));
        when(identityService.requireIdentity(account)).thenReturn(person);
        when(identityService.findByLoginIdentifier(anyString())).thenReturn(Optional.empty());
        doReturn(NEW).when(accountService).archivePersonalMobile(ACCOUNT);
    }

    private UserSessionService sessions() {
        return (UserSessionService) ReflectionTestUtils.getField(accountService, "sessionService");
    }

    @Test
    void theLoginMobileBecomesThePersonalMobile_andOnlyThat() {
        accountService.resetWorkContacts(ACCOUNT, "Verified by passport at front desk");

        verify(identityService).updateLoginMobile(11L, NEW);
        // Same person, same enterprise email, same status, same password.
        assertThat(account.getProfileId()).isEqualTo(PROFILE);
        assertThat(account.getEmail()).isEqualTo("ada@acme.com");
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(person.getPassword()).isEqualTo("hash");
        // The account list shows and finds the number the person now signs in with.
        assertThat(account.getMobile()).isEqualTo(NEW);
    }

    @Test
    void theLog_masksBothNumbers_asTheyAreShownEverywhere() {
        accountService.resetWorkContacts(ACCOUNT, "Verified by passport");

        UserSecurityLog row = logged(SecurityAction.RESET_USER);
        assertThat(row.getOldValue()).isEqualTo("+65 911****11");
        assertThat(row.getNewValue()).isEqualTo("+65 922****22");
    }

    @Test
    void everySessionAndDeviceOfThePersonEnds_theOldNumberAndTheEmailAreTold_andItIsLogged() {
        accountService.resetWorkContacts(ACCOUNT, "Verified by passport");

        verify(sessions()).revokePerson(PROFILE, null, SessionRevokeReason.RESET_USER);
        verify(devices).unregisterPerson(PROFILE);
        verify(eventPublisher).publishEvent(any(SmsRequestMessage.class));
        verify(eventPublisher).publishEvent(any(MailRequestMessage.class));
        verify(securityLog).entry(eq(SecurityAction.RESET_USER), any(), eq(account), any());
    }

    @Test
    void aReasonIsRequired() {
        assertThatThrownBy(() -> accountService.resetWorkContacts(ACCOUNT, " "))
                .isInstanceOf(RuntimeException.class);
        assertThat(person.getLoginMobile()).isEqualTo(OLD);
    }

    @Test
    void theSameNumber_isNoChange() {
        doReturn("+65 9111-1111").when(accountService).archivePersonalMobile(ACCOUNT);

        assertThatThrownBy(() -> accountService.resetWorkContacts(ACCOUNT, "Verified"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("MOBILE_UNCHANGED"));
    }

    @Test
    void aNumberSomeoneActiveSignsInWith_isTaken() {
        UserIdentity other = new UserIdentity();
        other.setProfileId(99L);
        when(identityService.findByLoginIdentifier(NEW)).thenReturn(Optional.of(other));
        doReturn(false).when(accountService).releaseDormantLoginMobile(NEW, "Reset User");

        assertThatThrownBy(() -> accountService.resetWorkContacts(ACCOUNT, "Verified"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("MOBILE_TAKEN"));
        assertThat(person.getLoginMobile()).isEqualTo(OLD);
    }

    @Test
    void aNumberADormantPersonHeld_isReleasedFirst() {
        UserIdentity other = new UserIdentity();
        other.setProfileId(99L);
        when(identityService.findByLoginIdentifier(NEW)).thenReturn(Optional.of(other));
        doReturn(true).when(accountService).releaseDormantLoginMobile(NEW, "Reset User");

        accountService.resetWorkContacts(ACCOUNT, "Verified");

        assertThat(person.getLoginMobile()).isEqualTo(NEW);
    }

    @Test
    void anAccountNotJoinedYet_cannotBeReset() {
        account.setStatus(AccountStatus.INVITED);

        assertThatThrownBy(() -> accountService.resetWorkContacts(ACCOUNT, "Verified"))
                .isInstanceOf(BusinessException.class);
        verify(identityService, never()).updateLoginMobile(any(), any());
        assertThat(List.of()).isEmpty();
    }

    /** The security-log row of this action that was written. */
    private UserSecurityLog logged(SecurityAction action) {
        org.mockito.ArgumentCaptor<UserSecurityLog> rows = org.mockito.ArgumentCaptor.forClass(UserSecurityLog.class);
        verify(securityLog, org.mockito.Mockito.atLeastOnce()).record(rows.capture());
        return rows.getAllValues().stream().filter(row -> row.getAction() == action).findFirst().orElseThrow();
    }
}
