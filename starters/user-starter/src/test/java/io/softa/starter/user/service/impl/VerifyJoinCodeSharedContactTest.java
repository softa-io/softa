package io.softa.starter.user.service.impl;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.exception.BusinessException;
import io.softa.starter.user.dto.JoinVerification;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.entity.UserIdentity;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserIdentityService;
import io.softa.starter.user.service.UserInvitationService;
import io.softa.starter.user.service.UserProfileService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import io.softa.starter.user.service.ConsultantService;

/**
 * {@code verifyJoinCode} must refuse to identify an invitee from a SHARED work contact (finding #2).
 *
 * <p>Two employees on one work number, both invited: the first to join seeds it as their login
 * identifier; the second, verifying a code sent to the same number, would be resolved to the FIRST
 * person and bound to their account — signing in as them. The guard is that a contact used by more
 * than one account cannot identify anyone, so the /join flow stops and hands the bind to HR.
 *
 * <p>The live schema makes this concrete: {@code +6231314611320} is the work mobile of three
 * different people, {@code +6591234567} of two.
 */
class VerifyJoinCodeSharedContactTest {

    private static final String SHARED = "+6231314611320";
    private static final String TOKEN = "raw-token";

    private final UserInvitationService invitationService = mock(UserInvitationService.class);
    private final UserAccountService accountService = mock(UserAccountService.class);
    private final UserIdentityService identityService = mock(UserIdentityService.class);
    private final UserProfileService profileService = mock(UserProfileService.class);
    private final VerificationCodeGuard codeGuard = mock(VerificationCodeGuard.class);
    private final LoginServiceImpl loginService = new LoginServiceImpl();

    VerifyJoinCodeSharedContactTest() {
        ReflectionTestUtils.setField(loginService, "invitationService", invitationService);
        ReflectionTestUtils.setField(loginService, "consultantService",
                mock(ConsultantService.class));
        ReflectionTestUtils.setField(loginService, "accountService", accountService);
        ReflectionTestUtils.setField(loginService, "identityService", identityService);
        ReflectionTestUtils.setField(loginService, "profileService", profileService);
        ReflectionTestUtils.setField(loginService, "codeGuard", codeGuard);
        ReflectionTestUtils.setField(loginService, "proofGuard", mock(JoinProofGuard.class));
    }

    @Test
    void aSharedWorkContact_isRefused_beforeResolvingAnyPerson() {
        when(invitationService.resolveJoinChannel(TOKEN, "mobile")).thenReturn(SHARED);
        when(accountService.isWorkContactShared(SHARED)).thenReturn(true);

        assertThatThrownBy(() -> loginService.verifyJoinCode(TOKEN, "mobile", "123456"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("shared by more than one account");

        // Never got as far as picking a person or minting one — that is the whole point.
        verify(identityService, never()).findByLoginIdentifier(anyString());
        verify(profileService, never()).createPersonForJoin(anyString());
    }

    @Test
    void anExclusiveContact_resolvesNormally() {
        String personal = "alice@personal.com";
        when(invitationService.resolveJoinChannel(TOKEN, "email")).thenReturn(personal);
        when(accountService.isWorkContactShared(personal)).thenReturn(false);
        // The membership belongs to nobody yet, so the person is resolved by the address.
        when(invitationService.resolveJoinAccount(TOKEN)).thenReturn(Optional.of(new UserAccount()));
        UserIdentity alice = new UserIdentity();
        alice.setProfileId(7L);
        alice.setPassword("hash");
        when(identityService.findByLoginIdentifier(personal)).thenReturn(Optional.of(alice));

        JoinVerification result = loginService.verifyJoinCode(TOKEN, "email", "123456");

        assertThat(result.profileId()).isEqualTo(7L);
    }

    @Test
    void codeLogin_refusesASharedContact_too() {
        // The same hole on the login side: a code sent to a shared number lets any holder sign in
        // as whoever holds it as their login identifier.
        when(accountService.isWorkContactShared(SHARED)).thenReturn(true);

        assertThatThrownBy(() -> loginService.authenticateByCode(SHARED, "123456"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("shared by more than one account");
        verify(identityService, never()).findByLoginIdentifier(anyString());
    }

    @Test
    void codeReset_refusesASharedContact_too() {
        // And on reset: a shared number would otherwise let one holder set the password of another.
        when(accountService.isWorkContactShared(SHARED)).thenReturn(true);

        assertThatThrownBy(() -> loginService.resetPasswordByCode(SHARED, "123456", "N3w-Passw0rd"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("shared by more than one account");
        verify(identityService, never()).findByLoginIdentifier(anyString());
    }
}
