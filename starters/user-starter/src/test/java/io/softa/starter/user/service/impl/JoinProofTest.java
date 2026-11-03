package io.softa.starter.user.service.impl;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.CacheService;
import io.softa.starter.user.dto.JoinContacts;
import io.softa.starter.user.dto.JoinVerification;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.entity.UserIdentity;
import io.softa.starter.user.entity.UserInvitation;
import io.softa.starter.user.enums.AccountStatus;
import io.softa.starter.user.enums.InvitationPurpose;
import io.softa.starter.user.enums.InvitationStatus;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserIdentityService;
import io.softa.starter.user.service.UserInvitationService;
import io.softa.starter.user.service.UserProfileService;

import static io.softa.starter.user.TestSystemConfigs.siteAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import io.softa.starter.user.service.ConsultantService;

/**
 * The proof that ties /join's two anonymous follow-up steps to a code that was actually passed.
 *
 * <p>The hole it closes: POST /login/setJoinPassword has no session, and for a row already bound
 * to a person the only tie was the caller-supplied profileId — readable off the roster. A company
 * holding a re-hired person's work mailbox (where the invitation lands) could set that person's
 * GLOBAL password with the token and that id, never passing the code, and sign in as them at every
 * other company. Run against the real guard over an in-memory cache and the real confirmJoin, so
 * that minting, checking and spending cannot drift apart.
 */
class JoinProofTest {

    private static final String TOKEN = "raw-token";
    private static final String OTHER_TOKEN = "another-invitation";
    private static final Long ADA = 7L;
    private static final Long STRANGER = 999L;
    private static final String WORK_EMAIL = "ada@acme.com";
    private static final String PASSWORD = "Str0ng!Passw0rd";

    private final Map<String, String> cache = new HashMap<>();
    private final JoinProofGuard proofGuard = new JoinProofGuard(inMemoryCache());

    private final UserInvitationService invitationService = mock(UserInvitationService.class);
    private final UserAccountService accountService = mock(UserAccountService.class);
    private final UserIdentityService identityService = mock(UserIdentityService.class);
    private final LoginServiceImpl loginService = new LoginServiceImpl();
    private final UserInvitationServiceImpl realInvitationService = spy(new UserInvitationServiceImpl(
            accountService, identityService, mock(ApplicationEventPublisher.class), null, proofGuard,
            mock(VerificationCodeGuard.class), siteAt("http://localhost")));
    private final io.softa.starter.user.service.UserSecurityLogService invitationSecurityLog =
            InvitationServiceTestSupport.quiet(realInvitationService);

    private final UserAccount revived = new UserAccount();
    private final UserIdentity ada = new UserIdentity();

    JoinProofTest() {
        ReflectionTestUtils.setField(loginService, "invitationService", invitationService);
        ReflectionTestUtils.setField(loginService, "history", mock(LoginHistoryRecorder.class));
        ReflectionTestUtils.setField(loginService, "mobileFormat", InvitationServiceTestSupport.mobileFormat());
        ReflectionTestUtils.setField(loginService, "consultantService",
                mock(ConsultantService.class));
        ReflectionTestUtils.setField(loginService, "accountService", accountService);
        ReflectionTestUtils.setField(loginService, "identityService", identityService);
        ReflectionTestUtils.setField(loginService, "profileService", mock(UserProfileService.class));
        ReflectionTestUtils.setField(loginService, "codeGuard", mock(VerificationCodeGuard.class));
        ReflectionTestUtils.setField(loginService, "proofGuard", proofGuard);

        // The bound, password-less row the hole is about: Ada's revived membership, fully released.
        revived.setId(100L);
        revived.setTenantId(1L);
        revived.setProfileId(ADA);
        revived.setStatus(AccountStatus.INVITED);
        revived.setEmail(WORK_EMAIL);
        ada.setId(11L);
        ada.setProfileId(ADA);
        when(invitationService.resolveJoinChannel(TOKEN, "email")).thenReturn(WORK_EMAIL);
        when(invitationService.resolveJoinContacts(TOKEN)).thenReturn(new JoinContacts(WORK_EMAIL, null));
        when(invitationService.resolveJoinAccount(TOKEN)).thenReturn(Optional.of(revived));
        when(identityService.findByProfile(ADA)).thenReturn(Optional.of(ada));
        when(identityService.isIdentifierClaimable(WORK_EMAIL, ADA)).thenReturn(true);

        // confirmJoin's own reads. The invitation is re-read PENDING on every call so the second
        // confirm reaches the account's activationTime check rather than the token-status one.
        doAnswer(inv -> Optional.of(pendingInvitation())).when(realInvitationService).searchOne(any(Filters.class));
        doReturn(true).when(realInvitationService).updateOne(any(UserInvitation.class));
        when(accountService.getById(100L)).thenReturn(Optional.of(revived));
        when(accountService.listMembershipsOf(any())).thenReturn(List.of());
        when(accountService.findMembershipInTenant(1L, ADA)).thenReturn(Optional.of(revived));
    }

