package io.softa.starter.user.service.impl;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.service.CacheService;
import io.softa.starter.user.dto.AuthenticationResult;
import io.softa.starter.user.dto.JoinContacts;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.entity.UserIdentity;
import io.softa.starter.user.enums.AccountStatus;
import io.softa.starter.user.service.ConsultantService;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserIdentityService;
import io.softa.starter.user.service.UserInvitationService;
import io.softa.starter.user.service.UserProfileService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What confirming a join hands back: a session only for a join that just happened to a person who
 * can sign in. A re-entrant confirm carries no proof, so nothing; a password still owed comes first.
 */
class JoinConfirmEntryTest {

    private static final String TOKEN = "raw-token";
    private static final Long ACCOUNT = 100L;
    private static final Long ADA = 7L;

    private final UserInvitationService invitationService = mock(UserInvitationService.class);
    private final UserAccountService accountService = mock(UserAccountService.class);
    private final UserIdentityService identityService = mock(UserIdentityService.class);
    private final UserProfileService profileService = mock(UserProfileService.class);
    private final JoinProofGuard proofGuard = mock(JoinProofGuard.class);
    private final LoginServiceImpl loginService = new LoginServiceImpl();
    private final UserIdentity ada = new UserIdentity();

    JoinConfirmEntryTest() {
        ReflectionTestUtils.setField(loginService, "invitationService", invitationService);
        ReflectionTestUtils.setField(loginService, "accountService", accountService);
        ReflectionTestUtils.setField(loginService, "identityService", identityService);
        ReflectionTestUtils.setField(loginService, "profileService", profileService);
        ReflectionTestUtils.setField(loginService, "proofGuard", proofGuard);
        ReflectionTestUtils.setField(loginService, "cacheService", mock(CacheService.class));
        ReflectionTestUtils.setField(loginService, "consultantService", mock(ConsultantService.class));
        ReflectionTestUtils.setField(loginService, "history", mock(LoginHistoryRecorder.class));
        ada.setId(11L);
        ada.setProfileId(ADA);
        when(identityService.findByProfile(ADA)).thenReturn(Optional.of(ada));
    }

    private UserAccount joining() {
        UserAccount account = new UserAccount();
        account.setId(ACCOUNT);
        account.setTenantId(1L);
        account.setStatus(AccountStatus.ACTIVE);
        account.setProfileId(ADA);
        when(invitationService.resolveJoinAccount(TOKEN)).thenReturn(Optional.of(account));
        when(invitationService.resolveJoinContacts(TOKEN)).thenReturn(new JoinContacts("ada@acme.com", "+6591234567"));
        when(proofGuard.channelOf("proof")).thenReturn("mobile");
        when(accountService.listMembershipsOf(ADA)).thenReturn(List.of(account));
        return account;
    }

    @Test
    void aReEntrantConfirm_handsBackNoSessionAndNoToken() {
        // The link is spent, so nothing names the person but the id the caller sent.
        when(invitationService.resolveJoinAccount(TOKEN)).thenReturn(Optional.empty());

        AuthenticationResult result = loginService.confirmJoin(TOKEN, ADA, "whatever");

        assertThat(result.signInRequired()).isTrue();
        assertThat(result.isResolved()).isFalse();
        assertThat(result.authToken()).isNull();
        verify(profileService, never()).getUserInfo(anyLong());
    }

    @Test
    void aPasswordStillOwed_comesBeforeTheSession() {
        joining();

        AuthenticationResult result = loginService.confirmJoin(TOKEN, ADA, "proof");

        assertThat(result.isResolved()).isFalse();
        assertThat(result.mustSetPassword()).isTrue();
        assertThat(result.authToken()).isNotBlank();
        assertThat(result.autoEnterAccountId()).isEqualTo(ACCOUNT);
        assertThat(result.prefill()).isEqualTo(new AuthenticationResult.JoinPrefill("mobile", "+6591234567"));
        verify(profileService, never()).getUserInfo(anyLong());
    }

    @Test
    void someoneWhoCanSignIn_goesStraightIn() {
        joining();
        ada.setPassword("hash");
        when(profileService.getUserInfo(ACCOUNT)).thenReturn(new io.softa.framework.base.context.UserInfo());

        AuthenticationResult result = loginService.confirmJoin(TOKEN, ADA, "proof");

        assertThat(result.isResolved()).isTrue();
        assertThat(result.mustSetPassword()).isFalse();
    }
}
