package io.softa.starter.user.service.impl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.domain.Filters;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.dto.WorkContacts;
import io.softa.starter.user.entity.UserIdentity;
import io.softa.starter.user.entity.UserSecurityLog;
import io.softa.starter.user.enums.SecurityAction;
import io.softa.starter.user.enums.SecurityLogSource;
import io.softa.starter.user.service.UserInvitationService;
import io.softa.starter.user.enums.AccountStatus;
import io.softa.starter.user.service.UserIdentityService;
import io.softa.starter.user.service.UserRoleRelService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Off-boarding and re-hire — the two ends of one membership's life.
 *
 * <p>Two assertions here are security properties rather than behaviour:
 *
 * <ul>
 *   <li>off-boarding releases the WORK EMAIL from the person's login identifiers, otherwise a
 *       recycled address lets a new hire verify by code into the previous holder's account;</li>
 *   <li>off-boarding and reviving both clear role grants, because re-hire REUSES the row — grants
 *       left behind would be inherited silently by the returning employee;</li>
 *   <li>off-boarding clears the person's password lock, for the same reuse reason: a lock left
 *       behind refuses the returning employee's password with nothing explaining it.</li>
 * </ul>
 */
class OffBoardAndReviveTest {

    private static final Long PROFILE = 7L;
    private static final Long ACCOUNT = 100L;
    /**
     * Deliberately NOT equal to PROFILE. The credential row and the person are separate ids, and
     * production passes {@code identity.getId()} — with both set to the same value the verify below
     * would pass for either argument, which is no assertion at all.
     */
    private static final Long IDENTITY = 11L;

    private final UserIdentityService identityService = mock(UserIdentityService.class);
    private final UserRoleRelService roleRelService = mock(UserRoleRelService.class);
    private final UserAccountServiceImpl accountService = spy(new UserAccountServiceImpl());
    private final io.softa.starter.user.service.UserSecurityLogService securityLog =
            AccountServiceTestSupport.quiet(accountService);

    OffBoardAndReviveTest() {
        ReflectionTestUtils.setField(accountService, "identityService", identityService);
        ReflectionTestUtils.setField(accountService, "roleRelService", roleRelService);
    }

    private static UserAccount account(AccountStatus status, String email, String mobile) {
        UserAccount account = new UserAccount();
        account.setId(ACCOUNT);
        account.setProfileId(PROFILE);
        account.setStatus(status);
        account.setEmail(email);
        account.setMobile(mobile);
        account.setActivationTime(LocalDateTime.now());
        return account;
    }

    private static UserIdentity identity(String loginEmail, String loginMobile) {
        UserIdentity identity = new UserIdentity();
        identity.setId(IDENTITY);
        identity.setProfileId(PROFILE);
        identity.setLoginEmail(loginEmail);
        identity.setLoginMobile(loginMobile);
        return identity;
    }

    private void workContacts(String email, String mobile) {
        doReturn(new WorkContacts(email, mobile)).when(accountService).archiveWorkContacts(ACCOUNT);
    }

    @Test
    void offBoarding_keepsThePersonsOwnLoginMobile() {
        // The login mobile is the person's own number, and it signs them into their other companies.
        UserIdentity person = identity(null, "+8613800138000");
        UserAccount membership = account(AccountStatus.ACTIVE, "alice@acme.com", "+8613800138000");
        workContacts("alice@acme.com", "+6590000000");
        when(identityService.findByProfile(PROFILE)).thenReturn(Optional.of(person));

        accountService.offBoardWith(membership);

        assertThat(person.getLoginMobile()).isEqualTo("+8613800138000");
        verify(identityService, never()).updateOne(any(UserIdentity.class), anyBoolean());
        verify(securityLog, never()).entry(eq(SecurityAction.WORK_MOBILE_RELEASED), any(), any(), any());
    }

