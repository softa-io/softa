package io.softa.starter.user.service.impl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.domain.Filters;
import io.softa.starter.message.service.MessageService;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.entity.UserIdentity;
import io.softa.starter.user.entity.UserInvitation;
import io.softa.starter.user.enums.AccountStatus;
import io.softa.starter.user.enums.InvitationStatus;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserIdentityService;

import static io.softa.starter.user.TestSystemConfigs.siteAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The authorization tie on {@code confirmJoin}.
 *
 * <p>The endpoint is anonymous — whoever holds the link reaches it with no session — and it takes
 * {@code profileId} from the caller. Without a check that the named person is the one the
 * invitation was addressed to, a holder of ANY valid invitation could bind their account to
 * someone else's person by naming that person's id. {@code setJoinPassword} already makes this tie;
 * this asserts {@code confirmJoin} makes the same one, because it is the call that actually writes
 * the binding.
 */
class ConfirmJoinAuthorizationTest {

    private static final String TOKEN = "raw-token";
    private static final Long ACCOUNT_ID = 100L;
    private static final Long TENANT = 1L;
    private static final Long RIGHTFUL_PROFILE = 7L;
    private static final Long ATTACKER_TARGET_PROFILE = 999L;

    private final UserAccountService accountService = mock(UserAccountService.class);
    private final UserIdentityService identityService = mock(UserIdentityService.class);
    private final MessageService messageService = mock(MessageService.class);
    /** The proof is JoinProofTest's subject; here it is waved through so the profileId tie is what decides. */
    private final UserInvitationServiceImpl service = spy(new UserInvitationServiceImpl(
            accountService, identityService, messageService, null, mock(JoinProofGuard.class),
            mock(VerificationCodeGuard.class), siteAt("http://localhost:3000")));
    private final io.softa.starter.user.service.UserSecurityLogService invitationSecurityLog =
            InvitationServiceTestSupport.quiet(service);

