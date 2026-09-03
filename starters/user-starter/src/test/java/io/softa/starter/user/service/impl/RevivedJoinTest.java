package io.softa.starter.user.service.impl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.domain.Filters;
import io.softa.starter.user.dto.JoinContacts;
import io.softa.starter.user.dto.JoinVerification;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.entity.UserIdentity;
import io.softa.starter.user.entity.UserInvitation;
import io.softa.starter.user.enums.AccountStatus;
import io.softa.starter.user.enums.InvitationStatus;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserIdentityService;
import io.softa.starter.user.service.UserInvitationService;
import io.softa.starter.user.service.UserProfileService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * /join on a membership that already belongs to a person — the re-hired leaver.
 *
 * <p>Re-hire revives the closed row with its profileId intact and HR sends a fresh invitation to
 * the work address on it. That address was released from the person's identity at off-boarding,
 * so the flow's find-or-create by address sees nobody. Left to itself it would mint a second person
 * and confirmJoin would bind the row to it: the real person's password and other-company
 * memberships stay on a profileId that no membership points at any more, and a duplicate exists.
 * The row already knows whose it is, and that answer has to win over the address.
 */
class RevivedJoinTest {

    private static final String TOKEN = "raw-token";
    private static final Long ADA = 7L;
    private static final String RELEASED_WORK_EMAIL = "ada@acme.com";

    private final UserInvitationService invitationService = mock(UserInvitationService.class);
    private final UserAccountService accountService = mock(UserAccountService.class);
    private final UserIdentityService identityService = mock(UserIdentityService.class);
    private final UserProfileService profileService = mock(UserProfileService.class);
    private final VerificationCodeGuard codeGuard = mock(VerificationCodeGuard.class);
    /** Waved through: the proof is JoinProofTest's subject; this test is about whose row it is. */
    private final JoinProofGuard proofGuard = mock(JoinProofGuard.class);
    private final LoginServiceImpl loginService = new LoginServiceImpl();

    RevivedJoinTest() {
        ReflectionTestUtils.setField(loginService, "invitationService", invitationService);
        ReflectionTestUtils.setField(loginService, "consultantService",
                mock(io.softa.starter.user.service.ConsultantService.class));
        ReflectionTestUtils.setField(loginService, "accountService", accountService);
        ReflectionTestUtils.setField(loginService, "identityService", identityService);
        ReflectionTestUtils.setField(loginService, "profileService", profileService);
        ReflectionTestUtils.setField(loginService, "codeGuard", codeGuard);
        ReflectionTestUtils.setField(loginService, "proofGuard", proofGuard);
        when(invitationService.resolveJoinChannel(TOKEN, "email")).thenReturn(RELEASED_WORK_EMAIL);
        when(invitationService.resolveJoinContacts(TOKEN)).thenReturn(new JoinContacts(RELEASED_WORK_EMAIL, null));
        when(accountService.isWorkContactShared(RELEASED_WORK_EMAIL)).thenReturn(false);
        // Nobody's login identifier any more: off-boarding released it on purpose.
        when(identityService.findByLoginIdentifier(RELEASED_WORK_EMAIL)).thenReturn(Optional.empty());
    }

    private UserAccount invitationIsFor(Long profileId) {
        UserAccount account = new UserAccount();
        account.setId(100L);
        account.setTenantId(1L);
        account.setProfileId(profileId);
        account.setStatus(AccountStatus.INVITED);
        account.setEmail(RELEASED_WORK_EMAIL);
        when(invitationService.resolveJoinAccount(TOKEN)).thenReturn(Optional.of(account));
        return account;
    }

    private static UserIdentity identityOf(Long profileId, String loginEmail, String loginMobile) {
        UserIdentity identity = new UserIdentity();
        identity.setId(11L);
        identity.setProfileId(profileId);
        identity.setLoginEmail(loginEmail);
        identity.setLoginMobile(loginMobile);
        identity.setPassword("hash");
        return identity;
    }

    @Test
    void aRevivedMembership_resolvesToItsOwnPerson_andAFullyReleasedIdentityGetsTheAddressBack() {
        invitationIsFor(ADA);
        // Fully released: off-boarding took the work email, and she never had a personal one.
        UserIdentity ada = identityOf(ADA, null, null);
        when(identityService.findByProfile(ADA)).thenReturn(Optional.of(ada));
        when(identityService.isIdentifierClaimable(RELEASED_WORK_EMAIL, ADA)).thenReturn(true);

        JoinVerification result = loginService.verifyJoinCode(TOKEN, "email", "123456");

        assertThat(result.profileId()).isEqualTo(ADA);
        assertThat(result.mustSetPassword()).isFalse();   // she still has her password
        // No second Ada.
        verify(profileService, never()).createPersonForJoin(anyString());
        // The work address comes home as her login identifier: nobody could prove ownership of an
        // identity with no identifier at all, and the row's contacts were hers at off-boarding.
        assertThat(ada.getLoginEmail()).isEqualTo(RELEASED_WORK_EMAIL);
        verify(identityService).updateOne(ada);
    }