    @Test
    void offBoarding_releasesALoginMobileThatIsThisCompanysWorkMobile() {
        // People created when the work mobile came first sign in with a company handset's number,
        // which goes to the next hire. That one is taken off the person, and logged.
        UserIdentity person = identity(null, "+6590000000");
        UserAccount membership = account(AccountStatus.ACTIVE, "alice@acme.com", "+6590000000");
        workContacts("alice@acme.com", "+65 9000-0000");
        when(identityService.findByProfile(PROFILE)).thenReturn(Optional.of(person));

        accountService.offBoardWith(membership);

        assertThat(person.getLoginMobile()).isNull();
        verify(identityService).updateOne(person, false);
        verify(securityLog).entry(eq(SecurityAction.WORK_MOBILE_RELEASED), eq(SecurityLogSource.SYSTEM), eq(membership), any());
        // Masked as everywhere: dial code, space, first three, four stars, last two.
        assertThat(logged(SecurityAction.WORK_MOBILE_RELEASED).getOldValue()).isEqualTo("+65 900****00");
    }

    @Test
    void offBoarding_killsUnusedLinks_andLogsTheStatusChange() {
        UserAccount membership = account(AccountStatus.ACTIVE, "alice@acme.com", null);
        workContacts(null, null);
        when(identityService.findByProfile(PROFILE)).thenReturn(Optional.empty());
        UserInvitationService invitations = (UserInvitationService) ReflectionTestUtils.getField(accountService, "invitationService");

        accountService.offBoardWith(membership);

        verify(invitations).revokeOutstandingLinks(ACCOUNT);
        verify(securityLog).entry(eq(SecurityAction.STATUS_CHANGED), eq(SecurityLogSource.SYSTEM), eq(membership), any());
    }

    @Test
    void offBoarding_clearsThePasswordLock() {
        // A Locked row leaves through "termination (clear the lock first) → Deactivated".
        // Left standing, the lock survives the re-hire that REVIVES this row, and the returning
        // employee's password login is refused with nothing in the UI explaining why.
        UserIdentity person = identity("alice@acme.com", null);
        person.setPasswordLockedUntil(LocalDateTime.now().plusMinutes(30));
        UserAccount membership = account(AccountStatus.ACTIVE, "alice@acme.com", null);
        workContacts("alice@acme.com", null);
        when(identityService.findByProfile(PROFILE)).thenReturn(Optional.of(person));

        accountService.offBoardWith(membership);

        assertThat(person.getPasswordLockedUntil()).isNull();
        verify(identityService).clearPasswordFailures(IDENTITY);
        // One read and one write for both facts — the release and the unlock share the row.
        verify(identityService, times(1)).findByProfile(PROFILE);
        verify(identityService, times(1)).updateOne(person, false);
    }

    @Test
    void offBoarding_clearsTheLockEvenWhenNoIdentifierIsReleased() {
        // The lock is the PERSON's, the released address is this COMPANY's: clearing it must not
        // depend on the work contact happening to be a login identifier too. Without the write
        // here the unlock would stay in memory only.
        UserIdentity person = identity("alice.personal@gmail.com", null);
        person.setPasswordLockedUntil(LocalDateTime.now().plusMinutes(30));
        UserAccount membership = account(AccountStatus.ACTIVE, "alice@acme.com", null);
        workContacts("alice@acme.com", null);
        when(identityService.findByProfile(PROFILE)).thenReturn(Optional.of(person));

        accountService.offBoardWith(membership);

        assertThat(person.getLoginEmail()).isEqualTo("alice.personal@gmail.com");
        assertThat(person.getPasswordLockedUntil()).isNull();
        verify(identityService).updateOne(person, false);
    }

    @Test
    void offBoarding_clearsRoleGrants_andClosesTheMembership() {
        UserAccount membership = account(AccountStatus.ACTIVE, "alice@acme.com", null);
        workContacts(null, null);
        when(identityService.findByProfile(PROFILE)).thenReturn(Optional.empty());

        accountService.offBoardWith(membership);

        assertThat(membership.getStatus()).isEqualTo(AccountStatus.DEACTIVATED);
        verify(roleRelService).deleteByFilters(any(Filters.class));
    }

    @Test
    void offBoarding_isIdempotent() {
        UserAccount alreadyClosed = account(AccountStatus.DEACTIVATED, "alice@acme.com", null);

        accountService.offBoardWith(alreadyClosed);

        // Nothing touched — off-boarding twice is a no-op, not an error, because the caller
        // (an HR workflow) may legitimately fire more than once.
        verify(roleRelService, never()).deleteByFilters(any(Filters.class));
        verify(identityService, never()).updateOne(any(UserIdentity.class), anyBoolean());
    }

