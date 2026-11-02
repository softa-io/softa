package io.softa.starter.user.service.impl;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.context.UserInfo;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.service.CacheService;
import io.softa.framework.orm.service.TenantInfoService;
import io.softa.framework.web.session.SessionStore;
import io.softa.starter.user.constant.AccountErrorCodes;
import io.softa.starter.user.dto.AuthenticationResult;
import io.softa.starter.user.entity.UserAccount;
import io.softa.starter.user.entity.UserIdentity;
import io.softa.starter.user.enums.AccountStatus;
import io.softa.starter.user.service.ConsultantService;
import io.softa.starter.user.service.UserAccountService;
import io.softa.starter.user.service.UserIdentityService;
import io.softa.starter.user.service.UserProfileService;
import io.softa.starter.user.service.UserSessionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Consultant accounts sign in on the web only: on the APP no consultancy is offered or entered, by
 * code, by password, by picking a company or by any other route to a session. The web is unchanged.
 */
class ConsultantOnAppTest {

    private static final Long PROFILE = 7L;
    private static final Long CONSULTANCY = 100L;
    private static final Long EMPLOYMENT = 200L;
    private static final String EMAIL = "ada@consult.com";

    private final UserAccountService accountService = mock(UserAccountService.class);
    private final UserIdentityService identityService = mock(UserIdentityService.class);
    private final UserProfileService profileService = mock(UserProfileService.class);
    private final ConsultantService consultantService = mock(ConsultantService.class);
    private final CacheService cacheService = mock(CacheService.class);
    private final SessionStore sessionStore = mock(SessionStore.class);
    private final LoginServiceImpl loginService = new LoginServiceImpl();
    private final UserIdentity identity = new UserIdentity();

    ConsultantOnAppTest() {
        ReflectionTestUtils.setField(loginService, "accountService", accountService);
        ReflectionTestUtils.setField(loginService, "identityService", identityService);
        ReflectionTestUtils.setField(loginService, "profileService", profileService);
        ReflectionTestUtils.setField(loginService, "consultantService", consultantService);
        ReflectionTestUtils.setField(loginService, "cacheService", cacheService);
        ReflectionTestUtils.setField(loginService, "codeGuard", mock(VerificationCodeGuard.class));
        ReflectionTestUtils.setField(loginService, "history", mock(LoginHistoryRecorder.class));
        ReflectionTestUtils.setField(loginService, "sessionService", mock(UserSessionService.class));
        ReflectionTestUtils.setField(loginService, "sessionStore", sessionStore);
        TenantInfoService tenants = mock(TenantInfoService.class);
        when(tenants.isTenantActive(any())).thenReturn(true);
        ReflectionTestUtils.setField(loginService, "tenantInfoService", tenants);
        identity.setId(11L);
        identity.setProfileId(PROFILE);
        identity.setPassword("hash");
        when(identityService.findByLoginIdentifier(EMAIL)).thenReturn(Optional.of(identity));
        when(identityService.findByProfile(PROFILE)).thenReturn(Optional.of(identity));
        when(accountService.listAccountIdsOf(PROFILE)).thenReturn(List.of(CONSULTANCY));
        when(consultantService.isConsultant(PROFILE)).thenReturn(true);
        when(consultantService.grantStands(any(), any())).thenReturn(true);
        when(cacheService.get("login:preauth:tok")).thenReturn(PROFILE.toString());
        UserInfo info = new UserInfo();
        info.setUserId(CONSULTANCY);
        when(profileService.getUserInfo(anyLong())).thenReturn(info);
    }

    @AfterEach
    void unbindRequest() {
        ClientRequests.clear();
    }

    private UserAccount consultancy() {
        UserAccount account = new UserAccount();
        account.setId(CONSULTANCY);
        account.setTenantId(1L);
        account.setProfileId(PROFILE);
        account.setStatus(AccountStatus.ACTIVE);
        account.setConsultant(true);
        when(accountService.getById(CONSULTANCY)).thenReturn(Optional.of(account));
        return account;
    }

    private UserAccount employment() {
        UserAccount account = new UserAccount();
        account.setId(EMPLOYMENT);
        account.setTenantId(2L);
        account.setProfileId(PROFILE);
        account.setStatus(AccountStatus.ACTIVE);
        when(accountService.getById(EMPLOYMENT)).thenReturn(Optional.of(account));
        return account;
    }

    private static String codeOf(Throwable e) {
        return ((BusinessException) e).getErrorCode();
    }

    @Test
    void aConsultant_isRefusedOnTheApp_byCodeAndByPassword() {
        UserAccount consultancy = consultancy();
        when(accountService.listMembershipsOf(PROFILE)).thenReturn(List.of(consultancy));
        when(identityService.matchesPassword(identity, "pw")).thenReturn(true);
        ClientRequests.fromApp();

        assertThatThrownBy(() -> loginService.authenticateByCode(EMAIL, "123456"))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(AccountErrorCodes.CONSULTANT_APP_UNSUPPORTED))
                .hasMessage("Consultant accounts can only sign in on the web.");
        assertThatThrownBy(() -> loginService.authenticateByPassword(EMAIL, "pw"))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(AccountErrorCodes.CONSULTANT_APP_UNSUPPORTED));
    }

    @Test
    void theSameConsultant_onTheWeb_isUnaffected() {
        UserAccount consultancy = consultancy();
        when(accountService.listMembershipsOf(PROFILE)).thenReturn(List.of(consultancy));

        AuthenticationResult result = loginService.authenticateByCode(EMAIL, "123456");

        assertThat(result.isResolved()).isTrue();
        verify(profileService).getUserInfo(CONSULTANCY);
    }

    @Test
    void someoneWhoIsAlsoAnEmployee_seesOnlyTheEmploymentOnTheApp() {
        UserAccount consultancy = consultancy();
        UserAccount employment = employment();
        when(accountService.listMembershipsOf(PROFILE)).thenReturn(List.of(consultancy, employment));
        ClientRequests.fromApp();

        AuthenticationResult result = loginService.authenticateByCode(EMAIL, "123456");

        // Straight into the one company the APP offers.
        assertThat(result.isResolved()).isTrue();
        verify(profileService).getUserInfo(EMPLOYMENT);
        verify(profileService, never()).getUserInfo(CONSULTANCY);
        assertThat(loginService.listTenants("tok")).extracting(o -> o.accountId()).containsExactly(EMPLOYMENT);
    }

    @Test
    void pickingTheConsultancyOnTheApp_isRefusedAsSuch() {
        UserAccount consultancy = consultancy();
        UserAccount employment = employment();
        when(accountService.listMembershipsOf(PROFILE)).thenReturn(List.of(consultancy, employment));
        ClientRequests.fromApp();

        assertThatThrownBy(() -> loginService.selectTenant("tok", CONSULTANCY))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(AccountErrorCodes.CONSULTANT_APP_UNSUPPORTED));
        assertThatThrownBy(() -> loginService.switchTenant(EMPLOYMENT, CONSULTANCY))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(AccountErrorCodes.CONSULTANT_APP_UNSUPPORTED));
    }

    @Test
    void noSessionIntoAConsultancy_isIssuedToTheApp_whateverPickedIt() {
        consultancy();
        ClientRequests.fromApp();

        assertThatThrownBy(() -> loginService.generateSessionId(CONSULTANCY))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(AccountErrorCodes.CONSULTANT_APP_UNSUPPORTED));
        verify(sessionStore, never()).create(any(), any(), any(), any(), any(), any());
    }
}
