package io.softa.starter.user.service.impl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.orm.domain.Filters;
import io.softa.starter.user.dto.WorkContacts;
import io.softa.starter.user.constant.AccountErrorCodes;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.entity.UserInvitation;
import io.softa.starter.user.enums.AccountStatus;
import io.softa.starter.user.enums.InvitationPurpose;
import io.softa.starter.user.enums.InvitationStatus;
import org.springframework.test.util.ReflectionTestUtils;
import io.softa.framework.web.session.SessionRevokeReason;
import io.softa.starter.user.enums.SecurityAction;
import io.softa.starter.user.service.UserSessionService;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserIdentityService;

import static io.softa.starter.user.TestSystemConfigs.siteAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unbind & Re-invite — the remedy when a membership was bound to the WRONG person.
 *
 * <p>Three of these are security properties rather than behaviour:
 *
 * <ul>
 *   <li>the OLD person's login identifiers are released, or the wrong person keeps a working route
 *       into an address this company is about to hand to someone else;</li>
 *   <li>the detach is written with the overload that KEEPS nulls — the default one drops them, so
 *       the membership would stay attached and the call would report success;</li>
 *   <li>outstanding tokens are revoked, so the link the wrong person holds stops working.</li>
 * </ul>
 *
 * <p>And one that is a deliberate difference from off-boarding: role grants are KEPT, because the
 * position stands and only its holder was wrong.
 */
class UnbindAndReinviteTest {

    private static final Long ACCOUNT = 100L;
    private static final Long PROFILE = 7L;
    private static final Long OPERATOR = 1L;

    private final UserAccountService accountService = mock(UserAccountService.class);
    private final UserIdentityService identityService = mock(UserIdentityService.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final UserInvitationServiceImpl invitationService = spy(new UserInvitationServiceImpl(
            accountService, identityService, eventPublisher, null, mock(JoinProofGuard.class),
            mock(VerificationCodeGuard.class), siteAt("http://localhost:3000")));
    private final io.softa.starter.user.service.UserSecurityLogService invitationSecurityLog =
            InvitationServiceTestSupport.quiet(invitationService);

    private UserAccount given(AccountStatus status) {
        UserAccount account = new UserAccount();
        account.setId(ACCOUNT);
        account.setTenantId(1L);
        account.setProfileId(PROFILE);
        account.setStatus(status);
        account.setEmail("wrong@acme.com");
        account.setActivationTime(LocalDateTime.now());
        account.setRoles(List.of(9L));
        when(accountService.getById(ACCOUNT)).thenReturn(Optional.of(account));
        // The corrected contact is HR's edit to the EMPLOYEE RECORD now, not something
        // this call carries — so that is what has to be stubbed.
        when(accountService.archiveWorkContacts(ACCOUNT))
                .thenReturn(new WorkContacts("right@acme.com", null));
        when(accountService.archivePersonalMobile(ACCOUNT)).thenReturn("+6591234567");
        // No outstanding invitations unless a test says otherwise.
        doReturn(List.of()).when(invitationService).searchList(any(Filters.class));
        doReturn(1L).when(invitationService).createOne(any(UserInvitation.class));
        return account;
    }

    private static UserInvitation sentTo(String email, String mobile, InvitationPurpose purpose, int minutesAgo) {
        UserInvitation invitation = new UserInvitation();
        invitation.setId(60L + minutesAgo);
        invitation.setUserId(ACCOUNT);
        invitation.setStatus(InvitationStatus.REVOKED);
        invitation.setPurpose(purpose);
        invitation.setEmail(email);
        invitation.setMobile(mobile);
        invitation.setCreatedTime(LocalDateTime.now().minusMinutes(minutesAgo));
        return invitation;
    }

    @Test
    void unchangedContacts_areRefused_beforeAnyoneIsSignedOutOrDetached() {
        // Re-inviting to what the last invitation went to reaches the same wrong person again.
        UserAccount account = given(AccountStatus.ACTIVE);
        UserSessionService sessions = mock(UserSessionService.class);
        ReflectionTestUtils.setField(invitationService, "sessionService", sessions);
        // Spelt differently, case differs: still the same mobile and the same mailbox.
        doReturn(List.of(sentTo("Right@Acme.com", "+65 9123 4567", InvitationPurpose.INVITE, 5)))
                .when(invitationService).searchList(any(Filters.class));

        assertThatThrownBy(() -> invitationService.unbindAndReinvite(ACCOUNT, "Wrong hire", OPERATOR))
                .isInstanceOf(BusinessException.class)
                .hasMessage("The personal mobile and work email are the same as the last invitation. "
                        + "Update the employee profile first.")
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AccountErrorCodes.INVITATION_UNCHANGED);
        verify(sessions, never()).revokeAccount(any(), any());
        verify(accountService, never()).updateOne(any(UserAccount.class), anyBoolean());
        assertThat(account.getProfileId()).isEqualTo(PROFILE);
    }