    @Test
    void reviving_resetsToPendingWithTheNewContacts() {
        UserAccount closed = account(AccountStatus.DEACTIVATED, "old@acme.com", "+8613800000000");
        closed.setRoles(List.of(1L, 2L));

        accountService.reviveWith(closed, "new@acme.com", "+8613811111111");

        assertThat(closed.getStatus()).isEqualTo(AccountStatus.PENDING);
        assertThat(closed.getEmail()).isEqualTo("new@acme.com");
        assertThat(closed.getMobile()).isEqualTo("+8613811111111");
        // No activation carried over: the returning employee must accept a fresh invitation.
        assertThat(closed.getActivationTime()).isNull();
        // And no person: /join decides again who this is, from the number it verifies — with the
        // person it belonged to remembered, so they are recognised when it is them.
        assertThat(closed.getProfileId()).isNull();
        assertThat(closed.getFormerProfileId()).isEqualTo(PROFILE);
    }

    @Test
    void theResetAndUnbindDialogs_getThePersonalAndTheLoginMobile_besideTheWorkContacts() {
        UserAccount membership = account(AccountStatus.ACTIVE, "alice@acme.com", "+6590000001");
        doReturn(Optional.of(membership)).when(accountService).getById(ACCOUNT);
        workContacts("alice@acme.com", "+6594444444");
        doReturn("+6592222222").when(accountService).archivePersonalMobile(ACCOUNT);
        when(identityService.findByProfile(PROFILE)).thenReturn(Optional.of(identity(null, "+6591111111")));

        WorkContacts contacts = accountService.archiveContactsForAccount(ACCOUNT);

        // The work mobile stays what it was — off-boarding compares against it.
        assertThat(contacts.mobile()).isEqualTo("+6594444444");
        assertThat(contacts.personalMobile()).isEqualTo("+6592222222");
        assertThat(contacts.loginMobile()).isEqualTo("+6591111111");
    }

    @Test
    void reviving_dropsTheEmailTheAccountClosedWith() {
        UserAccount closed = account(AccountStatus.DEACTIVATED, null, null);
        closed.setClosedEmail("old@acme.com");

        accountService.reviveWith(closed, "new@acme.com", null);

        assertThat(closed.getClosedEmail()).isNull();
    }

    @Test
    void theListedMobile_followsTheLoginMobile_onJoinedAccountsOnly() {
        UserAccount joined = account(AccountStatus.ACTIVE, "a@acme.com", "+6590000001");
        UserAccount invited = account(AccountStatus.INVITED, "a@globex.com", "+6590000001");
        invited.setId(200L);
        doReturn(List.of(joined, invited)).when(accountService).listMembershipsOf(PROFILE);
        doReturn(true).when(accountService).updateOne(any(UserAccount.class), anyBoolean());
        when(identityService.findByProfile(PROFILE)).thenReturn(Optional.of(identity(null, "+6598765432")));

        accountService.followLoginMobile(PROFILE);

        assertThat(joined.getMobile()).isEqualTo("+6598765432");
        verify(accountService).updateOne(joined, false);
        // Before joining, the invitation mobile is what the list shows.
        assertThat(invited.getMobile()).isEqualTo("+6590000001");
    }

    @Test
    void reviving_aRowNobodyJoinedSinceItWasLastReopened_keepsThePersonItRemembers() {
        UserAccount reopenedAndClosedAgain = account(AccountStatus.DEACTIVATED, null, "+8613800000000");
        reopenedAndClosedAgain.setProfileId(null);
        reopenedAndClosedAgain.setFormerProfileId(PROFILE);

        accountService.reviveWith(reopenedAndClosedAgain, "new@acme.com", "+8613811111111");

        assertThat(reopenedAndClosedAgain.getFormerProfileId()).isEqualTo(PROFILE);
    }

