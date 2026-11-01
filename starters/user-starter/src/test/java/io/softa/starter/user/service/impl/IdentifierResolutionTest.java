package io.softa.starter.user.service.impl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.context.UserInfo;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.service.CacheService;
import io.softa.framework.orm.service.TenantInfoService;
import io.softa.starter.user.dto.AuthenticationResult;
import io.softa.starter.user.dto.MembershipOption;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.entity.UserIdentity;
import io.softa.starter.user.enums.AccountStatus;
import io.softa.starter.user.enums.LoginEvent;
import io.softa.starter.user.service.ConsultantService;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserIdentityService;
import io.softa.starter.user.service.UserProfileService;
import io.softa.starter.user.service.UserSessionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Who a login identifier names, and where the entry rule sends them.
 */
class IdentifierResolutionTest {

    private static final Long PROFILE = 7L;
    private static final Long ACCOUNT = 100L;
    private static final Long OTHER = 200L;
    private static final String EMAIL = "alice@acme.com";
    private static final String PASSWORD = "S0me-Passw0rd";

    private final UserAccountService accountService = mock(UserAccountService.class);
    private final UserIdentityService identityService = mock(UserIdentityService.class);
    private final UserProfileService profileService = mock(UserProfileService.class);
    private final TenantInfoService tenantInfoService = mock(TenantInfoService.class);
    private final CacheService cacheService = mock(CacheService.class);
    private final LoginHistoryRecorder history = mock(LoginHistoryRecorder.class);
    private final LoginServiceImpl loginService = new LoginServiceImpl();

    IdentifierResolutionTest() {
        ReflectionTestUtils.setField(loginService, "sessionService", mock(UserSessionService.class));
        ReflectionTestUtils.setField(loginService, "accountService", accountService);
        ReflectionTestUtils.setField(loginService, "cacheService", cacheService);
        ReflectionTestUtils.setField(loginService, "history", history);
        ReflectionTestUtils.setField(loginService, "consultantService", mock(ConsultantService.class));
        ReflectionTestUtils.setField(loginService, "identityService", identityService);
        ReflectionTestUtils.setField(loginService, "profileService", profileService);
        ReflectionTestUtils.setField(loginService, "tenantInfoService", tenantInfoService);
        ReflectionTestUtils.setField(loginService, "codeGuard", mock(VerificationCodeGuard.class));
        when(accountService.listAccountIdsOf(PROFILE)).thenReturn(List.of(ACCOUNT));
    }

    private UserIdentity person(String password) {
        UserIdentity identity = new UserIdentity();
        identity.setId(11L);
        identity.setProfileId(PROFILE);
        identity.setPassword(password);
        when(identityService.findByLoginIdentifier(EMAIL)).thenReturn(Optional.of(identity));
        when(identityService.findByProfile(PROFILE)).thenReturn(Optional.of(identity));
        when(identityService.matchesPassword(identity, PASSWORD)).thenReturn(true);
        return identity;
    }

    private static UserAccount membership(Long id, AccountStatus status) {
        UserAccount account = new UserAccount();
        account.setId(id);
        account.setTenantId(id / 100);
        account.setProfileId(PROFILE);
        account.setStatus(status);
        return account;
    }

    private void givenMemberships(UserAccount... accounts) {
        when(accountService.listMembershipsOf(PROFILE)).thenReturn(List.of(accounts));
        for (UserAccount account : accounts) {
            UserInfo info = new UserInfo();
            info.setUserId(account.getId());
            when(profileService.getUserInfo(account.getId())).thenReturn(info);
        }
    }

    private static String errorCodeOf(Throwable e) {
        return ((BusinessException) e).getErrorCode();
    }

    @Test
    void oneEnterableCompany_isEnteredDirectly_andTheSignInIsRecorded() {
        person("hash");
        givenMemberships(membership(ACCOUNT, AccountStatus.ACTIVE));

        AuthenticationResult result = loginService.authenticateByPassword(EMAIL, PASSWORD);

        assertThat(result.isResolved()).isTrue();
        assertThat(result.autoEnterAccountId()).isEqualTo(ACCOUNT);
        verify(history).record(eq(LoginEvent.LOGIN_SUCCESS), eq(PROFILE), eq(ACCOUNT), any(), any(), any(), isNull());
    }