    private CacheService inMemoryCache() {
        CacheService cacheService = mock(CacheService.class);
        doAnswer(inv -> cache.put(inv.getArgument(0), inv.getArgument(1).toString()))
                .when(cacheService).save(anyString(), any(), anyInt());
        when(cacheService.get(anyString())).thenAnswer(inv -> cache.get(inv.<String>getArgument(0)));
        doAnswer(inv -> cache.remove(inv.<String>getArgument(0))).when(cacheService).clear(anyString());
        return cacheService;
    }

    private static UserInvitation pendingInvitation() {
        UserInvitation invitation = new UserInvitation();
        invitation.setId(55L);
        invitation.setUserId(100L);
        invitation.setStatus(InvitationStatus.PENDING);
        invitation.setEmail(WORK_EMAIL);
        invitation.setExpiresAt(LocalDateTime.now().plusDays(1));
        return invitation;
    }

    private String passTheCode() {
        JoinVerification verified = loginService.verifyJoinCode(TOKEN, "email", "123456");
        assertThat(verified.profileId()).isEqualTo(ADA);
        assertThat(verified.proof()).isNotBlank();
        return verified.proof();
    }

    @Test
    void theChain_verifyThenSetPasswordThenConfirm_succeeds_andSpendsTheProof() {
        String proof = passTheCode();
        // The cache holds a digest of the proof, never the proof itself: a dump is not a replay.
        assertThat(cache.keySet()).allSatisfy(key -> assertThat(key).startsWith("join:proof").doesNotContain(proof));

        loginService.setJoinPassword(TOKEN, ADA, PASSWORD, proof);
        verify(identityService).setPassword(11L, PASSWORD);
        // Still alive: confirmJoin is next and needs it.
        assertThat(cache.keySet()).filteredOn(key -> key.startsWith("join:proof:")).hasSize(1);

        realInvitationService.confirmJoin(TOKEN, ADA, proof);
        assertThat(revived.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        verify(accountService).updateOne(revived);
        // Load-bearing: spent on success, so the completed flow leaves no standing credential.
        assertThat(cache).isEmpty();
    }

    @Test
    void joiningAReopenedAccount_endsTheSessionsStillNamingIt() {
        // Reopened for a re-hire: nobody is behind it until now, and whatever session still names the
        // account was opened for whoever held it before.
        io.softa.starter.user.service.UserSessionService sessions = mock(io.softa.starter.user.service.UserSessionService.class);
        ReflectionTestUtils.setField(realInvitationService, "sessionService", sessions);
        String proof = passTheCode();
        revived.setProfileId(null);

        realInvitationService.confirmJoin(TOKEN, ADA, proof);

        verify(sessions).revokeAccount(100L, io.softa.framework.web.session.SessionRevokeReason.UNBOUND);
        assertThat(revived.getProfileId()).isEqualTo(ADA);
    }

    @Test
    void withoutAProof_setJoinPasswordIsRefused_beforeAnythingIsLookedUp() {
        // The attack itself: token and roster id in hand, no code ever passed.
        for (String missing : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> loginService.setJoinPassword(TOKEN, ADA, PASSWORD, missing))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Verify the code sent to your contact first.");
        }
        verify(identityService, never()).setPassword(any(Long.class), anyString());
        verify(invitationService, never()).resolveJoinAccount(anyString());
    }

    @Test
    void aProofMintedForAnotherInvitation_isRefused() {
        // Passing the code on one's OWN invitation must not buy a proof that works on someone else's
        // link. Nothing about OTHER_TOKEN is stubbed: the refusal has to come before any lookup.
        String proof = passTheCode();

        assertThatThrownBy(() -> loginService.setJoinPassword(OTHER_TOKEN, ADA, PASSWORD, proof))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Verify the code sent to your contact first.");
        verify(identityService, never()).setPassword(any(Long.class), anyString());
    }