    @Test
    void offBoarding_takesTheEnterpriseEmailOffTheAccount_andKeepsTheOldValueInTheLog() {
        // The mailbox is the company's to hand to the next hire; left on the closed row it would
        // block that hire's account.
        UserSecurityLog logged = new UserSecurityLog();
        when(securityLog.entry(eq(SecurityAction.ENTERPRISE_EMAIL_CHANGED), any(), any(), any())).thenReturn(logged);
        UserAccount membership = account(AccountStatus.ACTIVE, "alice@acme.com", "+8613800138000");
        workContacts("alice@acme.com", null);
        when(identityService.findByProfile(PROFILE)).thenReturn(Optional.empty());

        accountService.offBoardWith(membership);

        assertThat(membership.getEmail()).isNull();
        // The list still shows what the closed account had.
        assertThat(membership.getClosedEmail()).isEqualTo("alice@acme.com");
        assertThat(logged.getOldValue()).isEqualTo("alice@acme.com");
        verify(securityLog).record(logged);
    }

    @Test
    void offBoarding_dropsTheAccountsPushDevices() {
        io.softa.starter.user.spi.PushDeviceRegistry devices = mock(io.softa.starter.user.spi.PushDeviceRegistry.class);
        ReflectionTestUtils.setField(accountService, "pushDeviceRegistry", devices);
        UserAccount membership = account(AccountStatus.ACTIVE, null, null);
        workContacts(null, null);
        when(identityService.findByProfile(PROFILE)).thenReturn(Optional.empty());

        accountService.offBoardWith(membership);

        verify(devices).unregisterAccount(ACCOUNT);
    }

    @Test
    void offBoard_writesTheClearedEmail() {
        // A null the default update overload would drop.
        UserAccount membership = account(AccountStatus.ACTIVE, "alice@acme.com", null);
        doReturn(Optional.of(membership)).when(accountService).getById(ACCOUNT);
        doReturn(true).when(accountService).updateOne(any(UserAccount.class), anyBoolean());
        workContacts("alice@acme.com", null);
        when(identityService.findByProfile(PROFILE)).thenReturn(Optional.empty());

        accountService.offBoard(ACCOUNT);

        verify(accountService).updateOne(membership, false);
    }

    @Test
    void reopeningByAccountId_findsARowThatHasNoPersonAnyMore() {
        // Reopened once, closed again before anyone joined: the row has no person, and a re-hire
        // looking it up by person would never reach it.
        UserAccount reopenedAndClosedAgain = account(AccountStatus.DEACTIVATED, null, null);
        reopenedAndClosedAgain.setTenantId(1L);
        reopenedAndClosedAgain.setProfileId(null);
        reopenedAndClosedAgain.setFormerProfileId(PROFILE);
        doReturn(Optional.of(reopenedAndClosedAgain)).when(accountService).getById(ACCOUNT);
        doReturn(List.of()).when(accountService).searchList(any(Filters.class));
        doReturn(true).when(accountService).updateOne(any(UserAccount.class), anyBoolean());

        assertThat(accountService.reviveAccount(ACCOUNT, "back@acme.com", "+8613811111111")).isPresent();

        assertThat(reopenedAndClosedAgain.getStatus()).isEqualTo(AccountStatus.PENDING);
        assertThat(reopenedAndClosedAgain.getEmail()).isEqualTo("back@acme.com");
    }

    @Test
    void reviving_clearsTheGrantsFromThePreviousStint() {
        // The reason revival needs care at all: reusing the row means anything left on it is
        // inherited. A returning employee must start with no roles.
        UserAccount closed = account(AccountStatus.DEACTIVATED, "old@acme.com", null);

        accountService.reviveWith(closed, "new@acme.com", null);

        verify(roleRelService).deleteByFilters(any(Filters.class));
    }

    /** The security-log row of this action that was written. */
    private UserSecurityLog logged(SecurityAction action) {
        org.mockito.ArgumentCaptor<UserSecurityLog> rows = org.mockito.ArgumentCaptor.forClass(UserSecurityLog.class);
        verify(securityLog, org.mockito.Mockito.atLeastOnce()).record(rows.capture());
        return rows.getAllValues().stream().filter(row -> row.getAction() == action).findFirst().orElseThrow();
    }
}
