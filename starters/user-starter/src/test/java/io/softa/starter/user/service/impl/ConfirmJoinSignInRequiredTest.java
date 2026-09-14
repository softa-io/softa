package io.softa.starter.user.service.impl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.context.UserInfo;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.CacheService;
import io.softa.starter.user.dto.AuthenticationResult;
import io.softa.starter.user.dto.JoinVerification;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.entity.UserIdentity;
import io.softa.starter.user.entity.UserInvitation;
import io.softa.starter.user.enums.AccountStatus;
import io.softa.starter.user.enums.InvitationStatus;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserIdentityService;
import io.softa.starter.user.service.UserProfileService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import io.softa.starter.user.service.ConsultantService;

/**
 * confirmJoin on a bound row does not sign in a person who can sign in elsewhere — password or not.
 *
 * <p>The trace this closes: Ada left, kept ada@personal.com as her login, and was re-hired. The
 * invitation goes to ada@acme.com — a mailbox the company holds. Whoever holds it passes the code
 * (mustSetPassword=false, so the password step is skipped) and confirms. Before this, confirmJoin
 * activated the row and then issued a session for Ada's profileId — and, had she belonged elsewhere
 * too, a pre-auth token into the company step. With no password on her identity that session mints
 * her GLOBAL first password ({@code /UserAccount/setMyFirstPassword}); with one, it simply IS Ada,
 * here and at every other company. The code proved the company's mailbox, not the person, and the
 * two must not be confused at the one moment a session is minted.
 */
class ConfirmJoinSignInRequiredTest {

    private static final String TOKEN = "raw-token";
    private static final Long ADA = 7L;
    private static final Long ACCOUNT_ID = 100L;
    private static final String WORK_EMAIL = "ada@acme.com";
    private static final String PERSONAL_EMAIL = "ada@personal.com";

    private final UserAccountService accountService = mock(UserAccountService.class);
    private final UserIdentityService identityService = mock(UserIdentityService.class);
    private final UserProfileService profileService = mock(UserProfileService.class);
    private final CacheService cacheService = mock(CacheService.class);
    private final VerificationCodeGuard codeGuard = mock(VerificationCodeGuard.class);
    /** Waved through: the proof is JoinProofTest's subject; this test is about what confirm issues. */
    private final JoinProofGuard proofGuard = mock(JoinProofGuard.class);
    /** The real invitation service, so the row is bound and activated the way production does it. */
    private final UserInvitationServiceImpl invitationService = spy(new UserInvitationServiceImpl(
            accountService, identityService, mock(ApplicationEventPublisher.class), null, proofGuard,
            "http://localhost"));
    private final LoginServiceImpl loginService = new LoginServiceImpl();

    ConfirmJoinSignInRequiredTest() {
        ReflectionTestUtils.setField(loginService, "invitationService", invitationService);
        ReflectionTestUtils.setField(loginService, "consultantService",
                mock(ConsultantService.class));
        ReflectionTestUtils.setField(loginService, "accountService", accountService);
        ReflectionTestUtils.setField(loginService, "identityService", identityService);
        ReflectionTestUtils.setField(loginService, "profileService", profileService);
        ReflectionTestUtils.setField(loginService, "cacheService", cacheService);
        ReflectionTestUtils.setField(loginService, "codeGuard", codeGuard);
        ReflectionTestUtils.setField(loginService, "proofGuard", proofGuard);
        when(accountService.isWorkContactShared(anyString())).thenReturn(false);
        when(profileService.getUserInfo(ACCOUNT_ID)).thenReturn(new UserInfo());
    }

