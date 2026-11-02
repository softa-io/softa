package io.softa.starter.user.service.impl;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.web.session.SessionRevokeReason;
import io.softa.starter.user.constant.AccountErrorCodes;
import io.softa.starter.user.dto.AuthenticationResult;
import io.softa.starter.user.dto.JoinContacts;
import io.softa.starter.user.dto.JoinPasswordResult;
import io.softa.starter.user.dto.JoinVerification;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.entity.UserIdentity;
import io.softa.starter.user.entity.UserSecurityLog;
import io.softa.starter.user.enums.AccountStatus;
import io.softa.starter.user.enums.SecurityAction;
import io.softa.starter.user.service.ConsultantService;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserIdentityService;
import io.softa.starter.user.service.UserInvitationService;
import io.softa.starter.user.service.UserProfileService;
import io.softa.starter.user.service.UserSecurityLogService;
import io.softa.starter.user.service.UserSessionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An Unbind &amp; Re-invite link: the person is already a member, so the flow is verify → set the
 * login password → done. Setting the password makes the account theirs and active; there is no
 * confirm-join step, and someone who already had a password resets it here.
 */
class RebindJoinTest {

    private static final String TOKEN = "rebind-token";
    private static final String MOBILE = "+6591234567";
    private static final Long ACCOUNT = 100L;
    private static final Long ADA = 7L;

    private final UserInvitationService invitationService = mock(UserInvitationService.class);
    private final UserIdentityService identityService = mock(UserIdentityService.class);
    private final UserSessionService sessionService = mock(UserSessionService.class);
    private final UserSecurityLogService securityLog = mock(UserSecurityLogService.class);
    private final JoinProofGuard proofGuard = mock(JoinProofGuard.class);
    private final LoginServiceImpl loginService = new LoginServiceImpl();
    private final UserAccount account = new UserAccount();
    private final UserIdentity ada = new UserIdentity();

    RebindJoinTest() {
        ReflectionTestUtils.setField(loginService, "invitationService", invitationService);
        ReflectionTestUtils.setField(loginService, "accountService", mock(UserAccountService.class));
        ReflectionTestUtils.setField(loginService, "identityService", identityService);
        ReflectionTestUtils.setField(loginService, "profileService", mock(UserProfileService.class));
        ReflectionTestUtils.setField(loginService, "proofGuard", proofGuard);
        ReflectionTestUtils.setField(loginService, "codeGuard", mock(VerificationCodeGuard.class));
        ReflectionTestUtils.setField(loginService, "consultantService", mock(ConsultantService.class));
        ReflectionTestUtils.setField(loginService, "history", mock(LoginHistoryRecorder.class));
        ReflectionTestUtils.setField(loginService, "sessionService", sessionService);
        ReflectionTestUtils.setField(loginService, "securityLog", securityLog);
        ReflectionTestUtils.setField(loginService, "mobileFormat", InvitationServiceTestSupport.mobileFormat());

        // Unbound: the membership has no person until this flow binds one.
        account.setId(ACCOUNT);
        account.setTenantId(1L);
        account.setStatus(AccountStatus.INVITED);
        account.setMobile(MOBILE);
        ada.setId(11L);
        ada.setProfileId(ADA);
        ada.setLoginMobile(MOBILE);
        ada.setPassword("hash");
        when(invitationService.resolveJoinAccount(TOKEN)).thenReturn(Optional.of(account));
        when(invitationService.resolveJoinChannel(TOKEN, "mobile")).thenReturn(MOBILE);
        when(invitationService.resolveJoinContacts(TOKEN)).thenReturn(new JoinContacts(null, MOBILE));
        when(identityService.findByLoginIdentifier(MOBILE)).thenReturn(Optional.of(ada));
        when(identityService.findByProfile(ADA)).thenReturn(Optional.of(ada));
        when(proofGuard.mint(any(), any(), any())).thenReturn("proof");
        when(securityLog.entry(any(), any(), any(), any())).thenReturn(new UserSecurityLog());
        when(proofGuard.channelOf("proof")).thenReturn("mobile");
    }

    @Test
    void aRebindLink_alwaysGoesOnToSetThePassword_evenForSomeoneWhoHasOne() {
        when(invitationService.isRebindLink(TOKEN)).thenReturn(true);

        JoinVerification result = loginService.verifyJoinCode(TOKEN, "mobile", "123456");

        assertThat(result.profileId()).isEqualTo(ADA);
        assertThat(result.nextStep()).isEqualTo(JoinVerification.NextStep.SET_PASSWORD);
        assertThat(result.proof()).isEqualTo("proof");
    }

    @Test
    void anOrdinaryInvitation_toSomeoneWithAPassword_stillGoesToConfirm() {
        when(invitationService.isRebindLink(TOKEN)).thenReturn(false);

        assertThat(loginService.verifyJoinCode(TOKEN, "mobile", "123456").nextStep())
                .isEqualTo(JoinVerification.NextStep.CONFIRM);
    }

    @Test
    void settingThePassword_onARebindLink_completesTheJoin_andEndsTheOldPasswordsSessions() {
        when(invitationService.isRebindLink(TOKEN)).thenReturn(true);

        JoinPasswordResult result = loginService.setJoinPassword(TOKEN, ADA, "N3w-Passw0rd!", "proof");

        assertThat(result.completed()).isTrue();
        // The contact just verified, in full, for the "done" page to hand to the APP.
        assertThat(result.prefill()).isEqualTo(new AuthenticationResult.JoinPrefill("mobile", MOBILE));
        verify(identityService).setPassword(11L, "N3w-Passw0rd!");
        // Bound and activated in the same step: no confirm screen follows.
        verify(invitationService).confirmJoin(TOKEN, ADA, "proof");
        verify(sessionService).revokePerson(ADA, null, SessionRevokeReason.PASSWORD_RESET);
        verify(securityLog).entry(eq(SecurityAction.PASSWORD_RESET), any(), eq(account), eq(ADA));
    }

    @Test
    void aFirstPassword_onARebindLink_completesTheJoin_withNothingToEnd() {
        when(invitationService.isRebindLink(TOKEN)).thenReturn(true);
        ada.setPassword(null);

        assertThat(loginService.setJoinPassword(TOKEN, ADA, "N3w-Passw0rd!", "proof").completed()).isTrue();

        verify(invitationService).confirmJoin(TOKEN, ADA, "proof");
        verify(sessionService, never()).revokePerson(any(), any(), any());
    }

    @Test
    void anOrdinaryInvitation_keepsItsConfirmStep_andRefusesToReplaceAPassword() {
        when(invitationService.isRebindLink(TOKEN)).thenReturn(false);

        assertThatThrownBy(() -> loginService.setJoinPassword(TOKEN, ADA, "N3w-Passw0rd!", "proof"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AccountErrorCodes.PASSWORD_ALREADY_SET);

        ada.setPassword(null);
        JoinPasswordResult result = loginService.setJoinPassword(TOKEN, ADA, "N3w-Passw0rd!", "proof");
        assertThat(result.completed()).isFalse();
        // Nothing to prefill yet: confirm joining hands it over.
        assertThat(result.prefill()).isNull();
        verify(invitationService, never()).confirmJoin(anyString(), any(), anyString());
    }
}