    @Test
    void aBlankEmailOnBothSides_countsAsTheSame() {
        given(AccountStatus.ACTIVE);
        when(accountService.archiveWorkContacts(ACCOUNT)).thenReturn(new WorkContacts(null, null));
        doReturn(List.of(sentTo(" ", "+6591234567", InvitationPurpose.REINVITE, 5)))
                .when(invitationService).searchList(any(Filters.class));

        assertThatThrownBy(() -> invitationService.unbindAndReinvite(ACCOUNT, "Wrong hire", OPERATOR))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AccountErrorCodes.INVITATION_UNCHANGED);
    }

    @Test
    void onlyTheLastInvitationCounts_andAForgotPasswordLinkIsNotOne() {
        // The older invitation went to the same contacts; the newest one did not — so the record has
        // changed since. A forgot-password link to the same address is no invitation at all.
        given(AccountStatus.ACTIVE);
        doReturn(List.of(
                sentTo("right@acme.com", "+6591234567", InvitationPurpose.INVITE, 60),
                sentTo("right@acme.com", "+6599999999", InvitationPurpose.REINVITE, 30),
                sentTo("right@acme.com", "+6591234567", InvitationPurpose.PASSWORD_RESET, 5)))
                .when(invitationService).searchList(any(Filters.class));

        assertThatCode(() -> invitationService.unbindAndReinvite(ACCOUNT, "Wrong hire", OPERATOR))
                .doesNotThrowAnyException();
    }

    @Test
    void theWrongPersonIsSignedOutOfThisCompany_andKeepsTheirOwnLogin() {
        given(AccountStatus.ACTIVE);
        UserSessionService sessions = mock(UserSessionService.class);
        ReflectionTestUtils.setField(invitationService, "sessionService", sessions);

        invitationService.unbindAndReinvite(ACCOUNT, "Wrong hire", OPERATOR);

        verify(sessions).revokeAccount(ACCOUNT, SessionRevokeReason.UNBOUND);
        verify(identityService, never()).updateOne(any());
        verify(invitationSecurityLog).entry(eq(SecurityAction.UNBIND_AND_REINVITE), any(), any(), eq(PROFILE));
    }

    @Test
    void theMembershipIsDetached_writtenWithTheOverloadThatKeepsNulls() {
        // The trap: updateOne(entity) drops null keys, so the detach would silently not happen.
        UserAccount account = given(AccountStatus.ACTIVE);

        invitationService.unbindAndReinvite(ACCOUNT, "Wrong hire", OPERATOR);

        assertThat(account.getProfileId()).isNull();
        assertThat(account.getActivationTime()).isNull();
        assertThat(account.getEmail()).isEqualTo("right@acme.com");
        // Invited at the personal mobile on the record — the login mobile of whoever joins.
        assertThat(account.getMobile()).isEqualTo("+6591234567");
        assertThat(account.getStatus()).isEqualTo(AccountStatus.INVITED);
        verify(accountService).updateOne(account, false);
        verify(accountService, never()).updateOne(any(UserAccount.class));
    }

    @Test
    void roleGrantsAreKept_unlikeOffBoarding() {
        // The position stands; only its holder was wrong. Clearing them here would quietly strip a
        // department's manager of their authority as a side effect of fixing a typo.
        UserAccount account = given(AccountStatus.ACTIVE);

        invitationService.unbindAndReinvite(ACCOUNT, "Wrong hire", OPERATOR);

        assertThat(account.getRoles()).containsExactly(9L);
    }

    @Test
    void theLinkTheWrongPersonHolds_isRevoked_andAReinviteTokenIssued() {
        UserAccount account = given(AccountStatus.INVITED);
        UserInvitation outstanding = new UserInvitation();
        outstanding.setId(55L);
        outstanding.setUserId(ACCOUNT);
        outstanding.setStatus(InvitationStatus.PENDING);
        doReturn(List.of(outstanding)).when(invitationService).searchList(any(Filters.class));
        doReturn(true).when(invitationService).updateOne(any(UserInvitation.class));

        UserInvitation[] issued = new UserInvitation[1];
        doAnswer(call -> {
            issued[0] = call.getArgument(0);
            return 2L;
        }).when(invitationService).createOne(any(UserInvitation.class));

        invitationService.unbindAndReinvite(ACCOUNT, "Wrong hire", OPERATOR);

        assertThat(outstanding.getStatus()).isEqualTo(InvitationStatus.REVOKED);
        assertThat(issued[0].getPurpose()).isEqualTo(InvitationPurpose.REINVITE);
        // The reason rides on the invitation, where a later review of the membership will see it.
        assertThat(issued[0].getReason()).isEqualTo("Wrong hire");
        assertThat(issued[0].getEmail()).isEqualTo("right@acme.com");
    }

    @Test
    void aBlankOrOversizedReason_isRefused_beforeAnythingIsTouched() {
        given(AccountStatus.ACTIVE);

        // Assert.* raises the framework's own IllegalArgumentException (BAD_REQUEST) — not
        // java.lang's — which is the shape invite() uses for its preconditions too.
        assertThatThrownBy(() ->
                invitationService.unbindAndReinvite(ACCOUNT, "  ", OPERATOR))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                invitationService.unbindAndReinvite(ACCOUNT, "x".repeat(501), OPERATOR))
                .isInstanceOf(IllegalArgumentException.class);

        verify(accountService, never()).updateOne(any(UserAccount.class), anyBoolean());
    }

    @Test
    void pendingAndDeactivated_areOutOfScope_perTheStatusMatrix() {
        // Pending: nobody accepted, so there is nothing to unbind — fix the contact and Send.
        // Deactivated: the membership is closed; bringing it back is reviveMembership.
        for (AccountStatus status : List.of(AccountStatus.PENDING, AccountStatus.DEACTIVATED)) {
            given(status);
            assertThatThrownBy(() -> invitationService.unbindAndReinvite(ACCOUNT, "Wrong hire", OPERATOR))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("cannot be unbound");
        }
    }

    @Test
    void anAddressAnotherAccountHolds_isRefusedWithAName_notAConstraintViolation() {
        given(AccountStatus.ACTIVE);
        UserAccount other = new UserAccount();
        other.setId(999L);
        // Scoped to THIS company — see ResetWorkContactsTest for why the global form had to go.
        // The record now names the address, and another membership here already holds it.
        when(accountService.archiveWorkContacts(ACCOUNT))
                .thenReturn(new WorkContacts("taken@acme.com", null));
        when(accountService.findContactHolderInTenant("taken@acme.com", ACCOUNT))
                .thenReturn(Optional.of(other));

        assertThatThrownBy(() -> invitationService.unbindAndReinvite(ACCOUNT, "Wrong hire", OPERATOR))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already belongs to another account");

        // Refused BEFORE the unbind: otherwise the membership is detached and the call still fails.
    }

    @Test
    void anEmployeeRecordWithNoContacts_isRefused() {
        // Nothing to re-invite to, and nothing this call could supply instead: the contact comes
        // from the record now, so the message points at the record.
        given(AccountStatus.ACTIVE);
        when(accountService.archivePersonalMobile(ACCOUNT)).thenReturn(null);

        assertThatThrownBy(() ->
                invitationService.unbindAndReinvite(ACCOUNT, "Wrong hire", OPERATOR))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("before re-inviting");
    }
}