    @Test
    void anIdentityStillHoldingAnyLiveIdentifier_isNotRebound_evenOnTheEmptyChannel() {
        // The takeover: the row is bound and the invitation went to its WORK address — a contact
        // that is reissued (a pool phone, an HR typo). Whoever now holds that address passes the
        // code. Before this, an empty email channel was enough to rebind the address onto the
        // leaver's identity as a LOGIN identifier, after which the stranger signs in by code to that
        // address and lands in the leaver's profile. Ada still holds her mobile, so she has a way
        // in that the stranger does not; the address stays a work contact and nothing more.
        invitationIsFor(ADA);
        UserIdentity ada = identityOf(ADA, null, "+6591234567");
        when(identityService.findByProfile(ADA)).thenReturn(Optional.of(ada));

        JoinVerification result = loginService.verifyJoinCode(TOKEN, "email", "123456");

        assertThat(result.profileId()).isEqualTo(ADA);
        assertThat(ada.getLoginEmail()).isNull();
        verify(identityService, never()).isIdentifierClaimable(anyString(), any());
        verify(identityService, never()).updateOne(any(UserIdentity.class));
    }

    @Test
    void theReclaimedAddress_isStoredInCanonicalForm_notAsTheInvitationSpeltIt() {
        // The invitation carries the work contact as HR typed it; the identifier is what login
        // looks up, so it comes home in the form login will ask for it.
        when(invitationService.resolveJoinChannel(TOKEN, "email")).thenReturn(" Ada@Acme.com ");
        invitationIsFor(ADA);
        UserIdentity ada = identityOf(ADA, null, null);
        when(identityService.findByProfile(ADA)).thenReturn(Optional.of(ada));
        when(identityService.isIdentifierClaimable(RELEASED_WORK_EMAIL, ADA)).thenReturn(true);

        loginService.verifyJoinCode(TOKEN, "email", "123456");

        verify(codeGuard).verify(RELEASED_WORK_EMAIL, "123456");
        assertThat(ada.getLoginEmail()).isEqualTo(RELEASED_WORK_EMAIL);
    }

    @Test
    void anAddressSomeoneElseClaimedMeanwhile_isNotReboundOntoThePerson() {
        // The address was reissued and another identity holds it now. Binding it to Ada as well
        // would make the identifier ambiguous for both; her person still resolves from the row.
        invitationIsFor(ADA);
        UserIdentity ada = identityOf(ADA, null, null);
        when(identityService.findByProfile(ADA)).thenReturn(Optional.of(ada));
        when(identityService.isIdentifierClaimable(RELEASED_WORK_EMAIL, ADA)).thenReturn(false);

        assertThat(loginService.verifyJoinCode(TOKEN, "email", "123456").profileId()).isEqualTo(ADA);

        assertThat(ada.getLoginEmail()).isNull();
        verify(identityService, never()).updateOne(any(UserIdentity.class));
    }

    @Test
    void aPersonalLoginEmail_isNotOverwrittenByTheWorkOne() {
        // She signs in with ada@personal.com; the invitation went to ada@acme.com on her revived
        // row. Her person is returned and her identity is not touched — confirmJoin then admits her
        // on the row's own profileId, not on the address (ConfirmJoinAuthorizationTest).
        invitationIsFor(ADA);
        UserIdentity ada = identityOf(ADA, "ada@personal.com", null);
        when(identityService.findByProfile(ADA)).thenReturn(Optional.of(ada));

        assertThat(loginService.verifyJoinCode(TOKEN, "email", "123456").profileId()).isEqualTo(ADA);

        assertThat(ada.getLoginEmail()).isEqualTo("ada@personal.com");
        verify(identityService, never()).isIdentifierClaimable(anyString(), any());
        verify(identityService, never()).updateOne(any(UserIdentity.class));
    }

    @Test
    void anUnboundMembership_stillFindsOrCreatesByAddress() {
        // The ordinary first-time invitee: the row belongs to nobody, so the address decides.
        invitationIsFor(null);
        when(profileService.createPersonForJoin(RELEASED_WORK_EMAIL)).thenReturn(42L);

        JoinVerification result = loginService.verifyJoinCode(TOKEN, "email", "123456");

        assertThat(result.profileId()).isEqualTo(42L);
        assertThat(result.mustSetPassword()).isTrue();
        verify(identityService).findByLoginIdentifier(RELEASED_WORK_EMAIL);
        verify(identityService, never()).findByProfile(any());
    }

