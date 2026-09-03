package io.softa.starter.user.service.impl;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.context.UserInfo;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.service.TenantInfoService;
import io.softa.starter.user.dto.AuthenticationResult;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.entity.UserIdentity;
import io.softa.starter.user.enums.AccountStatus;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserIdentityService;
import io.softa.starter.user.service.UserProfileService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Login resolves the PERSON by a login identifier rather than the company account by its work
 * email, and that is the WHOLE lookup — there is no fallback to the work contact. One existed while
 * identifiers were being introduced; it required {@code UserAccount.email} to stay globally unique,
 * which is precisely what blocked narrowing that index to {@code (tenantId, email)}.
 *
 * <p>What is load-bearing here is the absence of an account-existence oracle: "no such identifier"
 * and "wrong password" must be indistinguishable from outside, or an anonymous endpoint tells a
 * stranger which addresses are registered.
 */
class IdentifierResolutionTest {

    private static final Long PROFILE = 7L;
    private static final Long ACCOUNT = 100L;
    private static final String EMAIL = "alice@acme.com";
    private static final String PASSWORD = "S0me-Passw0rd";
    private static final String FAILED_LOGIN = "Incorrect account or password.";

    private final UserAccountService accountService = mock(UserAccountService.class);
    private final UserIdentityService identityService = mock(UserIdentityService.class);
    private final UserProfileService profileService = mock(UserProfileService.class);
    private final TenantInfoService tenantInfoService = mock(TenantInfoService.class);
    private final LoginServiceImpl loginService = new LoginServiceImpl();

    IdentifierResolutionTest() {
        ReflectionTestUtils.setField(loginService, "accountService", accountService);
        ReflectionTestUtils.setField(loginService, "consultantService",
                mock(io.softa.starter.user.service.ConsultantService.class));
        ReflectionTestUtils.setField(loginService, "identityService", identityService);
        ReflectionTestUtils.setField(loginService, "profileService", profileService);
        ReflectionTestUtils.setField(loginService, "tenantInfoService", tenantInfoService);
    }

    private static UserIdentity identity(String loginEmail, String password) {
        UserIdentity identity = new UserIdentity();
        identity.setId(11L);
        identity.setProfileId(PROFILE);
        identity.setLoginEmail(loginEmail);
        identity.setPassword(password);
        return identity;
    }

    private static UserAccount membership() {
        UserAccount account = new UserAccount();
        account.setId(ACCOUNT);
        account.setTenantId(1L);
        account.setProfileId(PROFILE);
        account.setStatus(AccountStatus.ACTIVE);
        account.setEmail(EMAIL);
        return account;
    }

    /** One enterable company, so authentication resolves straight to a session payload. */
    private void givenOneCompany() {
        when(accountService.listMembershipsOf(PROFILE)).thenReturn(List.of(membership()));
        when(profileService.getUserInfo(ACCOUNT)).thenReturn(new UserInfo());
    }

    @Test
    void identifierOnFile_resolvesDirectly_withoutTouchingTheAccount() {
        UserIdentity person = identity(EMAIL, "hash");
        when(identityService.findByLoginIdentifier(EMAIL)).thenReturn(Optional.of(person));
        when(identityService.matchesPassword(person, PASSWORD)).thenReturn(true);
        givenOneCompany();

        AuthenticationResult result = loginService.authenticateByPassword(EMAIL, PASSWORD);

        assertThat(result.isResolved()).isTrue();
        assertThat(result.profileId()).isEqualTo(PROFILE);
    }

    @Test
    void anUnknownIdentifier_isRefused_withNoFallbackToTheWorkContact() {
        // There is deliberately no second lookup by the ACCOUNT's work contact. One existed while
        // identifiers were being introduced, to heal rows created before the seeding; keeping it
        // would require UserAccount.email to stay globally unique, which is exactly what blocks
        // narrowing that index to (tenantId, email).
        when(identityService.findByLoginIdentifier(EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> loginService.authenticateByPassword(EMAIL, PASSWORD))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(FAILED_LOGIN);
        verify(accountService, never()).getUserByEmail(anyString());
    }

    @Test
    void unknownIdentifier_andWrongPassword_areIndistinguishable() {
        when(identityService.findByLoginIdentifier("nobody@acme.com")).thenReturn(Optional.empty());

        UserIdentity person = identity(EMAIL, "hash");
        when(identityService.findByLoginIdentifier(EMAIL)).thenReturn(Optional.of(person));
        when(identityService.matchesPassword(person, "wrong")).thenReturn(false);

        assertThatThrownBy(() -> loginService.authenticateByPassword("nobody@acme.com", PASSWORD))
                .hasMessageContaining(FAILED_LOGIN);
        assertThatThrownBy(() -> loginService.authenticateByPassword(EMAIL, "wrong"))
                .hasMessageContaining(FAILED_LOGIN);
    }

    @Test
    void passwordLessPerson_isToldToSetOne_ratherThanBeingLetInSilently() {
        UserIdentity person = identity(EMAIL, null);
        when(identityService.findByLoginIdentifier(EMAIL)).thenReturn(Optional.of(person));
        when(identityService.findByProfile(PROFILE)).thenReturn(Optional.of(person));
        when(identityService.matchesPassword(person, PASSWORD)).thenReturn(true);
        givenOneCompany();

        assertThat(loginService.authenticateByPassword(EMAIL, PASSWORD).mustSetPassword()).isTrue();
        assertThat(loginService.mustSetPassword(PROFILE)).isTrue();
    }
}
