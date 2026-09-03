package io.softa.starter.user.service.impl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.domain.Filters;
import io.softa.starter.user.dto.JoinContacts;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.entity.UserIdentity;
import io.softa.starter.user.entity.UserInvitation;
import io.softa.starter.user.entity.UserProfile;
import io.softa.starter.user.enums.AccountStatus;
import io.softa.starter.user.enums.InvitationStatus;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserIdentityService;
import io.softa.starter.user.service.UserInvitationService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The first-time invitee whose work contact HR typed with surrounding whitespace.
 *
 * <p>The identifier is seeded canonical (createPersonForJoin), the invitation keeps HR's spelling,
 * and the two are compared at setJoinPassword and again at confirmJoin. Compared raw, the person
 * passed the code and was then refused at both steps with "does not belong" — an invitation that
 * could never be accepted, for a spelling the person never saw. The three steps are run against
 * the real implementations end to end so that the seeding and the two ties cannot drift apart.
 */
class JoinContactSpellingTest {

    private static final String TOKEN = "raw-token";
    private static final String AS_HR_TYPED_IT = " Ada@Acme.com ";
    private static final Long ACCOUNT_ID = 100L;
    private static final Long TENANT = 1L;
    private static final Long PROFILE = 42L;

    private final UserIdentityService identityService = mock(UserIdentityService.class);
    private final UserAccountService accountService = mock(UserAccountService.class);
    private final UserInvitationService invitationService = mock(UserInvitationService.class);
    private final UserProfileServiceImpl profileService = spy(new UserProfileServiceImpl());
    private final LoginServiceImpl loginService = new LoginServiceImpl();
    /** Waved through: the proof is JoinProofTest's subject, the contact spelling is this one's. */
    private final JoinProofGuard proofGuard = mock(JoinProofGuard.class);
    private final UserInvitationServiceImpl realInvitationService = spy(new UserInvitationServiceImpl(
            accountService, identityService, mock(ApplicationEventPublisher.class), null, proofGuard,
            "http://localhost"));

    JoinContactSpellingTest() {
        ReflectionTestUtils.setField(profileService, "identityService", identityService);
        ReflectionTestUtils.setField(loginService, "invitationService", invitationService);
        ReflectionTestUtils.setField(loginService, "consultantService",
                mock(io.softa.starter.user.service.ConsultantService.class));
        ReflectionTestUtils.setField(loginService, "identityService", identityService);
        ReflectionTestUtils.setField(loginService, "proofGuard", proofGuard);
        doReturn(PROFILE).when(profileService).createOne(any(UserProfile.class));
    }

    @Test
    void aFirstTimeInvitee_whoseContactHRTypedWithWhitespace_canSetAPasswordAndConfirm() {
        // ① verifyJoinCode's last step: the person is minted from the address the code went to.
        profileService.createPersonForJoin(AS_HR_TYPED_IT);
        ArgumentCaptor<UserIdentity> seeded = ArgumentCaptor.forClass(UserIdentity.class);
        verify(identityService).createOne(seeded.capture());
        UserIdentity ada = seeded.getValue();
        ada.setId(11L);
        assertThat(ada.getLoginEmail()).isEqualTo("ada@acme.com");
        when(identityService.findByProfile(PROFILE)).thenReturn(Optional.of(ada));

        // The unbound row the invitation was issued for.
        UserAccount account = new UserAccount();
        account.setId(ACCOUNT_ID);
        account.setTenantId(TENANT);
        account.setStatus(AccountStatus.INVITED);
        account.setEmail(AS_HR_TYPED_IT);
        when(invitationService.resolveJoinAccount(TOKEN)).thenReturn(Optional.of(account));

        // ② setJoinPassword ties the person to the invitation, which still spells the contact as
        // HR did. Load-bearing: compared raw this threw "This link does not belong to that account."
        when(invitationService.resolveJoinContacts(TOKEN)).thenReturn(new JoinContacts(AS_HR_TYPED_IT, null));
        loginService.setJoinPassword(TOKEN, PROFILE, "Str0ng!Passw0rd", "proof");
        verify(identityService).setPassword(11L, "Str0ng!Passw0rd");

        // ③ confirmJoin makes the same tie for an unbound row, from the invitation's own columns.
        UserInvitation invitation = new UserInvitation();
        invitation.setId(55L);
        invitation.setUserId(ACCOUNT_ID);
        invitation.setStatus(InvitationStatus.PENDING);
        invitation.setEmail(AS_HR_TYPED_IT);
        invitation.setExpiresAt(LocalDateTime.now().plusDays(1));
        doReturn(Optional.of(invitation)).when(realInvitationService).searchOne(any(Filters.class));
        doReturn(true).when(realInvitationService).updateOne(any(UserInvitation.class));
        when(accountService.getById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(accountService.listMembershipsOf(any())).thenReturn(List.of());

        realInvitationService.confirmJoin(TOKEN, PROFILE, "proof");

        assertThat(account.getProfileId()).isEqualTo(PROFILE);
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        verify(accountService).updateOne(account);
    }
}