    @Test
    void aProofMintedForAnotherPerson_isRefused_withTheSameWording() {
        // Same link, different profileId: the proof names the person as well as the invitation.
        // Same message as "no proof" — a distinct one would confirm which ids a proof exists for.
        String proof = passTheCode();

        assertThatThrownBy(() -> loginService.setJoinPassword(TOKEN, STRANGER, PASSWORD, proof))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Verify the code sent to your contact first.");
        verify(identityService, never()).setPassword(any(Long.class), anyString());
    }

    @Test
    void confirmJoinWithoutAProof_isRefused_andBindsNothing() {
        // A stale or forged proof reaches confirmJoin the same way it reaches setJoinPassword.
        passTheCode();

        assertThatThrownBy(() -> realInvitationService.confirmJoin(TOKEN, ADA, "not-the-proof"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Verify the code sent to your contact first.");
        assertThat(revived.getStatus()).isEqualTo(AccountStatus.INVITED);
        verify(accountService, never()).updateOne(any(UserAccount.class));
    }

    @Test
    void aSpentProof_doesNotWorkAgain() {
        String proof = passTheCode();
        realInvitationService.confirmJoin(TOKEN, ADA, proof);
        revived.setActivationTime(null);   // pretend the row were fresh: only the proof should stop this

        assertThatThrownBy(() -> realInvitationService.confirmJoin(TOKEN, ADA, proof))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Verify the code sent to your contact first.");
    }

    @Test
    void aSecondTap_afterTheInvitationIsMarkedAccepted_returnsQuietly_forTheSamePersonOnly() {
        // As it really happens: the first confirm marks the invitation ACCEPTED, and the entry check
        // reads an accepted link as spent. The person's own second tap is not an error; a tap naming
        // someone else is still refused.
        UserInvitation invitation = pendingInvitation();
        doReturn(Optional.of(invitation)).when(realInvitationService).searchOne(any(Filters.class));
        String proof = passTheCode();
        realInvitationService.confirmJoin(TOKEN, ADA, proof);
        assertThat(invitation.getStatus()).isEqualTo(InvitationStatus.ACCEPTED);
        // Whatever the account's activation says, the invitation's status is what is read first.
        revived.setActivationTime(null);

        assertThatCode(() -> realInvitationService.confirmJoin(TOKEN, ADA, proof)).doesNotThrowAnyException();
        verify(accountService).updateOne(revived);   // once — the second tap wrote nothing
        assertThat(realInvitationService.resolveJoinAccount(TOKEN)).isEmpty();   // so LoginService asks to sign in

        assertThatThrownBy(() -> realInvitationService.confirmJoin(TOKEN, STRANGER, proof))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("no longer valid");
    }

    @Test
    void confirmJoinTwice_theSecondTapReturnsQuietly_eventhoughTheProofIsSpent() {
        // The first confirm spends the proof; the person's double tap arrives without one. The
        // ALREADY_JOINED quiet return has to run BEFORE the proof check, or a completed join is
        // answered with "verify the code first" for a membership it just activated.
        String proof = passTheCode();
        realInvitationService.confirmJoin(TOKEN, ADA, proof);
        assertThat(revived.getActivationTime()).isNotNull();

        assertThatCode(() -> realInvitationService.confirmJoin(TOKEN, ADA, proof)).doesNotThrowAnyException();
        verify(accountService).updateOne(revived);   // once — the second tap wrote nothing
    }

    @Test
    void aForgotPasswordLink_cannotJoin_anyoneIntoAnything() {
        // Someone signed in can mail themselves a reset link — an account long joined, a token still
        // pending. Read as a join link it lands on "already joined", past the proof check that guards
        // binding; it must read as no link at all.
        UserInvitation reset = pendingInvitation();
        reset.setPurpose(InvitationPurpose.PASSWORD_RESET);
        doAnswer(inv -> Optional.of(reset)).when(realInvitationService).searchOne(any(Filters.class));
        revived.setActivationTime(LocalDateTime.now().minusDays(30));
        revived.setStatus(AccountStatus.ACTIVE);

        assertThat(realInvitationService.inspectJoinToken(TOKEN).reason())
                .isEqualTo(io.softa.starter.user.dto.JoinEntry.Reason.LINK_INVALID);
        assertThatThrownBy(() -> realInvitationService.confirmJoin(TOKEN, STRANGER, "anything"))
                .isInstanceOf(BusinessException.class);
        verify(accountService, never()).updateOne(any(UserAccount.class));
    }
}