    /** A pending invitation to {@code email} / {@code mobile} for a row bound to {@code profileId} (null = unbound). */
    private UserAccount invitationFor(Long profileId, String email, String mobile) {
        UserAccount account = new UserAccount();
        account.setId(ACCOUNT_ID);
        account.setTenantId(1L);
        account.setProfileId(profileId);
        account.setStatus(AccountStatus.INVITED);
        account.setEmail(email);
        account.setMobile(mobile);
        UserInvitation invitation = new UserInvitation();
        invitation.setId(55L);
        invitation.setUserId(ACCOUNT_ID);
        invitation.setStatus(InvitationStatus.PENDING);
        invitation.setEmail(email);
        invitation.setMobile(mobile);
        invitation.setExpiresAt(LocalDateTime.now().plusDays(1));
        doReturn(Optional.of(invitation)).when(invitationService).searchOne(any(Filters.class));
        doReturn(true).when(invitationService).updateOne(any(UserInvitation.class));
        when(accountService.getById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(accountService.findMembershipInTenant(1L, ADA)).thenReturn(Optional.of(account));
        // After activation the row is the one enterable membership, so a session would resolve.
        when(accountService.listMembershipsOf(ADA)).thenReturn(List.of(account));
        // Nobody holds the work address as a login identifier: off-boarding released it.
        when(identityService.findByLoginIdentifier(WORK_EMAIL)).thenReturn(Optional.empty());
        return account;
    }

    private UserIdentity ada(String loginEmail, String loginMobile, String password) {
        UserIdentity identity = new UserIdentity();
        identity.setId(11L);
        identity.setProfileId(ADA);
        identity.setLoginEmail(loginEmail);
        identity.setLoginMobile(loginMobile);
        identity.setPassword(password);
        when(identityService.findByProfile(ADA)).thenReturn(Optional.of(identity));
        return identity;
    }

    @Test
    void aBoundPerson_whoCanSignInElsewhere_andHasNoPassword_isJoinedButNotSignedIn() {
        UserAccount revived = invitationFor(ADA, WORK_EMAIL, null);
        ada(PERSONAL_EMAIL, null, null);

        // The mailbox holder passes the work-mailbox code; the password step is (rightly) skipped.
        JoinVerification verified = loginService.verifyJoinCode(TOKEN, "email", "123456");
        assertThat(verified.mustSetPassword()).isFalse();

        AuthenticationResult result = loginService.confirmJoin(TOKEN, ADA, "proof");

        // HR intended the membership: it is active and bound to Ada.
        assertThat(revived.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(revived.getProfileId()).isEqualTo(ADA);
        verify(accountService).updateOne(revived);
        // Load-bearing: nothing that stands for Ada leaves this call. No session payload (so the
        // controller sets no cookie), no pre-auth token (so the company step is unreachable), and
        // the result says why.
        assertThat(result.signInRequired()).isTrue();
        assertThat(result.isResolved()).isFalse();
        assertThat(result.userInfo()).isNull();
        assertThat(result.authToken()).isNull();
        assertThat(result.tenants()).isEmpty();
        assertThat(result.mustSetPassword()).isFalse();
        verify(cacheService, never()).save(anyString(), any(), anyInt());
        verify(profileService, never()).getUserInfo(any());
    }

    @Test
    void aFullyReleasedIdentity_stillGetsASession() {
        // Off-boarding took her only identifier and she never had another: the address that received
        // the code is the best evidence of who she is, and it came home at verifyJoinCode.
        invitationFor(ADA, WORK_EMAIL, null);
        UserIdentity released = ada(null, null, null);
        when(identityService.isIdentifierClaimable(WORK_EMAIL, ADA)).thenReturn(true);

        loginService.verifyJoinCode(TOKEN, "email", "123456");
        assertThat(released.getLoginEmail()).isEqualTo(WORK_EMAIL);
        AuthenticationResult result = loginService.confirmJoin(TOKEN, ADA, "proof");

        assertThat(result.signInRequired()).isFalse();
        assertThat(result.isResolved()).isTrue();
        assertThat(result.userInfo()).isNotNull();
        assertThat(result.mustSetPassword()).isTrue();
    }

    @Test
    void anIdentityHoldingOnlyTheInvitedContact_stillGetsASession() {
        // Her mobile is on the invitation too: nothing she holds is out of the link-holder's reach,
        // so the address remains the evidence and the flow stays as it was (password set on /join).
        invitationFor(ADA, WORK_EMAIL, "+6591234567");
        ada(null, "+6591234567", null);

        AuthenticationResult result = loginService.confirmJoin(TOKEN, ADA, "proof");

        assertThat(result.signInRequired()).isFalse();
        assertThat(result.isResolved()).isTrue();
    }

    @Test
    void aBoundPerson_withAPassword_whoHoldsAnOutsideLogin_isJoinedButNotSignedIn() {
        // The password changes nothing about WHO passed the code. Ada can sign in with
        // ada@personal.com and her password; the mailbox holder cannot, and a session here would
        // be Ada's — in this company, and through the pre-auth token in every other one.
        UserAccount revived = invitationFor(ADA, WORK_EMAIL, null);
        ada(PERSONAL_EMAIL, null, "hash");

        AuthenticationResult result = loginService.confirmJoin(TOKEN, ADA, "proof");

        assertThat(revived.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(revived.getProfileId()).isEqualTo(ADA);
        // Load-bearing: signInRequired, and nothing that stands for Ada — no session payload, no
        // pre-auth token written to the cache.
        assertThat(result.signInRequired()).isTrue();
        assertThat(result.isResolved()).isFalse();
        assertThat(result.userInfo()).isNull();
        assertThat(result.authToken()).isNull();
        verify(cacheService, never()).save(anyString(), any(), anyInt());
        verify(profileService, never()).getUserInfo(any());
    }

    @Test
    void aBoundPerson_withAPassword_holdingOnlyTheInvitedContact_stillGetsASession() {
        // Her only login is the work mobile the invitation also names: nothing she holds is out of
        // the link-holder's reach, so the address stays the evidence and the session is issued.
        invitationFor(ADA, WORK_EMAIL, "+6591234567");
        ada(null, "+6591234567", "hash");

        AuthenticationResult result = loginService.confirmJoin(TOKEN, ADA, "proof");

        assertThat(result.signInRequired()).isFalse();
        assertThat(result.isResolved()).isTrue();
        assertThat(result.mustSetPassword()).isFalse();
        verify(profileService).getUserInfo(eq(ACCOUNT_ID));
    }

    @Test
    void anUnboundRow_isTiedByTheContact_andGetsASession_evenWithAnotherLoginAndNoPassword() {
        // First time in this company, and the invitation went to HER login identifier: the code did
        // identify the person. Holding a second identifier the invitation did not name changes
        // nothing — the guard is about a bound row, where the row and not the address named her.
        invitationFor(null, WORK_EMAIL, null);
        ada(WORK_EMAIL, "+6591234567", null);

        AuthenticationResult result = loginService.confirmJoin(TOKEN, ADA, "proof");

        assertThat(result.signInRequired()).isFalse();
        assertThat(result.isResolved()).isTrue();
        verify(profileService).getUserInfo(eq(ACCOUNT_ID));
    }
}