    // ─── the bound person who kept a personal login identifier and has no password yet ───

    /** confirmJoin's real implementation, fed the same row and invitation the login steps saw. */
    private UserInvitationServiceImpl realInvitationServiceFor(UserAccount account) {
        UserInvitationServiceImpl real = spy(new UserInvitationServiceImpl(
                accountService, identityService, mock(ApplicationEventPublisher.class), null, proofGuard,
                "http://localhost"));
        UserInvitation invitation = new UserInvitation();
        invitation.setId(55L);
        invitation.setUserId(account.getId());
        invitation.setStatus(InvitationStatus.PENDING);
        invitation.setEmail(RELEASED_WORK_EMAIL);
        invitation.setExpiresAt(LocalDateTime.now().plusDays(1));
        doReturn(Optional.of(invitation)).when(real).searchOne(any(Filters.class));
        doReturn(true).when(real).updateOne(any(UserInvitation.class));
        when(accountService.getById(account.getId())).thenReturn(Optional.of(account));
        when(accountService.listMembershipsOf(any())).thenReturn(List.of());
        // Her own row is the membership in this tenant; it is not a second slot.
        when(accountService.findMembershipInTenant(1L, ADA)).thenReturn(Optional.of(account));
        return real;
    }

    @Test
    void aBoundPersonWithNoPassword_whoKeptAPersonalLogin_confirmsHere_andSetsThePasswordInSession() {
        // Ada left, kept ada@personal.com as her login, never set a password (she signs in by code),
        // and was re-hired. The code proved control of ada@acme.com — a mailbox the company holds —
        // not of Ada; letting it set her GLOBAL password would let whoever holds that mailbox sign
        // in as her everywhere. She has a way in the link-holder does not (her personal email), so
        // the password step is skipped here: she confirms on the row's profileId and afterJoin's
        // mustSetPassword takes her to set it inside her own session. Load-bearing: setJoinPassword
        // is refused with the sign-in wording and setPassword is never reached; confirmJoin succeeds.
        UserAccount revived = invitationIsFor(ADA);
        UserIdentity ada = identityOf(ADA, "ada@personal.com", null);
        ada.setPassword(null);
        when(identityService.findByProfile(ADA)).thenReturn(Optional.of(ada));

        JoinVerification verified = loginService.verifyJoinCode(TOKEN, "email", "123456");
        assertThat(verified.profileId()).isEqualTo(ADA);
        assertThat(verified.mustSetPassword()).isFalse();

        assertThatThrownBy(() -> loginService.setJoinPassword(TOKEN, ADA, "Str0ng!Passw0rd", "proof"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Sign in with your existing login to set a password.");
        verify(identityService, never()).setPassword(any(Long.class), anyString());

        realInvitationServiceFor(revived).confirmJoin(TOKEN, ADA, "proof");
        assertThat(revived.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(revived.getProfileId()).isEqualTo(ADA);
        verify(accountService).updateOne(revived);
    }

    @Test
    void aBoundPersonWithNoPassword_whoseOnlyLoginIsTheInvitedContact_stillSetsItHere() {
        // Her mobile is on the invitation too, so it is no way in the link-holder lacks: the
        // invitation reaches both of her identifiers. Nothing outside the invitation -> the
        // password step stays open, as it does for the fully released identity.
        invitationIsFor(ADA);
        when(invitationService.resolveJoinContacts(TOKEN))
                .thenReturn(new JoinContacts(RELEASED_WORK_EMAIL, "+6591234567"));
        UserIdentity ada = identityOf(ADA, null, "+6591234567");
        ada.setPassword(null);
        when(identityService.findByProfile(ADA)).thenReturn(Optional.of(ada));

        assertThat(loginService.verifyJoinCode(TOKEN, "email", "123456").mustSetPassword()).isTrue();

        loginService.setJoinPassword(TOKEN, ADA, "Str0ng!Passw0rd", "proof");
        verify(identityService).setPassword(11L, "Str0ng!Passw0rd");
    }

    @Test
    void aBoundRow_stillRefusesAnyOtherProfileId_atSetJoinPassword() {
        // The tie moved from the contact to the row's profileId; it did not go away. A link-holder
        // naming a stranger's password-less person — even one whose login identifier the invitation
        // happens to name — is refused before anything is written.
        invitationIsFor(ADA);
        UserIdentity stranger = identityOf(999L, RELEASED_WORK_EMAIL, null);
        stranger.setPassword(null);
        when(identityService.findByProfile(999L)).thenReturn(Optional.of(stranger));

        assertThatThrownBy(() -> loginService.setJoinPassword(TOKEN, 999L, "Str0ng!Passw0rd", "proof"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("does not belong");
        verify(identityService, never()).setPassword(any(Long.class), anyString());
    }
}