    @Test
    void anUnknownNumber_isAccountNotFound_whenSendingAndWhenSigningIn() {
        when(identityService.findByLoginIdentifier(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> loginService.sendMobileCode("+6591234567"))
                .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo("ACCOUNT_NOT_FOUND"));
        assertThatThrownBy(() -> loginService.authenticateByPassword(EMAIL, PASSWORD))
                .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo("ACCOUNT_NOT_FOUND"));
        assertThatThrownBy(() -> loginService.authenticateByCode(EMAIL, "123456"))
                .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void aPersonNoAccountWasEverCreatedFor_isAccountNotFound_too() {
        person("hash");
        when(accountService.listAccountIdsOf(PROFILE)).thenReturn(List.of());

        assertThatThrownBy(() -> loginService.sendEmailCode(EMAIL))
                .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void theCompanyEnteredLastTime_isEnteredDirectly_amongSeveral() {
        UserIdentity identity = person("hash");
        identity.setLastAccountId(OTHER);
        givenMemberships(membership(ACCOUNT, AccountStatus.ACTIVE), membership(OTHER, AccountStatus.ACTIVE));

        AuthenticationResult result = loginService.authenticateByPassword(EMAIL, PASSWORD);

        assertThat(result.isResolved()).isTrue();
        assertThat(result.autoEnterAccountId()).isEqualTo(OTHER);
    }

    @Test
    void severalCompanies_andNoUsableLastOne_showThePicker_markingTheLastOne() {
        UserIdentity identity = person("hash");
        identity.setLastAccountId(OTHER);
        givenMemberships(membership(ACCOUNT, AccountStatus.ACTIVE), membership(OTHER, AccountStatus.FROZEN));

        AuthenticationResult result = loginService.authenticateByPassword(EMAIL, PASSWORD);

        assertThat(result.isResolved()).isFalse();
        assertThat(result.autoEnterAccountId()).isNull();
        assertThat(result.authToken()).isNotBlank();
        assertThat(result.tenants()).filteredOn(MembershipOption::lastUsed)
                .singleElement().extracting(MembershipOption::accountId).isEqualTo(OTHER);
    }

    @Test
    void aLockedPerson_signingInByCode_seesLocked_andCanEnter() {
        UserIdentity identity = person("hash");
        identity.setPasswordLockedUntil(LocalDateTime.now().plusMinutes(10));
        when(identityService.isPasswordLocked(identity)).thenReturn(true);
        givenMemberships(membership(ACCOUNT, AccountStatus.ACTIVE), membership(OTHER, AccountStatus.ACTIVE));

        AuthenticationResult result = loginService.authenticateByCode(EMAIL, "123456");

        assertThat(result.tenants()).allSatisfy(option -> {
            assertThat(option.status()).isEqualTo(AccountStatus.LOCKED);
            assertThat(option.selectable()).isTrue();
        });
    }

    @Test
    void everyMembershipFrozen_isDeactivated_withoutASession() {
        person("hash");
        givenMemberships(membership(ACCOUNT, AccountStatus.FROZEN));

        AuthenticationResult result = loginService.authenticateByPassword(EMAIL, PASSWORD);

        assertThat(result.isResolved()).isFalse();
        assertThat(result.noEnterableReason()).isEqualTo(AuthenticationResult.NoEnterableReason.DEACTIVATED);
    }

    @Test
    void onlyUnconfirmedInvitations_isNotJoined() {
        person("hash");
        // The picker lists only Active / Locked / Frozen, so the invitation never shows as an option.
        when(accountService.listMembershipsOf(PROFILE)).thenReturn(List.of(membership(ACCOUNT, AccountStatus.INVITED)));

        AuthenticationResult result = loginService.authenticateByPassword(EMAIL, PASSWORD);

        assertThat(result.tenants()).isEmpty();
        assertThat(result.noEnterableReason()).isEqualTo(AuthenticationResult.NoEnterableReason.NOT_JOINED);
    }

    @AfterEach
    void unbindRequest() {
        ClientRequests.clear();
    }

    @Test
    void aPasswordOwed_comesFirst_evenWhenTheEntryRuleKnowsWhereToGo() {
        ClientRequests.fromApp();
        person(null);
        givenMemberships(membership(ACCOUNT, AccountStatus.ACTIVE));

        AuthenticationResult result = loginService.authenticateByCode(EMAIL, "123456");

        assertThat(result.mustSetPassword()).isTrue();
        assertThat(result.isResolved()).isFalse();
        assertThat(result.autoEnterAccountId()).isEqualTo(ACCOUNT);
        assertThat(result.authToken()).isNotBlank();
        verify(cacheService).save(anyString(), startsWith(PROFILE + ":"), anyInt());
    }

    @Test
    void aPasswordOwed_onWebToo_comesBeforeAnySession() {
        // No header-based exception any more: Web sets the first password with the sign-in token
        // as the APP does.
        person(null);
        givenMemberships(membership(ACCOUNT, AccountStatus.ACTIVE));

        AuthenticationResult result = loginService.authenticateByCode(EMAIL, "123456");

        assertThat(result.isResolved()).isFalse();
        assertThat(result.mustSetPassword()).isTrue();
        assertThat(result.authToken()).isNotBlank();
    }

    @Test
    void aPasswordOwed_withNowhereToEnter_stillGetsTheTokenToSetIt() {
        // Every company frozen: nothing to pick, but setting the password is a step the person can
        // still take, and without the token they could not.
        person(null);
        givenMemberships(membership(ACCOUNT, AccountStatus.FROZEN));

        AuthenticationResult result = loginService.authenticateByCode(EMAIL, "123456");

        assertThat(result.mustSetPassword()).isTrue();
        assertThat(result.noEnterableReason()).isEqualTo(AuthenticationResult.NoEnterableReason.DEACTIVATED);
        assertThat(result.authToken()).isNotBlank();

        when(cacheService.get("login:preauth:tok")).thenReturn(PROFILE.toString());
        AuthenticationResult after = loginService.setFirstPassword("tok", "N3w-Passw0rd!");

        verify(identityService).setPassword(11L, "N3w-Passw0rd!");
        assertThat(after.isResolved()).isFalse();
        assertThat(after.mustSetPassword()).isFalse();
        assertThat(after.noEnterableReason()).isEqualTo(AuthenticationResult.NoEnterableReason.DEACTIVATED);
    }

    @Test
    void anUnknownIdentifier_isAFailedSignIn_inTheHistory() {
        when(identityService.findByLoginIdentifier(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> loginService.authenticateByPassword("nobody@acme.com", PASSWORD))
                .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo("ACCOUNT_NOT_FOUND"));
        verify(history).record(eq(LoginEvent.LOGIN_FAILED), isNull(), any(), any(), any(), any(), eq("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void misses_areCountedPerAddress_andTooManyStopTheLookup() {
        // A miss costs nothing against the per-identifier send limits, so walking a number range
        // has to be bounded by who is asking.
        ClientRequests.fromAddress("203.0.113.9");
        when(identityService.findByLoginIdentifier(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> loginService.sendMobileCode("+6590000001"))
                .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo("ACCOUNT_NOT_FOUND"));
        verify(cacheService).increment(eq("login:unknown-identifier:203.0.113.9"), anyLong());

        when(cacheService.get("login:unknown-identifier:203.0.113.9"))
                .thenReturn(String.valueOf(LoginServiceImpl.UNKNOWN_LOOKUPS_PER_HOUR));
        assertThatThrownBy(() -> loginService.sendMobileCode("+6590000002"))
                .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo("TOO_MANY_REQUESTS"));
        verify(identityService, never()).findByLoginIdentifier("+6590000002");
    }

    @Test
    void setFirstPassword_usesTheSignInToken_thenEnters() {
        UserIdentity identity = person(null);
        givenMemberships(membership(ACCOUNT, AccountStatus.ACTIVE));
        when(cacheService.get("login:preauth:tok")).thenReturn(PROFILE.toString());

        AuthenticationResult result = loginService.setFirstPassword("tok", "N3w-Passw0rd!");

        verify(identityService).setPassword(identity.getId(), "N3w-Passw0rd!");
        verify(cacheService).clear("login:preauth:tok");
        verify(history).record(eq(LoginEvent.PASSWORD_SET), eq(PROFILE), any(), any(), any(), any(), any());
        assertThat(result.isResolved()).isTrue();
        assertThat(result.autoEnterAccountId()).isEqualTo(ACCOUNT);
    }

    @Test
    void setFirstPassword_refusesAPersonWhoAlreadyHasOne() {
        person("hash");
        when(cacheService.get("login:preauth:tok")).thenReturn(PROFILE.toString());

        assertThatThrownBy(() -> loginService.setFirstPassword("tok", "N3w-Passw0rd!"))
                .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo("PASSWORD_ALREADY_SET"));
        verify(identityService, never()).setPassword(any(Long.class), anyString());
    }

    @Test
    void setFirstPassword_withoutALiveToken_isRefused() {
        when(cacheService.get("login:preauth:gone")).thenReturn(null);

        assertThatThrownBy(() -> loginService.setFirstPassword("gone", "N3w-Passw0rd!"))
                .satisfies(e -> assertThat(errorCodeOf(e)).isEqualTo("AUTH_TOKEN_EXPIRED"));
    }
}