    private UserInvitation givenPendingInvitationTo(String email, String mobile) {
        UserInvitation invitation = new UserInvitation();
        invitation.setId(55L);
        invitation.setUserId(ACCOUNT_ID);
        invitation.setStatus(InvitationStatus.PENDING);
        invitation.setEmail(email);
        invitation.setMobile(mobile);
        invitation.setExpiresAt(LocalDateTime.now().plusDays(1));
        doReturn(Optional.of(invitation)).when(service).searchOne(any(Filters.class));

        UserAccount account = new UserAccount();
        account.setId(ACCOUNT_ID);
        account.setTenantId(TENANT);
        account.setStatus(AccountStatus.INVITED);
        account.setEmail(email);
        account.setMobile(mobile);
        when(accountService.getById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(accountService.listMembershipsOf(any())).thenReturn(List.of());
        doReturn(true).when(service).updateOne(any(UserInvitation.class));
        return invitation;
    }

    private static UserIdentity withPassword(UserIdentity identity) {
        identity.setPassword("hash");
        return identity;
    }

    private static UserIdentity identity(String loginEmail, String loginMobile) {
        UserIdentity identity = new UserIdentity();
        identity.setLoginEmail(loginEmail);
        identity.setLoginMobile(loginMobile);
        return identity;
    }

    @Test
    void theRightfulInvitee_canConfirm() {
        givenPendingInvitationTo("alice@acme.com", null);
        when(identityService.findByProfile(RIGHTFUL_PROFILE))
                .thenReturn(Optional.of(identity("alice@acme.com", null)));

        service.confirmJoin(TOKEN, RIGHTFUL_PROFILE, "proof");

        verify(accountService).updateOne(any(UserAccount.class));
    }

    @Test
    void aMembershipAlreadyBoundToAPerson_isNotHandedToAnotherProfileId() {
        // A re-hired leaver's revived row carries their profileId. verifyJoinCode returns that
        // person, so a different id here is a stale client or a link-holder choosing one — and
        // writing it would orphan the real person (their password and other tenants stay on the
        // old id). The bound id is asserted, never overwritten.
        givenPendingInvitationTo("alice@acme.com", null);
        UserAccount revived = accountService.getById(ACCOUNT_ID).orElseThrow();
        revived.setProfileId(RIGHTFUL_PROFILE);
        // A real person — they have a password — not the placeholder minted with the account.
        when(identityService.findByProfile(RIGHTFUL_PROFILE)).thenReturn(Optional.of(withPassword(identity("alice@acme.com", null))));
        when(identityService.findByProfile(ATTACKER_TARGET_PROFILE))
                .thenReturn(Optional.of(identity("alice@acme.com", null)));

        assertThatThrownBy(() -> service.confirmJoin(TOKEN, ATTACKER_TARGET_PROFILE, "proof"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("does not belong");

        assertThat(revived.getProfileId()).isEqualTo(RIGHTFUL_PROFILE);
        verify(accountService, never()).updateOne(any(UserAccount.class));
    }

    @Test
    void theBoundPerson_confirmsTheirRevivedMembership() {
        givenPendingInvitationTo("alice@acme.com", null);
        UserAccount revived = accountService.getById(ACCOUNT_ID).orElseThrow();
        revived.setProfileId(RIGHTFUL_PROFILE);
        when(identityService.findByProfile(RIGHTFUL_PROFILE))
                .thenReturn(Optional.of(identity("alice@acme.com", null)));
        // Their own row is the membership in this tenant; it is not a second slot.
        when(accountService.findMembershipInTenant(TENANT, RIGHTFUL_PROFILE))
                .thenReturn(Optional.of(revived));

        service.confirmJoin(TOKEN, RIGHTFUL_PROFILE, "proof");

        assertThat(revived.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        verify(accountService).updateOne(revived);
    }

    @Test
    void theBoundPerson_whoKeptAPersonalLoginEmail_confirmsTheirRevivedMembership() {
        // Ada left, kept ada@personal.com as her login, and was re-hired; the invitation went to the
        // work address on her revived row. verifyJoinCode returned her person without rebinding that
        // address (she still holds a live identifier — see RevivedJoinTest's takeover case), so her
        // identity carries no contact the invitation names. The row's profileId equalling hers IS
        // the tie for a bound row: the invitation was issued for this very row, and she proved
        // control of the address it went to. Tying her to the contact instead refused exactly the
        // person the invitation was for.
        givenPendingInvitationTo("alice@acme.com", null);
        UserAccount revived = accountService.getById(ACCOUNT_ID).orElseThrow();
        revived.setProfileId(RIGHTFUL_PROFILE);
        when(identityService.findByProfile(RIGHTFUL_PROFILE))
                .thenReturn(Optional.of(identity("alice.personal@gmail.com", null)));
        when(accountService.findMembershipInTenant(TENANT, RIGHTFUL_PROFILE))
                .thenReturn(Optional.of(revived));

        service.confirmJoin(TOKEN, RIGHTFUL_PROFILE, "proof");

        assertThat(revived.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(revived.getProfileId()).isEqualTo(RIGHTFUL_PROFILE);
        verify(accountService).updateOne(revived);
    }

    @Test
    void aReHireViaNewInvitation_isToldToUseReHire_notAConstraintError() {
        // The person left this company before: their DEACTIVATED row still holds the (tenant,
        // profile) slot. Binding here would hit the unique index; listMembershipsOf hides that row,
        // so without this the operator sees a raw constraint error instead of "use re-hire".
        givenPendingInvitationTo("alice@acme.com", null);
        when(identityService.findByProfile(RIGHTFUL_PROFILE))
                .thenReturn(Optional.of(identity("alice@acme.com", null)));
        UserAccount closed = new UserAccount();
        closed.setId(77L);
        closed.setTenantId(TENANT);
        closed.setStatus(AccountStatus.DEACTIVATED);
        when(accountService.findMembershipInTenant(TENANT, RIGHTFUL_PROFILE))
                .thenReturn(Optional.of(closed));

        assertThatThrownBy(() -> service.confirmJoin(TOKEN, RIGHTFUL_PROFILE, "proof"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("re-hire");
        verify(accountService, never()).updateOne(any(UserAccount.class));
    }

    @Test
    void aPersonAlreadyInThisCompany_isAlreadyJoined() {
        givenPendingInvitationTo("alice@acme.com", null);
        UserAccount live = new UserAccount();
        live.setId(77L);
        live.setTenantId(TENANT);
        live.setStatus(AccountStatus.ACTIVE);
        when(accountService.findMembershipInTenant(TENANT, RIGHTFUL_PROFILE)).thenReturn(Optional.of(live));

        assertThatThrownBy(() -> service.confirmJoin(TOKEN, RIGHTFUL_PROFILE, "proof"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("ALREADY_JOINED"));
        verify(accountService, never()).updateOne(any(UserAccount.class));
    }

    @Test
    void anEnterpriseEmailAnotherPersonHolds_isTaken_systemWide() {
        givenPendingInvitationTo("alice@acme.com", null);
        when(accountService.isEmailHeldByAnotherPerson(eq("alice@acme.com"), eq(RIGHTFUL_PROFILE), any())).thenReturn(true);

        assertThatThrownBy(() -> service.confirmJoin(TOKEN, RIGHTFUL_PROFILE, "proof"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("EMAIL_TAKEN"));
        verify(accountService, never()).updateOne(any(UserAccount.class));
        verify(invitationSecurityLog).entry(eq(io.softa.starter.user.enums.SecurityAction.JOIN_BLOCKED), any(), any(), eq(RIGHTFUL_PROFILE));
    }
}
